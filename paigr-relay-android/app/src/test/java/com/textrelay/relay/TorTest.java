package com.textrelay.relay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.service.notification.StatusBarNotification;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;

/**
 * Connect through Tor on the relay phone: an inbox on an onion server is reached through Tor alone (a stand-in for
 * Orbot's SOCKS port connects the onion name to the fake inbox), names and all; with Tor on but not running, texts
 * wait on the phone and nothing goes out direct; and the inbox server field takes onion addresses.
 */
@RunWith(RobolectricTestRunner.class)
public class TorTest {
    static final String ONION = "abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvwx.onion";

    private final RelayTest fixtures = new RelayTest();
    private FakeHedy server;
    private FakeTor tor;
    private Application app;
    private Relay relay;

    @Before
    public void setUp() throws Exception {
        server = new FakeHedy();
        Relay.reset();
        app = RuntimeEnvironment.getApplication();
        relay = Relay.get(app);
        fixtures.app = app;
        fixtures.relay = relay;
        fixtures.server = server;
    }

    @After
    public void tearDown() throws Exception {
        RelayTest.settle();
        Relay.reset();
        server.close();
        if (tor != null) tor.close();
        Tor.socksPort = 9050;
    }

    private int serverPort() {
        String b = server.baseUrl();
        return Integer.parseInt(b.substring(b.lastIndexOf(':') + 1));
    }

    /** This phone in a private inbox on the onion server. */
    private void linkOnion() {
        relay.prefs.setServer("http://" + ONION, "");
        relay.prefs.setLink(new Link("http://" + ONION, FakeHedy.TID, RelayTest.TOKEN, RelayTest.INBOX_KEY));
        relay.prefs.setWrapTag("done");
        server.link = relay.prefs.link();
    }

    @Test
    public void theServerFieldTakesHttpsAndOnionAddresses() {
        String[] why = new String[1];
        assertEquals("https://paigr.example.com", MoreActivity.serverAddress(" https://Paigr.example.com/ ", why));
        assertEquals("http://" + ONION, MoreActivity.serverAddress(ONION, why));
        assertEquals("http://" + ONION, MoreActivity.serverAddress("http://" + ONION.toUpperCase(java.util.Locale.US) + "/", why));
        assertNull(MoreActivity.serverAddress("http://paigr.example.com", why));
        assertEquals("Use an https:// address, or a .onion one.", why[0]);
        assertNull(MoreActivity.serverAddress("abcdef.onion", why));
        assertTrue(why[0], why[0].contains("56 letters and digits"));
        assertNull(MoreActivity.serverAddress("https://paigr.example.com/path", why));
        // An onion server turns Tor on, for good while it's the server
        relay.prefs.setServer("http://" + ONION, "");
        assertTrue(relay.prefs.tor());
        relay.prefs.setTor(false);
        assertTrue("can't be turned off for an onion server", relay.prefs.tor());
        assertTrue(Tor.wanted());
    }

    @Test
    public void anInboxOnAnOnionServerIsReachedThroughTorAlone() throws Exception {
        tor = new FakeTor().route(ONION, serverPort());
        Tor.socksPort = tor.port();
        linkOnion();
        assertTrue(relay.prefs.tor());
        long now = System.currentTimeMillis();
        relay.onNotification(fixtures.conversation("c1", "Alice Smith", false, "REPLY_C1", fixtures.from("Alice Smith", "Meet at Sundance at 6?", now)));
        relay.drain();
        JSONObject call = server.calls("/api/relay/ingest").get(0);
        assertEquals(RelayTest.TOKEN, call.getString("_key"));
        relay.startPoller();
        RelayTest.waitFor(() -> !server.calls("/api/relay/heartbeat").isEmpty());
        assertFalse(relay.torDown);
        assertFalse(tor.asked.isEmpty());
        assertTrue(tor.asked.contains(ONION + ":80"));
        // The fake inbox hands out a realtime address that leads nowhere: that went through Tor as well.
        for (String x : new ArrayList<>(tor.asked)) assertTrue(x, x.equals(ONION + ":80") || x.equals("127.0.0.1:1"));
        for (int t : new ArrayList<>(tor.types)) assertEquals("Tor got the name, never an address", 3, t);
        for (String u : new ArrayList<>(tor.users)) assertEquals("paigr-relay", u);
    }

    @Test
    public void withTorOnButNotRunningTextsWaitOnThePhoneAndNothingGoesDirect() throws Exception {
        FakeTor stopped = new FakeTor();
        Tor.socksPort = stopped.port();
        stopped.close();
        // An inbox the phone could reach directly, with Tor on
        relay.prefs.setServer(server.baseUrl(), "");
        relay.prefs.setLink(new Link(server.baseUrl(), FakeHedy.TID, RelayTest.TOKEN, RelayTest.INBOX_KEY));
        relay.prefs.setWrapTag("done");
        server.link = relay.prefs.link();
        relay.prefs.setTor(true);
        long now = System.currentTimeMillis();
        StatusBarNotification n = fixtures.conversation("c2", "Kai", false, "REPLY_C2", fixtures.from("Kai", "Still on for tonight?", now));
        relay.onNotification(n);
        relay.startPoller();
        RelayTest.waitFor(() -> relay.torDown);
        assertEquals(Tor.NOT_RUNNING, relay.lastError);
        Thread.sleep(1500);
        assertTrue("nothing reached the inbox", server.calls.isEmpty());
        // The main screen says what to do
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        RelayTest.tick();
        String screen = RelayTest.allText(a.findViewById(android.R.id.content));
        assertTrue(screen, screen.contains("Tor isn't running"));
        assertTrue(screen, screen.contains("Tap to get Orbot"));

        // Tor starts: the text that waited goes up, through it
        tor = new FakeTor().route("127.0.0.1", serverPort());
        Tor.socksPort = tor.port();
        relay.wakePoller();
        RelayTest.waitFor(() -> !server.calls("/api/relay/ingest").isEmpty());
        RelayTest.waitFor(() -> !relay.torDown);
        assertTrue(tor.asked.contains("127.0.0.1:" + serverPort()));
        a.finish();
    }
}
