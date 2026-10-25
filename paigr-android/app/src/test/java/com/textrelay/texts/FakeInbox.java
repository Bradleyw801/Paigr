package com.textrelay.texts;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A stand-in for the inbox server: keeps messages in the order they arrived, with arrival-log keys, and answers
 * /api/inbox and /api/sync?feed= like the real one, including the re-read of the few seconds before a cursor.
 */
final class FakeInbox implements AutoCloseable {
    /** In arrival order. */
    final List<JSONObject> messages = new CopyOnWriteArrayList<>();
    private final List<String> feedKeys = new CopyOnWriteArrayList<>();
    /** The fake server clock, one millisecond per arrival. */
    private long clock = 1_790_000_000_000L;
    final List<String> requests = new CopyOnWriteArrayList<>();
    volatile String expectedKey = null;
    /** When set, /api/inbox and /api/sync carry a relay record with this lastSeen. */
    volatile long relayLastSeen = 0;
    /** The next /api/sync answers that the cursor is older than the log keeps. */
    volatile boolean resetNext = false;
    /** When set, the fake is also a private inbox (v2): /v2/inbox and /v2/sync send what /api/… would, sealed with it. */
    volatile Link link;
    /** When true, the private inbox no longer has this device. */
    volatile boolean revoked;
    /** Phone wraps filed through /v2/wrap, by tag; /v2/link/recover hands them back. */
    final java.util.Map<String, String> wraps = new java.util.concurrent.ConcurrentHashMap<>();
    private final ServerSocket socket;

    FakeInbox() throws IOException {
        socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread(() -> {
            while (!socket.isClosed()) {
                try (Socket s = socket.accept()) {
                    handle(s);
                } catch (IOException ignored) {
                    // closed
                }
            }
        }, "fake-inbox");
        t.setDaemon(true);
        t.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + socket.getLocalPort();
    }

    /** v2: a conversation's merge records (the relay phone's sealed word that others were folded into it). */
    final java.util.Map<String, JSONArray> merged = new java.util.concurrent.ConcurrentHashMap<>();

    synchronized JSONObject add(String k, String conv, String dir, String sender, String name, String text) throws Exception {
        JSONObject m = new JSONObject().put("k", k).put("conv", conv).put("dir", dir).put("sender", sender).put("name", name)
                .put("text", text).put("ts", System.currentTimeMillis()).put("status", "in".equals(dir) ? "received" : "sent");
        clock++;
        feedKeys.add(String.format(Locale.US, "f:%013d:%s", clock, k));
        messages.add(m);
        return m;
    }

    private synchronized JSONObject feedSince(String cursor) throws Exception {
        if (resetNext) {
            resetNext = false;
            return new JSONObject().put("items", new JSONArray()).put("convs", new JSONArray()).put("reset", true)
                    .put("cursor", String.format(Locale.US, "f:%013d", clock)).put("more", false);
        }
        long ts = Long.parseLong(cursor.substring(2, 15));
        String from = String.format(Locale.US, "f:%013d", ts - 15_000);
        java.util.LinkedHashMap<String, JSONObject> latest = new java.util.LinkedHashMap<>();
        for (int i = 0; i < feedKeys.size(); i++) {
            String fk = feedKeys.get(i);
            if (fk.compareTo(from) > 0 && fk.compareTo(cursor) < 0) latest.put(messages.get(i).getString("k"), messages.get(i));
        }
        String next = cursor;
        int taken = 0;
        boolean more = false;
        for (int i = 0; i < feedKeys.size(); i++) {
            String fk = feedKeys.get(i);
            if (fk.compareTo(cursor) <= 0) continue;
            if (taken == 100) {
                more = true;
                break;
            }
            latest.remove(messages.get(i).getString("k"));
            latest.put(messages.get(i).getString("k"), messages.get(i));
            next = fk;
            taken++;
        }
        JSONArray items = new JSONArray();
        for (JSONObject m : latest.values()) items.put(m);
        return new JSONObject().put("items", items).put("cursor", next).put("more", more).put("relay", relay())
                .put("serverTime", System.currentTimeMillis());
    }

    private void handle(Socket s) throws IOException {
        InputStream in = new BufferedInputStream(s.getInputStream());
        String requestLine = line(in);
        String key = null;
        String token = null;
        int length = 0;
        String header;
        while (!(header = line(in)).isEmpty()) {
            int colon = header.indexOf(':');
            if (colon <= 0) continue;
            String name = header.substring(0, colon).trim().toLowerCase(Locale.US);
            String value = header.substring(colon + 1).trim();
            if (name.equals("x-hedy-app-key")) key = value;
            if (name.equals("x-relay-token")) token = value;
            if (name.equals("content-length")) length = Integer.parseInt(value);
        }
        byte[] raw = new byte[length];
        for (int read = 0; read < length; ) {
            int n = in.read(raw, read, length - read);
            if (n < 0) break;
            read += n;
        }
        String path = requestLine.split(" ")[1];
        requests.add(path);
        int status = 200;
        String reply;
        try {
            JSONObject response = new JSONObject();
            JSONObject body = length > 0 ? new JSONObject(new String(raw, StandardCharsets.UTF_8)) : new JSONObject();
            if (path.startsWith("/v2/")) {
                Object[] r = v2(path, token, body);
                status = (Integer) r[0];
                response = (JSONObject) r[1];
            } else if (expectedKey != null && !expectedKey.equals(key)) {
                status = 401;
                response.put("error", new JSONObject().put("code", "unauthorized").put("message", "Authentication required."));
            } else if (path.startsWith("/api/inbox")) {
                synchronized (this) {
                    response.put("items", new JSONArray()).put("feed", String.format(Locale.US, "f:%013d", clock)).put("relay", relay());
                }
            } else if (path.startsWith("/api/sync?feed=")) {
                response = feedSince(URLDecoder.decode(path.substring("/api/sync?feed=".length()), "UTF-8"));
            } else {
                status = 404;
                response.put("error", "no such route");
            }
            reply = response.toString();
        } catch (Exception e) {
            throw new IOException(e);
        }
        byte[] out = reply.getBytes(StandardCharsets.UTF_8);
        OutputStream os = s.getOutputStream();
        os.write(("HTTP/1.1 " + status + (status == 200 ? " OK" : " Error") + "\r\nContent-Type: application/json\r\nContent-Length: " + out.length
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        os.write(out);
        os.flush();
    }

    /** The private inbox's answers: the same records, with what's readable sealed with the link's key. */
    private Object[] v2(String path, String token, JSONObject body) throws Exception {
        if (path.equals("/v2/link/recover")) {
            String w = wraps.get(body.optString("tag"));
            return w == null ? new Object[] { 404, new JSONObject().put("known", false) } : new Object[] { 200, new JSONObject().put("ok", true).put("wrap", w) };
        }
        Link l = link;
        if (l == null || revoked || !l.token.equals(token)) return new Object[] { 403, new JSONObject().put("unlinked", true).put("error", "not linked") };
        if (path.equals("/v2/ping")) return new Object[] { 200, new JSONObject().put("ok", true).put("tid", l.tid).put("role", "inbox") };
        if (path.equals("/v2/wrap")) {
            wraps.put(body.getString("tag"), body.getString("wrap"));
            return new Object[] { 200, new JSONObject().put("ok", true) };
        }
        if (path.startsWith("/v2/inbox")) {
            synchronized (this) {
                return new Object[] { 200, new JSONObject().put("items", new JSONArray()).put("feed", String.format(Locale.US, "f:%013d", clock)).put("relay", relay()) };
            }
        }
        if (path.startsWith("/v2/sync?feed=")) {
            JSONObject r = feedSince(URLDecoder.decode(path.substring("/v2/sync?feed=".length()), "UTF-8"));
            JSONArray items = r.optJSONArray("items");
            JSONArray sealed = new JSONArray();
            for (int i = 0; items != null && i < items.length(); i++) {
                JSONObject m = items.getJSONObject(i);
                JSONObject w = new JSONObject().put("k", m.getString("k")).put("conv", m.getString("conv")).put("dir", m.getString("dir"))
                        .put("status", m.optString("status")).put("ts", m.optLong("ts"));
                for (String f : new String[] { "read", "history" }) if (m.has(f)) w.put(f, m.get(f));
                // As the relay phone seals it: its own time and fingerprint (the stem of its files' paths) in the payload,
                // the fingerprint its own for each message.
                String fp = Crypto.hex(java.security.MessageDigest.getInstance("SHA-256").digest(m.getString("k").getBytes(StandardCharsets.UTF_8))).substring(0, 16);
                JSONObject payload = new JSONObject().put("text", m.optString("text")).put("sender", m.optString("sender"))
                        .put("name", m.optString("name")).put("group", m.optBoolean("group")).put("ts", m.optLong("ts")).put("fp", fp);
                JSONObject media = m.optJSONObject("media");
                // "sealFor" stands in for a server filing it under another conversation; "sealDir" under another direction.
                String sealFor = m.optString("sealFor", m.getString("conv"));
                if (media != null) {
                    payload.put("media", new JSONObject().put("mime", media.optString("mime")));
                    w.put("media", new JSONObject().put("path", "/media/v2/" + l.tid + "/" + sealFor + "/" + Crypto.pad13(m.optLong("ts")) + "-" + fp + "-o.enc"));
                }
                w.put("e", l.sealForTests(payload, Crypto.aadMessage(sealFor, m.optString("sealDir", m.getString("dir")))));
                if (!m.optString("name").isEmpty()) w.put("ne", l.sealForTests(new JSONObject().put("name", m.optString("name")).put("group", m.optBoolean("group")), Crypto.aadName(sealFor)));
                sealed.put(w);
            }
            r.put("items", sealed);
            JSONArray convs = new JSONArray();
            for (java.util.Map.Entry<String, JSONArray> e : merged.entrySet()) convs.put(new JSONObject().put("conv", e.getKey()).put("merged", e.getValue()));
            r.put("convs", convs);
            return new Object[] { 200, r };
        }
        return new Object[] { 404, new JSONObject().put("error", "no such route") };
    }

    private Object relay() throws Exception {
        if (relayLastSeen == 0) return JSONObject.NULL;
        return new JSONObject().put("lastSeen", relayLastSeen).put("listening", true).put("battery", 80);
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1 && c != '\n') if (c != '\r') b.write(c);
        return b.toString("UTF-8");
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
