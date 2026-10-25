package com.textrelay.texts;

import android.content.Context;
import android.content.SharedPreferences;

/** Everything the app remembers: the inbox address and device key, how far it has read, and how it alerts. */
final class Prefs {
    static final String ALERT_NORMAL = "normal";
    static final String ALERT_QUIET = "quiet";
    static final String ALERT_OFF = "off";
    /**
     * Messages remembered as announced, so the log's overlapping re-reads never announce one twice: up to this many keys,
     * and as many seals of messages that opened here.
     */
    static final int SEEN_KEEP = 600;
    /** The keys the inbox gives messages: "m:", the time, and a short id of letters and digits. */
    private static final java.util.regex.Pattern MESSAGE_KEY = java.util.regex.Pattern.compile("^m:[0-9]{1,19}:[a-z0-9]{1,40}$");

    private final SharedPreferences sp;
    private final String defaultServer;
    private final Object lock = new Object();
    private final Context app;

    Prefs(Context context) {
        app = context.getApplicationContext();
        sp = app.getSharedPreferences("texts", Context.MODE_PRIVATE);
        defaultServer = app.getString(R.string.default_server);
        // A link stored by a build from before these were kept: it counts as linked, on this phone. The first version's
        // key belongs to this phone the same way.
        if (sp.contains("link2") && (!sp.getBoolean("ever_linked", false) || !sp.contains("here2"))) {
            SharedPreferences.Editor e = sp.edit().putBoolean("ever_linked", true);
            if (!sp.contains("here2")) e.putString("here2", here());
            e.commit();
        } else if (!sp.getString("key", "").isEmpty() && !sp.contains("here2")) {
            sp.edit().putString("here2", here()).commit();
        }
    }

    private volatile String hereFor;
    private volatile String hereHash;

    /**
     * This phone, as the stored link knows it: a hash of the Android ID the app has here (Android gives the app another
     * one on every other phone). Empty when the phone has none worth trusting.
     */
    private String here() {
        String id = MainActivity.androidIdForTests != null ? MainActivity.androidIdForTests : MainActivity.androidId(app);
        if (id == null) return "";
        if (!id.equals(hereFor)) {
            hereHash = Crypto.hex(sha256("textrelay v2 here:" + id));
            hereFor = id;
        }
        return hereHash;
    }

    private static byte[] sha256(String s) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Whether the stored link was made on another phone and came here with a copy of the app's data. */
    private boolean copiedHere() {
        String stored = sp.getString("here2", "");
        String now = here();
        return !stored.isEmpty() && !now.isEmpty() && !stored.equals(now);
    }

    /** True once, after a link that came from another phone was dropped (the app then says so and stops). */
    boolean takeLinkCopied() {
        if (!sp.getBoolean("link_copied", false)) return false;
        sp.edit().remove("link_copied").commit();
        return true;
    }

    String server() {
        return sp.getString("server", defaultServer);
    }

    void setServer(String server) {
        sp.edit().putString("server", server).apply();
    }

    /** The first version's device key; one copied here from another phone (see link()) goes. */
    String key() {
        String k = sp.getString("key", "");
        if (!k.isEmpty() && copiedHere()) {
            sp.edit().remove("key").remove("latest").remove("feed").remove("seen").remove("seals").remove("seal_floor")
                    .putBoolean("link_copied", true).commit();
            return "";
        }
        return k;
    }

    /** Linked to an inbox: the private one (v2), or the first version's with its key. */
    boolean paired() {
        return link() != null || !key().isEmpty();
    }

    // ---- the private inbox (v2) ----

    private volatile String linkRaw;
    private volatile Link linkParsed;

    /** This phone's place in a private inbox, or null. A link copied here from another phone goes, wherever it's read. */
    Link link() {
        String raw = sp.getString("link2", null);
        if (raw == null) return null;
        if (copiedHere()) {
            synchronized (lock) {
                if (sp.getString("link2", null) != null && copiedHere()) {
                    sp.edit().remove("link2").remove("latest").remove("feed").remove("seen").remove("seals").remove("seal_floor")
                            .remove("wrap2").putBoolean("link_copied", true).commit();
                }
            }
            return null;
        }
        if (!raw.equals(linkRaw)) {
            linkParsed = Link.parse(raw, server());
            linkRaw = raw;
        }
        return linkParsed;
    }

    /** A new inbox starts reading from "now", like a new key: nothing older is ever announced. */
    void setLink(Link link) {
        Link cur = link();
        if (link != null && link.sameAs(cur)) return;
        SharedPreferences.Editor e = sp.edit().remove("latest").remove("feed").remove("seen").remove("seals")
                .putLong("seal_floor", System.currentTimeMillis() - FLOOR_SLACK_MS).remove("wrap2");
        if (link == null) e.remove("link2");
        else e.putString("link2", link.toJson()).putString("here2", here()).putBoolean("ever_linked", true).remove("link_copied");
        if (link != null && Tor.isOnion(link.server)) e.putBoolean(Tor.PREF, true);
        e.commit();
    }

    /**
     * Whether this install has ever been in a private inbox. Only an install that never was gets back in by itself
     * after a reinstall: one that left an inbox (or was told it isn't in one) waits for a code, so nobody can put it back
     * into an inbox it left.
     */
    boolean everLinked() {
        return sp.getBoolean("ever_linked", false);
    }

    /** The tag of the wrap filed for getting back in after a reinstall, once the inbox has it. */
    String wrapTag() {
        return sp.getString("wrap2", "");
    }

    void setWrapTag(String tag) {
        sp.edit().putString("wrap2", tag).apply();
    }

    /** A new key starts reading from "now": nothing older is ever shown as a notification. */
    void setKey(String key) {
        String k = key == null ? "" : key.trim();
        if (k.equals(key())) return;
        SharedPreferences.Editor e = sp.edit().putString("key", k).remove("latest").remove("feed").remove("seen").remove("seals")
                .remove("seal_floor");
        if (!k.isEmpty()) e.putString("here2", here());
        e.apply();
    }

    /** Where the next check picks up in the inbox's arrival log, or null before the first successful check. */
    String feed() {
        return sp.getString("feed", null);
    }

    void setFeed(String cursor) {
        sp.edit().putString("feed", cursor).apply();
    }

    static boolean isMessageKey(String k) {
        return k != null && MESSAGE_KEY.matcher(k).matches();
    }

    /** How far back a newly linked phone (or one whose memory predates this) announces: nothing sealed earlier. */
    static final long FLOOR_SLACK_MS = 10 * 60_000L;

    /**
     * Records a message as announced. False when it was announced before: by its key, or, for one that opened here
     * (`id` names the message, as its payload does; `sealedAt` is the time sealed in it), by that message. Also false
     * for one that opened here but was sealed no later than the floor: what this phone has since forgotten announcing
     * (the earliest sealed go first, and the floor never passes this phone's clock), and anything from before it
     * linked. So the server can't announce an old text again by filing it under a new key, sealed again or not, even
     * after crowding it out of memory.
     */
    boolean markSeen(String k, String id, long sealedAt) {
        if (!isMessageKey(k)) return false;
        synchronized (lock) {
            java.util.List<String> keys = seenKeys();
            if (keys.contains(k)) return false;
            keys.add(k);
            while (keys.size() > SEEN_KEEP) keys.remove(0);
            long now = System.currentTimeMillis();
            SharedPreferences.Editor e = sp.edit().putString("seen", new org.json.JSONArray(keys).toString());
            boolean fresh = true;
            if (id != null) {
                // Memory from before this was kept (or a link made by an older build) starts from now.
                long floor = sp.contains("seal_floor") ? sp.getLong("seal_floor", 0) : now - FLOOR_SLACK_MS;
                java.util.List<org.json.JSONArray> seals = new java.util.ArrayList<>();
                org.json.JSONArray stored = seals();
                for (int i = 0; i < stored.length(); i++) {
                    org.json.JSONArray x = stored.optJSONArray(i);
                    if (x == null) continue;
                    seals.add(x);
                    if (id.equals(x.optString(0))) fresh = false;
                }
                if (fresh && sealedAt <= floor) fresh = false;
                if (fresh) {
                    seals.add(new org.json.JSONArray().put(id).put(sealedAt));
                    if (seals.size() > SEEN_KEEP) {
                        seals.sort((a, b) -> Long.compare(a.optLong(1, 0), b.optLong(1, 0)));
                        while (seals.size() > SEEN_KEEP) floor = Math.max(floor, Math.min(seals.remove(0).optLong(1, 0), now));
                    }
                    e.putString("seals", new org.json.JSONArray(seals).toString());
                }
                e.putLong("seal_floor", floor);
            }
            e.apply();
            return fresh;
        }
    }

    /** The keys remembered; a list from a build that kept them comma-separated reads the same. */
    private java.util.List<String> seenKeys() {
        java.util.List<String> out = new java.util.ArrayList<>();
        String raw = sp.getString("seen", "");
        if (raw.startsWith("[")) {
            try {
                org.json.JSONArray a = new org.json.JSONArray(raw);
                for (int i = 0; i < a.length(); i++) if (isMessageKey(a.optString(i))) out.add(a.optString(i));
                return out;
            } catch (org.json.JSONException ignored) {
                return out;
            }
        }
        for (String k : raw.split(",")) if (isMessageKey(k)) out.add(k);
        return out;
    }

    private org.json.JSONArray seals() {
        try {
            return new org.json.JSONArray(sp.getString("seals", "[]"));
        } catch (org.json.JSONException e) {
            return new org.json.JSONArray();
        }
    }

    /** The inbox, device id and name this phone last sealed its own name for (Inbox.nameThisDevice). */
    String named() {
        return sp.getString("named2", "");
    }

    void setNamed(String mark) {
        sp.edit().putString("named2", mark).apply();
    }

    long lastUsed() {
        return sp.getLong("last_used", 0);
    }

    void touchUsed() {
        sp.edit().putLong("last_used", System.currentTimeMillis()).apply();
    }

    /** "normal" (sound, vibration, pop-up), "quiet" (listed in the shade, nothing else) or "off" (no notifications). */
    String alertMode() {
        String m = sp.getString("alert_mode", ALERT_NORMAL);
        return ALERT_QUIET.equals(m) || ALERT_OFF.equals(m) ? m : ALERT_NORMAL;
    }

    void setAlertMode(String mode) {
        sp.edit().putString("alert_mode", ALERT_QUIET.equals(mode) || ALERT_OFF.equals(mode) ? mode : ALERT_NORMAL).apply();
    }

    /** The emoji picked most recently, newest first (up to 32). */
    java.util.List<String> recentEmoji() {
        String raw = sp.getString("emoji_recent", "");
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String e : raw.split("\t")) if (!e.isEmpty()) out.add(e);
        return out;
    }

    void rememberEmoji(String emoji) {
        if (emoji == null || emoji.isEmpty()) return;
        java.util.List<String> list = recentEmoji();
        list.remove(emoji);
        list.add(0, emoji);
        while (list.size() > 32) list.remove(list.size() - 1);
        sp.edit().putString("emoji_recent", String.join("\t", list)).apply();
    }

    /** "Connect through Tor" (see Tor). Always on while this phone's inbox is on a .onion server. */
    boolean tor() {
        return sp.getBoolean(Tor.PREF, false) || onionServer();
    }

    /** Whether this phone's inbox server is a .onion address. */
    boolean onionServer() {
        Link l = link();
        return Tor.isOnion(l != null ? l.server : server());
    }

    void setTor(boolean on) {
        sp.edit().putBoolean(Tor.PREF, on || onionServer()).commit();
    }
}
