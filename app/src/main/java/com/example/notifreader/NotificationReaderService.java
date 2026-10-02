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

    // true  = store only money RECEIVED (paid you / received / credited)
    // false = store any notification that has an amount
    private static final boolean ONLY_RECEIVED = true;

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

    // Grouped summaries such as "5 messages | Unread" carry no real message
    private static final Pattern SUMMARY = Pattern.compile(
            "^\\d+\\s+(?:new\\s+)?messages?\\b", Pattern.CASE_INSENSITIVE);

    // SMS apps: bank alerts arrive here. Source shown = the SMS sender.
    private static final Set<String> SMS_APPS = new HashSet<>(Arrays.asList(
            "com.android.mms",                    // Xiaomi / MIUI "Messaging"
            "com.google.android.apps.messaging",  // Google Messages
            "com.samsung.android.messaging"       // Samsung Messages
    ));

    private static final Set<String> ALLOWED = new HashSet<>(Arrays.asList(
            "com.android.mms",                        // SMS: Xiaomi Messaging
            "com.google.android.apps.messaging",      // SMS: Google Messages
            "com.samsung.android.messaging",          // SMS: Samsung Messages
            "com.google.android.apps.nbu.paisa.user", // Google Pay
            "com.phonepe.app",                        // PhonePe
            "net.one97.paytm",                        // Paytm
            "in.org.npci.upiapp",                     // BHIM
            "in.amazon.mShop.android.shopping",       // Amazon Pay
            "com.dreamplug.androidapp",               // CRED
            "com.mobikwik_new",                       // Mobikwik
            "com.freecharge.android",                 // Freecharge
            "com.myairtelapp",                        // Airtel Thanks
            "com.samsung.android.spay",               // Samsung Wallet / Pay
            "com.csam.icici.bank.imobile",            // ICICI iMobile
            "com.sbi.lotusintouch",                   // SBI YONO
            "com.snapwork.hdfc",                      // HDFC MobileBanking
            "com.axis.mobile",                        // Axis Mobile
            "com.kotak.mobile.banking",               // Kotak
            "com.bankofbaroda.mconnect",              // Bank of Baroda
            "com.idfcfirstbank.optimus",              // IDFC FIRST Bank
            "com.indusind.indusmobile",               // IndusInd Bank
            "com.yesbank.app",                        // Yes Bank
            "com.federalbank.mobile",                 // Federal Bank
            "in.co.aubank.au0101",                    // AU Small Finance Bank
            "com.rblbank.mobank",                     // RBL Bank
            "com.bandhan.mbandhan",                   // Bandhan Bank
            "com.infrasoft.unionbank",                // Union Bank of India
            "com.canarabank.mobility",                // Canara Bank
            "com.Version1",                           // Punjab National Bank
            "com.IndianBank.IndOASIS",                // Indian Bank
            "com.bankofindia.upi",                    // Bank of India
            "com.centrallibank.mobilebanking"         // Central Bank of India
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
        if (!ALLOWED.contains(pkg)) return;
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
        String time = new SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
                .format(new Date(sbn.getPostTime()));

        Matcher am = AMOUNT.matcher(full);
        if (am.find()) {
            // ---- Payment: needs an amount ----
            if (ONLY_RECEIVED) {
                if (!RECEIVED.matcher(full).find()) return;
                if (NOT_RECEIVED.matcher(full).find()) return;
            }
            String amount = "\u20B9" + am.group(1);
            String name = extractName(full);   // optional
            NotificationLog.add(new NotificationLog.Entry(source, name, amount, time));
            announce(sbn.getKey(), appName + ". " + title + ". " + text);
        } else {
            // ---- Normal message: no amount ----
            if (OTP.matcher(full).find()) return;   // never keep OTP messages

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