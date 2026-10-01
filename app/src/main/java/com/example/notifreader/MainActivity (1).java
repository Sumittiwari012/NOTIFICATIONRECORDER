package com.example.notifreader;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private TextView list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences prefs = getSharedPreferences("prefs", MODE_PRIVATE);

        // Android 13+: allow the "running" notification to show
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 96, 48, 48);

        Button grant = new Button(this);
        grant.setText("Grant notification access");
        grant.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));

        Button battery = new Button(this);
        battery.setText("Turn off battery restrictions");
        battery.setOnClickListener(v -> startActivity(new Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + getPackageName()))));

        Switch speak = new Switch(this);
        speak.setText("Read notifications aloud");
        speak.setChecked(prefs.getBoolean("speak", true));
        speak.setOnCheckedChangeListener((b, on) ->
                prefs.edit().putBoolean("speak", on).apply());

        list = new TextView(this);
        list.setTextSize(15f);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);

        root.addView(grant);
        root.addView(battery);
        root.addView(speak);
        root.addView(scroll);
        setContentView(root);

        NotificationLog.onChange = () -> runOnUiThread(this::render);
        render();
    }

    private void render() {
        if (NotificationLog.items.isEmpty()) {
            list.setText("No notifications yet.");
        } else {
            list.setText(String.join("\n\n", NotificationLog.items));
        }
    }

    @Override
    protected void onDestroy() {
        NotificationLog.onChange = null;
        super.onDestroy();
    }
}
