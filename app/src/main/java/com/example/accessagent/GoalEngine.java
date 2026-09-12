package com.example.accessagent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic on-device goal planner. It does not call an LLM.
 *
 * v4.1 fixes vs v4:
 * - CRITICAL: v4 scored only the ROOT node because observe() returns a flat
 *   node JSON with no "children" array, so chooseTarget() could never find
 *   anything and every goal ended in no_target_found. It now walks the full
 *   serialized tree via service.publicObserveTree().
 * - Success is no longer reported trivially: v4's goalSatisfied() returned
 *   true for any "buka/open" goal whenever any window existed, even after a
 *   failed click. Now success requires the click to have succeeded AND the
 *   foreground package to match a goal token (e.g. "buka Settings" ->
 *   com.android.settings).
 * - Targets already acted on are skipped (no endless re-clicking of the same
 *   node until max_steps).
 * - Non-clickable labels (common: TextView inside a clickable container) are
 *   activated with a coordinate tap on their bounds instead of a failing
 *   ACTION_CLICK.
 * - Scroll branch: if no scrollable node exists it stops with a clear status
 *   instead of scrolling into the void until max_steps.
 * - history is capped at 200 entries (v4 grew it unboundedly).
 */
public final class GoalEngine {
    private final AgentAccessibilityService service;
    private final ArrayList<JSONObject> history = new ArrayList<>();

    public GoalEngine(AgentAccessibilityService service) { this.service = service; }

    public synchronized JSONObject run(String goal, int maxSteps, long stepDelay) throws Exception {
        JSONObject out = new JSONObject();
        JSONArray trace = new JSONArray();
        String g = goal == null ? "" : goal.trim();
        if (g.isEmpty()) return out.put("ok", false).put("error", "goal_required");
        maxSteps = Math.max(1, Math.min(maxSteps, 30));
        HashSet<String> done = new HashSet<>();
        int acted = 0;
        for (int step = 0; step < maxSteps; step++) {
            JSONObject obs = service.publicObserveTree();
            JSONObject t = chooseTarget(g, obs, done);
            if (t != null) {
                JSONObject cmd = buildCommand(t);
                if (cmd == null) { done.add(signature(t)); continue; }
                JSONObject res = service.executeExternal(cmd);
                trace.put(new JSONObject().put("step", step + 1).put("command", cmd).put("result", res));
                history.add(res);
                if (history.size() > 200) history.remove(0);
                done.add(signature(t));
                if (res.optBoolean("ok", false)) acted++;
                if (res.optBoolean("ok", false) && goalSatisfied(g, service.publicObserveTree()))
                    return out.put("ok", true).put("status", "success").put("steps", step + 1).put("trace", trace);
            } else if (containsAny(g, "scroll", "find", "search", "down", "below", "more", "cari", "gulir", "bawah")) {
                JSONObject cmd = new JSONObject().put("action", "scroll_forward")
                        .put("target", new JSONObject().put("scrollable", true));
                JSONObject res = service.executeExternal(cmd);
                trace.put(new JSONObject().put("step", step + 1).put("command", cmd).put("result", res));
                if (!res.optBoolean("ok", false))
                    return out.put("ok", false).put("status", acted > 0 ? "inconclusive" : "blocked")
                            .put("error", "no_scrollable_and_no_target").put("steps", step).put("trace", trace);
            } else {
                return out.put("ok", false).put("status", acted > 0 ? "inconclusive" : "blocked")
                        .put("error", "no_target_found").put("steps", step).put("trace", trace);
            }
            Thread.sleep(Math.max(0, Math.min(stepDelay, 2000)));
        }
        return out.put("ok", false).put("status", "max_steps").put("steps", maxSteps).put("trace", trace);
    }

    /** Clickable nodes get ACTION_CLICK; label-only nodes get a coordinate tap
     *  on their bounds center (works regardless of view clickability). */
    private JSONObject buildCommand(JSONObject t) throws Exception {
        if (t.optBoolean("clickable", false)) {
            return new JSONObject().put("action", "click")
                    .put("target", t.getJSONObject("target"))
                    .put("retries", 2).put("settle_ms", 300);
        }
        JSONArray b = t.optJSONArray("bounds");
        if (b == null || b.length() < 4) return null;
        int x = (b.optInt(0) + b.optInt(2)) / 2;
        int y = (b.optInt(1) + b.optInt(3)) / 2;
        return new JSONObject().put("action", "tap").put("x", x).put("y", y);
    }

    private JSONObject chooseTarget(String goal, JSONObject obs, HashSet<String> done) throws Exception {
        JSONObject root = obs.optJSONObject("root");
        if (root == null) return null;
        JSONArray candidates = new JSONArray();
        collect(root, goal, candidates);
        for (int i = 0; i < candidates.length(); i++) {
            JSONObject c = candidates.optJSONObject(i);
            if (c != null && !done.contains(signature(c))) return c;
        }
        return null;
    }

    private String signature(JSONObject c) {
        JSONObject t = c.optJSONObject("target");
        if (t == null) return String.valueOf(c.hashCode());
        return t.optString("text", "") + "|" + t.optString("description", "") + "|" + t.optString("id", "");
    }

    private void collect(JSONObject n, String goal, JSONArray out) throws Exception {
        if (n == null) return;
        String text = n.optString("text", ""), desc = n.optString("description", "");
        double s = 0;
        for (String token : meaningful(goal)) {
            String z = token.toLowerCase(Locale.US);
            if (text.equalsIgnoreCase(token)) s += 90;
            else if (desc.equalsIgnoreCase(token)) s += 80;
            else if (text.toLowerCase(Locale.US).contains(z)) s += 35;
            else if (desc.toLowerCase(Locale.US).contains(z)) s += 30;
        }
        if (n.optBoolean("clickable", false)) s += 8;
        if (n.optBoolean("enabled", false)) s += 4;
        if (s > 15 && n.optBoolean("visible", false)) {
            out.put(new JSONObject()
                    .put("score", s)
                    .put("clickable", n.optBoolean("clickable", false))
                    .put("bounds", n.optJSONArray("bounds"))
                    .put("target", new JSONObject().put("text", text).put("description", desc)
                            .put("id", n.optString("id", "")).put("class", n.optString("class", ""))));
        }
        JSONArray children = n.optJSONArray("children");
        if (children != null)
            for (int i = 0; i < children.length(); i++) collect(children.optJSONObject(i), goal, out);
        if (out.length() > 1) sort(out);
    }

    private void sort(JSONArray a) throws Exception {
        ArrayList<JSONObject> x = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) x.add(a.getJSONObject(i));
        x.sort((p, q) -> Double.compare(q.optDouble("score"), p.optDouble("score")));
        while (a.length() > 0) a.remove(0);
        for (JSONObject j : x) a.put(j);
    }

    private List<String> meaningful(String g) {
        String cleaned = g.replaceAll("[\\p{Punct}]", " ");
        String[] words = cleaned.split("\\s+");
        HashSet<String> stop = new HashSet<>(Arrays.asList(
                "buka", "open", "klik", "click", "tekan", "press", "pilih", "select",
                "dan", "and", "the", "ke", "di", "dengan", "cari", "search", "find",
                "scroll", "down", "up", "untuk", "to", "a", "an", "app", "aplikasi",
                "please", "tolong", "silakan", "yang", "itu", "menuju", "go"));
        ArrayList<String> r = new ArrayList<>();
        for (String w : words)
            if (w.length() > 2 && !stop.contains(w.toLowerCase(Locale.US))) r.add(w);
        return r;
    }

    /**
     * Deterministic success heuristic: the foreground package must contain one
     * of the goal tokens (e.g. "buka Settings" -> com.android.settings,
     * "buka kalkulator" -> com.google.android.calculator). Package names are
     * usually English, so Indonesian app names ("kamera") may not match; in
     * that case the planner reports "inconclusive" instead of a false success.
     */
    private boolean goalSatisfied(String goal, JSONObject obs) {
        String pkg = obs.optString("package", "").toLowerCase(Locale.US);
        if (pkg.isEmpty()) return false;
        for (String token : meaningful(goal))
            if (pkg.contains(token.toLowerCase(Locale.US))) return true;
        return false;
    }

    private boolean containsAny(String s, String... x) {
        String z = s.toLowerCase(Locale.US);
        for (String a : x) if (z.contains(a)) return true;
        return false;
    }
}
