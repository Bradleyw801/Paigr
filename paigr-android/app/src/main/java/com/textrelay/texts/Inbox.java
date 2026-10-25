package com.textrelay.texts;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * The private inbox as the screens see it: its conversations, the messages of the ones that have been opened, and the
 * relay phone's state, kept up to date from the arrival log (and the doorbell) while Texts is on screen. It does what
 * the web page does, step for step (web/app.js: inbox, thread, sending, sync), with two differences that make it
 * quick on a small phone: what the inbox last sent is kept on disk, still sealed, and opened again at the start, so the
 * list and the recent conversations show at once; and the top conversations are fetched ahead of a tap.
 *
 * All of its state lives on the main thread. The network and the opening happen on background threads, whose results
 * are applied back on the main thread, in order.
 */
final class Inbox {
    interface Listener {
        /** What changed: some of LIST, THREAD, RELAY. `end`: a thread should show its newest message. */
        void changed(int what, boolean end);

        /** This phone isn't in its inbox any more (or was unlinked here); `message` says why, or is empty. */
        void unlinked(String message);

        /** A short message for the person. */
        void say(String text, long ms);

        /** The open conversation was folded into another one: back to the list. */
        void conversationGone();
    }

    static final int LIST = 1, THREAD = 2, RELAY = 4;
    static final long SYNC_MS = 5_000;
    static final long SYNC_CONNECTED_MS = 20_000;
    static final long RELAY_OFFLINE_MS = 10 * 60_000L;
    /** Conversations fetched ahead of a tap, newest first. */
    static final int PREFETCH = 6;
    /** Conversations whose first page is kept on disk. */
    static final int CACHE_THREADS = 40;

    private static Inbox instance;

    static synchronized Inbox get(Context context) {
        if (instance == null) instance = new Inbox(context.getApplicationContext());
        return instance;
    }

    /** Tests start over with a fresh inbox. */
    static synchronized void resetForTests() {
        if (instance != null) instance.stop();
        instance = null;
    }

    final Context app;
    final Prefs prefs;
    final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(3, daemon("texts-io"));
    private final ExecutorService disk = Executors.newSingleThreadExecutor(daemon("texts-disk"));
    Listener listener;
    final Media media;

    // ------------------------------------------------------------------ state (main thread)

    Link link;
    Opener open;
    final Map<String, Model.Conv> convs = new LinkedHashMap<>();
    final Map<String, List<Model.Msg>> msgs = new HashMap<>();
    final Map<String, String> cursors = new HashMap<>();
    final Set<String> complete = new HashSet<>();
    String feed;
    Long epoch;
    Model.Relay relay;
    boolean loadedInbox;
    /** The list on screen came from disk and the inbox hasn't answered yet. */
    boolean fromDisk;
    /** The conversation on screen, or null. */
    String current;
    /** "inbox", "thread", "details" or "settings": what's on screen. */
    String view = "inbox";
    /** In-thread search is open: the thread isn't redrawn under it. */
    boolean finding;
    final Set<String> pendingOut = new HashSet<>();
    private boolean syncing;
    private boolean visible;
    private Doorbell doorbell;
    private long lastRing;
    private boolean reconnecting;

    private Inbox(Context app) {
        this.app = app;
        this.prefs = new Prefs(app);
        this.media = new Media(this);
    }

    private static ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    void listen(Listener l) {
        listener = l;
    }

    private void tell(int what, boolean end) {
        if (listener != null) listener.changed(what, end);
    }

    private void say(String text, long ms) {
        if (listener != null) listener.say(text, ms);
    }

    /** Bumped whenever this phone starts using another inbox (or none): work begun before never lands after. */
    private int gen;

    /** Runs `work` on a background thread and `then` with its result on the main thread, for the same inbox only. */
    private <T> void run(Work<T> work, Done<T> then, Failed failed) {
        int at = gen;
        io.execute(() -> {
            T result;
            try {
                result = work.run();
            } catch (Exception e) {
                main.post(() -> {
                    if (gen != at) return;
                    if (e instanceof Hedy.ApiException && ((Hedy.ApiException) e).unlinked) {
                        unlinkedHere("This phone isn't linked to your inbox any more.");
                        return;
                    }
                    if (failed != null) failed.failed(e);
                });
                return;
            }
            main.post(() -> {
                if (gen != at) return;   // work started for one inbox never lands in another
                then.done(result);
            });
        });
    }

    interface Work<T> {
        T run() throws Exception;
    }

    interface Done<T> {
        void done(T result);
    }

    interface Failed {
        void failed(Exception e);
    }

    /** What to tell the person about a failure, in this app's own words. */
    static String words(Exception e) {
        if (e instanceof Hedy.ApiException) {
            Hedy.ApiException a = (Hedy.ApiException) e;
            if (!a.said.isEmpty()) return a.said;
            if (a.code == 429) return "Too many tries just now. Wait a minute and try again.";
            if (a.code == 404) return "The inbox doesn't have that any more.";
            if (a.code >= 500) return "The inbox had a problem. Try again in a moment.";
            if (a.code > 0) return "The inbox refused that.";
        }
        if (e instanceof Tor.Down) return e.getMessage();
        if (e instanceof IOException && Tor.wanted()) return "Can't reach the inbox through Tor. Check that Orbot is connected.";
        if (e instanceof IOException) return "Can't reach the inbox. Check this phone's connection.";
        return "Something went wrong. Try again.";
    }

    // ------------------------------------------------------------------ the inbox this phone is in

    /** Starts using this inbox (or none): nothing opened with another inbox's key stays in memory. */
    void useLink(Link l) {
        if (link != null && (l == null || !l.tid.equals(link.tid))) {
            media.forget();
            if (open != null) open.forget();
            clearModel();
            disk.execute(this::wipeDisk);
        }
        if (l == null) {
            stopSync();
            clearModel();
        }
        if (l != link) {
            gen++;
            syncing = false;
            reconnecting = false;
        }
        link = l;
        open = l != null ? new Opener(l) : null;
    }

    private void clearModel() {
        convs.clear();
        msgs.clear();
        cursors.clear();
        complete.clear();
        pendingOut.clear();
        feed = null;
        epoch = null;
        relay = null;
        loadedInbox = false;
        fromDisk = false;
        current = null;
    }

    Hedy hedy() {
        return new Hedy(link);
    }

    /** This phone just joined `l` (a scan, or getting back in after a reinstall): kept, named, and synced. */
    void adopt(Link l) {
        useLink(l);
        clearModel();
        prefs.setLink(l);
        SyncService.stop(app);   // a fresh start, on the new inbox's channel
        if (prefs.paired()) SyncService.start(app);
        else SyncService.notifier(app).clearAll();
        new Thread(() -> MainActivity.fileWrap(app, prefs, l), "texts-wrap").start();
        nameThisDevice();
        if (visible) startSync();
    }

    /** Taken out of the inbox (or unlinked here): the link goes, and the screen asks for a code. */
    void unlinkedHere(String message) {
        if (link != null || prefs.link() != null) {
            useLink(null);
            prefs.setLink(null);
            SyncService.stop(app);
            SyncService.notifier(app).clearAll();
        }
        if (listener != null) listener.unlinked(message == null ? "" : message);
    }

    // ------------------------------------------------------------------ on screen or not

    void resume() {
        visible = true;
        if (link == null) return;
        if (!loadedInbox && convs.isEmpty()) fromDiskThenSync();
        else startSync();
    }

    void pause() {
        visible = false;
        stopSync();
    }

    boolean isVisible() {
        return visible;
    }

    /** Whether the server's socket is open, so a ring brings news at once. */
    boolean doorbellUp() {
        Doorbell d = doorbell;
        return d != null && d.isConnected();
    }

    /** How many times the server has rung (for tests). */
    volatile int ringsHeard;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!visible || link == null) return;
            boolean up = doorbell != null && doorbell.isConnected();
            if (!up || System.currentTimeMillis() - lastRing > SYNC_CONNECTED_MS) sync();
            main.postDelayed(this, SYNC_MS);
        }
    };

    void startSync() {
        stopSync();
        if (link == null) return;
        sync();
        main.postDelayed(poll, SYNC_MS);
        Link l = link;
        doorbell = new Doorbell(l.server, "inbox", new Doorbell.Listener() {
            @Override
            public void onRing(String type, JSONObject payload) {
                ringsHeard++;
                main.post(() -> {
                    lastRing = System.currentTimeMillis();
                    sync();
                });
            }

            @Override
            public void onStateChange(boolean connected) {
                if (connected) main.post(() -> {
                    lastRing = System.currentTimeMillis();
                    sync();
                });
            }
        }, () -> new Hedy(l).get("/v2/ticket"));
        doorbell.start();
    }

    void stopSync() {
        main.removeCallbacks(poll);
        Doorbell d = doorbell;
        doorbell = null;
        if (d != null) d.stop();
    }

    void stop() {
        stopSync();
        visible = false;
    }

    // ------------------------------------------------------------------ the list

    /** The list as the inbox last sent it, opened again from disk, then the inbox itself. */
    private void fromDiskThenSync() {
        Link at = link;
        Opener o = open;
        disk.execute(() -> {
            List<Model.Conv> list = null;
            JSONObject relayRaw = null;
            try {
                JSONObject saved = readDisk(inboxFile(), at);
                if (saved != null) {
                    JSONArray items = saved.optJSONArray("items");
                    list = new ArrayList<>();
                    for (int i = 0; items != null && i < items.length(); i++) {
                        JSONObject c = items.optJSONObject(i);
                        if (c != null) list.add(o.openConv(c));
                    }
                    relayRaw = saved.optJSONObject("relay");
                }
            } catch (RuntimeException e) {
                list = null;
            }
            List<Model.Conv> got = list;
            JSONObject r = relayRaw;
            main.post(() -> {
                if (link != at) return;
                if (got != null && !loadedInbox && convs.isEmpty()) {
                    for (Model.Conv c : got) convs.put(c.conv, c);
                    if (relay == null && r != null) relay = open.openRelay(r);
                    fromDisk = true;
                    tell(LIST | RELAY, false);
                }
                if (visible) startSync();
            });
        });
    }

    /** The inbox's summary of every conversation, opened here. */
    private static final class Loaded {
        JSONObject raw;
        List<Model.Conv> items = new ArrayList<>();
        Model.Relay relay;
        String feed;
        Long epoch;
    }

    private Loaded fetchInbox(Link at, Opener o) throws IOException {
        JSONObject data = new Hedy(at).get("/v2/inbox");
        Loaded out = new Loaded();
        out.raw = data;
        JSONArray items = data.optJSONArray("items");
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject c = items.optJSONObject(i);
            if (c != null && c.opt("conv") instanceof String) out.items.add(o.openConv(c));
        }
        out.relay = o.openRelay(data.optJSONObject("relay"));
        String f = data.optString("feed", "");
        out.feed = f.isEmpty() ? null : f;
        Object ep = data.opt("epoch");
        out.epoch = ep instanceof Number ? ((Number) ep).longValue() : null;
        return out;
    }

    private void applyInbox(Loaded d) {
        convs.clear();
        for (Model.Conv c : d.items) convs.put(c.conv, c);
        if (feed == null) feed = d.feed;
        if (d.epoch != null) epoch = d.epoch;
        relay = d.relay;
        loadedInbox = true;
        fromDisk = false;
        tell(LIST | RELAY, false);
        Link at = link;
        JSONObject raw = d.raw;
        disk.execute(() -> writeDisk(inboxFile(), at, raw));
        prefetch();
    }

    void loadInbox(Runnable then, Failed failed) {
        Opener o = open;
        Link at = link;
        run(() -> fetchInbox(at, o), d -> {
            applyInbox(d);
            if (then != null) then.run();
        }, failed);
    }

    /** Pull to refresh: the list from the inbox now, and the relay phone checks Messages and its message store. */
    void refresh(Runnable done) {
        if (link == null) {
            if (done != null) done.run();
            return;
        }
        wake("refresh", null);
        loadInbox(() -> {
            sync();
            if (done != null) done.run();
        }, e -> {
            say(words(e), 3000);
            if (done != null) done.run();
        });
    }

    /** Every conversation, newest first, matching the search when there is one. */
    List<Model.Conv> list(String filter) {
        List<Model.Conv> out = new ArrayList<>(convs.values());
        out.sort((a, b) -> Long.compare(b.lastTs, a.lastTs));
        if (filter != null && !filter.isEmpty()) {
            String q = filter.toLowerCase(java.util.Locale.ROOT);
            out.removeIf(c -> !((c.name + " " + c.lastText).toLowerCase(java.util.Locale.ROOT).contains(q)));
        }
        return out;
    }

    int unreadElsewhere() {
        int n = 0;
        for (Model.Conv c : convs.values()) if (!c.conv.equals(current)) n += c.unread;
        return n;
    }

    // ------------------------------------------------------------------ a thread

    /** One page of a conversation, every message opened as the conversation that was asked for. */
    private static final class Page {
        JSONObject raw;
        List<Model.Msg> items = new ArrayList<>();
        String cursor;
        Model.Conv conversation;
    }

    private Page fetchPage(Link at, Opener o, String conv, String cursor, List<String> known) throws IOException {
        JSONObject data = new Hedy(at).get("/v2/thread?conv=" + enc(conv) + (cursor != null ? "&cursor=" + enc(cursor) : ""));
        return openPage(o, conv, data, known);
    }

    private static Page openPage(Opener o, String conv, JSONObject data, List<String> known) {
        Page p = new Page();
        p.raw = data;
        JSONObject summary = data.optJSONObject("conversation");
        if (summary != null) {
            try {
                p.conversation = o.openConv(new JSONObject(summary.toString()).put("conv", conv));
            } catch (JSONException e) {
                p.conversation = null;
            }
        }
        List<String> aliases = p.conversation != null ? p.conversation.aliases : known;
        JSONArray items = data.optJSONArray("items");
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject m = items.optJSONObject(i);
            if (m == null) continue;
            try {
                p.items.add(o.openMessage(new JSONObject(m.toString()).put("conv", conv), aliases));
            } catch (JSONException ignored) {
                // not a message
            }
        }
        String c = data.optString("cursor", "");
        p.cursor = data.isNull("cursor") || c.isEmpty() ? null : c;
        return p;
    }

    /** What the screen already knows a conversation answers to; just itself until its summary is opened. */
    List<String> knownAliases(String conv) {
        Model.Conv c = convs.get(conv);
        return c != null && c.aliases != null && !c.aliases.isEmpty() ? c.aliases : java.util.Collections.singletonList(conv);
    }

    /** Opens a conversation: what's held here shows at once, then the inbox's newest page replaces it. */
    void openThread(String conv) {
        current = conv;
        view = "thread";
        finding = false;
        SyncService.notifier(app).clear(conv);
        Link at = link;
        Opener o = open;
        if (at == null) return;
        boolean held = msgs.containsKey(conv);
        if (!held) {
            disk.execute(() -> {
                JSONObject saved = readDisk(threadFile(conv), at);
                if (saved == null) return;
                Page p = openPage(o, conv, saved, java.util.Collections.singletonList(conv));
                main.post(() -> {
                    if (link != at || msgs.containsKey(conv)) return;
                    msgs.put(conv, Model.tidy(p.items));
                    if (conv.equals(current)) tell(THREAD, true);
                });
            });
        } else {
            tell(THREAD, true);
        }
        List<String> known = knownAliases(conv);
        run(() -> fetchPage(at, o, conv, null, known), p -> {
            boolean first = !msgs.containsKey(conv);
            msgs.put(conv, Model.tidy(p.items));
            cursors.put(conv, p.cursor);
            if (p.cursor == null) complete.add(conv);
            else complete.remove(conv);
            if (p.conversation != null) convs.put(conv, p.conversation);
            JSONObject raw = p.raw;
            disk.execute(() -> writeDisk(threadFile(conv), at, raw));
            // Another conversation was opened meanwhile: never drawn under that one's name.
            if (!conv.equals(current)) return;
            tell(THREAD | LIST, first);
            Model.Conv now = convs.get(conv);
            if (now != null && now.unread > 0) {
                now.unread = 0;
                tell(LIST, false);
                markRead(conv);
            }
        }, e -> {
            if (conv.equals(current)) say(words(e), 3000);
        });
    }

    void closeThread() {
        current = null;
        view = "inbox";
        finding = false;
    }

    private void markRead(String conv) {
        Link at = link;
        io.execute(() -> {
            try {
                new Hedy(at).post("/v2/read", new JSONObject().put("conv", conv));
            } catch (IOException | JSONException ignored) {
                // the next open marks it again
            }
        });
    }

    List<Model.Msg> thread(String conv) {
        List<Model.Msg> l = msgs.get(conv);
        return l != null ? l : java.util.Collections.emptyList();
    }

    boolean hasEarlier(String conv) {
        return cursors.get(conv) != null;
    }

    /** The page before the oldest one held. `then` gets how many messages came in front. */
    void loadEarlier(Done<Integer> then) {
        String conv = current;
        String cursor = conv != null ? cursors.get(conv) : null;
        if (conv == null || cursor == null) return;
        Link at = link;
        Opener o = open;
        List<String> known = knownAliases(conv);
        run(() -> fetchPage(at, o, conv, cursor, known), p -> {
            if (!cursor.equals(cursors.get(conv))) return;   // reloaded meanwhile
            List<Model.Msg> have = msgs.containsKey(conv) ? msgs.get(conv) : new ArrayList<>();
            Set<String> keys = new HashSet<>();
            for (Model.Msg m : have) keys.add(m.k);
            List<Model.Msg> all = new ArrayList<>();
            for (Model.Msg m : p.items) if (!keys.contains(m.k)) all.add(m);
            int before = have.size();
            all.addAll(have);
            List<Model.Msg> tidied = Model.tidy(all);
            msgs.put(conv, tidied);
            cursors.put(conv, p.cursor);
            if (p.cursor == null) complete.add(conv);
            if (conv.equals(current) && then != null) then.done(tidied.size() - before);
        }, e -> say(words(e), 3000));
    }

    /** The rest of a conversation's history, so search and the details cover all of it. */
    void loadWhole(String conv, Runnable then) {
        if (complete.contains(conv) || cursors.get(conv) == null) {
            if (cursors.get(conv) == null && msgs.containsKey(conv)) complete.add(conv);
            if (then != null) then.run();
            return;
        }
        String start = cursors.get(conv);
        Link at = link;
        Opener o = open;
        List<String> known = knownAliases(conv);
        run(() -> {
            List<Model.Msg> got = new ArrayList<>();
            String cursor = start;
            for (int page = 0; page < 60 && cursor != null; page++) {
                Page p = fetchPage(at, o, conv, cursor, known);
                got.addAll(p.items);
                cursor = p.cursor;
            }
            return new Object[] { got, cursor };
        }, r -> {
            if (!start.equals(cursors.get(conv))) {   // reloaded meanwhile
                if (then != null) then.run();
                return;
            }
            @SuppressWarnings("unchecked") List<Model.Msg> got = (List<Model.Msg>) r[0];
            List<Model.Msg> have = msgs.containsKey(conv) ? msgs.get(conv) : new ArrayList<>();
            Set<String> keys = new HashSet<>();
            for (Model.Msg m : have) keys.add(m.k);
            List<Model.Msg> all = new ArrayList<>();
            for (Model.Msg m : got) if (!keys.contains(m.k)) all.add(m);
            all.addAll(have);
            msgs.put(conv, Model.tidy(all));
            String left = (String) r[1];
            cursors.put(conv, left);
            if (left == null) complete.add(conv);
            if (then != null) then.run();
        }, e -> {
            say(words(e), 3000);
            if (then != null) then.run();
        });
    }

    /** The newest page of a conversation again, in place (the relay phone filled it in from its history). */
    private void reloadThread(String conv) {
        Link at = link;
        Opener o = open;
        List<String> known = knownAliases(conv);
        run(() -> fetchPage(at, o, conv, null, known), p -> {
            if (!conv.equals(current)) return;
            msgs.put(conv, Model.tidy(p.items));
            cursors.put(conv, p.cursor);
            if (p.cursor == null) complete.add(conv);
            else complete.remove(conv);
            if (p.conversation != null) mergeConversation(p.conversation);
            if (!finding) tell(THREAD | LIST, false);
        }, null);
    }

    /** Replies waiting for a sent or failed status: their newest state. */
    private void refreshPending() {
        String conv = current;
        if (conv == null) return;
        Link at = link;
        Opener o = open;
        List<String> known = knownAliases(conv);
        run(() -> fetchPage(at, o, conv, null, known), p -> {
            List<Model.Msg> have = msgs.containsKey(conv) ? new ArrayList<>(msgs.get(conv)) : new ArrayList<>();
            for (Model.Msg m : p.items) {
                for (int i = 0; i < have.size(); i++) if (have.get(i).k.equals(m.k)) have.set(i, m);
                if (m.out() && !"queued".equals(m.status)) pendingOut.remove(m.k);
            }
            if (msgs.containsKey(conv)) msgs.put(conv, Model.tidy(have));
            if (p.conversation != null) convs.put(conv, p.conversation);
            if (conv.equals(current) && "thread".equals(view) && !finding) tell(THREAD | LIST, false);
        }, null);
    }

    // ------------------------------------------------------------------ sending

    /** A file on this phone, about to go out with a message. */
    static final class Attachment {
        final android.net.Uri uri;
        final String mime;
        final String name;
        final long bytes;

        Attachment(android.net.Uri uri, String mime, String name, long bytes) {
            this.uri = uri;
            this.mime = mime;
            this.name = name;
            this.bytes = bytes;
        }
    }

    /** The most a file sent from here can be: what the inbox takes by a ticket (inbox2.js TICKET_MAX, less a margin). */
    static final long ATTACH_MAX = 25L * 1024 * 1024 - 4096;
    /** A picture bigger than this (or wider) also goes up as a small preview, so threads load it quickly. */
    private static final int PREVIEW_FROM_BYTES = 300 * 1024, PREVIEW_FROM_SIDE = 1280, PREVIEW_SIDE = 960;

    void send(String conv, String text) {
        send(conv, text, null);
    }

    /**
     * Sends a text, an attachment, or both. An attachment is sealed and stored first (with a preview when it's a big
     * picture), then the message names it: every inbox device by its record, the relay phone by the path inside the
     * sealed request, which it fetches and sends on as the text (PROTOCOL.md, Replies).
     */
    void send(String conv, String text, Attachment att) {
        String t = text == null ? "" : text.trim();
        if ((t.isEmpty() && att == null) || conv == null || link == null) return;
        long now = System.currentTimeMillis();
        Model.Msg temp = new Model.Msg();
        temp.k = "tmp:" + now + ":" + Crypto.hex(Crypto.random(3));
        temp.conv = conv;
        temp.dir = "out";
        temp.status = "queued";
        temp.text = t;
        temp.ts = now;
        temp.temp = true;
        temp.seal = "";
        if (att != null) {
            temp.media = new Model.Media();
            temp.media.mime = att.mime;
            temp.media.bytes = Math.max(0, att.bytes);
            temp.media.localUri = att.uri.toString();
        }
        List<Model.Msg> arr = msgs.get(conv);
        if (arr == null) msgs.put(conv, arr = new ArrayList<>());
        arr.add(temp);
        tell(THREAD, true);
        Link at = link;
        run(() -> {
            JSONObject shown = new JSONObject().put("text", t).put("ts", now);
            JSONObject request = new JSONObject().put("text", t).put("n", Crypto.b64u(Crypto.random(16))).put("ts", now);
            JSONObject body = new JSONObject().put("conv", conv);
            String mime = "";
            if (att != null) {
                byte[] plain = media.readLocal(att.uri, ATTACH_MAX);
                mime = att.mime;
                int[] wh = mime.startsWith("image/") ? Media.size(plain) : new int[] { 0, 0 };
                // Its files are named by a fingerprint this phone makes up, as the relay phone names its own.
                String fp = at.fpId(Crypto.hex(Crypto.random(8)));
                String path = new Hedy(at).upload(conv, fp, now, "original", keep(at, conv, now, fp, "original", plain));
                if (path.isEmpty()) throw new IOException("the inbox didn't say where it stored the file");
                JSONObject where = new JSONObject().put("path", path);
                if (mime.startsWith("image/") && !mime.equals("image/gif") && (plain.length > PREVIEW_FROM_BYTES || wh[0] > PREVIEW_FROM_SIDE || wh[1] > PREVIEW_FROM_SIDE)) {
                    byte[] small = Media.shrink(plain, PREVIEW_SIDE, 80);
                    if (small != null && small.length < plain.length) {
                        String preview = new Hedy(at).upload(conv, fp, now, "preview", keep(at, conv, now, fp, "preview", small));
                        if (!preview.isEmpty()) where.put("preview", preview);
                    }
                }
                JSONObject about = new JSONObject().put("mime", mime).put("w", wh[0]).put("h", wh[1]).put("bytes", plain.length);
                shown.put("fp", fp).put("media", about);
                request.put("media", new JSONObject().put("path", path).put("mime", mime).put("bytes", plain.length).put("name", att.name));
                body.put("media", where);
                temp.media.w = wh[0];
                temp.media.h = wh[1];
                temp.media.bytes = plain.length;
                temp.media.path = path;
                temp.media.preview = where.optString("preview", null);
            }
            // How every inbox device shows it: a message this device sent, at this time.
            body.put("e", at.seal(shown, Crypto.aadMessage(conv, "out")))
                    // What the relay phone acts on: for this conversation alone, with a nonce and the time (PROTOCOL.md, Replies).
                    .put("o", at.seal(request, Crypto.aadOut(conv)))
                    .put("mt", at.matchToken("out", t, mime));
            return new Hedy(at).post("/v2/send", body);
        }, r -> {
            String id = r.optString("id", "");
            List<Model.Msg> list = msgs.get(conv);
            if (list != null && !id.isEmpty()) {
                boolean already = false;
                for (Model.Msg m : list) if (m.k.equals(id)) already = true;
                if (already) list.remove(temp);   // a sync already brought it
                else {
                    temp.k = id;
                    temp.temp = false;
                }
            }
            if (!id.isEmpty()) pendingOut.add(id);
            Model.Conv c = convs.get(conv);
            if (c != null) {
                c.lastTs = temp.ts;
                c.lastText = !t.isEmpty() ? t : att != null ? Opener.mediaKind(att.mime) : "";
                c.lastDir = "out";
            }
            if (Boolean.FALSE.equals(r.opt("canReply")) && conv.equals(current)) say("Queued. It goes out after their next text reaches the relay phone.", 4000);
            tell(THREAD | LIST, conv.equals(current));
        }, e -> {
            temp.status = "failed";
            temp.code = e instanceof IOException && "too big".equals(e.getMessage()) ? "too_big_here" : "local";
            tell(THREAD, false);
        });
    }

    /** Seals a file for its conversation and message, keeps the sealed copy here (so it shows without a download), and returns it. */
    private byte[] keep(Link at, String conv, long ts, String fp, String kind, byte[] plain) {
        byte[] sealed = at.sealFile(plain, Crypto.aadFile(conv, ts, fp, kind));
        media.keepSealed(at, Opener.mediaPath(at.tid, conv, ts, fp, kind), sealed);
        return sealed;
    }

    /** Reacts to a message, as a text in the form phones show as a reaction (Words); `remove` takes one back. */
    void react(Model.Msg target, String emoji, boolean remove) {
        if (target == null || target.unchecked || emoji == null || emoji.isEmpty()) return;
        String to = target.text.isEmpty() ? null : target.text;
        send(target.conv, Words.reactionText(emoji, to, Words.kindOf(target.media), remove), null);
    }

    /** A reply that didn't go out, written again as a new one (an attachment is read from this phone again). */
    void sendAgain(Model.Msg m) {
        if (m == null || !m.out() || m.unchecked || (m.text.isEmpty() && (m.media == null || m.media.localUri == null))) return;
        List<Model.Msg> list = msgs.get(m.conv);
        if (list != null && m.temp) list.remove(m);
        Attachment again = m.media != null && m.media.localUri != null
                ? new Attachment(android.net.Uri.parse(m.media.localUri), m.media.mime, "file", m.media.bytes) : null;
        send(m.conv, m.text, again);
    }

    // ------------------------------------------------------------------ the arrival log

    /** Adds or updates one message. Returns whether anything changed. Never counts unread: the summaries do. */
    boolean mergeMessage(Model.Msg m) {
        boolean changed = false;
        boolean added = false;
        List<Model.Msg> arr = msgs.get(m.conv);
        if (arr != null) {
            int i = -1;
            for (int j = 0; j < arr.size(); j++) if (arr.get(j).k.equals(m.k)) { i = j; break; }
            if (i < 0) {
                // The same sealed message again, under another key: shown once (the copy that opens, if one doesn't).
                int twin = -1;
                if (!m.seal.isEmpty()) for (int j = 0; j < arr.size(); j++) if (arr.get(j).seal.equals(m.seal)) { twin = j; break; }
                if (twin >= 0) {
                    if (!(arr.get(twin).unchecked && !m.unchecked)) return false;
                    arr.remove(twin);
                }
                arr.add(m);
                arr.sort(Model.BY_TIME);
                changed = added = true;
            } else if (!(!"queued".equals(arr.get(i).status) && "queued".equals(m.status)) && !arr.get(i).same(m)) {
                arr.set(i, m);   // a sent or failed status never goes back to queued
                // What the server put under this key may be another message's copy: still each message once, in order.
                List<Model.Msg> tidied = Model.tidy(arr);
                arr.clear();
                arr.addAll(tidied);
                changed = true;
            }
        }
        if (!convs.containsKey(m.conv)) {
            Model.Conv c = new Model.Conv();
            c.conv = m.conv;
            c.name = !m.name.isEmpty() ? m.name : !m.sender.isEmpty() ? m.sender : "Unknown";
            c.group = m.group;
            c.lastTs = m.ts;
            c.lastText = !m.text.isEmpty() ? m.text : m.media != null ? m.media.kind() : "";
            c.lastDir = m.dir;
            c.lastSender = m.sender;
            c.aliases = java.util.Collections.singletonList(m.conv);
            c.aliasKey = m.conv;
            convs.put(m.conv, c);
            changed = added = true;
        }
        if (m.out() && !"queued".equals(m.status)) pendingOut.remove(m.k);
        if (added && !m.out() && !m.read && m.conv.equals(current) && "thread".equals(view)) markRead(m.conv);
        return changed;
    }

    /** The inbox's summary of a conversation wins, unread count included. The open conversation reads as read. */
    boolean mergeConversation(Model.Conv c) {
        if (c == null || c.conv.isEmpty()) return false;
        Model.Conv next = c.copy();
        if (c.conv.equals(current) && "thread".equals(view)) next.unread = 0;
        Model.Conv cur = convs.get(c.conv);
        if (cur != null && cur.sameSummary(next)) return false;
        convs.put(c.conv, next);
        return true;
    }

    /** One read of the arrival log, opened here: the rounds in order, or a sign to start over. */
    private static final class Round {
        List<Model.Msg> items = new ArrayList<>();
        List<Model.Conv> convs = new ArrayList<>();
        List<String> reload = new ArrayList<>();
        String cursor;
        Model.Relay relay;
        boolean startOver;
    }

    private static final class Catchup {
        Loaded inbox;
        List<Round> rounds = new ArrayList<>();
    }

    void sync() {
        if (syncing || link == null || !visible) return;
        syncing = true;
        Link at = link;
        Opener o = open;
        boolean needInbox = !loadedInbox;
        String from = feed;
        Long knownEpoch = epoch;
        Map<String, List<String>> known = new HashMap<>();
        for (Model.Conv c : convs.values()) known.put(c.conv, c.aliases);
        run(() -> {
            Catchup out = new Catchup();
            String cursor = from;
            Long ep = knownEpoch;
            if (needInbox) {
                out.inbox = fetchInbox(at, o);
                if (cursor == null) cursor = out.inbox.feed;
                if (out.inbox.epoch != null) ep = out.inbox.epoch;
                for (Model.Conv c : out.inbox.items) known.put(c.conv, c.aliases);
            }
            if (cursor == null) return out;
            // Arrival order, not message time: a text that reaches the server late still shows up here.
            for (int round = 0; round < 10; round++) {
                JSONObject data = new Hedy(at).get("/v2/sync?feed=" + enc(cursor));
                Round r = new Round();
                out.rounds.add(r);
                r.relay = o.openRelay(data.optJSONObject("relay"));
                Object e = data.opt("epoch");
                if (data.optBoolean("reset", false) || (e instanceof Number && ep != null && ((Number) e).longValue() != ep)) {
                    // Away longer than the log keeps, or the relay phone found two conversations to be one: start over.
                    r.startOver = true;
                    break;
                }
                JSONArray cs = data.optJSONArray("convs");
                Map<String, List<String>> fresh = new HashMap<>();
                for (int i = 0; cs != null && i < cs.length(); i++) {
                    JSONObject c = cs.optJSONObject(i);
                    if (c == null || !(c.opt("conv") instanceof String)) continue;
                    Model.Conv oc = o.openConv(c);
                    r.convs.add(oc);
                    fresh.put(oc.conv, oc.aliases);
                }
                JSONArray items = data.optJSONArray("items");
                for (int i = 0; items != null && i < items.length(); i++) {
                    JSONObject m = items.optJSONObject(i);
                    if (m == null) continue;
                    String mc = m.optString("conv", "");
                    List<String> al = fresh.containsKey(mc) ? fresh.get(mc) : known.containsKey(mc) ? known.get(mc) : java.util.Collections.singletonList(mc);
                    r.items.add(o.openMessage(m, al));
                }
                JSONArray reload = data.optJSONArray("reload");
                for (int i = 0; reload != null && i < reload.length(); i++) if (reload.opt(i) instanceof String) r.reload.add(reload.optString(i));
                String next = data.optString("cursor", "");
                if (!next.isEmpty()) {
                    r.cursor = next;
                    cursor = next;
                }
                if (!data.optBoolean("more", false)) break;
            }
            return out;
        }, out -> {
            syncing = false;
            if (out.inbox != null) applyInbox(out.inbox);
            boolean touched = false;
            String reopen = null;
            for (Round r : out.rounds) {
                if (r.startOver) {
                    relay = r.relay != null ? r.relay : relay;
                    reloadEverything(null);
                    return;
                }
                for (Model.Msg m : r.items) if (mergeMessage(m)) touched = true;
                for (Model.Conv c : r.convs) if (mergeConversation(c)) touched = true;
                // Filled in by a full-history sync: what's held here for those conversations is out of date.
                for (String conv : r.reload) {
                    if (conv.equals(current) && ("thread".equals(view) || "details".equals(view))) reopen = conv;
                    else {
                        msgs.remove(conv);
                        cursors.remove(conv);
                        complete.remove(conv);
                    }
                }
                if (r.cursor != null) feed = r.cursor;
                if (r.relay != null) relay = r.relay;
            }
            tell(RELAY | (touched ? LIST | (current != null && "thread".equals(view) && !finding ? THREAD : 0) : 0), false);
            if (reopen != null) reloadThread(reopen);
            if (!pendingOut.isEmpty() && current != null) refreshPending();
            torDown(false);
        }, e -> {
            syncing = false;
            torDown(e instanceof Tor.Down);
        });
    }

    /** Tor is on but isn't running here, so nothing reaches the inbox (the list says so, and how to fix it). */
    volatile boolean torDown;

    private void torDown(boolean down) {
        if (down == torDown) return;
        torDown = down;
        tell(RELAY, false);
    }

    /** Throws away everything held here and reloads every conversation; the open one reloads too. */
    void reloadEverything(Runnable then) {
        String was = current;
        String shown = view;
        convs.clear();
        msgs.clear();
        cursors.clear();
        complete.clear();
        feed = null;
        epoch = null;
        loadedInbox = false;
        loadInbox(() -> {
            if (was != null && was.equals(current) && ("thread".equals(shown) || "details".equals(shown))) {
                if (convs.containsKey(was)) openThread(was);
                else if (listener != null) listener.conversationGone();
            }
            if (then != null) then.run();
        }, e -> {
            say(words(e), 3000);
            if (then != null) then.run();
        });
    }

    /** Everything here again, and the relay phone sends anything from the last week it still has. */
    void resync(Runnable done) {
        say("Resyncing…", 2000);
        reloadEverything(() -> wake("resync", ok -> {
            say("All " + convs.size() + " conversations reloaded." + (ok ? " The relay phone is checking its messages too." : ""), 4500);
            if (done != null) done.run();
        }));
    }

    // ------------------------------------------------------------------ the relay phone

    static final class Health {
        final String level;   // "none", "bad", "warn", "ok"
        final String chip;
        final String title;
        final boolean fix;

        Health(String level, String chip, String title, boolean fix) {
            this.level = level;
            this.chip = chip;
            this.title = title;
            this.fix = fix;
        }
    }

    /**
     * Healthy unless the connection has actually failed: no check-in for 10 minutes, or the relay phone says it lost
     * Messages. A quiet stretch with no new texts is normal and says nothing about the connection.
     */
    static Health health(Model.Relay r, long now) {
        if (r == null || r.lastSeen == 0) return new Health("none", "Relay", "Relay phone hasn't checked in yet", false);
        if (now - r.lastSeen >= RELAY_OFFLINE_MS) return new Health("bad", "Relay offline", "Relay phone is offline", true);
        if (Boolean.FALSE.equals(r.listening)) return new Health("warn", "Check Relay", "Relay phone isn't reading Messages", true);
        String chip = "Relay";
        if (!Double.isNaN(r.battery) && r.battery > 0) chip += " " + Math.round(r.battery) + "%";
        return new Health("ok", chip, "Relay phone connected", false);
    }

    interface Asked {
        void asked(boolean ok);
    }

    /** Rings the relay phone: "reconnect", "resync" or "refresh". */
    void wake(String kind, Asked then) {
        if (link == null) {
            if (then != null) then.asked(false);
            return;
        }
        Link at = link;
        run(() -> new Hedy(at).post("/v2/relay/wake", new JSONObject().put("kind", kind)), r -> {
            if (then != null) then.asked(true);
        }, e -> {
            if (then != null) then.asked(false);
        });
    }

    boolean reconnecting() {
        return reconnecting;
    }

    /** Asks the relay phone to reopen its connection, re-bind to Messages and check in, then watches for that. */
    void reconnectRelay() {
        if (reconnecting || link == null) return;
        reconnecting = true;
        tell(RELAY, false);
        long before = relay != null ? relay.lastSeen : 0;
        Link at = link;
        Opener o = open;
        run(() -> new Hedy(at).post("/v2/relay/wake", new JSONObject().put("kind", "reconnect")), r -> {
            say("Asked the relay phone to reconnect…", 3000);
            long asked = r.optLong("at", System.currentTimeMillis());
            long until = System.currentTimeMillis() + 90_000;
            watchReconnect(at, o, before, asked, until);
        }, e -> {
            reconnecting = false;
            tell(RELAY, false);
            say(words(e), 4000);
        });
    }

    private void watchReconnect(Link at, Opener o, long before, long asked, long until) {
        main.postDelayed(() -> {
            if (link != at) {
                reconnecting = false;
                return;
            }
            run(() -> o.openRelay(new Hedy(at).get("/v2/status").optJSONObject("relay")), r -> {
                if (r != null) relay = r;
                tell(RELAY, false);
                if (r != null && r.lastSeen > before && r.lastSeen >= asked - 2000) {
                    reconnecting = false;
                    tell(RELAY, false);
                    say(Boolean.FALSE.equals(r.listening)
                            ? "The relay phone answered, but it still isn't reading Messages. Open Relay on it."
                            : "The relay phone is connected.", 4000);
                    return;
                }
                if (System.currentTimeMillis() >= until) {
                    reconnecting = false;
                    tell(RELAY, false);
                    say("No answer yet. If the relay phone is off, offline or out of power, nothing reaches it until it's back.", 7000);
                    return;
                }
                watchReconnect(at, o, before, asked, until);
            }, e -> {
                reconnecting = false;
                tell(RELAY, false);
                say(words(e), 4000);
            });
        }, 5_000);
    }

    // ------------------------------------------------------------------ devices

    void devices(Done<List<Model.Device>> then, Failed failed) {
        Link at = link;
        Opener o = open;
        if (at == null) return;
        run(() -> {
            JSONObject r = new Hedy(at).get("/v2/devices");
            JSONArray items = r.optJSONArray("items");
            List<Model.Device> out = new ArrayList<>();
            for (int i = 0; items != null && i < items.length(); i++) {
                JSONObject d = items.optJSONObject(i);
                if (d != null) out.add(o.openDevice(d));
            }
            return out;
        }, then, failed);
    }

    void removeDevice(String id, Runnable then) {
        Link at = link;
        run(() -> new Hedy(at).post("/v2/devices/remove", new JSONObject().put("id", id)), r -> {
            say("Removed.", 2500);
            if (then != null) then.run();
        }, e -> {
            say(words(e), 4000);
            if (then != null) then.run();
        });
    }

    /** What this phone is called in the inbox's list of devices. */
    static String deviceLabel() {
        String m = (Build.MANUFACTURER == null ? "" : Build.MANUFACTURER) + " " + (Build.MODEL == null ? "" : Build.MODEL);
        m = m.trim();
        if (Build.MODEL != null && Build.MANUFACTURER != null && Build.MODEL.toLowerCase(java.util.Locale.ROOT).startsWith(Build.MANUFACTURER.toLowerCase(java.util.Locale.ROOT))) m = Build.MODEL;
        return m.length() > 60 ? m.substring(0, 60) : m;
    }

    /**
     * This phone's name for itself, sealed with the inbox key for its own id: the device list shows only names that
     * open that way, never the server's words. Sent once per inbox and name.
     */
    void nameThisDevice() {
        Link at = link;
        if (at == null) return;
        String name = Opener.plainName(deviceLabel());
        if (name.isEmpty()) return;
        io.execute(() -> {
            try {
                String id = Opener.deviceId(at.token);
                String mark = at.tid + ":" + id + ":" + name;
                if (mark.equals(prefs.named())) return;
                String sn = at.seal(new JSONObject().put("name", name), "tr2:dev:" + id);
                new Hedy(at).post("/v2/devices/name", new JSONObject().put("sn", sn));
                prefs.setNamed(mark);
            } catch (IOException | JSONException | RuntimeException ignored) {
                // named the next time
            }
        });
    }

    /** Leaves the inbox: it removes this phone (and its way back in after a reinstall), then this phone forgets it. */
    void leave() {
        Link at = link;
        Runnable forget = () -> {
            prefs.setKey("");
            unlinkedHere("");
        };
        if (at == null) {
            forget.run();
            return;
        }
        io.execute(() -> {
            try {
                new Hedy(at).post("/v2/leave", new JSONObject());
            } catch (IOException | RuntimeException ignored) {
                // removed already, or offline: unlinked here either way
            }
            main.post(forget);
        });
    }

    // ------------------------------------------------------------------ fetched ahead, kept on disk

    /** The newest conversations' first pages, fetched before they're tapped. */
    private void prefetch() {
        Link at = link;
        Opener o = open;
        int n = 0;
        for (Model.Conv c : list(null)) {
            if (n >= PREFETCH) break;
            n++;
            if (msgs.containsKey(c.conv)) continue;
            String conv = c.conv;
            List<String> aliases = c.aliases;
            run(() -> fetchPage(at, o, conv, null, aliases), p -> {
                if (msgs.containsKey(conv)) return;
                msgs.put(conv, Model.tidy(p.items));
                cursors.put(conv, p.cursor);
                if (p.cursor == null) complete.add(conv);
                JSONObject raw = p.raw;
                disk.execute(() -> writeDisk(threadFile(conv), at, raw));
                if (conv.equals(current)) tell(THREAD, true);
            }, null);
        }
    }

    private File dir() {
        File d = new File(app.getNoBackupFilesDir(), "inbox-view");
        if (!d.isDirectory()) //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        return d;
    }

    private File inboxFile() {
        return new File(dir(), "inbox.json");
    }

    private File threadFile(String conv) {
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(conv.getBytes(StandardCharsets.UTF_8));
            return new File(dir(), "t-" + Crypto.hex(h).substring(0, 20) + ".json");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** What the inbox sent, still sealed as it came, for this inbox. Only this phone's app can read the folder. */
    private void writeDisk(File f, Link at, JSONObject raw) {
        if (at == null || raw == null) return;
        try {
            JSONObject o = new JSONObject().put("tid", at.tid).put("at", System.currentTimeMillis()).put("data", raw);
            File tmp = new File(f.getPath() + ".tmp");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                out.write(o.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(f)) //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            File[] all = dir().listFiles((d, name) -> name.startsWith("t-"));
            if (all != null && all.length > CACHE_THREADS) {
                java.util.Arrays.sort(all, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
                for (int i = 0; i < all.length - CACHE_THREADS; i++) //noinspection ResultOfMethodCallIgnored
                    all[i].delete();
            }
        } catch (IOException | JSONException | RuntimeException ignored) {
            // kept the next time
        }
    }

    private JSONObject readDisk(File f, Link at) {
        if (at == null || !f.isFile()) return null;
        try {
            byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
            JSONObject o = new JSONObject(new String(b, StandardCharsets.UTF_8));
            if (!at.tid.equals(o.optString("tid"))) return null;
            return o.optJSONObject("data");
        } catch (IOException | JSONException | RuntimeException e) {
            return null;
        }
    }

    private void wipeDisk() {
        File[] all = dir().listFiles();
        if (all != null) for (File f : all) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    /** Tests: wait for what's on the disk thread. */
    void flushDiskForTests() throws Exception {
        disk.submit(() -> { }).get();
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return s;
        }
    }
}
