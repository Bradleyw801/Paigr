package com.textrelay.relay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Person;
import android.app.RemoteInput;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;
import android.service.notification.StatusBarNotification;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/** End-to-end checks against a fake inbox server and real Android notification objects. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class RelayTest {
    private static final String MESSAGES = "com.google.android.apps.messaging";
    static final String KEY = "hak_abcdef0123456789.abcDEFghiJKLmnoPQRstuVWXyz0123456789";

    /** This device's token in the test's private inbox (v2). */
    static final String TOKEN = "tok0123456789abcdefghijklmnopqrstuvwxyzABCD";
    /** The test inbox's key (v2). */
    static final byte[] INBOX_KEY = new byte[32];
    static {
        for (int i = 0; i < 32; i++) INBOX_KEY[i] = (byte) (i * 7 + 3);
    }

    FakeHedy server;
    Application app;
    Relay relay;

    /** False: the first version's inbox, with a device key. RelayV2Test runs every test against a private inbox. */
    protected boolean v2() {
        return false;
    }

    @Before
    public void setUp() throws IOException {
        server = new FakeHedy();
        Relay.reset();
        app = RuntimeEnvironment.getApplication();
        relay = Relay.get(app);
        connect(server.baseUrl(), KEY);
    }

    /** Points this phone at an inbox: v1 with the device key, or a private inbox; an empty key leaves it unlinked. */
    void connect(String base, String key) {
        if (!v2()) {
            relay.prefs.setServer(base, key);
            return;
        }
        relay.prefs.setServer(base, "");
        if (key == null || key.isEmpty()) {
            relay.prefs.clearLink();
            return;
        }
        relay.prefs.setLink(new Link(base, FakeHedy.TID, TOKEN, INBOX_KEY));
        relay.prefs.setWrapTag("done");   // the tests that care file it themselves
        server.link = relay.prefs.link();
    }

    /** What the server sees as this device's credential. */
    String credential() {
        return v2() ? TOKEN : KEY;
    }

    /** Where the server keeps a file, by the conversation and fingerprint the relay uses. */
    String mediaPath(String conv, long ts, String fp, String kind, String ext) {
        if (!v2()) return "/media/" + conv + "/" + ts + "-" + fp + "-" + kind + "." + ext;
        Link l = relay.prefs.link();
        return FakeHedy.v2MediaPath(l.convId(conv), Long.toString(ts), l.fpId(fp), "p".equals(kind) ? "preview" : "original");
    }

    @After
    public void tearDown() throws Exception {
        Relay.reset();
        server.close();
        settle();
    }

    /**
     * Lets the screens' network threads finish (the server is closed, so they fail fast) and runs what they post, so
     * none of it lands in the next test while Robolectric is still setting it up.
     */
    static void settle() throws InterruptedException {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            String n = t.getName();
            if (n.startsWith("relay-connect") || n.startsWith("relay-link")) t.join(10_000);
        }
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
    }

    // ------------------------------------------------------------------ texts up

    @Test
    public void relaysEachTextOnceWithRepliesShownInTheConversationAsOutgoing() throws Exception {
        long now = System.currentTimeMillis();
        StatusBarNotification first = conversation("c1", "Alice", false, "REPLY_C1",
                from("Alice", "Are we still on for 6?", now), mine("Yes", now + 1));
        relay.onNotification(first);
        relay.onNotification(first);   // Messages re-posts the same notification all the time
        relay.drain();

        List<JSONObject> ingests = server.calls("/api/relay/ingest");
        assertEquals(1, ingests.size());
        assertEquals(credential(), ingests.get(0).getString("_key"));
        JSONArray items = ingests.get(0).getJSONArray("items");
        assertEquals(2, items.length());
        JSONObject item = items.getJSONObject(0);
        assertEquals("Alice", item.getString("name"));
        assertEquals("Alice", item.getString("sender"));
        assertEquals("Are we still on for 6?", item.getString("text"));
        assertEquals(now, item.getLong("ts"));
        assertEquals(16, item.getString("fp").length());
        assertFalse(item.getBoolean("group"));
        assertEquals("in", item.getString("dir"));
        assertEquals("notif", item.getString("src"));
        // The reply Messages shows (sent from this phone, or from Texts through it): outgoing; the inbox folds its copies.
        JSONObject reply = items.getJSONObject(1);
        assertEquals("out", reply.getString("dir"));
        assertEquals("", reply.getString("sender"));
        assertEquals("Yes", reply.getString("text"));
        String conv = item.getString("conv");
        assertEquals(32, conv.length());   // SHA-256 of the conversation key, first 16 bytes; the raw key never leaves the phone
        assertTrue(ingests.get(0).getJSONObject("replyable").getBoolean(conv));

        StatusBarNotification second = conversation("c1", "Alice", false, "REPLY_C1",
                from("Alice", "Are we still on for 6?", now), mine("Yes", now + 1),
                from("Alice", "Great, see you", now + 2));
        relay.onNotification(second);
        relay.drain();
        ingests = server.calls("/api/relay/ingest");
        assertEquals(2, ingests.size());
        JSONArray more = ingests.get(1).getJSONArray("items");
        assertEquals(1, more.length());
        assertEquals("Great, see you", more.getJSONObject(0).getString("text"));
        assertEquals(conv, more.getJSONObject(0).getString("conv"));
    }

    @Test
    public void groupTextsCarryTheGroupNameAndSender() throws Exception {
        long now = System.currentTimeMillis();
        relay.onNotification(conversation("g1", "Climbing crew", true, "REPLY_G1",
                from("Sam", "Rope or bouldering?", now)));
        relay.drain();
        JSONObject item = server.calls("/api/relay/ingest").get(0).getJSONArray("items").getJSONObject(0);
        assertTrue(item.getBoolean("group"));
        assertEquals("Climbing crew", item.getString("name"));
        assertEquals("Sam", item.getString("sender"));
    }

    // ------------------------------------------------------------------ pictures and attachments

    @Test
    public void picturesGoUpUntouchedWithAPreviewBeforeTheText() throws Exception {
        long now = System.currentTimeMillis();
        byte[] original = bytes(300_000, 7);
        byte[] preview = bytes(20_000, 9);
        relay.mediaReader = (uri, mime) -> {
            assertEquals("content://media/1", uri.toString());
            return new Media.Prepared(new Media.Blob(original, "image/jpeg", 1600, 1200), new Media.Blob(preview, "image/jpeg", 1024, 768));
        };
        relay.onNotification(conversation("c9", "Alice", false, "REPLY_C9", picture("Alice", "image/jpeg", "content://media/1", now)));
        relay.drain();

        JSONObject item = server.calls("/api/relay/ingest").get(0).getJSONArray("items").getJSONObject(0);
        JSONObject media = item.getJSONObject("media");
        String conv = item.getString("conv");
        String fp = item.getString("fp");
        assertEquals("image/jpeg", media.getString("mime"));
        assertEquals(mediaPath(conv, now, fp, "o", "jpg"), media.getString("path"));
        assertEquals(mediaPath(conv, now, fp, "p", "jpg"), media.getString("preview"));
        assertEquals(1600, media.getInt("w"));
        assertEquals(1200, media.getInt("h"));
        assertEquals(original.length, media.getInt("bytes"));
        assertEquals("", item.getString("text"));

        // The server got the original byte for byte, and the preview, both with the device key, before the text.
        List<JSONObject> uploads = server.calls("/api/relay/media");
        assertEquals(2, uploads.size());
        JSONObject first = uploads.get(0);
        assertEquals(credential(), first.getString("_key"));
        assertEquals("original", first.getJSONObject("_headers").getString("x-relay-kind"));
        assertEquals(v2() ? "application/octet-stream" : "image/jpeg", first.getJSONObject("_headers").getString("content-type"));
        assertEquals(conv, first.getJSONObject("_headers").getString("x-relay-conv"));
        assertEquals(fp, first.getJSONObject("_headers").getString("x-relay-fp"));
        assertEquals(Long.toString(now), first.getJSONObject("_headers").getString("x-relay-ts"));
        assertEquals(FakeHedy.sha256(original), first.getString("_sha256"));
        assertEquals("preview", uploads.get(1).getJSONObject("_headers").getString("x-relay-kind"));
        assertEquals(FakeHedy.sha256(preview), uploads.get(1).getString("_sha256"));
        int ingestAt = server.calls.indexOf(server.calls("/api/relay/ingest").get(0));
        assertTrue(ingestAt > server.calls.indexOf(uploads.get(1)));
    }

    @Test
    public void bigFilesGoThroughAnUploadTicketWithoutTheDeviceKey() throws Exception {
        long now = System.currentTimeMillis();
        byte[] video = bytes(Hedy.DIRECT_MAX + 123_456, 3);
        relay.mediaReader = (uri, mime) -> new Media.Prepared(new Media.Blob(video, "video/mp4", 0, 0), null);
        relay.onNotification(conversation("c8", "Alice", false, "REPLY_C8", picture("Alice", "video/mp4", "content://media/2", now)));
        relay.drain();

        JSONObject ticket = server.calls("/api/relay/media/ticket").get(0);
        int sent = v2() ? video.length + 28 : video.length;   // sealed: 12 bytes of IV and a 16-byte tag more
        assertEquals(v2() ? "application/zip" : "video/mp4", ticket.getString("mime"));
        assertEquals(sent, ticket.getInt("bytes"));
        assertEquals("original", ticket.getString("kind"));
        assertEquals(credential(), ticket.getString("_key"));

        List<JSONObject> puts = new java.util.ArrayList<>();
        for (JSONObject c : server.calls) if (c.optString("_path").startsWith("/upload/")) puts.add(c);
        assertEquals(1, puts.size());
        assertEquals("PUT", puts.get(0).getString("_method"));
        assertEquals("", puts.get(0).getString("_key"));   // storage never sees the device key
        assertEquals(v2() ? "application/zip" : "video/mp4", puts.get(0).getJSONObject("_headers").getString("content-type"));
        assertEquals(Integer.toString(sent), puts.get(0).getJSONObject("_headers").getString("content-length"));
        assertEquals(FakeHedy.sha256(video), puts.get(0).getString("_sha256"));

        assertEquals(1, server.calls("/api/relay/media/complete").size());
        JSONObject media = server.calls("/api/relay/ingest").get(0).getJSONArray("items").getJSONObject(0).getJSONObject("media");
        assertTrue(media.getString("path"), media.getString("path").endsWith(v2() ? "-o.enc" : "-o.mp4"));
        assertEquals(video.length, media.getInt("bytes"));
        assertFalse(media.has("preview"));
        assertFalse(media.has("w"));
        assertTrue(server.calls("/api/relay/media").isEmpty());
    }

    @Test
    public void unreadableAttachmentsStillTellTheInboxWhatTheyWere() throws Exception {
        long now = System.currentTimeMillis();
        relay.mediaReader = (uri, mime) -> { throw new IOException("no permission to read the attachment"); };
        relay.onNotification(conversation("c7", "Alice", false, "REPLY_C7", picture("Alice", "image/png", "content://media/3", now)));
        relay.drain();
        JSONObject media = server.calls("/api/relay/ingest").get(0).getJSONArray("items").getJSONObject(0).getJSONObject("media");
        assertEquals("image/png", media.getString("mime"));
        assertFalse(media.has("path"));
        assertTrue(server.calls("/api/relay/media").isEmpty());
        assertTrue(relay.lastMediaError, relay.lastMediaError.contains("attachment"));
    }

    @Test
    public void anAttachmentTheInboxCantStoreIsNamedButNotUploaded() throws Exception {
        long now = System.currentTimeMillis();
        relay.mediaReader = (uri, mime) -> new Media.Prepared(new Media.Blob(bytes(4000, 3), "audio/amr", 0, 0), null);
        relay.onNotification(conversation("c8", "Alice", false, "REPLY_C8", picture("Alice", "audio/amr", "content://media/8", now)));
        relay.drain();
        JSONObject media = server.calls("/api/relay/ingest").get(0).getJSONArray("items").getJSONObject(0).getJSONObject("media");
        assertEquals("audio/amr", media.getString("mime"));
        assertFalse(media.has("path"));
        assertTrue(server.calls("/api/relay/media").isEmpty());   // not even tried
        assertTrue(relay.lastMediaError, relay.lastMediaError.contains("can't store"));
        assertTrue(Media.sendable("image/heic"));                   // turned into a JPEG first
        assertFalse(Media.sendable("audio/amr"));
        assertFalse(Media.sendable("video/3gpp"));
    }

    @Test
    public void aPictureTheInboxKeepsFailingOnGoesUpWithoutItAndTheNextOneFollows() throws Exception {
        long saved = Relay.laneBackoffMs;
        Relay.laneBackoffMs = 5;
        try {
            Relay.reset();
            relay = Relay.get(app);
            connect(server.baseUrl(), KEY);
            long now = System.currentTimeMillis();
            relay.mediaReader = (uri, mime) -> new Media.Prepared(new Media.Blob(bytes(1000, 1), "image/png", 10, 10), null);
            server.failMediaTimes.set(Relay.MEDIA_GIVE_UP);   // the first picture fails every time
            relay.onNotification(conversation("c9", "Alice", false, "REPLY_C9",
                    picture("Alice", "image/png", "content://media/91", now), picture("Alice", "image/png", "content://media/92", now + 1000)));
            relay.drain();
            List<JSONObject> items = new java.util.ArrayList<>();
            for (JSONObject call : server.calls("/api/relay/ingest")) {
                JSONArray arr = call.getJSONArray("items");
                for (int i = 0; i < arr.length(); i++) items.add(arr.getJSONObject(i));
            }
            assertEquals(2, items.size());
            JSONObject first = items.get(0).getLong("ts") < items.get(1).getLong("ts") ? items.get(0) : items.get(1);
            JSONObject second = first == items.get(0) ? items.get(1) : items.get(0);
            assertFalse(first.getJSONObject("media").has("path"));   // in the inbox, named, without the file
            assertEquals("image/png", first.getJSONObject("media").getString("mime"));
            assertTrue(second.getJSONObject("media").has("path"));   // and the next picture wasn't held up
            assertEquals(Relay.MEDIA_GIVE_UP + 1, server.calls("/api/relay/media").size());
            assertTrue(relay.lastMediaError, relay.lastMediaError.contains("Gave up"));
        } finally {
            Relay.laneBackoffMs = saved;
        }
    }

    @Test
    public void aRefusedUploadStillDeliversTheText() throws Exception {
        long now = System.currentTimeMillis();
        relay.mediaReader = (uri, mime) -> new Media.Prepared(new Media.Blob(bytes(1000, 1), "image/png", 10, 10), null);
        server.rejectMedia = true;
        relay.onNotification(conversation("c6", "Alice", false, "REPLY_C6",
                picture("Alice", "image/png", "content://media/4", now), from("Alice", "did you get it?", now + 5)));
        relay.drain();
        // Texts and attachments travel on separate lanes, so these arrive as two deliveries.
        List<JSONObject> items = new java.util.ArrayList<>();
        for (JSONObject call : server.calls("/api/relay/ingest")) {
            JSONArray arr = call.getJSONArray("items");
            for (int i = 0; i < arr.length(); i++) items.add(arr.getJSONObject(i));
        }
        assertEquals(2, items.size());
        JSONObject pic = items.get(0).has("media") ? items.get(0) : items.get(1);
        JSONObject txt = pic == items.get(0) ? items.get(1) : items.get(0);
        assertEquals("image/png", pic.getJSONObject("media").getString("mime"));
        assertFalse(pic.getJSONObject("media").has("path"));
        assertEquals("did you get it?", txt.getString("text"));
        assertEquals(1, server.calls("/api/relay/media").size());   // a 4xx is not retried
        assertEquals(0, relay.outbound.size());
    }

    @Test
    public void nothingIsLostWhileTheServerIsAway() throws Exception {
        long now = System.currentTimeMillis();
        String good = server.baseUrl();
        connect("http://127.0.0.1:1", KEY);   // nothing listens here
        relay.onNotification(conversation("c4", "Bob", false, "REPLY_C4", from("Bob", "first", now)));
        relay.onNotification(conversation("c4", "Bob", false, "REPLY_C4", from("Bob", "first", now), from("Bob", "second", now + 1)));
        Thread.sleep(300);
        assertEquals(2, relay.outbound.size());
        assertTrue(server.calls("/api/relay/ingest").isEmpty());

        // The queue survives the process: a fresh Relay reads it back from disk.
        Relay.reset();
        relay = Relay.get(app);
        assertEquals(2, relay.outbound.size());
        connect(good, KEY);
        relay.drain();
        List<JSONObject> ingests = server.calls("/api/relay/ingest");
        assertEquals(1, ingests.size());
        JSONArray items = ingests.get(0).getJSONArray("items");
        assertEquals(2, items.length());
        assertEquals("first", items.getJSONObject(0).getString("text"));
        assertEquals("second", items.getJSONObject(1).getString("text"));
        assertEquals(0, relay.outbound.size());
    }

    private static byte[] bytes(int n, int seed) {
        byte[] b = new byte[n];
        new java.util.Random(seed).nextBytes(b);
        return b;
    }

    private Notification.MessagingStyle.Message picture(String name, String mime, String uri, long time) {
        Person p = new Person.Builder().setName(name).setKey(name.toLowerCase(Locale.US)).build();
        return new Notification.MessagingStyle.Message("", time, p).setData(mime, android.net.Uri.parse(uri));
    }

    @Test
    public void otherAppsAreIgnored() {
        long now = System.currentTimeMillis();
        StatusBarNotification other = conversation("x1", "Alice", false, "REPLY_X1", from("Alice", "hi", now));
        StatusBarNotification fromOtherApp = new StatusBarNotification("com.whatsapp", "com.whatsapp", 1, null,
                10_002, 0, 0, other.getNotification(), Process.myUserHandle(), now);
        relay.onNotification(fromOtherApp);
        relay.drain();
        assertTrue(server.calls("/api/relay/ingest").isEmpty());
    }

    @Test
    public void textsFromBeforeInstallAreNotRelayed() {
        long old = relay.prefs.installTs() - 10 * 60_000;
        relay.onNotification(conversation("c3", "Carol", false, "REPLY_C3", from("Carol", "old news", old)));
        relay.drain();
        assertTrue(server.calls("/api/relay/ingest").isEmpty());
    }

    @Test
    public void nothingIsSentBeforeTheKeyIsSaved() {
        connect(server.baseUrl(), "");
        long now = System.currentTimeMillis();
        relay.onNotification(conversation("c4", "Dan", false, "REPLY_C4", from("Dan", "hello?", now)));
        relay.drain();
        assertTrue(server.calls("/api/relay/ingest").isEmpty());
    }

    @Test
    public void aRejectedKeyKeepsTheTextForAfterRePairing() throws Exception {
        org.junit.Assume.assumeFalse("a private inbox removes a device instead; see RelayV2Test", v2());
        server.expectedKey = "hak_other.key";
        long now = System.currentTimeMillis();
        relay.onNotification(conversation("c5", "Eve", false, "REPLY_C5", from("Eve", "hi", now)));
        Thread.sleep(800);
        assertEquals(1, server.calls("/api/relay/ingest").size());   // tried once, then waits a minute, not a tight loop
        assertEquals(1, relay.outbound.size());
        assertNotNull(relay.lastError);
        assertTrue(relay.lastError, relay.lastError.contains("401"));

        server.expectedKey = null;
        relay.prefs.setServer(server.baseUrl(), "hak_other.key");
        relay.drain();
        assertEquals(2, server.calls("/api/relay/ingest").size());
        assertEquals(0, relay.outbound.size());
    }

    @Test
    public void aBadRequestIsDroppedRatherThanWedgingTheQueue() throws Exception {
        server.rejectIngestWith = 400;
        long now = System.currentTimeMillis();
        relay.onNotification(conversation("c3", "Mal", false, "REPLY_C3", from("Mal", "bad", now)));
        relay.drain();
        assertEquals(1, server.calls("/api/relay/ingest").size());
        assertEquals(0, relay.outbound.size());
        server.rejectIngestWith = 0;
        relay.onNotification(conversation("c3", "Mal", false, "REPLY_C3", from("Mal", "bad", now), from("Mal", "good", now + 1)));
        relay.drain();
        assertEquals(2, server.calls("/api/relay/ingest").size());
    }

    // ------------------------------------------------------------------ replies down

    @Test
    public void queuedReplyGoesOutThroughMessagesReplyActionAndIsReported() throws Exception {
        long now = System.currentTimeMillis();
        relay.onNotification(conversation("c2", "Bob", false, "REPLY_C2", from("Bob", "Call me?", now)));
        relay.drain();
        String conv = server.calls("/api/relay/ingest").get(0).getJSONArray("items").getJSONObject(0).getString("conv");

        server.pendingOutbox.add(new JSONObject().put("id", "m:1:abc").put("conv", conv).put("text", "In 10 minutes").put("ts", now));
        int poll = relay.processOutbox(relay.hedy());
        assertEquals(5, poll);

        Intent sent = lastBroadcast("REPLY_C2");
        assertNotNull("reply should be sent through the notification's reply action", sent);
        Bundle results = RemoteInput.getResultsFromIntent(sent);
        assertNotNull(results);
        assertEquals("In 10 minutes", results.getCharSequence("reply_text").toString());

        List<JSONObject> reports = server.calls("/api/relay/outbox/result");
        assertEquals(1, reports.size());
        assertEquals("m:1:abc", reports.get(0).getString("id"));
        assertEquals("sent", reports.get(0).getString("status"));
        assertTrue(reports.get(0).getBoolean("canReply"));
    }

    @Test
    public void aReplyWhoseReportGotLostIsReportedAgainNotSentTwice() throws Exception {
        long now = System.currentTimeMillis();
        relay.onNotification(conversation("c2", "Bob", false, "REPLY_C2", from("Bob", "Call me?", now)));
        relay.drain();
        String conv = server.calls("/api/relay/ingest").get(0).getJSONArray("items").getJSONObject(0).getString("conv");
        JSONObject queued = new JSONObject().put("id", "m:1:lost").put("conv", conv).put("text", "Calling now").put("ts", now);
        server.pendingOutbox.add(queued);
        server.failNextResult = true;
        try {
            relay.processOutbox(relay.hedy());
            throw new AssertionError("the lost report should surface");
        } catch (IOException expected) {
            // the reply went out; telling the inbox didn't
        }
        server.pendingOutbox.add(queued);   // still queued on the inbox, so it comes back
        relay.processOutbox(relay.hedy());
        int sends = 0;
        for (Intent i : shadowOf(app).getBroadcastIntents()) if ("REPLY_C2".equals(i.getAction())) sends++;
        assertEquals(1, sends);
        List<JSONObject> reports = server.calls("/api/relay/outbox/result");
        assertEquals(2, reports.size());
        assertEquals("sent", reports.get(1).getString("status"));
        assertNull(relay.prefs.outboxReport("m:1:lost"));
    }

    @Test
    public void replyWithoutAnOpenNotificationIsReportedFailed() throws Exception {
        server.pendingOutbox.add(new JSONObject().put("id", "m:2:def").put("conv", "0123456789abcdef").put("text", "hello").put("ts", 1));
        relay.processOutbox(relay.hedy());
        List<JSONObject> reports = server.calls("/api/relay/outbox/result");
        assertEquals(1, reports.size());
        assertEquals("failed", reports.get(0).getString("status"));
        assertFalse(reports.get(0).getBoolean("canReply"));
        assertEquals(NotSent.NO_FALLBACK, reports.get(0).getString("code"));   // a private inbox gets only the code...
        if (reports.get(0).has("error")) assertEquals(NotSent.words(NotSent.NO_FALLBACK), reports.get(0).getString("error"));   // ...the first version's, the words
        assertEquals("rcs", reports.get(0).getString("via"));
        assertNull(lastBroadcast("REPLY_NONE"));
    }

    @Test
    public void withoutAHandleTheReplyGoesOutAsSmsWhenTheNumberIsKnown() throws Exception {
        long now = System.currentTimeMillis();
        shadowOf(app).grantPermissions(android.Manifest.permission.SEND_SMS);
        // Messages tells us the number on the Person; the conversation then has no reply action at all.
        Person alice = new Person.Builder().setName("Alice").setKey("alice").setUri("tel:+18015550123").build();
        StatusBarNotification sbn = conversation("c2", "Alice", false, "REPLY_C2",
                new Notification.MessagingStyle.Message("hey", now, alice));
        relay.onNotification(sbn);
        relay.drain();
        String conv = server.calls("/api/relay/ingest").get(0).getJSONArray("items").getJSONObject(0).getString("conv");
        assertEquals("+18015550123", relay.prefs.number(conv));

        Relay.reset();                       // a restart: reply handles are gone
        relay = Relay.get(app);
        server.pendingOutbox.add(new JSONObject().put("id", "m:3:sms").put("conv", conv).put("text", "on my way").put("ts", 1));
        relay.processOutbox(relay.hedy());
        JSONObject report = server.calls("/api/relay/outbox/result").get(0);
        assertEquals(report.optString("error", ""), "sent", report.getString("status"));
        assertEquals("sms", report.getString("via"));
        assertFalse(report.getBoolean("canReply"));
        org.robolectric.shadows.ShadowSmsManager.TextMultipartParams sent =
                shadowOf(android.telephony.SmsManager.getDefault()).getLastSentMultipartTextMessageParams();
        assertNotNull(sent);
        assertEquals("+18015550123", sent.getDestinationAddress());
        assertEquals("on my way", String.join("", sent.getParts()));
    }

    @Test
    public void groupChatsNeverFallBackToSms() throws Exception {
        shadowOf(app).grantPermissions(android.Manifest.permission.SEND_SMS);
        long now = System.currentTimeMillis();
        relay.onNotification(conversation("g2", "Climbing crew", true, "REPLY_G2", from("Sam", "Rope?", now)));
        relay.drain();
        String conv = server.calls("/api/relay/ingest").get(0).getJSONArray("items").getJSONObject(0).getString("conv");
        Relay.reset();
        relay = Relay.get(app);
        server.pendingOutbox.add(new JSONObject().put("id", "m:4:grp").put("conv", conv).put("text", "both").put("ts", 1));
        Object smsBefore = shadowOf(android.telephony.SmsManager.getDefault()).getLastSentMultipartTextMessageParams();
        relay.processOutbox(relay.hedy());
        JSONObject report = server.calls("/api/relay/outbox/result").get(0);
        assertEquals("failed", report.getString("status"));
        // nothing new went out as SMS (the shadow keeps the last one from any earlier test)
        assertTrue(smsBefore == shadowOf(android.telephony.SmsManager.getDefault()).getLastSentMultipartTextMessageParams());
    }

    @Test
    public void pollerSendsHeartbeatAndMarksVerified() throws Exception {
        relay.startPoller();
        long deadline = System.currentTimeMillis() + 10_000;
        while (server.calls("/api/relay/heartbeat").isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(25);
        assertEquals(1, server.calls("/api/relay/heartbeat").size());
        JSONObject hb = server.calls("/api/relay/heartbeat").get(0);
        assertEquals(Relay.VERSION, hb.getString("version"));
        assertTrue(hb.has("listening"));
        assertTrue(hb.has("battery"));
        waitFor(() -> relay.prefs.verified());   // marked once the poll round that sent it finishes
        assertFalse(server.calls("/api/relay/outbox").isEmpty());
    }

    // ------------------------------------------------------------------ linking

    @Test
    public void aPhoneWithoutAnAndroidIdHasNoTag() {
        android.provider.Settings.Secure.putString(app.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID, "9774d56d682e549c");
        assertNull(Pairing.phoneTag(app, "relay"));
        android.provider.Settings.Secure.putString(app.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID, null);
        assertNull(Pairing.phoneTag(app, "relay"));
    }

    @Test
    public void linkADeviceShowsACodeForTextsToScan() throws Exception {
        org.junit.Assume.assumeFalse("see RelayV2Test", v2());
        relay.prefs.setVerified(true);
        MainActivity activity = org.robolectric.Robolectric.buildActivity(MainActivity.class).setup().get();
        click(activity, "Link a device");
        android.widget.ImageView qr = findQr(activity.findViewById(android.R.id.content), "QR code for linking a phone");
        long until = System.currentTimeMillis() + 10_000;
        while (!(shown(qr) && qr.getDrawable() != null) && System.currentTimeMillis() < until) {
            Thread.sleep(20);
            org.robolectric.shadows.ShadowLooper.idleMainLooper();
        }
        String payload = decode(qr);
        assertTrue(payload, payload.matches("TEXTRELAY:LINK:[0-9A-Z]{12}"));
        JSONObject registered = server.calls("/api/pair/codes").get(0);
        assertEquals("inbox", registered.getString("role"));
        assertEquals(Pairing.sha256Hex(Pairing.normalize(payload.substring(15))), registered.getString("hash"));
        assertEquals(KEY, registered.getString("_key"));
    }

    static android.widget.ImageView findQr(android.view.View v, String description) {
        if (v instanceof android.widget.ImageView && v.getContentDescription() != null
                && description.contentEquals(v.getContentDescription())) return (android.widget.ImageView) v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                android.widget.ImageView q = findQr(g.getChildAt(i), description);
                if (q != null) return q;
            }
        }
        return null;
    }

    static String decode(android.widget.ImageView view) throws Exception {
        android.graphics.Bitmap bmp = ((android.graphics.drawable.BitmapDrawable) view.getDrawable()).getBitmap();
        int[] px = new int[bmp.getWidth() * bmp.getHeight()];
        bmp.getPixels(px, 0, bmp.getWidth(), 0, 0, bmp.getWidth(), bmp.getHeight());
        com.google.zxing.BinaryBitmap image = new com.google.zxing.BinaryBitmap(new com.google.zxing.common.HybridBinarizer(
                new com.google.zxing.RGBLuminanceSource(bmp.getWidth(), bmp.getHeight(), px)));
        // A drawn code, not a photo of one: read it as such (no guessing at perspective or blur).
        java.util.Map<com.google.zxing.DecodeHintType, Object> hints = new java.util.EnumMap<>(com.google.zxing.DecodeHintType.class);
        hints.put(com.google.zxing.DecodeHintType.PURE_BARCODE, Boolean.TRUE);
        hints.put(com.google.zxing.DecodeHintType.TRY_HARDER, Boolean.TRUE);
        return new com.google.zxing.qrcode.QRCodeReader().decode(image, hints).getText();
    }

    // ------------------------------------------------------------------ reconnect and resync

    @Test
    public void aReconnectRequestResendsWhatMessagesShowsAndChecksIn() throws Exception {
        long now = System.currentTimeMillis();
        StatusBarNotification sbn = conversation("c1", "Alice", false, "REPLY_C1", from("Alice", "Still on for 6?", now));
        relay.onNotification(sbn);
        relay.drain();
        assertEquals(1, server.calls("/api/relay/ingest").size());
        relay.active = () -> new StatusBarNotification[] { sbn };

        // An inbox device pressed Reconnect or Resync: the next outbox poll carries the request.
        server.wake = now + 5;
        relay.processOutbox(relay.hedy());
        waitFor(() -> server.calls("/api/relay/ingest").size() >= 2);
        relay.drain();
        JSONArray again = server.calls("/api/relay/ingest").get(1).getJSONArray("items");
        assertEquals(1, again.length());
        assertEquals("Still on for 6?", again.getJSONObject(0).getString("text"));   // the inbox keeps one copy
        assertEquals(now + 5, relay.prefs.wakeHandled());
        assertTrue(relay.lastResync > 0);
        assertEquals(1, relay.lastResyncConversations);
        assertEquals(1, relay.lastResyncTexts);

        // The same request is acted on once, however many polls and heartbeats repeat it.
        relay.processOutbox(relay.hedy());
        relay.heartbeat(relay.hedy());
        Thread.sleep(300);
        relay.drain();
        assertEquals(2, server.calls("/api/relay/ingest").size());
        JSONObject hb = server.calls("/api/relay/heartbeat").get(server.calls("/api/relay/heartbeat").size() - 1);
        assertEquals(now + 5, hb.getLong("wakeHandled"));
    }

    @Test
    public void aResyncDoesNotUploadAttachmentsTwice() throws Exception {
        long now = System.currentTimeMillis();
        relay.mediaReader = (uri, mime) -> new Media.Prepared(new Media.Blob(bytes(5000, 2), "image/jpeg", 10, 10), null);
        StatusBarNotification sbn = conversation("c5", "Alice", false, "REPLY_C5",
                picture("Alice", "image/jpeg", "content://media/9", now), from("Alice", "see it?", now + 1));
        relay.onNotification(sbn);
        relay.drain();
        assertEquals(1, server.calls("/api/relay/media").size());
        relay.active = () -> new StatusBarNotification[] { sbn };
        assertTrue(relay.rescan(true));
        relay.drain();
        assertEquals(1, server.calls("/api/relay/media").size());
        assertEquals(1, relay.lastResyncTexts);   // the text goes up again; the picture does not
    }

    @Test
    public void theQueueNeverHoldsTheSameTextTwice() throws Exception {
        connect("http://127.0.0.1:1", KEY);   // nothing listens: everything stays queued
        long now = System.currentTimeMillis();
        StatusBarNotification sbn = conversation("c6", "Bob", false, "REPLY_C6", from("Bob", "once", now));
        assertEquals(1, relay.onNotification(sbn, false));
        assertEquals(0, relay.onNotification(sbn, true));   // already waiting
        assertEquals(1, relay.outbound.size());
    }

    @Test
    public void withoutTheListenerAResyncAsksAndroidToReconnectIt() {
        relay.active = () -> null;
        assertFalse(relay.rescan(true));
        assertEquals(0, relay.lastResync);
    }

    // ------------------------------------------------------------------ status notification

    @Test
    public void theStatusNotificationIsSilentAndCanBeHiddenEntirely() {
        RelayService.createChannels(app);
        android.app.NotificationManager nm = app.getSystemService(android.app.NotificationManager.class);
        android.app.NotificationChannel channel = nm.getNotificationChannel(RelayService.CHANNEL);
        assertEquals(android.app.NotificationManager.IMPORTANCE_LOW, channel.getImportance());
        assertNull(channel.getSound());
        assertNull(nm.getNotificationChannel(RelayService.OLD_MINIMAL));

        assertEquals(Prefs.STATUS_SHOWN, relay.prefs.statusMode());
        Notification shown = RelayService.build(app);
        assertEquals(RelayService.CHANNEL, shown.getChannelId());
        assertEquals(Notification.VISIBILITY_SECRET, shown.visibility);
        assertTrue((shown.flags & Notification.FLAG_ONGOING_EVENT) != 0);
        RelayService.start(app);
        Intent started = shadowOf(app).getNextStartedService();
        assertNotNull(started);
        assertEquals(RelayService.class.getName(), started.getComponent().getClassName());

        // Hidden: no service and no notification; the relay keeps running in the notification listener's process.
        relay.prefs.setStatusMode(Prefs.STATUS_HIDDEN);
        RelayService.start(app);
        assertNull(shadowOf(app).getNextStartedService());
        assertEquals(RelayService.class.getName(), shadowOf(app).getNextStoppedService().getComponent().getClassName());
    }

    @Test
    public void earlierStatusChoicesReadAsShown() {
        relay.prefs.raw().edit().putString("status_mode", "minimal").apply();
        assertEquals(Prefs.STATUS_SHOWN, relay.prefs.statusMode());
        relay.prefs.raw().edit().putString("status_mode", "standard").apply();
        assertEquals(Prefs.STATUS_SHOWN, relay.prefs.statusMode());
    }

    // ------------------------------------------------------------------ guide

    @Test
    public void theGuideOpensCallSettingsFirstWithCarrierCodesAsTheFallback() {
        relay.prefs.setCarryNumber("(801) 555-0123");
        WizardActivity w = org.robolectric.Robolectric.buildActivity(WizardActivity.class).setup().get();
        assertTrue(allText(w.findViewById(android.R.id.content)).contains("Leave this phone at home"));
        w.go(3);
        android.view.View content = w.findViewById(android.R.id.content);
        String screen = allText(content);
        assertTrue(screen, screen.contains("Forward your calls"));
        assertTrue(screen, screen.contains("4 of 5"));
        click(w, "Open call settings");
        assertEquals(android.telecom.TelecomManager.ACTION_SHOW_CALL_SETTINGS, shadowOf(w).getNextStartedActivity().getAction());
        // The carrier's codes wait behind an expander.
        android.widget.Button forward = findButton(content, "Forward all calls");
        assertFalse(shown(forward));
        clickText(content, "Use your carrier's code");
        assertTrue(shown(forward));
        android.widget.EditText number = findFirst(content, android.widget.EditText.class);
        number.setText("(801) 555-0123");
        click(w, "Forward all calls");
        Intent dial = shadowOf(w).getNextStartedActivity();
        assertEquals(Intent.ACTION_DIAL, dial.getAction());
        assertEquals("tel:**21*8015550123%23", dial.getDataString());
        click(w, "Stop forwarding");
        assertEquals("tel:%23%2321%23", shadowOf(w).getNextStartedActivity().getDataString());
        assertEquals("8015550123", relay.prefs.carryNumber());

        assertEquals(WizardActivity.Carrier.VERIZON, WizardActivity.Carrier.guess("Verizon"));
        assertEquals(WizardActivity.Carrier.US_CELLULAR, WizardActivity.Carrier.guess("U.S. Cellular"));
        assertEquals(WizardActivity.Carrier.GSM, WizardActivity.Carrier.guess("T-Mobile"));
        assertEquals("*728015550123", WizardActivity.Carrier.VERIZON.forwardTo("8015550123"));
        assertEquals("+18015550123", WizardActivity.digits(" +1 (801) 555-0123 "));

        w.go(4);
        assertTrue(allText(w.findViewById(android.R.id.content)).contains("Try it"));
    }

    @Test
    public void theGuideAndTheScreenDrawBelowTheStatusBar() {
        WizardActivity w = org.robolectric.Robolectric.buildActivity(WizardActivity.class).setup().get();
        android.view.ViewGroup content = w.findViewById(android.R.id.content);
        assertTrue(content.getChildAt(0).getFitsSystemWindows());
        MainActivity m = org.robolectric.Robolectric.buildActivity(MainActivity.class).setup().get();
        android.view.ViewGroup main = m.findViewById(android.R.id.content);
        assertTrue(main.getChildAt(0).getFitsSystemWindows());
        // No action bar sits on top of the first page's heading.
        assertNull(w.getActionBar());
    }

    static boolean shown(android.view.View v) {
        for (android.view.View x = v; x != null; x = x.getParent() instanceof android.view.View ? (android.view.View) x.getParent() : null) {
            if (x.getVisibility() != android.view.View.VISIBLE) return false;
        }
        return true;
    }

    /** Taps a piece of text: the text itself when it's a button, or the row or card it sits on. */
    private static void clickText(android.view.View root, String text) {
        android.widget.TextView t = findText(root, text);
        assertNotNull("no text " + text, t);
        android.view.View v = t;
        while (!v.isClickable() && v.getParent() instanceof android.view.View) v = (android.view.View) v.getParent();
        v.performClick();
    }

    static android.widget.TextView findText(android.view.View v, String text) {
        if (v instanceof android.widget.TextView && text.contentEquals(((android.widget.TextView) v).getText())) return (android.widget.TextView) v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                android.widget.TextView t = findText(g.getChildAt(i), text);
                if (t != null) return t;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ screen

    @Test
    public void theScreenSaysHowItsDoingAndShowsOnlyWhatsMissing() {
        relay.prefs.setVerified(true);
        MainActivity activity = org.robolectric.Robolectric.buildActivity(MainActivity.class).setup().get();
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        android.view.View content = activity.findViewById(android.R.id.content);
        String screen = visibleText(content);
        // Without notification access it's paused, and the one thing to do is right there.
        assertTrue(screen, screen.contains("Paused"));
        assertTrue(screen, screen.contains("Finish setting up"));
        assertTrue(screen, screen.contains("Notification access"));
        assertTrue(screen, screen.contains("Background use"));
        assertTrue(screen, screen.contains("Link a device"));
        assertTrue(screen, screen.contains("Sync now"));
        assertTrue(screen, screen.contains("Message history"));
        assertTrue(screen, screen.contains("SMS fallback"));
        assertTrue(screen, screen.contains("Leave this phone at home"));
        if (v2()) assertFalse(screen, screen.contains("first version"));
        else assertTrue(screen, screen.contains("Relaying to your inbox (first version)"));
        assertFalse(screen, screen.contains("127.0.0.1"));
        assertFalse(screen, screen.contains("Instant channel"));   // the details wait behind the card

        // Given what it needs, it's relaying, and the list of what's missing goes away.
        shadowOf(app.getSystemService(android.app.NotificationManager.class))
                .setNotificationListenerAccessGranted(new android.content.ComponentName(app, RelayListenerService.class), true);
        shadowOf(app.getSystemService(android.os.PowerManager.class)).setIgnoringBatteryOptimizations(app.getPackageName(), true);
        tick();
        tick();
        tick();
        tick();
        screen = visibleText(content);
        assertTrue(screen, screen.contains("Relaying"));
        assertFalse(screen, screen.contains("Finish setting up"));
        assertFalse(screen, screen.contains("Notification access"));

        // Tapping the card shows the details.
        clickText(content, "Relaying");
        screen = visibleText(content);
        assertTrue(screen, screen.contains("Instant channel"));
        assertTrue(screen, screen.contains(v2() ? "Linked to your private inbox, end-to-end encrypted." : "First-version inbox (not encrypted)."));

        // The rest is a tap away, under More.
        MoreActivity more = org.robolectric.Robolectric.buildActivity(MoreActivity.class).setup().get();
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        android.view.ViewGroup moreContent = more.findViewById(android.R.id.content);
        String settings = visibleText(moreContent);
        for (String s : new String[] { "Allow message access", "Allow SMS fallback", "Status notification", "Shown", "Hidden",
                "Inbox server", "Get updates" }) {
            assertTrue(s + " in " + settings, settings.contains(s));
        }
        assertFalse(settings, settings.contains("Sync full history"));   // not before message access
        shadowOf(app).grantPermissions(android.Manifest.permission.READ_SMS, android.Manifest.permission.READ_CONTACTS);
        more.refresh();
        settings = visibleText(moreContent);
        for (String s : new String[] { "On, with names", "Text only", "With pictures", "Sync full history" }) {
            assertTrue(s + " in " + settings, settings.contains(s));
        }
        assertFalse(settings, settings.contains("Allow message access"));
        assertTrue(moreContent.getChildAt(0).getFitsSystemWindows());
        clickText(moreContent, "Hidden");
        assertEquals(Prefs.STATUS_HIDDEN, relay.prefs.statusMode());
        clickText(moreContent, "Get updates");
        assertEquals(WizardActivity.REPO_URL, shadowOf(more).getNextStartedActivity().getDataString());
    }

    /** The text a person can see: what's on screen, not what's tucked away. */
    static String visibleText(android.view.View v) {
        if (v.getVisibility() != android.view.View.VISIBLE) return "";
        StringBuilder b = new StringBuilder();
        if (v instanceof android.widget.TextView) b.append(((android.widget.TextView) v).getText()).append('\n');
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) b.append(visibleText(g.getChildAt(i)));
        }
        return b.toString();
    }

    /** Lets the main looper's clock run on a little, so delayed posts (a panel's next check) happen. */
    static void tick() {
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500));
    }

    static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long until = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < until) Thread.sleep(20);
        assertTrue("timed out", condition.getAsBoolean());
    }

    static void click(android.app.Activity a, String label) {
        android.widget.Button b = findButton(a.findViewById(android.R.id.content), label);
        assertNotNull("no button " + label, b);
        b.performClick();
    }

    private static android.widget.Button findButton(android.view.View v, String label) {
        if (v instanceof android.widget.Button && label.contentEquals(((android.widget.Button) v).getText())) return (android.widget.Button) v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                android.widget.Button b = findButton(g.getChildAt(i), label);
                if (b != null) return b;
            }
        }
        return null;
    }

    private static <T extends android.view.View> T findFirst(android.view.View v, Class<T> type) {
        if (type.isInstance(v)) return type.cast(v);
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                T t = findFirst(g.getChildAt(i), type);
                if (t != null) return t;
            }
        }
        return null;
    }

    static String allText(android.view.View v) {
        StringBuilder b = new StringBuilder();
        if (v instanceof android.widget.TextView) b.append(((android.widget.TextView) v).getText()).append('\n');
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) b.append(allText(g.getChildAt(i)));
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ fixtures

    StatusBarNotification conversation(String id, String title, boolean group, String replyAction,
                                               Notification.MessagingStyle.Message... messages) {
        Person me = new Person.Builder().setName("You").setKey("me").build();
        Notification.MessagingStyle style = new Notification.MessagingStyle(me);
        if (group) {
            style.setConversationTitle(title);
            style.setGroupConversation(true);
        }
        for (Notification.MessagingStyle.Message m : messages) style.addMessage(m);

        Intent replyIntent = new Intent(replyAction).setPackage(app.getPackageName());
        PendingIntent pi = PendingIntent.getBroadcast(app, replyAction.hashCode(), replyIntent,
                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        RemoteInput input = new RemoteInput.Builder("reply_text").setLabel("Reply").build();
        Notification.Action reply = new Notification.Action.Builder(null, "Reply", pi)
                .addRemoteInput(input)
                .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
                .build();
        // Messages puts "Mark as read" first; the relay must pick the reply action, not this one.
        PendingIntent readPi = PendingIntent.getBroadcast(app, ("READ_" + id).hashCode(),
                new Intent("READ_" + id).setPackage(app.getPackageName()), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(app, "messages")
                .setSmallIcon(android.R.drawable.sym_action_chat)
                .setContentTitle(title)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setShortcutId("conversation_" + id)
                .setStyle(style)
                .addAction(new Notification.Action.Builder(null, "Mark as read", readPi)
                        .setSemanticAction(Notification.Action.SEMANTIC_ACTION_MARK_AS_READ).build())
                .addAction(reply)
                .build();
        return new StatusBarNotification(MESSAGES, MESSAGES, id.hashCode(), null, 10_001, 0, 0, n,
                Process.myUserHandle(), System.currentTimeMillis());
    }

    Notification.MessagingStyle.Message from(String name, String text, long time) {
        Person p = new Person.Builder().setName(name).setKey(name.toLowerCase(Locale.US)).build();
        return new Notification.MessagingStyle.Message(text, time, p);
    }

    private Notification.MessagingStyle.Message mine(String text, long time) {
        return new Notification.MessagingStyle.Message(text, time, (Person) null);
    }

    Intent lastBroadcast(String action) {
        List<Intent> intents = shadowOf(app).getBroadcastIntents();
        for (int i = intents.size() - 1; i >= 0; i--) {
            if (action.equals(intents.get(i).getAction())) return intents.get(i);
        }
        return null;
    }
}
