package com.textrelay.relay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.app.Notification;
import android.app.Person;
import android.service.notification.StatusBarNotification;
import android.telephony.SmsManager;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowSmsManager;

import java.util.ArrayList;
import java.util.List;

/**
 * A conversation started from the inbox with a number (Paigr's New message): the request names the number and is
 * filed under the conversation this phone keys from it, the first text goes out as SMS from here, and everything that
 * follows (the copy in the message store, their answer in Messages) lands in that one conversation. A number this phone
 * already texts with keeps its conversation: the text goes through it and the started one folds into it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class NewConversationTest {
    private static final String NUMBER = "+18015550123";
    private final RelayV2Test t = new RelayV2Test();
    private FakeHedy server;
    private Application app;
    private Relay relay;
    private FakeTelephony phone;

    @Before
    public void setUp() throws java.io.IOException {
        t.setUp();
        server = t.server;
        app = t.app;
        relay = t.relay;
        phone = Robolectric.buildContentProvider(FakeTelephony.class).create("sms").get();
        ShadowContentResolver.registerProviderInternal("mms", phone);
        ShadowContentResolver.registerProviderInternal("mms-sms", phone);
        relay.prefs.raw().edit().putLong("install_ts", System.currentTimeMillis() - 86_400_000L).apply();
        Link.forgetForTests();   // what another test's phone worked out about its conversations isn't this phone's
    }

    @After
    public void tearDown() throws Exception {
        t.tearDown();
    }

    /** The keyed id of the conversation started with a number, worked out as Paigr does it (never through this phone's Link). */
    private static String wireFor(String number) {
        byte[] mac = Crypto.hkdf(RelayTest.INBOX_KEY, "textrelay v2 mac");
        return Crypto.hex(Crypto.hmac(mac, Crypto.utf8("conv:to:" + number))).substring(0, 32);
    }

    /** Files a request as Paigr seals one to start a conversation: for the keyed conversation alone, naming the number. */
    private void queued(String id, String text, String to, String wire) throws Exception {
        Link l = relay.prefs.link();
        JSONObject request = Json.obj("text", text, "n", Crypto.b64u(Crypto.random(16)), "ts", System.currentTimeMillis(), "to", to);
        server.pendingOutbox.add(new JSONObject().put("id", id).put("wire", wire).put("ts", 1).put("o", l.seal(request, Crypto.aadOut(wire))));
    }

    private JSONObject lastReport() {
        List<JSONObject> reports = server.calls("/api/relay/outbox/result");
        return reports.get(reports.size() - 1);
    }

    private ShadowSmsManager.TextMultipartParams lastSms() {
        return shadowOf(SmsManager.getDefault()).getLastSentMultipartTextMessageParams();
    }

    private List<JSONObject> ingested() throws Exception {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject call : server.calls("/api/relay/ingest")) {
            JSONArray arr = call.optJSONArray("items");
            for (int i = 0; arr != null && i < arr.length(); i++) out.add(arr.getJSONObject(i));
        }
        return out;
    }

    private static JSONObject find(List<JSONObject> items, String text) {
        for (JSONObject it : items) if (text.equals(it.optString("text"))) return it;
        throw new AssertionError("nothing with text " + text + " in " + items);
    }

    private StatusBarNotification fromNumber(String id, String title, String uri, String text, long time) {
        Person.Builder p = new Person.Builder().setName(title).setKey(id);
        if (uri != null) p.setUri(uri);
        return t.conversation(id, title, false, "REPLY_" + id.toUpperCase(java.util.Locale.US), new Notification.MessagingStyle.Message(text, time, p.build()));
    }

    @Test
    public void aTextToANewNumberStartsAConversationHereAndGoesOutAsSms() throws Exception {
        shadowOf(app).grantPermissions(Manifest.permission.SEND_SMS, Manifest.permission.READ_SMS);
        String wire = wireFor(NUMBER);
        String conv = Conversations.startedId(NUMBER);
        Object before = lastSms();
        queued("m:1:new", "Hi, this is Brad. Still selling the bike?", NUMBER, wire);
        relay.processOutbox(relay.hedy());

        JSONObject report = lastReport();
        assertEquals(report.optString("code", ""), "sent", report.getString("status"));
        assertEquals("sms", report.getString("via"));
        ShadowSmsManager.TextMultipartParams sent = lastSms();
        assertTrue("an SMS went out", sent != null && sent != before);
        assertEquals(NUMBER, sent.getDestinationAddress());
        assertEquals("Hi, this is Brad. Still selling the bike?", String.join("", sent.getParts()));
        // The conversation is this phone's now, named for the number, with the number SMS fallback sends to.
        assertTrue(relay.prefs.knows(conv));
        assertEquals("(801) 555-0123", relay.prefs.name(conv));
        assertEquals(NUMBER, relay.prefs.number(conv));
        assertEquals(Boolean.FALSE, relay.prefs.group(conv));
        assertEquals(java.util.Collections.singleton("8015550123"), relay.prefs.participants(conv));
        assertTrue(relay.prefs.merges().isEmpty());

        // The message store has the SMS this phone sent: its thread joins the started conversation, and the copy
        // goes up under it (the inbox folds it into the request it came from).
        long now = System.currentTimeMillis();
        phone.thread(7, NUMBER);
        phone.sms(7, NUMBER, now - 30_000, 2, true, "Hi, this is Brad. Still selling the bike?");
        assertEquals(1, relay.storeSync.scanNow(StoreSync.DAYS_2, false));
        relay.drain();
        assertEquals(conv, relay.prefs.threadConv(7));
        JSONObject copy = find(ingested(), "Hi, this is Brad. Still selling the bike?");
        assertEquals(conv, copy.getString("conv"));
        assertEquals("out", copy.getString("dir"));
        assertEquals("store", copy.getString("src"));

        // Their answer: in the store, and in Messages' notification, which is a new one. Both land in the started conversation.
        phone.sms(7, NUMBER, now, 1, false, "Yes! Want to come see it?");
        relay.onNotification(fromNumber("n1", "(801) 555-0123", "tel:" + NUMBER, "Yes! Want to come see it?", now));
        relay.drain();
        JSONObject answer = find(ingested(), "Yes! Want to come see it?");
        assertEquals(conv, answer.getString("conv"));
        assertEquals("in", answer.getString("dir"));
        assertEquals(1, relay.prefs.conversations().size());

        // From here on it's an ordinary conversation: the next reply goes through Messages' own reply action.
        server.pendingOutbox.add(new JSONObject().put("id", "m:2:again").put("conv", conv).put("text", "On my way").put("ts", 2));
        relay.processOutbox(relay.hedy());
        assertEquals("sent", lastReport().getString("status"));
        assertEquals("rcs", lastReport().getString("via"));
        assertNotNull(t.lastBroadcast("REPLY_N1"));
    }

    @Test
    public void theirAnswerJoinsTheStartedConversationWithoutMessageAccess() throws Exception {
        shadowOf(app).grantPermissions(Manifest.permission.SEND_SMS);
        String conv = Conversations.startedId(NUMBER);
        queued("m:1:new", "Hello from the inbox", NUMBER, wireFor(NUMBER));
        relay.processOutbox(relay.hedy());
        assertEquals("sent", lastReport().getString("status"));

        // Messages shows their answer with their number on them: the same conversation.
        long now = System.currentTimeMillis();
        relay.onNotification(fromNumber("n2", "Alex Rivera", "tel:" + NUMBER, "Who is this?", now));
        relay.drain();
        assertEquals(conv, find(ingested(), "Who is this?").getString("conv"));
        // The name Messages shows is the conversation's from now on (a contact's, where the inbox only had the number).
        assertEquals("Alex Rivera", relay.prefs.name(conv));
        assertEquals(1, relay.prefs.conversations().size());

        // Another started conversation, with only the number for a title (no contact): matched by the title.
        String other = "+18015550999";
        queued("m:3:new", "Hey", other, wireFor(other));
        relay.processOutbox(relay.hedy());
        relay.onNotification(fromNumber("n3", "(801) 555-0999", null, "Hey yourself", now + 1));
        relay.drain();
        assertEquals(Conversations.startedId(other), find(ingested(), "Hey yourself").getString("conv"));
        assertEquals(2, relay.prefs.conversations().size());
    }

    @Test
    public void aNumberThisPhoneAlreadyTextsWithKeepsItsConversation() throws Exception {
        shadowOf(app).grantPermissions(Manifest.permission.SEND_SMS);
        long now = System.currentTimeMillis();
        // Alice has been texting through Messages, with her number on the notification, and her reply action is open.
        relay.onNotification(fromNumber("c2", "Alice", "tel:" + NUMBER, "hey", now - 60_000));
        relay.drain();
        String alice = find(ingested(), "hey").getString("conv");
        assertEquals(NUMBER, relay.prefs.number(alice));

        // The inbox starts a "new" conversation with her number (typed in, not knowing it's Alice).
        String wire = wireFor(NUMBER);
        String started = Conversations.startedId(NUMBER);
        Object before = lastSms();
        queued("m:5:new", "Is this Alice?", NUMBER, wire);
        relay.processOutbox(relay.hedy());
        JSONObject report = lastReport();
        assertEquals(report.optString("code", ""), "sent", report.getString("status"));
        // Through Messages' reply, in her conversation, not as a second SMS conversation.
        assertEquals("rcs", report.getString("via"));
        assertTrue(before == lastSms());
        android.content.Intent reply = t.lastBroadcast("REPLY_C2");
        assertNotNull(reply);
        assertEquals("Is this Alice?", android.app.RemoteInput.getResultsFromIntent(reply).getCharSequence("reply_text").toString());
        // The started conversation is never made here: it folds into Alice's on the inbox.
        assertFalse(relay.prefs.knows(started));
        assertEquals(1, relay.prefs.merges().size());
        assertEquals(started, relay.prefs.merges().get(0).from);
        assertEquals(alice, relay.prefs.merges().get(0).into);
        relay.flushMerges(relay.hedy());
        JSONObject merge = server.calls("/api/relay/merge").get(0);
        assertEquals(started, merge.getString("from"));
        assertEquals(alice, merge.getString("into"));

        // A second text for the started conversation, before the inbox finished folding it: still hers.
        queued("m:6:new", "Alice??", NUMBER, wire);
        relay.processOutbox(relay.hedy());
        assertEquals("sent", lastReport().getString("status"));
        assertEquals("Alice??", android.app.RemoteInput.getResultsFromIntent(t.lastBroadcast("REPLY_C2")).getCharSequence("reply_text").toString());
        assertEquals(1, relay.prefs.merges().size());
    }

    @Test
    public void aRequestWhoseNumberIsNotTheConversationsIsRefused() throws Exception {
        shadowOf(app).grantPermissions(Manifest.permission.SEND_SMS);
        Object before = lastSms();
        // Filed under one number's conversation, naming another: sent to neither.
        queued("m:7:bad", "Hi", "+18015550999", wireFor(NUMBER));
        relay.processOutbox(relay.hedy());
        JSONObject report = lastReport();
        assertEquals("failed", report.getString("status"));
        assertEquals(NotSent.UNKNOWN_CONV, report.getString("code"));
        assertTrue(before == lastSms());
        assertTrue(relay.prefs.conversations().isEmpty());
        // A number that isn't one.
        queued("m:8:bad", "Hi", "not a number", wireFor("not a number"));
        relay.processOutbox(relay.hedy());
        assertEquals(NotSent.UNKNOWN_CONV, lastReport().getString("code"));
        assertTrue(relay.prefs.conversations().isEmpty());
    }

    @Test
    public void withoutSmsFallbackTheFirstTextWaitsAndSaysWhy() throws Exception {
        String conv = Conversations.startedId(NUMBER);
        Object before = lastSms();
        queued("m:9:new", "Hi there", NUMBER, wireFor(NUMBER));
        relay.processOutbox(relay.hedy());
        JSONObject report = lastReport();
        assertEquals("failed", report.getString("status"));
        assertEquals(NotSent.START_SMS, report.getString("code"));
        assertTrue(before == lastSms());
        // The conversation is still this phone's, so the text goes once SMS fallback is allowed.
        assertTrue(relay.prefs.knows(conv));
        assertEquals(NUMBER, relay.prefs.number(conv));
        shadowOf(app).grantPermissions(Manifest.permission.SEND_SMS);
        queued("m:10:new", "Hi there", NUMBER, wireFor(NUMBER));
        relay.processOutbox(relay.hedy());
        assertEquals("sent", lastReport().getString("status"));
        assertEquals(NUMBER, lastSms().getDestinationAddress());
        assertNull(relay.lastError);
    }
}
