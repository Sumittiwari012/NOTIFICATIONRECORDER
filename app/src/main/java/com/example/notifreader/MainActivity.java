package com.example.notifreader;

import android.Manifest;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private TableLayout table;       // received UPI payments
    private TextView heading;        // "Payments (n)"

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Not logged in: go to the login screen first
        if (SecureStore.get(this, "cid") == null) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }

        // On Android 13+ the "running" notification needs this permission
        askPermissions();

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

        Button logout = new Button(this);
        logout.setText("Log out");
        logout.setOnClickListener(v -> {
            SecureStore.clearAll(this);
            startActivity(new Intent(this, LoginActivity.class));
            finish();
        });

        heading = new TextView(this);
        heading.setTextSize(18f);
        heading.setTypeface(null, Typeface.BOLD);
        heading.setPadding(0, 32, 0, 0);

        table = new TableLayout(this);
        table.setStretchAllColumns(true);
        table.setPadding(0, 24, 0, 0);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(table);

        root.addView(grant);
        root.addView(battery);
        root.addView(logout);
        root.addView(heading);
        root.addView(scroll);
        setContentView(root);

        NotificationLog.onChange = () -> runOnUiThread(this::render);

        // The server's answer for each uploaded payment: just redraw
        ApiClient.setTxListener((status, body) -> render());
        render();
    }

    private void askPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    private TextView cell(String text, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(14f);
        tv.setPadding(8, 12, 8, 4);
        if (bold) tv.setTypeface(null, Typeface.BOLD);
        return tv;
    }

    private void render() {
        heading.setText("Payments (" + NotificationLog.items.size() + ")");
        table.removeAllViews();

        // Source | Name | Amount | Note
        TableRow header = new TableRow(this);
        header.addView(cell("Source", true));
        header.addView(cell("Name", true));
        header.addView(cell("Amount", true));
        header.addView(cell("Note", true));
        table.addView(header);

        if (NotificationLog.items.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No UPI payments recorded yet.");
            empty.setPadding(8, 24, 8, 8);
            table.addView(empty);
            return;
        }

        for (NotificationLog.Entry e : NotificationLog.items) {
            TableRow row = new TableRow(this);
            row.addView(cell(e.source, false));
            row.addView(cell(e.name.isEmpty() ? "-" : e.name, false));
            row.addView(cell(e.amount, true));
            row.addView(cell(e.note.isEmpty() ? "-" : e.note, false));
            table.addView(row);

            TableRow timeRow = new TableRow(this);
            TextView time = new TextView(this);
            time.setText(e.time);
            time.setTextSize(12f);
            time.setAlpha(0.6f);
            time.setPadding(8, 0, 8, 20);
            TableRow.LayoutParams lp = new TableRow.LayoutParams();
            lp.span = 4;
            time.setLayoutParams(lp);
            timeRow.addView(time);
            table.addView(timeRow);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ApiClient.flushPending(this);   // retry any uploads that failed earlier
        if (table != null) render();    // the service may have saved new ones meanwhile
    }

    @Override
    protected void onDestroy() {
        NotificationLog.onChange = null;
        ApiClient.setTxListener(null);
        super.onDestroy();
    }
}
