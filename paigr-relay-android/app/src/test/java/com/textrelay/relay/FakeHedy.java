package com.textrelay.relay;

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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/** A tiny stand-in for the inbox server: records every call and answers like the real API. */
final class FakeHedy implements AutoCloseable {
    /** Every request, as its JSON body plus _method, _path, _key, _headers, _bytes (body length) and _sha256 (body hash). */
    final List<JSONObject> calls = new CopyOnWriteArrayList<>();
    /** Outbox items handed out by the next GET /api/relay/outbox. */
    final Queue<JSONObject> pendingOutbox = new ConcurrentLinkedQueue<>();
    /** Files stored through /api/relay/media or an upload ticket: path -> bytes. */
    final Map<String, byte[]> files = new ConcurrentHashMap<>();
    /** A one-time code the fake accepts, in any of the forms people type it. */
    static final String CODE = "K7Q4M2XD9PRT";
    /** Codes a linked phone has "scanned": a relay phone waiting with one of these gets in. */
    final java.util.Set<String> approved = ConcurrentHashMap.newKeySet();
    /** Tags of phones the inbox has linked before (see Pairing.phoneTag): /pair gives these their key with no code. */
    final java.util.Set<String> knownPhones = ConcurrentHashMap.newKeySet();
    /** The request from an inbox device the next outbox poll or heartbeat reports (server time); 0 for none. */
    volatile long wake = 0;
    volatile String wakeKind = "reconnect";
    /** Text (as the inbox compares it) -> the conversation the inbox already holds it in, for /api/relay/locate. */
    final Map<String, String> located = new ConcurrentHashMap<>();
    /** The status /api/relay/merge answers with (200: done). */
    volatile int mergeStatus = 200;
    /** When true, the next /api/relay/outbox/result fails as if the network dropped it. */
    volatile boolean failNextResult = false;
    /** Attachments (by fp) the inbox already has, so it doesn't ask for them. */
    final java.util.Set<String> haveMedia = ConcurrentHashMap.newKeySet();
    volatile String expectedKey = null;
    /** When set, every media upload is refused with a 415, as the real server does for an unsupported type. */
    volatile boolean rejectMedia = false;
    /** The next this-many media uploads fail with a 500, as a server bug would. */
    final java.util.concurrent.atomic.AtomicInteger failMediaTimes = new java.util.concurrent.atomic.AtomicInteger();
    /** When non-zero, every ingest is refused with this status. */
    volatile int rejectIngestWith = 0;
    volatile String pairedKey = "hak_paired0000000000.PAIREDsecretPAIREDsecretPAIREDsecret";

    // ---- the private inbox (v2) ----
    /** The inbox every test link belongs to. */
    static final String TID = "00112233445566778899aabbccddeeff";
    /**
     * When set, the fake is also a private inbox (v2): it opens what the relay sealed with this link's key and runs the
     * same logic as for v1, so `calls` reads the same in both. Each v2 call is recorded under its v1 path, with
     * _v2path and _raw (the body as it arrived, still sealed) added.
     */
    volatile Link link;
    /** When true, this device's token is no longer in the inbox (another relay phone took over). */
    volatile boolean revoked;
    /** Keyed fingerprints back to the relay's own (from Link's test hook). */
    final Map<String, String> fpLocal = new ConcurrentHashMap<>();
    /** v2 linking: codes asked for (lookup -> token), and the answer the next wait gets (lookup -> answer box). */
    final Map<String, String> asked = new ConcurrentHashMap<>();
    final Map<String, String> answers = new ConcurrentHashMap<>();
    final Map<String, String> wraps = new ConcurrentHashMap<>();   // tag -> wrap
    final Map<String, String> invites = new ConcurrentHashMap<>(); // lookup -> box
    final java.util.Set<String> usedInvites = ConcurrentHashMap.newKeySet();
    private final ServerSocket socket;
    /** Stands in for the separate storage host that upload tickets point at. */
    private final ServerSocket storage;
    private final Map<String, String> ticketPaths = new ConcurrentHashMap<>();
    /** v2: the AAD each upload ticket's file must be sealed with. */
    private final Map<String, String> ticketAads = new ConcurrentHashMap<>();

    FakeHedy() throws IOException {
        Link.onFp = (wire, local) -> fpLocal.put(wire, local);
        socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        storage = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        accept(socket, "fake-hedy");
        accept(storage, "fake-storage");
    }

    private void accept(ServerSocket server, String name) {
        Thread acceptor = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket s = server.accept()) {
                    handle(s);
                } catch (IOException ignored) {
                    // closed, or a client went away
                }
            }
        }, name);
        acceptor.setDaemon(true);
        acceptor.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + socket.getLocalPort();
    }

    List<JSONObject> calls(String path) {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject c : calls) if (path.equals(c.optString("_path"))) out.add(c);
        return out;
    }

    private void handle(Socket s) throws IOException {
        InputStream in = new BufferedInputStream(s.getInputStream());
        String requestLine = line(in);
        int contentLength = 0;
        String key = null;
        Map<String, String> headers = new LinkedHashMap<>();
        String header;
        while (!(header = line(in)).isEmpty()) {
            int colon = header.indexOf(':');
            if (colon <= 0) continue;
            String name = header.substring(0, colon).trim().toLowerCase(Locale.US);
            String value = header.substring(colon + 1).trim();
            headers.put(name, value);
            if (name.equals("content-length")) contentLength = Integer.parseInt(value);
            if (name.equals("x-hedy-app-key")) key = value;
        }
        byte[] body = new byte[contentLength];
        int read = 0;
        while (read < contentLength) {
            int n = in.read(body, read, contentLength - read);
            if (n < 0) break;
            read += n;
        }
        String[] parts = requestLine.split(" ");
        String method = parts[0];
        String path = parts[1];
        if (path.startsWith("http://") || path.startsWith("https://")) {
            // A request through a proxy names the whole address (absolute-form), which servers take as its path.
            int slash = path.indexOf('/', path.indexOf("://") + 3);
            path = slash < 0 ? "/" : path.substring(slash);
        }
        if (path.startsWith("/_files/")) {   // what a signed address hands out: the sealed bytes, as stored
            byte[] f = files.get(path.substring("/_files".length()));
            OutputStream os = s.getOutputStream();
            if (f == null) {
                os.write("HTTP/1.1 404 Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            } else {
                os.write(("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: " + f.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                os.write(f);
            }
            os.flush();
            return;
        }
        Object[] answer;
        try {
            if (link != null && path.startsWith("/v2/")) {
                answer = v2(method, path, headers, body);
            } else {
                if (link != null && path.startsWith("/upload/")) {
                    // storage got sealed bytes, sealed for the file the ticket was for
                    body = link.openFile(body, ticketAads.getOrDefault(path.substring("/upload/".length()), ""));
                }
                String contentType = headers.getOrDefault("content-type", "");
                JSONObject request = contentType.startsWith("application/json") && body.length > 0 && body[0] == '{'
                        ? new JSONObject(new String(body, StandardCharsets.UTF_8)) : new JSONObject();
                answer = respond(method, path, headers, body, request, key);
            }
        } catch (Exception e) {
            throw new IOException(e);
        }
        int status = (Integer) answer[0];
        byte[] out = answer[1].toString().getBytes(StandardCharsets.UTF_8);
        OutputStream os = s.getOutputStream();
        os.write(("HTTP/1.1 " + status + (status == 200 ? " OK" : " Error") + "\r\nContent-Type: application/json\r\nContent-Length: " + out.length
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        os.write(out);
        os.flush();
    }

    /** The first version's API, as the real server answers it: { status, reply JSON }. */
    private Object[] respond(String method, String path, Map<String, String> headers, byte[] body, JSONObject request, String key) throws Exception {
        String contentType = headers.getOrDefault("content-type", "");
        int status = 200;
        String reply;
        {
            request.put("_method", method);
            request.put("_path", path);
            request.put("_key", key != null ? key : "");
            request.put("_headers", new JSONObject(headers));
            request.put("_bytes", body.length);
            request.put("_sha256", sha256(body));
            calls.add(request);
            JSONObject response = new JSONObject();
            if (path.equals("/pair")) {
                String given = Pairing.normalize(request.optString("code")).toUpperCase(Locale.US);
                if (knownPhones.contains(request.optString("device"))) {
                    response.put("ok", true).put("role", request.optString("role")).put("key", pairedKey).put("known", true);
                } else if (CODE.equals(given) || approved.contains(given)) {
                    approved.remove(given);
                    response.put("ok", true).put("role", request.optString("role")).put("key", pairedKey);
                } else if (request.optBoolean("wait")) {
                    status = 202;
                    response.put("waiting", true);
                } else {
                    status = 403;   // not 401: HttpURLConnection drops the body of a 401
                    response.put("error", "That code is not right, or it has expired. Make a new one in Relay.");
                }
            } else if (path.startsWith("/upload/")) {
                // Storage, as the upload ticket's signed URL: no device key expected here.
                String ticket = path.substring("/upload/".length());
                String stored = ticketPaths.get(ticket);
                if (stored == null) {
                    status = 403;
                    response.put("error", "unknown ticket");
                } else {
                    files.put(stored, body);
                }
            } else if (expectedKey != null && !expectedKey.equals(key)) {
                status = 401;
                response.put("error", new JSONObject().put("code", "unauthorized").put("message", "Authentication required."));
            } else if (path.equals("/api/relay/ingest") && rejectIngestWith != 0) {
                status = rejectIngestWith;
                response.put("error", "refused for the test");
            } else if (path.equals("/api/relay/ingest")) {
                // Like the real server: one result per item, and an attachment is asked for only when it isn't there yet.
                JSONArray results = new JSONArray();
                JSONArray items = request.optJSONArray("items");
                for (int i = 0; items != null && i < items.length(); i++) {
                    JSONObject it = items.getJSONObject(i);
                    String fp = it.optString("fp");
                    JSONObject r = new JSONObject().put("fp", fp).put("k", "m:" + String.format(Locale.US, "%013d", it.optLong("ts")) + ":" + fp)
                            .put("status", "stored");
                    JSONObject media = it.optJSONObject("media");
                    if (media != null && media.has("path")) {
                        haveMedia.add(fp);
                        r.put("media", "attached");
                    }
                    if (it.optBoolean("wantMedia") && !haveMedia.contains(fp)) r.put("needMedia", true);
                    results.put(r);
                }
                response.put("ok", true).put("results", results);
            } else if (path.equals("/api/relay/merge") && mergeStatus != 200) {
                status = mergeStatus;
                response.put("error", "unknown conversation");
            } else if (path.equals("/api/relay/outbox/result") && failNextResult) {
                failNextResult = false;
                status = 503;
                response.put("error", "service unavailable");
            } else if (path.equals("/api/relay/locate")) {
                JSONArray out = new JSONArray();
                JSONArray probes = request.optJSONArray("probes");
                for (int i = 0; probes != null && i < probes.length(); i++) {
                    String conv = located.get(Store.norm(probes.getJSONObject(i).optString("text")));
                    out.put(conv == null ? new JSONArray() : new JSONArray().put(conv));
                }
                response.put("convs", out);
            } else if (path.equals("/api/relay/outbox")) {
                JSONArray items = new JSONArray();
                JSONObject item;
                while ((item = pendingOutbox.poll()) != null) items.put(item);
                response.put("items", items).put("pollSeconds", 5).put("serverTime", System.currentTimeMillis()).put("wake", wake)
                        .put("wakeKind", wakeKind);
            } else if (path.equals("/api/relay/heartbeat")) {
                response.put("ok", true).put("pollSeconds", 20).put("wake", wake).put("wakeKind", wakeKind);
            } else if (path.equals("/api/relay/media")) {
                if (failMediaTimes.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                    status = 500;
                    response.put("error", "Server error: storage failed");
                } else if (rejectMedia) {
                    status = 415;
                    response.put("error", "unsupported media type");
                } else if (body.length > Hedy.DIRECT_MAX) {
                    status = 413;
                    response.put("error", "Too large for a direct upload; use a ticket.");
                } else {
                    String stored = mediaPath(headers.get("x-relay-conv"), headers.get("x-relay-ts"), headers.get("x-relay-fp"),
                            headers.get("x-relay-kind"), contentType);
                    files.put(stored, body);
                    response.put("ok", true).put("path", stored).put("bytes", body.length);
                }
            } else if (path.equals("/api/relay/media/ticket")) {
                String stored = mediaPath(request.optString("conv"), Long.toString(request.optLong("ts")), request.optString("fp"),
                        request.optString("kind"), request.optString("mime"));
                String ticket = "tk" + Integer.toHexString(stored.hashCode());
                ticketPaths.put(ticket, stored);
                response.put("ok", true).put("path", stored).put("ticket", ticket)
                        .put("uploadUrl", "http://127.0.0.1:" + storage.getLocalPort() + "/upload/" + ticket)
                        .put("headers", new JSONObject().put("Content-Type", request.optString("mime")));
            } else if (path.equals("/api/relay/media/complete")) {
                String stored = ticketPaths.get(request.optString("ticket"));
                if (stored == null || !files.containsKey(stored)) {
                    status = 400;
                    response.put("error", "nothing was uploaded for that ticket");
                } else {
                    response.put("ok", true).put("path", stored).put("bytes", files.get(stored).length);
                }
            } else {
                response.put("ok", true);
            }
            reply = response.toString();
        }
        return new Object[] { status, reply };
    }

    // ------------------------------------------------------------------ the private inbox (v2)

    private Object[] v2(String method, String path, Map<String, String> headers, byte[] body) throws Exception {
        JSONObject req = body.length > 0 && body[0] == '{' ? new JSONObject(new String(body, StandardCharsets.UTF_8)) : new JSONObject();
        if (path.startsWith("/v2/link/")) return v2link(path, req, headers);
        Link l = link;
        if (revoked || !l.token.equals(headers.get("x-relay-token"))) {
            return new Object[] { 403, new JSONObject().put("unlinked", true).put("error", "This device isn't linked to an inbox.") };
        }
        Map<String, String> h = new LinkedHashMap<>(headers);
        h.put("x-hedy-app-key", l.token);   // recorded as _key
        String v1;
        JSONObject open = new JSONObject();
        byte[] bytes = body;
        switch (path.split("\\?")[0]) {
            case "/v2/ping": return new Object[] { 200, new JSONObject().put("ok", true).put("tid", TID).put("role", "relay") };
            case "/v2/media": {   // a signed address for one of the inbox's files: here, the fake's own /_files route
                String p = java.net.URLDecoder.decode(path.contains("?p=") ? path.substring(path.indexOf("?p=") + 3) : "", "UTF-8");
                calls.add(new JSONObject().put("_method", method).put("_path", "/v2/media").put("p", p));
                if (!files.containsKey(p)) return new Object[] { 404, new JSONObject().put("error", "no such file") };
                return new Object[] { 200, new JSONObject().put("url", baseUrl() + "/_files" + p).put("expiresIn", 3600) };
            }
            case "/v2/ticket": return new Object[] { 200, new JSONObject().put("ticket", "rt").put("webSocketUrl", "ws://127.0.0.1:1").put("channel", "tr2/" + TID + "/o") };
            case "/v2/wrap": return new Object[] { 200, record(path, req, h, body, new JSONObject().put("ok", true), () -> wraps.put(req.optString("tag"), req.optString("wrap"))) };
            case "/v2/relay/ingest":
                v1 = "/api/relay/ingest";
                open = openIngest(l, req);
                break;
            case "/v2/relay/locate":
                v1 = "/api/relay/locate";
                open = new JSONObject().put("probes", req.optJSONArray("probes"));
                break;
            case "/v2/relay/merge":
                v1 = "/api/relay/merge";
                open = new JSONObject().put("from", local(l, req.optString("from"))).put("into", local(l, req.optString("into")));
                break;
            case "/v2/relay/outbox": v1 = "/api/relay/outbox"; open = req; break;
            case "/v2/relay/outbox/result": v1 = "/api/relay/outbox/result"; open = req; break;
            case "/v2/relay/heartbeat": {
                v1 = "/api/relay/heartbeat";
                open = new JSONObject(req.toString());
                if (req.has("sn")) {   // the relay phone's own words come sealed
                    JSONObject said = l.open(req.getString("sn"), Crypto.AAD_NOTE);
                    open.put("note", said.optString("note"));
                    JSONObject hist = open.optJSONObject("store") != null ? open.getJSONObject("store").optJSONObject("history") : null;
                    if (hist != null) hist.put("note", said.optString("history"));
                }
                break;
            }
            case "/v2/media/put":
                v1 = "/api/relay/media";
                bytes = l.openFile(body, Crypto.aadFile(headers.get("x-relay-conv"), Long.parseLong(headers.get("x-relay-ts")),
                        headers.get("x-relay-fp"), headers.get("x-relay-kind")));
                h.put("x-relay-conv", local(l, headers.get("x-relay-conv")));
                h.put("x-relay-fp", fpLocal.getOrDefault(headers.get("x-relay-fp"), headers.get("x-relay-fp")));
                break;
            case "/v2/media/ticket":
                v1 = "/api/relay/media/ticket";
                open = new JSONObject(req.toString()).put("conv", local(l, req.optString("conv"))).put("fp", fpLocal.getOrDefault(req.optString("fp"), req.optString("fp")))
                        .put("mime", "application/zip");
                break;
            case "/v2/media/complete": v1 = "/api/relay/media/complete"; open = req; break;
            default: return new Object[] { 404, new JSONObject().put("error", "No such endpoint: " + method + " " + path) };
        }
        open.put("_v2path", path);
        open.put("_raw", req);
        Object[] r = respond(method, v1, h, bytes, open, l.token);
        if ((Integer) r[0] != 200) return r;
        JSONObject res = new JSONObject(r[1].toString());
        switch (v1) {
            case "/api/relay/ingest": {
                JSONArray results = res.optJSONArray("results");
                for (int i = 0; results != null && i < results.length(); i++) {
                    JSONObject one = results.getJSONObject(i);
                    String wfp = l.fpId(one.optString("fp"));
                    one.put("fp", wfp).put("k", "m:" + String.format(Locale.US, "%013d", Long.parseLong(one.optString("k").split(":")[1])) + ":" + wfp);
                }
                break;
            }
            case "/api/relay/locate": {
                // The fake keeps `located` by text; a private inbox matches by token.
                JSONArray probes = req.optJSONArray("probes");
                JSONArray out = new JSONArray();
                for (int i = 0; probes != null && i < probes.length(); i++) {
                    JSONObject p = probes.getJSONObject(i);
                    JSONArray hit = new JSONArray();
                    for (Map.Entry<String, String> e : located.entrySet()) {
                        if (l.matchToken(p.optString("dir"), e.getKey(), null).equals(p.optString("mt"))) hit.put(l.convId(e.getValue()));
                    }
                    out.put(hit);
                }
                res.put("convs", out);
                break;
            }
            case "/api/relay/outbox": {
                // As Texts sends a reply: a request sealed for its conversation, with a nonce and the time it was made.
                // A test can give its own "o", or "n" / "made", or "sealFor" (seal it for one conversation, file it under another).
                JSONArray items = res.optJSONArray("items");
                JSONArray sealed = new JSONArray();
                for (int i = 0; items != null && i < items.length(); i++) {
                    JSONObject it = items.getJSONObject(i);
                    // "wire": a keyed conversation id given as is (one this phone has never had to compute itself)
                    String wconv = it.has("wire") ? it.getString("wire") : l.convId(it.optString("conv"));
                    JSONObject w = new JSONObject().put("id", it.optString("id")).put("conv", wconv).put("ts", it.optLong("ts"));
                    if (it.has("o")) {
                        w.put("o", it.getString("o"));
                    } else {
                        JSONObject request = new JSONObject().put("text", it.optString("text"))
                                .put("n", it.optString("n", Crypto.b64u(Crypto.random(16))))
                                .put("ts", it.optLong("made", System.currentTimeMillis()));
                        w.put("o", l.seal(request, Crypto.aadOut(it.has("wire") && !it.has("sealFor") ? wconv : l.convId(it.optString("sealFor", it.optString("conv"))))));
                    }
                    sealed.put(w);
                }
                res.put("items", sealed);
                break;
            }
            case "/api/relay/media":
            case "/api/relay/media/ticket": {
                // Files are kept under the real server's v2 names; tests look them up by the path the relay sent.
                String old = res.optString("path");
                String wconv = "/api/relay/media".equals(v1) ? headers.get("x-relay-conv") : req.optString("conv");
                String wfp = "/api/relay/media".equals(v1) ? headers.get("x-relay-fp") : req.optString("fp");
                String ts = "/api/relay/media".equals(v1) ? headers.get("x-relay-ts") : Long.toString(req.optLong("ts"));
                String kind = "/api/relay/media".equals(v1) ? headers.get("x-relay-kind") : req.optString("kind");
                String path2 = v2MediaPath(wconv, ts, wfp, kind);
                if (files.containsKey(old)) files.put(path2, files.remove(old));
                for (Map.Entry<String, String> t : ticketPaths.entrySet()) if (t.getValue().equals(old)) t.setValue(path2);
                if (res.has("ticket")) ticketAads.put(res.getString("ticket"), Crypto.aadFile(wconv, Long.parseLong(ts), wfp, kind));
                res.put("path", path2);
                break;
            }
            default:
                break;
        }
        return new Object[] { 200, res };
    }

    /** /media/v2/<tid>/<conv>/<ts>-<fp>-<o|p>.enc, as the real server names a sealed file. */
    static String v2MediaPath(String wconv, String ts, String wfp, String kind) {
        return "/media/v2/" + TID + "/" + wconv + "/" + String.format(Locale.US, "%013d", Long.parseLong(ts)) + "-" + wfp + "-" + ("preview".equals(kind) ? "p" : "o") + ".enc";
    }

    private String local(Link l, String wire) {
        String c = l.localConv(wire, null);
        return c != null ? c : wire;
    }

    /** A sealed ingest, opened: the relay's own conversation ids, fingerprints, texts and names again. */
    private JSONObject openIngest(Link l, JSONObject req) throws Exception {
        JSONArray items = req.optJSONArray("items");
        JSONArray out = new JSONArray();
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject w = items.getJSONObject(i);
            // Sealed for its conversation and direction: a payload that opens only as another conversation's, or as the
            // other direction, would fail here. It names the message's own time and fingerprint.
            JSONObject payload = l.open(w.getString("e"), Crypto.aadMessage(w.optString("conv"), w.optString("dir")));
            if (payload.optLong("ts") != w.optLong("ts") || !payload.optString("fp").equals(w.optString("fp"))) {
                throw new AssertionError("a payload names its own message's time and fingerprint: " + payload);
            }
            JSONObject it = new JSONObject()
                    .put("conv", local(l, w.optString("conv")))
                    .put("fp", fpLocal.getOrDefault(w.optString("fp"), w.optString("fp")))
                    .put("ts", w.optLong("ts")).put("dir", w.optString("dir")).put("src", w.optString("src"))
                    .put("text", payload.optString("text")).put("sender", payload.optString("sender"))
                    .put("name", payload.optString("name")).put("group", payload.optBoolean("group"));
            for (String k : new String[] { "read", "history", "status", "wantMedia", "mt" }) if (w.has(k)) it.put(k, w.get(k));
            if (w.has("ne")) it.put("_name", l.open(w.getString("ne"), Crypto.aadName(w.optString("conv"))).optString("name"));
            JSONObject about = payload.optJSONObject("media");
            JSONObject where = w.optJSONObject("media");
            if (about != null || where != null) {
                JSONObject m = new JSONObject(about != null ? about.toString() : "{}");
                if (where != null) for (java.util.Iterator<String> k = where.keys(); k.hasNext(); ) {
                    String name = k.next();
                    m.put(name, where.get(name));
                }
                it.put("media", m);
            }
            out.put(it);
        }
        JSONObject open = new JSONObject().put("items", out);
        for (String map : new String[] { "replyable", "reads" }) {
            JSONObject byWire = req.optJSONObject(map);
            if (byWire == null) continue;
            JSONObject byLocal = new JSONObject();
            for (java.util.Iterator<String> k = byWire.keys(); k.hasNext(); ) {
                String wc = k.next();
                byLocal.put(local(l, wc), byWire.get(wc));
            }
            open.put(map, byLocal);
        }
        return open;
    }

    private JSONObject record(String path, JSONObject req, Map<String, String> headers, byte[] body, JSONObject response, Runnable effect) throws Exception {
        JSONObject c = new JSONObject(req.toString());
        c.put("_method", "POST").put("_path", path).put("_key", headers.getOrDefault("x-relay-token", "")).put("_headers", new JSONObject(headers))
                .put("_bytes", body.length).put("_sha256", sha256(body));
        calls.add(c);
        if (effect != null) effect.run();
        return response;
    }

    /** v2 linking, enough for the relay's side: ask, wait (answered by the test through `answers`), recover. */
    private Object[] v2link(String path, JSONObject req, Map<String, String> headers) throws Exception {
        JSONObject call = record(path, req, headers, new byte[0], new JSONObject(), null);
        Object[] r = v2linkAnswer(path, req, headers);
        calls.get(calls.size() - 1).put("_status", r[0]);
        return r;
    }

    private Object[] v2linkAnswer(String path, JSONObject req, Map<String, String> headers) throws Exception {
        switch (path) {
            case "/v2/link/ask":
                asked.put(req.getString("lookup"), req.getString("token"));
                return new Object[] { 200, new JSONObject().put("ok", true).put("expiresAt", System.currentTimeMillis() + 15 * 60_000) };
            case "/v2/link/wait": {
                String lookup = req.getString("lookup");
                if (!asked.containsKey(lookup)) return new Object[] { 410, new JSONObject().put("expired", true).put("error", "This code has expired.") };
                String answer = answers.get(lookup);
                if (answer == null) return new Object[] { 202, new JSONObject().put("waiting", true) };
                return new Object[] { 200, new JSONObject().put("ok", true).put("tid", TID).put("role", "relay").put("answer", answer) };
            }
            case "/v2/link/recover": {
                String w = wraps.get(req.getString("tag"));
                if (w == null) return new Object[] { 404, new JSONObject().put("known", false) };
                return new Object[] { 200, new JSONObject().put("ok", true).put("role", "relay").put("wrap", w) };
            }
            case "/v2/link/invite":
                if (link == null || !link.token.equals(headers.get("x-relay-token"))) return new Object[] { 403, new JSONObject().put("unlinked", true) };
                invites.put(req.getString("lookup"), req.getString("box"));
                return new Object[] { 200, new JSONObject().put("ok", true).put("expiresAt", System.currentTimeMillis() + 15 * 60_000) };
            case "/v2/link/status":
                return new Object[] { 200, new JSONObject().put("state", usedInvites.contains(req.getString("lookup")) ? "done" : invites.containsKey(req.getString("lookup")) ? "open" : "expired") };
            default:
                return new Object[] { 404, new JSONObject().put("error", "No such endpoint") };
        }
    }

    /** The same naming rule as the real server: /media/<conv>/<ts>-<fp>-<o|p>.<ext>. */
    private static String mediaPath(String conv, String ts, String fp, String kind, String mime) {
        String ext = mime == null ? "bin" : mime.contains("jpeg") ? "jpg" : mime.contains("png") ? "png" : mime.contains("gif") ? "gif"
                : mime.contains("mp4") ? "mp4" : "bin";
        return "/media/" + conv + "/" + ts + "-" + fp + "-" + ("preview".equals(kind) ? "p" : "o") + "." + ext;
    }

    static String sha256(byte[] bytes) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format(Locale.US, "%02x", x));
            return b.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1 && c != '\n') {
            if (c != '\r') b.write(c);
        }
        return b.toString("UTF-8");
    }

    @Override
    public void close() throws IOException {
        socket.close();
        storage.close();
    }
}
