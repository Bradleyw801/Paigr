package com.textrelay.relay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Person;
import android.app.RemoteInput;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import android.os.Process;
import android.service.notification.StatusBarNotification;
import android.telephony.SmsManager;

import org.json.JSONObject;
import org.junit.Test;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowSmsManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A reply with a file, from a private inbox: fetched and opened here, handed to Messages' reply when that takes
 * files, else sent from this phone as MMS, the carrier's limits respected. What the inbox stores stays sealed; what
 * goes out is checked byte by byte.
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 35)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
public class AttachmentsTest {
    private static final String MESSAGES = "com.google.android.apps.messaging";
    /** The private-inbox harness (its fixtures are reused, its own tests are not run again here). */
    private final RelayV2Test t = new RelayV2Test();
    private FakeHedy server;
    private android.app.Application app;
    private Relay relay;
    /** The message as the phone's MMS service read it, while the send was pending. */
    private byte[] lastPdu;

    @org.junit.Before
    public void setUp() throws java.io.IOException {
        t.setUp();
        server = t.server;
        app = t.app;
        relay = t.relay;
    }

    @org.junit.After
    public void tearDown() throws Exception {
        t.tearDown();
    }

    private StatusBarNotification conversation(String id, String title, boolean group, String replyAction, Notification.MessagingStyle.Message... messages) {
        return t.conversation(id, title, group, replyAction, messages);
    }

    private Notification.MessagingStyle.Message from(String name, String text, long time) {
        return t.from(name, text, time);
    }

    private Intent lastBroadcast(String action) {
        return t.lastBroadcast(action);
    }

    static byte[] png(int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(Color.rgb(246, 160, 92));
        android.graphics.Paint p = new android.graphics.Paint();
        p.setColor(Color.rgb(92, 64, 160));
        c.drawCircle(w / 2f, h / 2f, Math.min(w, h) / 3f, p);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, out);
        return out.toByteArray();
    }

    static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** Stores `bytes` sealed in the fake inbox as a file of `conv`; returns its path, as the inbox names it. */
    private String stored(String conv, byte[] bytes, long ts, String fp) {
        Link l = relay.prefs.link();
        String wconv = l.convId(conv);
        String path = FakeHedy.v2MediaPath(wconv, Long.toString(ts), fp, "original");
        server.files.put(path, l.sealFile(bytes, Crypto.aadFile(wconv, ts, fp, "original")));
        return path;
    }

    /** Queues a reply with a file, as Texts seals one: the file named inside the request, for this conversation alone. */
    private void queued(String id, String conv, String text, String path, String mime, int bytes, String name) throws Exception {
        Link l = relay.prefs.link();
        JSONObject request = Json.obj("text", text, "n", Crypto.b64u(Crypto.random(16)), "ts", System.currentTimeMillis(),
                "media", Json.obj("path", path, "mime", mime, "bytes", bytes, "name", name));
        server.pendingOutbox.add(new JSONObject().put("id", id).put("conv", conv).put("ts", 1)
                .put("o", l.seal(request, Crypto.aadOut(l.convId(conv)))));
    }

    /** Runs the outbox on another thread, as Relay does, and plays the phone's MMS service: the send is answered with `result`. */
    private JSONObject outboxWithMms(int result) throws Exception {
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread poller = new Thread(() -> {
            try {
                relay.processOutbox(relay.hedy());
            } catch (Throwable t) {
                failed.set(t);
            }
        });
        ShadowSmsManager sms = shadowOf(SmsManager.getDefault());
        ShadowSmsManager.SendMultimediaMessageParams before = sms.getLastSentMultimediaMessageParams();
        poller.start();
        long until = System.currentTimeMillis() + 20_000;
        while (poller.isAlive() && sms.getLastSentMultimediaMessageParams() == before) {
            if (System.currentTimeMillis() > until) throw new AssertionError("no MMS was handed to the phone");
            ShadowLooper.idleMainLooper();
            Thread.sleep(20);
        }
        if (poller.isAlive()) {
            ShadowSmsManager.SendMultimediaMessageParams p = sms.getLastSentMultimediaMessageParams();
            assertNotNull(p.getSentIntent());
            lastPdu = read(p.getContentUri());   // readable through the grant while the send is pending
            Intent answer = shadowOf(p.getSentIntent()).getSavedIntent();
            app.sendOrderedBroadcast(answer, null, null, null, result, null, null);
            while (poller.isAlive()) {
                if (System.currentTimeMillis() > until) throw new AssertionError("the send never finished");
                ShadowLooper.idleMainLooper();
                Thread.sleep(20);
            }
        }
        if (failed.get() != null) throw new AssertionError(failed.get());
        List<JSONObject> reports = server.calls("/api/relay/outbox/result");
        return reports.get(reports.size() - 1);
    }

    private String aliceWithNumber() throws Exception {
        Person alice = new Person.Builder().setName("Alice").setKey("alice").setUri("tel:+18015550123").build();
        relay.onNotification(conversation("c2", "Alice", false, "REPLY_C2",
                new Notification.MessagingStyle.Message("hey", System.currentTimeMillis(), alice)));
        relay.drain();
        String conv = server.calls("/api/relay/ingest").get(0).getJSONArray("items").getJSONObject(0).getString("conv");
        assertEquals("+18015550123", relay.prefs.number(conv));
        return conv;
    }

    // ------------------------------------------------------------------ as MMS from this phone

    @Test
    public void aPictureGoesOutAsMmsWhenMessagesReplyTakesNoFiles() throws Exception {
        shadowOf(app).grantPermissions(android.Manifest.permission.SEND_SMS);
        String conv = aliceWithNumber();
        byte[] picture = png(640, 480);
        long ts = System.currentTimeMillis();
        String path = stored(conv, picture, ts, "0123456789abcdef");
        queued("m:1:pic", conv, "look at this", path, "image/png", picture.length, "market.png");

        JSONObject report = outboxWithMms(Activity.RESULT_OK);
        assertEquals(report.optString("code", ""), "sent", report.getString("status"));
        assertEquals("mms", report.getString("via"));
        assertTrue("Messages' reply action is still open", report.getBoolean("canReply"));
        // The inbox was asked for the file by its path, and nothing readable passed through it.
        assertEquals(path, server.calls("/v2/media").get(0).getString("p"));
        assertTrue(indexOf(server.files.get(path), utf8("IHDR")) < 0);

        // What the phone's MMS service got: a send request to Alice, with the picture as it was and the words beside it.
        ShadowSmsManager.SendMultimediaMessageParams p = shadowOf(SmsManager.getDefault()).getLastSentMultimediaMessageParams();
        assertNotNull(p);
        byte[] pdu = lastPdu;
        assertEquals(0x8C, pdu[0] & 0xFF);   // X-Mms-Message-Type
        assertEquals(0x80, pdu[1] & 0xFF);   // m-send-req
        assertTrue(indexOf(pdu, utf8("+18015550123/TYPE=PLMN")) > 0);
        assertTrue("the picture, byte for byte", indexOf(pdu, picture) > 0);
        assertTrue(indexOf(pdu, utf8("look at this")) > 0);
        assertTrue(indexOf(pdu, utf8("<smil>")) > 0);
        assertTrue(indexOf(pdu, utf8("image_0.png")) > 0);
        // The message file was this app's own, handed over by a grant, and is gone once sent.
        assertEquals(app.getPackageName() + ".files", p.getContentUri().getAuthority());
        try {
            app.getContentResolver().openInputStream(p.getContentUri());
            org.junit.Assert.fail("the message file should be gone once sent");
        } catch (java.io.FileNotFoundException expected) {
            // gone
        }
    }

    @Test
    public void aFileWithNoWordsGoesAsMmsAndTheCarriersNoIsReported() throws Exception {
        shadowOf(app).grantPermissions(android.Manifest.permission.SEND_SMS);
        String conv = aliceWithNumber();
        byte[] clip = new byte[40_000];
        for (int i = 0; i < clip.length; i++) clip[i] = (byte) (i * 31);
        String path = stored(conv, clip, System.currentTimeMillis(), "fedcba9876543210");
        queued("m:2:clip", conv, "", path, "video/mp4", clip.length, "clip.mp4");
        JSONObject report = outboxWithMms(SmsManager.MMS_ERROR_UNABLE_CONNECT_MMS);
        assertEquals("failed", report.getString("status"));
        assertEquals(NotSent.MMS_FAILED, report.getString("code"));
        assertEquals("mms", report.getString("via"));
        byte[] pdu = lastPdu;
        assertTrue(indexOf(pdu, clip) > 0);
        assertTrue(indexOf(pdu, utf8("video_0.mp4")) > 0);
        assertTrue("no text part", indexOf(pdu, utf8("text_0.txt")) < 0);
    }

    @Test
    public void aBigPictureIsShrunkToTheCarriersLimitsAndAFileThatCantBeIsRefused() throws Exception {
        shadowOf(app).grantPermissions(android.Manifest.permission.SEND_SMS);
        String conv = aliceWithNumber();
        byte[] big = png(2400, 1800);
        String path = stored(conv, big, System.currentTimeMillis(), "aaaabbbbccccdddd");
        queued("m:3:big", conv, "the view", path, "image/png", big.length, "view.png");
        JSONObject report = outboxWithMms(Activity.RESULT_OK);
        assertEquals(report.optString("code", ""), "sent", report.getString("status"));
        byte[] pdu = lastPdu;
        assertTrue("not the original", indexOf(pdu, big) < 0);
        assertTrue("a JPEG instead", indexOf(pdu, utf8("image_0.jpg")) > 0);
        assertTrue(pdu.length < Mms.DEFAULT_MAX_BYTES);
        int jpeg = indexOf(pdu, new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF });
        assertTrue(jpeg > 0);
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeByteArray(pdu, jpeg, pdu.length - jpeg, o);
        assertTrue("fits " + Mms.DEFAULT_MAX_WIDTH + "x" + Mms.DEFAULT_MAX_HEIGHT + ": " + o.outWidth + "x" + o.outHeight,
                o.outWidth <= Mms.DEFAULT_MAX_WIDTH && o.outHeight <= Mms.DEFAULT_MAX_HEIGHT);

        // A video the carrier won't take can't be shrunk here: refused, with a code the inbox puts in words.
        byte[] film = new byte[Mms.DEFAULT_MAX_BYTES + 1];
        String fpath = stored(conv, film, System.currentTimeMillis(), "1111222233334444");
        queued("m:4:film", conv, "", fpath, "video/mp4", film.length, "film.mp4");
        ShadowSmsManager.SendMultimediaMessageParams before = shadowOf(SmsManager.getDefault()).getLastSentMultimediaMessageParams();
        relay.processOutbox(relay.hedy());
        List<JSONObject> reports = server.calls("/api/relay/outbox/result");
        report = reports.get(reports.size() - 1);
        assertEquals("failed", report.getString("status"));
        assertEquals(NotSent.TOO_BIG, report.getString("code"));
        assertTrue(before == shadowOf(SmsManager.getDefault()).getLastSentMultimediaMessageParams());
    }

    @Test
    public void withoutSmsFallbackOrANumberAnAttachmentIsRefusedInWords() throws Exception {
        String conv = aliceWithNumber();
        byte[] picture = png(200, 200);
        String path = stored(conv, picture, System.currentTimeMillis(), "0000111122223333");
        queued("m:5:perm", conv, "pic", path, "image/png", picture.length, "p.png");
        relay.processOutbox(relay.hedy());
        List<JSONObject> reports = server.calls("/api/relay/outbox/result");
        assertEquals(NotSent.ATTACH_PERM, reports.get(reports.size() - 1).getString("code"));

        // A group with no members known here: nowhere to send an MMS.
        shadowOf(app).grantPermissions(android.Manifest.permission.SEND_SMS);
        relay.onNotification(conversation("g2", "Climbing crew", true, "REPLY_G2", from("Sam", "Rope?", System.currentTimeMillis())));
        relay.drain();
        String group = null;
        for (JSONObject c : server.calls("/api/relay/ingest")) {
            JSONObject it = c.getJSONArray("items").getJSONObject(0);
            if (it.optBoolean("group")) group = it.getString("conv");
        }
        assertNotNull(group);
        String gpath = stored(group, picture, System.currentTimeMillis(), "4444555566667777");
        queued("m:6:group", group, "pic", gpath, "image/png", picture.length, "p.png");
        relay.processOutbox(relay.hedy());
        reports = server.calls("/api/relay/outbox/result");
        assertEquals(NotSent.NO_ATTACH, reports.get(reports.size() - 1).getString("code"));

        // A file the inbox doesn't have: nothing goes out.
        queued("m:7:gone", conv, "pic", FakeHedy.v2MediaPath(relay.prefs.link().convId(conv), "1700000000000", "9999888877776666", "original"), "image/png", 100, "p.png");
        relay.processOutbox(relay.hedy());
        reports = server.calls("/api/relay/outbox/result");
        assertEquals(NotSent.ATTACH_FETCH, reports.get(reports.size() - 1).getString("code"));

        // A file named for another conversation inside the request: not this conversation's, so not sent at all.
        String other = stored("c-other", picture, System.currentTimeMillis(), "abcdabcdabcdabcd");
        queued("m:8:other", conv, "pic", other, "image/png", picture.length, "p.png");
        relay.processOutbox(relay.hedy());
        reports = server.calls("/api/relay/outbox/result");
        assertEquals(NotSent.UNCHECKED, reports.get(reports.size() - 1).getString("code"));
        assertNull(lastBroadcast("REPLY_C2"));
    }

    // ------------------------------------------------------------------ through Messages' own reply

    @Test
    public void whenMessagesReplyTakesPicturesTheFileIsHandedToItWithTheWords() throws Exception {
        long now = System.currentTimeMillis();
        ShadowSmsManager.SendMultimediaMessageParams before = shadowOf(SmsManager.getDefault()).getLastSentMultimediaMessageParams();
        relay.onNotification(withPictures("c2", "Bob", "REPLY_PIC", from("Bob", "send it over", now)));
        relay.drain();
        String conv = server.calls("/api/relay/ingest").get(0).getJSONArray("items").getJSONObject(0).getString("conv");
        byte[] picture = png(320, 240);
        String path = stored(conv, picture, now, "5555666677778888");
        queued("m:9:hand", conv, "here you go", path, "image/png", picture.length, "p.png");
        relay.processOutbox(relay.hedy());
        List<JSONObject> reports = server.calls("/api/relay/outbox/result");
        JSONObject report = reports.get(reports.size() - 1);
        assertEquals(report.optString("code", ""), "sent", report.getString("status"));
        assertEquals("rcs", report.getString("via"));
        Intent sent = lastBroadcast("REPLY_PIC");
        assertNotNull(sent);
        assertEquals("here you go", RemoteInput.getResultsFromIntent(sent).getCharSequence("reply_text").toString());
        Map<String, Uri> data = RemoteInput.getDataResultsFromIntent(sent, "reply_text");
        assertNotNull(data);
        Uri uri = data.get("image/png");
        assertNotNull(uri);
        assertEquals(app.getPackageName() + ".files", uri.getAuthority());
        assertEquals("image/png", app.getContentResolver().getType(uri));
        byte[] handed = read(uri);
        assertEquals(picture.length, handed.length);
        assertTrue(indexOf(handed, utf8("IHDR")) > 0);
        assertTrue("nothing went as MMS", before == shadowOf(SmsManager.getDefault()).getLastSentMultimediaMessageParams());
    }

    @Test
    public void theMessageItselfIsWellFormed() {
        byte[] picture = png(64, 48);
        byte[] pdu = Mms.sendRequest(java.util.Arrays.asList("+18015550123", "+18015550124"), new Mms.Part(picture, "image/png"), "hi ✓");
        int at = 0;
        assertEquals(0x8C, pdu[at++] & 0xFF);
        assertEquals(0x80, pdu[at++] & 0xFF);
        assertEquals(0x98, pdu[at++] & 0xFF);   // transaction id, a text-string
        while (pdu[at] != 0) at++;
        at++;
        assertEquals(0x8D, pdu[at++] & 0xFF);   // version
        assertEquals(0x92, pdu[at++] & 0xFF);   // 1.2
        assertEquals(0x85, pdu[at++] & 0xFF);   // date: long-integer
        int len = pdu[at++] & 0xFF;
        assertTrue(len >= 4 && len <= 5);
        at += len;
        assertEquals(0x89, pdu[at++] & 0xFF);   // from: insert-address-token
        assertEquals(1, pdu[at++] & 0xFF);
        assertEquals(0x81, pdu[at++] & 0xFF);
        assertEquals(0x97, pdu[at++] & 0xFF);   // to
        assertEquals("+18015550123/TYPE=PLMN", textAt(pdu, at));
        at += "+18015550123/TYPE=PLMN".length() + 1;
        assertEquals(0x97, pdu[at++] & 0xFF);
        assertEquals("+18015550124/TYPE=PLMN", textAt(pdu, at));
        // the content type comes last among the headers, as multipart/related starting at the layout
        int ct = indexOf(pdu, new byte[] { (byte) 0x84 });
        assertTrue(ct > at);
        assertTrue(indexOf(pdu, utf8("application/smil")) > ct);
        assertTrue(indexOf(pdu, utf8("hi ✓")) > ct);
        // a uintvar and a value-length past 30 are written in the long form
        Mms.Pdu p = new Mms.Pdu();
        p.uintvar(0x3FFF);
        assertEquals("ff7f", hex(p.bytes()));
        p = new Mms.Pdu();
        p.valueLength(200);
        assertEquals("1f8148", hex(p.bytes()));
        p = new Mms.Pdu();
        p.longInt(604800);
        assertEquals("03093a80", hex(p.bytes()));
        // recipients are cleaned, never invented
        assertEquals(java.util.Arrays.asList("+18015550123", "8015550124"), Mms.numbers(java.util.Arrays.asList("+1 (801) 555-0123", "801-555-0124", "", null, "Alice")));
    }

    private static String textAt(byte[] b, int at) {
        int end = at;
        while (b[end] != 0) end++;
        return new String(b, at, end - at, StandardCharsets.UTF_8);
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x & 0xFF));
        return s.toString();
    }

    private byte[] read(Uri uri) throws Exception {
        try (InputStream in = app.getContentResolver().openInputStream(uri)) {
            assertNotNull("readable through the grant", in);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    /** A Messages conversation whose reply action also takes pictures (as some apps' do). */
    private StatusBarNotification withPictures(String id, String title, String replyAction, Notification.MessagingStyle.Message... messages) {
        Person me = new Person.Builder().setName("You").setKey("me").build();
        Notification.MessagingStyle style = new Notification.MessagingStyle(me);
        for (Notification.MessagingStyle.Message m : messages) style.addMessage(m);
        Intent replyIntent = new Intent(replyAction).setPackage(app.getPackageName());
        PendingIntent pi = PendingIntent.getBroadcast(app, replyAction.hashCode(), replyIntent, PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        RemoteInput input = new RemoteInput.Builder("reply_text").setLabel("Reply").setAllowDataType("image/*", true).build();
        Notification.Action reply = new Notification.Action.Builder(null, "Reply", pi).addRemoteInput(input)
                .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY).build();
        Notification n = new Notification.Builder(app, "messages")
                .setSmallIcon(android.R.drawable.sym_action_chat)
                .setContentTitle(title)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setShortcutId("conversation_" + id)
                .setStyle(style)
                .addAction(reply)
                .build();
        return new StatusBarNotification(MESSAGES, MESSAGES, id.hashCode(), null, 10_001, 0, 0, n, Process.myUserHandle(), System.currentTimeMillis());
    }
}
