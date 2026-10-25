package com.textrelay.texts;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Text Relay v2's encryption, as PROTOCOL.md describes it and web/tr2.js does it: HKDF-SHA256, PBKDF2-HMAC-SHA256,
 * AES-256-GCM sealing, and HMAC ids. Checked against the same test vectors as the web page.
 */
final class Crypto {
    static final int ITERATIONS = 150_000;
    static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";   // 32 symbols, no I, L, O or U
    static final String AAD_BOX = "tr2:box";
    static final String AAD_WRAP = "tr2:wrap";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Crypto() {}

    // ---------------------------------------------------------------- what each seal is bound to
    // Everything sealed for a conversation opens only as that conversation's (its keyed id), so the server can't show
    // one conversation's messages, name or pictures as another's, or point a reply at someone else (PROTOCOL.md). A
    // message is bound to its direction too: a text someone sent can't be shown as one sent from here, or the reverse.

    static String aadMessage(String wireConv, String dir) {
        return "tr2:m:" + wireConv + ":" + ("out".equals(dir) ? "out" : "in");
    }

    static String aadName(String wireConv) {
        return "tr2:n:" + wireConv;
    }

    /** One stored file: its conversation, its message's time and fingerprint, and whether it's the preview. */
    static String aadFile(String wireConv, long ts, String wireFp, String kind) {
        return "tr2:f:" + wireConv + ":" + pad13(ts) + ":" + wireFp + ":" + ("preview".equals(kind) || "p".equals(kind) ? "p" : "o");
    }

    /** The relay phone's word that a conversation was folded into this one. */
    static String aadMerge(String wireInto) {
        return "tr2:merge:" + wireInto;
    }

    /** A reply for the relay phone to send, for this conversation alone. */
    static String aadOut(String wireConv) {
        return "tr2:o:" + wireConv;
    }

    static String pad13(long n) {
        String s = Long.toString(Math.max(0, n));
        StringBuilder b = new StringBuilder();
        for (int i = s.length(); i < 13; i++) b.append('0');
        return b.append(s).toString();
    }

    // ---------------------------------------------------------------- primitives

    static byte[] random(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.length == 0 ? new byte[32] : key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** HKDF-SHA256 with an empty salt, 32 bytes out (one expand block). */
    static byte[] hkdf(byte[] ikm, String info) {
        byte[] prk = hmac(new byte[32], ikm);
        byte[] i = utf8(info);
        byte[] t = Arrays.copyOf(i, i.length + 1);
        t[i.length] = 1;
        return hmac(prk, t);
    }

    /** PBKDF2-HMAC-SHA256, 32 bytes out, written out here so every device derives exactly the same bytes. */
    static byte[] pbkdf2(String password, String salt) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(password.isEmpty() ? new byte[32] : utf8(password), "HmacSHA256"));
            byte[] s = utf8(salt);
            mac.update(s);
            mac.update(new byte[] { 0, 0, 0, 1 });
            byte[] u = mac.doFinal();
            byte[] out = u.clone();
            for (int n = 1; n < ITERATIONS; n++) {
                u = mac.doFinal(u);
                for (int j = 0; j < out.length; j++) out[j] ^= u[j];
            }
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** AES-256-GCM: iv ‖ ciphertext ‖ tag. */
    static byte[] sealBytes(byte[] key, byte[] plain, String aad) {
        return sealBytes(key, plain, aad, random(12));
    }

    static byte[] sealBytes(byte[] key, byte[] plain, String aad, byte[] iv) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            c.updateAAD(utf8(aad));
            byte[] ct = c.doFinal(plain);
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] openBytes(byte[] key, byte[] sealed, String aad) throws GeneralSecurityException {
        if (sealed == null || sealed.length < 28) throw new GeneralSecurityException("sealed data too short");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, sealed, 0, 12));
        c.updateAAD(utf8(aad));
        return c.doFinal(sealed, 12, sealed.length - 12);
    }

    static String seal(byte[] key, byte[] plain, String aad) {
        return "e1." + b64u(sealBytes(key, plain, aad));
    }

    static String seal(byte[] key, byte[] plain, String aad, byte[] iv) {
        return "e1." + b64u(sealBytes(key, plain, aad, iv));
    }

    static byte[] open(byte[] key, String sealed, String aad) throws GeneralSecurityException {
        if (sealed == null || !sealed.startsWith("e1.")) throw new GeneralSecurityException("not sealed");
        byte[] raw;
        try {
            raw = unb64u(sealed.substring(3));
        } catch (IllegalArgumentException e) {
            throw new GeneralSecurityException("not sealed", e);
        }
        return openBytes(key, raw, aad);
    }

    static String sealJson(byte[] key, JSONObject value, String aad) {
        return seal(key, utf8(value.toString()), aad);
    }

    static JSONObject openJson(byte[] key, String sealed, String aad) throws GeneralSecurityException {
        try {
            return new JSONObject(new String(open(key, sealed, aad), StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new GeneralSecurityException("sealed data is not JSON", e);
        }
    }

    // ---------------------------------------------------------------- encodings

    static String b64u(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /**
     * Base64url without padding, in its one spelling: padding, or other bits in the last character's unused part, is
     * refused, so the server can't write one sealed value several ways (each would otherwise count as another).
     */
    static byte[] unb64u(String s) {
        if (s == null || s.indexOf('=') >= 0) throw new IllegalArgumentException("not base64url");
        byte[] b = Base64.getUrlDecoder().decode(s);
        if (!b64u(b).equals(s)) throw new IllegalArgumentException("not base64url");
        return b;
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) s.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
        return s.toString();
    }

    static String newToken() {
        return b64u(random(32));
    }

    static String newTid() {
        return hex(random(16));
    }

    // ---------------------------------------------------------------- linking

    static String newCode() {
        StringBuilder s = new StringBuilder();
        for (byte b : random(12)) s.append(ALPHABET.charAt(b & 31));   // 256 is a multiple of 32: every symbol equally likely
        return s.toString();
    }

    /** Case, spaces and dashes don't matter; O, I and L read as 0, 1 and 1. */
    static String normCode(String v) {
        if (v == null) return "";
        String s = v.toLowerCase(Locale.US).replaceAll("[^a-z0-9]", "").replace('o', '0').replace('i', '1').replace('l', '1');
        return s.length() > 64 ? s.substring(0, 64) : s;
    }

    static final class LinkSecrets {
        final String lookup;
        final byte[] boxKey;

        LinkSecrets(String lookup, byte[] boxKey) {
            this.lookup = lookup;
            this.boxKey = boxKey;
        }
    }

    /** What a code stands for: where the server files its offer, and the key only the two devices know. */
    static LinkSecrets linkSecrets(String code) {
        byte[] seed = pbkdf2(normCode(code), "textrelay v2 link");
        return new LinkSecrets(hex(hkdf(seed, "textrelay v2 lookup")), hkdf(seed, "textrelay v2 box"));
    }

    static final class PhoneSecrets {
        final String tag;
        final byte[] wrapKey;

        PhoneSecrets(String tag, byte[] wrapKey) {
            this.tag = tag;
            this.wrapKey = wrapKey;
        }
    }

    /** This phone's own secrets, from the Android ID the app has here: its tag on the server and the key to its wrap. */
    static PhoneSecrets phoneSecrets(String androidId, String role) {
        byte[] seed = pbkdf2(androidId.trim().toLowerCase(Locale.US), "textrelay v2 phone " + role);
        return new PhoneSecrets(hex(hkdf(seed, "textrelay v2 tag")), hkdf(seed, "textrelay v2 wrap"));
    }

    /**
     * What Relay's QR code shows: TEXTRELAY2:<server host>/<CODE>. Not a web address on purpose: a camera app that isn't
     * Texts shows it as text, rather than opening a page from the server that could read the code (PROTOCOL.md).
     */
    static String linkQr(String server, String code) {
        String s = server.replaceFirst("(?i)^https?://", "");
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return "TEXTRELAY2:" + s.toLowerCase(Locale.US) + "/" + code.toUpperCase(Locale.US);
    }

    // ---------------------------------------------------------------- what two copies of a message share

    /** JavaScript's \s: what the server's v1 rule (and tr2.js) collapse and trim. */
    private static final String JS_SPACE = "\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF";
    private static final Pattern SPACES = Pattern.compile("[" + JS_SPACE + "]+");
    private static final Pattern EDGES = Pattern.compile("^[" + JS_SPACE + "]+|[" + JS_SPACE + "]+$");
    private static final Pattern ZERO_WIDTH = Pattern.compile("[\\u200B-\\u200D\\uFEFF]");
    private static final Pattern LEADING = Pattern.compile("^[^\\p{L}\\p{N}]+");
    private static final Pattern MEDIA_WORD = Pattern.compile(
            "^(?:sent an? )?(photo|picture|image|sticker|gif|video|voice message|audio|attachment|file)$", Pattern.CASE_INSENSITIVE);

    static String normText(String t) {
        if (t == null) return "";
        String s = Normalizer.normalize(t, Normalizer.Form.NFKC);
        s = ZERO_WIDTH.matcher(s).replaceAll("");
        s = SPACES.matcher(s).replaceAll(" ");
        return EDGES.matcher(s).replaceAll("");
    }

    static String mediaLabel(String mime) {
        if (mime == null || mime.isEmpty()) return "";
        String t = mime.toLowerCase(Locale.US);
        if (t.startsWith("image/")) return "Picture";
        if (t.startsWith("video/")) return "Video";
        if (t.startsWith("audio/")) return "Voice message";
        return "Attachment";
    }

    /** v1's rule: the text, or for a bare media word or an attachment without text, the kind of attachment. */
    static String matchText(String text, String mime) {
        String t = normText(text);
        Matcher w = MEDIA_WORD.matcher(LEADING.matcher(t).replaceFirst(""));
        if (w.matches()) {
            String x = w.group(1).toLowerCase(Locale.US);
            return "\u0000" + (x.equals("video") ? "Video" : x.equals("voice message") || x.equals("audio") ? "Voice message"
                    : x.equals("attachment") || x.equals("file") ? "Attachment" : "Picture");
        }
        if (t.isEmpty() && mime != null && !mime.isEmpty()) return "\u0000" + mediaLabel(mime);
        return t;
    }
}
