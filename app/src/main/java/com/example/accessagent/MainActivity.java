package com.example.accessagent;

import android.app.Activity;
import android.os.Bundle;
import android.provider.Settings;
import android.content.Intent;
import android.graphics.Color;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Button;

public class MainActivity extends Activity {
    private TextView status;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(48, 48, 48, 48);
        TextView t = new TextView(this);
        // v4.1 FIX: was "Access Agent\\n\\n..." which printed literal backslash-n.
        t.setText("Access Agent\n\nEnable the Accessibility Service, then control it from Termux on 127.0.0.1:8765.");
        t.setTextSize(18);
        l.addView(t);
        status = new TextView(this);
        status.setTextSize(15);
        status.setPadding(0, 40, 0, 0);
        l.addView(status);
        Button s = new Button(this);
        s.setText("Open Accessibility Settings");
        s.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        l.addView(s);
        setContentView(l);
    }

    @Override protected void onResume() {
        super.onResume();
        boolean running = AgentAccessibilityService.getInstance() != null;
        if (running) {
            status.setText("Status: service ACTIVE - server ready on 127.0.0.1:8765\nTest from Termux:\nprintf '%s\\n' '{\"action\":\"ping\"}' | nc 127.0.0.1 8765");
            status.setTextColor(Color.rgb(0, 128, 0));
        } else {
            status.setText("Status: service NOT ACTIVE - open Accessibility Settings and enable Access Agent.\n(Android 13: sideloaded apps must first tap the 3-dot menu in app info > Allow restricted settings.)");
            status.setTextColor(Color.rgb(200, 0, 0));
        }
    }
}
