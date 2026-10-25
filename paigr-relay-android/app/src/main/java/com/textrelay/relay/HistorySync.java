package com.textrelay.relay;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Brings the whole message store into the inbox when asked: every conversation, the most recent first, oldest message
 * first within each, in batches the inbox confirms before the next one goes. Text only unless old pictures, videos and
 * voice messages were asked for too; new ones always come through either way. Survives restarts and lost connections,
 * and picks up where it stopped. The inbox keeps one copy of each message, so running it again only fills gaps.
 */
final class HistorySync {
    static final int BATCH = 50;
    private static final String TAG = "HistorySync";

    private final Relay relay;
    private final Store store;
    private final File file;
    private JSONObject state;
    private Thread worker;
    private volatile boolean running;

    HistorySync(Context context, Relay relay, Store store) {
        this.relay = relay;
        this.store = store;
        this.file = new File(context.getApplicationContext().getFilesDir(), "history.json");
        state = load();
    }

    /** What the inbox devices and this phone's screen show: state, messages done and about how many there are. */
    synchronized JSONObject status() {
        return Json.obj("state", state.optString("state", "idle"), "done", state.optLong("done"), "total", state.optLong("total"),
                "at", state.optLong("at"), "media", state.optBoolean("media"), "note", state.optString("note", ""));
    }

    synchronized boolean media() {
        return state.optBoolean("media");
    }

    synchronized void setMedia(boolean media) {
        put("media", media);
        save();
    }

    synchronized boolean isRunning() {
        return "running".equals(state.optString("state")) && worker != null && worker.isAlive();
    }

    /** Starts over from the newest conversation. */
    synchronized void start() {
        List<Store.Thread> threads = store.threads();
        JSONArray ids = new JSONArray();
        long total = 0;
        for (Store.Thread t : threads) {
            ids.put(t.id);
            total += Math.max(0, t.count);
        }
        boolean media = state.optBoolean("media");
        state = Json.obj("state", "running", "media", media, "threads", ids, "index", 0, "cursorTs", 0L, "cursorKeys", new JSONArray(),
                "done", 0L, "total", total, "startedAt", System.currentTimeMillis(), "at", 0L, "note", "");
        save();
        launch();
    }

    /** After a restart, or once message access is allowed again: carries on if a sync was under way. */
    synchronized void resume() {
        String s = state.optString("state");
        if (!"running".equals(s) && !"paused".equals(s)) return;
        if (!relay.storeSync.allowed() || !relay.prefs.configured()) return;
        put("state", "running");
        put("note", "");
        save();
        launch();
    }

    synchronized void stop() {
        running = false;
        if (worker != null) worker.interrupt();
        worker = null;
    }

    private void launch() {
        if (worker != null && worker.isAlive()) return;
        running = true;
        worker = new Thread(this::work, "relay-history");
        worker.setDaemon(true);
        worker.start();
    }

    private void work() {
        long backoff = 2_000;
        while (running) {
            try {
                if (!step()) return;
                backoff = 2_000;
            } catch (Hedy.ApiException e) {
                if (e.keyRejected()) {
                    pause("The inbox didn't accept this phone. Link it again, then sync.");
                    return;
                }
                note("Waiting for the inbox (" + e.reason + ")");
                if (!sleep(backoff)) return;
                backoff = Math.min(backoff * 2, 120_000);
            } catch (IOException | Relay.RetryLater e) {
                note("Waiting for a connection");
                if (!sleep(backoff)) return;
                backoff = Math.min(backoff * 2, 120_000);
            } catch (RuntimeException e) {
                Log.w(TAG, "history sync failed", e);
                note("Problem: " + e.getClass().getSimpleName());
                if (!sleep(backoff)) return;
                backoff = Math.min(backoff * 2, 120_000);
            }
        }
    }

    /** One batch. False when there is nothing more to do (finished or paused). */
    private boolean step() throws IOException {
        if (!relay.storeSync.allowed()) {
            pause("Message access is off. Allow it in Paigr Relay to carry on.");
            return false;
        }
        if (!relay.prefs.configured()) {
            pause("This phone isn't connected to an inbox.");
            return false;
        }
        JSONArray ids;
        int index;
        long cursorTs;
        List<String> cursorKeys = new ArrayList<>();
        boolean media;
        synchronized (this) {
            ids = state.optJSONArray("threads");
            index = state.optInt("index");
            cursorTs = state.optLong("cursorTs");
            JSONArray k = state.optJSONArray("cursorKeys");
            for (int i = 0; k != null && i < k.length(); i++) cursorKeys.add(k.optString(i));
            media = state.optBoolean("media");
        }
        if (ids == null || index >= ids.length()) {
            synchronized (this) {
                put("state", "done");
                put("at", System.currentTimeMillis());
                put("note", "");
                save();
            }
            return false;
        }
        Store.Thread t = store.thread(ids.optLong(index));
        if (t == null) {
            nextThread(index);
            return true;
        }
        String conv = relay.conversations.forThread(t, store, relay.locator());
        if (conv == null) throw new IOException("the inbox couldn't be asked which conversation this is");
        Store.Page p = store.inThread(t.id, cursorTs, BATCH);
        List<Store.Msg> msgs = new ArrayList<>();
        for (Store.Msg m : p.msgs) {
            if (m.ts < cursorTs || (m.ts == cursorTs && cursorKeys.contains(m.key()))) continue;
            msgs.add(m);
        }
        if (msgs.isEmpty()) {
            if (!p.more) nextThread(index);
            else synchronized (this) {
                put("cursorTs", Math.max(cursorTs + 1, p.upTo));   // a page of unreadable rows: step past them
                put("cursorKeys", new JSONArray());
                save();
            }
            return true;
        }
        List<JSONObject> items = new ArrayList<>();
        for (Store.Msg m : msgs) items.addAll(relay.storeItems(m, conv, true, media));
        // The thread may have just joined another conversation (a notification showed whose it is): send it there.
        String latest = relay.prefs.threadConv(t.id);
        if (latest != null && !latest.equals(conv)) {
            for (JSONObject it : items) {
                try {
                    it.put("conv", latest);
                } catch (org.json.JSONException ignored) {
                    // literal key
                }
            }
        }
        List<JSONObject> uploads = relay.deliverNow(items);
        for (JSONObject job : uploads) relay.uploadNow(job);
        long cutoff = System.currentTimeMillis() - SentLog.KEEP_MS;
        for (Store.Msg m : msgs) if (m.ts >= cutoff) relay.storeSync.sent.add(m.key(), m.ts);
        relay.storeSync.sent.save();

        long lastTs = msgs.get(msgs.size() - 1).ts;
        JSONArray keysAtLast = new JSONArray();
        if (lastTs == cursorTs) for (String k : cursorKeys) keysAtLast.put(k);
        for (Store.Msg m : msgs) if (m.ts == lastTs) keysAtLast.put(m.key());
        synchronized (this) {
            put("done", state.optLong("done") + msgs.size());
            put("cursorTs", lastTs);
            put("cursorKeys", keysAtLast);
            put("at", System.currentTimeMillis());
            put("note", "");
            if (!p.more) {
                // that was the end of this conversation
                put("index", index + 1);
                put("cursorTs", 0L);
                put("cursorKeys", new JSONArray());
            }
            save();
        }
        return true;
    }

    private synchronized void nextThread(int index) {
        put("index", index + 1);
        put("cursorTs", 0L);
        put("cursorKeys", new JSONArray());
        save();
    }

    private synchronized void pause(String why) {
        put("state", "paused");
        put("note", why);
        save();
    }

    private synchronized void note(String why) {
        put("note", why);
        save();
    }

    private boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return running;
        } catch (InterruptedException e) {
            return false;
        }
    }

    private void put(String key, Object value) {
        try {
            state.put(key, value);
        } catch (JSONException ignored) {
            // literal keys
        }
    }

    private JSONObject load() {
        try {
            if (file.exists()) return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        } catch (IOException | JSONException | RuntimeException ignored) {
            // a damaged file starts over
        }
        return Json.obj("state", "idle", "media", false);
    }

    private void save() {
        try {
            File tmp = new File(file.getPath() + ".tmp");
            Files.write(tmp.toPath(), state.toString().getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(file)) Files.write(file.toPath(), state.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException ignored) {
            // the next save tries again
        }
    }
}
