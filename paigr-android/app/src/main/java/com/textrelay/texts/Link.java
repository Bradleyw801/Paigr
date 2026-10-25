package com.textrelay.texts;

import org.json.JSONException;
import org.json.JSONObject;

import java.security.GeneralSecurityException;
import java.util.Iterator;

/**
 * This phone's place in a private inbox (v2): the server, the inbox's id, this device's token, and the inbox key.
 * The page links and keeps it here; the background checks use it to open what they announce (see PROTOCOL.md).
 */
final class Link {
    final String server;
    final String tid;
    final String token;
    private final byte[] key;
    private final byte[] enc;

    Link(String server, String tid, String token, byte[] key) {
        this.server = server.endsWith("/") ? server.substring(0, server.length() - 1) : server;
        this.tid = tid;
        this.token = token;
        this.key = key.clone();
        this.enc = Crypto.hkdf(key, "textrelay v2 enc");
    }

    /** { tid, token, key[, server] }, as the page hands it over or this phone stored it; null when it isn't whole. */
    static Link parse(String json, String defaultServer) {
        if (json == null || json.trim().isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(json);
            byte[] k = Crypto.unb64u(o.getString("key"));
            String tid = o.getString("tid");
            String token = o.getString("token");
            String server = o.optString("server", "");
            if (k.length != 32 || !tid.matches("[0-9a-f]{32}") || token.length() != 43) return null;
            return new Link(server.startsWith("http") ? server : defaultServer, tid, token, k);
        } catch (JSONException | IllegalArgumentException e) {
            return null;
        }
    }

    String toJson() {
        try {
            return new JSONObject().put("v", 2).put("server", server).put("tid", tid).put("token", token).put("key", Crypto.b64u(key)).toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** What the page needs: no server (it's the page's own). */
    String forPage() {
        try {
            return new JSONObject().put("tid", tid).put("token", token).put("key", Crypto.b64u(key)).toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A conversation and the ones the relay phone's sealed merge records (`merged` in its summary) say were folded into
     * it, directly or through each other: what was sealed for any of them counts as this conversation's.
     */
    java.util.List<String> aliases(String conv, org.json.JSONArray merged) {
        java.util.List<String> set = new java.util.ArrayList<>();
        set.add(conv);
        if (merged == null) return set;
        java.util.Set<Integer> done = new java.util.HashSet<>();
        for (boolean grew = true; grew; ) {
            grew = false;
            for (int i = 0; i < merged.length(); i++) {
                if (done.contains(i)) continue;
                String r = merged.optString(i, "");
                for (String into : new java.util.ArrayList<>(set)) {
                    JSONObject v;
                    try {
                        v = Crypto.openJson(enc, r, Crypto.aadMerge(into));
                    } catch (GeneralSecurityException e) {
                        continue;
                    }
                    if (!into.equals(v.optString("into"))) continue;
                    done.add(i);
                    String from = v.optString("from", "");
                    if (!from.isEmpty() && !set.contains(from)) {
                        set.add(from);
                        grew = true;
                    }
                    break;
                }
            }
        }
        return set;
    }

    private JSONObject openFor(String sealed, java.util.List<String> convs, java.util.function.Function<String, String> aad) {
        for (String c : convs) {
            try {
                return Crypto.openJson(enc, sealed, aad.apply(c));
            } catch (GeneralSecurityException ignored) {
                // not this one
            }
        }
        return null;
    }

    JSONObject openMessage(JSONObject m) {
        return openMessage(m, null);
    }

    /**
     * A message from the inbox, opened as its conversation's (or one merged into it, per `merged`) and as its direction:
     * its text, sender, conversation name, time and attachment details, in the fields the notifications read. One that
     * doesn't open that way, or carries nothing sealed, says only that a message came, never someone else's name or
     * words, and nothing the server wrote: only its keys, conversation, direction and flags are taken from the server,
     * and of its attachment only where the files are, for files sealed for this message (its payload's time and
     * fingerprint).
     */
    JSONObject openMessage(JSONObject m, org.json.JSONArray merged) {
        try {
            JSONObject out = new JSONObject();
            String dir = "out".equals(m.optString("dir")) ? "out" : "in";
            for (String f : new String[] { "k", "conv", "ts", "status", "read", "history", "via", "src" }) if (m.has(f)) out.put(f, m.get(f));
            out.put("dir", dir);
            java.util.List<String> convs = aliases(m.optString("conv"), merged);
            String sealed = m.optString("e", "");
            JSONObject p = sealed.isEmpty() ? null : openFor(sealed, convs, c -> Crypto.aadMessage(c, dir));
            if (p == null) {
                // Not even its conversation's name: the server keeps every conversation's sealed name, and could
                // otherwise announce "Mom: New message" whenever it liked.
                out.put("text", "New message");
                out.put("sender", "");
                out.put("name", "");
                out.put("group", false);
                out.put("unchecked", true);
                return out;
            }
            JSONObject n = m.optString("ne", "").isEmpty() ? null : openFor(m.optString("ne"), convs, Crypto::aadName);
            String name = n != null ? n.optString("name", "") : "";
            out.put("group", (n != null && n.optBoolean("group")) || p.optBoolean("group"));
            out.put("text", p.optString("text", ""));
            out.put("sender", p.optString("sender", ""));
            out.put("name", name.isEmpty() ? p.optString("name", "") : name);
            if (p.optLong("ts", 0) > 0) out.put("ts", p.optLong("ts"));   // the time it was sealed with
            out.put("sealedAt", Math.max(0, p.optLong("ts", 0)));
            // Which message this is, as its payload names it (its own time and keyed fingerprint): the same for every
            // copy, sealed again or not, so the server can't announce one twice (Sync, Prefs.markSeen).
            String pfp = p.optString("fp", "");
            if (p.optLong("ts", 0) > 0 && pfp.matches("[a-z0-9]{1,40}")) out.put("sealedId", Crypto.pad13(p.optLong("ts")) + "-" + pfp);
            JSONObject about = p.optJSONObject("media");
            if (about != null) {
                JSONObject media = new JSONObject();
                if (about.has("mime")) media.put("mime", about.optString("mime", ""));
                for (String f : new String[] { "w", "h", "bytes" }) if (about.optLong(f, 0) > 0) media.put(f, about.optLong(f));
                JSONObject where = m.optJSONObject("media");
                String stem = p.has("fp") && p.optLong("ts", -1) >= 0 ? Crypto.pad13(p.optLong("ts")) + "-" + p.optString("fp") : null;
                for (String[] f : new String[][] { { "path", "o" }, { "preview", "p" } }) {
                    String at = where != null ? where.optString(f[0], "") : "";
                    if (at.isEmpty()) continue;
                    if (stem != null && ownFile(at, convs, stem, f[1])) media.put(f[0], at);
                    else media.put("blocked", true);
                }
                out.put("media", media);
            }
            return out;
        } catch (JSONException e) {
            try {
                return new JSONObject().put("conv", m.optString("conv")).put("dir", "in").put("text", "New message").put("unchecked", true);
            } catch (JSONException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
    }

    private static final java.util.regex.Pattern MEDIA_PATH =
            java.util.regex.Pattern.compile("^/media/v2/([a-f0-9]{32})/([a-z0-9_-]{1,64})/([0-9]{13})-([a-z0-9]{8,32})-([op])\\.enc$");

    /** A stored file of this inbox, of one of these conversations, of the message with this stem, of this kind. */
    private boolean ownFile(String path, java.util.List<String> convs, String stem, String kind) {
        java.util.regex.Matcher x = MEDIA_PATH.matcher(path);
        return x.matches() && tid.equals(x.group(1)) && convs.contains(x.group(2)) && stem.equals(x.group(3) + "-" + x.group(4)) && kind.equals(x.group(5));
    }

    /** What this phone files on the server to get back in after a reinstall; only this phone can open it. */
    String wrap(byte[] wrapKey) {
        try {
            JSONObject w = new JSONObject().put("v", 2).put("server", server).put("tid", tid).put("token", token).put("key", Crypto.b64u(key));
            return Crypto.sealJson(wrapKey, w, Crypto.AAD_WRAP);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    static Link unwrap(String server, byte[] wrapKey, String wrap) throws GeneralSecurityException {
        JSONObject o = Crypto.openJson(wrapKey, wrap, Crypto.AAD_WRAP);
        Link l = parse(o.toString(), server);
        if (l == null) throw new GeneralSecurityException("the wrap doesn't hold a whole link");
        return l;
    }

    boolean sameAs(Link other) {
        return other != null && tid.equals(other.tid) && token.equals(other.token) && java.util.Arrays.equals(key, other.key);
    }

    /** One sealed value of this inbox, opened as `aad`. */
    JSONObject open(String sealed, String aad) throws GeneralSecurityException {
        return Crypto.openJson(enc, sealed, aad);
    }

    /** Seals a value for this inbox, bound to `aad`. */
    String seal(JSONObject value, String aad) {
        return Crypto.sealJson(enc, value, aad);
    }

    /** One stored file of this inbox, opened as `aad` (its conversation, message and kind). */
    byte[] openFile(byte[] sealed, String aad) throws GeneralSecurityException {
        return Crypto.openBytes(enc, sealed, aad);
    }

    /** A file sealed for this inbox, bound to `aad` (its conversation, message and kind), to store. */
    byte[] sealFile(byte[] plain, String aad) {
        return Crypto.sealBytes(enc, plain, aad);
    }

    /** The keyed id of one of this phone's own fingerprints, as the relay phone makes them (tr2.js fpId). */
    String fpId(String local) {
        return Crypto.hex(mac("fp:" + local)).substring(0, 16);
    }

    private volatile byte[] macKey;

    /** The inbox's keyed hash (HMAC with K's mac key), as the relay phone and the page use it for ids. */
    byte[] mac(String s) {
        byte[] m = macKey;
        if (m == null) macKey = m = Crypto.hkdf(key, "textrelay v2 mac");
        return Crypto.hmac(m, Crypto.utf8(s));
    }

    /** What two copies of a message share, as the server compares it: "" when there is nothing to compare (tr2.js). */
    String matchToken(String dir, String text, String mime) {
        String t = Crypto.matchText(text, mime);
        return t.isEmpty() ? "" : Crypto.hex(mac("mt:" + ("out".equals(dir) ? "out" : "in") + ":" + t)).substring(0, 32);
    }

    /** The inbox key, as a link carries it. */
    String keyB64() {
        return Crypto.b64u(key);
    }

    /** Tests seal what the fake inbox sends. */
    String sealForTests(JSONObject value, String aad) {
        return Crypto.sealJson(enc, value, aad);
    }

    /** Tests: the relay phone's sealed word that `from` was folded into `into`. */
    String mergeRecordForTests(String from, String into) {
        try {
            return Crypto.sealJson(enc, new JSONObject().put("from", from).put("into", into), Crypto.aadMerge(into));
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Tests: the keyed id the relay phone gives one of its conversations. */
    String convIdForTests(String local) {
        byte[] mac = Crypto.hkdf(key, "textrelay v2 mac");
        return Crypto.hex(Crypto.hmac(mac, Crypto.utf8("conv:" + local))).substring(0, 32);
    }
}
