package com.textrelay.relay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Person;
import android.app.RemoteInput;
import android.content.Intent;
import android.content.SharedPreferences;
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

import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * The relay side of the fixes and what they could have broken: the request floor whatever the clocks do, misfiled and
 * refused requests spent, folded conversations, codes only, links copied from another phone, the QR code. (Started as
 * an independent reviewer's checks, each failing on the code they found a problem in; kept as regressions.)
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class FixChecksTest {
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
        Prefs.nonceLimit = 20000;
        Relay.reset();
        server.close();
        RelayTest.settle();
    }

    // ------------------------------------------------------------------ fix 4: the floor is the sender's clock

    /**
     * One reply from a device whose date is set ahead (a year, say, to skip a game's wait) is refused as "future", as it
     * should be. But it is spent with its own `made`, and once forgotten (3 days later on the relay's clock) that `made`
     * becomes the floor: every later reply from every device is refused as "old" until that date.
     */
    @Test
    public void oneReplyFromADeviceWhoseDateIsAheadDoesNotBlockEveryLaterReply() throws Exception {
        long now = System.currentTimeMillis();
        String bob = bob(now);
        outbox("m:1:ahead", bob, "aheadaheadaheadahead22", now + 365 * DAY);
        assertEquals(NotSent.FUTURE, lastReport().getString("code"));
        age(4 * DAY);   // the relay phone's clock moves on four days (as if it were four days later)
        long t = System.currentTimeMillis();
        outbox("m:2:right", bob, "rightrightrightright01", t);
        assertEquals("sent", lastReport().getString("status"));   // judged against the floor from before this call
        outbox("m:3:right", bob, "rightrightrightright02", t + 1);
        JSONObject r = lastReport();
        assertEquals("a reply made now, on a device with the right time, was refused (" + r.optString("code") + "); nonce_floor="
                + relay.prefs.raw().getLong("nonce_floor", 0) + " now=" + t, "sent", r.getString("status"));
    }

    /** ...and linking the relay phone again (even into a new inbox) doesn't clear that floor. */
    @Test
    public void aFloorFromAFarFutureRequestOutlivesLinkingAgain() {
        long now = System.currentTimeMillis();
        JSONObject sent = Json.obj("status", "sent");
        assertEquals(NotSent.FUTURE, relay.prefs.spendRequest("futurefuturefuturefut0", now + 3650 * DAY, now, sent));
        assertNull(relay.prefs.spendRequest("rightrightrightright00", now + 4 * DAY, now + 4 * DAY, sent));   // forgets it
        relay.prefs.clearLink();
        relay.prefs.setLink(new Link(server.baseUrl(), "ffeeddccbbaa99887766554433221100", Crypto.newToken(), Crypto.random(32)));
        long later = now + 4 * DAY + 60_000;
        String why = relay.prefs.spendRequest("newinboxnewinboxnewin0", later, later, sent);
        assertNull("a fresh request in a new inbox was refused (" + why + "), floor=" + relay.prefs.raw().getLong("nonce_floor", 0), why);
    }

    /** A device ahead by less than a day (accepted) never pushes the floor past a later correct request. */
    @Test
    public void aDeviceAheadByHoursDoesNotBlockOthers() {
        long now = System.currentTimeMillis();
        JSONObject sent = Json.obj("status", "sent");
        assertNull(relay.prefs.spendRequest("hoursaheadhoursahead00", now + 20 * 3_600_000L, now, sent));
        long later = now + 3 * DAY + 60_000;   // forgotten by age
        assertNull(relay.prefs.spendRequest("correctcorrectcorrect0", later, later, sent));
        assertNull(relay.prefs.spendRequest("correctcorrectcorrect1", later + 1, later + 1, sent));
        assertTrue(relay.prefs.raw().getLong("nonce_floor", 0) < later - DAY);
    }

    // ------------------------------------------------------------------ fix 3: refused requests are spent

    /**
     * A real request filed by the server under another of this phone's conversations doesn't open there and is reported
     * failed ("unchecked"), but its nonce is never spent: filed again where it belongs, it goes out.
     */
    @Test
    public void aReplyReportedUncheckedIsNeverSentLater() throws Exception {
        long now = System.currentTimeMillis();
        String bob = bob(now);
        relay.onNotification(conversation("c3", "Eve", "REPLY_C3", "hi", now));
        relay.drain();
        String eve = null;
        for (JSONObject call : server.calls("/api/relay/ingest")) {
            for (int i = 0; i < call.getJSONArray("items").length(); i++) {
                JSONObject it = call.getJSONArray("items").getJSONObject(i);
                if ("hi".equals(it.getString("text"))) eve = it.getString("conv");
            }
        }
        assertNotNull(eve);
        Link l = relay.prefs.link();
        String o = l.seal(Json.obj("text", "for Bob only", "n", "misfiledmisfiledmisf01", "ts", now), Crypto.aadOut(l.convId(bob)));
        server.pendingOutbox.add(new JSONObject().put("id", "m:1:x").put("conv", eve).put("o", o).put("ts", now));
        relay.processOutbox(relay.hedy());
        assertEquals("failed", lastReport().getString("status"));
        assertEquals(NotSent.UNCHECKED, lastReport().getString("code"));
        assertNull(lastBroadcast("REPLY_C2"));
        // The server kept the request and now files it under the right conversation (same id, same sealed request).
        server.pendingOutbox.add(new JSONObject().put("id", "m:1:x").put("conv", bob).put("o", o).put("ts", now));
        relay.processOutbox(relay.hedy());
        assertNull("a reply the person was told had failed went out later (reported " + lastReport().optString("status") + ")",
                lastBroadcast("REPLY_C2"));
    }

    /**
     * The relay phone folded a store thread into Lee's conversation; the inbox hasn't merged them yet, so a reply typed
     * in the store thread is filed under it. After a restart (Link's id cache is gone) it is refused as a conversation
     * "this phone doesn't know", and spent, though the phone knows exactly where it went.
     */
    @Test
    public void aReplyInAConversationThisPhoneMergedAwayIsNotRefusedAsUnknown() throws Exception {
        long now = System.currentTimeMillis();
        relay.onNotification(conversation("c7", "Lee", "REPLY_C7", "you around?", now));
        relay.drain();
        String lee = server.calls("/api/relay/ingest").get(0).getJSONArray("items").getJSONObject(0).getString("conv");
        String store = "store-thread-of-lee";
        relay.prefs.setName(store, "Lee");
        relay.prefs.setFromStore(store);
        relay.prefs.markSeen(store, "abc");
        String wire = relay.prefs.link().convId(store);
        relay.prefs.fold(store, lee);
        relay.prefs.addMerge(store, lee);
        forgetIdCache();   // the relay app restarted
        server.pendingOutbox.add(new JSONObject().put("id", "m:1:s").put("wire", wire).put("n", "mergedawaymergedaway01")
                .put("text", "yes").put("ts", now));
        relay.processOutbox(relay.hedy());
        JSONObject r = lastReport();
        assertEquals("a reply sealed for a conversation this phone merged into Lee's was refused (" + r.optString("code") + ")",
                "sent", r.getString("status"));
    }

    // ------------------------------------------------------------------ fix 5: codes only

    @Test
    public void everyV2ReportCarriesACodeAndNoWords() throws Exception {
        long now = System.currentTimeMillis();
        String bob = bob(now);
        outbox("m:1:old", bob, "oldoldoldoldoldoldold0", now - 3 * DAY);
        outbox("m:2:future", bob, "futurefuturefuturefut1", now + 2 * DAY);
        outbox("m:3:again", bob, "oldoldoldoldoldoldold0", now);   // the same nonce: its earlier outcome again
        server.pendingOutbox.add(new JSONObject().put("id", "m:4:unknown").put("wire", "0123456789abcdef0123456789abcdef")
                .put("n", "unknownunknownunknown1").put("text", "x").put("ts", now));
        relay.processOutbox(relay.hedy());
        List<JSONObject> reports = server.calls("/api/relay/outbox/result");
        assertEquals(4, reports.size());
        for (JSONObject r : reports) {
            // (the fake records an outbox result as the body it got, which is also its own _raw)
            assertFalse(r.optString("id") + " carries words", r.has("error"));
            assertTrue(r.optString("id") + " has no code", r.has("code"));
        }
    }

    // ------------------------------------------------------------------ fix 6: a link copied from another phone

    @Test
    public void aLinkIsKeptWhenTheAndroidIdCantBeReadLater() {
        Relay.androidIdForTests = "5555666677778888";
        relay.prefs.setLink(new Link(server.baseUrl(), FakeHedy.TID, TOKEN, INBOX_KEY));
        Relay.androidIdForTests = null;
        android.provider.Settings.Secure.putString(app.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID, "9774d56d682e549c");
        assertNotNull("dropped because the ID couldn't be read", relay.prefs.link());
        android.provider.Settings.Secure.putString(app.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID, null);
        assertNotNull(relay.prefs.link());
    }

    @Test
    public void theMigrationRunsOnceEvenWithManyPrefsAtOnce() throws Exception {
        Relay.androidIdForTests = "a1b2c3d4e5f60789";
        relay.prefs.raw().edit().remove("ever_linked").remove("link2_at").remove("link2_grace").remove("here2").commit();
        Thread[] ts = new Thread[8];
        Prefs[] ps = new Prefs[8];
        for (int i = 0; i < ts.length; i++) {
            int k = i;
            ts[i] = new Thread(() -> ps[k] = new Prefs(app));
        }
        for (Thread t : ts) t.start();
        for (Thread t : ts) t.join();
        long at = ps[0].linkedAt();
        assertTrue(at > 0);
        for (Prefs p : ps) {
            assertNotNull(p.link());
            assertTrue(p.everLinked());
        }
        Thread.sleep(5);
        assertEquals(relay.prefs.raw().getLong("link2_at", 0), new Prefs(app).linkedAt());   // not again
        assertNotNull(new Prefs(app).link());
        Relay.androidIdForTests = "ffff000011112222";   // the same data, read on another phone
        assertNull(new Prefs(app).link());
    }

    /** A link copied while its owner was mid-invite: the Link a device panel must not crash when the link is gone. */
    @Test
    public void linkADeviceDoesNotCrashWhenThePhoneIsUnlinkedWhileTheCodeIsMade() throws Exception {
        relay.prefs.setVerified(true);
        MainActivity activity = org.robolectric.Robolectric.buildActivity(MainActivity.class).setup().get();
        RelayTest.click(activity, "Link a device");   // files an invite with the link it has now, on a thread
        relay.unlinked();                              // meanwhile the poller hears this phone was removed
        RelayTest.waitFor(() -> !server.calls("/v2/link/invite").isEmpty());
        Thread.sleep(300);
        try {
            RelayTest.tick();
        } catch (RuntimeException e) {
            Throwable t = e;
            while (t.getCause() != null && !(t instanceof NullPointerException)) t = t.getCause();
            fail("the screen crashed: " + t);
        }
    }

    // ------------------------------------------------------------------ fix 7: the QR code

    @Test
    public void theQrCodeForTheDefaultServerIsTheNewFormatAndDecodes() throws Exception {
        String text = Crypto.linkQr(app.getString(R.string.default_server), "K7Q4M2XD9PRT");
        assertEquals("TEXTRELAY2:relay--bradwhite.apps.hedyassist.com/K7Q4M2XD9PRT", text);
        assertEquals("TEXTRELAY2:relay.example:8443/K7Q4M2XD9PRT", Crypto.linkQr("HTTPS://Relay.Example:8443//", "k7q4m2xd9prt"));
        android.graphics.Bitmap bmp = Pairing.qr(text, 720);
        int[] px = new int[bmp.getWidth() * bmp.getHeight()];
        bmp.getPixels(px, 0, bmp.getWidth(), 0, 0, bmp.getWidth(), bmp.getHeight());
        com.google.zxing.BinaryBitmap image = new com.google.zxing.BinaryBitmap(new com.google.zxing.common.HybridBinarizer(
                new com.google.zxing.RGBLuminanceSource(bmp.getWidth(), bmp.getHeight(), px)));
        assertEquals(text, new com.google.zxing.qrcode.QRCodeReader().decode(image).getText());
    }

    // ------------------------------------------------------------------ helpers

    private String bob(long now) throws Exception {
        relay.onNotification(conversation("c2", "Bob", "REPLY_C2", "Call me?", now));
        relay.drain();
        for (JSONObject call : server.calls("/api/relay/ingest")) {
            for (int i = 0; i < call.getJSONArray("items").length(); i++) {
                JSONObject it = call.getJSONArray("items").getJSONObject(i);
                if ("Call me?".equals(it.getString("text"))) return it.getString("conv");
            }
        }
        throw new AssertionError("Bob's conversation never went up");
    }

    private void outbox(String id, String conv, String nonce, long made) throws Exception {
        server.pendingOutbox.add(new JSONObject().put("id", id).put("conv", conv).put("n", nonce).put("made", made)
                .put("text", "reply " + id).put("ts", System.currentTimeMillis()));
        relay.processOutbox(relay.hedy());
    }

    private JSONObject lastReport() {
        List<JSONObject> r = server.calls("/api/relay/outbox/result");
        return r.get(r.size() - 1);
    }

    /** Every remembered request `ms` older, as the relay phone's clock would see them `ms` later. */
    private void age(long ms) throws Exception {
        SharedPreferences sp = relay.prefs.raw();
        JSONObject all = new JSONObject(sp.getString("nonces2", "{}"));
        for (Iterator<String> k = all.keys(); k.hasNext(); ) {
            JSONObject e = all.getJSONObject(k.next());
            e.put("at", e.getLong("at") - ms);
        }
        sp.edit().putString("nonces2", all.toString()).commit();
    }

    @SuppressWarnings("unchecked")
    private static void forgetIdCache() throws Exception {
        java.lang.reflect.Field f = Link.class.getDeclaredField("LOCAL");
        f.setAccessible(true);
        ((java.util.Map<String, String>) f.get(null)).clear();
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
}
