package com.textrelay.relay;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tor's SOCKS port, as the app sees it on a phone running Orbot: a SOCKS5 server on 127.0.0.1 that connects names it
 * knows (an onion address, say) to servers on this machine. It notes everything it's asked, so tests can tell that
 * the app gave Tor names to look up (never addresses it looked up itself) and logged in under its own name.
 */
final class FakeTor implements AutoCloseable {
    private final ServerSocket server;
    private final Map<String, Integer> routes = new ConcurrentHashMap<>();
    /** "name:port" for each connection asked for. */
    final List<String> asked = Collections.synchronizedList(new ArrayList<>());
    /** Each connection's address type: 3 is a name, 1 and 4 are addresses. */
    final List<Integer> types = Collections.synchronizedList(new ArrayList<>());
    /** The names the app logged in with. */
    final List<String> users = Collections.synchronizedList(new ArrayList<>());

    FakeTor() throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 }));
        Thread t = new Thread(this::accept, "fake-tor");
        t.setDaemon(true);
        t.start();
    }

    int port() {
        return server.getLocalPort();
    }

    /** Connections to `name` go to 127.0.0.1:port. */
    FakeTor route(String name, int port) {
        routes.put(name, port);
        return this;
    }

    private void accept() {
        while (!server.isClosed()) {
            try {
                Socket c = server.accept();
                Thread t = new Thread(() -> serve(c), "fake-tor-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private void serve(Socket c) {
        Socket up = null;
        try {
            InputStream in = c.getInputStream();
            OutputStream out = c.getOutputStream();
            if (in.read() != 5) return;
            int n = in.read();
            boolean login = false;
            for (int i = 0; i < n; i++) if (in.read() == 2) login = true;
            if (login) {
                out.write(new byte[] { 5, 2 });
                if (in.read() != 1) return;
                String user = new String(read(in, in.read()), StandardCharsets.US_ASCII);
                read(in, in.read());
                users.add(user);
                out.write(new byte[] { 1, 0 });
            } else {
                out.write(new byte[] { 5, 0 });
            }
            if (in.read() != 5 || in.read() != 1 || in.read() != 0) return;
            int type = in.read();
            types.add(type);
            String name;
            if (type == 3) name = new String(read(in, in.read()), StandardCharsets.US_ASCII);
            else if (type == 1) name = InetAddress.getByAddress(read(in, 4)).getHostAddress();
            else name = InetAddress.getByAddress(read(in, 16)).getHostAddress();
            byte[] p = read(in, 2);
            int port = ((p[0] & 255) << 8) | (p[1] & 255);
            asked.add(name + ":" + port);
            Integer to = routes.get(name);
            if (to == null) {
                out.write(new byte[] { 5, 4, 0, 1, 0, 0, 0, 0, 0, 0 });   // host unreachable
                return;
            }
            up = new Socket();
            up.connect(new InetSocketAddress(InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 }), to), 5000);
            out.write(new byte[] { 5, 0, 0, 1, 127, 0, 0, 1, 0, 0 });
            out.flush();
            Socket u = up;
            Thread back = new Thread(() -> copy(u, c), "fake-tor-back");
            back.setDaemon(true);
            back.start();
            copy(c, up);
            back.join();
        } catch (IOException | InterruptedException ignored) {
            // the app went away
        } finally {
            try {
                c.close();
            } catch (IOException ignored) {
                // closed
            }
            if (up != null) try {
                up.close();
            } catch (IOException ignored) {
                // closed
            }
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
            // closed
        }
        try {
            to.shutdownOutput();
        } catch (IOException ignored) {
            // closed
        }
    }

    private static byte[] read(InputStream in, int n) throws IOException {
        byte[] b = new byte[Math.max(0, n)];
        int at = 0;
        while (at < b.length) {
            int got = in.read(b, at, b.length - at);
            if (got < 0) throw new IOException("closed");
            at += got;
        }
        return b;
    }

    @Override
    public void close() {
        try {
            server.close();
        } catch (IOException ignored) {
            // closed
        }
    }
}
