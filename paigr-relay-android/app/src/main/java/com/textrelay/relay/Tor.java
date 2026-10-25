package com.textrelay.relay;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Tor, for this app's connections to its inbox server: through Orbot (or another Tor app with its SOCKS port at
 * 127.0.0.1:9050) when "Connect through Tor" is on, which it always is for a .onion server.
 *
 * Tor looks up every name; this phone never does, so its DNS doesn't learn the server either. When Tor isn't running,
 * nothing goes out at all: a request fails rather than going direct. Requests reach Tor through a small proxy on this
 * phone (Gate), so that HttpURLConnection keeps doing the HTTP and the TLS checks; the realtime socket goes to Tor
 * itself (socket()).
 */
final class Tor {
    static final String ORBOT = "org.torproject.android";
    static final String PREF = "tor";
    static final String NOT_RUNNING = "Tor isn't running. Start Orbot to reach the inbox.";
    /** Tor keeps each app's connections on circuits of their own when they name themselves like this. */
    private static final String USER = "paigr-relay";
    private static final InetAddress LOOPBACK;
    static {
        try {
            LOOPBACK = InetAddress.getByAddress("localhost", new byte[] { 127, 0, 0, 1 });
        } catch (UnknownHostException e) {
            throw new AssertionError(e);
        }
    }

    /** Where Tor's SOCKS port is (tests use their own). */
    static volatile int socksPort = 9050;
    private static volatile SharedPreferences prefs;

    /** Tor isn't there to carry a request, in this app's words. */
    static final class Down extends IOException {
        Down(String message) {
            super(message);
        }
    }

    private Tor() {}

    static void init(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences("relay", Context.MODE_PRIVATE);
    }

    /** Whether "Connect through Tor" is on. Before the app has read its settings, it is: nothing goes out direct by mistake. */
    static boolean wanted() {
        SharedPreferences p = prefs;
        return p == null || p.getBoolean(PREF, false);
    }

    static boolean isOnion(String hostOrUrl) {
        String h = hostOrUrl == null ? "" : hostOrUrl.trim().toLowerCase(Locale.US);
        int scheme = h.indexOf("://");
        if (scheme >= 0) h = h.substring(scheme + 3);
        int end = h.length();
        for (char c : new char[] { '/', ':', '?', '#' }) {
            int i = h.indexOf(c);
            if (i >= 0 && i < end) end = i;
        }
        h = h.substring(0, end);
        return h.endsWith(".onion") && h.length() > ".onion".length();
    }

    /** Whether a connection to this host goes through Tor. */
    static boolean through(String host) {
        return isOnion(host) || wanted();
    }

    /**
     * A connection to url: through Tor when that's how this app connects, otherwise as any other. Through Tor, the
     * first connection to an onion server can take most of a minute, so it waits longer for an answer.
     */
    static HttpURLConnection open(URL url, int connectMs, int readMs) throws IOException {
        HttpURLConnection c;
        if (!through(url.getHost())) {
            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(connectMs);
            c.setReadTimeout(readMs);
            return c;
        }
        check();
        c = (HttpURLConnection) url.openConnection(Gate.proxy());
        c.setConnectTimeout(Math.max(connectMs, 30_000));
        c.setReadTimeout(Math.max(readMs, SLOW_MS));
        return c;
    }

    /** How long a request through Tor waits for its answer: Tor itself gives up on a connection after two minutes. */
    static final int SLOW_MS = 120_000;

    /** Whether Tor's SOCKS port answers on this phone. */
    static boolean running() {
        try (Socket s = new Socket(Proxy.NO_PROXY)) {
            s.connect(new InetSocketAddress(LOOPBACK, socksPort), 1500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** When Tor last answered: a request soon after doesn't ask again. */
    private static volatile long answeredAt;

    private static void check() throws Down {
        if (System.currentTimeMillis() - answeredAt < 5000) return;
        if (!running()) throw new Down(NOT_RUNNING);
        answeredAt = System.currentTimeMillis();
    }

    /** A socket for a realtime connection (ws:// or wss://): through Tor, and for wss checked to be that server's. */
    static Socket socket(URI uri, int timeoutMs) throws IOException {
        boolean tls = "wss".equalsIgnoreCase(uri.getScheme());
        int port = uri.getPort() > 0 ? uri.getPort() : tls ? 443 : 80;
        String host = uri.getHost();
        check();
        Socket raw = connect(host, port, timeoutMs);
        if (!tls) return raw;
        boolean ok = false;
        try {
            SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(raw, host, port, true);
            SSLParameters p = ssl.getSSLParameters();
            p.setEndpointIdentificationAlgorithm("HTTPS");
            ssl.setSSLParameters(p);
            ssl.setSoTimeout(timeoutMs);
            ssl.startHandshake();
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.getSession())) throw new SSLPeerUnverifiedException(host);
            ssl.setSoTimeout(0);
            ok = true;
            return ssl;
        } finally {
            if (!ok) close(raw);
        }
    }

    /**
     * A connection to host:port through Tor's SOCKS port (SOCKS5, the name given to Tor to look up). Throws Down when
     * Tor isn't there, and a plain IOException when Tor couldn't reach the server.
     */
    static Socket connect(String host, int port, int timeoutMs) throws IOException {
        byte[] name = host == null ? new byte[0] : host.getBytes(StandardCharsets.US_ASCII);
        if (name.length == 0 || name.length > 255 || port <= 0 || port > 65535) throw new IOException("no such address");
        Socket s = new Socket(Proxy.NO_PROXY);
        boolean ok = false;
        try {
            try {
                s.connect(new InetSocketAddress(LOOPBACK, socksPort), Math.min(timeoutMs, 5000));
            } catch (IOException e) {
                answeredAt = 0;
                throw new Down(NOT_RUNNING);
            }
            s.setSoTimeout(timeoutMs);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            out.write(new byte[] { 5, 2, 0, 2 });   // SOCKS5: no login, or a name and password
            out.flush();
            byte[] r = read(in, 2);
            if (r[0] != 5) throw new IOException("not Tor's SOCKS port");
            if (r[1] == 2) {
                byte[] user = USER.getBytes(StandardCharsets.US_ASCII);
                ByteArrayOutputStream login = new ByteArrayOutputStream();
                login.write(1);
                login.write(user.length);
                login.write(user, 0, user.length);
                login.write(user.length);
                login.write(user, 0, user.length);
                out.write(login.toByteArray());
                out.flush();
                if (read(in, 2)[1] != 0) throw new IOException("Tor turned this app away");
            } else if (r[1] != 0) {
                throw new IOException("Tor turned this app away");
            }
            ByteArrayOutputStream ask = new ByteArrayOutputStream();
            ask.write(new byte[] { 5, 1, 0, 3 }, 0, 4);   // CONNECT to a name
            ask.write(name.length);
            ask.write(name, 0, name.length);
            ask.write(port >> 8);
            ask.write(port & 255);
            out.write(ask.toByteArray());
            out.flush();
            byte[] head = read(in, 4);
            if (head[0] != 5) throw new IOException("not Tor's SOCKS port");
            if (head[1] != 0) throw new IOException("Tor couldn't reach " + host + " (" + (head[1] & 255) + ")");
            int rest = head[3] == 1 ? 4 : head[3] == 4 ? 16 : head[3] == 3 ? (read(in, 1)[0] & 255) : -1;
            if (rest < 0) throw new IOException("not Tor's SOCKS port");
            read(in, rest + 2);
            s.setSoTimeout(0);
            ok = true;
            return s;
        } finally {
            if (!ok) close(s);
        }
    }

    private static byte[] read(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        int at = 0;
        while (at < n) {
            int got = in.read(b, at, n - at);
            if (got < 0) throw new IOException("Tor closed the connection");
            at += got;
        }
        return b;
    }

    static void close(Socket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (IOException ignored) {
            // closed already
        }
    }

    // ------------------------------------------------------------------ Orbot

    static boolean orbotInstalled(Context c) {
        try {
            c.getPackageManager().getPackageInfo(ORBOT, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Asks Orbot to start Tor, and opens it, where its own button does it if it doesn't start on its own. */
    static void startOrbot(Context c) {
        c.sendBroadcast(new Intent("org.torproject.android.intent.action.START").setPackage(ORBOT)
                .putExtra("org.torproject.android.intent.extra.PACKAGE_NAME", c.getPackageName()));
        Intent open = c.getPackageManager().getLaunchIntentForPackage(ORBOT);
        if (open == null) return;
        try {
            c.startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException ignored) {
            // the broadcast may still start it
        }
    }

    /** Orbot in the phone's app store (F-Droid, or another), or its website when there's no store. */
    static void getOrbot(Context c) {
        try {
            c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + ORBOT)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            try {
                c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://orbot.app")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException ignored) {
                // no browser either
            }
        }
    }

    // ------------------------------------------------------------------ the gate

    /**
     * A proxy on this phone, for this app alone (it listens on 127.0.0.1 only): HttpURLConnection hands it each
     * request, CONNECT host:port for https and the whole request for http, and it passes the connection on through
     * Tor. It never looks anything up, and when Tor can't connect it closes the connection, so the request fails.
     */
    static final class Gate {
        private static ServerSocket server;

        private Gate() {}

        static synchronized Proxy proxy() throws IOException {
            if (server == null || server.isClosed()) {
                ServerSocket s = new ServerSocket(0, 50, LOOPBACK);
                server = s;
                Thread t = new Thread(() -> accept(s), "tor-gate");
                t.setDaemon(true);
                t.start();
            }
            return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(LOOPBACK, server.getLocalPort()));
        }

        private static void accept(ServerSocket s) {
            while (!s.isClosed()) {
                Socket c;
                try {
                    c = s.accept();
                } catch (IOException e) {
                    return;
                }
                Thread t = new Thread(() -> serve(c), "tor-gate-conn");
                t.setDaemon(true);
                t.start();
            }
        }

        private static void serve(Socket client) {
            Socket up = null;
            try {
                client.setSoTimeout(60_000);
                InputStream in = client.getInputStream();
                ByteArrayOutputStream got = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int end = -1;
                while (end < 0) {
                    int n = in.read(buf);
                    if (n < 0 || got.size() + n > 64 * 1024) return;
                    got.write(buf, 0, n);
                    end = endOfHead(got.toByteArray());
                }
                byte[] all = got.toByteArray();
                int eol = indexOf(all, (byte) '\r');
                String[] first = new String(all, 0, eol, StandardCharsets.US_ASCII).split(" ");
                if (first.length != 3) return;
                boolean tunnel = "CONNECT".equals(first[0]);
                String host;
                int port;
                if (tunnel) {
                    int colon = first[1].lastIndexOf(':');
                    if (colon <= 0) return;
                    host = first[1].substring(0, colon);
                    if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
                    port = Integer.parseInt(first[1].substring(colon + 1));
                } else {
                    URI u = new URI(first[1]);
                    if (!"http".equalsIgnoreCase(u.getScheme()) || u.getHost() == null) return;
                    host = u.getHost();
                    port = u.getPort() > 0 ? u.getPort() : 80;
                }
                up = connect(host, port, SLOW_MS);
                if (tunnel) {
                    client.getOutputStream().write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    client.getOutputStream().flush();
                    if (all.length > end) up.getOutputStream().write(all, end, all.length - end);
                } else {
                    up.getOutputStream().write(all);   // the origin server takes the request as written (absolute-form)
                }
                client.setSoTimeout(0);
                pipe(client, up);
            } catch (IOException | URISyntaxException | RuntimeException e) {
                // closing the connection tells HttpURLConnection the request failed
            } finally {
                close(client);
                close(up);
            }
        }

        /** Copies both ways until both sides are done. */
        private static void pipe(Socket client, Socket up) throws IOException {
            Thread back = new Thread(() -> {
                copy(up, client);
            }, "tor-gate-back");
            back.setDaemon(true);
            back.start();
            copy(client, up);
            try {
                back.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private static void copy(Socket from, Socket to) {
            byte[] buf = new byte[16 * 1024];
            try {
                InputStream in = from.getInputStream();
                OutputStream out = to.getOutputStream();
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    out.flush();
                }
            } catch (IOException ignored) {
                // one side went away
            }
            try {
                to.shutdownOutput();
            } catch (IOException | UnsupportedOperationException ignored) {
                // closed already
            }
        }

        /** Where the request's head ends (just past its blank line), or -1 until it has. */
        private static int endOfHead(byte[] b) {
            for (int i = 3; i < b.length; i++) {
                if (b[i] == '\n' && b[i - 1] == '\r' && b[i - 2] == '\n' && b[i - 3] == '\r') return i + 1;
            }
            return -1;
        }

        private static int indexOf(byte[] b, byte x) {
            for (int i = 0; i < b.length; i++) if (b[i] == x) return i;
            return b.length;
        }
    }
}
