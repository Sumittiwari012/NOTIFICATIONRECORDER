package com.example.notifreader;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private TableLayout table;       // payments
    private LinearLayout msgBox;     // normal messages
    private ScrollView payScroll, msgScroll;
    private Button payTab, msgTab;

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

        // Two tabs: Payments | Normal messages
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        payTab = new Button(this);
        msgTab = new Button(this);
        tabs.addView(payTab, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tabs.addView(msgTab, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        payTab.setOnClickListener(v -> showTab(true));
        msgTab.setOnClickListener(v -> showTab(false));

        table = new TableLayout(this);
        table.setStretchAllColumns(true);
        table.setPadding(0, 24, 0, 0);
        payScroll = new ScrollView(this);
        payScroll.addView(table);

        msgBox = new LinearLayout(this);
        msgBox.setOrientation(LinearLayout.VERTICAL);
        msgBox.setPadding(8, 24, 8, 0);
        msgScroll = new ScrollView(this);
        msgScroll.addView(msgBox);

        root.addView(grant);
        root.addView(battery);
        root.addView(speak);
        root.addView(tabs);
        root.addView(payScroll);
        root.addView(msgScroll);
        setContentView(root);

        showTab(true);
        NotificationLog.onChange = () -> runOnUiThread(this::render);
        render();
    }

    private void showTab(boolean payments) {
        payScroll.setVisibility(payments ? View.VISIBLE : View.GONE);
        msgScroll.setVisibility(payments ? View.GONE : View.VISIBLE);
        payTab.setAlpha(payments ? 1f : 0.5f);
        msgTab.setAlpha(payments ? 0.5f : 1f);
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
        payTab.setText("Payments (" + NotificationLog.items.size() + ")");
        msgTab.setText("Normal messages (" + NotificationLog.messages.size() + ")");
        renderPayments();
        renderMessages();
    }

    private void renderPayments() {
        table.removeAllViews();

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

    private void renderMessages() {
        msgBox.removeAllViews();

        if (NotificationLog.messages.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No normal messages yet.");
            msgBox.addView(empty);
            return;
        }

        for (NotificationLog.Msg m : NotificationLog.messages) {
            TextView src = new TextView(this);
            src.setText(m.source);
            src.setTextSize(15f);
            src.setTypeface(null, Typeface.BOLD);

            TextView body = new TextView(this);
            body.setText(m.message);   // complete message
            body.setTextSize(15f);
            body.setPadding(0, 4, 0, 4);

            TextView time = new TextView(this);
            time.setText(m.time);
            time.setTextSize(12f);
            time.setAlpha(0.6f);
            time.setPadding(0, 0, 0, 32);

            msgBox.addView(src);
            msgBox.addView(body);
            msgBox.addView(time);
        }
    }

    @Override
    protected void onDestroy() {
        NotificationLog.onChange = null;
        super.onDestroy();
    }
}