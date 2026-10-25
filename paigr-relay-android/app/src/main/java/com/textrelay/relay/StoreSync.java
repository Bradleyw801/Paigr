package com.textrelay.relay;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keeps the inbox in step with the phone's own message store: texts sent from this phone, conversations read here,
 * and anything Messages' notifications never showed (the listener was down, the conversation was open on screen, a
 * notification was swiped away). Reads what is new a few seconds after the store changes, and the last two days every
 * 15 minutes in case a change went unnoticed. The inbox keeps one copy of each message, whichever way it came.
 */
final class StoreSync {
    /** Each live check re-reads this much before where the last one ended: an MMS can land in the store minutes late. */
    static final long LIVE_BACK_MS = 10 * 60_000L;
    static final long DAYS_2 = 2 * 86_400_000L;
    static final long DAYS_7 = 7 * 86_400_000L;
    static final long DEEP_EVERY_MS = 15 * 60_000L;
    static final long DEBOUNCE_MS = 3_000L;
    /** A text in a thread not seen before waits this long for its notification to say which conversation it is. */
    static final long SETTLE_MS = 2 * 60_000L;
    private static final String TAG = "StoreSync";

    private final Context context;
    private final Relay relay;
    private final Store store;
    final SentLog sent;
    private final Map<Long, Boolean> readState = new HashMap<>();
    private final Object lock = new Object();
    private HandlerThread thread;
    private Handler handler;
    private ContentObserver observer;
    /** What the next check covers: 0 for the live check, else this many ms back, and whether to send everything in it. */
    private long pendingWindow = -1;
    private boolean pendingResend;
    private long dueAt;

    volatile long lastScan;
    volatile long lastDeep;
    volatile int lastFound;
    volatile String lastError;

    StoreSync(Context context, Relay relay, Store store) {
        this.context = context.getApplicationContext();
        this.relay = relay;
        this.store = store;
        this.sent = new SentLog(new File(this.context.getFilesDir(), "store-sent.json"));
    }

    boolean allowed() {
        return context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED;
    }

    /** Starts watching once message access is allowed; safe to call again (after the permission is granted). */
    synchronized void start() {
        if (thread == null) {
            thread = new HandlerThread("relay-store");
            thread.start();
            handler = new Handler(thread.getLooper());
        }
        if (observer == null && allowed()) {
            observer = new ContentObserver(handler) {
                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    poke();
                }
            };
            try {
                for (Uri u : new Uri[] { Store.ALL, Store.SMS, Store.MMS }) {
                    context.getContentResolver().registerContentObserver(u, true, observer);
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "can't watch the message store", e);
                observer = null;
            }
            request(DAYS_2, false);   // whatever happened while Relay wasn't watching
        }
    }

    synchronized void stop() {
        if (observer != null) {
            try {
                context.getContentResolver().unregisterContentObserver(observer);
            } catch (RuntimeException ignored) {
                // already gone
            }
            observer = null;
        }
        if (thread != null) thread.quitSafely();
        thread = null;
        handler = null;
    }

    /** The store changed: check a moment later, once things settle. */
    void poke() {
        schedule(0, false, DEBOUNCE_MS);
    }

    /** Checks the last `window` ms now; with resend, sends everything in it again (the inbox skips what it has). */
    void request(long window, boolean resend) {
        schedule(window, resend, 0);
    }

    /** Called often by the poll loop: a full check of the last two days every 15 minutes. */
    void deepIfDue() {
        if (System.currentTimeMillis() - lastDeep >= DEEP_EVERY_MS) request(DAYS_2, false);
    }

    private void schedule(long window, boolean resend, long delay) {
        Handler h = handler;
        if (h == null || !allowed()) return;
        synchronized (lock) {
            long due = System.currentTimeMillis() + delay;
            boolean queued = pendingWindow >= 0;
            pendingWindow = Math.max(pendingWindow, window);
            pendingResend |= resend;
            if (queued && dueAt <= due) return;   // a check is coming sooner anyway, and will cover this
            dueAt = due;
            h.removeCallbacks(run);
            h.postDelayed(run, delay);
        }
    }

    private final Runnable run = () -> {
        long window;
        boolean resend;
        synchronized (lock) {
            window = pendingWindow;
            resend = pendingResend;
            pendingWindow = -1;
            pendingResend = false;
        }
        if (window < 0) return;
        try {
            scan(window, resend);
            lastError = null;
        } catch (RuntimeException e) {
            Log.w(TAG, "store check failed", e);
            lastError = "Reading messages: " + e.getMessage();
        }
    };

    /** Tests only: runs a check on the calling thread. */
    int scanNow(long window, boolean resend) {
        return scan(window, resend);
    }

    /**
     * Reads the store and sends what the inbox may not have. New messages are queued (and only then remembered as
     * sent); with resend, messages sent before go straight to the inbox in batches, which keeps the queue small. A
     * problem partway through still sends what was read before it. Returns how many items were queued or re-sent.
     */
    private int scan(long window, boolean resend) {
        if (!allowed() || !relay.prefs.configured()) return 0;
        Prefs prefs = relay.prefs;
        long now = System.currentTimeMillis();
        long mark = prefs.storeMark();
        long from = window > 0 ? now - window : mark > 0 ? Math.max(mark - LIVE_BACK_MS, now - DAYS_2) : now - DAYS_2;
        Map<Long, Store.Thread> threads = new HashMap<>();
        List<JSONObject> fresh = new ArrayList<>();
        List<Store.Msg> freshMsgs = new ArrayList<>();
        List<JSONObject> again = new ArrayList<>();
        Set<String> handled = new HashSet<>();
        boolean settling = false;
        long reached = mark;
        RuntimeException problem = null;
        try {
            for (Store.Thread t : store.threads()) threads.put(t.id, t);
            Conversations.Locator locator = relay.locator();
            long cursor = from;
            for (int page = 0; page < 40; page++) {
                Store.Page p = store.since(cursor, 300);
                for (Store.Msg m : p.msgs) {
                    if (!handled.add(m.key())) continue;
                    boolean before = sent.has(m.key());
                    if (before && !resend) continue;
                    Store.Thread t = threads.get(m.threadId);
                    if (t == null) t = store.thread(m.threadId);
                    if (t == null) continue;
                    threads.put(t.id, t);
                    String conv = prefs.threadConv(t.id);
                    if (conv == null && !m.out && now - m.ts < SETTLE_MS && !relay.hiddenNear(m.ts)) {
                        settling = true;   // its notification may be about to say whose thread this is
                        continue;
                    }
                    if (conv == null) conv = relay.conversations.forThread(t, store, locator);
                    if (conv == null) {
                        settling = true;   // the inbox couldn't be asked just now
                        continue;
                    }
                    List<JSONObject> its = relay.storeItems(m, conv, false, true);
                    if (before) {
                        again.addAll(its);
                    } else {
                        fresh.addAll(its);
                        freshMsgs.add(m);
                    }
                    reached = Math.max(reached, Math.min(m.ts, now));   // a row dated in the future never moves the mark past now
                }
                if (!p.more) break;
                cursor = p.upTo;
            }
        } catch (RuntimeException e) {
            problem = e;
        }

        // Conversations read on this phone are read in the inbox too.
        JSONObject reads = new JSONObject();
        for (Store.Thread t : threads.values()) {
            Boolean was = readState.put(t.id, t.read);
            if (!t.read) continue;
            boolean news = was != null ? !was : t.date >= from;
            String conv = prefs.threadConv(t.id);
            if (news && conv != null) {
                try {
                    reads.put(conv, Math.max(reads.optLong(conv, 0), t.date));
                } catch (org.json.JSONException ignored) {
                    // plain key
                }
            }
        }

        relay.queueFromStore(fresh, reads);
        for (Store.Msg m : freshMsgs) sent.add(m.key(), m.ts);
        sent.save();
        if (window == 0 && problem == null) {
            if (reached > mark) prefs.setStoreMark(reached);
            else if (mark == 0) prefs.setStoreMark(now - LIVE_BACK_MS);
        } else if (window == 0 && reached > mark) {
            prefs.setStoreMark(reached);
        }
        int resent = again.isEmpty() ? 0 : relay.resendNow(again);
        lastScan = now;
        lastFound = fresh.size() + resent;
        if (window >= DAYS_2 && problem == null) lastDeep = now;
        if (settling) schedule(0, false, SETTLE_MS + 1_000);
        if (problem != null) throw problem;
        return fresh.size() + resent;
    }
}
