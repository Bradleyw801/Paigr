package com.textrelay.relay;

import org.json.JSONObject;

import java.security.GeneralSecurityException;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This phone's place in a private inbox (v2): the server, the inbox's id, this device's own token, and the inbox key.
 * Everything that says who or what is sealed with keys made from the inbox key before it leaves the phone; the server
 * sees conversation ids and fingerprints only as keyed hashes (see PROTOCOL.md).
 */
final class Link {
    final String server;
    final String tid;
    final String token;
    private final byte[] key;
    private final byte[] enc;
    private final byte[] mac;
    /** <tid>/<keyed conversation id> -> this phone's own id, for what the server sends back; shared by every Link. */
    private static final Map<String, String> LOCAL = new ConcurrentHashMap<>();

    Link(String server, String tid, String token, byte[] key) {
        this.server = server.endsWith("/") ? server.substring(0, server.length() - 1) : server;
        this.tid = tid;
        this.token = token;
        this.key = key.clone();
        this.enc = Crypto.hkdf(key, "textrelay v2 enc");
        this.mac = Crypto.hkdf(key, "textrelay v2 mac");
    }

    /** As stored on this phone, or null when it isn't a whole link. */
    static Link parse(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(json);
            byte[] k = Crypto.unb64u(o.getString("key"));
            String tid = o.getString("tid");
            String token = o.getString("token");
            if (k.length != 32 || !tid.matches("[0-9a-f]{32}") || token.length() != 43) return null;
            return new Link(o.getString("server"), tid, token, k);
        } catch (org.json.JSONException | IllegalArgumentException e) {
            return null;
        }
    }

    String toJson() {
        return Json.obj("v", 2, "server", server, "tid", tid, "token", token, "key", Crypto.b64u(key)).toString();
    }

    String keyB64() {
        return Crypto.b64u(key);
    }

    String convId(String localConv) {
        String w = Crypto.hex(Crypto.hmac(mac, Crypto.utf8("conv:" + localConv))).substring(0, 32);
        LOCAL.put(tid + "/" + w, localConv);
        return w;
    }

    /** Tests watch keyed fingerprints go by, to turn them back into the relay's own. */
    static volatile java.util.function.BiConsumer<String, String> onFp;

    /** Tests: a phone that has worked out none of its conversations' keyed ids yet. */
    static void forgetForTests() {
        LOCAL.clear();
    }

    String fpId(String fp) {
        String w = Crypto.hex(Crypto.hmac(mac, Crypto.utf8("fp:" + fp))).substring(0, 16);
        java.util.function.BiConsumer<String, String> watch = onFp;
        if (watch != null) watch.accept(w, fp);
        return w;
    }

    /** "" when there is nothing two copies could share. */
    String matchToken(String dir, String text, String mime) {
        String t = Crypto.matchText(text, mime);
        if (t.isEmpty()) return "";
        return Crypto.hex(Crypto.hmac(mac, Crypto.utf8("mt:" + ("out".equals(dir) ? "out" : "in") + ":" + t))).substring(0, 32);
    }

    /** This phone's id for a conversation the server named, or null when it isn't one of this phone's. */
    String localConv(String wire, Collection<String> known) {
        String hit = LOCAL.get(tid + "/" + wire);
        if (hit != null) return hit;
        if (known != null) for (String c : known) convId(c);
        return LOCAL.get(tid + "/" + wire);
    }

    String seal(JSONObject value, String aad) {
        return Crypto.sealJson(enc, value, aad);
    }

    JSONObject open(String sealed, String aad) throws GeneralSecurityException {
        return Crypto.openJson(enc, sealed, aad);
    }

    /** A file, sealed for one place: Crypto.aadFile (its conversation, message and kind). */
    byte[] sealFile(byte[] bytes, String aad) {
        return Crypto.sealBytes(enc, bytes, aad);
    }

    byte[] openFile(byte[] sealed, String aad) throws GeneralSecurityException {
        return Crypto.openBytes(enc, sealed, aad);
    }

    /** The relay phone's sealed word that conversation `from` was folded into `into` (keyed ids). */
    String mergeRecord(String wireFrom, String wireInto) {
        return Crypto.sealJson(enc, Json.obj("from", wireFrom, "into", wireInto), Crypto.aadMerge(wireInto));
    }

    /** The box an invite or an answer carries: what lets another device into this inbox. */
    String box(byte[] boxKey) {
        return Crypto.sealJson(boxKey, Json.obj("v", 2, "tid", tid, "key", Crypto.b64u(key)), Crypto.AAD_BOX);
    }

    /** What this phone files on the server to get back in after a reinstall; only this phone can open it. */
    String wrap(byte[] wrapKey) {
        return Crypto.sealJson(wrapKey, Json.obj("v", 2, "server", server, "tid", tid, "token", token, "key", Crypto.b64u(key)), Crypto.AAD_WRAP);
    }

    static Link unwrap(String server, byte[] wrapKey, String wrap) throws GeneralSecurityException {
        JSONObject o = Crypto.openJson(wrapKey, wrap, Crypto.AAD_WRAP);
        Link l = parse(Json.obj("server", o.optString("server", server), "tid", o.optString("tid"), "token", o.optString("token"), "key", o.optString("key")).toString());
        if (l == null) throw new GeneralSecurityException("the wrap doesn't hold a whole link");
        return l;
    }

    boolean sameAs(Link other) {
        return other != null && tid.equals(other.tid) && token.equals(other.token) && java.util.Arrays.equals(key, other.key);
    }
}
