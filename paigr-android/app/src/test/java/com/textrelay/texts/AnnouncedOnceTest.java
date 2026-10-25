package com.textrelay.texts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;

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
import java.util.Locale;

/**
 * Each sealed text is announced once, whatever the server does with keys, and a message that doesn't open names no one.
 * (Started as an independent reviewer's checks, kept as regressions.)
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class AnnouncedOnceTest {
    private static final String TID = "00112233445566778899aabbccddeeff";
    private static final String TOKEN = "tokTexts0123456789abcdefghijklmnopqrstuvwxy";
    private ServerSocket socket;
    private volatile JSONObject syncAnswer;
    private Application app;
    private Prefs prefs;
    private Link l;
    private final List<List<JSONObject>> announced = new ArrayList<>();
    private Sync sync;
    private int cursor = 10;
    private long keyTime = 1_700_000_000_000L;

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
        }, "check-inbox");
        t.setDaemon(true);
        t.start();
        app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences("texts", android.content.Context.MODE_PRIVATE).edit().clear().commit();
        prefs = new Prefs(app);
        prefs.setServer("http://127.0.0.1:" + socket.getLocalPort());
        byte[] key = new byte[32];
        for (int i = 0; i < 32; i++) key[i] = (byte) (i * 3 + 1);
        l = new Link(prefs.server(), TID, TOKEN, key);
        prefs.setLink(l);
        sync = new Sync(prefs, new Sync.Listener() {
            @Override
            public void onIncoming(List<JSONObject> messages) {
                announced.add(messages);
            }

            @Override
            public void onRelayState(boolean online, long lastSeen) {
            }
        });
        sync.link = l;
        syncAnswer = page(new JSONArray());
        sync.checkOnce(new Hedy(l));
    }

    @After
    public void tearDown() throws IOException {
        socket.close();
    }

    /**
     * A message that doesn't open, then Mom's real one in the same conversation: the first isn't in Mom's notification at
     * all (nor named as hers), but in a notification of its own.
     */
    @Test
    public void aMessageThatDoesntOpenIsNotShownAsMomsOrInHerConversation() throws Exception {
        String mom = l.convIdForTests("mom");
        JSONObject forged = record(key("forged"), mom, "e1.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        JSONObject real = record(key("real"), mom, sealed(mom, "see you soon", System.currentTimeMillis()));
        syncAnswer = page(new JSONArray().put(forged).put(real));
        sync.checkOnce(new Hedy(l));
        new Notifier(app).show(announced.get(0));
        Notification n = shadowOf(app.getSystemService(NotificationManager.class)).getNotification(Notifier.idFor(mom));
        assertNotNull(n);
        android.os.Parcelable[] ms = n.extras.getParcelableArray(Notification.EXTRA_MESSAGES);
        assertEquals(1, ms.length);
        assertEquals("see you soon", String.valueOf(((android.os.Bundle) ms[0]).getCharSequence("text")));
        assertEquals("Mom", String.valueOf(n.extras.getCharSequence(Notification.EXTRA_TITLE)));
        Notification other = shadowOf(app.getSystemService(NotificationManager.class)).getNotification(Notifier.UNOPENED_ID);
        assertNotNull("the one that didn't open has a notification of its own", other);
        assertEquals("Paigr", String.valueOf(other.extras.getCharSequence(Notification.EXTRA_TITLE)));
        assertTrue(String.valueOf(other.extras.getCharSequence(Notification.EXTRA_TEXT)).contains("couldn't be checked"));
    }

    /** The same in a group: a record that doesn't open, filed under the group, doesn't re-post the group's notification. */
    @Test
    public void aMessageThatDoesntOpenNeverPostsAsAGroups() throws Exception {
        String fam = l.convIdForTests("family");
        String real = l.sealForTests(new JSONObject().put("text", "dinner at 7").put("sender", "Ann").put("name", "Family")
                .put("group", true).put("ts", System.currentTimeMillis()).put("fp", "0123456789abcdef"), Crypto.aadMessage(fam, "in"));
        Notifier notifier = new Notifier(app);
        syncAnswer = page(new JSONArray().put(record(key("real"), fam, real)));
        sync.checkOnce(new Hedy(l));
        notifier.show(announced.get(0));
        Notification before = shadowOf(app.getSystemService(NotificationManager.class)).getNotification(Notifier.idFor(fam));
        syncAnswer = page(new JSONArray().put(record(key("junk"), fam, "e1.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")));
        sync.checkOnce(new Hedy(l));
        notifier.show(announced.get(1));
        Notification after = shadowOf(app.getSystemService(NotificationManager.class)).getNotification(Notifier.idFor(fam));
        assertTrue("the group's notification wasn't posted again", before == after
                || before.extras.getParcelableArray(Notification.EXTRA_MESSAGES).length == after.extras.getParcelableArray(Notification.EXTRA_MESSAGES).length);
        assertNotNull(shadowOf(app.getSystemService(NotificationManager.class)).getNotification(Notifier.UNOPENED_ID));
    }

    /** A key the inbox never makes (here, one with commas, that would crowd out the memory) announces nothing. */
    @Test
    public void aKeyTheInboxNeverMakesAnnouncesNothing() throws Exception {
        String mom = l.convIdForTests("mom");
        String e = sealed(mom, "I'm at the hospital, call me", System.currentTimeMillis() - 60_000L);
        syncAnswer = page(new JSONArray().put(record(key("a"), mom, e)));
        sync.checkOnce(new Hedy(l));
        StringBuilder k = new StringBuilder("m:2:");
        for (int i = 0; i < 600; i++) k.append("x").append(i).append(',');
        k.append("end");
        syncAnswer = page(new JSONArray().put(record(k.toString(), mom, e)).put(record(key("b"), mom, e)));
        sync.checkOnce(new Hedy(l));
        assertEquals("the same sealed text was announced again", 1, opened());
        assertFalse(Prefs.isMessageKey(k.toString()));
        assertTrue(Prefs.isMessageKey("m:1792569371155:3a84c7705b1398cc"));
        assertTrue(Prefs.isMessageKey("m:1792569373167:owng7pke4"));
    }

    /** The same seal written another way (padding, other unused bits) doesn't open, so it's never the text again. */
    @Test
    public void theSameSealSpelledAnotherWayIsNeverTheTextAgain() throws Exception {
        String mom = l.convIdForTests("mom");
        String e = null;
        for (int pad = 0; pad < 3 && e == null; pad++) {
            String x = sealed(mom, "I'm at the hospital, call me" + "  ".substring(0, pad), System.currentTimeMillis() - 60_000L);
            if ((x.length() - 3) % 4 == 2) e = x;
        }
        assertNotNull(e);
        syncAnswer = page(new JSONArray().put(record(key("a"), mom, e)));
        sync.checkOnce(new Hedy(l));
        JSONArray again = new JSONArray();
        String alpha = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        int n = 0;
        for (char c : alpha.toCharArray()) {
            for (String pad : new String[] { "", "=", "==" }) {
                String v = e.substring(0, e.length() - 1) + c + pad;
                if (!v.equals(e)) again.put(record(key("v" + n++), mom, v));
            }
        }
        syncAnswer = page(again);
        sync.checkOnce(new Hedy(l));
        assertEquals("the text was announced again under another spelling", 1, opened());
    }

    /** The relay phone seals each upload afresh: a resync's copy of the same message, under a new key, isn't news. */
    @Test
    public void aCopySealedAgainIsNotAnnouncedAgain() throws Exception {
        String mom = l.convIdForTests("mom");
        long ts = System.currentTimeMillis() - 60_000L;
        String first = sealed(mom, "the gate code is 4471", ts);
        String copy = sealed(mom, "the gate code is 4471", ts);
        assertFalse(first.equals(copy));
        syncAnswer = page(new JSONArray().put(record(key("orig"), mom, first)));
        sync.checkOnce(new Hedy(l));
        syncAnswer = page(new JSONArray().put(record(key("resync"), mom, copy)));
        sync.checkOnce(new Hedy(l));
        assertEquals(1, opened());
    }

    /**
     * An old text crowded out of memory by 600 newer ones, filed again under a new key, isn't announced: it was sealed
     * before what Texts still remembers. A new one still is.
     */
    @Test
    public void aTextCrowdedOutOfMemoryIsNotAnnouncedAgain() throws Exception {
        String mom = l.convIdForTests("mom");
        long t0 = System.currentTimeMillis() - 5 * 60_000L;
        String old = sealed(mom, "the old one", t0);
        syncAnswer = page(new JSONArray().put(record(key("old"), mom, old)));
        sync.checkOnce(new Hedy(l));
        JSONArray newer = new JSONArray();
        for (int i = 1; i <= Prefs.SEEN_KEEP; i++) newer.put(record(key("n" + i), mom, sealed(mom, "newer " + i, t0 + i * 100L)));
        syncAnswer = page(newer);
        sync.checkOnce(new Hedy(l));
        assertEquals(1 + Prefs.SEEN_KEEP, opened());
        syncAnswer = page(new JSONArray().put(record(key("again"), mom, sealed(mom, "the old one", t0))));
        sync.checkOnce(new Hedy(l));
        assertEquals("the old text was announced again", 1 + Prefs.SEEN_KEEP, opened());
        syncAnswer = page(new JSONArray().put(record(key("fresh"), mom, sealed(mom, "a new one", System.currentTimeMillis()))));
        sync.checkOnce(new Hedy(l));
        assertEquals(2 + Prefs.SEEN_KEEP, opened());
    }

    /** One text sealed far ahead (a relay phone whose clock was wrong) doesn't silence every text after it. */
    @Test
    public void oneTextDatedAheadDoesNotSilenceTheRest() throws Exception {
        String mom = l.convIdForTests("mom");
        long now = System.currentTimeMillis();
        syncAnswer = page(new JSONArray().put(record(key("ahead"), mom, sealed(mom, "sent while the clock was wrong", now + 365L * 86_400_000L))));
        sync.checkOnce(new Hedy(l));
        JSONArray normal = new JSONArray();
        for (int i = 1; i <= Prefs.SEEN_KEEP; i++) normal.put(record(key("n" + i), mom, sealed(mom, "ordinary " + i, now + i)));
        syncAnswer = page(normal);
        sync.checkOnce(new Hedy(l));
        assertEquals(1 + Prefs.SEEN_KEEP, opened());
        syncAnswer = page(new JSONArray().put(record(key("fresh"), mom, sealed(mom, "a genuinely new text", System.currentTimeMillis() + 5_000))));
        sync.checkOnce(new Hedy(l));
        assertEquals("a genuine new text was not announced", 2 + Prefs.SEEN_KEEP, opened());
    }

    /** Nothing sealed before this phone linked (beyond a few minutes for the clocks) is announced. */
    @Test
    public void nothingFromBeforeTheLinkIsAnnounced() throws Exception {
        String mom = l.convIdForTests("mom");
        syncAnswer = page(new JSONArray().put(record(key("before"), mom, sealed(mom, "from last month", System.currentTimeMillis() - 30L * 86_400_000L)))
                .put(record(key("just"), mom, sealed(mom, "a minute ago", System.currentTimeMillis() - 60_000L))));
        sync.checkOnce(new Hedy(l));
        assertEquals(1, opened());
        assertEquals("a minute ago", announced.get(0).get(0).getString("text"));
    }

    /**
     * Upgraded from a build that kept only keys: a text announced before the upgrade, filed under a new key afterwards,
     * isn't announced again (memory from before starts its floor at the upgrade).
     */
    @Test
    public void aTextAnnouncedBeforeTheUpgradeIsNotAnnouncedAgain() throws Exception {
        String mom = l.convIdForTests("mom");
        String e = sealed(mom, "announced by the old build", System.currentTimeMillis() - 3_600_000L);
        String oldKey = key("old");
        app.getSharedPreferences("texts", android.content.Context.MODE_PRIVATE).edit().putString("seen", oldKey + "," + key("x"))
                .remove("seals").remove("seal_floor").commit();
        syncAnswer = page(new JSONArray().put(record(oldKey, mom, e)));
        sync.checkOnce(new Hedy(l));
        syncAnswer = page(new JSONArray().put(record(key("new"), mom, e)));
        sync.checkOnce(new Hedy(l));
        assertEquals(0, opened());
    }

    /** What a build that kept the keys comma-separated remembered still counts. */
    @Test
    public void keysRememberedTheOldWayStillCount() {
        String a = key("a"), b = key("b");
        app.getSharedPreferences("texts", android.content.Context.MODE_PRIVATE).edit().putString("seen", a + "," + b).commit();
        assertFalse(prefs.markSeen(a, null, 0));
        assertFalse(prefs.markSeen(b, null, 0));
        assertTrue(prefs.markSeen(key("c"), null, 0));
    }

    /** Texts announced that opened here (the ones that didn't go to a notification of their own). */
    private int opened() {
        int n = 0;
        for (List<JSONObject> a : announced) for (JSONObject m : a) if (!m.optBoolean("unchecked", false)) n++;
        return n;
    }

    private String key(String id) {
        return "m:" + String.format(Locale.US, "%013d", keyTime++) + ":" + id;
    }

    private JSONObject record(String k, String conv, String e) throws Exception {
        return new JSONObject().put("k", k).put("conv", conv).put("dir", "in").put("ts", System.currentTimeMillis()).put("e", e);
    }

    private String sealed(String conv, String text, long ts) throws Exception {
        return l.sealForTests(new JSONObject().put("text", text).put("sender", "Mom").put("name", "Mom")
                .put("group", false).put("ts", ts).put("fp", "0123456789abcdef"), Crypto.aadMessage(conv, "in"));
    }

    private JSONObject page(JSONArray items) throws Exception {
        cursor++;
        return new JSONObject().put("items", items).put("convs", new JSONArray()).put("cursor", String.format(Locale.US, "f:%013d", cursor)).put("more", false);
    }

    private void handle(Socket s) throws Exception {
        InputStream in = new BufferedInputStream(s.getInputStream());
        String request = line(in);
        String h;
        while (!(h = line(in)).isEmpty()) { /* headers */ }
        String path = request.split(" ")[1];
        JSONObject r = path.startsWith("/v2/inbox") ? new JSONObject().put("items", new JSONArray()).put("feed", "f:0000000000001") : syncAnswer;
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
}
