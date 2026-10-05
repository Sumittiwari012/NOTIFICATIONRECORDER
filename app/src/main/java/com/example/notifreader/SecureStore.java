package com.example.notifreader;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Small encrypted key-value store.
 *
 * Values are encrypted with AES-256-GCM using a key that lives in the Android
 * Keystore (the key never leaves the device's secure storage) and the encrypted
 * text is kept in SharedPreferences. Needs no extra Gradle dependency.
 *
 * Used for: "cid" (the customer id) and "pending" (payments not uploaded yet).
 */
public final class SecureStore {

    private static final String PREFS = "secure_store";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "notifreader_secure_key";
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final Object LOCK = new Object();

    private SecureStore() { }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) {
            return (SecretKey) ks.getKey(KEY_ALIAS, null);
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        kg.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }

    // "base64(iv):base64(ciphertext)"
    private static String encrypt(String plain) throws Exception {
        Cipher c = Cipher.getInstance(TRANSFORM);
        c.init(Cipher.ENCRYPT_MODE, key());
        byte[] iv = c.getIV();
        byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(iv, Base64.NO_WRAP) + ":"
                + Base64.encodeToString(ct, Base64.NO_WRAP);
    }

    private static String decrypt(String stored) throws Exception {
        int sep = stored.indexOf(':');
        byte[] iv = Base64.decode(stored.substring(0, sep), Base64.NO_WRAP);
        byte[] ct = Base64.decode(stored.substring(sep + 1), Base64.NO_WRAP);
        Cipher c = Cipher.getInstance(TRANSFORM);
        c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(GCM_TAG_BITS, iv));
        return new String(c.doFinal(ct), StandardCharsets.UTF_8);
    }

    /** Saves a value (encrypted). A null value removes the key. */
    public static void put(Context ctx, String name, String value) {
        synchronized (LOCK) {
            SharedPreferences.Editor ed = prefs(ctx).edit();
            try {
                if (value == null) {
                    ed.remove(name);
                } else {
                    ed.putString(name, encrypt(value));
                }
            } catch (Exception e) {
                // Could not encrypt: store nothing rather than store plain text
                ed.remove(name);
            }
            ed.apply();
        }
    }

    /** Returns the value, or null if it was never saved or can't be read any more. */
    public static String get(Context ctx, String name) {
        synchronized (LOCK) {
            String stored = prefs(ctx).getString(name, null);
            if (stored == null) return null;
            try {
                return decrypt(stored);
            } catch (Exception e) {
                // Key was lost (e.g. restored from a backup onto another phone): treat as empty
                prefs(ctx).edit().remove(name).apply();
                return null;
            }
        }
    }

    /** Like get(), but returns the given default when there is no value. */
    public static String getOr(Context ctx, String name, String fallback) {
        String v = get(ctx, name);
        return v == null ? fallback : v;
    }

    /** Deletes everything (used by "Log out"). */
    public static void clearAll(Context ctx) {
        synchronized (LOCK) {
            prefs(ctx).edit().clear().apply();
        }
    }
}
