package com.textrelay.relay;

import static org.junit.Assert.assertEquals;
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
 * Reply requests acted on once whatever the clocks do, refused ones never sent later, and links that aren't this
 * phone's: an older build's link, and one copied from another phone. (Started as an independent reviewer's proof tests,
 * each failing on the code they found the problem in; kept as regressions.)
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ReplayAndCopyTest {
    static final String TOKEN = "tok0123456789abcdefghijklmnopqrstuvwxyzABCD";
    static final byte[] INBOX_KEY = new byte[32];
    static {
        for (int i = 0; i < 32; i++) INBOX_KEY[i] = (byte) (i * 7 + 3);
    }
    private static final long DAY = 86_400_000L;

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
        Relay.androidIdForTests = null;
        Relay.reset();
        server.close();
    }

    /**
     * Nonces are dropped by the relay's wall clock while a request's age is judged against the sender's clock. After the
     * relay's clock steps forward and back (or just back), a request whose nonce was dropped is acted on a second time.
     */
    @Test
    public void aRequestIsNeverActedOnTwiceEvenWhenTheRelayClockSteps() {
        long t = System.currentTimeMillis() + 60_000L;
        JSONObject sent = Json.obj("status", "sent");
        assertNull(relay.prefs.spendRequest("firstfirstfirstfirst00", t, t, sent));          // sent once, at t
        // The relay's clock jumps 2 days ahead (bad NITZ, manual change) while one more reply comes in from a phone
        // whose clock is right (made 1.5 days after the first): accepted, and the first nonce is dropped as > 3 days old.
        long made2 = t + 36 * 3_600_000L;
        assertNull(relay.prefs.spendRequest("secondsecondsecondsec0", made2, made2 + 2 * DAY, sent));
        // The clock is corrected. The server hands the first request back: made 1.5 days ago, so within the 2 days.
        String why = relay.prefs.spendRequest("firstfirstfirstfirst00", t, made2, sent);
        assertNotNull("the first request was accepted a second time", why);
    }

    /** The same, with the floor that guards nonces dropped to make room: it holds relay-clock times, compared to `made`. */
    @Test
    public void aRequestFromAPhoneWhoseClockIsAheadIsNeverActedOnTwiceAfterMakingRoom() {
        int limit = Prefs.nonceLimit;
        Prefs.nonceLimit = 1;
        try {
            long now = System.currentTimeMillis() + 60_000L;
            JSONObject sent = Json.obj("status", "sent");
            // Made on a phone whose clock is 12 hours ahead of the relay phone's.
            assertNull(relay.prefs.spendRequest("aheadaheadaheadahead00", now + 12 * 3_600_000L, now, sent));
            assertNull(relay.prefs.spendRequest("nextnextnextnextnext00", now + 1, now + 1, sent));   // the first is forgotten
            String why = relay.prefs.spendRequest("aheadaheadaheadahead00", now + 12 * 3_600_000L, now + 2, sent);
            assertNotNull("a forgotten request was accepted again: nonce_floor is relay time, `made` is sender time", why);
        } finally {
            Prefs.nonceLimit = limit;
        }
    }

    /**
     * A request the relay refused ("time is off") is reported failed and the person is told to send it again, but its
     * nonce isn't spent: when the server hands the same request back later, it goes out (on top of the person's resend).
     */
    @Test
    public void aRefusedRequestIsNeverSentLater() throws Exception {
        long now = System.currentTimeMillis();
        relay.onNotification(conversation("c2", "Bob", "REPLY_C2", "Call me?", now));
        relay.drain();
        String bob = server.calls("/api/relay/ingest").get(0).getJSONArray("items").getJSONObject(0).getString("conv");
        long made = System.currentTimeMillis() + DAY + 1_500;   // from a phone whose clock runs a day and a bit ahead
        server.pendingOutbox.add(new JSONObject().put("id", "m:1:ahead").put("conv", bob).put("n", "aheadaheadaheadahead11")
                .put("made", made).put("text", "running late").put("ts", now));
        relay.processOutbox(relay.hedy());
        List<JSONObject> reports = server.calls("/api/relay/outbox/result");
        assertEquals("failed", reports.get(0).getString("status"));
        assertEquals(NotSent.FUTURE, reports.get(0).getString("code"));
        assertNull(lastBroadcast("REPLY_C2"));
        Thread.sleep(2_000);
        // The server keeps the request and hands it back (same id, same sealed request).
        server.pendingOutbox.add(new JSONObject().put("id", "m:1:ahead").put("conv", bob).put("n", "aheadaheadaheadahead11")
                .put("made", made).put("text", "running late").put("ts", now));
        relay.processOutbox(relay.hedy());
        assertNull("a request reported failed was sent later", lastBroadcast("REPLY_C2"));
        reports = server.calls("/api/relay/outbox/result");
        assertEquals(NotSent.FUTURE, reports.get(1).getString("code"));   // what became of it the first time, again
    }

    /** A real request for a conversation this phone doesn't know is refused, and spent: it never goes out later. */
    @Test
    public void aRequestForAConversationThisPhoneDoesntKnowIsNeverSentLater() throws Exception {
        long now = System.currentTimeMillis();
        // a conversation id keyed with this inbox's key, for a conversation this phone has never had
        String wire = Crypto.hex(Crypto.hmac(Crypto.hkdf(INBOX_KEY, "textrelay v2 mac"), Crypto.utf8("conv:never-seen-here"))).substring(0, 32);
        server.pendingOutbox.add(new JSONObject().put("id", "m:1:unknown").put("wire", wire)
                .put("n", "unknownunknownunknown0").put("text", "hello?").put("ts", now));
        relay.processOutbox(relay.hedy());
        List<JSONObject> reports = server.calls("/api/relay/outbox/result");
        assertEquals("failed", reports.get(0).getString("status"));
        assertEquals(NotSent.UNKNOWN_CONV, reports.get(0).getString("code"));
        assertNotNull("spent", relay.prefs.spentRequest("unknownunknownunknown0"));
        // handed back later (under any id): what became of it is reported again, nothing is sent
        server.pendingOutbox.add(new JSONObject().put("id", "m:2:again").put("wire", wire)
                .put("n", "unknownunknownunknown0").put("text", "hello?").put("ts", now));
        relay.processOutbox(relay.hedy());
        reports = server.calls("/api/relay/outbox/result");
        assertEquals("m:2:again", reports.get(1).getString("id"));
        assertEquals(NotSent.UNKNOWN_CONV, reports.get(1).getString("code"));
    }

    /**
     * An install linked by the previous build has link2 but no ever_linked / link2_at (no migration). Removed from its
     * inbox, it asks the server for its wrap and goes back in.
     */
    @Test
    public void anInstallLinkedBeforeThisVersionIsNotPutBackByAWrap() throws Exception {
        Link before = relay.prefs.link();
        relay.prefs.raw().edit().remove("ever_linked").remove("link2_at").remove("link2_grace").remove("here2").commit();   // as the old build left it
        restart();   // this version starts on it
        Crypto.PhoneSecrets p = Crypto.phoneSecrets("0a1b2c3d4e5f6789", "relay");
        server.wraps.put(p.tag, before.wrap(p.wrapKey));   // a server that kept the wrap it should have deleted
        Relay.androidIdForTests = "0a1b2c3d4e5f6789";
        server.revoked = true;
        relay.unlinked();   // removed in Texts (to start a new inbox)
        server.revoked = false;
        assertNull("put back into the inbox it was removed from", relay.recover());
    }

    /** ...and while linked it has no "made before joining" guard, with an empty nonce store. */
    @Test
    public void anInstallLinkedBeforeThisVersionRefusesRequestsFromBeforeTheUpgrade() {
        relay.prefs.raw().edit().remove("ever_linked").remove("link2_at").remove("link2_grace").remove("here2").commit();
        restart();
        long now = System.currentTimeMillis();
        String why = relay.prefs.spendRequest("preupgradepreupgrade00", now - DAY, now, Json.obj("status", "sent"));
        assertNotNull("a request made a day before the upgrade was accepted (linkedAt() == 0)", why);
    }

    /** A link copied before its wrap was filed is dropped all the same: what counts is the phone it was stored on. */
    @Test
    public void aLinkCopiedBeforeItsWrapWasFiledIsDropped() {
        Relay.androidIdForTests = "5555666677778888";
        relay.prefs.setLink(new Link(server.baseUrl(), FakeHedy.TID, TOKEN, INBOX_KEY));   // linked on the old phone
        relay.prefs.setWrapTag("");   // copied before the old phone had filed its wrap
        Relay.androidIdForTests = "1111222233334444";
        relay.checkSamePhone();
        relay.fileWrap();
        assertNull("a copied link stays live on another phone", relay.prefs.link());
        assertTrue(server.calls("/v2/wrap").isEmpty());
    }

    /** The relay phone restarting with what's stored (a new version starting on an older one's data). */
    private void restart() {
        Relay.reset();
        relay = Relay.get(app);
    }

    // ------------------------------------------------------------------ fixtures (as RelayTest)

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
}
