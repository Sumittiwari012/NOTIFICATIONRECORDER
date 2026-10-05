package com.example.notifreader;

import android.Manifest;
import android.content.Intent;
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
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private TableLayout table;       // received UPI payments
    private LinearLayout ambBox;     // payments that need to be settled by hand
    private ScrollView payScroll, ambScroll;
    private Button payTab, ambTab;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Not logged in: go to the login screen first
        if (SecureStore.get(this, "cid") == null) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }

        // On Android 13+ the "running" notification needs permission
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

        // Two tabs: Payments | Needs action
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        payTab = new Button(this);
        ambTab = new Button(this);
        tabs.addView(payTab, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tabs.addView(ambTab, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        payTab.setOnClickListener(v -> showTab(0));
        ambTab.setOnClickListener(v -> showTab(1));

        table = new TableLayout(this);
        table.setStretchAllColumns(true);
        table.setPadding(0, 24, 0, 0);
        payScroll = new ScrollView(this);
        payScroll.addView(table);

        ambBox = new LinearLayout(this);
        ambBox.setOrientation(LinearLayout.VERTICAL);
        ambBox.setPadding(8, 24, 8, 0);
        ambScroll = new ScrollView(this);
        ambScroll.addView(ambBox);

        root.addView(grant);
        root.addView(battery);
        root.addView(logout);
        root.addView(tabs);
        root.addView(payScroll);
        root.addView(ambScroll);
        setContentView(root);

        showTab(0);
        NotificationLog.onChange = () -> runOnUiThread(this::render);

        // The server's answer for each uploaded payment
        ApiClient.setTxListener((status, body) -> {
            if ("ambiguous".equals(status)) {
                Toast.makeText(this,
                        "A payment matches several QR requests. Open \"Needs action\".",
                        Toast.LENGTH_LONG).show();
            }
            render();
        });
        render();
    }

    private void askPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    // 0 = payments, 1 = needs action (ambiguous)
    private void showTab(int tab) {
        payScroll.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        ambScroll.setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
        payTab.setAlpha(tab == 0 ? 1f : 0.5f);
        ambTab.setAlpha(tab == 1 ? 1f : 0.5f);
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
        payTab.setText("Payments (" + NotificationLog.items.size() + ")");
        ambTab.setText("Needs action (" + ApiClient.getAmbiguous(this).length() + ")");
        renderPayments();
        renderAmbiguous();
    }

    // Time part of "2026-10-03T14:05:11.123" -> "14:05:11"
    private String clock(String iso) {
        if (iso == null) return "";
        int t = iso.indexOf('T');
        return (t >= 0 && iso.length() >= t + 9) ? iso.substring(t + 1, t + 9) : iso;
    }

    // Payments the server could not match to a single QR request. Tap the request
    // the payment belongs to.
    private void renderAmbiguous() {
        ambBox.removeAllViews();
        JSONArray list = ApiClient.getAmbiguous(this);

        if (list.length() == 0) {
            TextView empty = new TextView(this);
            empty.setText("Nothing to settle.");
            ambBox.addView(empty);
            return;
        }

        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item == null) continue;
            JSONObject payer = item.optJSONObject("payer");
            final String payerName = payer == null ? "" : payer.optString("name", "");
            final String amount = payer == null ? "" : payer.optString("amount", "");
            final String receivedAt = item.optString("receivedAt", null);

            TextView head = new TextView(this);
            head.setText("\u20B9" + amount + " from " + (payerName.isEmpty() ? "Unknown" : payerName)
                    + "\nSeveral QR requests have this amount. Who is it for?");
            head.setTextSize(15f);
            head.setTypeface(null, Typeface.BOLD);
            head.setPadding(0, 16, 0, 8);
            ambBox.addView(head);

            JSONArray matches = item.optJSONArray("matches");
            if (matches == null) continue;
            for (int k = 0; k < matches.length(); k++) {
                JSONObject m = matches.optJSONObject(k);
                if (m == null) continue;
                final long requestId = m.optLong("requestId");
                String who = m.optString("name", "");
                Button pick = new Button(this);
                pick.setText((who.isEmpty() ? "Request #" + requestId : who)
                        + "  (QR shown at " + clock(m.optString("requestedAt", "")) + ")");
                pick.setOnClickListener(v -> {
                    pick.setEnabled(false);
                    ApiClient.confirmPayment(this, requestId, payerName, receivedAt, (ok, msg) -> {
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
                        pick.setEnabled(true);
                        render();
                    });
                });
                ambBox.addView(pick);
            }
        }
    }

    // Payments table: Source | Name | Amount | Note, with the time under each row
    private void renderPayments() {
        table.removeAllViews();

        TableRow header = new TableRow(this);
        header.addView(cell("Source", true));
        header.addView(cell("Name", true));
        header.addView(cell("Amount", true));
        header.addView(cell("Note", true));
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
        if (ambBox != null) render();   // the service may have saved new ones meanwhile
    }

    @Override
    protected void onDestroy() {
        NotificationLog.onChange = null;
        ApiClient.setTxListener(null);
        super.onDestroy();
    }
}
