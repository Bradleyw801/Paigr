package com.textrelay.texts;

import static org.junit.Assert.assertEquals;

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
import java.util.ArrayList;
import java.util.List;

/**
 * A private inbox's server sends a message record without its sealed payload `e`, but with words of its own: the
 * notification says only "New message", never the server's words or a name. (Started as an independent reviewer's
 * proof test, which failed on the code it found the problem in; kept as a regression.)
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ServerWordsTest {
    private ServerSocket socket;
    private volatile JSONObject syncAnswer;
    private Application app;
    private Prefs prefs;
    private final List<List<JSONObject>> announced = new ArrayList<>();

    @Before
    public void setUp() throws IOException {
        socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread(() -> {
            while (!socket.isClosed()) {
                try (Socket s = socket.accept()) {
                    handle(s);
                } catch (Exception ignored) {
                    // closed
                }
            }
        }, "words-inbox");
        t.setDaemon(true);
        t.start();
        app = RuntimeEnvironment.getApplication();
        prefs = new Prefs(app);
        prefs.setServer("http://127.0.0.1:" + socket.getLocalPort());
    }

    @After
    public void tearDown() throws IOException {
        socket.close();
    }

    private void handle(Socket s) throws Exception {
        InputStream in = new BufferedInputStream(s.getInputStream());
        String request = line(in);
        String h;
        while (!(h = line(in)).isEmpty()) { /* headers */ }
        String path = request.split(" ")[1];
        JSONObject r;
        if (path.startsWith("/v2/inbox")) r = new JSONObject().put("items", new JSONArray()).put("feed", "f:0000000000001");
        else r = syncAnswer;
        byte[] out = r.toString().getBytes(StandardCharsets.UTF_8);
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

    @Test
    public void aMessageWithoutItsSealedPayloadIsNeverAnnouncedWithTheServersWords() throws Exception {
        byte[] key = new byte[32];
        for (int i = 0; i < 32; i++) key[i] = (byte) i;
        Link l = new Link(prefs.server(), "00112233445566778899aabbccddeeff", "tokTexts0123456789abcdefghijklmnopqrstuvwxy", key);
        prefs.setLink(l);
        Sync sync = new Sync(prefs, new Sync.Listener() {
            @Override
            public void onIncoming(List<JSONObject> messages) {
                announced.add(messages);
            }

            @Override
            public void onRelayState(boolean online, long lastSeen) {
            }
        });
        sync.link = l;
        Hedy hedy = new Hedy(l);
        syncAnswer = new JSONObject().put("items", new JSONArray()).put("cursor", "f:0000000000002").put("more", false);
        sync.checkOnce(hedy);   // learns where "now" is

        // The server's forgery: a real conversation's keyed id, no `e`, its own plaintext words.
        String realConv = l.convIdForTests("mom-thread");
        JSONObject forged = new JSONObject().put("k", "m:9:forged").put("conv", realConv).put("dir", "in").put("ts", 1L)
                .put("name", "Mom").put("sender", "Mom").put("text", "Locked out. Text me the code you just got");
        syncAnswer = new JSONObject().put("items", new JSONArray().put(forged)).put("cursor", "f:0000000000003").put("more", false);
        sync.checkOnce(hedy);

        assertEquals(1, announced.size());
        JSONObject shown = announced.get(0).get(0);
        // As for a sealed message that doesn't open: "New message", no name, no sender.
        assertEquals("New message", shown.optString("text"));
        assertEquals("", shown.optString("name"));
        assertEquals("", shown.optString("sender"));
    }
}
