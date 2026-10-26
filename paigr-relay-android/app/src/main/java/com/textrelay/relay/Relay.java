package com.textrelay.relay;

import android.Manifest;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.service.notification.StatusBarNotification;
import android.telephony.SmsManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The relay itself: texts go up to the inbox server, from Messages' notifications as they arrive and from the phone's
 * message store (texts sent from this phone, anything a notification never showed, and the full history when asked);
 * replies come back down and out through Google Messages.
 */
final class Relay {
    static final String VERSION = "0.9";
    private static final String TAG = "Relay";
    /** Server errors in a row on one attachment before the media lane lets it go (the text is already in the inbox). */
    static final int MEDIA_GIVE_UP = 6;
    /** First wait after a failed delivery, doubling to sixty times this. Tests make it short. */
    static volatile long laneBackoffMs = 2_000;

    private static Relay instance;

    static synchronized Relay get(Context context) {
        if (instance == null) instance = new Relay(context.getApplicationContext());
        return instance;
    }

    /** Tests only. */
    static synchronized void reset() {
        if (instance != null) instance.shutdown();
        instance = null;
    }

    final Prefs prefs;
    final Outbound outbound;
    final Store store;
    final Conversations conversations;
    final StoreSync storeSync;
    final HistorySync historySync;
    private final Context context;
    /** Reply handles from Messages' live notifications, per conversation id. Rebuilt from active notifications after a restart. */
    private final Map<String, Notification.Action> replyHandles = new ConcurrentHashMap<>();
    private final Lane textLane = new Lane("relay-texts", false);
    private final Lane mediaLane = new Lane("relay-media", true);
    private Poller poller;
    private Doorbell doorbell;

    volatile boolean listening;
    volatile long lastRelayed;
    /** When Android last hid a Messages notification from Relay as a one-time code; 0 if it hasn't since Relay started. */
    volatile long lastHidden;
    volatile long lastSync;
    volatile long lastHeartbeat;
    volatile int lastQueued;
    volatile String lastError;
    /** Tor is on but isn't running on this phone, so nothing reaches the inbox until it is. */
    volatile boolean torDown;
    /** The last attachment that could not be read or stored; the text still went through without it. */
    volatile String lastMediaError;
    /** The last time everything was sent to the inbox again, and how much of Messages' notifications that covered. */
    volatile long lastResync;
    volatile int lastResyncConversations;
    volatile int lastResyncTexts;
    /** Tests swap in their own attachment reader. */
    volatile Media.Reader mediaReader;
    /** What Messages is showing right now. Tests swap in their own. */
    volatile ActiveNotifications active = RelayListenerService::activeNow;
    /** Whether Android has the notification listener bound. Tests swap in their own. */
    volatile java.util.function.BooleanSupplier listenerBound = RelayListenerService::bound;

    interface ActiveNotifications {
        /** Null when the notification listener isn't connected. */
        StatusBarNotification[] get();
    }

    private Relay(Context context) {
        this.context = context;
        this.prefs = new Prefs(context);
        this.outbound = new Outbound(context);
        this.store = new Store(context);
        this.conversations = new Conversations(context, prefs);
        this.storeSync = new StoreSync(context, this, store);
        this.historySync = new HistorySync(context, this, store);
        this.mediaReader = Media.reader(context);
        conversations.onFold = (from, into) -> outbound.rewriteConv(from, into);
        // A link copied here with the app's data from another phone is dropped before anything uses it (Prefs.link).
        if (prefs.link() == null && prefs.takeLinkCopied()) lastError = LINK_COPIED;
        textLane.start();
        mediaLane.start();
    }

    private static final String LINK_COPIED = "This phone's link to your inbox came from another phone, so it was dropped. Open Paigr Relay to link this phone.";

    /** The inbox this phone relays to: its private inbox once it has one, the first version's until then. */
    Hedy hedy() {
        Link l = prefs.link();
        if (l != null) return new Hedy2(l, prefs::allConversations, prefs::mergedInto, prefs.requests());
        return new Hedy(prefs.server(), prefs.key());
    }

    /** The private inbox's client, or null while this phone isn't in one. */
    Hedy2 hedy2() {
        Link l = prefs.link();
        return l != null ? new Hedy2(l, prefs::allConversations, prefs::mergedInto, prefs.requests()) : null;
    }

    synchronized void startPoller() {
        if (poller == null || !poller.isAlive()) {
            poller = new Poller(this);
            poller.start();
        }
        if (doorbell == null && prefs.configured()) {
            Link l = prefs.link();
            Doorbell.Tickets tickets = l == null ? null : () -> new Hedy2(l, null).ticket();
            doorbell = new Doorbell(l != null ? l.server : prefs.server(), l != null ? "private" : "outbox", new Doorbell.Listener() {
                @Override
                public void onRing(String type, JSONObject payload) {
                    if ("reconnect".equals(type)) checkWake(payload.optLong("at", 0), payload.optString("kind", "reconnect"), true);
                    wakePoller();
                }

                @Override
                public void onStateChange(boolean connected) {
                    if (connected) wakePoller();
                }
            }, tickets);
            doorbell.start();
        }
        storeSync.start();
        historySync.resume();
    }

    synchronized void stopPoller() {
        if (poller != null) poller.shutdown();
        poller = null;
        if (doorbell != null) doorbell.stop();
        doorbell = null;
    }

    private void shutdown() {
        stopPoller();
        storeSync.stop();
        historySync.stop();
        textLane.shutdown();
        mediaLane.shutdown();
    }

    /** Something is waiting in the outbox: check now instead of at the next interval. */
    void wakePoller() {
        Poller p = poller;
        if (p != null) p.wake();
    }

    boolean doorbellConnected() {
        Doorbell d = doorbell;
        return d != null && d.isConnected();
    }

    int replyableCount() {
        return replyHandles.size();
    }

    // ------------------------------------------------------------------ refresh, resync, reconnect

    private final Object wakeLock = new Object();

    /**
     * Acts once on a request from an inbox device that is newer than the last one handled: "refresh" (pull to refresh)
     * sends anything from the last two days not sent yet; "resync" sends the last week again, the inbox keeping one copy
     * of each; "reconnect" also reopens every connection first.
     */
    void checkWake(long at, String kind, boolean viaDoorbell) {
        if (at <= 0) return;
        synchronized (wakeLock) {
            if (at <= prefs.wakeHandled()) return;
            prefs.setWakeHandled(at);
        }
        if ("refresh".equals(kind)) refresh(StoreSync.DAYS_2, false);
        else if ("resync".equals(kind)) refresh(StoreSync.DAYS_7, true);
        else reconnect(viaDoorbell);
    }

    /** Checks what Messages shows and what the message store holds for the last `window` ms, and checks in. */
    void refresh(long window, boolean resend) {
        background("relay-refresh", () -> {
            rescan(resend);
            storeSync.request(window, resend);
            lastHeartbeat = 0;
            wakePoller();
        });
    }

    /**
     * Puts everything back on its feet and checks in: a fresh realtime connection (unless the request just came
     * through it), the service and the notification listener restarted if Android dropped them, the last week sent to
     * the inbox again (it skips what it already has), and a heartbeat right away.
     */
    void reconnect(boolean viaDoorbell) {
        background("relay-reconnect", () -> {
            Doorbell d = doorbell;
            if (d != null && !viaDoorbell) d.reconnect();
            try {
                Watchdog.check(context);
            } catch (RuntimeException e) {
                Log.w(TAG, "watchdog check failed", e);
            }
            storeSync.start();
            rescan(true);
            storeSync.request(StoreSync.DAYS_7, true);
            lastHeartbeat = 0;
            wakePoller();
        });
    }

    private static void background(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }

    /**
     * Reads every Messages notification on screen. With resend, texts already relayed go up again too; the inbox keeps
     * one copy of each, so this only fills gaps. Attachments already relayed are not uploaded twice. Returns false when
     * the notification listener isn't connected (Android is asked to reconnect it).
     */
    boolean rescan(boolean resend) {
        StatusBarNotification[] now = active.get();
        if (now == null) {
            // Only a listener Android dropped needs rebinding; one that just reconnected can refuse this for a moment.
            if (!listenerBound.getAsBoolean()) Watchdog.rebind(context, new android.content.ComponentName(context, RelayListenerService.class));
            return false;
        }
        int conversationsSeen = 0;
        int texts = 0;
        for (StatusBarNotification sbn : now) {
            int n = onNotification(sbn, resend);
            if (n >= 0) {
                conversationsSeen++;
                texts += n;
            }
        }
        if (resend) {
            lastResync = System.currentTimeMillis();
            lastResyncConversations = conversationsSeen;
            lastResyncTexts = texts;
        }
        return true;
    }

    // ------------------------------------------------------------------ texts up, from notifications

    void onNotification(StatusBarNotification sbn) {
        onNotification(sbn, false);
    }

    /** Returns how many texts were queued for the inbox, or -1 when this isn't a Messages conversation. */
    int onNotification(StatusBarNotification sbn, boolean resend) {
        if (!Parser.MESSAGES_PACKAGE.equals(sbn.getPackageName())) return -1;
        Parser.Snapshot s = Parser.parse(sbn);
        if (s == null) return -1;
        if (s.hidden) return onHidden(s);
        String conv = conversations.forNotification(s, storeSync.allowed() ? this::threadOf : null);
        if (s.reply != null) replyHandles.put(conv, s.reply);
        prefs.setName(conv, s.title);
        conversations.learn(conv, s);

        // Not in an inbox (yet, or any more): nothing is marked relayed, so what is still on screen goes up once it is.
        if (!prefs.configured()) return 0;
        long floor = prefs.installTs() - 60_000;
        List<JSONObject> fresh = new ArrayList<>();
        for (Parser.Msg m : s.messages) {
            if (m.time != 0 && m.time < floor) continue;           // history from before Relay was installed
            if (m.self && m.time == 0) continue;                    // a reply without a time can't be matched to its copies
            boolean seen = !prefs.markSeen(conv, m.fingerprint());
            if (!seen) prefs.noteRecent(conv, m.self, m.time, m.text);
            // Sent again only when the inbox can recognise it (a real timestamp) and there is no file to upload twice.
            if (seen && !(resend && m.time != 0 && m.dataUri == null)) continue;
            fresh.add(item(s, conv, m));
        }
        if (fresh.isEmpty()) return 0;
        int added = outbound.add(fresh);
        textLane.wake();
        mediaLane.wake();
        return added;
    }

    /**
     * A notification Android hid from Relay because it took it for a one-time code (Android 15 and later). It holds
     * nothing to relay, so it never names or starts a conversation; its reply action still works, and is kept for a
     * conversation already known. The text itself comes from the message store, read a moment from now, and a new
     * thread it lands in doesn't wait for a notification that will say nothing.
     */
    private int onHidden(Parser.Snapshot s) {
        lastHidden = System.currentTimeMillis();
        String conv = conversations.knownForNotification(s);
        if (conv != null && s.reply != null) replyHandles.put(conv, s.reply);
        storeSync.poke();
        return 0;
    }

    /** True when Android hid a notification close to this time, so a store text from then has none to wait for. */
    boolean hiddenNear(long ts) {
        long h = lastHidden;
        return h > 0 && Math.abs(h - ts) < StoreSync.SETTLE_MS;
    }

    /**
     * What the queue remembers about one text: enough to deliver it later, nothing from the raw notification. Replies
     * Messages shows in the conversation (sent from this phone, or from Texts through it) go up as outgoing; the inbox
     * folds a Texts reply and its copies into one.
     */
    private static JSONObject item(Parser.Snapshot s, String conv, Parser.Msg m) {
        long ts = m.time != 0 ? m.time : System.currentTimeMillis();
        JSONObject it = Json.obj(
                "id", conv + ":" + m.fingerprint(),
                "conv", conv,
                "name", s.title,
                "group", s.group,
                "sender", m.self ? "" : m.sender != null ? m.sender : s.title,
                "dir", m.self ? "out" : "in",
                "src", "notif",
                "text", Text.clip(m.text, 4000),
                "ts", ts,
                "fp", m.fingerprint());
        if (m.dataUri != null) {
            put(it, "mime", Media.normalize(m.mime));
            put(it, "dataUri", m.dataUri.toString());
        }
        return it;
    }

    /**
     * The store thread a notification's newest texts are in: the same text, within three minutes, from the same
     * person. Null when there is none (an encrypted RCS conversation stays out of the store) or more than one.
     */
    Store.Thread threadOf(Parser.Snapshot s) {
        if (!storeSync.allowed()) return null;
        List<String> numbers = null;
        int tried = 0;
        try {
            for (int i = s.messages.size() - 1; i >= 0 && tried < 3; i--) {
                Parser.Msg m = s.messages.get(i);
                if (m.self || m.time == 0) continue;
                String text = Store.norm(m.text);
                if (text.length() < 2) continue;
                tried++;
                if (numbers == null) numbers = conversations.numbersOf(s);
                Set<Long> ids = new HashSet<>();
                for (Store.Msg x : store.since(m.time - 180_000, 200).msgs) {
                    if (x.out || x.ts > m.time + 180_000 || !Store.norm(x.text).equals(text)) continue;
                    if (sameSender(s, m, x, numbers)) ids.add(x.threadId);
                }
                if (ids.size() > 1) return null;
                if (ids.size() == 1) {
                    Store.Thread t = store.thread(ids.iterator().next());
                    // A group's notification only ever matches a group thread, and a one-to-one only a one-to-one.
                    if (t == null || (Conversations.keysOf(t.addresses).size() > 1) != s.group) return null;
                    return t;
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "message store lookup failed", e);
        }
        return null;
    }

    /**
     * Whether a store message is from the person a notification shows: the same number, or the contact's name for that
     * number. Without either there is no evidence, and no match: the same words from someone else must never join
     * two people's conversations.
     */
    private boolean sameSender(Parser.Snapshot s, Parser.Msg m, Store.Msg x, List<String> numbers) {
        String addr = Store.key(x.address);
        if (addr.isEmpty()) return false;
        for (String n : numbers) if (Store.key(n).equals(addr)) return true;
        if (!s.group && !numbers.isEmpty()) return false;   // its number is known, and this isn't it
        String who = (m.sender != null ? m.sender : s.title).trim();
        if (Store.isNumber(who)) return Store.key(who).equals(addr);
        String contact = conversations.contactName(x.address);
        return contact != null && contact.equalsIgnoreCase(who);
    }

    // ------------------------------------------------------------------ texts up, from the message store

    /**
     * What the queue keeps for one store message: one item per attachment, the text on the first. With withMedia, an
     * attachment goes up only if the inbox says it still needs it; without, the inbox learns only what kind it was.
     */
    List<JSONObject> storeItems(Store.Msg m, String conv, boolean history, boolean withMedia) {
        List<JSONObject> out = new ArrayList<>();
        boolean group = Boolean.TRUE.equals(prefs.group(conv));
        String name = prefs.name(conv);
        String sender = m.out ? "" : m.address != null ? conversations.nameFor(m.address) : name;
        int n = Math.max(1, m.media.size());
        for (int i = 0; i < n; i++) {
            JSONObject it = Json.obj(
                    "id", "store:" + m.key() + (i > 0 ? ":" + i : ""),
                    "conv", conv,
                    "name", name,
                    "group", group,
                    "sender", sender,
                    "dir", m.out ? "out" : "in",
                    "src", "store",
                    "read", m.read,
                    "text", i == 0 ? Text.clip(m.text, 4000) : "",
                    "ts", m.ts + i,
                    "fp", Parser.hash("p|" + m.key() + (i > 0 ? "|" + i : ""), 8));
            if (m.failed) put(it, "status", "failed");
            if (history) put(it, "history", true);
            if (i < m.media.size()) {
                Store.Part p = m.media.get(i);
                put(it, "mime", Media.normalize(p.mime));
                if (withMedia) put(it, "partUri", p.uri.toString());
            }
            out.add(it);
        }
        return out;
    }

    /**
     * Sends again, straight to the inbox in batches, store messages it most likely has already (a resync); attachments
     * it turns out to be missing go to the upload lane. Stops at the first failure, since these were sent before.
     * Returns how many went.
     */
    int resendNow(List<JSONObject> items) {
        int done = 0;
        try {
            for (int i = 0; i < items.size(); i += 50) {
                List<JSONObject> batch = items.subList(i, Math.min(items.size(), i + 50));
                List<JSONObject> jobs = ingest(batch);
                if (!jobs.isEmpty()) {
                    outbound.add(jobs);
                    mediaLane.wake();
                }
                done += batch.size();
            }
        } catch (IOException | RetryLater e) {
            lastError = "A resync stopped partway; the next one tries again.";
        }
        return done;
    }

    /** Queues what a check of the message store found, and which conversations were read on this phone. */
    void queueFromStore(List<JSONObject> items, JSONObject reads) {
        List<JSONObject> all = new ArrayList<>(items);
        if (reads.length() > 0) all.add(Json.obj("id", "reads:" + System.currentTimeMillis(), "reads", reads));
        if (all.isEmpty()) return;
        outbound.add(all);
        textLane.wake();
    }

    /**
     * Which conversations already hold some of a thread's messages. A private inbox is never asked: its answer would
     * decide where replies go (a thread's number is what SMS fallback sends to), so this phone goes by the texts its
     * own notifications showed. The first version's inbox, which reads everything anyway, is asked.
     */
    Conversations.Locator locator() {
        if (!prefs.configured()) return null;
        if (prefs.link() != null) {
            return probes -> {
                List<List<String>> out = new ArrayList<>();
                for (Store.Msg m : probes) out.add(prefs.recentlyShowing(m.out, m.ts, m.text));
                return out;
            };
        }
        return probes -> {
            JSONArray list = new JSONArray();
            for (Store.Msg m : probes) {
                JSONObject p = Json.obj("ts", m.ts, "text", Text.clip(m.text, 1000), "dir", m.out ? "out" : "in");
                list.put(p);
            }
            JSONArray convs = hedy().locate(list).optJSONArray("convs");
            List<List<String>> out = new ArrayList<>();
            for (int i = 0; convs != null && i < convs.length(); i++) {
                JSONArray one = convs.optJSONArray(i);
                List<String> l = new ArrayList<>();
                for (int j = 0; one != null && j < one.length(); j++) l.add(one.optString(j));
                out.add(l);
            }
            return out;
        };
    }

    // ------------------------------------------------------------------ delivery

    /**
     * Sends items to the inbox now, 50 to a request. Attachments from notifications go up first, so such a message never
     * arrives without its picture; for store messages the inbox says which attachments it still needs, and those come
     * back as upload jobs. Throws when the inbox can't be reached, so nothing is marked done.
     */
    List<JSONObject> deliverNow(List<JSONObject> batch) throws IOException {
        List<JSONObject> jobs = new ArrayList<>();
        for (int i = 0; i < batch.size(); i += 50) jobs.addAll(ingest(batch.subList(i, Math.min(batch.size(), i + 50))));
        return jobs;
    }

    private List<JSONObject> ingest(List<JSONObject> batch) throws IOException {
        JSONArray items = new JSONArray();
        JSONObject replyable = new JSONObject();
        JSONObject reads = new JSONObject();
        Map<String, JSONObject> byFp = new HashMap<>();
        for (JSONObject it : batch) {
            JSONObject r = it.optJSONObject("reads");
            if (r != null) {
                for (java.util.Iterator<String> k = r.keys(); k.hasNext(); ) {
                    String conv = k.next();
                    put(reads, conv, Math.max(reads.optLong(conv, 0), r.optLong(conv)));
                }
                continue;
            }
            JSONObject w = wire(it);
            if (it.has("dataUri")) put(w, "media", it.optBoolean("skipUpload")
                    ? Json.obj("mime", Media.normalize(it.optString("mime")))
                    : mediaFor(it.optString("conv"), it.optString("fp"), it.optLong("ts"),
                            Uri.parse(it.optString("dataUri")), it.optString("mime", "application/octet-stream")));
            items.put(w);
            byFp.put(it.optString("fp"), it);
            if (!"store".equals(it.optString("src"))) put(replyable, it.optString("conv"), replyHandles.containsKey(it.optString("conv")));
        }
        if (items.length() == 0 && reads.length() == 0) return new ArrayList<>();
        JSONObject r = hedy().ingest(Json.obj("items", items, "replyable", replyable, "reads", reads));
        if (items.length() > 0) lastRelayed = System.currentTimeMillis();
        List<JSONObject> jobs = new ArrayList<>();
        JSONArray results = r.optJSONArray("results");
        for (int i = 0; results != null && i < results.length(); i++) {
            JSONObject res = results.optJSONObject(i);
            if (res == null || !res.optBoolean("needMedia")) continue;
            JSONObject it = byFp.get(res.optString("fp"));
            if (it == null || !it.has("partUri")) continue;
            jobs.add(Json.obj("id", "up:" + it.optString("id"), "uploadUri", it.optString("partUri"), "mime", it.optString("mime"), "item", it));
        }
        return jobs;
    }

    /** One item as the inbox reads it. */
    private static JSONObject wire(JSONObject it) {
        JSONObject w = Json.obj(
                "conv", it.optString("conv"),
                "name", it.optString("name"),
                "group", it.optBoolean("group"),
                "sender", it.optString("sender"),
                "dir", it.optString("dir", "in"),
                "src", it.optString("src", "notif"),
                "text", it.optString("text"),
                "ts", it.optLong("ts"),
                "fp", it.optString("fp"));
        if (it.optBoolean("read")) put(w, "read", true);
        if (it.optBoolean("history")) put(w, "history", true);
        if (it.has("status")) put(w, "status", it.optString("status"));
        if (it.has("mime") && !it.has("dataUri")) {
            put(w, "media", Json.obj("mime", it.optString("mime")));
            // Only what the inbox can store is offered; the rest stays on this phone and is named for what it was.
            if (it.has("partUri") && Media.sendable(it.optString("mime"))) put(w, "wantMedia", true);
        }
        return w;
    }

    /**
     * Uploads an attachment the inbox asked for, then tells the inbox where it is. A file that can't be read, or that
     * the server refuses for good, is given up on; the message is already there without it.
     */
    void uploadNow(JSONObject job) throws IOException {
        JSONObject it = job.optJSONObject("item");
        if (it == null || job.optBoolean("skipUpload")) return;   // given up on: the message is in the inbox without it
        JSONObject media = mediaFor(it.optString("conv"), it.optString("fp"), it.optLong("ts"), Uri.parse(job.optString("uploadUri")),
                job.optString("mime", "application/octet-stream"));
        if (!media.has("path")) return;
        JSONObject w = wire(it);
        w.remove("wantMedia");
        put(w, "media", media);
        hedy().ingest(Json.obj("items", new JSONArray().put(w)));
        lastRelayed = System.currentTimeMillis();
    }

    /** Uploads the untouched original plus a small preview; when that can't happen, the inbox still learns what it was. */
    private JSONObject mediaFor(String conv, String fp, long ts, Uri uri, String mime) {
        JSONObject media = Json.obj("mime", mime);
        Media.Prepared p;
        try {
            Media.Reader reader = mediaReader;
            if (reader == null) throw new IOException("no attachment reader");
            p = reader.read(uri, mime);
        } catch (IOException | RuntimeException e) {
            lastMediaError = "Could not read an attachment (" + e.getMessage() + ")";
            return media;   // the grant from the notification is gone (restart), or the file is unreadable
        }
        if (!Media.storable(p.original.mime)) {
            lastMediaError = "The inbox can't store " + p.original.mime + " attachments; they stay on this phone";
            return media;
        }
        try {
            String path = hedy().uploadMedia(conv, fp, ts, "original", p.original.mime, p.original.bytes);
            put(media, "mime", p.original.mime);
            put(media, "path", path);
            put(media, "bytes", p.original.bytes.length);
            if (p.original.w > 0 && p.original.h > 0) {
                put(media, "w", p.original.w);
                put(media, "h", p.original.h);
            }
            if (p.preview != null) {
                put(media, "preview", hedy().uploadMedia(conv, fp, ts, "preview", p.preview.mime, p.preview.bytes));
            }
        } catch (Hedy.ApiException e) {
            if (e.refused()) {
                lastMediaError = "Could not store an attachment (" + e.getMessage() + ")";
                return media;   // the server refused it for good; the text still goes through
            }
            throw new RetryLater(e);
        } catch (IOException e) {
            throw new RetryLater(e);
        }
        return media;
    }

    /** Sends one batch from a lane; attachments the inbox still needs are queued for the slow lane. */
    private void deliver(List<JSONObject> batch) throws IOException {
        List<JSONObject> jobs = new ArrayList<>();
        List<JSONObject> sends = new ArrayList<>();
        for (JSONObject it : batch) {
            if (it.has("uploadUri")) uploadNow(it);
            else sends.add(it);
        }
        if (!sends.isEmpty()) jobs.addAll(deliverNow(sends));
        if (!jobs.isEmpty()) {
            outbound.add(jobs);
            mediaLane.wake();
        }
    }

    /** A delivery lane: one queue consumer with unbounded, backed-off retry. Texts and uploads have one each. */
    private final class Lane extends Thread {
        private final boolean withMedia;
        private final Object signal = new Object();
        private volatile boolean running = true;
        private volatile boolean idle;

        Lane(String name, boolean withMedia) {
            super(name);
            setDaemon(true);
            this.withMedia = withMedia;
        }

        void wake() {
            synchronized (signal) {
                signal.notifyAll();
            }
        }

        void shutdown() {
            running = false;
            interrupt();
        }

        boolean isIdle() {
            return idle && !outbound.hasAny(withMedia);
        }

        @Override
        public void run() {
            long backoff = laneBackoffMs;
            int serverFailures = 0;   // in a row, on the same attachment (this lane takes them one at a time)
            while (running) {
                List<JSONObject> batch = prefs.configured() ? outbound.take(withMedia, withMedia ? 1 : 20) : new ArrayList<>();
                if (batch.isEmpty()) {
                    idle = true;
                    synchronized (signal) {
                        try {
                            signal.wait(30_000);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                    continue;
                }
                idle = false;
                try {
                    deliver(batch);
                    outbound.remove(batch);
                    lastError = null;
                    backoff = laneBackoffMs;
                    serverFailures = 0;
                    continue;
                } catch (RetryLater e) {
                    if (e.getCause() instanceof Tor.Down) torDown = true;
                    lastError = e.getCause() instanceof Tor.Down ? e.getCause().getMessage()
                            : "No connection to the inbox server (" + e.getClass().getSimpleName() + ")";
                    if (withMedia && e.getCause() instanceof Hedy.ApiException && ((Hedy.ApiException) e.getCause()).code >= 500
                            && ++serverFailures >= MEDIA_GIVE_UP) {
                        // The inbox keeps failing on this one attachment: send the message without it, so it can't
                        // hold up the rest. The message still says what the attachment was.
                        for (JSONObject it : batch) put(it, "skipUpload", true);
                        lastMediaError = "Gave up on an attachment the inbox kept failing to store (" + e.getCause().getMessage() + ")";
                        Log.w(TAG, "sending " + batch.size() + " item(s) without their attachment after " + serverFailures + " server errors");
                        serverFailures = 0;
                        continue;
                    }
                } catch (Hedy.ApiException e) {
                    lastError = "Server: " + e.getMessage();
                    if (e.refused()) {
                        outbound.remove(batch);   // the server will never take these; don't wedge the queue behind them
                        Log.w(TAG, "dropped " + batch.size() + " item(s): " + e.getMessage());
                        continue;
                    }
                    if (e.keyRejected()) backoff = 60_000;
                } catch (IOException e) {
                    if (e instanceof Tor.Down) torDown = true;
                    lastError = e instanceof Tor.Down ? e.getMessage() : "No connection to the inbox server (" + e.getClass().getSimpleName() + ")";
                } catch (RuntimeException e) {
                    Log.w(TAG, "delivery failed", e);
                    lastError = "Problem: " + e.getClass().getSimpleName();
                }
                synchronized (signal) {
                    try {
                        signal.wait(backoff);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                backoff = Math.min(backoff * 2, laneBackoffMs * 60);
            }
        }
    }

    /** A delivery that should be tried again later, carrying the network or server error that stopped it. */
    static final class RetryLater extends RuntimeException {
        RetryLater(Throwable cause) {
            super(cause);
        }
    }

    /**
     * Conversations found to be one are merged on the inbox once nothing for either is still waiting to go up, in
     * slices the inbox can do within its time limit. Each merge is done once more ten minutes later, for anything that
     * was on its way at the time. When the inbox doesn't have the conversation to merge into yet, it waits and retries.
     */
    void flushMerges(Hedy hedy) throws IOException {
        long now = System.currentTimeMillis();
        for (Prefs.Merge m : prefs.merges()) {
            if (m.next > now) continue;
            if (outbound.waitingFor(m.from) || outbound.waitingFor(m.into)) continue;
            JSONObject r = null;
            try {
                for (int i = 0; i < 40; i++) {
                    r = hedy.merge(m.from, m.into);
                    if (!r.optBoolean("more")) break;
                }
            } catch (Hedy.ApiException e) {
                if (e.code == 404) {
                    prefs.mergeTried(m.from, m.into, false, now, now + 5 * 60_000L);   // the inbox doesn't have `into` yet
                    continue;
                }
                if (e.refused()) {
                    prefs.removeMerge(m.from, m.into);
                    continue;
                }
                throw e;
            }
            boolean finished = r != null && !r.optBoolean("more");
            if (finished) prefs.mergeTried(m.from, m.into, true, now, now + 10 * 60_000L);
            else prefs.mergeTried(m.from, m.into, false, now, now + 60_000L);   // more slices left: carry on shortly
        }
    }

    // ------------------------------------------------------------------ replies down

    static final class SendResult {
        final boolean sent;
        final String code;    // why not (NotSent): a private inbox gets only this
        final String error;   // the same in words, for the first version's inbox
        final boolean canReply;
        final String via;   // "rcs" through Messages' own reply, "sms" straight from this device

        SendResult(boolean sent, String code, boolean canReply, String via) {
            this.sent = sent;
            this.code = code;
            this.error = sent ? null : NotSent.words(code);
            this.canReply = canReply;
            this.via = via;
        }
    }

    /** Fetches queued replies, sends each through Messages' own reply action, and reports back. Returns the poll interval. */
    int processOutbox(Hedy hedy) throws IOException {
        JSONObject r = hedy.outbox();
        checkWake(r.optLong("wake", 0), r.optString("wakeKind", "reconnect"), false);
        JSONArray items = r.optJSONArray("items");
        lastQueued = items != null ? items.length() : 0;
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                String id = item.optString("id");
                // Sent already, and only the report to the inbox was lost: report again, never send twice.
                JSONObject report = prefs.outboxReport(id);
                if (report == null) {
                    boolean v2 = hedy instanceof Hedy2;
                    String nonce = item.optString("nonce");
                    // Kept before sending: if Relay stops mid-send, the reply is reported failed, never sent twice.
                    JSONObject stopped = NotSent.report(id, NotSent.STOPPED);
                    // A private inbox's request is acted on once, while it's fresh (see Prefs.spendRequest). The same
                    // request again (filed twice, or under another id) gets what became of it the first time; one
                    // refused is spent too, so it can't be sent later either (a request for a conversation this phone
                    // doesn't know comes marked "refuse", see Hedy2.outbox).
                    JSONObject earlier = v2 ? prefs.spentRequest(nonce) : null;
                    String refused = v2 && earlier == null
                            ? prefs.spendRequest(nonce, item.optLong("made"), System.currentTimeMillis(), stopped, item.optString("refuse", null),
                                    item.optLong("since", 0)) : null;
                    if (earlier != null) {
                        report = Json.obj("id", id, "status", earlier.optString("status", "failed"));
                        for (String k : new String[] { "canReply", "via", "error", "code" }) if (earlier.has(k)) put(report, k, earlier.opt(k));
                    } else if (refused != null) {
                        report = NotSent.report(id, refused);
                    } else {
                        prefs.setOutboxReport(id, stopped);
                        String conv = item.optString("conv");
                        // A conversation an inbox device started with a number: made here now, or the one this phone
                        // already has with that number (Conversations.started).
                        String to = item.optString("to", "");
                        if (!to.isEmpty()) conv = conversations.started(conv, to);
                        // A conversation this phone folded into another (its own merge, maybe not on the inbox yet)
                        // is answered through the one it went into.
                        SendResult result = sendReply(prefs.foldedInto(conv), item.optString("text"), item.optJSONObject("media"), hedy);
                        report = Json.obj("id", id, "status", result.sent ? "sent" : "failed", "canReply", result.canReply, "via", result.via);
                        if (!result.sent) {
                            put(report, "code", result.code);
                            put(report, "error", result.error);
                        }
                        if (v2) prefs.recordRequest(nonce, report);
                    }
                    prefs.setOutboxReport(id, report);
                }
                hedy.outboxResult(report);
                prefs.clearOutboxReport(id);
            }
        }
        lastSync = System.currentTimeMillis();
        return Math.max(3, Math.min(60, r.optInt("pollSeconds", 20)));
    }

    /**
     * A reply with a file: fetched from the inbox and opened here, then handed to Messages' reply action when that
     * takes files, else sent from this phone as MMS (Messages shows what this phone sends, so the conversation stays
     * whole there too).
     */
    SendResult sendReply(String conv, String text, JSONObject media, Hedy hedy) {
        if (media == null || !(hedy instanceof Hedy2)) return sendReply(conv, text);
        Files.sweep(context);
        String mime = Media.normalize(media.optString("mime", ""));
        byte[] bytes;
        try {
            bytes = ((Hedy2) hedy).download(media.optString("path", ""));
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "attachment not fetched: " + e.getMessage());
            return new SendResult(false, NotSent.ATTACH_FETCH, replyHandles.containsKey(conv), "rcs");
        }
        Notification.Action action = replyHandles.get(conv);
        if (action != null) {
            RemoteInput[] inputs = action.getRemoteInputs();
            RemoteInput takesFiles = null;
            for (RemoteInput input : inputs != null ? inputs : new RemoteInput[0]) {
                java.util.Set<String> types = input.getAllowedDataTypes();
                if (types == null) continue;
                for (String t : types) if (android.content.ClipDescription.compareMimeTypes(mime, t)) takesFiles = input;
            }
            if (takesFiles != null) {
                try {
                    Uri uri = Files.share(context, bytes, mime);
                    context.grantUriPermission(Parser.MESSAGES_PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    Bundle results = new Bundle();
                    for (RemoteInput input : inputs) if (!input.isDataOnly()) results.putCharSequence(input.getResultKey(), text);
                    Intent fillIn = new Intent();
                    RemoteInput.addResultsToIntent(inputs, fillIn, results);
                    Map<String, Uri> data = new HashMap<>();
                    data.put(mime, uri);
                    RemoteInput.addDataResultToIntent(takesFiles, fillIn, data);
                    RemoteInput.setResultsSource(fillIn, RemoteInput.SOURCE_FREE_FORM_INPUT);
                    action.actionIntent.send(context, 0, fillIn);
                    return new SendResult(true, null, true, "rcs");
                } catch (PendingIntent.CanceledException e) {
                    replyHandles.remove(conv);
                } catch (IOException e) {
                    Log.w(TAG, "attachment not handed to Messages: " + e.getMessage());
                }
            }
        }
        return mmsFallback(conv, text, bytes, mime);
    }

    /** MMS from this phone: to the number Messages told us, or to everyone in the group as the message store has them. */
    SendResult mmsFallback(String conv, String text, byte[] bytes, String mime) {
        boolean canReply = replyHandles.containsKey(conv);
        if (!canSendSms()) return new SendResult(false, NotSent.ATTACH_PERM, canReply, "mms");
        List<String> to = new ArrayList<>();
        if (Boolean.TRUE.equals(prefs.group(conv))) {
            long thread = prefs.linkedThread(conv);
            if (thread > 0 && storeSync.allowed()) {
                try {
                    Store.Thread t = store.thread(thread);
                    if (t != null) to.addAll(t.addresses);
                } catch (RuntimeException e) {
                    Log.w(TAG, "group members not read", e);
                }
            }
        } else {
            String number = numberFor(conv);
            if (number != null) to.add(number);
        }
        List<String> numbers = Mms.numbers(to);
        if (numbers.isEmpty()) return new SendResult(false, NotSent.NO_ATTACH, canReply, "mms");
        try {
            @SuppressWarnings("deprecation")   // the default subscription is the right one on a single-SIM relay phone
            SmsManager sms = SmsManager.getDefault();
            Mms.Part part = Mms.fit(bytes, mime, text, Mms.limits(sms));
            if (part == null) return new SendResult(false, NotSent.TOO_BIG, canReply, "mms");
            Mms.Outcome outcome = Mms.send(context, sms, Mms.sendRequest(numbers, part, text));
            if (!outcome.sent) Mms.log("MMS not sent: " + Mms.describe(outcome));
            return outcome.sent ? new SendResult(true, null, canReply, "mms") : new SendResult(false, NotSent.MMS_FAILED, canReply, "mms");
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "MMS failed", e);   // the details stay on this phone
            return new SendResult(false, NotSent.MMS_FAILED, canReply, "mms");
        }
    }

    SendResult sendReply(String conv, String text) {
        Notification.Action action = replyHandles.get(conv);
        if (action != null) {
            try {
                RemoteInput[] inputs = action.getRemoteInputs();
                Bundle results = new Bundle();
                for (RemoteInput input : inputs) results.putCharSequence(input.getResultKey(), text);
                Intent fillIn = new Intent();
                RemoteInput.addResultsToIntent(inputs, fillIn, results);
                RemoteInput.setResultsSource(fillIn, RemoteInput.SOURCE_FREE_FORM_INPUT);
                action.actionIntent.send(context, 0, fillIn);
                return new SendResult(true, null, true, "rcs");
            } catch (PendingIntent.CanceledException e) {
                replyHandles.remove(conv);
            }
        }
        return smsFallback(conv, text);
    }

    /**
     * No open notification to reply through (reboot, dismissed, never had one, or a conversation started from the
     * inbox): send a plain SMS from this device. Only for one-to-one conversations whose number is known for certain;
     * group chats stay RCS-only.
     */
    SendResult smsFallback(String conv, String text) {
        if (!canSendSms()) return new SendResult(false, Conversations.startedHere(conv) ? NotSent.START_SMS : NotSent.NO_FALLBACK, false, "rcs");
        if (Boolean.TRUE.equals(prefs.group(conv))) return new SendResult(false, NotSent.GROUP, false, "rcs");
        String number = numberFor(conv);
        if (number == null) return new SendResult(false, NotSent.NO_NUMBER, false, "rcs");
        try {
            @SuppressWarnings("deprecation")   // the default subscription is the right one on a single-SIM relay phone
            SmsManager sms = SmsManager.getDefault();
            sms.sendMultipartTextMessage(number, null, smsParts(text), null, null);
            return new SendResult(true, null, false, "sms");
        } catch (RuntimeException e) {
            Log.w(TAG, "SMS fallback failed", e);   // the details stay on this phone
            return new SendResult(false, NotSent.SMS_FAILED, false, "sms");
        }
    }

    /** Segments short enough for any alphabet (67 UCS-2 characters), so the carrier always reassembles them. */
    static ArrayList<String> smsParts(String text) {
        ArrayList<String> parts = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            int end = Math.min(text.length(), i + 67);
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
            parts.add(text.substring(i, end));
            i = end;
        }
        if (parts.isEmpty()) parts.add("");
        return parts;
    }

    boolean canSendSms() {
        return context.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED;
    }

    /** The number Messages or the message store told us, else the one contact with exactly that name; null when in doubt. */
    String numberFor(String conv) {
        String known = prefs.number(conv);
        if (known != null) return known;
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null;
        String name = prefs.name(conv);
        if (name == null || name.isEmpty() || name.equals("that conversation")) return null;
        Map<Long, List<String[]>> byContact = new LinkedHashMap<>();
        String[] cols = { ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.TYPE };
        try (Cursor c = context.getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, cols,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " = ?", new String[] { name }, null)) {
            if (c == null) return null;
            while (c.moveToNext()) {
                long id = c.getLong(0);
                String number = c.getString(1);
                if (number == null || number.trim().isEmpty()) continue;
                byContact.computeIfAbsent(id, k -> new ArrayList<>()).add(new String[] { number.trim(), c.getString(2) });
            }
        } catch (RuntimeException e) {
            return null;
        }
        if (byContact.size() != 1) return null;   // nobody, or more than one person with that name
        List<String[]> numbers = byContact.values().iterator().next();
        for (String[] n : numbers) {
            if (String.valueOf(ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE).equals(n[1])) return n[0];
        }
        return numbers.get(0)[0];
    }

    // ------------------------------------------------------------------ the private inbox (v2)

    /** The code this phone shows while it isn't in a private inbox, and the token it will have once it's scanned. */
    static final class PendingLink {
        volatile String code;
        volatile String token;
        volatile Crypto.LinkSecrets secrets;
        /** The code whose offer the server has. */
        volatile String filedFor;
        /** Tried getting back in after a reinstall, this process. */
        volatile boolean recovered;

        void forget() {
            code = null;
            token = null;
            secrets = null;
            filedFor = null;
        }
    }

    final PendingLink pending = new PendingLink();

    /** Tests set this in place of the phone's Android ID. */
    static volatile String androidIdForTests;
    private volatile Crypto.PhoneSecrets phoneSecrets;
    private volatile String phoneSecretsFor;

    /** This phone's own secrets for getting back in after a reinstall; null when it has no usable Android ID. */
    Crypto.PhoneSecrets phoneSecrets() {
        String id = androidIdForTests != null ? androidIdForTests : Pairing.androidId(context);
        if (id == null) return null;
        Crypto.PhoneSecrets p = phoneSecrets;
        if (p == null || !id.equals(phoneSecretsFor)) {
            phoneSecrets = p = Crypto.phoneSecrets(id, "relay");
            phoneSecretsFor = id;
        }
        return p;
    }

    /**
     * This phone is in a private inbox now: everything from here on goes there, sealed, and nothing to the first
     * version's inbox. Reconnects the doorbell to the inbox's own channel and files the wrap for after a reinstall.
     */
    void joined(Link link) {
        joined(link, false);
    }

    /**
     * `recovered`: its own link back after a reinstall. What Messages still shows goes up right away (it was kept, not
     * marked relayed, while this phone wasn't in an inbox).
     */
    void joined(Link link, boolean recovered) {
        prefs.setLink(link, recovered);
        lastError = null;
        restartConnections();
        background("relay-wrap", this::fileWrap);
        background("relay-joined", () -> {
            try {
                rescan(false);
            } catch (RuntimeException e) {
                Log.w(TAG, "couldn't read Messages' notifications after joining", e);
            }
        });
    }

    /**
     * A copy of the app's data can reach another phone (backups leave it out, but a rooted phone can copy anything). A
     * link that came along isn't this phone's (it was stored under the Android ID the app had on the old one): Prefs
     * drops it wherever it's read, and this phone links with a code.
     */
    void checkSamePhone() {
        if (prefs.link() != null || !prefs.takeLinkCopied()) return;
        lastError = LINK_COPIED;
        restartConnections();
    }

    /** The inbox says this phone isn't in it any more: another relay phone took over, or someone removed it. */
    void unlinked() {
        if (prefs.link() == null) return;
        // The reason first: whoever sees the link gone sees why.
        lastError = "This phone was removed from your inbox (another relay phone took over, or it was removed in Paigr). Open Paigr Relay to link it again.";
        prefs.clearLink();
        restartConnections();
    }

    /** "Connect through Tor" changed: every connection made the old way goes, and new ones start the new way. */
    void torChanged() {
        restartConnections();
    }

    private synchronized void restartConnections() {
        boolean wasRunning = poller != null;
        stopPoller();
        lastHeartbeat = 0;
        if (wasRunning || prefs.configured()) startPoller();
    }

    /** Files this phone's wrap with the inbox once, so a reinstalled Relay gets back in by itself. */
    void fileWrap() {
        Link l = prefs.link();
        if (l == null) return;
        Crypto.PhoneSecrets p = phoneSecrets();
        if (p == null || p.tag.equals(prefs.wrapTag())) return;
        try {
            new Hedy2(l, null).wrap(p);
            prefs.setWrapTag(p.tag);
        } catch (IOException e) {
            Log.w(TAG, "couldn't file the wrap yet: " + e.getMessage());   // tried again on the next start
        }
    }

    /**
     * A reinstalled Relay on a phone that was linked before: back in its inbox, if the inbox still has this phone.
     * Returns the link it got back, or null.
     */
    Link recover() throws IOException {
        if (prefs.link() != null) return prefs.link();
        if (prefs.everLinked()) return null;   // this install left an inbox, or was told it isn't in one: a code, not a wrap
        Crypto.PhoneSecrets p = phoneSecrets();
        if (p == null) return null;
        Link l = Hedy2.recover(prefs.server(), p);
        if (l == null) return null;
        try {
            new Hedy2(l, null).ping();
        } catch (Hedy.ApiException e) {
            if (e.unlinked) return null;
            throw e;
        }
        joined(l, true);
        prefs.setWrapTag(p.tag);
        return l;
    }

    // ------------------------------------------------------------------ heartbeat

    private volatile String phoneTag;

    /** This phone's tag for the inbox (see Pairing.phoneTag); it lets a reinstalled Relay back in without a scan. */
    String phoneTag() {
        String t = phoneTag;
        if (t == null) phoneTag = t = Pairing.phoneTag(context, "relay");
        return t;
    }

    void heartbeat(Hedy hedy) throws IOException {
        int battery = -1;
        boolean charging = false;
        try {
            Intent b = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b != null) {
                int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                if (level >= 0 && scale > 0) battery = Math.round(100f * level / scale);
                int plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
                charging = plugged != 0;
            }
        } catch (RuntimeException ignored) {
            // some ROMs refuse sticky broadcasts to background callers
        }
        String problem = lastError != null ? lastError : lastMediaError != null ? lastMediaError : storeSync.lastError != null ? storeSync.lastError : "";
        JSONObject body = Json.obj(
                "listening", listening,
                "listenerConnected", listening,
                "replyable", replyHandles.size(),
                "lastRelayed", lastRelayed,
                "queued", outbound.size(),
                "doorbell", doorbellConnected(),
                "smsFallback", canSendSms(),
                "battery", battery,
                "charging", charging,
                "version", VERSION,
                "model", Build.MANUFACTURER + " " + Build.MODEL,
                "wakeHandled", prefs.wakeHandled(),
                "device", phoneTag(),
                "store", Json.obj("allowed", storeSync.allowed(), "lastScan", storeSync.lastScan, "history", historySync.status()),
                "note", Text.clip(problem, 200));
        JSONObject r = hedy.heartbeat(body);
        lastHeartbeat = System.currentTimeMillis();
        checkWake(r.optLong("wake", 0), r.optString("wakeKind", "reconnect"), false);
    }

    /** Tests only: waits until everything queued has been delivered. */
    void drain() {
        long until = System.currentTimeMillis() + 60_000;
        textLane.wake();
        mediaLane.wake();
        while (System.currentTimeMillis() < until) {
            if (textLane.isIdle() && mediaLane.isIdle()) {
                sleep(30);
                if (textLane.isIdle() && mediaLane.isIdle()) return;
            }
            sleep(20);
        }
        throw new IllegalStateException("outbound queue did not drain: " + outbound.size() + " left, " + lastError);
    }

    private static void put(JSONObject o, String key, Object value) {
        try {
            o.put(key, value);
        } catch (org.json.JSONException ignored) {
            // literal keys, plain values
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
