package com.example.accessagent;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.accessibilityservice.AccessibilityService.ScreenshotResult;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.GZIPOutputStream;

/**
 * Access Agent v4.1: UI observation + scored target resolution + actions + gestures +
 * verification/retry + batch + wait_for + goal planning over a loopback JSON-lines protocol.
 *
 * v4.1 fixes vs v4:
 * - (critical) works on real devices: the manifest now declares INTERNET permission;
 *   bind failures are logged and toasted instead of being swallowed silently.
 * - new publicObserveTree(): GoalEngine now walks the FULL node tree
 *   (v4 walked a flat root node with no children and never found targets).
 * - node(): getStateDescription() guarded by SDK >= 30 (crashed on API 26-29).
 * - verifiedAction(): verification only runs when verify_target is present,
 *   so verify:true alone no longer re-clicks the target on every retry.
 * - accept loop no longer hot-spins when accept() fails repeatedly.
 * - screenshot: HardwareBuffer is closed in finally (no leak on error paths).
 */
public class AgentAccessibilityService extends AccessibilityService {
    private static final String TAG = "AccessAgent";
    public static final int PORT = 8765;
    private static volatile AgentAccessibilityService instance;
    private ServerSocket server;
    private ExecutorService pool;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile JSONObject lastEvent = new JSONObject();
    private volatile long eventSeq = 0;
    private GoalEngine goalEngine;

    public static AgentAccessibilityService getInstance() { return instance; }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        goalEngine = new GoalEngine(this);
        pool = Executors.newCachedThreadPool();
        Log.i(TAG, "service connected, starting server on 127.0.0.1:" + PORT);
        Toast.makeText(this, "Access Agent listening on 127.0.0.1:" + PORT, Toast.LENGTH_SHORT).show();
        startServer();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        try {
            JSONObject x = new JSONObject();
            x.put("seq", ++eventSeq);
            x.put("type", AccessibilityEvent.eventTypeToString(e.getEventType()));
            x.put("typeId", e.getEventType());
            x.put("package", safe(e.getPackageName()));
            x.put("class", safe(e.getClassName()));
            x.put("text", eventText(e));
            x.put("timestamp", e.getEventTime());
            lastEvent = x;
        } catch (Exception ignored) {}
    }

    @Override public void onInterrupt() {}

    @Override public boolean onUnbind(android.content.Intent intent) {
        Log.i(TAG, "service unbinding, stopping server");
        stopServer(); instance = null; return super.onUnbind(intent);
    }

    private String safe(CharSequence s) { return s == null ? "" : s.toString(); }
    private JSONArray eventText(AccessibilityEvent e) throws Exception {
        JSONArray a = new JSONArray(); for (CharSequence s : e.getText()) a.put(safe(s)); return a;
    }

    private void startServer() {
        pool.submit(() -> {
            try {
                server = new ServerSocket(PORT, 32, InetAddress.getByName("127.0.0.1"));
                Log.i(TAG, "listening on 127.0.0.1:" + PORT);
                int failures = 0;
                while (!server.isClosed()) {
                    Socket s;
                    try {
                        s = server.accept();
                    } catch (Exception acceptError) {
                        if (server.isClosed()) break;
                        if (++failures > 50) { Log.e(TAG, "accept() failing repeatedly, giving up", acceptError); break; }
                        try { Thread.sleep(50); } catch (InterruptedException ie) { break; }
                        continue;
                    }
                    failures = 0;
                    final Socket cs = s;
                    pool.submit(() -> handle(cs));
                }
            } catch (IOException e) {
                Log.e(TAG, "server failed to start (check INTERNET permission / port already in use)", e);
                main.post(() -> Toast.makeText(this, "Agent server failed: " + e, Toast.LENGTH_LONG).show());
            }
        });
    }

    private void stopServer() {
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        if (pool != null) pool.shutdownNow();
    }

    private void handle(Socket socket) {
        if (socket == null) return;
        try (Socket s = socket) {
            s.setSoTimeout(15000);
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String line = r.readLine();
            JSONObject req = new JSONObject(line == null ? "{}" : line);
            JSONObject out = execute(req);
            BufferedWriter w = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
            w.write(out.toString()); w.write("\n"); w.flush();
        } catch (Exception ignored) {}
    }

    private JSONObject execute(JSONObject r) {
        JSONObject o = new JSONObject();
        try {
            String a = r.optString("action", "observe").toLowerCase(Locale.US);
            switch (a) {
                case "ping": return o.put("ok", true).put("service", "access-agent-v4.1").put("api", android.os.Build.VERSION.SDK_INT);
                case "observe": return o.put("ok", true).put("ui", observe());
                case "observe_tree": return o.put("ok", true).put("ui", publicObserveTree());
                case "goal": return goal(r);
                case "state": return state();
                case "tree": return o.put("ok", true).put("tree", rootTree());
                case "windows": return o.put("ok", true).put("windows", windows());
                case "event": return o.put("ok", true).put("event", lastEvent).put("seq", eventSeq);
                case "find": return o.put("ok", true).put("matches", find(r));
                case "click": return verifiedAction(r, AccessibilityNodeInfo.ACTION_CLICK);
                case "long_click": return verifiedAction(r, AccessibilityNodeInfo.ACTION_LONG_CLICK);
                case "focus": return actionResult(r, AccessibilityNodeInfo.ACTION_FOCUS);
                case "clear_focus": return actionResult(r, AccessibilityNodeInfo.ACTION_CLEAR_FOCUS);
                case "select": return actionResult(r, AccessibilityNodeInfo.ACTION_SELECT);
                case "clear_selection": return actionResult(r, AccessibilityNodeInfo.ACTION_CLEAR_SELECTION);
                case "copy": return actionResult(r, AccessibilityNodeInfo.ACTION_COPY);
                case "cut": return actionResult(r, AccessibilityNodeInfo.ACTION_CUT);
                case "paste": return actionResult(r, AccessibilityNodeInfo.ACTION_PASTE);
                case "expand": return actionResult(r, AccessibilityNodeInfo.ACTION_EXPAND);
                case "collapse": return actionResult(r, AccessibilityNodeInfo.ACTION_COLLAPSE);
                case "dismiss": return actionResult(r, AccessibilityNodeInfo.ACTION_DISMISS);
                case "scroll_forward": return actionResult(r, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                case "scroll_backward": return actionResult(r, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
                case "type": return setText(r, r.optString("value", ""));
                case "tap": return gestureResult("tap", tap(r));
                case "double_tap": return gestureResult("double_tap", doubleTap(r));
                case "long_press": return gestureResult("long_press", longPress(r));
                case "swipe": return gestureResult("swipe", swipe(r));
                case "drag": return gestureResult("drag", swipe(r));
                case "back": return global("back", GLOBAL_ACTION_BACK);
                case "home": return global("home", GLOBAL_ACTION_HOME);
                case "recents": return global("recents", GLOBAL_ACTION_RECENTS);
                case "notifications": return global("notifications", GLOBAL_ACTION_NOTIFICATIONS);
                case "quick_settings": return global("quick_settings", GLOBAL_ACTION_QUICK_SETTINGS);
                case "power_dialog": return global("power_dialog", GLOBAL_ACTION_POWER_DIALOG);
                case "lock_screen": return global("lock_screen", GLOBAL_ACTION_LOCK_SCREEN);
                case "screenshot": return screenshot();
                case "wait": Thread.sleep(clamp(r.optLong("ms", 300), 0, 10000)); return o.put("ok", true).put("waitedMs", r.optLong("ms",300));
                case "wait_for": return waitFor(r);
                case "batch": return batch(r);
                default: return o.put("ok", false).put("error", "unknown_action").put("action", a);
            }
        } catch (Exception e) {
            try { return o.put("ok", false).put("error", e.toString()); } catch (Exception ignored) { return o; }
        }
    }

    public JSONObject executeExternal(JSONObject r) { return execute(r); }
    public JSONObject publicObserve() throws Exception { return observe(); }

    /** Full serialized node tree (root node + children). GoalEngine needs the
     *  children; observe() only returns the flat root node. */
    public JSONObject publicObserveTree() throws Exception {
        AccessibilityNodeInfo n = root();
        JSONObject o = new JSONObject();
        o.put("package", n == null ? "" : safe(n.getPackageName()));
        o.put("class", n == null ? "" : safe(n.getClassName()));
        o.put("hasRoot", n != null);
        o.put("eventSeq", eventSeq);
        if (n != null) o.put("root", nodeTree(n));
        return o;
    }

    private JSONObject goal(JSONObject r) throws Exception {
        String g=r.optString("goal","");
        int max=(int)clamp(r.optLong("max_steps",12),1,30);
        long delay=clamp(r.optLong("step_delay_ms",350),0,2000);
        return goalEngine.run(g,max,delay);
    }

    private JSONObject state() throws Exception {
        JSONObject x=new JSONObject();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        x.put("package", root == null ? "" : safe(root.getPackageName()));
        x.put("class", root == null ? "" : safe(root.getClassName()));
        x.put("eventSeq", eventSeq);
        x.put("lastEvent", lastEvent);
        x.put("uptimeMs", android.os.SystemClock.uptimeMillis());
        return new JSONObject().put("ok",true).put("state",x);
    }

    private JSONObject global(String name, int action) throws Exception {
        boolean ok = performGlobalAction(action);
        return new JSONObject().put("ok", ok).put("action", name);
    }

    private JSONObject gestureResult(String action, boolean ok) throws Exception {
        return new JSONObject().put("ok", ok).put("action", action);
    }

    private JSONObject actionResult(JSONObject r, int action) throws Exception {
        AccessibilityNodeInfo n = resolve(root(), r);
        if (n == null) return new JSONObject().put("ok", false).put("error", "target_not_found");
        boolean ok = n.performAction(action);
        return new JSONObject().put("ok", ok).put("action", r.optString("action")).put("target", node(n));
    }

    private JSONObject verifiedAction(JSONObject r, int action) throws Exception {
        int retries = (int) clamp(r.optLong("retries", 2), 0, 5);
        long settle = clamp(r.optLong("settle_ms", 250), 0, 3000);
        // v4.1 FIX: verify only makes sense with a verify_target; without it the
        // old code re-clicked the target on every retry when verify:true.
        boolean doVerify = r.optBoolean("verify", false) && r.has("verify_target");
        JSONObject last = new JSONObject().put("ok", false).put("error", "target_not_found");
        for (int attempt = 0; attempt <= retries; attempt++) {
            AccessibilityNodeInfo n = resolve(root(), r);
            if (n == null) { Thread.sleep(settle); continue; }
            boolean ok = n.performAction(action);
            last = new JSONObject().put("ok", ok).put("action", r.optString("action")).put("attempt", attempt + 1).put("target", node(n));
            if (!ok) { Thread.sleep(settle); continue; }
            if (!doVerify) return last;
            Thread.sleep(settle);
            if (verify(r)) return last.put("verified", true);
        }
        return last.put("verified", false);
    }

    private JSONObject setText(JSONObject r, String value) throws Exception {
        AccessibilityNodeInfo n = resolve(root(), r);
        if (n == null) return new JSONObject().put("ok", false).put("error", "target_not_found");
        Bundle b = new Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        boolean ok = n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
        return new JSONObject().put("ok", ok).put("action", "type").put("target", node(n)).put("value", value);
    }

    private boolean verify(JSONObject r) {
        JSONObject v = r.optJSONObject("verify_target");
        if (v == null) return false;
        try {
            return resolve(root(), new JSONObject().put("target", v)) != null;
        } catch (JSONException e) {
            Log.e(TAG, "verify: JSONException", e);
            return false;
        }
    }

    private JSONObject waitFor(JSONObject r) throws Exception {
        long timeout = clamp(r.optLong("timeout_ms", 5000), 50, 30000);
        long interval = clamp(r.optLong("interval_ms", 250), 50, 3000);
        JSONObject target = r.optJSONObject("target");
        long end = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < end) {
            if (target != null && resolve(root(), new JSONObject().put("target", target)) != null)
                return new JSONObject().put("ok", true).put("found", true).put("elapsedMs", timeout - Math.max(0, end-System.currentTimeMillis()));
            Thread.sleep(interval);
        }
        return new JSONObject().put("ok", false).put("found", false).put("error", "timeout");
    }

    private JSONObject batch(JSONObject r) throws Exception {
        JSONArray cmds = r.optJSONArray("commands");
        JSONArray results = new JSONArray();
        if (cmds == null) return new JSONObject().put("ok", false).put("error", "commands_required");
        boolean stopOnError = r.optBoolean("stop_on_error", true);
        for (int i=0; i<cmds.length(); i++) {
            JSONObject cmd = cmds.optJSONObject(i);
            JSONObject res = cmd == null ? new JSONObject().put("ok", false).put("error", "invalid_command") : execute(cmd);
            results.put(res);
            if (stopOnError && !res.optBoolean("ok", false)) break;
        }
        return new JSONObject().put("ok", true).put("results", results).put("count", results.length());
    }

    private AccessibilityNodeInfo root() { return getRootInActiveWindow(); }

    /** Scored resolver. Exact text/id/description wins over fuzzy text matching. */
    private AccessibilityNodeInfo resolve(AccessibilityNodeInfo root, JSONObject r) {
        if (root == null) return null;
        JSONObject t = r.optJSONObject("target"); if (t == null) t = r;
        List<ScoredNode> list = new ArrayList<>(); collect(root, t, list);
        if (list.isEmpty()) return null;
        Collections.sort(list, (a,b) -> Double.compare(b.score, a.score));
        double min = t.optDouble("min_score", 1);
        return list.get(0).score >= min ? list.get(0).node : null;
    }

    private void collect(AccessibilityNodeInfo n, JSONObject t, List<ScoredNode> out) {
        if (n == null) return;
        double score = score(n, t);
        if (score > 0) out.add(new ScoredNode(n, score));
        for (int i=0; i<n.getChildCount(); i++) collect(n.getChild(i), t, out);
    }

    private static class ScoredNode { AccessibilityNodeInfo node; double score; ScoredNode(AccessibilityNodeInfo n,double s){node=n;score=s;} }

    private double score(AccessibilityNodeInfo n, JSONObject t) {
        String text=safe(n.getText()), desc=safe(n.getContentDescription()), id=safe(n.getViewIdResourceName()), cls=safe(n.getClassName());
        String q=t.optString("query", ""), wantText=t.optString("text", ""), wantDesc=t.optString("description", t.optString("contentDescription", ""));
        String wantId=t.optString("id", t.optString("viewId", "")), wantCls=t.optString("class", "");
        String contains=t.optString("contains", "");
        boolean visible=!t.has("visible") || t.optBoolean("visible", true);
        if (visible && !n.isVisibleToUser()) return 0;
        double s=0;
        if (!wantId.isEmpty()) { if (id.equals(wantId)) s+=100; else return 0; }
        if (!wantText.isEmpty()) { if (text.equals(wantText)) s+=80; else if (text.equalsIgnoreCase(wantText)) s+=65; else if (text.toLowerCase(Locale.US).contains(wantText.toLowerCase(Locale.US))) s+=50; else return 0; }
        if (!wantDesc.isEmpty()) { if (desc.equals(wantDesc)) s+=70; else if (desc.equalsIgnoreCase(wantDesc)) s+=55; else return 0; }
        if (!wantCls.isEmpty()) { if (cls.equals(wantCls)) s+=25; else return 0; }
        if (!contains.isEmpty()) { if (text.toLowerCase(Locale.US).contains(contains.toLowerCase(Locale.US))) s+=45; else return 0; }
        if (!q.isEmpty() && wantText.isEmpty() && wantDesc.isEmpty() && wantId.isEmpty()) {
            String z=q.toLowerCase(Locale.US); if(text.equalsIgnoreCase(q)||desc.equalsIgnoreCase(q))s+=75; else if(text.toLowerCase(Locale.US).contains(z)||desc.toLowerCase(Locale.US).contains(z)) s+=40; else return 0;
        }
        if (t.optBoolean("clickable", false) && n.isClickable()) s+=10;
        if (t.optBoolean("editable", false) && n.isEditable()) s+=10;
        if (t.optBoolean("scrollable", false) && n.isScrollable()) s+=10;
        if (t.optBoolean("enabled", false) && n.isEnabled()) s+=5;
        return s;
    }

    private JSONArray find(JSONObject r) throws Exception {
        JSONArray a=new JSONArray(); AccessibilityNodeInfo n=root(); if(n!=null) walkFind(n,r,a); return a;
    }
    private void walkFind(AccessibilityNodeInfo n, JSONObject r, JSONArray a) throws Exception {
        if(n==null)return; JSONObject t=r.optJSONObject("target"); if(t==null)t=r;
        if(score(n,t)>0)a.put(node(n));
        for(int i=0;i<n.getChildCount();i++)walkFind(n.getChild(i),r,a);
    }

    private JSONObject observe() throws Exception {
        AccessibilityNodeInfo n=root(); JSONObject o=new JSONObject();
        o.put("package",n==null?"":safe(n.getPackageName())).put("class",n==null?"":safe(n.getClassName()));
        o.put("hasRoot",n!=null).put("windows",windows()).put("eventSeq",eventSeq); if(n!=null)o.put("root",node(n)); return o;
    }
    private JSONObject rootTree() throws Exception { AccessibilityNodeInfo n=root(); return n==null?new JSONObject().put("hasRoot",false):nodeTree(n); }
    private JSONObject nodeTree(AccessibilityNodeInfo n) throws Exception { JSONObject o=node(n); JSONArray c=new JSONArray(); for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo x=n.getChild(i); if(x!=null)c.put(nodeTree(x));} o.put("children",c); return o; }
    private JSONArray windows() throws Exception { JSONArray a=new JSONArray(); for(AccessibilityWindowInfo w:getWindows()){JSONObject o=new JSONObject();Rect b=new Rect();w.getBoundsInScreen(b);o.put("title",safe(w.getTitle())).put("type",w.getType()).put("bounds",rect(b));a.put(o);} return a; }

    private JSONObject node(AccessibilityNodeInfo n) throws Exception {
        JSONObject o=new JSONObject(); Rect b=new Rect(); n.getBoundsInScreen(b);
        o.put("text",safe(n.getText())).put("description",safe(n.getContentDescription())).put("hint",safe(n.getHintText()));
        o.put("class",safe(n.getClassName())).put("package",safe(n.getPackageName())).put("id",safe(n.getViewIdResourceName()));
        o.put("paneTitle",safe(n.getPaneTitle()))
         // v4.1 FIX: getStateDescription() was added in API 30; with minSdk 26 calling
         // it on Android 8-10 threw NoSuchMethodError (an Error, not caught by catch
         // Exception) and killed the whole process.
         .put("stateDescription", android.os.Build.VERSION.SDK_INT >= 30 ? safe(n.getStateDescription()) : "")
         .put("tooltip",safe(n.getTooltipText()));
        o.put("clickable",n.isClickable()).put("editable",n.isEditable()).put("enabled",n.isEnabled()).put("focused",n.isFocused());
        o.put("selected",n.isSelected()).put("checked",n.isChecked()).put("checkable",n.isCheckable()).put("scrollable",n.isScrollable());
        o.put("visible",n.isVisibleToUser()).put("password",n.isPassword()).put("bounds",rect(b)).put("childCount",n.getChildCount());
        JSONArray acts=new JSONArray();for(AccessibilityNodeInfo.AccessibilityAction x:n.getActionList())acts.put(new JSONObject().put("id",x.getId()).put("label",safe(x.getLabel())));o.put("actions",acts); return o;
    }
    private JSONArray rect(Rect b)throws Exception{return new JSONArray().put(b.left).put(b.top).put(b.right).put(b.bottom);}

    private boolean tap(JSONObject r){return stroke(r.optDouble("x",0),r.optDouble("y",0),80);}
    private boolean longPress(JSONObject r){return stroke(r.optDouble("x",0),r.optDouble("y",0),700);}
    private boolean doubleTap(JSONObject r){float x=(float)r.optDouble("x",0),y=(float)r.optDouble("y",0);Path p=new Path();p.moveTo(x,y);GestureDescription.Builder b=new GestureDescription.Builder();b.addStroke(new GestureDescription.StrokeDescription(p,0,100));return dispatchGesture(b.build(),null,null);}
    private boolean stroke(double x,double y,long duration){Path p=new Path();p.moveTo((float)x,(float)y);GestureDescription.Builder b=new GestureDescription.Builder();b.addStroke(new GestureDescription.StrokeDescription(p,0,duration));return dispatchGesture(b.build(),null,null);}
    private boolean swipe(JSONObject r){Path p=new Path();p.moveTo((float)r.optDouble("x1",0),(float)r.optDouble("y1",0));p.lineTo((float)r.optDouble("x2",0),(float)r.optDouble("y2",0));GestureDescription.Builder b=new GestureDescription.Builder();b.addStroke(new GestureDescription.StrokeDescription(p,0,clamp(r.optLong("duration_ms",300),50,5000)));return dispatchGesture(b.build(),null,null);}

    private JSONObject screenshot() throws Exception {
        JSONObject out=new JSONObject(); if(android.os.Build.VERSION.SDK_INT<30)return out.put("ok",false).put("error","screenshot_api_requires_30");
        final CountDownLatch latch=new CountDownLatch(1); final JSONObject result=new JSONObject();
        main.post(() -> takeScreenshot(0,getMainExecutor(),new TakeScreenshotCallback(){
            @Override public void onSuccess(ScreenshotResult sr){
                HardwareBuffer hb = sr.getHardwareBuffer();
                try {
                    Bitmap b=Bitmap.wrapHardwareBuffer(hb,sr.getColorSpace());
                    if(b==null){result.put("ok",false).put("error","bitmap_unavailable");}
                    else{
                        ByteArrayOutputStream raw=new ByteArrayOutputStream();
                        b.compress(Bitmap.CompressFormat.JPEG,60,raw);
                        byte[] data=gzip(raw.toByteArray());
                        result.put("ok",true).put("format","jpg+gzip").put("width",b.getWidth()).put("height",b.getHeight())
                              .put("dataBase64",android.util.Base64.encodeToString(data,android.util.Base64.NO_WRAP));
                        b.recycle();
                    }
                }catch(Exception e){try{result.put("ok",false).put("error",e.toString());}catch(Exception ignored){}}
                finally{
                    // v4.1 FIX: buffer is now always closed, even on error paths.
                    try{hb.close();}catch(Exception ignored){}
                    latch.countDown();
                }
            }
            @Override public void onFailure(int code){try{result.put("ok",false).put("error","screenshot_failed").put("code",code);}catch(Exception ignored){}latch.countDown();}
        }));
        latch.await(8,TimeUnit.SECONDS); return result.length()==0?out.put("ok",false).put("error","screenshot_timeout"):result;
    }
    private byte[] gzip(byte[] input)throws Exception{ByteArrayOutputStream o=new ByteArrayOutputStream();GZIPOutputStream g=new GZIPOutputStream(o);g.write(input);g.finish();g.close();return o.toByteArray();}
    private long clamp(long x,long min,long max){return Math.max(min,Math.min(max,x));}
}
