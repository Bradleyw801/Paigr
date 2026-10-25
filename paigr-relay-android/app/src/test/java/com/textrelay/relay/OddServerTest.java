package com.textrelay.relay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** The relay phone's outbox handling against a server that answers with odd shapes: nothing throws, nothing goes out. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class OddServerTest {
    private ServerSocket socket;
    private volatile String outboxAnswer = "{}";
    private final List<JSONObject> posted = new CopyOnWriteArrayList<>();
    private Relay relay;

    @Before
    public void setUp() throws Exception {
        socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread(() -> {
            while (!socket.isClosed()) {
                try (Socket s = socket.accept()) {
                    handle(s);
                } catch (Exception ignored) {
                    // closed
                }
            }
        }, "odd-inbox");
        t.setDaemon(true);
        t.start();
        Relay.reset();
        Application app = RuntimeEnvironment.getApplication();
        relay = Relay.get(app);
        relay.prefs.raw().edit().clear().commit();
        String base = "http://127.0.0.1:" + socket.getLocalPort();
        relay.prefs.setServer(base, "");
        relay.prefs.setLink(new Link(base, "00112233445566778899aabbccddeeff", "tok0123456789abcdefghijklmnopqrstuvwxyzABCD", new byte[32]));
    }

    @After
    public void tearDown() throws IOException {
        Relay.reset();
        socket.close();
    }

    @Test
    public void oddOutboxAnswersNeverThrowAndNeverSend() throws Exception {
        Link l = relay.prefs.link();
        relay.prefs.setName("c1", "Ann");
        String wire = l.convId("c1");
        long now = System.currentTimeMillis();
        JSONArray items = new JSONArray()
                .put("not an object").put(JSONObject.NULL).put(5)
                .put(new JSONObject().put("id", new JSONObject().put("a", 1)).put("conv", 5).put("o", 7).put("ts", "x"))
                .put(new JSONObject().put("id", "m:1").put("conv", wire).put("o", l.seal(Json.obj("text", "hi", "n", "bad/nonce/bad/nonce/00", "ts", now), Crypto.aadOut(wire))))
                .put(new JSONObject().put("id", "m:2").put("conv", wire).put("o", l.seal(Json.obj("text", 123, "n", "goodnoncegoodnonce0000", "ts", "9"), Crypto.aadOut(wire))))
                .put(new JSONObject().put("id", "m:3").put("conv", JSONObject.NULL).put("o", JSONObject.NULL));
        outboxAnswer = new JSONObject().put("items", items).put("pollSeconds", "abc").put("wake", "soon").toString();
        int poll = relay.processOutbox(relay.hedy());
        assertEquals(20, poll);
        assertEquals(4, posted.size());
        for (JSONObject r : posted) {
            assertEquals("failed", r.getString("status"));
            assertFalse(r.has("error"));
        }
        outboxAnswer = new JSONObject().put("items", "x").toString();
        relay.processOutbox(relay.hedy());
        outboxAnswer = "[1,2]";
        relay.processOutbox(relay.hedy());
        assertTrue(relay.lastQueued == 0);
    }

    private void handle(Socket s) throws Exception {
        InputStream in = new BufferedInputStream(s.getInputStream());
        String request = line(in);
        int length = 0;
        String h;
        while (!(h = line(in)).isEmpty()) if (h.toLowerCase().startsWith("content-length:")) length = Integer.parseInt(h.substring(15).trim());
        byte[] body = new byte[length];
        for (int read = 0; read < length; ) {
            int n = in.read(body, read, length - read);
            if (n < 0) break;
            read += n;
        }
        String path = request.split(" ")[1];
        String reply = "{}";
        if (path.startsWith("/v2/relay/outbox/result")) posted.add(new JSONObject(new String(body, StandardCharsets.UTF_8)));
        else if (path.startsWith("/v2/relay/outbox")) reply = outboxAnswer;
        byte[] out = reply.getBytes(StandardCharsets.UTF_8);
        OutputStream os = s.getOutputStream();
        os.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + out.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        os.write(out);
        os.flush();
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1 && c != '\n') if (c != '\r') b.write(c);
        return b.toString("UTF-8");
    }
}
