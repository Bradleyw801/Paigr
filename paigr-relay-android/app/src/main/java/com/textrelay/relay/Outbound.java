package com.textrelay.relay;

import android.content.Context;

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
 * Texts waiting to go up to the inbox, kept on disk so nothing is lost when the app restarts or the network is away
 * for a day. Items leave only after the server has acknowledged them, or refused them for good; nothing is dropped to
 * make room. Re-sending what the inbox already has goes straight to it instead, so this stays small.
 */
final class Outbound {
    private final File file;
    private final List<JSONObject> items = new ArrayList<>();

    Outbound(Context context) {
        this(new File(context.getApplicationContext().getFilesDir(), "outbound.json"));
    }

    Outbound(File file) {
        this.file = file;
        load();
    }

    synchronized int size() {
        return items.size();
    }

    /** Queues the batch, skipping anything already waiting. Returns how many were added. */
    synchronized int add(List<JSONObject> batch) {
        java.util.Set<String> waiting = new java.util.HashSet<>();
        for (JSONObject it : items) waiting.add(it.optString("id"));
        int added = 0;
        for (JSONObject it : batch) {
            if (!waiting.add(it.optString("id"))) continue;
            items.add(it);
            added++;
        }
        if (added == 0) return 0;
        save();
        return added;
    }

    /** Items with a file to upload (an attachment from a notification, or one the inbox asked for) take the slow lane. */
    static boolean heavy(JSONObject it) {
        return it.has("dataUri") || it.has("uploadUri");
    }

    /** Up to `max` items, the oldest first; with `withMedia` false, only items that carry no file to upload. */
    synchronized List<JSONObject> take(boolean withMedia, int max) {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject it : items) {
            if (heavy(it) != withMedia) continue;
            out.add(it);
            if (out.size() >= max) break;
        }
        return out;
    }

    synchronized boolean hasAny(boolean withMedia) {
        for (JSONObject it : items) if (heavy(it) == withMedia) return true;
        return false;
    }

    /** Two conversations turned out to be one: what still waits for the inbox goes to the one that stays. */
    synchronized void rewriteConv(String from, String into) {
        boolean changed = false;
        for (JSONObject it : items) {
            JSONObject target = it.optJSONObject("item") != null ? it.optJSONObject("item") : it;
            if (from.equals(target.optString("conv"))) {
                try {
                    target.put("conv", into);
                } catch (JSONException ignored) {
                    // literal key
                }
                changed = true;
            }
        }
        if (changed) save();
    }

    synchronized boolean waitingFor(String conv) {
        for (JSONObject it : items) {
            JSONObject target = it.optJSONObject("item") != null ? it.optJSONObject("item") : it;
            if (conv.equals(target.optString("conv"))) return true;
        }
        return false;
    }

    /** The server has them: forget them. */
    synchronized void remove(List<JSONObject> done) {
        boolean changed = false;
        for (JSONObject d : done) {
            String id = d.optString("id");
            for (int i = 0; i < items.size(); i++) {
                if (id.equals(items.get(i).optString("id"))) {
                    items.remove(i);
                    changed = true;
                    break;
                }
            }
        }
        if (changed) save();
    }

    private void load() {
        try {
            if (!file.exists()) return;
            JSONArray arr = new JSONArray(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) items.add(o);
            }
        } catch (IOException | JSONException | RuntimeException e) {
            // A damaged file is dropped rather than wedging the relay; what was in it is already marked seen and lost.
            items.clear();
        }
    }

    private void save() {
        try {
            JSONArray arr = new JSONArray();
            for (JSONObject it : items) arr.put(it);
            File tmp = new File(file.getPath() + ".tmp");
            Files.write(tmp.toPath(), arr.toString().getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(file)) Files.write(file.toPath(), arr.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException ignored) {
            // the in-memory queue still drives delivery; the next save tries again
        }
    }
}
