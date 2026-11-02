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

        /** The open conversation was folded into `into` (a number this phone started a conversation with was already one): show that. */
        void conversationMoved(String into);
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
    /** Pages fetched ahead of a tap, on their own thread: never in front of a send or an opened conversation. */
    private final ExecutorService ahead = Executors.newSingleThreadExecutor(daemon("texts-ahead"));
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
    /** Conversations started here with a number, by conversation: the number (the relay phone keys them from it) and the name given here. */
    private final Map<String, String[]> started = new LinkedHashMap<>();
    /** Conversations started here that the inbox doesn't list yet: nothing has been sent in them so far. */
    private final Map<String, Model.Conv> unsent = new LinkedHashMap<>();
    private boolean syncing;
    /** The doorbell rang while a sync was running: another follows at once, so nothing waits for the next tick. */
    private boolean ringPending;
    private long lastSyncAt;
    private boolean visible;
    private Doorbell doorbell;
    private long lastRing;
    private boolean reconnecting;
    /** Conversations the inbox hasn't been told were read (the call failed): told on the next sync that gets through. */
    private final Set<String> readsOwed = new HashSet<>();
    /** The open conversation whose page couldn't be fetched: fetched again when the inbox answers. */
    private String pageNeeded;
    /** Rows written here and not yet handed to the inbox, kept on this phone across a restart. */
    final Outbox outbox;

    private Inbox(Context app) {
        this.app = app;
        this.prefs = new Prefs(app);
        this.media = new Media(this);
        this.outbox = new Outbox(this);
        started.putAll(prefs.startedHere());
        retried.addAll(prefs.retried());
        readsOwed.addAll(prefs.readsOwed());
    }

    /** True while `conv` is being read: open as a thread, or its details (photos, files, links) over the thread. */
    boolean reading(String conv) {
        return conv != null && conv.equals(current) && ("thread".equals(view) || "details".equals(view));
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
        runOn(io, work, then, failed);
    }

    private <T> void runOn(ExecutorService on, Work<T> work, Done<T> then, Failed failed) {
        int at = gen;
        on.execute(() -> {
            T result;
            try {
                result = work.run();
            } catch (Exception | OutOfMemoryError thrown) {
                Exception e = thrown instanceof Exception ? (Exception) thrown : new IOException("out of memory");
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
            if (a.code > 0) return "The inbox refused that. Try again; if it keeps happening, check for an update.";
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
        unsent.clear();
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
        outbox.restore();
        if (!loadedInbox && convs.isEmpty()) fromDiskThenSync();
        else startSync();
    }

    void diskExecute(Runnable r) {
        disk.execute(r);
    }

    /** A row kept on this phone from before a restart, back in its conversation as one that waits to go again. */
    void restoreRow(JSONObject r) {
        String conv = r.optString("conv");
        if (conv.isEmpty()) return;
        List<Model.Msg> list = msgs.get(conv);
        if (list != null) for (Model.Msg m : list) if (m.k.equals(r.optString("cid")) || (!r.optString("seal").isEmpty() && r.optString("seal").equals(m.seal))) return;
        Model.Msg m = new Model.Msg();
        m.k = r.optString("cid");
        m.cid = m.k;
        m.conv = conv;
        m.dir = "out";
        m.status = "failed";
        m.code = "offline";
        m.text = r.optString("text");
        m.ts = r.optLong("ts", System.currentTimeMillis());
        m.temp = true;
        m.tries = r.optInt("tries", 1);
        m.seal = r.optString("seal");
        if (!r.optString("uri").isEmpty()) {
            m.media = new Model.Media();
            m.media.mime = r.optString("mime", "application/octet-stream");
            m.media.bytes = r.optLong("bytes", 0);
            m.media.localUri = r.optString("uri");
        }
        if (list == null) msgs.put(conv, list = new ArrayList<>());
        list.add(m);
        list.sort(Model.BY_TIME);
        if (conv.equals(current)) tell(THREAD, false);
        if (visible && link != null) sync();   // it goes again as soon as the inbox answers
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
            // With the socket up the server rings for news; a check this often is only insurance against a missed ring.
            if (!up || System.currentTimeMillis() - lastSyncAt > SYNC_CONNECTED_MS) sync();
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
        // A conversation started here stays listed until the inbox has it (its first text puts it there).
        unsent.keySet().removeIf(convs::containsKey);
        for (Map.Entry<String, Model.Conv> e : unsent.entrySet()) convs.put(e.getKey(), e.getValue());
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
        for (Model.Conv c : convs.values()) if (!c.conv.equals(current) && !c.filtered) n += c.unread;
        return n;
    }

    /**
     * The owner moves a conversation under Filtered or back to the inbox: shown so at once, and the inbox told, so
     * every device files it the same way and the relay phone's reading no longer moves it. Says whether the inbox heard.
     */
    void setFiltered(String conv, boolean filtered, java.util.function.Consumer<Boolean> then) {
        Model.Conv c = convs.get(conv);
        Link at = link;
        if (c == null || at == null) {
            if (then != null) then.accept(false);
            return;
        }
        boolean was = c.filtered;
        String wasBy = c.filteredBy;
        c.filtered = filtered;
        c.filteredBy = "user";
        if (filtered) SyncService.notifier(app).clear(conv);
        tell(LIST | THREAD, false);
        io.execute(() -> {
            boolean told;
            try {
                new Hedy(at).post("/v2/filter", new JSONObject().put("conv", conv).put("filtered", filtered));
                told = true;
            } catch (IOException | JSONException e) {
                told = false;
            }
            boolean ok = told;
            main.post(() -> {
                if (link != at) return;
                if (!ok) {
                    Model.Conv now = convs.get(conv);
                    if (now != null && now.filtered == filtered && "user".equals(now.filteredBy)) {   // not heard: as it was
                        now.filtered = was;
                        now.filteredBy = wasBy;
                        tell(LIST | THREAD, false);
                    }
                }
                if (then != null) then.accept(ok);
            });
        });
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

    /**
     * A page from the inbox in place of what's held: rows written on this phone that the inbox doesn't have yet (on
     * their way, or waiting to be sent again) stay, and a message that arrived while the page was on its way too.
     */
    private List<Model.Msg> withOwn(String conv, List<Model.Msg> page, Set<String> hadBefore) {
        List<Model.Msg> old = msgs.get(conv);
        if (old == null || old.isEmpty()) return Model.tidy(page);
        Set<String> keys = new HashSet<>(), seals = new HashSet<>(), cids = new HashSet<>();
        for (Model.Msg m : page) {
            keys.add(m.k);
            if (!m.seal.isEmpty()) seals.add(m.seal);
            if (!m.cid.isEmpty()) cids.add(m.cid);
        }
        List<Model.Msg> all = new ArrayList<>(page);
        for (Model.Msg m : old) {
            if (keys.contains(m.k)) continue;
            boolean own = m.temp && !cids.contains(m.k) && (m.seal.isEmpty() || !seals.contains(m.seal));
            boolean arrived = !m.temp && hadBefore != null && !hadBefore.contains(m.k);
            if (own || arrived) all.add(m);
        }
        return Model.tidy(all);
    }

    private Set<String> keysHeld(String conv) {
        Set<String> out = new HashSet<>();
        for (Model.Msg m : thread(conv)) out.add(m.k);
        return out;
    }

    /** Opens a conversation: what's held here shows at once, then the inbox's newest page replaces it. */
    void openThread(String conv) {
        current = conv;
        view = "thread";
        finding = false;
        SyncService.setOpenConversation(app, conv);
        Notifier notifier = SyncService.notifier(app);
        for (String a : knownAliases(conv)) notifier.clear(a);   // a notification filed before a merge too
        notifier.clear(conv);
        // Opened is read: the count goes now, here and on the inbox, however the page's fetch goes.
        Model.Conv c = convs.get(conv);
        if (c != null && c.unread > 0) {
            c.unread = 0;
            tell(LIST, false);
            markRead(conv);
        }
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
                    msgs.put(conv, withOwn(conv, p.items, null));
                    if (conv.equals(current)) tell(THREAD, true);
                });
            });
        } else {
            tell(THREAD, true);
        }
        List<String> known = knownAliases(conv);
        Set<String> had = keysHeld(conv);
        run(() -> fetchPage(at, o, conv, null, known), p -> {
            boolean first = !msgs.containsKey(conv);
            msgs.put(conv, withOwn(conv, p.items, had));
            cursors.put(conv, p.cursor);
            if (p.cursor == null) complete.add(conv);
            else complete.remove(conv);
            if (p.conversation != null) convs.put(conv, p.conversation);
            if (conv.equals(pageNeeded)) pageNeeded = null;
            JSONObject raw = p.raw;
            disk.execute(() -> writeDisk(threadFile(conv), at, raw));
            // Another conversation was opened meanwhile: never drawn under that one's name.
            if (!conv.equals(current)) return;
            tell(THREAD | LIST, first);
            Model.Conv now = convs.get(conv);
            if (now != null && now.unread > 0) {   // a count this phone didn't know of
                now.unread = 0;
                tell(LIST, false);
                markRead(conv);
            }
        }, e -> {
            if (!conv.equals(current)) return;
            // Nothing held and nothing fetched: the page comes when the inbox answers again.
            if (!msgs.containsKey(conv)) pageNeeded = conv;
            say(words(e), 3000);
            tell(THREAD, false);
        });
    }

    /** True while the open conversation has nothing to show because the inbox couldn't be reached. */
    boolean pageWaiting(String conv) {
        return conv != null && conv.equals(pageNeeded) && !msgs.containsKey(conv);
    }

    void closeThread() {
        String was = current;
        current = null;
        view = "inbox";
        finding = false;
        SyncService.setOpenConversation(app, null);
        // A conversation started here and left without a word: nothing to list.
        if (was != null && unsent.containsKey(was) && thread(was).isEmpty()) {
            unsent.remove(was);
            convs.remove(was);
            msgs.remove(was);
            complete.remove(was);
            tell(LIST, false);
        }
    }

    // ------------------------------------------------------------------ starting a conversation

    /**
     * A conversation with a number typed here. One this phone already lists under that number is opened instead. Any
     * other is keyed from the number the way the relay phone keys it (so the two agree on it without a word between
     * them), named for the number until the relay phone names it, and listed here until the inbox has it, which its
     * first text sees to; the relay phone sends that as SMS, and folds the conversation into one it already has with
     * that number (PROTOCOL.md, New conversations). Null when what was typed isn't a number.
     */
    String startConversation(String typed, String name) {
        String number = Numbers.canonical(typed);
        if (number == null || link == null) return null;
        String key = Numbers.key(number);
        for (Model.Conv c : convs.values()) if (!c.group && Numbers.isNumber(c.name) && Numbers.key(c.name).equals(key)) return c.conv;
        for (Map.Entry<String, String[]> e : started.entrySet()) {
            if (!Numbers.key(e.getValue()[0]).equals(key)) continue;
            if (convs.containsKey(e.getKey())) return e.getKey();
            // Started here before, and folded by the relay phone into the conversation it had with that number.
            for (Model.Conv c : convs.values()) if (c.aliases != null && c.aliases.contains(e.getKey())) return c.conv;
            // Gone from the list (left without a word): the same conversation again, keyed from the number as it was
            // written then, so one person doesn't become two conversations by how their number was typed.
            number = e.getValue()[0];
            if (name == null || name.trim().isEmpty()) name = e.getValue()[1];
            break;
        }
        String conv = Crypto.hex(link.mac("conv:to:" + number)).substring(0, 32);
        String called = name == null || name.trim().isEmpty() ? Numbers.pretty(number) : name.trim();
        started.put(conv, new String[] { number, called });
        prefs.rememberStarted(conv, number, called);
        if (!convs.containsKey(conv)) {
            Model.Conv c = new Model.Conv();
            c.conv = conv;
            c.name = called;
            c.lastTs = System.currentTimeMillis();
            c.lastDir = "out";
            c.aliases = java.util.Collections.singletonList(conv);
            c.aliasKey = conv;
            convs.put(conv, c);
            unsent.put(conv, c);
            if (!msgs.containsKey(conv)) msgs.put(conv, new ArrayList<>());
            complete.add(conv);
            tell(LIST, false);
        }
        return conv;
    }

    /** The number a conversation was started with here, or null for one the relay phone brought. */
    String startedWith(String conv) {
        String[] s = conv != null ? started.get(conv) : null;
        return s != null ? s[0] : null;
    }

    /** Whether the relay phone says it can send SMS: a conversation needs no open reply handle then (one-to-one). */
    boolean smsFallbackOn() {
        return relay != null && Boolean.TRUE.equals(relay.smsFallback);
    }

    /** The most a video or file sent from here can be: an MMS from the relay phone carries this much; 0 while unknown. */
    long mmsMax() {
        return relay != null ? relay.mmsMax : 0;
    }

    /** True while a picture from here goes through Messages on the relay phone (as it is) rather than as a shrunken MMS. */
    boolean pictureGoesWhole() {
        return relay != null && Boolean.TRUE.equals(relay.imageReply);
    }

    /**
     * Why a file from this phone can't be sent, in a sentence, or null when it can. A picture always can (it's shrunk
     * to what the way out takes); a video or file goes as MMS from the relay phone, so one bigger than that takes is
     * refused here, before anything is uploaded.
     */
    String cantSend(String mime, long bytes) {
        boolean picture = mime != null && mime.startsWith("image/") && !"image/gif".equals(mime);
        if (picture) return null;
        long max = mmsMax();
        if (max > 0 && bytes > max) {
            return (mime != null && mime.startsWith("video/") ? "Videos" : "image/gif".equals(mime) ? "GIFs" : "Files")
                    + " go out as MMS from the relay phone, which takes up to " + Format.bytes(max) + " there. This one is " + Format.bytes(bytes) + ".";
        }
        if (bytes > ATTACH_MAX) return "That file is too big to send (" + Format.bytes(ATTACH_MAX + 4096) + " at most).";
        return null;
    }

    /**
     * Every conversation read: the counts go to zero here at once, the inbox is told one conversation at a time (it
     * has no word for all of them), and the notifications go. A conversation whose count the inbox still has comes
     * back unread on the next sync only if the call for it failed, and the next open marks it again.
     */
    void markAllRead() {
        List<String> unread = new ArrayList<>();
        for (Model.Conv c : convs.values()) {
            if (c.unread > 0) {
                c.unread = 0;
                unread.add(c.conv);
            }
        }
        SyncService.notifier(app).clearAll();
        if (unread.isEmpty()) return;
        tell(LIST, false);
        for (String conv : unread) markRead(conv);
    }

    /** One conversation read, from the list. */
    void markRead(String conv, boolean fromList) {
        Model.Conv c = convs.get(conv);
        if (c == null || c.unread == 0) return;
        c.unread = 0;
        SyncService.notifier(app).clear(conv);
        tell(LIST, false);
        markRead(conv);
    }

    private void markRead(String conv) {
        Link at = link;
        if (at == null) return;
        io.execute(() -> {
            boolean told;
            try {
                new Hedy(at).post("/v2/read", new JSONObject().put("conv", conv));
                told = true;
            } catch (IOException | JSONException e) {
                told = false;
            }
            boolean ok = told;
            main.post(() -> {
                if (link != at) return;
                if (ok) readsOwed.remove(conv);
                else readsOwed.add(conv);   // told on the next sync that gets through
                prefs.rememberReadsOwed(readsOwed);
            });
        });
    }

    /** Reads the inbox wasn't told of (the calls failed), told now that it answers. */
    private void settleReads() {
        if (readsOwed.isEmpty()) return;
        for (String conv : new ArrayList<>(readsOwed)) markRead(conv);
    }

    List<Model.Msg> thread(String conv) {
        List<Model.Msg> l = msgs.get(conv);
        return l != null ? l : java.util.Collections.emptyList();
    }

    boolean hasEarlier(String conv) {
        return cursors.get(conv) != null;
    }

    /** The page before the oldest one held. `then` gets how many messages came in front, or -1 when it couldn't be fetched; it always hears. */
    void loadEarlier(Done<Integer> then) {
        String conv = current;
        String cursor = conv != null ? cursors.get(conv) : null;
        if (conv == null || cursor == null) {
            if (then != null) then.done(0);
            return;
        }
        Link at = link;
        Opener o = open;
        List<String> known = knownAliases(conv);
        run(() -> fetchPage(at, o, conv, cursor, known), p -> {
            if (!cursor.equals(cursors.get(conv))) {   // reloaded meanwhile
                if (then != null) then.done(0);
                return;
            }
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
            if (then != null) then.done(tidied.size() - before);
        }, e -> {
            say(words(e), 3000);
            if (then != null) then.done(-1);
        });
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
        Set<String> had = keysHeld(conv);
        run(() -> fetchPage(at, o, conv, null, known), p -> {
            if (!conv.equals(current)) return;
            msgs.put(conv, withOwn(conv, p.items, had));
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
    /**
     * A picture on its way out as MMS is shrunk to this before it's sealed (the relay phone shrinks it again to the
     * carrier's few hundred kilobytes), so a 12 MB photo doesn't take a minute to upload on a slow line for nothing.
     */
    static final int SEND_SIDE = 1600, SEND_QUALITY = 85, SEND_WHOLE_BELOW = 600 * 1024;

    void send(String conv, String text) {
        send(conv, text, null);
    }

    /**
     * Sends a text, an attachment, or both. An attachment is sealed and stored first (with a preview when it's a big
     * picture), then the message names it: every inbox device by its record, the relay phone by the path inside the
     * sealed request, which it fetches and sends on as the text (PROTOCOL.md, Replies).
     */
    void send(String conv, String text, Attachment att) {
        send(conv, text, att, null, 0);
    }

    /**
     * The same, as a message this phone wrote before (`cid`, its row's id) going out again: the inbox knows a message
     * by its device's id, so one whose answer was lost on the way back is the same message, never a second one.
     */
    private void send(String conv, String text, Attachment att, String cid, int tries) {
        String t = text == null ? "" : text.trim();
        if ((t.isEmpty() && att == null) || conv == null || link == null) return;
        long now = System.currentTimeMillis();
        Link at = link;
        Model.Msg temp = new Model.Msg();
        temp.k = cid != null ? cid : "tmp:" + now + ":" + Crypto.hex(Crypto.random(3));
        temp.cid = temp.k;
        temp.conv = conv;
        temp.dir = "out";
        temp.status = "queued";
        temp.text = t;
        temp.ts = now;
        temp.temp = true;
        temp.tries = tries + 1;
        // How the inbox's copy will name itself, so the two are one row here from the moment it arrives, answer or no
        // answer: a file by its time and fingerprint, a plain text by its seal (made here, now, and sent as it is).
        String fp = att != null ? at.fpId(Crypto.hex(Crypto.random(8))) : null;
        String plainE = null;
        if (att != null) temp.seal = "m:out:" + Opener.stemOf(now, fp);
        else {
            try {
                plainE = at.seal(new JSONObject().put("text", t).put("ts", now), Crypto.aadMessage(conv, "out"));
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }
            temp.seal = "e:" + plainE.substring(plainE.length() - 22);
        }
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
        String[] from = started.get(conv);
        String to = from != null ? from[0] : null;
        String called = from != null && !from[1].isEmpty() ? from[1] : to != null ? Numbers.pretty(to) : "";
        outbox.put(temp, att, to);   // kept on this phone until the inbox has it, so a restart doesn't lose it
        String sealedText = plainE;
        run(() -> {
            JSONObject shown = new JSONObject().put("text", t).put("ts", now);
            JSONObject request = new JSONObject().put("text", t).put("n", Crypto.b64u(Crypto.random(16))).put("ts", now);
            JSONObject body = new JSONObject().put("conv", conv).put("cid", temp.k);
            if (to != null) {
                // A conversation started here: the request names the number (inside the seal, so only the relay phone
                // reads it), and the inbox gets the conversation's name in case it doesn't have the conversation yet.
                request.put("to", to);
                body.put("ne", at.seal(new JSONObject().put("name", called).put("group", false), Crypto.aadName(conv)));
            }
            String mime = "";
            if (att != null) {
                mime = att.mime;
                boolean picture = mime.startsWith("image/") && !"image/gif".equals(mime);
                // A picture is read whole whatever its size, then shrunk when the way out would shrink it anyway. A
                // video or file goes as MMS from the relay phone, so one bigger than that takes stops here.
                long mms = mmsMax();
                long cap = picture ? Media.MAX_BYTES : mms > 0 ? Math.min(ATTACH_MAX, mms) : ATTACH_MAX;
                byte[] plain;
                try {
                    plain = media.readLocal(att.uri, cap);
                } catch (IOException e) {
                    throw "too big".equals(e.getMessage()) && !picture && cap < ATTACH_MAX ? new IOException("too big for mms") : e;
                }
                if (picture && (plain.length > ATTACH_MAX || (!pictureGoesWhole() && plain.length > SEND_WHOLE_BELOW))) {
                    byte[] small = Media.shrink(plain, SEND_SIDE, SEND_QUALITY);
                    if (small != null && small.length < plain.length) {
                        plain = small;
                        mime = "image/jpeg";
                    } else if (plain.length > ATTACH_MAX) throw new IOException("too big");
                }
                int[] wh = mime.startsWith("image/") ? Media.size(plain) : new int[] { 0, 0 };
                // Its files are named by a fingerprint this phone made up (as the relay phone names its own).
                String path = new Hedy(at).upload(conv, fp, now, "original", keep(at, conv, now, fp, "original", plain));
                if (path.isEmpty()) throw new IOException("the inbox didn't say where it stored the file");
                JSONObject where = new JSONObject().put("path", path);
                if (mime.startsWith("image/") && !mime.equals("image/gif") && (plain.length > PREVIEW_FROM_BYTES || wh[0] > PREVIEW_FROM_SIDE || wh[1] > PREVIEW_FROM_SIDE)) {
                    byte[] small = Media.shrink(plain, PREVIEW_SIDE, 80);
                    if (small != null && small.length < plain.length) {
                        String preview = new Hedy(at).upload(conv, fp, now, "preview", keep(at, conv, now, fp, "preview", small));
                        if (!preview.isEmpty()) where.put("preview", preview);
                    }
                } else if (mime.startsWith("video/")) {
                    // A frame of the video as its preview, so every device shows a picture of it (as the relay phone does).
                    Media.Poster poster = media.poster(att.uri);
                    if (poster != null) {
                        String preview = new Hedy(at).upload(conv, fp, now, "preview", keep(at, conv, now, fp, "preview", poster.jpeg));
                        if (!preview.isEmpty()) {
                            where.put("preview", preview);
                            wh = new int[] { poster.w, poster.h };
                        }
                    }
                }
                JSONObject about = new JSONObject().put("mime", mime).put("w", wh[0]).put("h", wh[1]).put("bytes", plain.length);
                temp.media.mime = mime;
                shown.put("fp", fp).put("media", about);
                request.put("media", new JSONObject().put("path", path).put("mime", mime).put("bytes", plain.length).put("name", att.name));
                body.put("media", where);
                temp.media.w = wh[0];
                temp.media.h = wh[1];
                temp.media.bytes = plain.length;
                temp.media.path = path;
                temp.media.preview = where.optString("preview", null);
            }
            // How every inbox device shows it: a message this device sent, at this time (a plain text was sealed up front).
            body.put("e", sealedText != null ? sealedText : at.seal(shown, Crypto.aadMessage(conv, "out")))
                    // What the relay phone acts on: for this conversation alone, with a nonce and the time (PROTOCOL.md, Replies).
                    .put("o", at.seal(request, Crypto.aadOut(conv)))
                    .put("mt", at.matchToken("out", t, mime));
            return new Hedy(at).post("/v2/send", body);
        }, r -> {
            String id = r.optString("id", "");
            outbox.remove(temp.cid);
            List<Model.Msg> list = msgs.get(conv);
            if (list != null && !id.isEmpty()) {
                boolean already = false;
                for (Model.Msg m : list) if (m.k.equals(id)) already = true;
                if (already) list.remove(temp);   // a sync already brought it
                else {
                    temp.k = id;
                    temp.temp = false;
                    if (r.optBoolean("again") && r.opt("status") instanceof String) temp.status = r.optString("status");
                }
            }
            if (!id.isEmpty() && "queued".equals(temp.status)) pendingOut.add(id);
            unsent.remove(conv);   // the inbox has the conversation now
            Model.Conv c = convs.get(conv);
            if (c != null) {
                c.lastTs = temp.ts;
                c.lastText = !t.isEmpty() ? t : att != null ? Opener.mediaKind(att.mime) : "";
                c.lastDir = "out";
            }
            boolean group = c != null && c.group;
            if (Boolean.FALSE.equals(r.opt("canReply")) && conv.equals(current) && (group || !smsFallbackOn())) {
                say("Queued. It goes out after their next text reaches the relay phone.", 4000);
            }
            tell(THREAD | LIST, conv.equals(current));
        }, e -> {
            List<Model.Msg> list = msgs.get(conv);
            if (list != null && !list.contains(temp)) {
                outbox.remove(temp.cid);   // the inbox's copy replaced this row meanwhile: it got there after all
                return;
            }
            temp.status = "failed";
            String why = e instanceof IOException && e.getMessage() != null ? e.getMessage() : "";
            temp.code = "too big".equals(why) ? "too_big_here" : "too big for mms".equals(why) ? "too_big_mms"
                    : "out of memory".equals(why) ? "too_big_here"
                    : e instanceof Hedy.ApiException ? "refused" : e instanceof IOException ? "offline" : "local";
            if ("offline".equals(temp.code)) outbox.keep(temp);   // tried again by itself once the inbox answers
            else outbox.remove(temp.cid);
            tell(THREAD, false);
        });
    }

    /** True while a failed row of this phone's own is one it will send again by itself (the inbox couldn't be reached). */
    static boolean waitsForConnection(Model.Msg m) {
        return m != null && m.temp && "failed".equals(m.status) && "offline".equals(m.code) && m.tries < SEND_TRIES;
    }

    /** How many times a text goes out by itself before it waits for a tap. */
    static final int SEND_TRIES = 3;

    /** Rows that couldn't reach the inbox, sent again now that it answers (each the same message, by its id). */
    private void retryWaiting() {
        List<Model.Msg> again = new ArrayList<>();
        for (List<Model.Msg> list : msgs.values()) for (Model.Msg m : list) if (waitsForConnection(m)) again.add(m);
        for (Model.Msg m : again) sendAgain(m);
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

    /**
     * A reply that didn't go out, sent again. A row written here goes as the same message (its id); one the inbox has
     * (the relay phone couldn't send it) goes as a new one, and the old row stops offering to retry.
     */
    void sendAgain(Model.Msg m) {
        if (m == null || !m.out() || m.unchecked || (m.text.isEmpty() && (m.media == null || m.media.localUri == null))) return;
        if (!m.temp && retried.contains(m.k)) return;
        List<Model.Msg> list = msgs.get(m.conv);
        if (list != null && m.temp) list.remove(m);
        if (!m.temp) {
            retried.add(m.k);
            prefs.rememberRetried(m.k);
        }
        Attachment again = m.media != null && m.media.localUri != null
                ? new Attachment(android.net.Uri.parse(m.media.localUri), m.media.mime, "file", m.media.bytes) : null;
        send(m.conv, m.text, again, m.temp ? m.cid : null, m.temp ? m.tries : 0);
    }

    /** Messages the inbox has as failed that were sent again from here: their rows don't offer to retry a second time. */
    final Set<String> retried = new HashSet<>();

    boolean wasRetried(Model.Msg m) {
        return m != null && !m.temp && retried.contains(m.k);
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
                // A row written on this phone, as the inbox has it (by the id this phone gave it, or by its seal): the
                // inbox's copy takes the row's place, with the file this phone still has for it.
                int own = -1;
                for (int j = 0; j < arr.size(); j++) {
                    Model.Msg t = arr.get(j);
                    if (t.temp && ((!m.cid.isEmpty() && m.cid.equals(t.k)) || (!m.seal.isEmpty() && m.seal.equals(t.seal)))) { own = j; break; }
                }
                if (own >= 0) {
                    Model.Msg was = arr.remove(own);
                    if (m.media != null && was.media != null && was.media.localUri != null) m.media.localUri = was.media.localUri;
                    outbox.remove(was.cid);
                    arr.add(m);
                    arr.sort(Model.BY_TIME);
                    if (m.out() && !"queued".equals(m.status)) pendingOut.remove(m.k);
                    return true;
                }
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
                // The same key filed as someone's text and as this phone's (one copy from the thread, one from the
                // feed, by a server that changed its mind about who said it): nobody's words here, either way round.
                if (!arr.get(i).dir.equals(m.dir) && !arr.get(i).temp) m = Opener.asUnchecked(m);
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
        if (added && !m.out() && !m.read && reading(m.conv)) markRead(m.conv);
        return changed;
    }

    /** The inbox's summary of a conversation wins, unread count included. The open conversation reads as read. */
    boolean mergeConversation(Model.Conv c) {
        if (c == null || c.conv.isEmpty()) return false;
        Model.Conv next = c.copy();
        if (reading(c.conv)) next.unread = 0;
        unsent.remove(c.conv);
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
        if (link == null || !visible) return;
        if (syncing) {
            ringPending = true;
            return;
        }
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
            lastSyncAt = System.currentTimeMillis();
            unreached = null;
            if (out.inbox != null) applyInbox(out.inbox);
            Map<String, Integer> counts = new HashMap<>();
            if (out.inbox != null) for (Model.Conv c : out.inbox.items) counts.put(c.conv, c.filtered ? 0 : c.unread);
            for (Round r : out.rounds) for (Model.Conv c : r.convs) counts.put(c.conv, c.filtered ? 0 : c.unread);   // Filtered: nothing to show
            if (!counts.isEmpty()) SyncService.notifier(app).unread(counts);
            boolean touched = false, touchedOpen = false;
            String reopen = null;
            for (Round r : out.rounds) {
                if (r.startOver) {
                    relay = r.relay != null ? r.relay : relay;
                    reloadEverything(null);
                    return;
                }
                for (Model.Msg m : r.items) if (mergeMessage(m)) { touched = true; if (m.conv.equals(current)) touchedOpen = true; }
                for (Model.Conv c : r.convs) if (mergeConversation(c)) { touched = true; if (c.conv.equals(current)) touchedOpen = true; }
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
            tell(RELAY | (touched ? LIST : 0) | (touchedOpen && "thread".equals(view) && !finding ? THREAD : 0), false);
            if (reopen != null) reloadThread(reopen);
            if (!pendingOut.isEmpty() && current != null) refreshPending();
            torDown(false);
            // The inbox answers: what it was owed while it didn't.
            settleReads();
            retryWaiting();
            if (pageWaiting(current)) openThread(current);
            if (ringPending) {
                ringPending = false;
                sync();
            }
        }, e -> {
            syncing = false;
            torDown(e instanceof Tor.Down);
            if (!loadedInbox) {
                unreached = words(e);
                tell(LIST, false);
            }
            if (ringPending) {
                ringPending = false;
                main.postDelayed(this::sync, 2_000);
            }
        });
    }

    /** Why the list couldn't be loaded the first time (the inbox couldn't be reached), or null. */
    String unreached;

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
        // Rows written here that the inbox doesn't have yet stay, under their conversations.
        Map<String, List<Model.Msg>> own = new HashMap<>();
        for (Map.Entry<String, List<Model.Msg>> e : msgs.entrySet()) {
            List<Model.Msg> keep = new ArrayList<>();
            for (Model.Msg m : e.getValue()) if (m.temp) keep.add(m);
            if (!keep.isEmpty()) own.put(e.getKey(), keep);
        }
        convs.clear();
        msgs.clear();
        msgs.putAll(own);
        cursors.clear();
        complete.clear();
        feed = null;
        epoch = null;
        loadedInbox = false;
        loadInbox(() -> {
            if (was != null && was.equals(current) && ("thread".equals(shown) || "details".equals(shown))) {
                if (convs.containsKey(was)) openThread(was);
                else if (listener != null) {
                    // Folded into another conversation (its summary vouches for the old id): that one, in its place.
                    String into = null;
                    for (Model.Conv c : convs.values()) if (c.aliases != null && c.aliases.contains(was)) into = c.conv;
                    if (into != null) listener.conversationMoved(into);
                    else listener.conversationGone();
                }
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
                            ? "The relay phone answered, but it still isn't reading Messages. Open Paigr Relay on it."
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
            runOn(ahead, () -> fetchPage(at, o, conv, null, aliases), p -> {
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
