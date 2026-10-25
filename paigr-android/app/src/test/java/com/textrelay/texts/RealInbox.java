package com.textrelay.texts;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * The real inbox server (relay-hedy's functions/inbox2.js) on this machine, with a private inbox in it, set up the way
 * two phones would and filled by a stand-in relay phone (test/seed-native.js). It runs as the hosted platform's stand-in
 * (test/serve.js), or as the self-hosted server people run themselves (server/paigr-server.js, realtime socket and
 * all). Tests that need it are skipped where Node or the relay-hedy checkout isn't there.
 */
final class RealInbox implements AutoCloseable {
    final String origin;
    final JSONObject link;
    private final Process server;

    static File home() {
        String p = System.getProperty("relay.hedy");
        File[] tries = { p != null ? new File(p) : null, new File("../inbox"), new File("../../inbox"), new File("../../relay-hedy"), new File("../relay-hedy"), new File("/home/claude/relay-hedy") };
        for (File f : tries) if (f != null && new File(f, "test/serve.js").isFile()) return f;
        return null;
    }

    /** The platform's stand-in, test/serve.js. */
    static final String STAND_IN = "stand-in";
    /** The self-hosted server, server/paigr-server.js. */
    static final String SELF_HOSTED = "self-hosted";

    private static String script(String kind) {
        return SELF_HOSTED.equals(kind) ? "server/test/serve-selfhost.js" : "test/serve.js";
    }

    static boolean available() {
        return available(STAND_IN);
    }

    static boolean available(String kind) {
        if (home() == null || !new File(home(), script(kind)).isFile()) return false;
        try {
            Process p = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    RealInbox(String scenario) throws Exception {
        this(scenario, STAND_IN);
    }

    RealInbox(String scenario, String kind) throws Exception {
        File home = home();
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        origin = "http://127.0.0.1:" + port;
        server = new ProcessBuilder("node", new File(home, script(kind)).getAbsolutePath(), String.valueOf(port))
                .redirectErrorStream(true).redirectOutput(new File("build/test-server.log")).start();
        long until = System.currentTimeMillis() + 15_000;
        while (true) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(origin + "/index.html").openConnection();
                c.setConnectTimeout(500);
                if (c.getResponseCode() == 200) break;
            } catch (Exception e) {
                if (System.currentTimeMillis() > until) throw new IllegalStateException("the test server didn't start", e);
                Thread.sleep(100);
            }
        }
        Process seed = new ProcessBuilder("node", new File(home, "test/seed-native.js").getAbsolutePath(), origin, scenario)
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        String line;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(seed.getInputStream(), StandardCharsets.UTF_8))) {
            line = r.readLine();
        }
        if (!seed.waitFor(60, TimeUnit.SECONDS) || seed.exitValue() != 0 || line == null) throw new IllegalStateException("seeding failed");
        link = new JSONObject(line);
    }

    /** This inbox, as the Texts device's link. */
    Link texts() {
        return Link.parse(link.toString(), origin);
    }

    /** One call to the server, as whoever `token` is ("" for nobody); the reply as JSON, its status in "_status". */
    JSONObject call(String method, String path, String token, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(origin + path).openConnection();
        try {
            c.setRequestMethod(method);
            c.setRequestProperty("accept", "application/json");
            if (token != null && !token.isEmpty()) c.setRequestProperty("x-relay-token", token);
            if (body != null) {
                byte[] b = body.toString().getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setRequestProperty("content-type", "application/json");
                try (java.io.OutputStream out = c.getOutputStream()) {
                    out.write(b);
                }
            }
            int status = c.getResponseCode();
            java.io.InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = "";
            if (in != null) try (java.util.Scanner s = new java.util.Scanner(in, "UTF-8").useDelimiter("\\A")) {
                text = s.hasNext() ? s.next() : "";
            }
            JSONObject o = text.trim().startsWith("{") ? new JSONObject(text) : new JSONObject();
            return o.put("_status", status);
        } finally {
            c.disconnect();
        }
    }

    /** What the server holds (its test-only /_debug/dump). */
    JSONObject dump() throws Exception {
        return call("GET", "/_debug/dump", "", null);
    }

    @Override
    public void close() {
        server.destroy();
        try {
            server.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
