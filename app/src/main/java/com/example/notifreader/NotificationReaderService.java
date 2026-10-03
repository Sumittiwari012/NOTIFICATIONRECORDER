package com.example.notifreader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.speech.tts.TextToSpeech;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NotificationReaderService extends NotificationListenerService
        implements TextToSpeech.OnInitListener {

    private static final String CHANNEL_ID = "reader_status";

    private TextToSpeech tts;
    private boolean ttsReady = false;
    private final Set<String> seen = new HashSet<>();

    private static final Pattern AMOUNT = Pattern.compile(
            "(?:\u20B9|\\b(?:rs|inr)\\.?)\\s?([0-9][0-9,]*(?:\\.[0-9]{1,2})?)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern RECEIVED = Pattern.compile(
            "paid you|sent you|has sent|received|credited|deposited",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern NOT_RECEIVED = Pattern.compile(
            "debited|you paid|paid to|sent to|payment to",
            Pattern.CASE_INSENSITIVE);

    // "DURGA TIWARI paid you ₹1.00"
    private static final Pattern NAME_PAID = Pattern.compile(
            "^\\s*(.{2,40}?)\\s+(?:paid|sent)\\s+you", Pattern.CASE_INSENSITIVE);

    // "received ₹500 from RAHUL SHARMA on ..."
    private static final Pattern NAME_FROM = Pattern.compile(
            "(?:received|credited).{0,80}?\\bfrom\\s+([A-Za-z][A-Za-z .'-]{1,40}?)"
                    + "(?=\\s+(?:on|via|using|to|ref|upi|vpa|a/c|with)\\b|[.,;(]|\\d|$)",
            Pattern.CASE_INSENSITIVE);

    // Normal messages containing "otp" are ignored
    private static final Pattern OTP = Pattern.compile(
            "(?<![a-z])otp(?![a-z])|one[- ]time password", Pattern.CASE_INSENSITIVE);

    // Placeholders shown when the Messaging app hides the real text:
    // "Unread", "1 message", "New message", "5 messages | Unread", ...
    private static final Pattern SUMMARY = Pattern.compile(
            "^\\s*(?:\\d+\\s+(?:new\\s+)?messages?\\b.*"
                    + "|(?:new\\s+)?messages?"
                    + "|\\d*\\s*unread"
                    + "|.{0,20}\\|\\s*unread"
                    + "|(?:sensitive|content).{0,40}hidden.*)\\s*$",
            Pattern.CASE_INSENSITIVE);

    // SMS apps: bank alerts arrive here. Source shown = the SMS sender.
    private static final Set<String> SMS_APPS = new HashSet<>(Arrays.asList(
            "com.android.mms",                    // Xiaomi / MIUI "Messaging"
            "com.google.android.apps.messaging",  // Google Messages
            "com.samsung.android.messaging"       // Samsung Messages
    ));

    @Override
    public void onCreate() {
        super.onCreate();
        tts = new TextToSpeech(this, this);
    }

    @Override
    public void onInit(int status) {
        ttsReady = status == TextToSpeech.SUCCESS;
        if (ttsReady) tts.setLanguage(Locale.getDefault());
    }

    // Called when Android connects us to the notification stream
    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        startInForeground();
    }

    // If Android/MIUI disconnects us, ask to be reconnected
    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        requestRebind(new ComponentName(this, NotificationReaderService.class));
    }

    private void startInForeground() {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "Reader status", NotificationManager.IMPORTANCE_LOW));

            Notification n = new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("Notification Reader is running")
                    .setContentText("Reading your notifications aloud")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setOngoing(true)
                    .build();

            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(1, n);
            }
        } catch (Exception e) {
            // If this fails the listener still works, just less protected
        }
    }

    // Returns the account holder's name, or "" if none can be found
    private String extractName(String full) {
        Matcher m = NAME_PAID.matcher(full);
        String name = m.find() ? m.group(1) : null;
        if (name == null) {
            m = NAME_FROM.matcher(full);
            if (m.find()) name = m.group(1);
        }
        if (name == null) return "";
        name = name.trim();
        // Reject results that don't look like a name
        if (name.length() < 2 || name.length() > 40 || name.matches(".*\\d.*")) return "";
        return name;
    }

    private void announce(String key, String what) {
        SharedPreferences prefs = getSharedPreferences("prefs", MODE_PRIVATE);
        if (prefs.getBoolean("speak", true) && ttsReady) {
            if (what.length() > 3000) what = what.substring(0, 3000);
            tts.speak(what, TextToSpeech.QUEUE_ADD, null, key);
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        String pkg = sbn.getPackageName();
        if (pkg.equals(getPackageName())) return; // ignore our own
        int flags = sbn.getNotification().flags;
        if ((flags & Notification.FLAG_ONGOING_EVENT) != 0) return;
        if ((flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;

        Bundle extras = sbn.getNotification().extras;
        CharSequence t = extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence x = extras.getCharSequence(Notification.EXTRA_TEXT);
        CharSequence b = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        String title = t == null ? "" : t.toString().trim();
        String text = x == null ? "" : x.toString().trim();
        // Prefer the expanded text: it holds the complete message
        if (b != null && b.toString().trim().length() > text.length()) {
            text = b.toString().trim();
        }
        if (title.isEmpty() && text.isEmpty()) return;
        if (SUMMARY.matcher(text).find()) return;
        // An SMS notification with no text has nothing to record
        if (SMS_APPS.contains(pkg) && text.isEmpty()) return;

        // Source is required: drop if the app name can't be found
        String appName;
        try {
            appName = getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return;
        }
        if (appName.trim().isEmpty()) return;

        // For SMS apps, the source is the sender (e.g. "Punjab National Bank")
        String source = appName;
        if (SMS_APPS.contains(pkg) && !title.isEmpty()) source = title;

        // Ignore the same notification being posted again (updates)
        String id = sbn.getKey() + sbn.getPostTime();
        if (seen.size() > 500) seen.clear();
        if (!seen.add(id)) return;

        String full = title + " " + text;

        // OTPs are never stored or announced
        if (OTP.matcher(full).find()) return;

        String time = new SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
                .format(new Date(sbn.getPostTime()));

        // A payment = has an amount AND says money was received
        Matcher am = AMOUNT.matcher(full);
        boolean isPayment = am.find()
                && RECEIVED.matcher(full).find()
                && !NOT_RECEIVED.matcher(full).find();

        if (isPayment) {
            // ---- Payments tab ----
            String amount = "\u20B9" + am.group(1);
            String name = extractName(full);   // optional
            NotificationLog.add(new NotificationLog.Entry(source, name, amount, time));

            // Send it to the server. The server matches it to the open QR request
            // for this amount and ends that screen's wait. Pass the plain number
            // (no rupee sign); ApiClient queues it and retries if the network is down.
            ApiClient.sendTransaction(this, name, am.group(1), sbn.getPostTime());
            announce(sbn.getKey(), appName + ". " + title + ". " + text);
        } else {
            // ---- Normal messages tab: everything else ----
            String message = SMS_APPS.contains(pkg)
                    ? text : (title.isEmpty() ? text : title + ": " + text);
            if (message.isEmpty()) message = title;

            NotificationLog.addMessage(new NotificationLog.Msg(source, message, time));
            announce(sbn.getKey(), source + ". " + message);
        }
    }

    @Override
    public void onDestroy() {
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }
}