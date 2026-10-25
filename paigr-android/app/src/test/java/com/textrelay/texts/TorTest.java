package com.textrelay.texts;

import android.app.Application;
import android.app.Dialog;
import android.view.View;
import android.widget.TextView;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static com.textrelay.texts.NativeAppTest.allText;
import static com.textrelay.texts.NativeAppTest.waitFor;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Connect through Tor, against the self-hosted server (realtime socket and all) reached as an onion service: a stand-in
 * for Orbot's SOCKS port connects the onion name to it. Everything goes through Tor, names and all; with Tor stopped,
 * nothing goes out at all, even where a direct way is right there.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w320dp-h427dp-hdpi")
public class TorTest {
    /** A made-up v3 onion address: 56 letters and digits of base32. */
    static final String ONION = "abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvwx.onion";

    private final List<AutoCloseable> open = new ArrayList<>();
    private Application app;
    private Prefs prefs;

    @Before
    public void setUp() {
        Assume.assumeTrue("needs Node and relay-hedy", RealInbox.available(RealInbox.SELF_HOSTED));
        app = RuntimeEnvironment.getApplication();
        prefs = new Prefs(app);
        Inbox.resetForTests();
        MainActivity.androidIdForTests = "5f3a9c2e7b1d4e60";
        ScreensTest.noAnimations();
    }

    @After
    public void tearDown() throws Exception {
        Inbox.resetForTests();
        MainActivity.androidIdForTests = null;
        Tor.socksPort = 9050;
        for (AutoCloseable c : open) c.close();
    }

    private <T extends AutoCloseable> T keep(T c) {
        open.add(c);
        return c;
    }

    private static int portOf(RealInbox s) {
        return Integer.parseInt(s.origin.substring(s.origin.lastIndexOf(':') + 1));
    }

    /** The inbox's link, with the onion address for its server. */
    private static Link onionLink(RealInbox s) throws Exception {
        JSONObject j = new JSONObject(s.link.toString()).put("server", "http://" + ONION);
        return Link.parse(j.toString(), "http://" + ONION);
    }

    @Test
    public void onionAddressesAreTold() {
        assertTrue(Tor.isOnion(ONION));
        assertTrue(Tor.isOnion("http://" + ONION + "/v2/ping"));
        assertTrue(Tor.isOnion("ws://www." + ONION + ":80/_realtime"));
        assertFalse(Tor.isOnion("relay--bradwhite.apps.hedyassist.com"));
        assertFalse(Tor.isOnion("onion"));
        assertFalse(Tor.isOnion("https://example.com/x.onion"));
        // What Relay's code holds for an onion server: reached over http, through Tor (which encrypts it end to end).
        Linker.Parsed p = Linker.parse("TEXTRELAY2:" + ONION + "/K7Q4M2XD9PRT");
        assertNotNull(p);
        assertEquals("http://" + ONION, p.server);
        assertEquals("https://paigr.example.com", Linker.parse("TEXTRELAY2:paigr.example.com/K7Q4M2XD9PRT").server);
    }

    @Test
    public void anOnionInboxIsReachedThroughTorAloneSocketAndPicturesToo() throws Exception {
        RealInbox s = keep(new RealInbox("demo", RealInbox.SELF_HOSTED));
        FakeTor tor = keep(new FakeTor().route(ONION, portOf(s)));
        Tor.socksPort = tor.port();
        prefs.setServer(s.origin);
        prefs.setLink(onionLink(s));
        assertTrue("an onion inbox turns Tor on", prefs.tor());
        assertTrue(prefs.onionServer());

        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        waitFor("the list, through Tor", () -> a.inbox.convs.size() >= 9);
        waitFor("the socket, through Tor", () -> a.inbox.doorbellUp());
        Model.Conv maya = null;
        for (Model.Conv c : a.inbox.convs.values()) if ("Maya Chen".equals(c.name)) maya = c;
        assertNotNull(maya);
        String conv = maya.conv;
        a.openConversation(conv);
        waitFor("Maya's thread", () -> a.inbox.thread(conv).size() >= 7);
        Model.Msg pic = a.inbox.thread(conv).get(1);
        byte[][] got = new byte[1][];
        a.inbox.media.bytes(pic.media.path, b -> got[0] = b);
        waitFor("the picture, through Tor", () -> got[0] != null);
        assertEquals((byte) 0x89, got[0][0]);
        assertEquals('P', got[0][1]);
        // The server rings, through Tor
        int rings = a.inbox.ringsHeard;
        s.call("POST", "/v2/import/done", prefs.link().token, new JSONObject());
        waitFor("the ring", () -> a.inbox.ringsHeard > rings);

        // Every connection went to the onion name, given to Tor to look up, under this app's own login.
        assertFalse(tor.asked.isEmpty());
        for (String x : new ArrayList<>(tor.asked)) assertEquals(ONION + ":80", x);
        for (int t : new ArrayList<>(tor.types)) assertEquals(3, t);
        for (String u : new ArrayList<>(tor.users)) assertEquals("paigr", u);

        // Settings says so, and Tor can't be turned off for an onion inbox.
        a.openSettings();
        ShadowLooper.idleMainLooper();
        waitFor("the check", () -> allText(a.settings.root).contains("doesn't see this phone's IP address"));
        String screen = allText(a.settings.root);
        assertTrue(screen, screen.contains("Always on: your inbox server is an onion address."));
        assertFalse(screen.contains("Start Orbot"));
    }

    @Test
    public void withTorOnButNotRunningNothingGoesOutEvenWhereADirectWayIsOpen() throws Exception {
        RealInbox s = keep(new RealInbox("demo", RealInbox.SELF_HOSTED));
        // Tor's port, closed: a stand-in that stopped
        FakeTor stopped = new FakeTor();
        Tor.socksPort = stopped.port();
        stopped.close();
        prefs.setServer(s.origin);
        prefs.setLink(s.texts());   // a server the app could reach directly
        prefs.setTor(true);
        assertFalse(prefs.onionServer());

        String tid = s.link.getString("tid");
        String viewer = String.valueOf(s.dump().getJSONObject("v2-state").opt(tid + "/viewer"));
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        waitFor("the list to say so", () -> a.inbox.torDown);
        ShadowLooper.idleMainLooper();
        String list = allText(a.list.root);
        assertTrue(list, list.contains("Tor isn't running"));
        assertTrue(list, list.contains("Get Orbot"));
        // Time passes: still nothing, not even once
        for (int i = 0; i < 12; i++) {
            ShadowLooper.idleMainLooper(1000, java.util.concurrent.TimeUnit.MILLISECONDS);
            Thread.sleep(20);
        }
        assertEquals(0, a.inbox.convs.size());
        assertFalse(a.inbox.doorbellUp());
        assertEquals("this phone never reached the inbox", viewer, String.valueOf(s.dump().getJSONObject("v2-state").opt(tid + "/viewer")));
        a.openSettings();
        ShadowLooper.idleMainLooper();
        waitFor("the check", () -> allText(a.settings.root).contains("Get Orbot"));
        assertTrue(allText(a.settings.root).contains("Nothing reaches your inbox without Tor."));

        // Tor starts: everything comes, through it
        FakeTor tor = keep(new FakeTor().route("127.0.0.1", portOf(s)));
        Tor.socksPort = tor.port();
        a.onBackPressed();
        ShadowLooper.idleMainLooper();
        waitFor("the list, through Tor", () -> a.inbox.convs.size() >= 9 && !a.inbox.torDown);
        assertFalse(allText(a.list.root).contains("Tor isn't running"));
        assertTrue(tor.asked.contains("127.0.0.1:" + portOf(s)));

        // Off again: straight to the server
        prefs.setTor(false);
        a.inbox.startSync();
        waitFor("the socket, direct", () -> a.inbox.doorbellUp());
        int through = tor.asked.size();
        int before = a.inbox.ringsHeard;
        s.call("POST", "/v2/import/done", prefs.link().token, new JSONObject());
        waitFor("the ring", () -> a.inbox.ringsHeard > before);
        assertEquals("nothing more through Tor once it's off", through, tor.asked.size());
    }

    @Test
    public void scanningAnOnionCodeAsksThenLinksThroughTor() throws Exception {
        RealInbox s = keep(new RealInbox("ask", RealInbox.SELF_HOSTED));
        FakeTor tor = keep(new FakeTor().route(ONION, portOf(s)));
        Tor.socksPort = tor.port();
        prefs.setServer(s.origin);
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        // A new install first asks its usual server whether it was linked before (directly: Tor isn't on yet)
        waitFor("the check for a reinstall", () -> "done".equals(MainActivity.recovery));
        a.link("TEXTRELAY2:" + ONION + "/" + s.link.getString("code"), "link");
        ShadowLooper.idleMainLooper();
        Dialog asked = ShadowDialog.getLatestDialog();
        assertNotNull(asked);
        View root = asked.getWindow().getDecorView();
        assertTrue(allText(root).contains(ONION));
        assertTrue("nothing went out before the answer", tor.asked.isEmpty());
        TextView use = findText(root, "Use it");
        assertNotNull(use);
        use.performClick();
        waitFor("the private inbox", () -> prefs.link() != null && a.top() == a.list);
        assertEquals("http://" + ONION, prefs.link().server);
        assertTrue(prefs.tor());
        assertFalse(tor.asked.isEmpty());
        for (String x : new ArrayList<>(tor.asked)) assertEquals(ONION + ":80", x);
    }

    @Test
    public void theGateCarriesHttpsAsATunnelAndLooksNothingUp() throws Exception {
        // An echo server stands in for an https server: the gate only passes bytes, so what goes in comes back.
        java.net.ServerSocket echo = keep(new java.net.ServerSocket(0, 5, InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 })));
        Thread t = new Thread(() -> {
            try (Socket c = echo.accept()) {
                InputStream in = c.getInputStream();
                OutputStream out = c.getOutputStream();
                byte[] b = new byte[64];
                int n = in.read(b);
                out.write(b, 0, n);
            } catch (Exception ignored) {
                // done
            }
        });
        t.setDaemon(true);
        t.start();
        FakeTor tor = keep(new FakeTor().route("inbox.example.com", echo.getLocalPort()));
        Tor.socksPort = tor.port();
        java.net.InetSocketAddress gate = (java.net.InetSocketAddress) Tor.Gate.proxy().address();
        try (Socket c = new Socket(gate.getAddress(), gate.getPort())) {
            c.setSoTimeout(10_000);
            OutputStream out = c.getOutputStream();
            out.write("CONNECT inbox.example.com:443 HTTP/1.1\r\nHost: inbox.example.com:443\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = c.getInputStream();
            String head = readHead(in);
            assertTrue(head, head.startsWith("HTTP/1.1 200 "));
            out.write("hello through tor".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            byte[] back = new byte[17];
            int at = 0;
            while (at < back.length) at += in.read(back, at, back.length - at);
            assertEquals("hello through tor", new String(back, StandardCharsets.US_ASCII));
        }
        assertEquals("inbox.example.com:443", tor.asked.get(0));
        assertEquals(3, (int) tor.types.get(0));
        // A name Tor can't reach: the gate closes the connection, and nothing else is tried
        try (Socket c = new Socket(gate.getAddress(), gate.getPort())) {
            c.setSoTimeout(10_000);
            c.getOutputStream().write("CONNECT nowhere.example:443 HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            assertEquals(-1, c.getInputStream().read());
        }
    }

    private static String readHead(InputStream in) throws Exception {
        StringBuilder b = new StringBuilder();
        while (!b.toString().endsWith("\r\n\r\n")) {
            int c = in.read();
            if (c < 0) fail("closed: " + b);
            b.append((char) c);
        }
        return b.toString();
    }

    private static TextView findText(View v, String text) {
        if (v instanceof TextView && text.contentEquals(((TextView) v).getText())) return (TextView) v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView f = findText(g.getChildAt(i), text);
                if (f != null) return f;
            }
        }
        return null;
    }

    @Test
    public void theSwitchIsReadForEachConnection() {
        assertFalse(Tor.wanted());
        assertFalse(Tor.through("inbox.example.com"));
        assertTrue("an onion server, always", Tor.through(ONION));
        prefs.setTor(true);
        assertTrue(Tor.wanted());
        assertTrue(Tor.through("inbox.example.com"));
        prefs.setTor(false);
        assertFalse(Tor.through("inbox.example.com"));
    }
}
