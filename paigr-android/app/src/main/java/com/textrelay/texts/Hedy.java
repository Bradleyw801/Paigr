package com.textrelay.texts;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * The inbox server, over HTTPS (or Tor, see Tor): the private inbox (v2) with this device's token, where /api/… is
 * /v2/…, or the first version's with its device key.
 */
final class Hedy {
    static final class ApiException extends IOException {
        final int code;
        /** The private inbox says this device isn't (or is no longer) in it. */
        final boolean unlinked;
        /** What the inbox said, when it's one of the sentences this app knows (SERVER_SAYS); otherwise "". */
        final String said;

        ApiException(int code, String message) {
            this(code, message, false, "");
        }

        ApiException(int code, String message, boolean unlinked) {
            this(code, message, unlinked, "");
        }

        ApiException(int code, String message, boolean unlinked, String said) {
            super(code + ": " + message);
            this.code = code;
            this.unlinked = unlinked;
            this.said = said == null ? "" : said;
        }

        boolean keyRejected() {
            return code == 401 || code == 403;
        }

        /** This app's own words for a refusal with this status: what a server writes in one is never kept. */
        static String words(int code) {
            if (code == 401 || code == 403) return "the inbox didn't accept this phone";
            if (code == 404) return "the inbox didn't know what this phone asked for";
            if (code == 408 || code == 429) return "the inbox was too busy just then";
            if (code == 400 || code == 422) return "the inbox refused the request";
            if (code >= 500) return "the inbox server had a problem";
            return "the inbox answered " + code;
        }
    }

    private final String base;
    private final String key;
    private final Link link;

    Hedy(String base, String key) {
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.key = key;
        this.link = null;
    }

    Hedy(Link link) {
        this.base = link.server;
        this.key = null;
        this.link = link;
    }

    JSONObject get(String path) throws IOException {
        return exchange("GET", path, null);
    }

    JSONObject post(String path, JSONObject body) throws IOException {
        return exchange("POST", path, body);
    }

    private JSONObject exchange(String method, String path, JSONObject body) throws IOException {
        return exchange(method, path, body != null ? body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8) : null,
                "application/json; charset=utf-8", null);
    }

    /** Bytes to the inbox (a sealed file, /v2/media/put), with the headers that say which message's they are. */
    JSONObject postBytes(String path, byte[] bytes, java.util.Map<String, String> headers) throws IOException {
        return exchange("POST", path, bytes, "application/octet-stream", headers);
    }

    /**
     * A sealed file of up to 25 MB to the inbox: in the request itself when small, else by an upload ticket (the
     * storage's own address, told to the inbox when done). Returns the path it was stored at.
     */
    String upload(String conv, String fp, long ts, String kind, byte[] sealed) throws IOException {
        if (sealed.length <= DIRECT_MAX) {
            java.util.Map<String, String> h = new java.util.HashMap<>();
            h.put("x-relay-conv", conv);
            h.put("x-relay-fp", fp);
            h.put("x-relay-ts", String.valueOf(ts));
            h.put("x-relay-kind", kind);
            return postBytes("/v2/media/put", sealed, h).optString("path", "");
        }
        JSONObject t;
        try {
            t = post("/v2/media/ticket", new JSONObject().put("conv", conv).put("fp", fp).put("ts", ts).put("bytes", sealed.length).put("kind", kind));
        } catch (JSONException e) {
            throw new IOException(e);
        }
        String url = t.optString("uploadUrl", "");
        if (!url.startsWith("https://") && !url.startsWith(base + "/")) throw new IOException("The inbox named an upload address this app doesn't use.");
        HttpURLConnection c = Tor.open(new URL(url), 15_000, 120_000);
        try {
            c.setRequestMethod("PUT");
            c.setDoOutput(true);
            JSONObject th = t.optJSONObject("headers");
            if (th != null) for (java.util.Iterator<String> k = th.keys(); k.hasNext(); ) {
                String name = k.next();
                c.setRequestProperty(name, th.optString(name));
            }
            if (th == null || !th.has("content-type")) c.setRequestProperty("content-type", "application/zip");
            c.setFixedLengthStreamingMode(sealed.length);
            try (java.io.OutputStream out = c.getOutputStream()) {
                out.write(sealed);
            }
            int status = c.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException("the upload was refused (" + status + ")");
        } finally {
            c.disconnect();
        }
        try {
            JSONObject done = post("/v2/media/complete", new JSONObject().put("ticket", t.optString("ticket")).put("path", t.optString("path")));
            return done.optString("path", t.optString("path", ""));
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    /** The most the inbox takes in one request (inbox2.js DIRECT_MAX, less a margin). */
    static final int DIRECT_MAX = 4 * 1024 * 1024;

    private JSONObject exchange(String method, String path, byte[] bytes, String contentType, java.util.Map<String, String> headers) throws IOException {
        String p = link != null && path.startsWith("/api/") ? "/v2/" + path.substring(5) : path;
        HttpURLConnection c = Tor.open(new URL(base + p), 15_000, bytes != null && bytes.length > 65536 ? 120_000 : 30_000);
        try {
            c.setRequestMethod(method);
            c.setRequestProperty("accept", "application/json");
            if (link != null) c.setRequestProperty("x-relay-token", link.token);
            else if (key != null && !key.isEmpty()) c.setRequestProperty("x-hedy-app-key", key);
            if (headers != null) for (java.util.Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
            if (bytes != null) {
                c.setDoOutput(true);
                c.setRequestProperty("content-type", contentType);
                c.setFixedLengthStreamingMode(bytes.length);
                try (java.io.OutputStream out = c.getOutputStream()) {
                    out.write(bytes);
                }
            }
            int status = c.getResponseCode();
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = in == null ? "" : readAll(in);
            if (status < 200 || status >= 300) throw new ApiException(status, ApiException.words(status), (status == 401 || status == 403) && saysUnlinked(text), knownWords(text));
            try {
                return text.isEmpty() ? new JSONObject() : new JSONObject(text);
            } catch (JSONException e) {
                throw new ApiException(status, "unexpected reply from the server");
            }
        } finally {
            c.disconnect();
        }
    }

    /**
     * What the inbox said when it refused something, in words this app knows (web/app.js has the same list): anything
     * else the server writes is never shown, since it could say anything, like where to "fix" your inbox.
     */
    static final java.util.Set<String> SERVER_SAYS = new java.util.HashSet<>(java.util.Arrays.asList(
            "A new inbox starts from the code in Relay, on the phone with the SIM.",
            "A relay phone hands over by showing its own code to Texts.",
            "Link this phone first.",
            "No device has turned on notifications yet.",
            "No such device in this inbox.",
            "Push is not enabled for this app.",
            "That code is taken. Make a new one.",
            "This code belongs to another device.",
            "This code has expired or was already used. Make a new one.",
            "This code has expired. Make a new one.",
            "This code was just used. Make a new one.",
            "This device is already linked to another inbox.",
            "This device is already linked.",
            "This device isn't linked to an inbox.",
            "This inbox already has 8 devices. Remove one in Settings first.",
            "Try again."));

    /** Two of those sentences name the apps as they were first called; this app says them with today's names. */
    static final java.util.Map<String, String> OWN_WORDS = new java.util.HashMap<>();
    static {
        OWN_WORDS.put("A new inbox starts from the code in Relay, on the phone with the SIM.",
                "A new inbox starts from the code in Paigr Relay, on the phone with the SIM.");
        OWN_WORDS.put("A relay phone hands over by showing its own code to Texts.",
                "A relay phone hands over by showing its own code to Paigr.");
    }

    static String knownWords(String text) {
        try {
            JSONObject o = new JSONObject(text);
            Object e = o.opt("error");
            String said = e instanceof String ? (String) e : e instanceof JSONObject ? ((JSONObject) e).optString("message", "") : "";
            return SERVER_SAYS.contains(said) ? OWN_WORDS.getOrDefault(said, said) : "";
        } catch (JSONException | RuntimeException e) {
            return "";
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
