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
 * Records ONLY "money received" notifications from UPI apps.
 * Everything else (other apps, SMS, normal messages, OTPs) is ignored completely:
 * nothing is stored, shown, uploaded or spoken.
 */
public class NotificationReaderService extends NotificationListenerService {

    private static final String CHANNEL_ID = "reader_status";

    private final Set<String> seen = new HashSet<>();

    // The UPI apps whose payment notifications are recorded. To support another app,
    // add its package name here.
    private static final Set<String> UPI_APPS = new HashSet<>(Arrays.asList(
            "com.phonepe.app",                                  // PhonePe
            "com.google.android.apps.nbu.paisa.user",           // Google Pay
            "com.google.android.apps.nbu.paisa.merchant",       // Google Pay for Business
            "net.one97.paytm",                                  // Paytm
            "in.org.npci.upiapp",                               // BHIM
            "com.dreamplug.androidapp",                         // CRED
            "com.freecharge.android",                           // Freecharge
            "com.mobikwik_new"                                  // MobiKwik
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

    // The transaction note: a word that starts with "GR" and contains a digit,
    // e.g. "GR1306202627". The digit requirement keeps names like "GRACE" from matching.
    private static final Pattern NOTE = Pattern.compile(
            "\\b(GR[A-Za-z0-9]*[0-9][A-Za-z0-9]*)\\b", Pattern.CASE_INSENSITIVE);

    // OTPs are never recorded
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
                    .setContentTitle("Payment reader is running")
                    .setContentText("Watching for UPI payments")
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

    // Returns the transaction note (starts with "GR", upper case), or "" if there is none
    private String extractNote(String full) {
        Matcher m = NOTE.matcher(full);
        return m.find() ? m.group(1).toUpperCase(Locale.ROOT) : "";
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        // Only UPI apps are looked at; everything else is dropped straight away
        String pkg = sbn.getPackageName();
        if (!UPI_APPS.contains(pkg)) return;

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

        // Source = the UPI app's name (e.g. "Google Pay", "PhonePe")
        String source;
        try {
            source = getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(pkg, 0)).toString().trim();
        } catch (Exception e) {
            return;
        }
        if (source.isEmpty()) return;

        // Ignore the same notification being posted again (updates)
        String id = sbn.getKey() + sbn.getPostTime();
        if (seen.size() > 500) seen.clear();
        if (!seen.add(id)) return;

        String full = title + " " + text;

        // OTPs are never recorded
        if (OTP.matcher(full).find()) return;

        // A payment = has an amount AND says money was received. Anything else
        // from a UPI app (offers, reminders, money sent out) is ignored.
        Matcher am = AMOUNT.matcher(full);
        boolean isPayment = am.find()
                && RECEIVED.matcher(full).find()
                && !NOT_RECEIVED.matcher(full).find();
        if (!isPayment) return;

        String amountNumber = am.group(1);               // plain number, e.g. "1.00"
        String amount = "\u20B9" + amountNumber;         // for display
        String name = extractName(full);                 // payer's name (may be empty)
        String note = extractNote(full);                 // "GR..." note (may be empty)

        String time = new SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
                .format(new Date(sbn.getPostTime()));

        NotificationLog.add(new NotificationLog.Entry(source, name, amount, note, time));

        // Send it to the server. The server matches it to the open QR request with the
        // same invoice number (the note) and ends that screen's wait. ApiClient queues
        // it and retries if the network is down.
        ApiClient.sendTransaction(this, name, amountNumber, note, sbn.getPostTime());
    }
}
