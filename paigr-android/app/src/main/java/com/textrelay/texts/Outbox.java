package com.textrelay.texts;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rows written on this phone that the inbox doesn't have yet, kept on disk (no backup) until it does: a send cut off
 * by a restart, or one that couldn't reach the inbox, comes back as a row that goes out again by itself (the same
 * message, by the id this phone gave it) rather than vanishing. Only what this phone wrote is kept: its own words,
 * the file's address on this phone, and the identity the inbox's copy will have. Main thread in, disk thread out.
 */
final class Outbox {
    private static final int KEEP = 50;
    private final Inbox inbox;
    /** By the row's id, in the order written. */
    private final Map<String, JSONObject> rows = new LinkedHashMap<>();
    private boolean loaded;

    Outbox(Inbox inbox) {
        this.inbox = inbox;
    }

    private File file() {
        return new File(inbox.app.getNoBackupFilesDir(), "outbox.json");
    }

    /** A row on its way: kept until the inbox has it. */
    void put(Model.Msg temp, Inbox.Attachment att, String to) {
        Link at = inbox.link;
        if (at == null) return;
        try {
            JSONObject r = new JSONObject().put("tid", at.tid).put("cid", temp.cid).put("conv", temp.conv).put("text", temp.text)
                    .put("ts", temp.ts).put("seal", temp.seal).put("tries", temp.tries).put("waiting", false);
            if (att != null) r.put("uri", att.uri.toString()).put("mime", att.mime).put("name", att.name).put("bytes", att.bytes);
            rows.remove(temp.cid);
            rows.put(temp.cid, r);
            while (rows.size() > KEEP) rows.remove(rows.keySet().iterator().next());
            save();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The inbox couldn't be reached: the row waits, and goes again when it answers (or on a tap). */
    void keep(Model.Msg temp) {
        JSONObject r = rows.get(temp.cid);
        if (r == null) return;
        try {
            r.put("waiting", true).put("tries", temp.tries);
        } catch (JSONException ignored) {
            // plain values
        }
        save();
    }

    /** The inbox has it (or it failed for good): nothing to keep. */
    void remove(String cid) {
        if (cid == null || rows.remove(cid) == null) return;
        save();
    }

    private void save() {
        JSONArray a = new JSONArray();
        for (JSONObject r : rows.values()) a.put(r);
        byte[] bytes = a.toString().getBytes(StandardCharsets.UTF_8);
        File f = file();
        inbox.diskExecute(() -> {
            try {
                File tmp = new File(f.getPath() + ".tmp");
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    out.write(bytes);
                }
                if (!tmp.renameTo(f)) throw new IOException("rename");
            } catch (IOException ignored) {
                // kept in memory for this run; the next save tries again
            }
        });
    }

    /** What was kept, as rows that wait to go again (once per inbox, when this phone's screen first opens). */
    void restore() {
        if (loaded) return;
        loaded = true;
        Link at = inbox.link;
        File f = file();
        inbox.diskExecute(() -> {
            List<JSONObject> kept = new ArrayList<>();
            try {
                if (f.isFile()) {
                    JSONArray a = new JSONArray(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject r = a.optJSONObject(i);
                        if (r != null && at != null && at.tid.equals(r.optString("tid")) && !r.optString("cid").isEmpty()) kept.add(r);
                    }
                }
            } catch (IOException | JSONException | RuntimeException ignored) {
                // nothing kept
            }
            inbox.main.post(() -> {
                if (inbox.link != at) return;
                for (JSONObject r : kept) {
                    if (rows.containsKey(r.optString("cid"))) continue;   // written again this run already
                    rows.put(r.optString("cid"), r);
                    inbox.restoreRow(r);
                }
            });
        });
    }

    /** Tests: the rows kept, by id. */
    java.util.Set<String> idsForTests() {
        return rows.keySet();
    }
}
