package com.textrelay.relay;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The relay's calls to a private inbox (v2). Relay hands it the same requests as the first version's client; this
 * seals the text, names and attachments with the inbox key, swaps this phone's conversation ids and fingerprints for
 * keyed ones, and adds the match tokens the server folds copies by. What comes back is turned back the same way.
 */
final class Hedy2 extends Hedy {
    private final Link link;
    private final Supplier<Collection<String>> known;
    /** This phone's conversations folded into a conversation (its own merges), for replies sealed before a merge. */
    private final java.util.function.Function<String, Collection<String>> mergedInto;
    private final Requests requests;

    /** A reply request made this long before this phone knew its conversation is refused (the two phones' clocks). */
    static final long KNOWN_GRACE_MS = 2 * 60_000L;

    /**
     * What this phone remembers for checking reply requests (Prefs): requests that opened for none of its conversations,
     * by their seal's tag, and since when it has known each conversation (0: since before it kept that).
     */
    interface Requests {
        void rememberUnopened(String tag, long now);

        boolean wasUnopened(String tag);

        long knownSince(String conv);
    }

    Hedy2(Link link, Supplier<Collection<String>> known) {
        this(link, known, null, null);
    }

    Hedy2(Link link, Supplier<Collection<String>> known, java.util.function.Function<String, Collection<String>> mergedInto) {
        this(link, known, mergedInto, null);
    }

    Hedy2(Link link, Supplier<Collection<String>> known, java.util.function.Function<String, Collection<String>> mergedInto,
            Requests requests) {
        super(link.server, null);
        this.link = link;
        this.known = known != null ? known : Collections::emptyList;
        this.mergedInto = mergedInto != null ? mergedInto : c -> Collections.emptyList();
        this.requests = requests != null ? requests : new Requests() {
            @Override
            public void rememberUnopened(String tag, long now) {
                // nothing kept
            }

            @Override
            public boolean wasUnopened(String tag) {
                return false;
            }

            @Override
            public long knownSince(String conv) {
                return 0;
            }
        };
    }

    /**
     * A sealed value's GCM tag (its last 16 bytes, hex), however its base64 is written: what identifies the request
     * itself, since a seal written another way never opens (Crypto.unb64u) but is the same request. "" for anything else.
     */
    static String tagOf(String sealed) {
        if (sealed == null || !sealed.startsWith("e1.")) return "";
        try {
            byte[] raw = java.util.Base64.getUrlDecoder().decode(sealed.substring(3));
            return raw.length < 28 ? "" : Crypto.hex(java.util.Arrays.copyOfRange(raw, raw.length - 16, raw.length));
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    @Override
    protected void authorize(HttpURLConnection c) {
        c.setRequestProperty("x-relay-token", link.token);
    }

    @Override
    JSONObject ping() throws IOException {
        return get("/v2/ping");
    }

    @Override
    JSONObject ingest(JSONObject body) throws IOException {
        JSONArray items = body.optJSONArray("items");
        JSONArray wire = new JSONArray();
        Map<String, String> fpBack = new HashMap<>();
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            String fp = it.optString("fp");
            String wfp = link.fpId(fp);
            fpBack.put(wfp, fp);
            wire.put(seal(it, wfp));
        }
        JSONObject out = Json.obj("items", wire);
        JSONObject replyable = body.optJSONObject("replyable");
        if (replyable != null) put(out, "replyable", keyed(replyable));
        JSONObject reads = body.optJSONObject("reads");
        if (reads != null) put(out, "reads", keyed(reads));
        JSONObject r = post("/v2/relay/ingest", out);
        JSONArray results = r.optJSONArray("results");
        for (int i = 0; results != null && i < results.length(); i++) {
            JSONObject res = results.optJSONObject(i);
            if (res == null) continue;
            String back = fpBack.get(res.optString("fp"));
            if (back != null) put(res, "fp", back);
        }
        return r;
    }

    /** One message as the private inbox stores it: what it is sealed, what the server needs to know in the open. */
    private JSONObject seal(JSONObject it, String wfp) {
        String dir = "out".equals(it.optString("dir")) ? "out" : "in";
        String name = it.optString("name");
        boolean group = it.optBoolean("group");
        JSONObject media = it.optJSONObject("media");
        String wconv = link.convId(it.optString("conv"));
        JSONObject w = Json.obj("conv", wconv, "fp", wfp, "ts", it.optLong("ts"), "dir", dir,
                "src", "store".equals(it.optString("src")) ? "store" : "notif");
        if (it.optBoolean("read")) put(w, "read", true);
        if (it.optBoolean("history")) put(w, "history", true);
        if (it.has("status")) put(w, "status", it.optString("status"));
        if (it.optBoolean("wantMedia")) put(w, "wantMedia", true);
        // The payload names the message's own time and keyed fingerprint (the stem of its files' paths), so a device
        // shows it at that time and takes only those files as its attachment.
        JSONObject payload = Json.obj("text", it.optString("text"), "sender", it.optString("sender"), "name", name, "group", group,
                "ts", it.optLong("ts"), "fp", wfp);
        String mime = null;
        if (media != null) {
            mime = media.optString("mime", "");
            JSONObject about = Json.obj("mime", mime);
            if (media.optInt("w") > 0 && media.optInt("h") > 0) {
                put(about, "w", media.optInt("w"));
                put(about, "h", media.optInt("h"));
            }
            if (media.optLong("bytes") > 0) put(about, "bytes", media.optLong("bytes"));
            put(payload, "media", about);
            JSONObject where = new JSONObject();
            if (media.has("path")) put(where, "path", media.optString("path"));
            if (media.has("preview")) put(where, "preview", media.optString("preview"));
            put(w, "media", where);
        }
        put(w, "e", link.seal(payload, Crypto.aadMessage(wconv, dir)));
        if (!name.isEmpty()) put(w, "ne", link.seal(Json.obj("name", name, "group", group), Crypto.aadName(wconv)));
        String mt = link.matchToken(dir, it.optString("text"), mime);
        if (!mt.isEmpty()) put(w, "mt", mt);
        return w;
    }

    private JSONObject keyed(JSONObject byConv) {
        JSONObject out = new JSONObject();
        for (Iterator<String> k = byConv.keys(); k.hasNext(); ) {
            String conv = k.next();
            put(out, link.convId(conv), byConv.opt(conv));
        }
        return out;
    }

    /** A private inbox isn't asked which conversation a thread belongs to: the relay phone decides that itself (Relay.locator). */
    @Override
    JSONObject locate(JSONArray probes) throws IOException {
        throw new ApiException(400, "a private inbox doesn't locate threads; the relay phone does");
    }

    /** With the phone's sealed word for it, so inbox devices accept `from`'s messages as `into`'s. */
    @Override
    JSONObject merge(String from, String into) throws IOException {
        String wfrom = link.convId(from), winto = link.convId(into);
        return post("/v2/relay/merge", Json.obj("from", wfrom, "into", winto, "mr", link.mergeRecord(wfrom, winto)));
    }

    /**
     * Replies waiting to go out, opened. Each is a request an inbox device sealed for one conversation, with a nonce and
     * the time it was made; the relay phone checks those before sending anything (Relay.processOutbox). One that doesn't
     * open as a request for the conversation it is filed under (sealed with another key, or moved to another
     * conversation) is reported failed at once rather than sent somewhere it wasn't meant for. A request for a
     * conversation this phone doesn't know is refused, unless it starts one: then it names the number, and the
     * conversation must be the one this phone keys from that number (`to` on the item says so).
     */
    @Override
    JSONObject outbox() throws IOException {
        JSONObject r = get("/v2/relay/outbox");
        JSONArray items = r.optJSONArray("items");
        JSONArray open = new JSONArray();
        Collection<String> mine = null;
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            String wire = it.optString("conv");
            String local = link.localConv(wire, null);
            if (local == null) {
                if (mine == null) mine = known.get();
                local = link.localConv(wire, mine);
            }
            String sealedRequest = it.optString("o");
            String tag = tagOf(sealedRequest);
            JSONObject request = openRequest(sealedRequest, wire, local);
            String refuse = null;
            // A conversation an inbox device started with a number: the request names the number, and the conversation
            // is the one this phone keys from that number alone, so the text can go nowhere else (Conversations.started).
            String to = request != null ? Conversations.number(request.optString("to", "")) : null;
            if (to != null && !link.convId(Conversations.startedId(to)).equals(wire)) to = null;
            if (to != null && local == null) local = Conversations.startedId(to);
            if (request == null) {
                // Not sealed for the conversation it's filed under. If it's a real request for another of this phone's
                // conversations (the server filed it elsewhere), it is refused and spent like any other, so it can't be
                // filed again under the right one and go out after the person was told it failed.
                if (mine == null) mine = known.get();
                request = openAnywhere(sealedRequest, mine);
                refuse = NotSent.UNCHECKED;
                // Sealed for none of them (a conversation this phone doesn't know yet, or another key): reported failed
                // below, and remembered, so it's refused too if it opens once that conversation turns up here.
                if (request == null) requests.rememberUnopened(tag, System.currentTimeMillis());
            } else if (requests.wasUnopened(tag)) {
                refuse = NotSent.UNCHECKED;   // reported failed earlier, when it opened nowhere: never sent now
            }
            String text = request != null ? request.optString("text", "") : "";
            String nonce = request != null ? request.optString("n", "") : "";
            long made = request != null ? request.optLong("ts", 0) : 0;
            // A file to send with it: named inside the sealed request, so it's this inbox's and this conversation's.
            JSONObject media = request != null ? mediaOf(request.optJSONObject("media"), wire) : null;
            boolean badMedia = request != null && request.optJSONObject("media") != null && media == null;
            if ((text.isEmpty() && media == null) || badMedia || !nonce.matches("[A-Za-z0-9_-]{16,64}") || made <= 0) {
                post("/v2/relay/outbox/result", Json.obj("id", it.optString("id"), "status", "failed", "code", NotSent.UNCHECKED));
                continue;
            }
            JSONObject o = Json.obj("id", it.optString("id"), "conv", local != null ? local : "", "text", text, "ts", it.optLong("ts"),
                    "nonce", nonce, "made", made);
            if (media != null) put(o, "media", media);
            if (to != null) put(o, "to", to);
            // Since when this phone has known the conversation: a request made before then is refused (Prefs.spendRequest).
            if (local != null && requests.knownSince(local) > 0) put(o, "since", requests.knownSince(local));
            // A real request for a conversation this phone doesn't know: refused, and spent like any other (see
            // Relay.processOutbox), so it can't go out later if that conversation turns up.
            if (refuse != null) put(o, "refuse", refuse);
            else if (local == null) put(o, "refuse", NotSent.UNKNOWN_CONV);
            open.put(o);
        }
        put(r, "items", open);
        return r;
    }

    /** Where this inbox keeps a message's sealed file (inbox2.js mediaPath): the conversation, the time and the fingerprint. */
    static final java.util.regex.Pattern MEDIA_PATH = java.util.regex.Pattern.compile("^/media/v2/([a-f0-9]{32})/([a-f0-9]{32})/([0-9]{13})-([a-z0-9]{8,32})-o\\.enc$");

    /** The attachment a request names, when it's a file of this inbox for `wire` (the conversation the request is for). */
    JSONObject mediaOf(JSONObject m, String wire) {
        if (m == null) return null;
        String path = m.optString("path", "");
        java.util.regex.Matcher x = MEDIA_PATH.matcher(path);
        if (!x.matches() || !x.group(1).equals(link.tid) || !x.group(2).equals(wire)) return null;
        String mime = Media.normalize(m.optString("mime", ""));
        String name = m.optString("name", "");
        return Json.obj("path", path, "mime", mime, "bytes", Math.max(0, m.optLong("bytes", 0)), "name", name.length() > 120 ? name.substring(0, 120) : name);
    }

    /** The most an attachment fetched to send on can be: what the inbox takes by a ticket. */
    static final long FETCH_MAX = 26L * 1024 * 1024;

    /** Fetches one of this inbox's sealed files and opens it: the conversation, time and fingerprint in its path are its seal. */
    byte[] download(String path) throws IOException {
        java.util.regex.Matcher x = MEDIA_PATH.matcher(path);
        if (!x.matches()) throw new IOException("not a file of this inbox");
        JSONObject where = get("/v2/media?p=" + java.net.URLEncoder.encode(path, "UTF-8"));
        String url = where.optString("url", "");
        if (url.isEmpty()) throw new IOException("the inbox has no such file");
        boolean own = url.startsWith(base + "/");
        if (!own && !url.startsWith("https://")) throw new IOException("the file isn't where the inbox said");
        HttpURLConnection c = Tor.open(new java.net.URL(url), 15_000, 120_000);
        byte[] sealed;
        try {
            c.setRequestMethod("GET");
            if (own) authorize(c);
            int status = c.getResponseCode();
            if (status != 200) throw new IOException("the file couldn't be fetched (" + status + ")");
            try (java.io.InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                long total = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > FETCH_MAX) throw new IOException("too big");
                    out.write(buf, 0, n);
                }
                sealed = out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
        try {
            return link.openFile(sealed, Crypto.aadFile(x.group(2), Long.parseLong(x.group(3)), x.group(4), "original"));
        } catch (GeneralSecurityException e) {
            throw new IOException("the file isn't sealed for this message");
        }
    }

    /** A reply request sealed for any of this phone's conversations, opened; null when it's none of theirs. */
    private JSONObject openAnywhere(String sealed, Collection<String> convs) {
        if (sealed == null || sealed.isEmpty()) return null;
        for (String c : convs) {
            try {
                return link.open(sealed, Crypto.aadOut(link.convId(c)));
            } catch (GeneralSecurityException ignored) {
                // not this one
            }
        }
        return null;
    }

    /**
     * A reply request, opened as one for this conversation, or for one this phone itself merged into it (a reply
     * written just before the merge). Null when it opens as neither.
     */
    private JSONObject openRequest(String sealed, String wire, String local) {
        java.util.List<String> aads = new java.util.ArrayList<>();
        aads.add(Crypto.aadOut(wire));
        if (local != null) for (String c : mergedInto.apply(local)) aads.add(Crypto.aadOut(link.convId(c)));
        for (String aad : aads) {
            try {
                return link.open(sealed, aad);
            } catch (GeneralSecurityException ignored) {
                // not this one
            }
        }
        return null;
    }

    /** What became of a reply: the inbox gets its code, never the words (each device has its own, NotSent). */
    @Override
    JSONObject outboxResult(JSONObject body) throws IOException {
        JSONObject b = new JSONObject();
        for (Iterator<String> k = body.keys(); k.hasNext(); ) {
            String name = k.next();
            if (!name.equals("error")) put(b, name, body.opt(name));
        }
        return post("/v2/relay/outbox/result", b);
    }

    /**
     * The relay phone's check-in. Its own words about how it's doing (problems, why the history copy paused) can name
     * people, files or messages, so they go sealed, with its model; the rest is numbers and switches the server watches.
     */
    @Override
    JSONObject heartbeat(JSONObject body) throws IOException {
        JSONObject b = new JSONObject();
        for (Iterator<String> k = body.keys(); k.hasNext(); ) {
            String name = k.next();
            // v1's phone tag stays home: a private inbox knows a reinstalled phone by its wrap instead. The phone's own
            // words and its model go sealed (devices show only what they open).
            if (!name.equals("device") && !name.equals("note") && !name.equals("store") && !name.equals("model")) put(b, name, body.opt(name));
        }
        String historyNote = "";
        JSONObject store = body.optJSONObject("store");
        if (store != null) {
            JSONObject s = new JSONObject();
            for (Iterator<String> k = store.keys(); k.hasNext(); ) {
                String name = k.next();
                if (!name.equals("history")) put(s, name, store.opt(name));
            }
            JSONObject h = store.optJSONObject("history");
            if (h != null) {
                JSONObject plain = new JSONObject();
                for (Iterator<String> k = h.keys(); k.hasNext(); ) {
                    String name = k.next();
                    if (name.equals("note")) historyNote = h.optString(name, "");
                    else put(plain, name, h.opt(name));
                }
                put(s, "history", plain);
            }
            put(b, "store", s);
        }
        put(b, "sn", link.seal(Json.obj("note", body.optString("note", ""), "history", historyNote, "model", body.optString("model", "")), Crypto.AAD_NOTE));
        return post("/v2/relay/heartbeat", b);
    }

    @Override
    JSONObject addPairingCode(String hash, String role, int ttlMinutes) throws IOException {
        throw new ApiException(400, "a private inbox links devices with an invite");
    }

    /** Seals the file and stores it; returns the path the inbox keeps it under. */
    @Override
    String uploadMedia(String conv, String fp, long ts, String kind, String mime, byte[] bytes) throws IOException {
        String wconv = link.convId(conv);
        String wfp = link.fpId(fp);
        byte[] sealed = link.sealFile(bytes, Crypto.aadFile(wconv, ts, wfp, kind));
        if (sealed.length <= DIRECT_MAX) {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("x-relay-conv", wconv);
            headers.put("x-relay-fp", wfp);
            headers.put("x-relay-ts", Long.toString(ts));
            headers.put("x-relay-kind", kind);
            JSONObject r = execute("POST", base + "/v2/media/put", headers, "application/octet-stream", sealed);
            String path = r.optString("path", "");
            if (path.isEmpty()) throw new ApiException(502, "the server did not say where it stored the file");
            return path;
        }
        JSONObject t = post("/v2/media/ticket", Json.obj("conv", wconv, "fp", wfp, "ts", ts, "bytes", sealed.length, "kind", kind));
        String uploadUrl = t.optString("uploadUrl", "");
        String ticket = t.optString("ticket", "");
        String path = t.optString("path", "");
        if (uploadUrl.isEmpty() || ticket.isEmpty() || path.isEmpty()) throw new ApiException(502, "the server did not issue an upload ticket");
        Map<String, String> headers = new LinkedHashMap<>();
        JSONObject given = t.optJSONObject("headers");
        String type = "application/zip";
        if (given != null) {
            for (Iterator<String> it = given.keys(); it.hasNext(); ) {
                String name = it.next();
                if (name.equalsIgnoreCase("content-type")) type = given.optString(name);
                else headers.put(name, given.optString(name));
            }
        }
        execute("PUT", uploadUrl, headers, type, sealed);
        JSONObject done = post("/v2/media/complete", Json.obj("ticket", ticket, "path", path));
        String stored = done.optString("path", path);
        return stored.isEmpty() ? path : stored;
    }

    // ------------------------------------------------------------------ the private inbox's own calls

    /** A single-use ticket for this phone's realtime channel. */
    JSONObject ticket() throws IOException {
        return get("/v2/ticket");
    }

    /** Lets another device into this inbox with the code it will scan: files the invite under the code's lookup. */
    JSONObject invite(Crypto.LinkSecrets s, String role) throws IOException {
        return post("/v2/link/invite", Json.obj("lookup", s.lookup, "box", link.box(s.boxKey), "role", role));
    }

    /** "open", "done" (a device used it) or "expired". */
    String linkStatus(String lookup) throws IOException {
        return post("/v2/link/status", Json.obj("lookup", lookup)).optString("state", "expired");
    }

    JSONObject wrap(Crypto.PhoneSecrets p) throws IOException {
        return post("/v2/wrap", Json.obj("tag", p.tag, "wrap", link.wrap(p.wrapKey)));
    }

    JSONObject leave() throws IOException {
        return post("/v2/leave", new JSONObject());
    }

    JSONObject devices() throws IOException {
        return get("/v2/devices");
    }

    // ------------------------------------------------------------------ before this phone is in an inbox

    /** Files this phone's code: whoever scans it brings this phone into their inbox, or starts one with it. */
    static void ask(String server, Crypto.LinkSecrets s, String token) throws IOException {
        // No name for the phone: the server would keep it in the open. Devices list it as the relay phone, with the
        // model it seals in its status.
        new Hedy(server, null).post("/v2/link/ask", Json.obj("lookup", s.lookup, "token", token, "role", "relay"));
    }

    /**
     * Whether the code has been scanned yet: null while it hasn't, the link once it has. Throws an ApiException with
     * code 410 once the code has expired.
     */
    static Link waitForScan(String server, Crypto.LinkSecrets s, String token) throws IOException {
        JSONObject r = new Hedy(server, null).post("/v2/link/wait", Json.obj("lookup", s.lookup, "token", token));
        if (r.optBoolean("waiting") || !r.has("answer")) return null;
        try {
            JSONObject box = Crypto.openJson(s.boxKey, r.optString("answer"), Crypto.AAD_BOX);
            Link l = Link.parse(Json.obj("server", server, "tid", box.optString("tid"), "token", token, "key", box.optString("key")).toString());
            if (l == null || !l.tid.equals(r.optString("tid"))) throw new ApiException(502, "the answer didn't hold a whole inbox");
            return l;
        } catch (GeneralSecurityException e) {
            throw new ApiException(502, "the answer to this code couldn't be opened");
        }
    }

    /** A reinstalled Relay on a phone that was linked: its wrap, opened. Null when the server doesn't know this phone. */
    static Link recover(String server, Crypto.PhoneSecrets p) throws IOException {
        JSONObject r;
        try {
            r = new Hedy(server, null).post("/v2/link/recover", Json.obj("tag", p.tag));
        } catch (ApiException e) {
            if (e.code == 404) return null;
            throw e;
        }
        try {
            return Link.unwrap(server, p.wrapKey, r.optString("wrap"));
        } catch (GeneralSecurityException e) {
            return null;
        }
    }

    private static void put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (org.json.JSONException ignored) {
            // literal keys, plain values
        }
    }

    static String utf8(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }
}
