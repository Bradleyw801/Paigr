package com.textrelay.texts;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the inbox the same way the web app does: everything that reached the server since the last check, in the
 * order it arrived, so a text that arrives late (the relay phone was offline) is still announced. The first check
 * only learns where "now" is, so old messages are never announced. The doorbell (a websocket the server rings) makes
 * checks immediate; the interval is only the safety net.
 */
final class Sync {
    interface Listener {
        /** New incoming messages, in arrival order. Never called with an empty list. */
        void onIncoming(List<JSONObject> messages);

        /** The relay phone's state changed: silent for too long, or back again. */
        void onRelayState(boolean online, long lastSeen);
    }

    static final int FAST_SECONDS = 8;
    static final int SLOW_SECONDS = 20;
    /** With the doorbell connected, a check this often is only insurance against a missed ring. */
    static final int CONNECTED_SECONDS = 60;
    static final long FAST_WINDOW_MS = 5 * 60_000;
    static final long RELAY_OFFLINE_MS = 10 * 60_000;

    private final Prefs prefs;
    private final Listener listener;
    /** True while the app's screen is open; the page syncs itself then, so the service only keeps its place. */
    volatile boolean screenOpen;
    volatile boolean doorbellConnected;
    /** The private inbox, when this phone is in one: what it announces is opened with its key. */
    volatile Link link;
    private Boolean relayOnline;   // null until the first check

    Sync(Prefs prefs, Listener listener) {
        this.prefs = prefs;
        this.listener = listener;
    }

    /** One check against the server. Returns the number of seconds to wait before the next one. */
    int checkOnce(Hedy hedy) throws IOException {
        String feed = prefs.feed();
        if (feed == null) {
            JSONObject inbox = hedy.get("/api/inbox");
            String start = inbox.optString("feed", "");
            if (!start.startsWith("f:")) throw new Hedy.ApiException(502, "the inbox did not say where its log starts");
            // The log is re-read a little before any cursor; what that turns up now is history, not news.
            JSONObject r = read(hedy, start, null);
            relayState(r != null ? r.optJSONObject("relay") : inbox.optJSONObject("relay"));
            return interval();
        }
        List<JSONObject> incoming = new ArrayList<>();
        JSONObject r = read(hedy, feed, incoming);
        if (!incoming.isEmpty() && !screenOpen) listener.onIncoming(incoming);
        if (r != null) relayState(r.optJSONObject("relay"));
        return interval();
    }

    /** Reads the log from the cursor until it is caught up, collecting incoming messages not seen before. */
    private JSONObject read(Hedy hedy, String cursor, List<JSONObject> incoming) throws IOException {
        JSONObject r = null;
        String at = cursor;
        for (int round = 0; round < 10; round++) {
            r = hedy.get("/api/sync?feed=" + encode(at));
            if (r.optBoolean("reset", false)) {
                // Away longer than the inbox keeps its log: start from now; the app shows the rest when opened.
                String now = r.optString("cursor", "");
                if (now.startsWith("f:")) at = now;
                break;
            }
            JSONArray items = r.optJSONArray("items");
            // Each conversation's merge records, so a message sealed before two conversations became one still opens.
            java.util.Map<String, JSONArray> merged = new java.util.HashMap<>();
            JSONArray convs = r.optJSONArray("convs");
            for (int i = 0; convs != null && i < convs.length(); i++) {
                JSONObject c = convs.optJSONObject(i);
                if (c != null && c.optJSONArray("merged") != null) merged.put(c.optString("conv"), c.optJSONArray("merged"));
            }
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject m = items.optJSONObject(i);
                    // Only news: not replies, not messages already read on the relay phone, not old history it brought in.
                    if (m == null || !"in".equals(m.optString("dir")) || m.optBoolean("read") || m.optBoolean("history")) continue;
                    String k = m.optString("k", "");
                    if (!Prefs.isMessageKey(k)) continue;   // not a key the inbox gives a message
                    Link l = link;
                    // A private inbox's message is only ever shown as it opens here: one with nothing sealed (or that
                    // doesn't open as its conversation's) says "New message", never what the server wrote. The first
                    // version's inbox (no link) sends plain records.
                    JSONObject shown = l != null ? l.openMessage(m, merged.get(m.optString("conv"))) : m;
                    // Announced once per key, and once per message: the same message filed again under a new key, or
                    // sealed again (a resync), is an old text, not a new one, and so is one sealed before what this
                    // phone still remembers.
                    String e = m.optString("e", "");
                    boolean opened = l != null && !shown.optBoolean("unchecked");
                    String id = shown.optString("sealedId", "");
                    if (opened && id.isEmpty() && e.length() >= 22) id = "e:" + e.substring(e.length() - 22);
                    if (prefs.markSeen(k, opened && !id.isEmpty() ? id : null, opened ? shown.optLong("sealedAt", 0) : 0)
                            && incoming != null) {
                        incoming.add(shown);
                    }
                }
            }
            String next = r.optString("cursor", "");
            if (next.startsWith("f:")) at = next;
            if (!r.optBoolean("more", false)) break;
        }
        prefs.setFeed(at);
        return r;
    }

    private void relayState(JSONObject relay) {
        if (relay == null) return;
        long lastSeen = relay.optLong("lastSeen", 0);
        if (lastSeen == 0) return;
        boolean online = System.currentTimeMillis() - lastSeen < RELAY_OFFLINE_MS;
        if (relayOnline != null && relayOnline == online) return;
        boolean first = relayOnline == null;
        relayOnline = online;
        if (first && online) return;   // nothing to say when all is well at startup
        listener.onRelayState(online, lastSeen);
    }

    int interval() {
        if (doorbellConnected) return CONNECTED_SECONDS;
        if (screenOpen) return SLOW_SECONDS;
        return System.currentTimeMillis() - prefs.lastUsed() < FAST_WINDOW_MS ? FAST_SECONDS : SLOW_SECONDS;
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }
}
