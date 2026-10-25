package com.textrelay.relay;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Iterator;

/**
 * Store messages already queued for the inbox in the last days, so the regular checks of the message store send only
 * what is new. The inbox keeps one copy of each anyway; this only saves sending the same thing again.
 */
final class SentLog {
    static final long KEEP_MS = 9L * 86_400_000;

    private final File file;
    private final JSONObject keys = new JSONObject();   // store key -> message time

    SentLog(File file) {
        this.file = file;
        try {
            if (file.exists()) {
                JSONObject o = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
                for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                    String k = it.next();
                    keys.put(k, o.optLong(k));
                }
            }
        } catch (IOException | JSONException | RuntimeException ignored) {
            // a damaged file only means some messages are checked with the inbox again
        }
    }

    synchronized boolean has(String key) {
        return keys.has(key);
    }

    synchronized void add(String key, long ts) {
        try {
            keys.put(key, ts);
        } catch (JSONException ignored) {
            // literal key
        }
    }

    /** Forgets what is older than the deepest regular check looks, and writes the rest down. */
    synchronized void save() {
        long cutoff = System.currentTimeMillis() - KEEP_MS;
        for (Iterator<String> it = keys.keys(); it.hasNext(); ) {
            if (keys.optLong(it.next()) < cutoff) it.remove();
        }
        try {
            File tmp = new File(file.getPath() + ".tmp");
            Files.write(tmp.toPath(), keys.toString().getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(file)) Files.write(file.toPath(), keys.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException ignored) {
            // the next save tries again
        }
    }

    synchronized int size() {
        return keys.length();
    }
}
