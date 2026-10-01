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

import java.util.Locale;

public class NotificationReaderService extends NotificationListenerService
        implements TextToSpeech.OnInitListener {

    private static final String CHANNEL_ID = "reader_status";

    private TextToSpeech tts;
    private boolean ttsReady = false;

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
        if (sbn.getPackageName().equals(getPackageName())) return; // ignore our own
        if ((sbn.getNotification().flags & Notification.FLAG_ONGOING_EVENT) != 0) return;

        Bundle extras = sbn.getNotification().extras;
        CharSequence t = extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence x = extras.getCharSequence(Notification.EXTRA_TEXT);
        String title = t == null ? "" : t.toString();
        String text = x == null ? "" : x.toString();
        if (title.trim().isEmpty() && text.trim().isEmpty()) return;

        String appName;
        try {
            appName = getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(sbn.getPackageName(), 0)).toString();
        } catch (Exception e) {
            appName = sbn.getPackageName();
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
