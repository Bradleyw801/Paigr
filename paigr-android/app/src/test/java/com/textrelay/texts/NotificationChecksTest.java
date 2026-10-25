package com.textrelay.texts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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

/**
 * The Texts side of the fixes and what they could have broken: notifications show only what opens (no name, never as
 * "You", once per sealed message), merged chains and sealed times still work, odd server input never throws, copies
 * of the app's data carry no live link, and Relay's QR code reads. (Started as an independent reviewer's checks, each
 * failing on the code they found a problem in; kept as regressions.)
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class NotificationChecksTest {
    private static final String TID = "00112233445566778899aabbccddeeff";
    private static final String TOKEN = "tokTexts0123456789abcdefghijklmnopqrstuvwxy";
    private ServerSocket socket;
    private volatile JSONObject syncAnswer;
    private Application app;
    private Prefs prefs;
    private Link l;
    private final List<List<JSONObject>> announced = new ArrayList<>();
    private Sync sync;

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
        syncAnswer = page(new JSONArray(), new JSONArray());
        sync.checkOnce(new Hedy(l));   // learns where "now" is
    }

    @After
    public void tearDown() throws IOException {
        MainActivity.androidIdForTests = null;
        socket.close();
    }

    // ------------------------------------------------------------------ fix 1: notifications show only what opens

    /**
     * PROTOCOL.md: a message that doesn't open "says "New message" with no name". The server keeps every conversation's
     * sealed name (`ne`), so it can put one beside a payload that doesn't open, as often as it likes.
     */
    @Test
    public void aMessageThatDoesntOpenIsAnnouncedWithNoName() throws Exception {
        String mom = l.convIdForTests("mom");
        String genuineName = l.sealForTests(new JSONObject().put("name", "Mom").put("group", false), Crypto.aadName(mom));
        JSONObject forged = new JSONObject().put("k", "m:1:forged").put("conv", mom).put("dir", "in").put("ts", 5L)
                .put("e", "e1.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA").put("ne", genuineName);
        JSONObject shown = l.openMessage(forged);
        assertEquals("New message", shown.getString("text"));
        assertEquals("a payload that doesn't open is shown under a name", "", shown.getString("name"));
    }

    /** The same record, through the background check and the notification it raises: no contact's name on it. */
    @Test
    public void theNotificationForAMessageThatDoesntOpenNamesNobody() throws Exception {
        String mom = l.convIdForTests("mom");
        String genuineName = l.sealForTests(new JSONObject().put("name", "Mom").put("group", false), Crypto.aadName(mom));
        JSONObject forged = new JSONObject().put("k", "m:2:forged").put("conv", mom).put("dir", "in").put("ts", 5L)
                .put("ne", genuineName);
        syncAnswer = page(new JSONArray().put(forged), new JSONArray());
        sync.checkOnce(new Hedy(l));
        assertEquals(1, announced.size());
        Notification n = notify(announced.get(0));
        // in a notification of its own, never in Mom's conversation
        assertNull(shadowOf(app.getSystemService(NotificationManager.class)).getNotification(Notifier.idFor(mom)));
        assertNotNull(shadowOf(app.getSystemService(NotificationManager.class)).getNotification(Notifier.UNOPENED_ID));
        assertFalse("notification titled " + title(n), "Mom".contentEquals(title(n)));
        assertFalse(String.valueOf(n.extras.getCharSequence(Notification.EXTRA_TEXT)).contains("Mom"));
    }

    /** A message that doesn't open (no sealed name either) isn't shown as one this phone's user sent. */
    @Test
    public void aMessageThatDoesntOpenIsNotShownAsSentByYou() throws Exception {
        JSONObject forged = new JSONObject().put("k", "m:3:forged").put("conv", l.convIdForTests("mom")).put("dir", "in").put("ts", 5L);
        syncAnswer = page(new JSONArray().put(forged), new JSONArray());
        sync.checkOnce(new Hedy(l));
        Notification n = notify(announced.get(0));
        assertEquals("Paigr", String.valueOf(title(n)));
        assertTrue(String.valueOf(n.extras.getCharSequence(Notification.EXTRA_TEXT)).contains("couldn't be checked"));
        assertFalse("an incoming notification titled " + title(n), "You".contentEquals(title(n)));
    }

    /** One sealed message is announced once, whatever key the server files it under (PROTOCOL: no replay as new). */
    @Test
    public void aSealedMessageReplayedUnderANewKeyIsNotAnnouncedAgain() throws Exception {
        String mom = l.convIdForTests("mom");
        long sent = System.currentTimeMillis() - 60_000L;   // a text from a minute ago
        String e = l.sealForTests(new JSONObject().put("text", "I'm at the hospital, call me").put("sender", "Mom").put("name", "Mom")
                .put("group", false).put("ts", sent).put("fp", "0123456789abcdef"), Crypto.aadMessage(mom, "in"));
        syncAnswer = page(new JSONArray().put(rec("m:1:a", mom, e)), new JSONArray());
        sync.checkOnce(new Hedy(l));
        syncAnswer = page(new JSONArray().put(rec("m:2:replayed", mom, e)), new JSONArray());
        sync.checkOnce(new Hedy(l));
        int total = 0;
        for (List<JSONObject> a : announced) total += a.size();
        assertEquals("the same sealed text alerted again", 1, total);
    }

    /** Legit messages still open: merged chains, the sealed time, and the notification's time. */
    @Test
    public void mergedConversationsAndTheSealedTimeStillWork() throws Exception {
        String a = l.convIdForTests("a"), b = l.convIdForTests("b"), c = l.convIdForTests("c");
        long ts = System.currentTimeMillis() - 90_000L;
        String e = l.sealForTests(new JSONObject().put("text", "from long ago").put("sender", "Pat").put("name", "Pat")
                .put("group", false).put("ts", ts).put("fp", "0123456789abcdef").put("media", new JSONObject().put("mime", "image/png")),
                Crypto.aadMessage(a, "in"));
        JSONObject m = rec("m:3:a", c, e).put("ts", 1_790_000_000_000L)
                .put("media", new JSONObject().put("path", "/media/v2/" + TID + "/" + a + "/" + Crypto.pad13(ts) + "-0123456789abcdef-o.enc"));
        JSONArray merged = new JSONArray().put(l.mergeRecordForTests(b, c)).put(l.mergeRecordForTests(a, b));
        syncAnswer = page(new JSONArray().put(m), new JSONArray().put(new JSONObject().put("conv", c).put("merged", merged)));
        sync.checkOnce(new Hedy(l));
        JSONObject got = announced.get(0).get(0);
        assertEquals("from long ago", got.getString("text"));
        assertEquals("Pat", got.getString("name"));
        assertEquals(ts, got.getLong("ts"));
        assertTrue(got.getJSONObject("media").has("path"));
        Notification n = notify(announced.get(0));
        android.os.Parcelable[] ms = n.extras.getParcelableArray(Notification.EXTRA_MESSAGES);
        assertEquals(ts, ((android.os.Bundle) ms[0]).getLong("time"));
        // a payload from an older relay (no ts or fp): words shown, server time, attachment not trusted
        String old = l.sealForTests(new JSONObject().put("text", "older build").put("sender", "Pat").put("name", "Pat")
                .put("media", new JSONObject().put("mime", "image/png")), Crypto.aadMessage(c, "in"));
        JSONObject o = l.openMessage(rec("m:4:b", c, old).put("ts", 42L).put("media", new JSONObject().put("path", "/media/v2/" + TID + "/" + c + "/0000000000042-0123456789abcdef-o.enc")));
        assertEquals("older build", o.getString("text"));
        assertEquals(42L, o.getLong("ts"));
        assertTrue(o.getJSONObject("media").optBoolean("blocked"));
    }

    /** Odd server input never throws, and never shows the server's words. */
    @Test
    public void oddServerInputNeverThrows() throws Exception {
        String mom = l.convIdForTests("mom");
        JSONArray items = new JSONArray()
                .put(new JSONObject().put("k", 5).put("conv", new JSONObject().put("x", 1)).put("dir", "in").put("e", 12).put("ne", new JSONArray()).put("ts", "soon").put("text", "server words"))
                .put(new JSONObject().put("k", "m:9:b").put("conv", mom).put("dir", "in").put("e", "e1.").put("ne", "e1.!!!").put("media", "x").put("ts", Long.MAX_VALUE).put("name", "Server"))
                .put(new JSONObject().put("k", "m:9:c").put("conv", JSONObject.NULL).put("dir", "in").put("e", "e1." + "A".repeat(100)))
                .put("not an object").put(JSONObject.NULL);
        JSONArray convs = new JSONArray().put(new JSONObject().put("conv", mom).put("merged", new JSONArray().put(JSONObject.NULL).put(7).put("e1.garbage")))
                .put("x").put(new JSONObject().put("conv", 3).put("merged", "nope"));
        syncAnswer = page(items, convs);
        sync.checkOnce(new Hedy(l));
        for (List<JSONObject> list : announced) {
            for (JSONObject m : list) {
                assertEquals("New message", m.getString("text"));
                assertEquals("", m.optString("name"));
            }
            new Notifier(app).show(list);
        }
    }

    // ------------------------------------------------------------------ fix 6: a copy of the app's data

    /** A copy keeps no live link: the first version's device key (still honoured when present) comes along too. */
    @Test
    public void aCopyOfTheAppsDataCarriesNoLiveLinkOfAnyVersion() {
        MainActivity.androidIdForTests = "5555666677778888";
        prefs.setKey("hak_inbox0123456789.abcDEFghiJKLmnoPQRstuVWXyz0123456789");   // not moved into the private inbox yet
        prefs.setLink(new Link(prefs.server(), TID, TOKEN, new byte[32]));
        MainActivity.androidIdForTests = "1111222233334444";   // the data, read on another phone
        Prefs copied = new Prefs(app);
        assertNull(copied.link());
        assertFalse("still linked (first version's key: " + copied.key() + ")", copied.paired());
    }

    @Test
    public void aLinkIsKeptWhenTheAndroidIdCantBeReadAndTheMigrationRunsOnce() {
        MainActivity.androidIdForTests = "5555666677778888";
        prefs.setLink(new Link(prefs.server(), TID, TOKEN, new byte[32]));
        MainActivity.androidIdForTests = null;
        android.provider.Settings.Secure.putString(app.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID, "0000000000000000");
        assertNotNull(new Prefs(app).link());
        app.getSharedPreferences("texts", android.content.Context.MODE_PRIVATE).edit().remove("here2").remove("ever_linked").commit();
        MainActivity.androidIdForTests = "abcdabcdabcdabcd";
        assertNotNull(new Prefs(app).link());
        assertNotNull(new Prefs(app).link());
        MainActivity.androidIdForTests = "dcbadcbadcbadcba";
        assertNull(new Prefs(app).link());
    }

    // ------------------------------------------------------------------ fix 7: the QR code, end to end

    @Test
    public void whatRelayDrawsIsReadByTheScanner() throws Exception {
        for (String text : new String[] {
                Crypto.linkQr(app.getString(R.string.default_server), "K7Q4M2XD9PRT"),   // the same code as Relay's Crypto.linkQr
                "TEXTRELAY2:relay--bradwhite.apps.hedyassist.com/K7Q4M2XD9PRT",
                "TEXTRELAY2:127.0.0.1:8790/0123456789AB" }) {
            assertTrue(text, QrDecoder.ours(text));
            // drawn as Relay draws it (Pairing.qr: level M, UTF-8, 4-module margin), seen in a camera frame
            java.util.Map<com.google.zxing.EncodeHintType, Object> hints = new java.util.EnumMap<>(com.google.zxing.EncodeHintType.class);
            hints.put(com.google.zxing.EncodeHintType.ERROR_CORRECTION, com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M);
            hints.put(com.google.zxing.EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(com.google.zxing.EncodeHintType.MARGIN, 4);
            com.google.zxing.common.BitMatrix qr = new com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, 330, 330, hints);
            int w = 1280, h = 720, stride = 1344;
            byte[] frame = new byte[stride * h];
            java.util.Arrays.fill(frame, (byte) 200);
            for (int y = 0; y < qr.getHeight(); y++) {
                for (int x = 0; x < qr.getWidth(); x++) frame[(y + 200) * stride + x + 480] = qr.get(x, y) ? (byte) 20 : (byte) 230;
            }
            assertEquals(text, QrDecoder.decode(frame, stride, w, h));
        }
        assertFalse(QrDecoder.ours("TEXTRELAY2:relay.example/inbox/K7Q4M2XD9PRT"));
    }

    // ------------------------------------------------------------------ the fake inbox

    private JSONObject rec(String k, String conv, String e) throws Exception {
        return new JSONObject().put("k", k).put("conv", conv).put("dir", "in").put("ts", System.currentTimeMillis()).put("e", e);
    }

    private int cursor = 10;

    private JSONObject page(JSONArray items, JSONArray convs) throws Exception {
        cursor++;
        return new JSONObject().put("items", items).put("convs", convs).put("cursor", String.format(java.util.Locale.US, "f:%013d", cursor)).put("more", false);
    }

    private Notification notify(List<JSONObject> messages) {
        new Notifier(app).show(messages);
        List<Notification> all = shadowOf(app.getSystemService(NotificationManager.class)).getAllNotifications();
        assertFalse(all.isEmpty());
        return all.get(all.size() - 1);
    }

    private static CharSequence title(Notification n) {
        CharSequence t = n.extras.getCharSequence(Notification.EXTRA_TITLE);
        return t == null ? "" : t;
    }

    private static String lastText(Notification n) {
        android.os.Parcelable[] ms = n.extras.getParcelableArray(Notification.EXTRA_MESSAGES);
        android.os.Bundle last = (android.os.Bundle) ms[ms.length - 1];
        return String.valueOf(last.getCharSequence("text"));
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
