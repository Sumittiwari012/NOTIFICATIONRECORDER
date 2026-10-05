package com.example.notifreader;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Talks to PaymentsController.
 *
 *  Phone app (notification reader)
 *    sendTransaction()       -> POST Payments/AddTransaction      (queued + retried)
 *    getAmbiguous()/confirmPayment() -> settle "ambiguous" payments by hand
 *
 *  QR screen (use these if the QR screen lives in this app)
 *    createPaymentRequest()  -> POST Payments/CreatePaymentRequest
 *    waitForPayment()        -> GET  Payments/WaitForPayment/{id}  (long poll, loops by itself)
 *    cancelPaymentRequest()  -> POST Payments/CancelPaymentRequest
 */
public class ApiClient {

    // TODO: set this to your server. It must be https and end with a slash.
    public static final String BASE_URL = "https://gripstyleapi.runasp.net/api/";

    // The server holds WaitForPayment for up to 50s, so the read timeout must be longer.
    private static final int NORMAL_TIMEOUT_MS = 15000;
    private static final int WAIT_READ_TIMEOUT_MS = 65000;

    // Login / QR calls (so they never wait behind uploads)
    private static final ExecutorService AUTH = Executors.newCachedThreadPool();
    // Transaction uploads and local queue changes run one at a time, in order
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    // Long-polling waits, one thread per QR screen
    private static final ExecutorService WAIT = Executors.newCachedThreadPool();

    private static final Map<Long, HttpURLConnection> activeWaits = new ConcurrentHashMap<>();
    private static final Set<Long> stoppedWaits =
            Collections.newSetFromMap(new ConcurrentHashMap<Long, Boolean>());

    public interface Callback { void done(boolean ok, String message); }

    public interface DataCallback { void done(boolean ok, String message, JSONObject data); }

    /** Called (on the main thread) after each payment upload gets an answer from the server.
     *  status: confirmed | recorded | duplicate | ambiguous | ignored */
    public interface TxListener { void onResult(String status, JSONObject body); }

    private static volatile TxListener txListener;

    public static void setTxListener(TxListener l) { txListener = l; }

    private static class Result { int code; String body; }

    // ---------- low level ----------

    private static Result post(String path, JSONObject json) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(BASE_URL + path).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(NORMAL_TIMEOUT_MS);
            c.setReadTimeout(NORMAL_TIMEOUT_MS);
            c.setRequestProperty("Content-Type", "application/json");
            c.setDoOutput(true);
            try (OutputStream os = c.getOutputStream()) {
                os.write(json.toString().getBytes(StandardCharsets.UTF_8));
            }
            Result r = new Result();
            r.code = c.getResponseCode();
            InputStream is = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
            r.body = is == null ? "" : readAll(is);
            return r;
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream is) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    private static String errorText(String body, String fallback) {
        if (body == null || body.trim().isEmpty()) return fallback;
        try {
            JSONObject o = new JSONObject(body);
            if (o.has("message")) return o.getString("message");
            if (o.has("title")) return o.getString("title");
        } catch (Exception ignored) { }
        return body.replace("\"", "").trim();
    }

    private static void reply(Callback cb, boolean ok, String msg) {
        if (cb == null) return;
        new Handler(Looper.getMainLooper()).post(() -> cb.done(ok, msg));
    }

    private static void replyData(DataCallback cb, boolean ok, String msg, JSONObject data) {
        if (cb == null) return;
        new Handler(Looper.getMainLooper()).post(() -> cb.done(ok, msg, data));
    }

    private static long customerId(Context app) {
        String cid = SecureStore.get(app, "cid");
        if (cid == null) return -1;
        try { return Long.parseLong(cid); } catch (Exception e) { return -1; }
    }

    // ---------- login ----------

    public static void sendOtp(String phone, Callback cb) {
        AUTH.execute(() -> {
            try {
                JSONObject j = new JSONObject();
                j.put("phoneNumber", phone);
                Result r = post("Payments/SendLoginOtp", j);
                if (r.code == 200) {
                    reply(cb, true, "OTP sent on WhatsApp. Enter it below.");
                } else {
                    reply(cb, false, errorText(r.body, "Could not send OTP (" + r.code + ")."));
                }
            } catch (Exception e) {
                reply(cb, false, "Network error. Check your connection.");
            }
        });
    }

    public static void verifyOtp(Context ctx, String phone, String otp, Callback cb) {
        final Context app = ctx.getApplicationContext();
        AUTH.execute(() -> {
            try {
                JSONObject j = new JSONObject();
                j.put("phoneNumber", phone);
                j.put("otpVal", Long.parseLong(otp));
                Result r = post("Payments/VerifyLoginOtp", j);
                if (r.code == 200) {
                    JSONObject o = new JSONObject(r.body);
                    // The customer id is stored encrypted and is never shown to the user
                    SecureStore.put(app, "cid", String.valueOf(o.getLong("id")));
                    flushPending(app);   // upload anything waiting
                    reply(cb, true, "Login successful.");
                } else {
                    reply(cb, false, errorText(r.body, "Wrong OTP."));
                }
            } catch (NumberFormatException e) {
                reply(cb, false, "Enter the numeric OTP.");
            } catch (Exception e) {
                reply(cb, false, "Network error. Check your connection.");
            }
        });
    }

    // ---------- received payments (phone app) ----------

    private static String iso(long millis) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(millis));
    }

    // Queue a received payment (with the hidden customer id) and try to upload it.
    // note = the transaction note read from the notification (starts with "GR"); the
    // server matches the payment to its QR request by this value.
    public static void sendTransaction(Context ctx, String name, String amount,
                                       String note, long timeMillis) {
        final Context app = ctx.getApplicationContext();
        IO.execute(() -> {
            long cid = customerId(app);
            if (cid <= 0) return;   // not logged in
            try {
                JSONObject tx = new JSONObject();
                tx.put("customerId", cid);
                tx.put("name", name == null ? "" : name);
                tx.put("amount", new BigDecimal(amount.replace(",", "")));
                tx.put("invoiceNumber", note == null ? "" : note);
                tx.put("transactionDateTime", iso(timeMillis));   // UTC; the server converts to IST

                JSONArray arr = new JSONArray(SecureStore.getOr(app, "pending", "[]"));
                arr.put(tx);
                SecureStore.put(app, "pending", arr.toString());
            } catch (Exception ignored) { }
            flush(app);
        });
    }

    // Retry anything that failed earlier (call on app open / after login)
    public static void flushPending(Context ctx) {
        final Context app = ctx.getApplicationContext();
        IO.execute(() -> flush(app));
    }

    // Must only run on the IO thread
    private static void flush(Context c) {
        try {
            if (customerId(c) <= 0) return;
            JSONArray arr = new JSONArray(SecureStore.getOr(c, "pending", "[]"));

            while (arr.length() > 0) {
                JSONObject tx = arr.getJSONObject(0);
                Result r = post("Payments/AddTransaction", tx);
                if (r.code >= 500) return;      // server trouble: try again later

                // 200 = handled (confirmed / recorded / duplicate / ambiguous / ignored)
                // 4xx = bad data: drop it so it can't block the queue
                if (r.code == 200) handleOutcome(c, tx, r.body);

                arr.remove(0);
                SecureStore.put(c, "pending", arr.toString());
            }
        } catch (Exception e) {
            // Network down: the queue stays saved and is retried later
        }
    }

    // What the server decided about one uploaded payment
    private static void handleOutcome(Context c, JSONObject tx, String body) {
        try {
            JSONObject o = new JSONObject(body);
            String status = o.optString("status", "");

            if ("ambiguous".equals(status)) {
                // The server stored nothing for this payment, so keep it here
                // until the operator settles it with confirmPayment().
                o.put("receivedAt", tx.optString("transactionDateTime"));
                JSONArray list = new JSONArray(SecureStore.getOr(c, "ambiguous", "[]"));
                list.put(o);
                SecureStore.put(c, "ambiguous", list.toString());
            }

            final TxListener l = txListener;
            if (l != null) {
                new Handler(Looper.getMainLooper()).post(() -> l.onResult(status, o));
            }
        } catch (Exception ignored) { }
    }

    // ---------- ambiguous payments (settled by hand) ----------

    /** Payments the server could not match to a single QR request. Each item has:
     *  payer {name, amount}, matches [{requestId, name, amount, requestedAt}], receivedAt. */
    public static JSONArray getAmbiguous(Context ctx) {
        try {
            return new JSONArray(SecureStore.getOr(ctx.getApplicationContext(), "ambiguous", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    /** Operator picks which QR request a payment belongs to.
     *  receivedAt = the "receivedAt" value of the ambiguous item being settled (or null). */
    public static void confirmPayment(Context ctx, long requestId, String payerName,
                                      String receivedAt, Callback cb) {
        final Context app = ctx.getApplicationContext();
        AUTH.execute(() -> {
            long cid = customerId(app);
            if (cid <= 0) { reply(cb, false, "Please log in again."); return; }
            try {
                JSONObject j = new JSONObject();
                j.put("customerId", cid);
                j.put("requestId", requestId);
                j.put("payerName", payerName == null ? "" : payerName);
                Result r = post("Payments/ConfirmPayment", j);
                if (r.code == 200) {
                    // Remove it from the local list first, then tell the screen,
                    // so the screen redraws without the settled item.
                    removeAmbiguous(app, receivedAt, () -> reply(cb, true, "Payment confirmed."));
                } else {
                    reply(cb, false, errorText(r.body, "Could not confirm (" + r.code + ")."));
                }
            } catch (Exception e) {
                reply(cb, false, "Network error. Check your connection.");
            }
        });
    }

    private static void removeAmbiguous(Context app, String receivedAt, Runnable then) {
        IO.execute(() -> {
            try {
                if (receivedAt == null) return;
                JSONArray list = new JSONArray(SecureStore.getOr(app, "ambiguous", "[]"));
                JSONArray keep = new JSONArray();
                for (int i = 0; i < list.length(); i++) {
                    JSONObject item = list.getJSONObject(i);
                    if (!receivedAt.equals(item.optString("receivedAt"))) keep.put(item);
                }
                SecureStore.put(app, "ambiguous", keep.toString());
            } catch (Exception ignored) {
            } finally {
                if (then != null) then.run();
            }
        });
    }

    // ---------- QR payment flow (QR screen) ----------

    /** Registers the amount and starts the payment window.
     *  invoiceNumber is required: the payment is matched to this request by it.
     *  data has: requestId, amount, invoiceNumber, windowSeconds, expiresAt */
    public static void createPaymentRequest(Context ctx, String name, String amount,
                                            String invoiceNumber, DataCallback cb) {
        final Context app = ctx.getApplicationContext();
        AUTH.execute(() -> {
            long cid = customerId(app);
            if (cid <= 0) { replyData(cb, false, "Please log in again.", null); return; }
            try {
                JSONObject j = new JSONObject();
                j.put("customerId", cid);
                j.put("name", name == null ? "" : name);
                j.put("invoiceNumber", invoiceNumber == null ? "" : invoiceNumber);
                j.put("amount", new BigDecimal(amount.replace(",", "")));
                Result r = post("Payments/CreatePaymentRequest", j);
                if (r.code == 200) {
                    replyData(cb, true, "QR request created.", new JSONObject(r.body));
                } else {
                    replyData(cb, false, errorText(r.body, "Could not create request (" + r.code + ")."), null);
                }
            } catch (NumberFormatException e) {
                replyData(cb, false, "Enter a valid amount.", null);
            } catch (Exception e) {
                replyData(cb, false, "Network error. Check your connection.", null);
            }
        });
    }

    /** Waits until the request is paid, cancelled or expired. The server holds each call for up
     *  to 50s and answers "pending" if nothing happened, so this repeats the call by itself.
     *  The callback fires once. ok = true only when status is "paid". data.status is one of:
     *  paid | cancelled | expired | not_found */
    public static void waitForPayment(long requestId, DataCallback cb) {
        stoppedWaits.remove(requestId);
        WAIT.execute(() -> {
            int failures = 0;
            while (!stoppedWaits.contains(requestId)) {
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(
                            BASE_URL + "Payments/WaitForPayment/" + requestId).openConnection();
                    c.setRequestMethod("GET");
                    c.setConnectTimeout(NORMAL_TIMEOUT_MS);
                    c.setReadTimeout(WAIT_READ_TIMEOUT_MS);
                    activeWaits.put(requestId, c);

                    int code = c.getResponseCode();
                    InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
                    String body = is == null ? "" : readAll(is);
                    failures = 0;

                    if (code != 200 || body.trim().isEmpty()) {
                        replyData(cb, false, errorText(body, "Waiting failed (" + code + ")."), null);
                        return;
                    }

                    JSONObject o = new JSONObject(body);
                    String status = o.optString("status", "");
                    if ("pending".equals(status)) continue;   // still waiting: ask again

                    String msg;
                    switch (status) {
                        case "paid":      msg = "Payment received."; break;
                        case "cancelled": msg = "Payment cancelled."; break;
                        default:          msg = o.optString("message", "Payment window ended.");
                    }
                    replyData(cb, "paid".equals(status), msg, o);
                    return;
                } catch (Exception e) {
                    if (stoppedWaits.contains(requestId)) return;   // screen closed on purpose
                    if (++failures >= 5) {
                        replyData(cb, false, "Network error. Check your connection.", null);
                        return;
                    }
                    try { Thread.sleep(3000); } catch (InterruptedException ie) { return; }
                } finally {
                    activeWaits.remove(requestId);
                    if (c != null) c.disconnect();
                }
            }
        });
    }

    /** Call when the QR screen closes without cancelling. Ends the waiting call on the
     *  server too, because dropping the connection cancels it there. */
    public static void stopWaiting(long requestId) {
        stoppedWaits.add(requestId);
        final HttpURLConnection c = activeWaits.get(requestId);
        if (c != null) AUTH.execute(c::disconnect);
    }

    /** The user ends the QR payment manually. The server deletes the pending row and
     *  the waiting call finishes with status "cancelled". */
    public static void cancelPaymentRequest(Context ctx, long requestId, Callback cb) {
        final Context app = ctx.getApplicationContext();
        AUTH.execute(() -> {
            long cid = customerId(app);
            if (cid <= 0) { reply(cb, false, "Please log in again."); return; }
            try {
                JSONObject j = new JSONObject();
                j.put("customerId", cid);
                j.put("requestId", requestId);
                Result r = post("Payments/CancelPaymentRequest", j);
                if (r.code == 200) {
                    reply(cb, true, "Payment cancelled.");
                } else {
                    reply(cb, false, errorText(r.body, "Could not cancel (" + r.code + ")."));
                }
            } catch (Exception e) {
                reply(cb, false, "Network error. Check your connection.");
            }
        });
    }
}