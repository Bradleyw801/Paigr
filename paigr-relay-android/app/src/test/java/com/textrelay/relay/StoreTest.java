package com.textrelay.relay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Person;
import android.app.RemoteInput;
import android.content.Intent;
import android.os.Process;
import android.service.notification.StatusBarNotification;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The message store side: texts sent from the relay phone, reads, matching threads to conversations, full history. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class StoreTest {
    private static final String MESSAGES = "com.google.android.apps.messaging";
    private static final String KEY = "hak_abcdef0123456789.abcDEFghiJKLmnoPQRstuVWXyz0123456789";
    private static final long MIN = 60_000;

    FakeHedy server;
    FakeTelephony phone;
    Application app;
    Relay relay;

    /** False: the first version's inbox. StoreV2Test runs every test against a private inbox. */
    protected boolean v2() {
        return false;
    }

    void connect() {
        if (!v2()) {
            relay.prefs.setServer(server.baseUrl(), KEY);
            return;
        }
        relay.prefs.setServer(server.baseUrl(), "");
        relay.prefs.setLink(new Link(server.baseUrl(), FakeHedy.TID, RelayTest.TOKEN, RelayTest.INBOX_KEY));
        relay.prefs.setWrapTag("done");
        server.link = relay.prefs.link();
    }

    @Before
    public void setUp() throws IOException {
        server = new FakeHedy();
        app = RuntimeEnvironment.getApplication();
        phone = Robolectric.buildContentProvider(FakeTelephony.class).create("sms").get();
        ShadowContentResolver.registerProviderInternal("mms", phone);
        ShadowContentResolver.registerProviderInternal("mms-sms", phone);
        shadowOf(app).grantPermissions(Manifest.permission.READ_SMS);
        Relay.reset();
        relay = Relay.get(app);
        connect();
        // Installed yesterday: the notifications these tests show are newer than that.
        relay.prefs.raw().edit().putLong("install_ts", System.currentTimeMillis() - 86_400_000L).apply();
    }

    @After
    public void tearDown() throws Exception {
        Relay.reset();
        server.close();
        RelayTest.settle();
    }

    // ------------------------------------------------------------------ live

    @Test
    public void aReplyTypedOnTheRelayPhoneReachesTheInboxInItsConversation() throws Exception {
        long now = System.currentTimeMillis();
        // Andy's texts have been coming through Messages' notifications, with his number on them.
        relay.onNotification(conversation("andy", "Andy Graham", false, person("Andy Graham", "tel:+18015550123"), "Lunch tomorrow?", now - 5 * MIN));
        relay.drain();
        String conv = items("/api/relay/ingest").get(0).getString("conv");

        // The store has his text and the reply typed in Messages on this phone, which no notification ever shows.
        phone.thread(7, "+18015550123");
        phone.sms(7, "+18015550123", now - 5 * MIN + 400, 1, true, "Lunch tomorrow?");
        phone.sms(7, "+18015550123", now - 2 * MIN, 2, true, "Sure, noon works");
        assertEquals(2, relay.storeSync.scanNow(StoreSync.DAYS_2, false));
        relay.drain();

        assertEquals(conv, relay.prefs.threadConv(7));
        JSONObject reply = find(items("/api/relay/ingest"), "Sure, noon works");
        assertEquals(conv, reply.getString("conv"));
        assertEquals("out", reply.getString("dir"));
        assertEquals("store", reply.getString("src"));
        assertEquals("", reply.getString("sender"));
        assertEquals("Andy Graham", reply.getString("name"));
        // His text from the store goes up too; the inbox folds it into the copy the notification brought.
        JSONObject copy = find(items("/api/relay/ingest"), "Lunch tomorrow?", "store");
        assertEquals("in", copy.getString("dir"));
        assertTrue(copy.getBoolean("read"));

        // Checked again: nothing new goes up.
        int before = server.calls("/api/relay/ingest").size();
        assertEquals(0, relay.storeSync.scanNow(0, false));
        relay.drain();
        assertEquals(before, server.calls("/api/relay/ingest").size());
    }

    @Test
    public void aNewThreadWaitsForItsNotificationThenJoinsIt() throws Exception {
        long now = System.currentTimeMillis();
        phone.thread(9, "+18015550777");
        phone.sms(9, "+18015550777", now - 10_000, 1, false, "New number, it's Jo from climbing");
        // The store saw it first: it waits a moment for the notification to say which conversation this is.
        assertEquals(0, relay.storeSync.scanNow(0, false));
        assertNull(relay.prefs.threadConv(9));

        relay.onNotification(conversation("jo", "(801) 555-0777", false, person("(801) 555-0777", null),
                "New number, it's Jo from climbing", now - 9_000));
        relay.drain();
        String conv = items("/api/relay/ingest").get(0).getString("conv");
        assertEquals(conv, relay.prefs.threadConv(9));

        assertEquals(1, relay.storeSync.scanNow(0, false));
        relay.drain();
        assertEquals(conv, find(items("/api/relay/ingest"), "New number, it's Jo from climbing", "store").getString("conv"));
    }

    @Test
    public void aConversationReadOnThisPhoneIsReadInTheInbox() throws Exception {
        long now = System.currentTimeMillis();
        relay.onNotification(conversation("sam", "Sam", false, person("Sam", "tel:+18015550444"), "You up?", now - 3 * MIN));
        relay.drain();
        String conv = items("/api/relay/ingest").get(0).getString("conv");
        phone.thread(3, "+18015550444");
        phone.sms(3, "+18015550444", now - 3 * MIN, 1, false, "You up?");
        relay.storeSync.scanNow(StoreSync.DAYS_2, false);
        relay.drain();

        phone.markRead(3, true);
        relay.storeSync.scanNow(0, false);
        relay.drain();
        JSONObject last = server.calls("/api/relay/ingest").get(server.calls("/api/relay/ingest").size() - 1);
        assertEquals(now - 3 * MIN, last.getJSONObject("reads").getLong(conv));
    }

    @Test
    public void groupMmsWithAPictureGoesUpOncePerAttachmentAndUploadsOnlyWhatTheInboxNeeds() throws Exception {
        long now = System.currentTimeMillis();
        byte[] photo = new byte[4000];
        new java.util.Random(4).nextBytes(photo);
        List<String> read = new ArrayList<>();
        relay.mediaReader = (uri, mime) -> {
            read.add(uri.toString());
            return new Media.Prepared(new Media.Blob(photo, "image/jpeg", 40, 30), null);
        };
        phone.thread(11, "+18015550101", "+18015550102");
        long id = phone.mms(11, "+18015550101", now - 4 * MIN, 1, false, "Summit!", "image/jpeg", "image/png");
        relay.storeSync.scanNow(StoreSync.DAYS_2, false);
        relay.drain();

        List<JSONObject> sent = items("/api/relay/ingest");
        JSONObject first = find(sent, "Summit!");
        assertEquals("image/jpeg", first.getJSONObject("media").getString("mime"));
        assertTrue(first.getBoolean("wantMedia"));
        assertTrue(first.getBoolean("group"));
        assertEquals("(801) 555-0101", first.getString("sender"));
        // Each picture went up once, after the inbox asked for it, then was attached to its message.
        assertEquals(2, read.size());
        assertTrue(read.contains("content://mms/part/" + partId(id, "image/jpeg")));
        assertEquals(2, server.calls("/api/relay/media").size());
        int attached = 0;
        for (JSONObject it : sent) if (it.has("media") && it.getJSONObject("media").has("path")) attached++;
        assertEquals(2, attached);

        // Sent again by a resync: the inbox already has both pictures, so nothing is uploaded again.
        relay.storeSync.scanNow(StoreSync.DAYS_7, true);
        relay.drain();
        assertEquals(2, server.calls("/api/relay/media").size());
    }

    // ------------------------------------------------------------------ which conversation

    @Test
    public void anOldThreadJoinsTheConversationItsTextsAreAlreadyIn() throws Exception {
        long now = System.currentTimeMillis();
        phone.thread(21, "+18015550555");
        phone.sms(21, "+18015550555", now - 40 * MIN, 1, true, "Did you see the game last night?");
        phone.sms(21, "+18015550555", now - 39 * MIN, 1, true, "Unbelievable ending");
        String conv = "c0ffee00c0ffee00c0ffee00c0ffee00";
        if (v2()) {
            // A private inbox isn't asked (its answer would say where replies go): this phone saw those texts itself, in
            // a notification conversation that never showed a number.
            relay.onNotification(conversation("sam", "Sam", false, person("Sam", null),
                    "Did you see the game last night?", now - 40 * MIN + 2000, "Unbelievable ending", now - 39 * MIN + 1500));
            relay.drain();
            conv = items("/api/relay/ingest").get(0).getString("conv");
        } else {
            server.located.put("Did you see the game last night?", conv);
            server.located.put("Unbelievable ending", conv);
        }
        relay.storeSync.scanNow(StoreSync.DAYS_2, false);
        relay.drain();
        assertEquals(conv, relay.prefs.threadConv(21));
        assertEquals(v2() ? 0 : 1, server.calls("/api/relay/locate").size());
        for (JSONObject it : items("/api/relay/ingest")) assertEquals(conv, it.getString("conv"));
        if (v2()) assertEquals("+18015550555", relay.prefs.number(conv));   // replies to Sam can fall back to SMS now
    }

    @Test
    public void aPrivateInboxCantPointAThreadAtAnotherConversation() throws Exception {
        if (!v2()) return;
        long now = System.currentTimeMillis();
        // Mom's conversation came from notifications only: no number known for it.
        relay.onNotification(conversation("mom", "Mom", false, person("Mom", null), "Call me when you can", now - 90 * MIN));
        relay.drain();
        String mom = items("/api/relay/ingest").get(0).getString("conv");
        // A stranger's thread whose texts the server says are in Mom's conversation: the phone never saw them there.
        phone.thread(23, "+18015550666");
        phone.sms(23, "+18015550666", now - 20 * MIN, 1, true, "Your code is 482913");
        phone.sms(23, "+18015550666", now - 19 * MIN, 1, true, "Do not share it");
        server.located.put("Your code is 482913", mom);
        server.located.put("Do not share it", mom);
        relay.storeSync.scanNow(StoreSync.DAYS_2, false);
        relay.drain();
        assertNotNull(relay.prefs.threadConv(23));
        assertNotEquals(mom, relay.prefs.threadConv(23));
        assertNull(relay.prefs.number(mom));   // replies to Mom can't go to that number
        assertTrue(relay.prefs.participants(mom).isEmpty());
        assertEquals(0, server.calls("/api/relay/locate").size());
    }

    @Test
    public void aGreetingSentToManyPeopleNeverJoinsTheirThreads() throws Exception {
        long now = System.currentTimeMillis();
        phone.thread(41, "+18015550411");
        phone.sms(41, "+18015550411", now - 30 * MIN, 2, true, "Happy New Year to you and yours!");
        phone.sms(41, "+18015550411", now - 29 * MIN, 1, true, "You too!!");
        // The same greeting is in someone else's conversation, sent a minute apart; "You too!!" nowhere.
        String other = "abad1dea0abad1dea0abad1dea0abad1";
        if (v2()) {
            // as this phone saw it: Greg's notification shows the greeting going out to him too
            relay.onNotification(conversation("greg", "Greg", false, null, "Happy New Year to you and yours!", now - 29 * MIN));
            relay.drain();
            other = items("/api/relay/ingest").get(0).getString("conv");
        } else {
            server.located.put("Happy New Year to you and yours!", other);
        }
        relay.storeSync.scanNow(StoreSync.DAYS_2, false);
        String conv = relay.prefs.threadConv(41);
        assertNotNull(conv);
        assertNotEquals(other, conv);
        assertTrue(relay.prefs.fromStore(conv));
        assertNull(relay.prefs.number(other));
    }

    @Test
    public void aThreadTheStoreBroughtInFoldsIntoItsConversationOnceANotificationShowsWhoseItIs() throws Exception {
        long now = System.currentTimeMillis();
        // A conversation Messages' notifications brought, without a number on it.
        relay.onNotification(conversation("pat", "Pat", false, person("Pat", null), "Hey stranger", now - 50 * MIN));
        relay.drain();
        String conv = items("/api/relay/ingest").get(0).getString("conv");
        // The store has an older thread nobody could place: it gets a conversation of its own.
        phone.thread(5, "+18015550999");
        phone.sms(5, "+18015550999", now - 30 * MIN, 1, true, "From the spring");
        relay.storeSync.scanNow(StoreSync.DAYS_2, false);
        relay.drain();
        String own = relay.prefs.threadConv(5);
        assertNotNull(own);
        assertNotEquals(conv, own);
        assertTrue(relay.prefs.fromStore(own));

        // Then a text shows up both in that thread and in Pat's notification, which now carries her number: one conversation.
        phone.sms(5, "+18015550999", now - MIN, 1, false, "Are you around Saturday afternoon?");
        relay.onNotification(conversation("pat", "Pat", false, person("Pat", "tel:+18015550999"), "Hey stranger", now - 50 * MIN,
                "Are you around Saturday afternoon?", now - MIN + 300));
        assertEquals(conv, relay.prefs.threadConv(5));
        Prefs.Merge pending = relay.prefs.merges().get(0);
        assertEquals(own, pending.from);
        assertEquals(conv, pending.into);
        relay.drain();
        relay.flushMerges(relay.hedy());
        JSONObject merge = server.calls("/api/relay/merge").get(0);
        assertEquals(own, merge.getString("from"));
        assertEquals(conv, merge.getString("into"));
        // Done once more ten minutes on, for anything that was on its way, then forgotten.
        assertTrue(relay.prefs.merges().get(0).confirmed > 0);
        relay.prefs.mergeTried(own, conv, false, 0, 0);   // as if the ten minutes had passed
        relay.flushMerges(relay.hedy());
        assertEquals(2, server.calls("/api/relay/merge").size());
        assertTrue(relay.prefs.merges().isEmpty());

        // From now on the thread's messages go to the one conversation.
        relay.storeSync.scanNow(0, false);
        relay.drain();
        assertEquals(conv, find(items("/api/relay/ingest"), "Are you around Saturday afternoon?", "store").getString("conv"));
    }

    @Test
    public void aMergeWaitsWhileTheInboxDoesntHaveTheConversationYet() throws Exception {
        relay.prefs.addMerge("f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0", "e0e0e0e0e0e0e0e0e0e0e0e0e0e0e0e0");
        server.mergeStatus = 404;
        relay.flushMerges(relay.hedy());
        Prefs.Merge m = relay.prefs.merges().get(0);
        assertTrue(m.next > System.currentTimeMillis());   // kept, tried again later
        assertEquals(0, m.confirmed);
    }

    @Test
    public void aSameTextFromAnotherPersonOrWithoutEvidenceNeverLinksAThread() throws Exception {
        long now = System.currentTimeMillis();
        phone.thread(51, "+18015550511");
        phone.sms(51, "+18015550511", now - 3 * MIN, 1, false, "Running ten minutes late, sorry about that");
        // The same words, from someone else entirely (a different number on the notification).
        relay.onNotification(conversation("ann", "Ann", false, person("Ann", "tel:+18015550522"),
                "Running ten minutes late, sorry about that", now - 3 * MIN));
        assertNull(relay.prefs.threadConv(51));
        // A name with no number and no contact to check it against: no evidence, no link, however long the text.
        relay.onNotification(conversation("bo", "Bo", false, person("Bo", null), "Running ten minutes late, sorry about that", now - 3 * MIN + 5));
        assertNull(relay.prefs.threadConv(51));
        // A group's notification never takes a one-to-one thread.
        relay.onNotification(conversation("grp", "Crew", true, person("(801) 555-0511", null), "Running ten minutes late, sorry about that", now - 3 * MIN + 9));
        assertNull(relay.prefs.threadConv(51));
    }

    @Test
    public void aStoreProblemPartwayNeverMarksUnsentTextsAsSent() throws Exception {
        long now = System.currentTimeMillis();
        phone.thread(61, "+18015550611");
        phone.sms(61, "+18015550611", now - 20 * MIN, 1, true, "first");
        long mms = phone.mms(61, "+18015550611", now - 19 * MIN, 1, true, "second", "image/jpeg");
        phone.failPartsFor = mms;
        try {
            relay.storeSync.scanNow(StoreSync.DAYS_2, false);
            throw new AssertionError("the store problem should surface");
        } catch (RuntimeException expected) {
            // the check stops
        }
        phone.failPartsFor = -1;
        relay.storeSync.scanNow(StoreSync.DAYS_2, false);
        relay.drain();
        List<JSONObject> sent = items("/api/relay/ingest");
        find(sent, "first");
        find(sent, "second");
    }

    @Test
    public void aResyncGoesStraightToTheInboxInsteadOfFillingTheQueue() throws Exception {
        long now = System.currentTimeMillis();
        phone.thread(71, "+18015550711");
        for (int i = 0; i < 120; i++) phone.sms(71, "+18015550711", now - 3 * 86_400_000L + i * MIN, 1, true, "week " + i);
        relay.storeSync.scanNow(StoreSync.DAYS_7, false);
        relay.drain();
        int before = server.calls("/api/relay/ingest").size();
        connect();
        relay.storeSync.scanNow(StoreSync.DAYS_7, true);
        assertEquals(0, relay.outbound.size());
        assertEquals(3, server.calls("/api/relay/ingest").size() - before);   // 120 in batches of 50, sent right away
    }

    @Test
    public void aShortTextFromSomeoneElseNeverLinksTheWrongThread() throws Exception {
        long now = System.currentTimeMillis();
        phone.thread(31, "+18015550311");
        phone.sms(31, "+18015550311", now - 2 * MIN, 1, false, "ok");
        // Kim's notification says "ok" at the same moment, but it's not from that number and too short to go on.
        relay.onNotification(conversation("kim", "Kim", false, person("Kim", "tel:+18015550322"), "ok", now - 2 * MIN));
        assertNull(relay.prefs.threadConv(31));
        relay.onNotification(conversation("lee", "Lee", false, person("Lee", null), "ok", now - 2 * MIN + 1));
        assertNull(relay.prefs.threadConv(31));
    }

    // ------------------------------------------------------------------ full history

    @Test
    public void fullHistoryTextOnlyBringsInEveryConversationQuietly() throws Exception {
        long old = System.currentTimeMillis() - 400L * 86_400_000;
        phone.thread(1, "+18015550001");
        for (int i = 0; i < 70; i++) phone.sms(1, "+18015550001", old + i * MIN, i % 2 == 0 ? 1 : 2, true, "old " + i);
        phone.mms(1, "+18015550001", old + 100 * MIN, 1, true, "A picture from back then", "image/jpeg");
        phone.thread(2, "+18015550002");
        phone.sms(2, "+18015550002", old + 200 * MIN, 1, true, "Hello from 2025");
        relay.mediaReader = (uri, mime) -> { throw new AssertionError("text only: nothing is read"); };

        relay.historySync.start();
        waitFor(() -> "done".equals(relay.historySync.status().optString("state")));
        JSONObject status = relay.historySync.status();
        assertEquals(72, status.getLong("done"));
        assertFalse(status.getBoolean("media"));

        List<JSONObject> sent = items("/api/relay/ingest");
        assertEquals(72, sent.size());
        for (JSONObject it : sent) {
            assertTrue(it.getBoolean("history"));
            assertEquals("store", it.getString("src"));
            assertFalse(it.has("wantMedia"));
        }
        assertEquals("image/jpeg", find(sent, "A picture from back then").getJSONObject("media").getString("mime"));
        for (JSONObject call : server.calls("/api/relay/ingest")) assertTrue(call.getJSONArray("items").length() <= 50);
        assertTrue(server.calls("/api/relay/media").isEmpty());
        // The newest conversation first.
        assertEquals("Hello from 2025", sent.get(0).getString("text"));
    }

    @Test
    public void fullHistoryWithMediaUploadsWhatTheInboxDoesntHaveAndPicksUpAfterARestart() throws Exception {
        long old = System.currentTimeMillis() - 30L * 86_400_000;
        byte[] photo = new byte[2000];
        relay.mediaReader = (uri, mime) -> new Media.Prepared(new Media.Blob(photo, "image/jpeg", 20, 20), null);
        phone.thread(1, "+18015550001");
        long a = phone.mms(1, "+18015550001", old, 1, true, null, "image/jpeg");
        long b = phone.mms(1, "+18015550001", old + MIN, 1, true, null, "image/jpeg");
        // The inbox already has the first picture (it came through a notification once).
        server.haveMedia.add(Parser.hash("p|mms:" + a, 8));
        relay.historySync.setMedia(true);

        // A restart in the middle: the saved progress carries on.
        server.rejectIngestWith = 503;
        relay.historySync.start();
        waitFor(() -> !relay.historySync.status().optString("note").isEmpty());
        Relay.reset();
        server.rejectIngestWith = 0;
        relay = Relay.get(app);
        relay.mediaReader = (uri, mime) -> new Media.Prepared(new Media.Blob(photo, "image/jpeg", 20, 20), null);
        assertEquals("running", relay.historySync.status().optString("state"));
        relay.historySync.resume();
        waitFor(() -> "done".equals(relay.historySync.status().optString("state")));

        assertEquals(1, server.calls("/api/relay/media").size());
        assertTrue(server.calls("/api/relay/media").get(0).getJSONObject("_headers").getString("x-relay-fp")
                .equals(Parser.hash("p|mms:" + b, 8)));
        assertTrue(relay.historySync.status().getBoolean("media"));
    }

    @Test
    public void theHeartbeatTellsTextsHowTheHistoryIsGoing() throws Exception {
        relay.heartbeat(relay.hedy());
        JSONObject store = server.calls("/api/relay/heartbeat").get(0).getJSONObject("store");
        assertTrue(store.getBoolean("allowed"));
        assertEquals("idle", store.getJSONObject("history").getString("state"));
    }

    // ------------------------------------------------------------------ codes Android hides

    @Test
    public void aCodeAndroidHidesFromNotificationsComesFromTheStoreInItsConversation() throws Exception {
        long now = System.currentTimeMillis();
        Person bank = person("Mountain Credit Union", "tel:+18015550188");
        // The bank's texts have come through notifications before, and its thread is linked.
        relay.onNotification(conversation("bank", "Mountain Credit Union", false, bank, "Your statement is ready", now - 60 * MIN));
        relay.drain();
        String conv = items("/api/relay/ingest").get(0).getString("conv");
        phone.thread(11, "+18015550188");
        phone.sms(11, "+18015550188", now - 60 * MIN + 300, 1, true, "Your statement is ready");
        relay.storeSync.scanNow(StoreSync.DAYS_2, false);
        relay.drain();
        assertEquals(conv, relay.prefs.threadConv(11));
        int before = items("/api/relay/ingest").size();

        // A code arrives, and Android hands Relay only a stand-in for its notification.
        phone.sms(11, "+18015550188", now - 2_000, 1, false, "Your code is 482913");
        StatusBarNotification real = conversation("bank", "Mountain Credit Union", false, bank,
                "Your statement is ready", now - 60 * MIN, "Your code is 482913", now - 2_000);
        assertEquals(0, relay.onNotification(hiddenVersionOf(real), false));
        relay.drain();
        assertEquals(before, items("/api/relay/ingest").size());

        // The code comes from the message store, in the bank's conversation, under the bank's name.
        assertEquals(1, relay.storeSync.scanNow(0, false));
        relay.drain();
        List<JSONObject> all = items("/api/relay/ingest");
        JSONObject code = find(all, "Your code is 482913");
        assertEquals(conv, code.getString("conv"));
        assertEquals("in", code.getString("dir"));
        assertEquals("Mountain Credit Union", code.getString("name"));
        for (JSONObject it : all) assertFalse(it.toString(), Parser.isHiddenText(it.optString("text")));
        assertEquals("Mountain Credit Union", relay.prefs.name(conv));

        // Relay's screen says what happened.
        relay.prefs.setVerified(true);
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        String screen = allText(activity.findViewById(android.R.id.content));
        assertTrue(screen, screen.contains("Android hid a text it took for a one-time code"));
        assertTrue(screen, screen.contains("it comes from the message store instead"));
    }

    @Test
    public void aHiddenNotificationNeverStartsOrNamesAConversation() throws Exception {
        long now = System.currentTimeMillis();
        // A first text from a short code, and its notification reaches Relay only as a stand-in.
        phone.thread(12, "72975");
        phone.sms(12, "72975", now - 1_000, 1, false, "G-482913 is your Google verification code.");
        StatusBarNotification hidden = hiddenVersionOf(conversation("g", "72975", false, person("72975", "tel:72975"),
                "G-482913 is your Google verification code.", now - 1_000));
        Parser.Snapshot s = Parser.parse(hidden);
        assertNotNull(s);
        assertTrue(s.hidden);
        assertEquals(0, relay.onNotification(hidden, false));
        assertFalse(relay.prefs.knows(s.id));
        assertNull(relay.prefs.notificationConv(s.id));

        // The store brings the code in at once: no notification is coming to say whose thread it is.
        assertEquals(1, relay.storeSync.scanNow(0, false));
        relay.drain();
        JSONObject code = find(items("/api/relay/ingest"), "G-482913 is your Google verification code.");
        assertEquals("in", code.getString("dir"));
        assertEquals("store", code.getString("src"));
        assertEquals(relay.prefs.threadConv(12), code.getString("conv"));
        assertEquals("72975", code.getString("name"));
        assertFalse(relay.prefs.knows(s.id));
    }

    @Test
    public void anOrdinaryNotificationIsNeverTakenForAHiddenOne() {
        long now = System.currentTimeMillis();
        Parser.Snapshot s = Parser.parse(conversation("pat", "Pat", false, person("Pat", "tel:+18015550144"), "See you at 6", now));
        assertNotNull(s);
        assertFalse(s.hidden);
        assertEquals("Pat", s.title);
        assertTrue(Parser.isHiddenText(" Sensitive notification content hidden "));
        assertFalse(Parser.isHiddenText("Sensitive notification content hidden, ha"));
    }

    // ------------------------------------------------------------------ helpers

    /**
     * What Android 15 and later hand an app like Relay in place of a notification they take for a one-time code, as
     * NotificationManagerService builds it: the app's name, one message from nobody, timed now, the actions retitled.
     */
    private StatusBarNotification hiddenVersionOf(StatusBarNotification real) {
        String hidden = "Sensitive notification content hidden";
        Notification old = real.getNotification();
        Person nobody = new Person.Builder().setName("").build();
        Notification.MessagingStyle style = new Notification.MessagingStyle(nobody);
        style.addMessage(new Notification.MessagingStyle.Message(hidden, System.currentTimeMillis(), nobody));
        Notification.Builder b = new Notification.Builder(app, old.getChannelId())
                .setSmallIcon(android.R.drawable.sym_action_chat)
                .setContentTitle("Messages")
                .setContentText(hidden)
                .setCategory(old.category)
                .setShortcutId(old.getShortcutId())
                .setStyle(style);
        for (Notification.Action a : old.actions) {
            Notification.Action act = new Notification.Action.Builder(a).build();
            act.title = "Sensitive app content hidden";
            b.addAction(act);
        }
        return new StatusBarNotification(MESSAGES, MESSAGES, real.getId(), real.getTag(), 10_001, 0, 0, b.build(),
                Process.myUserHandle(), real.getPostTime());
    }

    private static String allText(android.view.View v) {
        StringBuilder b = new StringBuilder();
        if (v instanceof android.widget.TextView) b.append(((android.widget.TextView) v).getText()).append('\n');
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) b.append(allText(g.getChildAt(i)));
        }
        return b.toString();
    }

    private long partId(long mmsId, String mime) {
        try (android.database.Cursor c = app.getContentResolver().query(Store.MMS_PART, new String[] { "_id" }, "mid = ? AND ct = ?",
                new String[] { Long.toString(mmsId), mime }, null)) {
            assertNotNull(c);
            assertTrue(c.moveToFirst());
            return c.getLong(0);
        }
    }

    /** Every item the inbox received, in order. */
    private List<JSONObject> items(String path) throws Exception {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject call : server.calls(path)) {
            JSONArray arr = call.optJSONArray("items");
            for (int i = 0; arr != null && i < arr.length(); i++) out.add(arr.getJSONObject(i));
        }
        return out;
    }

    private static JSONObject find(List<JSONObject> items, String text) {
        for (JSONObject it : items) if (text.equals(it.optString("text"))) return it;
        throw new AssertionError("nothing with text " + text + " in " + items);
    }

    private static JSONObject find(List<JSONObject> items, String text, String src) {
        for (JSONObject it : items) if (text.equals(it.optString("text")) && src.equals(it.optString("src"))) return it;
        throw new AssertionError("nothing with text " + text + " from " + src + " in " + items);
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long until = System.currentTimeMillis() + 20_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < until) Thread.sleep(20);
        assertTrue("timed out", condition.getAsBoolean());
    }

    private static Person person(String name, String uri) {
        Person.Builder b = new Person.Builder().setName(name).setKey(name.toLowerCase(Locale.US));
        if (uri != null) b.setUri(uri);
        return b.build();
    }

    /** A one-to-one Messages notification from `who`: pairs of text and time. */
    private StatusBarNotification conversation(String id, String title, boolean group, Person who, Object... textsAndTimes) {
        Person me = new Person.Builder().setName("You").setKey("me").build();
        Notification.MessagingStyle style = new Notification.MessagingStyle(me);
        if (group) {
            style.setConversationTitle(title);
            style.setGroupConversation(true);
        }
        for (int i = 0; i + 1 < textsAndTimes.length; i += 2) {
            style.addMessage(new Notification.MessagingStyle.Message((String) textsAndTimes[i], (Long) textsAndTimes[i + 1], who));
        }
        Intent replyIntent = new Intent("REPLY_" + id).setPackage(app.getPackageName());
        PendingIntent pi = PendingIntent.getBroadcast(app, id.hashCode(), replyIntent, PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Action reply = new Notification.Action.Builder(null, "Reply", pi)
                .addRemoteInput(new RemoteInput.Builder("reply_text").setLabel("Reply").build())
                .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
                .build();
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
