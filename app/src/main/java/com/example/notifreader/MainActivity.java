package com.example.notifreader;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private TableLayout table;

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

        table = new TableLayout(this);
        table.setStretchAllColumns(true);
        table.setPadding(0, 24, 0, 0);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(table);

        root.addView(grant);
        root.addView(battery);
        root.addView(speak);
        root.addView(scroll);
        setContentView(root);

        NotificationLog.onChange = () -> runOnUiThread(this::render);
        render();
    }

    private TextView cell(String text, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(15f);
        tv.setPadding(8, 12, 8, 4);
        if (bold) tv.setTypeface(null, Typeface.BOLD);
        return tv;
    }

    private void render() {
        table.removeAllViews();

        // Header
        TableRow header = new TableRow(this);
        header.addView(cell("Source", true));
        header.addView(cell("Name", true));
        header.addView(cell("Amount", true));
        table.addView(header);

        if (NotificationLog.items.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No payments recorded yet.");
            empty.setPadding(8, 24, 8, 8);
            table.addView(empty);
            return;
        }

        for (NotificationLog.Entry e : NotificationLog.items) {
            TableRow row = new TableRow(this);
            row.addView(cell(e.source, false));
            row.addView(cell(e.name.isEmpty() ? "-" : e.name, false));
            row.addView(cell(e.amount, true));
            table.addView(row);

            // Date and time under each record
            TableRow timeRow = new TableRow(this);
            TextView time = new TextView(this);
            time.setText(e.time);
            time.setTextSize(12f);
            time.setAlpha(0.6f);
            time.setPadding(8, 0, 8, 20);
            TableRow.LayoutParams lp = new TableRow.LayoutParams();
            lp.span = 3;
            time.setLayoutParams(lp);
            timeRow.addView(time);
            table.addView(timeRow);
        }
    }

    @Override
    protected void onDestroy() {
        NotificationLog.onChange = null;
        super.onDestroy();
    }
}