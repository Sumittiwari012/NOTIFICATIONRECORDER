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

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public class NotificationReaderService extends NotificationListenerService
        implements TextToSpeech.OnInitListener {

    private static final String CHANNEL_ID = "reader_status";

    private TextToSpeech tts;
    private boolean ttsReady = false;

    // Messages matching this are dropped (OTP / verification codes)
    private static final Pattern OTP_PATTERN = Pattern.compile(
            "\\botp\\b|one[- ]time password|verification code|\\bpasscode\\b");

    // Apps that must never be read (block always wins over allow)
    private static final Set<String> BLOCKED = new HashSet<>(Arrays.asList(
            "com.whatsapp",
            "com.whatsapp.w4b"
    ));

    // SMS apps: OTPs and bank alerts arrive here. Only bank-related texts are kept.
    private static final Set<String> SMS_APPS = new HashSet<>(Arrays.asList(
            "com.android.mms",                    // Xiaomi / MIUI messages
            "com.google.android.apps.messaging",  // Google Messages
            "com.samsung.android.messaging"       // Samsung Messages
    ));

    // Only these apps are read
    private static final Set<String> ALLOWED = new HashSet<>(Arrays.asList(
            // SMS apps
            "com.android.mms",
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",

            // Payment apps
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

            // Banks
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

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        String pkg = sbn.getPackageName();

        if (pkg.equals(getPackageName())) return;      // ignore our own
        if (BLOCKED.contains(pkg)) return;             // never read blocked apps
        if (!ALLOWED.contains(pkg)) return;            // only read allowed apps
        if ((sbn.getNotification().flags & Notification.FLAG_ONGOING_EVENT) != 0) return;

        Bundle extras = sbn.getNotification().extras;
        CharSequence t = extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence x = extras.getCharSequence(Notification.EXTRA_TEXT);
        String title = t == null ? "" : t.toString();
        String text = x == null ? "" : x.toString();
        if (title.trim().isEmpty() && text.trim().isEmpty()) return;

        String full = (title + " " + text).toLowerCase();

        // Drop OTP messages from every app
        if (OTP_PATTERN.matcher(full).find()) return;

        // For SMS apps, keep only bank related messages
        if (SMS_APPS.contains(pkg)) {
            boolean bankRelated = full.contains("debited")
                    || full.contains("credited")
                    || full.contains("a/c")
                    || full.contains("upi")
                    || full.contains("txn")
                    || full.contains("bank");
            if (!bankRelated) return;
        }

        String appName;
        try {
            appName = getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            appName = pkg;
        }

        NotificationLog.add(appName + ": " + title + " - " + text);

        SharedPreferences prefs = getSharedPreferences("prefs", MODE_PRIVATE);
        if (prefs.getBoolean("speak", true) && ttsReady) {
            tts.speak(appName + ". " + title + ". " + text,
                    TextToSpeech.QUEUE_ADD, null, sbn.getKey());
        }
    }

    @Override
    public void onDestroy() {
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }
}