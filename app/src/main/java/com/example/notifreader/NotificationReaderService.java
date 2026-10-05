package com.example.notifreader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Listens to notifications, but only keeps RECEIVED PAYMENTS from UPI apps
 * (PhonePe, Google Pay, Paytm, ...). Everything else is ignored: other apps,
 * SMS, normal messages and OTPs are never stored, shown or uploaded.
 *
 * Each payment is split into: source (the UPI app), name (the payer), amount and
 * note (the transaction note that starts with GSC).
 */
public class NotificationReaderService extends NotificationListenerService {

    private static final String CHANNEL_ID = "reader_status";

    private final Set<String> seen = new HashSet<>();

    // The only apps that are read. To add another UPI app, add its package name here
    // (find it with:  adb shell pm list packages | grep -i <app name>).
    private static final Set<String> UPI_APPS = new HashSet<>(Arrays.asList(
            "com.phonepe.app",                             // PhonePe
            "com.google.android.apps.nbu.paisa.user",      // Google Pay
            "com.google.android.apps.nbu.paisa.merchant",  // Google Pay for Business
            "net.one97.paytm",                             // Paytm
            "in.org.npci.upiapp",                          // BHIM
            "com.sbi.upi",                                 // BHIM SBI Pay
            "com.dreamplug.androidapp",                    // CRED
            "in.amazon.mShop.android.shopping",            // Amazon Pay (inside the Amazon app)
            "com.freecharge.android",                      // Freecharge
            "com.mobikwik_new"                             // MobiKwik
    ));

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

    // The transaction note: a word that starts with GSC, e.g. "GSC-1306-2026/27".
    // The server removes the dashes and slashes and compares what is left.
    private static final Pattern NOTE = Pattern.compile(
            "(?<![A-Za-z0-9])GSC[A-Za-z0-9._/\\-]*", Pattern.CASE_INSENSITIVE);

    // Anything with an OTP in it is ignored
    private static final Pattern OTP = Pattern.compile(
            "(?<![a-z])otp(?![a-z])|one[- ]time password", Pattern.CASE_INSENSITIVE);

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
                    .setContentTitle("UPI payment reader is running")
                    .setContentText("Watching for received UPI payments")
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

    // Returns the payer's name, or "" if none can be found
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

    // Returns the transaction note that starts with GSC, or "" if there is none
    private String extractNote(String full) {
        Matcher m = NOTE.matcher(full);
        if (!m.find()) return "";
        String note = m.group();
        // drop punctuation that belongs to the sentence, not the note
        note = note.replaceAll("[._/\\-]+$", "");
        return note;
    }

    private static String str(CharSequence cs) {
        return cs == null ? "" : cs.toString().trim();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        String pkg = sbn.getPackageName();

        // Only UPI apps are read. Everything else is ignored right away.
        if (!UPI_APPS.contains(pkg)) return;

        int flags = sbn.getNotification().flags;
        if ((flags & Notification.FLAG_ONGOING_EVENT) != 0) return;
        if ((flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;

        Bundle extras = sbn.getNotification().extras;
        String title = str(extras.getCharSequence(Notification.EXTRA_TITLE));
        String text = str(extras.getCharSequence(Notification.EXTRA_TEXT));
        String big = str(extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
        String sub = str(extras.getCharSequence(Notification.EXTRA_SUB_TEXT));
        String info = str(extras.getCharSequence(Notification.EXTRA_INFO_TEXT));

        // Prefer the expanded text: it holds the complete message
        if (big.length() > text.length()) text = big;

        // Inbox-style notifications keep extra lines here; the note can be on one of them
        StringBuilder lines = new StringBuilder();
        CharSequence[] arr = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (arr != null) {
            for (CharSequence c : arr) lines.append(' ').append(str(c));
        }

        if (title.isEmpty() && text.isEmpty()) return;

        // Ignore the same notification being posted again (updates)
        String id = sbn.getKey() + sbn.getPostTime();
        if (seen.size() > 500) seen.clear();
        if (!seen.add(id)) return;

        String full = title + " " + text + " " + sub + " " + info + lines;

        // OTPs are never stored or uploaded
        if (OTP.matcher(full).find()) return;

        // A payment = has an amount AND says money was received
        Matcher am = AMOUNT.matcher(full);
        boolean isPayment = am.find()
                && RECEIVED.matcher(full).find()
                && !NOT_RECEIVED.matcher(full).find();
        if (!isPayment) return;   // normal messages are not caught at all

        // Source = the UPI app's name (PhonePe, Google Pay, Paytm, ...)
        String source;
        try {
            source = getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(pkg, 0)).toString().trim();
        } catch (Exception e) {
            return;
        }
        if (source.isEmpty()) return;

        String amount = "\u20B9" + am.group(1);
        String name = extractName(full);   // optional
        String note = extractNote(full);   // starts with GSC, "" if the payer sent none

        String time = new SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
                .format(new Date(sbn.getPostTime()));

        NotificationLog.add(new NotificationLog.Entry(source, name, amount, note, time));

        // Send it to the server. The server matches it to the open QR request with
        // the same invoice number (the note). Pass the plain number (no rupee sign);
        // ApiClient queues it and retries if the network is down.
        ApiClient.sendTransaction(this, name, am.group(1), note, sbn.getPostTime());
    }
}
