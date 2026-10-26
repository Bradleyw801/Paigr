package com.textrelay.texts;

import android.util.Log;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * A websocket to the inbox server's realtime channel. The server rings it when something is waiting
 * (payloads carry keys, never message content), so the poll loop can fetch right away instead of waiting
 * out its interval. Reconnects on its own; while it is down the poll loop simply runs faster.
 */
final class Doorbell {
    interface Listener {
        void onRing(String type, JSONObject payload);
        void onStateChange(boolean connected);
    }

    private static final String TAG = "Doorbell";
    private static final int PING_SECONDS = 75;             // a dead socket shows within three minutes, not eight
    private static final long REFRESH_MS = 100 * 60_000L;   // reconnect before the 2-hour connection ceiling

    private final String base;
    private final String channel;
    private final Tickets tickets;

    /** Where a connection's single-use ticket comes from: the public endpoint (v1), or the private inbox (v2). */
    interface Tickets {
        JSONObject ticket() throws IOException, JSONException;
    }
    private final Listener listener;
    private volatile boolean running;
    private volatile boolean connected;
    private volatile WebSocketClient client;
    private Thread thread;

    Doorbell(String base, String channel, Listener listener) {
        this(base, channel, listener, null);
    }

    Doorbell(String base, String channel, Listener listener, Tickets tickets) {
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.channel = channel;
        this.listener = listener;
        this.tickets = tickets;
    }

    boolean isConnected() {
        return connected;
    }

    synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::loop, "doorbell-" + channel);
        thread.setDaemon(true);
        thread.start();
    }

    synchronized void stop() {
        running = false;
        WebSocketClient c = client;
        if (c != null) c.close();
        Thread t = thread;
        if (t != null) t.interrupt();
        thread = null;
    }

    /** Drops the current connection so the loop opens a fresh one (after a network change, for instance). */
    void reconnect() {
        WebSocketClient c = client;
        if (c != null) c.close();
    }

    private void loop() {
        long backoff = 2_000;
        while (running) {
            long openedAt = 0;
            try {
                JSONObject ticket = tickets != null ? tickets.ticket() : ticket();
                String url = ticket.getString("webSocketUrl") + "?ticket=" + ticket.getString("ticket");
                Map<String, String> headers = new HashMap<>();
                headers.put("Origin", base);
                URI uri = URI.create(url);
                Client c = new Client(uri, headers);
                c.setConnectionLostTimeout(PING_SECONDS);
                if (Tor.through(uri.getHost())) c.setSocket(Tor.socket(uri, Tor.SLOW_MS));   // through Tor, never direct
                client = c;
                if (c.connectBlocking() && running) {
                    openedAt = System.currentTimeMillis();
                    c.awaitClose(REFRESH_MS);
                    c.close();
                }
            } catch (IOException | JSONException | RuntimeException e) {
                Log.w(TAG, "doorbell " + channel + ": " + e.getMessage());
            } catch (InterruptedException e) {
                break;
            } finally {
                client = null;
                setConnected(false);
            }
            if (!running) break;
            long lived = openedAt == 0 ? 0 : System.currentTimeMillis() - openedAt;
            if (lived > 60_000) backoff = 2_000;
            try {
                Thread.sleep(lived > 60_000 ? 1_000 : backoff);
            } catch (InterruptedException e) {
                break;
            }
            backoff = Math.min(backoff * 2, 60_000);
        }
    }

    private void setConnected(boolean value) {
        if (connected == value) return;
        connected = value;
        try {
            listener.onStateChange(value);
        } catch (RuntimeException e) {
            Log.w(TAG, "listener failed", e);
        }
    }

    /** One single-use ticket for the channel, from the public ticket endpoint. */
    private JSONObject ticket() throws IOException, JSONException {
        HttpURLConnection c = Tor.open(new URL(base + "/__hedy/realtime/tickets"), 15_000, 20_000);
        try {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("content-type", "application/json");
            c.setRequestProperty("accept", "application/json");
            c.setRequestProperty("origin", base);
            byte[] body = new JSONObject().put("channel", channel).put("capabilities", new org.json.JSONArray().put("Subscribe")).toString().getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = c.getOutputStream()) {
                out.write(body);
            }
            int status = c.getResponseCode();
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = in == null ? "" : readAll(in);
            if (status < 200 || status >= 300) throw new IOException("ticket refused: HTTP " + status);
            return new JSONObject(text);
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    private final class Client extends WebSocketClient {
        private final Object closed = new Object();
        private boolean isClosed;

        Client(URI uri, Map<String, String> headers) {
            super(uri, headers);
        }

        @Override
        public void onOpen(ServerHandshake handshake) {
            setConnected(true);
        }

        @Override
        public void onMessage(String message) {
            try {
                JSONObject m = new JSONObject(message);
                JSONObject payload = new JSONObject();
                String raw = m.optString("payloadJson", "");
                if (!raw.isEmpty()) payload = new JSONObject(raw);
                listener.onRing(m.optString("type", ""), payload);
            } catch (JSONException | RuntimeException e) {
                Log.w(TAG, "bad event", e);
            }
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            synchronized (closed) {
                isClosed = true;
                closed.notifyAll();
            }
        }

        @Override
        public void onError(Exception ex) {
            Log.w(TAG, "socket error: " + ex.getMessage());
        }

        void awaitClose(long maxMs) throws InterruptedException {
            long until = System.currentTimeMillis() + maxMs;
            synchronized (closed) {
                while (!isClosed && running) {
                    long left = until - System.currentTimeMillis();
                    if (left <= 0) return;
                    closed.wait(Math.min(left, 30_000));
                }
            }
        }
    }
}
