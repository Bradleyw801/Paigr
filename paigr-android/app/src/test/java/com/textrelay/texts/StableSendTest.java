package com.textrelay.texts;

import static com.textrelay.texts.NativeAppTest.allText;
import static com.textrelay.texts.NativeAppTest.find;
import static com.textrelay.texts.NativeAppTest.waitFor;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * What a text written here does when the inbox can't be reached, or answers late: it never vanishes and never goes
 * twice. The inbox sits behind a small pipe this test can cut, so "offline" is the real thing: connection refused.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class StableSendTest {
    private final List<RealInbox> servers = new ArrayList<>();
    private Application app;
    private Prefs prefs;
    private Pipe pipe;

    @Before
    public void setUp() {
        Assume.assumeTrue("needs Node and relay-hedy", RealInbox.available());
        app = RuntimeEnvironment.getApplication();
        prefs = new Prefs(app);
        Inbox.resetForTests();
        MainActivity.androidIdForTests = "5f3a9c2e7b1d4e60";
        ScreensTest.noAnimations();
    }

    @After
    public void tearDown() {
        Inbox.resetForTests();
        MainActivity.androidIdForTests = null;
        if (pipe != null) pipe.close();
        for (RealInbox s : servers) s.close();
    }

    /** The inbox through a pipe that can be cut: refused connections while it's down, bytes both ways while it's up. */
    private static final class Pipe implements AutoCloseable {
        final ServerSocket socket;
        final String origin;
        private volatile boolean up = true;
        private final int toPort;
        private final List<Socket> live = java.util.Collections.synchronizedList(new ArrayList<>());

        /** Cut (false) or restore (true) the connection; cutting also drops every connection already open through it. */
        void up(boolean on) {
            up = on;
            if (on) return;
            synchronized (live) {
                for (Socket sock : live) try { sock.close(); } catch (IOException ignored) { /* closed */ }
                live.clear();
            }
        }

        Pipe(int toPort) throws IOException {
            this.toPort = toPort;
            socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            origin = "http://127.0.0.1:" + socket.getLocalPort();
            Thread t = new Thread(() -> {
                while (!socket.isClosed()) {
                    try {
                        Socket in = socket.accept();
                        if (!up) {
                            in.close();   // as a phone with no connection sees it: nothing answers
                            continue;
                        }
                        Socket out = new Socket("127.0.0.1", toPort);
                        live.add(in);
                        live.add(out);
                        pump(in, out);
                        pump(out, in);
                    } catch (IOException ignored) {
                        // closed, or a side went away
                    }
                }
            }, "pipe");
            t.setDaemon(true);
            t.start();
        }

        private static void pump(Socket from, Socket to) {
            Thread t = new Thread(() -> {
                try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        out.flush();
                    }
                } catch (IOException ignored) {
                    // one side closed
                } finally {
                    try { from.close(); } catch (IOException ignored) { /* closed */ }
                    try { to.close(); } catch (IOException ignored) { /* closed */ }
                }
            }, "pump");
            t.setDaemon(true);
            t.start();
        }

        @Override
        public void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // closed
            }
        }
    }

    private RealInbox start() throws Exception {
        RealInbox s = new RealInbox("demo");
        servers.add(s);
        pipe = new Pipe(Integer.parseInt(s.origin.substring(s.origin.lastIndexOf(':') + 1)));
        prefs.setServer(pipe.origin);
        Link l = Link.parse(new JSONObject(s.link.toString()).put("server", pipe.origin).toString(), pipe.origin);
        assertNotNull(l);
        prefs.setLink(l);
        return s;
    }

    private static Model.Conv named(MainActivity a, String name) {
        for (Model.Conv c : a.inbox.convs.values()) if (name.equals(c.name)) return c;
        return null;
    }

    private static ThreadScreen.Item itemSaying(MainActivity a, String text) {
        for (ThreadScreen.Item it : a.thread.itemsForTests()) if (it.m != null && text.equals(it.m.text)) return it;
        return null;
    }

    private static int onServer(RealInbox s, Link l, String conv, String text) throws Exception {
        int n = 0;
        JSONObject held = s.dump().getJSONObject("v2-messages");
        for (java.util.Iterator<String> keys = held.keys(); keys.hasNext(); ) {
            JSONObject v = held.getJSONObject(keys.next());
            if (!conv.equals(v.optString("conv")) || !"out".equals(v.optString("dir"))) continue;
            try {
                if (text.equals(l.open(v.getString("e"), Crypto.aadMessage(conv, "out")).optString("text"))) n++;
            } catch (RuntimeException | java.security.GeneralSecurityException ignored) {
                // sealed for another conversation
            }
        }
        return n;
    }

    /**
     * Offline, a text waits (and says so), goes by itself once the inbox answers, as one message; a restart in between
     * brings it back from this phone's own copy; opening a conversation offline still counts as reading it, told to
     * the inbox when it answers; and a draft outlives the screen.
     */
    @Test
    public void aTextWrittenOfflineWaitsGoesOnceAndSurvivesARestart() throws Exception {
        RealInbox s = start();
        Link l = prefs.link();
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = c.get();
        waitFor("the list", () -> a.inbox.convs.size() >= 9);
        Model.Conv maya = named(a, "Maya Chen");
        a.openConversation(maya.conv);
        waitFor("Maya's thread", () -> a.inbox.thread(maya.conv).size() >= 7 && a.inbox.cursors.containsKey(maya.conv));

        // The connection goes. A text written now can't reach the inbox: it waits, in words, and is kept on this phone.
        pipe.up(false);
        a.thread.input.setText("Written in the tunnel");
        find(a.thread.root, "Send").performClick();
        ShadowLooper.idleMainLooper();
        try {
            waitFor("the waiting row", () -> {
                ThreadScreen.Item it = itemSaying(a, "Written in the tunnel");
                return it != null && "Waiting for a connection. Tap to send now.".equals(it.status);
            });
        } catch (AssertionError e) {
            ThreadScreen.Item it = itemSaying(a, "Written in the tunnel");
            throw new AssertionError("row: " + (it == null ? "none" : it.m.status + "/" + it.m.code + "/" + it.status + "/temp=" + it.m.temp + "/tries=" + it.m.tries), e);
        }
        Model.Msg row = itemSaying(a, "Written in the tunnel").m;
        assertTrue(row.temp);
        assertEquals("offline", row.code);
        assertTrue(a.inbox.outbox.idsForTests().contains(row.cid));
        String cid = row.cid;

        // A draft typed meanwhile outlives the screen.
        a.thread.input.setText("half a thought");
        c.pause();
        assertEquals("half a thought", prefs.draft(maya.conv));

        // The app is restarted while the connection is still down: the row comes back from this phone's own copy.
        c.stop().destroy();
        Inbox.resetForTests();
        ActivityController<MainActivity> c2 = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity b = c2.get();
        waitFor("the list from disk", () -> b.inbox.convs.size() >= 9);
        b.openConversation(maya.conv);
        assertEquals("half a thought", b.thread.input.getText().toString());
        waitFor("the row back", () -> {
            ThreadScreen.Item it = itemSaying(b, "Written in the tunnel");
            return it != null && it.m.temp && cid.equals(it.m.cid) && "Waiting for a connection. Tap to send now.".equals(it.status);
        });
        assertEquals(0, onServer(s, l, maya.conv, "Written in the tunnel"));

        // The connection is back: the text goes by itself, once, as the message this phone named it, and the row is
        // the inbox's copy, then "Sent".
        pipe.up(true);
        waitFor("the text on the inbox", () -> {
            try {
                return onServer(s, l, maya.conv, "Written in the tunnel") == 1;
            } catch (Exception e) {
                return false;
            }
        });
        waitFor("one row, sending", () -> {
            int rows = 0;
            ThreadScreen.Item got = null;
            for (ThreadScreen.Item it : b.thread.itemsForTests()) if (it.m != null && "Written in the tunnel".equals(it.m.text)) { rows++; got = it; }
            return rows == 1 && got != null && !got.m.temp && got.m.k.startsWith("m:");
        });
        assertFalse(b.inbox.outbox.idsForTests().contains(cid));
        JSONObject held = s.dump().getJSONObject("v2-messages");
        String id = null;
        for (java.util.Iterator<String> keys = held.keys(); keys.hasNext(); ) {
            JSONObject v = held.getJSONObject(keys.next());
            if (cid.equals(v.optString("cid"))) id = v.getString("k");
        }
        assertNotNull("the inbox knows the message by this phone's id for it", id);
        s.call("POST", "/v2/relay/outbox/result", s.link.getString("relayToken"), new JSONObject().put("id", id).put("status", "sent").put("via", "rcs"));
        waitFor("sent", () -> {
            ThreadScreen.Item it = itemSaying(b, "Written in the tunnel");
            return it != null && "Sent".equals(it.status);
        });
        assertEquals(1, onServer(s, l, maya.conv, "Written in the tunnel"));
        c2.pause().stop().destroy();
    }

    /** Opening a conversation while offline reads it: the count goes here at once, and the inbox hears once it answers. */
    @Test
    public void readingOfflineIsToldToTheInboxWhenItAnswers() throws Exception {
        RealInbox s = start();
        Link l = prefs.link();
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = c.get();
        waitFor("the list", () -> a.inbox.convs.size() >= 9);
        Model.Conv unread = null;
        for (Model.Conv cv : a.inbox.convs.values()) if (cv.unread > 0 && !cv.group) unread = cv;
        assertNotNull("a conversation with something unread", unread);
        String conv = unread.conv;
        pipe.up(false);
        a.openConversation(conv);
        ShadowLooper.idleMainLooper();
        assertEquals("read the moment it opens, inbox or no inbox", 0, a.inbox.convs.get(conv).unread);
        waitFor("the read owed", () -> prefs.readsOwed().contains(conv));
        JSONObject before = s.dump().getJSONObject("v2-convs").getJSONObject(l.tid + "/" + conv);
        assertTrue(before.getInt("unread") > 0);
        pipe.up(true);
        waitFor("the inbox told", () -> {
            try {
                return s.dump().getJSONObject("v2-convs").getJSONObject(l.tid + "/" + conv).getInt("unread") == 0 && !prefs.readsOwed().contains(conv);
            } catch (Exception e) {
                return false;
            }
        });
        // and a page that couldn't load comes by itself once the inbox answers
        a.onBackPressed();
        pipe.up(false);
        Model.Conv other = null;
        for (Model.Conv cv : a.inbox.convs.values()) if (!a.inbox.msgs.containsKey(cv.conv)) other = cv;
        if (other != null) {
            a.openConversation(other.conv);
            ShadowLooper.idleMainLooper();
            waitFor("the notice", () -> allText(a.thread.root).contains("Couldn't load this conversation"));
            pipe.up(true);
            waitFor("the page", () -> !a.inbox.thread(a.inbox.current).isEmpty() && !allText(a.thread.root).contains("Couldn't load this conversation"));
        }
        c.pause().stop().destroy();
    }

    /** A row the inbox has as failed, sent again: the old row stops offering to retry, and a tap on it sends nothing. */
    @Test
    public void aFailedTextSentAgainOffersNoSecondRetry() throws Exception {
        RealInbox s = start();
        Link l = prefs.link();
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = c.get();
        waitFor("the list", () -> a.inbox.convs.size() >= 9);
        Model.Conv maya = named(a, "Maya Chen");
        a.openConversation(maya.conv);
        waitFor("Maya's thread", () -> a.inbox.thread(maya.conv).size() >= 7 && a.inbox.cursors.containsKey(maya.conv));
        a.thread.input.setText("Try this one");
        find(a.thread.root, "Send").performClick();
        ShadowLooper.idleMainLooper();
        waitFor("on the inbox", () -> {
            ThreadScreen.Item it = itemSaying(a, "Try this one");
            return it != null && !it.m.temp;
        });
        String id = itemSaying(a, "Try this one").m.k;
        s.call("POST", "/v2/relay/outbox/result", s.link.getString("relayToken"), new JSONObject().put("id", id).put("status", "failed").put("code", "no_fallback"));
        waitFor("failed", () -> {
            ThreadScreen.Item it = itemSaying(a, "Try this one");
            return it != null && "failed".equals(it.m.status) && it.status.endsWith("Tap to retry.");
        });
        Model.Msg failed = itemSaying(a, "Try this one").m;
        a.inbox.sendAgain(failed);
        ShadowLooper.idleMainLooper();
        waitFor("sent again, and the old row says so", () -> {
            int rows = 0;
            for (ThreadScreen.Item it : a.thread.itemsForTests()) if (it.m != null && "Try this one".equals(it.m.text)) rows++;
            ThreadScreen.Item old = null;
            for (ThreadScreen.Item it : a.thread.itemsForTests()) if (it.m != null && it.m.k.equals(id)) old = it;
            return rows == 2 && old != null && "Not sent. Sent again below.".equals(old.status);
        });
        a.inbox.sendAgain(failed);   // a second time: nothing
        ShadowLooper.idleMainLooper();
        Thread.sleep(300);
        ShadowLooper.idleMainLooper();
        assertEquals(2, onServer(s, l, maya.conv, "Try this one"));
        assertTrue(prefs.retried().contains(id));
        c.pause().stop().destroy();
    }
}
