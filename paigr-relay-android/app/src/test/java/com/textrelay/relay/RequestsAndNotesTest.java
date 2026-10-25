package com.textrelay.relay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Person;
import android.app.RemoteInput;
import android.content.Intent;
import android.os.Process;
import android.service.notification.StatusBarNotification;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.List;
import java.util.Locale;

/**
 * A reply request that opened nowhere stays failed, spent requests survive re-linking into the same inbox after an
 * upgrade, and the relay phone's notes are in its own words. (Started as an independent reviewer's checks, kept as
 * regressions.)
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class RequestsAndNotesTest {
    static final String TOKEN = "tok0123456789abcdefghijklmnopqrstuvwxyzABCD";
    static final byte[] INBOX_KEY = new byte[32];
    static {
        for (int i = 0; i < 32; i++) INBOX_KEY[i] = (byte) (i * 7 + 3);
    }

    FakeHedy server;
    Application app;
    Relay relay;

    @Before
    public void setUp() throws Exception {
        server = new FakeHedy();
        Relay.reset();
        app = RuntimeEnvironment.getApplication();
        relay = Relay.get(app);
        relay.prefs.raw().edit().clear().commit();
        relay.prefs.setServer(server.baseUrl(), "");
        relay.prefs.setLink(new Link(server.baseUrl(), FakeHedy.TID, TOKEN, INBOX_KEY));
        relay.prefs.setWrapTag("done");
        server.link = relay.prefs.link();
    }

    @After
    public void tearDown() throws Exception {
        Prefs.unopenedLimit = 2000;
        Relay.reset();
        server.close();
    }

    /**
     * A real request sealed for a conversation this phone doesn't know yet, filed under one it does, opens nowhere and
     * is reported failed. Once that person texts and the server files it where it belongs, it opens: it's refused then,
     * never sent after the person was told it wasn't.
     */
    @Test
    public void aRequestThatOpenedNowhereIsNeverSentLater() throws Exception {
        long now = System.currentTimeMillis();
        relay.onNotification(conversation("c2", "Bob", "REPLY_C2", "Call me?", now));
        relay.drain();
        String bob = server.calls("/api/relay/ingest").get(0).getJSONArray("items").getJSONObject(0).getString("conv");
        StatusBarNotification carolText = conversation("c3", "Carol", "REPLY_C3", "are you coming?", now + 1000);
        String carol = Parser.conversationId(carolText);
        // An inbox device's reply in Carol's conversation, sealed once (the server keeps it as it came, and can't seal
        // another), filed by the server under Bob's.
        Link link = relay.prefs.link();
        String o = link.seal(new JSONObject().put("text", "yes, at 6").put("n", "carolcarolcarolcarol00").put("ts", now),
                Crypto.aadOut(link.convId(carol)));
        server.pendingOutbox.add(new JSONObject().put("id", "m:1:mis").put("conv", bob).put("o", o).put("ts", now));
        relay.processOutbox(relay.hedy());
        List<JSONObject> reports = server.calls("/api/relay/outbox/result");
        assertEquals("failed", reports.get(0).getString("status"));
        assertEquals(NotSent.UNCHECKED, reports.get(0).getString("code"));
        relay.onNotification(carolText);
        relay.drain();
        server.pendingOutbox.add(new JSONObject().put("id", "m:2:right").put("conv", carol).put("o", o).put("ts", now));
        relay.processOutbox(relay.hedy());
        assertNull("a reply reported failed went out later", lastBroadcast("REPLY_C3"));
        reports = server.calls("/api/relay/outbox/result");
        assertEquals("failed", reports.get(reports.size() - 1).getString("status"));
        assertEquals(NotSent.UNCHECKED, reports.get(reports.size() - 1).getString("code"));
        // and a reply Carol's conversation really gets goes out as usual
        server.pendingOutbox.add(new JSONObject().put("id", "m:3:new").put("conv", carol)
                .put("n", "carolcarolcarolcarol01").put("made", now).put("text", "see you").put("ts", now));
        relay.processOutbox(relay.hedy());
        assertTrue(lastBroadcast("REPLY_C3") != null);
    }

    /** The same, with the seal written another way when it's filed under Carol's: still the same request, still refused. */
    @Test
    public void aRequestThatOpenedNowhereStaysRefusedHoweverItsSealIsWritten() throws Exception {
        long now = System.currentTimeMillis();
        relay.onNotification(conversation("c2", "Bob", "REPLY_C2", "Call me?", now));
        relay.drain();
        String bob = server.calls("/api/relay/ingest").get(0).getJSONArray("items").getJSONObject(0).getString("conv");
        StatusBarNotification carolText = conversation("c3", "Carol", "REPLY_C3", "are you coming?", now + 1000);
        String carol = Parser.conversationId(carolText);
        Link link = relay.prefs.link();
        String o = null;
        for (int pad = 0; pad < 3 && o == null; pad++) {
            String s = link.seal(new JSONObject().put("text", "yes, at 6" + "  ".substring(0, pad)).put("n", "carolcarolcarolcarol00").put("ts", now),
                    Crypto.aadOut(link.convId(carol)));
            if ((s.length() - 3) % 4 != 0) o = s;
        }
        String padded = o + ((o.length() - 3) % 4 == 2 ? "==" : "=");
        server.pendingOutbox.add(new JSONObject().put("id", "m:1:mis").put("conv", bob).put("o", o).put("ts", now));
        relay.processOutbox(relay.hedy());
        relay.onNotification(carolText);
        relay.drain();
        // padded: never opens (one spelling only); and as written first, it's the request remembered
        server.pendingOutbox.add(new JSONObject().put("id", "m:2:padded").put("conv", carol).put("o", padded).put("ts", now));
        server.pendingOutbox.add(new JSONObject().put("id", "m:3:right").put("conv", carol).put("o", o).put("ts", now));
        relay.processOutbox(relay.hedy());
        assertNull("a reply reported failed went out later", lastBroadcast("REPLY_C3"));
        assertEquals(Hedy2.tagOf(o), Hedy2.tagOf(padded));
    }

    /**
     * Even past the memory of requests that opened nowhere (the server can fill it with ones it made up), a request made
     * well before this phone knew its conversation is refused when it opens there: it was reported failed back then.
     */
    @Test
    public void aRequestMadeBeforeThisPhoneKnewItsConversationIsRefused() throws Exception {
        Prefs.unopenedLimit = 3;
        long now = System.currentTimeMillis();
        relay.prefs.raw().edit().putLong("link2_at", now - 3_600_000L).commit();   // linked an hour ago
        relay.onNotification(conversation("c2", "Bob", "REPLY_C2", "Call me?", now));
        relay.drain();
        String bob = server.calls("/api/relay/ingest").get(0).getJSONArray("items").getJSONObject(0).getString("conv");
        StatusBarNotification carolText = conversation("c3", "Carol", "REPLY_C3", "are you coming?", now + 1000);
        String carol = Parser.conversationId(carolText);
        Link link = relay.prefs.link();
        long made = now - 10 * 60_000L;   // typed ten minutes before Carol's text came in
        String o = link.seal(new JSONObject().put("text", "yes, at 6").put("n", "carolcarolcarolcarol00").put("ts", made), Crypto.aadOut(link.convId(carol)));
        server.pendingOutbox.add(new JSONObject().put("id", "m:1:mis").put("conv", bob).put("o", o).put("ts", made));
        relay.processOutbox(relay.hedy());
        for (int i = 0; i < Prefs.unopenedLimit; i++) {
            server.pendingOutbox.add(new JSONObject().put("id", "m:1:junk" + i).put("conv", bob).put("o", "e1." + Crypto.b64u(Crypto.random(60))).put("ts", now));
            relay.processOutbox(relay.hedy());
        }
        assertFalse("crowded out of memory", relay.prefs.wasUnopened(Hedy2.tagOf(o)));
        relay.onNotification(carolText);
        relay.drain();
        assertTrue(relay.prefs.knownSince(carol) > made);
        server.pendingOutbox.add(new JSONObject().put("id", "m:2:right").put("conv", carol).put("o", o).put("ts", made));
        relay.processOutbox(relay.hedy());
        assertNull("a reply reported failed went out later", lastBroadcast("REPLY_C3"));
        List<JSONObject> reports = server.calls("/api/relay/outbox/result");
        assertEquals(NotSent.UNKNOWN_CONV, reports.get(reports.size() - 1).getString("code"));
        // a conversation known from before this was kept (no time noted) is never refused for it
        assertEquals(0, relay.prefs.knownSince("never-seen"));
    }

    /**
     * In normal use the phones' clocks needn't agree: a reply typed 30 seconds after a new contact's first text, on a
     * phone whose clock runs 3 minutes behind the relay phone's, goes out.
     */
    @Test
    public void aQuickReplyToANewContactFromAPhoneBehindInTimeGoesOut() throws Exception {
        long now = System.currentTimeMillis();
        relay.prefs.raw().edit().putLong("link2_at", now - 3_600_000L).commit();   // linked an hour ago
        StatusBarNotification first = conversation("c9", "Dana", "REPLY_C9", "hi, it's Dana from the gym", now);
        relay.onNotification(first);
        relay.drain();
        String dana = Parser.conversationId(first);
        assertTrue(relay.prefs.knownSince(dana) >= now - 1000);
        long made = now + 30_000L - 3 * 60_000L;
        server.pendingOutbox.add(new JSONObject().put("id", "m:1:quick").put("conv", dana).put("n", "danadanadanadanadana00")
                .put("made", made).put("text", "hey! yes, see you there").put("ts", now + 30_000L));
        relay.processOutbox(relay.hedy());
        assertTrue("a genuine fresh reply was refused", lastBroadcast("REPLY_C9") != null);
    }

    /**
     * What Texts' memory of announced texts rests on: when the server asks the relay phone to send what Messages shows
     * again, the copy is sealed afresh but names the same message (its time, fingerprint and words).
     */
    @Test
    public void aCopySentAgainNamesTheSameMessage() throws Exception {
        long now = System.currentTimeMillis();
        StatusBarNotification sbn = conversation("c1", "Alice", "REPLY_C1", "the gate code is 4471", now);
        relay.onNotification(sbn);
        relay.drain();
        relay.active = () -> new StatusBarNotification[] { sbn };
        server.wake = now + 5;
        relay.processOutbox(relay.hedy());
        for (int i = 0; i < 100 && server.calls("/api/relay/ingest").size() < 2; i++) Thread.sleep(50);
        relay.drain();
        List<JSONObject> ups = server.calls("/api/relay/ingest");
        assertTrue(ups.size() >= 2);
        JSONObject a = ups.get(0).getJSONObject("_raw").getJSONArray("items").getJSONObject(0);
        JSONObject b = ups.get(1).getJSONObject("_raw").getJSONArray("items").getJSONObject(0);
        assertFalse(a.getString("e").equals(b.getString("e")));
        Link link = relay.prefs.link();
        JSONObject pa = link.open(a.getString("e"), Crypto.aadMessage(a.getString("conv"), "in"));
        JSONObject pb = link.open(b.getString("e"), Crypto.aadMessage(b.getString("conv"), "in"));
        assertEquals(pa.getLong("ts"), pb.getLong("ts"));
        assertEquals(pa.getString("fp"), pb.getString("fp"));
        assertEquals(pa.getString("text"), pb.getString("text"));
    }

    /** Remembered requests that opened nowhere stay bounded: past the limit the earliest seen go first. */
    @Test
    public void requestsThatOpenedNowhereAreBounded() throws Exception {
        Prefs.unopenedLimit = 3;
        long now = System.currentTimeMillis();
        for (int i = 0; i < 5; i++) relay.prefs.rememberUnopened("seal" + i, now + i);
        assertFalse(relay.prefs.wasUnopened("seal0"));
        assertFalse(relay.prefs.wasUnopened("seal1"));
        assertTrue(relay.prefs.wasUnopened("seal2"));
        assertTrue(relay.prefs.wasUnopened("seal4"));
        // forgotten after four days
        relay.prefs.rememberUnopened("later", now + 5 * 86_400_000L);
        assertFalse(relay.prefs.wasUnopened("seal4"));
        assertTrue(relay.prefs.wasUnopened("later"));
    }

    /**
     * Data from a build that didn't note whose its spent requests were: re-linked into the same inbox by a code (after
     * the server says it's unlinked), the phone still knows what it sent, and sends nothing twice.
     */
    @Test
    public void aRelinkIntoTheSameInboxAfterAnUpgradeKeepsSpentRequests() throws Exception {
        long now = System.currentTimeMillis();
        relay.onNotification(conversation("c2", "Bob", "REPLY_C2", "Call me?", now));
        relay.drain();
        String bob = server.calls("/api/relay/ingest").get(0).getJSONArray("items").getJSONObject(0).getString("conv");
        server.pendingOutbox.add(new JSONObject().put("id", "m:1:r").put("conv", bob).put("n", "relinkrelinkrelinkre00")
                .put("made", now).put("text", "on my way").put("ts", now));
        relay.processOutbox(relay.hedy());
        assertEquals(1, broadcasts("REPLY_C2"));
        relay.prefs.raw().edit().remove("nonces_tid").commit();   // as an older build left its data
        relay.prefs.clearLink();                                  // the server answers "unlinked"
        relay.prefs.setLink(new Link(server.baseUrl(), FakeHedy.TID, TOKEN, INBOX_KEY));   // the same inbox, by a code
        server.link = relay.prefs.link();
        server.pendingOutbox.add(new JSONObject().put("id", "m:2:r").put("conv", bob).put("n", "relinkrelinkrelinkre00")
                .put("made", now).put("text", "on my way").put("ts", now));
        relay.processOutbox(relay.hedy());
        assertEquals("the same request was sent twice", 1, broadcasts("REPLY_C2"));
    }

    /** Opened on data like that, the app notes the stored link's inbox as theirs. */
    @Test
    public void anUpgradeNotesWhoseSpentRequestsTheyAre() {
        relay.prefs.raw().edit().remove("nonces_tid").commit();
        new Prefs(app);
        assertEquals(FakeHedy.TID, relay.prefs.raw().getString("nonces_tid", null));
    }

    /**
     * The server refuses with words of its own: the relay phone's note (sealed for your other devices) and its own
     * screen say what happened in the phone's words, never the server's.
     */
    @Test
    public void theRelayPhonesNotesCarryNoWordsTheServerWrote() throws Exception {
        server.rejectIngestWith = 400;   // the fake answers {"error": "refused for the test"}
        relay.onNotification(conversation("c2", "Bob", "REPLY_C2", "Call me?", System.currentTimeMillis()));
        relay.drain();
        assertTrue(relay.lastError, relay.lastError != null && relay.lastError.contains("400"));
        assertFalse(relay.lastError, relay.lastError.contains("refused for the test"));
        relay.heartbeat(relay.hedy());
        List<JSONObject> beats = server.calls("/api/relay/heartbeat");
        String note = beats.get(beats.size() - 1).optString("note");   // what the fake opened from `sn`
        assertFalse("the relay phone's sealed note says: " + note, note.contains("refused for the test"));
    }

    private StatusBarNotification conversation(String id, String title, String replyAction, String text, long time) {
        Person me = new Person.Builder().setName("You").setKey("me").build();
        Notification.MessagingStyle style = new Notification.MessagingStyle(me);
        Person p = new Person.Builder().setName(title).setKey(title.toLowerCase(Locale.US)).build();
        style.addMessage(new Notification.MessagingStyle.Message(text, time, p));
        Intent replyIntent = new Intent(replyAction).setPackage(app.getPackageName());
        PendingIntent pi = PendingIntent.getBroadcast(app, replyAction.hashCode(), replyIntent,
                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        RemoteInput input = new RemoteInput.Builder("reply_text").setLabel("Reply").build();
        Notification.Action reply = new Notification.Action.Builder(null, "Reply", pi)
                .addRemoteInput(input).setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY).build();
        Notification n = new Notification.Builder(app, "messages")
                .setSmallIcon(android.R.drawable.sym_action_chat).setContentTitle(title)
                .setCategory(Notification.CATEGORY_MESSAGE).setShortcutId("conversation_" + id)
                .setStyle(style).addAction(reply).build();
        return new StatusBarNotification("com.google.android.apps.messaging", "com.google.android.apps.messaging", id.hashCode(), null,
                10_001, 0, 0, n, Process.myUserHandle(), System.currentTimeMillis());
    }

    private Intent lastBroadcast(String action) {
        List<Intent> intents = shadowOf(app).getBroadcastIntents();
        for (int i = intents.size() - 1; i >= 0; i--) if (action.equals(intents.get(i).getAction())) return intents.get(i);
        return null;
    }

    private int broadcasts(String action) {
        int n = 0;
        for (Intent i : shadowOf(app).getBroadcastIntents()) if (action.equals(i.getAction())) n++;
        return n;
    }
}
