package com.textrelay.relay;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * One-time linking codes, the same scheme Texts uses: 12 random symbols (60 bits) shown as XXXX-XXXX-XXXX and carried
 * in a QR code that Texts' built-in scanner reads. Only the code's SHA-256 is registered with the server; a code works
 * once, for one role, and expires.
 *
 * Two kinds, both made here, on the relay phone: LINK (under Link a device, for a phone running Texts) and RELAY (shown
 * while this phone isn't connected yet; a phone already linked scans it to let this one in).
 */
final class Pairing {
    static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";   // 32 symbols, no I, L, O or U
    static final int MINUTES = 15;
    static final String LINK = "LINK";
    static final String RELAY = "RELAY";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Pairing() {}

    static String newCode() {
        byte[] bytes = new byte[12];
        RANDOM.nextBytes(bytes);
        StringBuilder s = new StringBuilder();
        for (byte b : bytes) s.append(ALPHABET.charAt(b & 31));   // 256 is a multiple of 32: every symbol equally likely
        return s.toString();
    }

    /** XXXX-XXXX-XXXX, easier to read out and type. */
    static String pretty(String code) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < code.length(); i++) {
            if (i > 0 && i % 4 == 0) s.append('-');
            s.append(code.charAt(i));
        }
        return s.toString();
    }

    /** Case, spaces and dashes don't matter; O, I and L read as 0, 1 and 1. The same rule as the server. */
    static String normalize(String v) {
        if (v == null) return "";
        String s = v.toLowerCase(Locale.US).replaceAll("[^a-z0-9]", "").replace('o', '0').replace('i', '1').replace('l', '1');
        return s.length() > 64 ? s.substring(0, 64) : s;
    }

    /**
     * This phone, as the inbox remembers it: the SHA-256 of the role and the app's Android ID. Android keeps that ID the
     * same when the app is reinstalled on this phone and gives a different one on every other phone (and to apps signed
     * by anyone else), so a reinstalled Relay or Texts gets back in by itself. Null when the phone has no usable ID.
     */
    static String phoneTag(android.content.Context context, String role) {
        String id = androidId(context);
        return id == null ? null : sha256Hex("textrelay:" + role + ":" + id);
    }

    /** The Android ID this app has on this phone, lower case; null when there is none worth trusting. */
    @android.annotation.SuppressLint("HardwareIds")
    static String androidId(android.content.Context context) {
        String id;
        try {
            id = android.provider.Settings.Secure.getString(context.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
        } catch (RuntimeException e) {
            return null;
        }
        if (id == null) return null;
        id = id.trim().toLowerCase(Locale.US);
        if (id.length() < 8 || id.equals("9774d56d682e549c") || id.matches("0+")) return null;   // missing, or the old shared emulator value
        return id;
    }

    static String sha256Hex(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format(Locale.US, "%02x", x));
            return b.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** What the QR code holds: TEXTRELAY:LINK:<code> or TEXTRELAY:RELAY:<code>. No address; Texts already knows its inbox. */
    static String payload(String kind, String code) {
        return "TEXTRELAY:" + kind + ":" + code;
    }

    /** A QR code with crisp square modules and the standard quiet zone. */
    static Bitmap qr(String text, int targetPx) {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.MARGIN, 4);
        BitMatrix m;
        try {
            m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints);
        } catch (WriterException e) {
            throw new IllegalArgumentException(e);
        }
        int size = m.getWidth();
        int scale = Math.max(1, targetPx / size);
        int px = size * scale;
        int[] pixels = new int[px * px];
        for (int y = 0; y < px; y++) {
            for (int x = 0; x < px; x++) {
                pixels[y * px + x] = m.get(x / scale, y / scale) ? Color.BLACK : Color.WHITE;
            }
        }
        Bitmap bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        bmp.setPixels(pixels, 0, px, 0, 0, px, px);
        return bmp;
    }
}
