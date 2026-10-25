package com.textrelay.relay;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The few calls the relay makes to its inbox server, over plain HTTPS. This is the first version's inbox, with a device
 * key; Hedy2 speaks to a private inbox (v2), sealing everything readable on the way out.
 */
class Hedy {
    /** Files up to this size go to the server in one request; larger ones go through an upload ticket. */
    static final int DIRECT_MAX = 4 * 1024 * 1024;

    static final class ApiException extends IOException {
        final int code;
        /**
         * This phone's own words for what went wrong, without the status code. Never the server's: what a server
         * writes in a refusal is dropped, since this phone's screens and the note it seals for your other devices
         * would otherwise show it as this phone's.
         */
        final String reason;

        /** The server says this device isn't (or is no longer) in an inbox. */
        final boolean unlinked;

        ApiException(int code, String message) {
            this(code, message, false);
        }

        ApiException(int code, String message, boolean unlinked) {
            super(code + ": " + message);
            this.code = code;
            this.reason = message;
            this.unlinked = unlinked;
        }

        boolean keyRejected() {
            return code == 401 || code == 403;
        }

        /**
         * The server looked at the request and won't ever take it (bad request, too large, unsupported type). Anything
         * else, a 404, 408 or 5xx from the platform in front of it included, is worth trying again.
         */
        boolean refused() {
            return code == 400 || code == 413 || code == 415 || code == 422;
        }

        /** What this phone says about a refusal with this status. */
        static String words(int code) {
            if (code == 401 || code == 403) return "the inbox didn't accept this phone";
            if (code == 404) return "the inbox didn't know what this phone asked for";
            if (code == 408 || code == 429) return "the inbox was too busy just then";
            if (code == 409) return "the inbox had a newer change";
            if (code == 410) return "that has expired";
            if (code == 413) return "too large for the inbox";
            if (code == 415) return "a type the inbox doesn't take";
            if (code == 400 || code == 422) return "the inbox refused the request";
            if (code >= 500) return "the inbox server had a problem";
            return "the inbox answered " + code;
        }
    }

    protected final String base;
    private final String key;

    Hedy(String base, String key) {
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.key = key;
    }

    /** Registers the hash of a one-time code this phone made, so a phone running Texts can link with it once. */
    JSONObject addPairingCode(String hash, String role, int ttlMinutes) throws IOException {
        return post("/api/pair/codes", Json.obj("hash", hash, "role", role, "ttlMinutes", ttlMinutes));
    }

    /** Which conversations already hold these messages (by time, direction and text): one list per probe. */
    JSONObject locate(org.json.JSONArray probes) throws IOException {
        return post("/api/relay/locate", Json.obj("probes", probes));
    }

    /** Two conversations are one: the inbox moves everything from `from` into `into`. */
    JSONObject merge(String from, String into) throws IOException {
        return post("/api/relay/merge", Json.obj("from", from, "into", into));
    }

    JSONObject ping() throws IOException {
        return get("/api/ping");
    }

    JSONObject ingest(JSONObject body) throws IOException {
        return post("/api/relay/ingest", body);
    }

    JSONObject outbox() throws IOException {
        return get("/api/relay/outbox");
    }

    JSONObject outboxResult(JSONObject body) throws IOException {
        return post("/api/relay/outbox/result", body);
    }

    JSONObject heartbeat(JSONObject body) throws IOException {
        return post("/api/relay/heartbeat", body);
    }

    /**
     * Stores one attachment file (an original or a preview) and returns the path the inbox serves it from.
     * Small files go up in one request; large ones get an upload ticket, go straight to storage, then are confirmed.
     */
    String uploadMedia(String conv, String fp, long ts, String kind, String mime, byte[] bytes) throws IOException {
        if (bytes.length <= DIRECT_MAX) {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("x-relay-conv", conv);
            headers.put("x-relay-fp", fp);
            headers.put("x-relay-ts", Long.toString(ts));
            headers.put("x-relay-kind", kind);
            JSONObject r = execute("POST", base + "/api/relay/media", headers, mime, bytes);
            return storedPath(r, null);
        }
        JSONObject t = post("/api/relay/media/ticket",
                Json.obj("conv", conv, "fp", fp, "ts", ts, "mime", mime, "bytes", bytes.length, "kind", kind));
        String uploadUrl = t.optString("uploadUrl", "");
        String ticket = t.optString("ticket", "");
        String path = t.optString("path", "");
        if (uploadUrl.isEmpty() || ticket.isEmpty() || path.isEmpty()) throw new ApiException(502, "the server did not issue an upload ticket");
        Map<String, String> headers = new LinkedHashMap<>();
        JSONObject given = t.optJSONObject("headers");
        if (given != null) {
            for (Iterator<String> it = given.keys(); it.hasNext(); ) {
                String name = it.next();
                headers.put(name, given.optString(name));
            }
        }
        execute("PUT", uploadUrl, headers, mime, bytes);
        JSONObject done = post("/api/relay/media/complete", Json.obj("ticket", ticket, "path", path));
        return storedPath(done, path);
    }

    private static String storedPath(JSONObject r, String fallback) throws ApiException {
        String path = r.optString("path", fallback != null ? fallback : "");
        if (path.isEmpty()) throw new ApiException(502, "the server did not say where it stored the file");
        return path;
    }

    /** How this client proves which device it is, on requests to its own server (never to a storage URL). */
    protected void authorize(HttpURLConnection c) {
        if (key != null && !key.isEmpty()) c.setRequestProperty("x-hedy-app-key", key);
    }

    JSONObject get(String path) throws IOException {
        return execute("GET", base + path, null, null, null);
    }

    JSONObject post(String path, JSONObject body) throws IOException {
        return execute("POST", base + path, null, "application/json; charset=utf-8", body.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** One HTTP exchange. The device key only ever goes to the inbox server itself, never to a storage URL. */
    protected JSONObject execute(String method, String url, Map<String, String> headers, String contentType, byte[] body) throws IOException {
        HttpURLConnection c = Tor.open(new URL(url), 15_000, 60_000);
        try {
            c.setRequestMethod(method);
            c.setRequestProperty("accept", "application/json");
            boolean ownServer = url.startsWith(base + "/");
            if (ownServer) authorize(c);
            if (headers != null) {
                for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
            }
            if (body != null) {
                c.setDoOutput(true);
                if (contentType != null) c.setRequestProperty("content-type", contentType);
                c.setFixedLengthStreamingMode(body.length);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(body);
                }
            }
            int status = c.getResponseCode();
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = in == null ? "" : readAll(in);
            if (status < 200 || status >= 300) {
                throw new ApiException(status, ApiException.words(status), (status == 401 || status == 403) && saysUnlinked(text));
            }
            try {
                return text.isEmpty() || !text.startsWith("{") ? new JSONObject() : new JSONObject(text);
            } catch (JSONException e) {
                throw new ApiException(status, "unexpected reply from the server");
            }
        } finally {
            c.disconnect();
        }
    }

    private static boolean saysUnlinked(String text) {
        try {
            return new JSONObject(text).optBoolean("unlinked", false);
        } catch (JSONException e) {
            return false;
        }
    }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }
}
