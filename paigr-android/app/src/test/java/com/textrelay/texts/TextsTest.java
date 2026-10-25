package com.textrelay.texts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.Parcelable;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowNotificationManager;
import org.robolectric.shadows.ShadowWebView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class TextsTest {
    private static final String KEY = "hak_inbox0123456789.abcDEFghiJKLmnoPQRstuVWXyz0123456789";

    private FakeInbox server;
    private Application app;
    private Prefs prefs;
    private final List<List<JSONObject>> announced = new ArrayList<>();
    private final List<String> relayStates = new ArrayList<>();
    private Sync sync;

    @Before
    public void setUp() throws IOException {
        server = new FakeInbox();
        app = RuntimeEnvironment.getApplication();
        prefs = new Prefs(app);
        prefs.setServer(server.baseUrl());
        prefs.setKey(KEY);
        sync = new Sync(prefs, new Sync.Listener() {
            @Override
            public void onIncoming(List<JSONObject> messages) {
                announced.add(messages);
            }

            @Override
            public void onRelayState(boolean online, long lastSeen) {
                relayStates.add(online ? "online" : "offline");
            }
        });
    }

    @After
    public void tearDown() throws IOException {
        server.close();
    }

    private Hedy hedy() {
        return new Hedy(prefs.server(), prefs.key());
    }

    // ------------------------------------------------------------------ checking the inbox

    @Test
    public void firstCheckLearnsWhereNowIsAndAnnouncesNothing() throws Exception {
        server.add("m:1000000000001:aa", "c1", "in", "Megan", "Megan", "old text from before the app");
        assertNull(prefs.feed());
        sync.checkOnce(hedy());
        assertNotNull(prefs.feed());
        assertTrue(prefs.feed().startsWith("f:"));
        assertTrue(announced.isEmpty());   // not even the re-read of the moments before "now"
        assertEquals("/api/inbox", server.requests.get(0));
        sync.checkOnce(hedy());
        assertTrue(announced.isEmpty());
    }

    @Test
    public void newIncomingTextsAreAnnouncedOnceAndOutgoingOnesAreNot() throws Exception {
        sync.checkOnce(hedy());
        server.add("m:1000000000002:aa", "c1", "in", "Megan", "Megan", "Are we still on?");
        server.add("m:1000000000003:ab", "c1", "out", "", "Megan", "Yes");
        server.add("m:1000000000004:ac", "c2", "in", "Sam", "Climbing crew", "Rope?");
        sync.checkOnce(hedy());
        assertEquals(1, announced.size());
        assertEquals(2, announced.get(0).size());
        assertEquals("Are we still on?", announced.get(0).get(0).getString("text"));
        assertEquals("Rope?", announced.get(0).get(1).getString("text"));
        sync.checkOnce(hedy());   // the server re-reads the last few seconds; nothing is announced twice
        sync.checkOnce(hedy());
        assertEquals(1, announced.size());
        assertTrue(server.requests.get(server.requests.size() - 1).startsWith("/api/sync?feed=f%3A"));
    }

    @Test
    public void aTextThatReachesTheServerLateIsStillAnnounced() throws Exception {
        sync.checkOnce(hedy());
        server.add("m:1000000000009:aa", "c1", "out", "", "Megan", "On my way");
        sync.checkOnce(hedy());
        // Sent from the relay device an hour ago, delivered only now: older than the reply, newer in arrival.
        server.add("m:0999999999999:bb", "c1", "in", "Megan", "Megan", "Where are you?");
        sync.checkOnce(hedy());
        assertEquals(1, announced.size());
        assertEquals("Where are you?", announced.get(0).get(0).getString("text"));
    }

    @Test
    public void nothingIsAnnouncedWhileTheScreenIsOpenButThePlaceStillMoves() throws Exception {
        sync.checkOnce(hedy());
        server.add("m:1000000000005:aa", "c1", "in", "Megan", "Megan", "hi");
        sync.screenOpen = true;
        assertEquals(Sync.SLOW_SECONDS, sync.checkOnce(hedy()));
        assertTrue(announced.isEmpty());
        sync.screenOpen = false;
        sync.checkOnce(hedy());
        assertTrue(announced.isEmpty());   // seen on screen; not news later
    }

    @Test
    public void checksComeFasterRightAfterTheAppWasUsed() throws Exception {
        sync.checkOnce(hedy());
        assertEquals(Sync.SLOW_SECONDS, sync.interval());
        prefs.touchUsed();
        assertEquals(Sync.FAST_SECONDS, sync.interval());
    }

    @Test
    public void aQuietRelayDeviceIsReportedOnceAndSoIsItsReturn() throws Exception {
        server.relayLastSeen = System.currentTimeMillis();
        sync.checkOnce(hedy());
        sync.checkOnce(hedy());
        assertTrue(relayStates.isEmpty());   // healthy from the start: nothing to say
        server.relayLastSeen = System.currentTimeMillis() - 11 * 60_000;
        sync.checkOnce(hedy());
        sync.checkOnce(hedy());
        assertEquals(List.of("offline"), relayStates);
        server.relayLastSeen = System.currentTimeMillis();
        sync.checkOnce(hedy());
        assertEquals(List.of("offline", "online"), relayStates);
    }

    @Test
    public void withTheDoorbellConnectedChecksAreOnlyInsurance() throws Exception {
        sync.checkOnce(hedy());
        prefs.touchUsed();
        assertEquals(Sync.FAST_SECONDS, sync.interval());
        sync.doorbellConnected = true;
        assertEquals(Sync.CONNECTED_SECONDS, sync.interval());
    }

    @Test
    public void aRejectedKeyIsReportedAsSuch() throws Exception {
        server.expectedKey = "something-else";
        try {
            sync.checkOnce(hedy());
            fail("expected a rejected key");
        } catch (Hedy.ApiException e) {
            assertTrue(e.keyRejected());
        }
    }

    @Test
    public void aNewKeyForgetsThePlaceSoHistoryIsSkippedAgain() {
        prefs.setFeed("f:1790000000000");
        assertTrue(prefs.markSeen("m:1:aa", null, 0));
        assertFalse(prefs.markSeen("m:1:aa", null, 0));
        prefs.setKey("hak_other.key");
        assertNull(prefs.feed());
        assertTrue(prefs.markSeen("m:1:aa", null, 0));
        prefs.setKey("");
        assertFalse(prefs.paired());
    }

    // ------------------------------------------------------------------ notifications

    @Test
    public void oneNotificationPerConversationThatOpensIt() throws Exception {
        Notifier notifier = new Notifier(app);
        ShadowNotificationManager nm = shadowOf(app.getSystemService(NotificationManager.class));
        List<JSONObject> batch = new ArrayList<>();
        batch.add(message("c1", "Megan", "Megan", false, "Are we still on?"));
        batch.add(message("c1", "Megan", "Megan", false, "Hello?"));
        batch.add(message("c2", "Sam", "Climbing crew", true, "Rope?"));
        notifier.show(batch);

        assertEquals(3, nm.size());   // two conversations plus the group summary
        Notification megan = nm.getNotification(Notifier.idFor("c1"));
        assertNotNull(megan);
        assertEquals("Megan", megan.extras.getString(Notification.EXTRA_TITLE));
        Parcelable[] lines = megan.extras.getParcelableArray(Notification.EXTRA_MESSAGES);
        assertNotNull(lines);
        assertEquals(2, lines.length);
        assertEquals("Hello?", ((Bundle) lines[1]).getCharSequence("text").toString());
        Intent opens = shadowOf(megan.contentIntent).getSavedIntent();
        assertEquals("c1", opens.getStringExtra(MainActivity.EXTRA_CONV));

        Notification crew = nm.getNotification(Notifier.idFor("c2"));
        assertTrue(crew.extras.getString(Notification.EXTRA_TITLE).startsWith("Climbing crew"));
        assertTrue(crew.extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION));

        notifier.clear("c1");
        assertNull(nm.getNotification(Notifier.idFor("c1")));
        assertNotNull(nm.getNotification(Notifier.idFor("c2")));
    }

    @Test
    public void quietModeKeepsNewMessagesInTheShadeOnly() throws Exception {
        prefs.setAlertMode(Prefs.ALERT_QUIET);
        Notifier notifier = new Notifier(app);
        NotificationManager manager = app.getSystemService(NotificationManager.class);
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(Notifier.CHANNEL_QUIET).getImportance());
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel(Notifier.CHANNEL).getImportance());
        List<JSONObject> batch = new ArrayList<>();
        batch.add(message("c4", "Megan", "Megan", false, "psst"));
        notifier.show(batch);
        assertEquals(Notifier.CHANNEL_QUIET, shadowOf(manager).getNotification(Notifier.idFor("c4")).getChannelId());
        prefs.setAlertMode(Prefs.ALERT_NORMAL);
        notifier.show(batch);
        assertEquals(Notifier.CHANNEL, shadowOf(manager).getNotification(Notifier.idFor("c4")).getChannelId());
    }

    @Test
    public void relayAlertsSayWhatToDoAndComingBackIsSilent() {
        Notifier notifier = new Notifier(app);
        ShadowNotificationManager nm = shadowOf(app.getSystemService(NotificationManager.class));
        notifier.relayState(false, System.currentTimeMillis() - 12 * 60_000);
        Notification off = nm.getNotification(Notifier.RELAY_ID);
        assertEquals(Notifier.CHANNEL, off.getChannelId());
        assertTrue(String.valueOf(off.extras.getCharSequence(Notification.EXTRA_TEXT)).contains("Reconnect"));
        notifier.relayState(true, System.currentTimeMillis());
        assertEquals(Notifier.CHANNEL_QUIET, nm.getNotification(Notifier.RELAY_ID).getChannelId());
    }

    @Test
    public void theBackgroundNotificationIsSilentAndHidesThroughAndroidsOwnSetting() {
        SyncService.createChannels(app);
        NotificationManager manager = app.getSystemService(NotificationManager.class);
        android.app.NotificationChannel channel = manager.getNotificationChannel(SyncService.CHANNEL);
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.getImportance());
        assertNull(channel.getSound());
        assertNull(manager.getNotificationChannel(SyncService.OLD_STANDARD));
        Notification n = SyncService.build(app);
        assertEquals(SyncService.CHANNEL, n.getChannelId());
        assertEquals(Notification.VISIBILITY_SECRET, n.visibility);
        assertFalse(SyncService.statusHidden(app));
        // The person turns it off in Android's settings: Texts reads that as hidden.
        manager.deleteNotificationChannel(SyncService.CHANNEL);
        manager.createNotificationChannel(new android.app.NotificationChannel(SyncService.CHANNEL, "Background status", NotificationManager.IMPORTANCE_NONE));
        assertTrue(SyncService.statusHidden(app));
    }

    @Test
    public void withAlertsOffThereAreNoNotificationsAndNothingRunsInTheBackground() throws Exception {
        prefs.setAlertMode(Prefs.ALERT_OFF);
        Notifier notifier = new Notifier(app);
        ShadowNotificationManager nm = shadowOf(app.getSystemService(NotificationManager.class));
        List<JSONObject> batch = new ArrayList<>();
        batch.add(message("c1", "Megan", "Megan", false, "psst"));
        notifier.show(batch);
        notifier.relayState(false, System.currentTimeMillis() - 20 * 60_000);
        assertEquals(0, nm.size());
        SyncService.start(app);
        assertNull(shadowOf(app).getNextStartedService());
    }

    @Test
    public void textsAlreadyReadOnTheRelayPhoneOrBroughtInFromHistoryAreNotAnnounced() throws Exception {
        sync.checkOnce(hedy());
        server.add("m:1000000000020:aa", "c1", "in", "Megan", "Megan", "read on the relay phone").put("read", true);
        server.add("m:1000000000021:ab", "c1", "in", "Megan", "Megan", "from 2024").put("history", true).put("read", true);
        server.add("m:1000000000022:ac", "c1", "in", "Megan", "Megan", "new");
        sync.checkOnce(hedy());
        assertEquals(1, announced.size());
        assertEquals(1, announced.get(0).size());
        assertEquals("new", announced.get(0).get(0).getString("text"));
    }

    @Test
    public void afterLongerThanTheLogKeepsChecksStartAgainFromNow() throws Exception {
        sync.checkOnce(hedy());
        server.add("m:1000000000030:aa", "c1", "in", "Megan", "Megan", "while away");
        server.resetNext = true;
        sync.checkOnce(hedy());
        assertTrue(announced.isEmpty());
        server.add("m:1000000000031:ab", "c1", "in", "Megan", "Megan", "after");
        sync.checkOnce(hedy());
        assertEquals("after", announced.get(0).get(announced.get(0).size() - 1).getString("text"));
    }

    @Test
    public void picturesWithoutTextAreDescribed() throws Exception {
        Notifier notifier = new Notifier(app);
        ShadowNotificationManager nm = shadowOf(app.getSystemService(NotificationManager.class));
        JSONObject pic = message("c3", "Dad", "Dad", false, "").put("media", new JSONObject().put("mime", "image/jpeg"));
        List<JSONObject> batch = new ArrayList<>();
        batch.add(pic);
        notifier.show(batch);
        Parcelable[] lines = nm.getNotification(Notifier.idFor("c3")).extras.getParcelableArray(Notification.EXTRA_MESSAGES);
        assertEquals("Picture", ((Bundle) lines[0]).getCharSequence("text").toString());
    }

    // ------------------------------------------------------------------ the screen

    @Test
    public void screenLoadsTheInboxAndOpensTheConversationFromANotification() {
        WebActivity activity = Robolectric.buildActivity(WebActivity.class,
                new Intent(app, WebActivity.class).putExtra(MainActivity.EXTRA_CONV, "c9")).setup().get();
        ShadowWebView web = shadowOf((android.webkit.WebView) ((android.view.ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0));
        assertEquals(server.baseUrl() + "/?conv=c9", web.getLastLoadedUrl());
        assertTrue(web.getJavascriptInterface("TextsNative") != null);
        Intent started = shadowOf(app).getNextStartedService();
        assertNotNull("paired, so the background checks start with the screen", started);
        assertEquals(SyncService.class.getName(), started.getComponent().getClassName());
    }

    @Test
    public void aPhoneOnTheFirstVersionGetsThePageWhichMovesItsInbox() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class,
                new Intent(app, MainActivity.class).putExtra(MainActivity.EXTRA_CONV, "c9")).setup().get();
        Intent next = shadowOf(activity).getNextStartedActivity();
        assertEquals(WebActivity.class.getName(), next.getComponent().getClassName());
        assertEquals("c9", next.getStringExtra(MainActivity.EXTRA_CONV));
        assertTrue(activity.isFinishing());
    }

    @Test
    public void onceTheMoveIsDoneTheAppsOwnScreensTakeOver() throws Exception {
        WebActivity activity = Robolectric.buildActivity(WebActivity.class).setup().get();
        Object bridge = pageBridge(activity);
        Link l = new Link(server.baseUrl(), "00112233445566778899aabbccddeeff", "tokTexts0123456789abcdefghijklmnopqrstuvwxy", INBOX_KEY);
        bridge.getClass().getMethod("setLink", String.class).invoke(bridge, l.forPage());
        bridge.getClass().getMethod("setKey", String.class).invoke(bridge, "");   // the page dropped the first version's key
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        Intent next = shadowOf(activity).getNextStartedActivity();
        assertEquals(MainActivity.class.getName(), next.getComponent().getClassName());
        assertTrue(activity.isFinishing());
    }

    @Test
    public void theWebAppCanReadAndSetTheAlertAndStatusChoices() throws Exception {
        WebActivity activity = Robolectric.buildActivity(WebActivity.class).setup().get();
        Object bridge = pageBridge(activity);
        assertEquals("normal", bridge.getClass().getMethod("alertMode").invoke(bridge));
        assertEquals("shown", bridge.getClass().getMethod("statusMode").invoke(bridge));
        bridge.getClass().getMethod("setAlertMode", String.class).invoke(bridge, "quiet");
        assertEquals(Prefs.ALERT_QUIET, prefs.alertMode());
        // Hiding the background notification opens Android's setting for it, the only place it can be turned off.
        bridge.getClass().getMethod("setStatusMode", String.class).invoke(bridge, "hidden");
        Intent settings = shadowOf(activity).getNextStartedActivity();
        assertEquals(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, settings.getAction());
        assertEquals(SyncService.CHANNEL, settings.getStringExtra(android.provider.Settings.EXTRA_CHANNEL_ID));
        // Off: no notifications, and the background checks stop.
        bridge.getClass().getMethod("setAlertMode", String.class).invoke(bridge, "off");
        assertEquals(Prefs.ALERT_OFF, prefs.alertMode());
        Intent stopped = shadowOf(app).getNextStoppedService();
        assertNotNull(stopped);
        assertEquals(SyncService.class.getName(), stopped.getComponent().getClassName());
        assertEquals(MainActivity.VERSION, bridge.getClass().getMethod("version").invoke(bridge));
    }

    // ------------------------------------------------------------------ the private inbox (v2)

    static final byte[] INBOX_KEY = new byte[32];
    static {
        for (int i = 0; i < 32; i++) INBOX_KEY[i] = (byte) (i * 5 + 1);
    }

    private Link privateInbox() {
        Link l = new Link(server.baseUrl(), "00112233445566778899aabbccddeeff", "tokTexts0123456789abcdefghijklmnopqrstuvwxy", INBOX_KEY);
        server.link = l;
        prefs.setKey("");
        prefs.setLink(l);
        return l;
    }

    @Test
    public void aPrivateInboxAnnouncesWhatItOpensWithTheKey() throws Exception {
        Link l = privateInbox();
        sync.link = l;
        Hedy h = new Hedy(l);
        sync.checkOnce(h);   // learns where now is
        server.add("m:1:a", "c1", "in", "Andy", "Andy Graham", "Lunch at Sundance tomorrow?");
        JSONObject pic = server.add("m:2:b", "c2", "in", "Megan", "Megan", "");
        pic.put("media", new JSONObject().put("mime", "image/jpeg"));
        sync.checkOnce(h);
        assertEquals(1, announced.size());
        List<JSONObject> got = announced.get(0);
        assertEquals(2, got.size());
        assertEquals("Lunch at Sundance tomorrow?", got.get(0).getString("text"));
        assertEquals("Andy", got.get(0).getString("sender"));
        assertEquals("Andy Graham", got.get(0).getString("name"));
        assertFalse(got.get(0).has("e"));
        assertEquals("image/jpeg", got.get(1).getJSONObject("media").getString("mime"));
        assertTrue(got.get(1).getJSONObject("media").getString("path").startsWith("/media/v2/"));
        assertTrue(server.requests.contains("/v2/inbox"));
        // Filed by the server under another conversation, a message doesn't open there: no name, no words of it.
        server.add("m:3:c", "c3", "in", "Mom", "Mom", "Can you call me?").put("sealFor", "c1");
        // Sealed before two conversations became one, it opens in the one it's in now, on the relay phone's word for it.
        server.add("m:4:d", "c5", "in", "Pat", "Pat", "See you there").put("sealFor", "c4");
        server.merged.put("c5", new org.json.JSONArray().put(l.mergeRecordForTests("c4", "c5")));
        // ...and only on that: a record for another conversation vouches for nothing here.
        server.add("m:5:e", "c6", "in", "Sam", "Sam", "Hello?").put("sealFor", "c4");
        server.merged.put("c6", new org.json.JSONArray().put(l.mergeRecordForTests("c4", "c5")));
        sync.checkOnce(h);
        assertEquals(2, announced.size());
        List<JSONObject> more = announced.get(1);
        assertEquals(3, more.size());
        assertEquals("New message", more.get(0).getString("text"));
        assertEquals("", more.get(0).getString("name"));
        assertEquals("", more.get(0).getString("sender"));
        assertEquals("See you there", more.get(1).getString("text"));
        assertEquals("Pat", more.get(1).getString("name"));
        assertEquals("New message", more.get(2).getString("text"));
        assertEquals("", more.get(2).getString("name"));
        // Removed from the inbox: the service says so and stops.
        server.revoked = true;
        try {
            sync.checkOnce(h);
            fail("expected the inbox to say this device isn't in it");
        } catch (Hedy.ApiException e) {
            assertTrue(e.unlinked);
        }
    }

    @Test
    public void thePageLinksThroughTheBridgeAndAReinstallGetsBackInByItself() throws Exception {
        prefs.setKey("");
        MainActivity.androidIdForTests = "5f3a9c2e7b1d4e60";
        try {
            WebActivity activity = Robolectric.buildActivity(WebActivity.class).setup().get();
            Object bridge = pageBridge(activity);
            assertEquals("", bridge.getClass().getMethod("link").invoke(bridge));
            waitFor(() -> "done".equals(MainActivity.recovery));   // asked, and the inbox didn't know this phone

            // The page linked this phone: the app keeps the link and files its wrap.
            Link l = new Link(server.baseUrl(), "00112233445566778899aabbccddeeff", "tokTexts0123456789abcdefghijklmnopqrstuvwxy", INBOX_KEY);
            server.link = l;
            bridge.getClass().getMethod("setLink", String.class).invoke(bridge, l.forPage());
            assertTrue(l.sameAs(prefs.link()));
            assertEquals(l.forPage(), bridge.getClass().getMethod("link").invoke(bridge));
            waitFor(() -> !server.wraps.isEmpty());
            Crypto.PhoneSecrets p = Crypto.phoneSecrets("5f3a9c2e7b1d4e60", "inbox");
            assertTrue(l.sameAs(Link.unwrap(server.baseUrl(), p.wrapKey, server.wraps.get(p.tag))));
            assertFalse(server.wraps.containsKey(Crypto.phoneSecrets("5f3a9c2e7b1d4e60", "relay").tag));   // Relay's tag on this phone is another

            // Unlinked (or told it isn't in its inbox, true or not): it waits for a code, and never asks for its wrap.
            prefs.setLink(null);
            assertNull(prefs.link());
            long asked = server.requests.stream().filter("/v2/link/recover"::equals).count();
            Robolectric.buildActivity(MainActivity.class).setup();
            Thread.sleep(500);
            assertNull(prefs.link());
            assertEquals(asked, server.requests.stream().filter("/v2/link/recover"::equals).count());
            // Reinstalled: nothing on the phone, and it's back in its inbox without a code.
            app.getSharedPreferences("texts", android.content.Context.MODE_PRIVATE).edit().clear().commit();
            prefs.setServer(server.baseUrl());
            Robolectric.buildActivity(MainActivity.class).setup();
            waitFor(() -> prefs.link() != null);
            assertTrue(l.sameAs(prefs.link()));
            assertTrue(server.requests.contains("/v2/link/recover"));
            assertEquals("Unknown".length() > 0, bridge.getClass().getMethod("deviceName").invoke(bridge).toString().length() > 0);
        } finally {
            MainActivity.androidIdForTests = null;
        }
    }

    @Test
    public void thePageItselfComesFromTheApp() throws Exception {
        WebActivity activity = Robolectric.buildActivity(WebActivity.class).setup().get();
        android.webkit.WebResourceResponse r = activity.bundled(android.net.Uri.parse(server.baseUrl() + "/app.js"), "GET");
        assertNotNull(r);
        assertEquals("text/javascript", r.getMimeType());
        String js = new String(readAll(r.getData()), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(js.contains("Paigr: the inbox page"));
        assertNotNull(activity.bundled(android.net.Uri.parse(server.baseUrl() + "/?conv=c9"), "GET"));
        assertNotNull(activity.bundled(android.net.Uri.parse(server.baseUrl() + "/tr2.js"), "GET"));
        assertNull(activity.bundled(android.net.Uri.parse(server.baseUrl() + "/v2/inbox"), "GET"));          // the inbox's data still comes from the inbox
        assertNull(activity.bundled(android.net.Uri.parse("https://elsewhere.example/app.js"), "GET"));      // and nothing on any other site
        assertNull(activity.bundled(android.net.Uri.parse(server.baseUrl() + "/app.js"), "POST"));
    }

    /**
     * A link in a text can point at any page on the inbox server. It opens outside the app, and nothing but the app's own
     * page ever gets an answer from the bridge (which hands over the inbox key).
     */
    @Test
    public void onlyTheAppsOwnPageOpensInTheAppAndOnlyItGetsTheBridge() throws Exception {
        Link l = new Link(server.baseUrl(), "00112233445566778899aabbccddeeff", "tokTexts0123456789abcdefghijklmnopqrstuvwxy", INBOX_KEY);
        prefs.setLink(l);
        WebActivity activity = Robolectric.buildActivity(WebActivity.class).setup().get();
        android.webkit.WebView view = (android.webkit.WebView) ((android.view.ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0);
        ShadowWebView web = shadowOf(view);
        android.webkit.WebViewClient client = web.getWebViewClient();
        Object bridge = web.getJavascriptInterface("TextsNative");
        java.lang.reflect.Method link = bridge.getClass().getMethod("link");
        // Nothing has been vetted yet (say a leftover service worker served a page of its own): the bridge says nothing.
        assertEquals("", link.invoke(bridge));
        assertEquals(false, bridge.getClass().getMethod("isApp").invoke(bridge));
        // The app's own page, from its assets: now it answers.
        assertNotNull(client.shouldInterceptRequest(view, request(server.baseUrl() + "/", true, "GET")));
        assertEquals(l.forPage(), link.invoke(bridge));

        // Tapping a link to another page on the inbox server: the phone's browser opens it, not the app.
        assertTrue(client.shouldOverrideUrlLoading(view, request(server.baseUrl() + "/x?steal=1", true, "GET")));
        Intent opened = shadowOf(activity).getNextStartedActivity();
        assertNotNull(opened);
        assertEquals(Intent.ACTION_VIEW, opened.getAction());
        assertEquals(server.baseUrl() + "/x?steal=1", opened.getDataString());
        assertTrue(client.shouldOverrideUrlLoading(view, request(server.baseUrl() + "/app.js", true, "GET")));
        assertTrue(client.shouldOverrideUrlLoading(view, request("https://elsewhere.example/", true, "GET")));
        // The app's own page stays, with whatever it carries after the path.
        assertFalse(client.shouldOverrideUrlLoading(view, request(server.baseUrl() + "/?conv=abc", true, "GET")));
        assertFalse(client.shouldOverrideUrlLoading(view, request(server.baseUrl() + "/#link=K7Q4M2XD9PRT", true, "GET")));

        // Anything else that tries to load in the app anyway (a form, a redirect) shows an empty page with no script,
        // and while it shows the bridge answers nothing.
        android.webkit.WebResourceResponse r = client.shouldInterceptRequest(view, request(server.baseUrl() + "/x", true, "POST"));
        assertNotNull(r);
        assertFalse(new String(readAll(r.getData()), java.nio.charset.StandardCharsets.UTF_8).contains("<script"));
        assertEquals("", link.invoke(bridge));
        assertEquals("", bridge.getClass().getMethod("key").invoke(bridge));
        assertEquals(false, bridge.getClass().getMethod("isApp").invoke(bridge));
        bridge.getClass().getMethod("setLink", String.class).invoke(bridge, "");
        assertTrue(l.sameAs(prefs.link()));   // it can't unlink the phone either
        // Back on the app's own page, it answers again; the page's files and the inbox's data load as before.
        assertNotNull(client.shouldInterceptRequest(view, request(server.baseUrl() + "/", true, "GET")));
        assertEquals(l.forPage(), link.invoke(bridge));
        assertNotNull(client.shouldInterceptRequest(view, request(server.baseUrl() + "/app.js", false, "GET")));
        assertNull(client.shouldInterceptRequest(view, request(server.baseUrl() + "/v2/inbox", false, "GET")));
        assertEquals(l.forPage(), link.invoke(bridge));
    }

    /** The bridge, once the WebView has been handed the app's own page (as a real load does first). */
    private Object pageBridge(WebActivity activity) {
        android.webkit.WebView view = (android.webkit.WebView) ((android.view.ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0);
        ShadowWebView web = shadowOf(view);
        android.webkit.WebResourceResponse page = web.getWebViewClient().shouldInterceptRequest(view, request(server.baseUrl() + "/", true, "GET"));
        assertNotNull(page);
        return web.getJavascriptInterface("TextsNative");
    }

    private static android.webkit.WebResourceRequest request(String url, boolean mainFrame, String method) {
        android.net.Uri u = android.net.Uri.parse(url);
        return new android.webkit.WebResourceRequest() {
            @Override public android.net.Uri getUrl() { return u; }
            @Override public boolean isForMainFrame() { return mainFrame; }
            @Override public boolean isRedirect() { return false; }
            @Override public boolean hasGesture() { return true; }
            @Override public String getMethod() { return method; }
            @Override public java.util.Map<String, String> getRequestHeaders() { return new java.util.HashMap<>(); }
        };
    }

    private static byte[] readAll(java.io.InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static void waitFor(java.util.function.BooleanSupplier c) throws InterruptedException {
        long until = System.currentTimeMillis() + 10_000;
        while (!c.getAsBoolean() && System.currentTimeMillis() < until) {
            Thread.sleep(20);
            org.robolectric.shadows.ShadowLooper.idleMainLooper();
        }
        assertTrue("timed out", c.getAsBoolean());
    }

    @Test
    public void aLinkCopiedFromAnotherPhoneIsDroppedWhereverItsRead() throws Exception {
        try {
            MainActivity.androidIdForTests = "5555666677778888";
            Link l = privateInbox();   // linked on the old phone...
            assertNotNull(prefs.link());
            MainActivity.androidIdForTests = "1111222233334444";   // ...and its data copied to this one
            assertNull(prefs.link());
            assertNull(new Prefs(app).link());   // the background sync reads it the same way
            assertTrue(prefs.takeLinkCopied());
            assertFalse(prefs.takeLinkCopied());
            // linked on this phone, it stays
            privateInbox();
            assertNotNull(new Prefs(app).link());
            // a link an older build stored (no record of the phone) counts as this phone's, and as linked: a later
            // reinstall doesn't put it back into an inbox it left
            app.getSharedPreferences("texts", android.content.Context.MODE_PRIVATE).edit().clear().putString("link2", l.toJson()).commit();
            Prefs fresh = new Prefs(app);
            assertNotNull(fresh.link());
            assertTrue(fresh.everLinked());
        } finally {
            MainActivity.androidIdForTests = null;
        }
    }

    @Test
    public void sameVectorsAsTheWebPageAndRelay() throws Exception {
        String json;
        try (java.io.InputStream in = TextsTest.class.getResourceAsStream("/vectors.json")) {
            json = new String(readAll(in), java.nio.charset.StandardCharsets.UTF_8);
        }
        JSONObject v = new JSONObject(json);
        byte[] k = hex(v.getString("key"));
        assertEquals(v.getString("enc"), Crypto.hex(Crypto.hkdf(k, "textrelay v2 enc")));
        JSONObject m = v.getJSONObject("message");
        assertEquals(m.getString("plain"), new String(Crypto.open(hex(v.getString("enc")), m.getString("sealed"), m.getString("aad")), java.nio.charset.StandardCharsets.UTF_8));
        JSONObject a = v.getJSONObject("aads");
        assertEquals(a.getString("messageIn"), Crypto.aadMessage(a.getString("conv"), "in"));
        assertEquals(a.getString("messageOut"), Crypto.aadMessage(a.getString("conv"), "out"));
        assertEquals(a.getString("name"), Crypto.aadName(a.getString("conv")));
        assertEquals(a.getString("fileOriginal"), Crypto.aadFile(a.getString("conv"), a.getLong("ts"), a.getString("fp"), "original"));
        assertEquals(a.getString("filePreviewShortTs"), Crypto.aadFile(a.getString("conv"), a.getLong("shortTs"), a.getString("fp"), "preview"));
        assertEquals(a.getString("merge"), Crypto.aadMerge(a.getString("conv")));
        Crypto.LinkSecrets s = Crypto.linkSecrets(v.getJSONObject("link").getString("code"));
        assertEquals(v.getJSONObject("link").getString("lookup"), s.lookup);
        assertEquals(v.getJSONObject("phone").getString("tag"), Crypto.phoneSecrets(v.getJSONObject("phone").getString("androidId"), "relay").tag);
        // A message as the inbox sends it opens to what the notifications show.
        Link l = new Link("https://inbox.example", "00112233445566778899aabbccddeeff", "tokTexts0123456789abcdefghijklmnopqrstuvwxy", k);
        JSONObject sealed = new JSONObject().put("k", "m:1:a").put("dir", "in").put("conv", a.getString("conv")).put("e", m.getString("sealed"));
        JSONObject open = l.openMessage(sealed);
        assertEquals("Hi 👋 there", open.getString("text"));
        assertEquals("Andy Graham", open.getString("name"));
        assertEquals(640, open.getJSONObject("media").getInt("w"));
        assertEquals(1700000000123L, open.getLong("ts"));   // the time it was sealed with
        // the same message filed under another conversation, or as the other direction, opens as nothing
        assertEquals("New message", l.openMessage(new JSONObject(sealed.toString()).put("conv", "00000000000000000000000000000000")).getString("text"));
        assertEquals("New message", l.openMessage(new JSONObject(sealed.toString()).put("dir", "out")).getString("text"));
        // only its own files: under its stem, in this inbox, in its conversation
        String stem = "1700000000123-" + a.getString("fp");
        JSONObject withFile = new JSONObject(sealed.toString()).put("media", new JSONObject()
                .put("path", "/media/v2/00112233445566778899aabbccddeeff/" + a.getString("conv") + "/" + stem + "-o.enc").put("mime", "video/mp4").put("bytes", 9));
        JSONObject media = l.openMessage(withFile).getJSONObject("media");
        assertEquals("image/jpeg", media.getString("mime"));   // the sealed type and size, not the server's
        assertEquals(51234, media.getInt("bytes"));
        assertTrue(media.has("path"));
        for (String other : new String[] {
                "/media/v2/00112233445566778899aabbccddeeff/" + a.getString("conv") + "/1700000000124-" + a.getString("fp") + "-o.enc",
                "/media/v2/ffffffffffffffffffffffffffffffff/" + a.getString("conv") + "/" + stem + "-o.enc",
                "/media/v2/00112233445566778899aabbccddeeff/00000000000000000000000000000000/" + stem + "-o.enc",
                "/media/v2/00112233445566778899aabbccddeeff/" + a.getString("conv") + "/" + stem + "-p.enc" }) {
            JSONObject m2 = l.openMessage(new JSONObject(sealed.toString()).put("media", new JSONObject().put("path", other))).getJSONObject("media");
            assertFalse(other, m2.has("path"));
            assertTrue(other, m2.optBoolean("blocked"));
        }
    }

    private static byte[] hex(String h) {
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    @Test
    public void theBuiltInScannerHandsTheCodeToThePage() throws Exception {
        WebActivity activity = Robolectric.buildActivity(WebActivity.class).setup().get();
        android.webkit.WebView view = (android.webkit.WebView) ((android.view.ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0);
        ShadowWebView web = shadowOf(view);
        Object bridge = pageBridge(activity);
        bridge.getClass().getMethod("scanQr").invoke(bridge);
        org.robolectric.shadows.ShadowActivity.IntentForResult scan = shadowOf(activity).getNextStartedActivityForResult();
        assertEquals(ScanActivity.class.getName(), scan.intent.getComponent().getClassName());
        shadowOf(activity).receiveResult(scan.intent, android.app.Activity.RESULT_OK,
                new Intent().putExtra(ScanActivity.EXTRA_TEXT, "TEXTRELAY:LINK:K7Q4M2XD9PRT"));
        assertEquals("window.textsScanned && textsScanned(\"TEXTRELAY:LINK:K7Q4M2XD9PRT\")", web.getLastEvaluatedJavascript());

        bridge.getClass().getMethod("scanQr").invoke(bridge);
        scan = shadowOf(activity).getNextStartedActivityForResult();
        shadowOf(activity).receiveResult(scan.intent, ScanActivity.RESULT_TYPE, null);
        assertEquals("window.textsScanClosed && textsScanClosed(true)", web.getLastEvaluatedJavascript());
    }

    @Test
    public void theScannerReadsRelayCodesAndOnlyThose() throws Exception {
        assertTrue(QrDecoder.ours("TEXTRELAY2:relay--bradwhite.apps.hedyassist.com/K7Q4M2XD9PRT"));   // what Relay shows
        assertTrue(QrDecoder.ours(Crypto.linkQr("http://127.0.0.1:8790", "K7Q4M2XD9PRT")));
        assertFalse(QrDecoder.ours("TEXTRELAY2:relay.example/K7Q4"));
        assertTrue(QrDecoder.ours("https://relay--bradwhite.apps.hedyassist.com/#link=K7Q4M2XD9PRT"));   // a link to the page
        assertTrue(QrDecoder.ours("https://relay--bradwhite.apps.hedyassist.com/#link=K7Q4-M2XD-9PRT"));
        assertTrue(QrDecoder.ours("TEXTRELAY:LINK:K7Q4M2XD9PRT"));   // an older Relay's: the page says to update it
        assertTrue(QrDecoder.ours(" textrelay:relay:k7q4m2xd9prt "));
        assertFalse(QrDecoder.ours("https://example.com/"));
        assertFalse(QrDecoder.ours("https://example.com/#link=K7Q4"));
        assertFalse(QrDecoder.ours("http://example.com/#link=K7Q4M2XD9PRT"));
        assertFalse(QrDecoder.ours("TEXTRELAY:LINK:K7Q4"));
        assertFalse(QrDecoder.ours("TEXTRELAY:OTHER:K7Q4M2XD9PRT"));

        // A camera frame's brightness values, with the code off to one side and rows padded like real camera buffers.
        String text = "TEXTRELAY:RELAY:MMMMNNNNPPPP";
        com.google.zxing.common.BitMatrix qr = new com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, 300, 300);
        int w = 1280, h = 720, stride = 1344;
        byte[] frame = new byte[stride * h];
        java.util.Arrays.fill(frame, (byte) 200);
        for (int y = 0; y < qr.getHeight(); y++) {
            for (int x = 0; x < qr.getWidth(); x++) {
                frame[(y + 300) * stride + x + 700] = qr.get(x, y) ? (byte) 20 : (byte) 230;
            }
        }
        assertEquals(text, QrDecoder.decode(frame, stride, w, h));
        assertNull(QrDecoder.decode(new byte[stride * h], stride, w, h));
    }

    @Test
    public void unpairedScreenDoesNotStartBackgroundChecks() {
        prefs.setKey("");
        Robolectric.buildActivity(MainActivity.class).setup();
        assertNull(shadowOf(app).getNextStartedService());
    }

    private static JSONObject message(String conv, String sender, String name, boolean group, String text) throws Exception {
        return new JSONObject().put("k", "m:" + System.nanoTime() + ":aa").put("conv", conv).put("dir", "in").put("sender", sender)
                .put("name", name).put("group", group).put("text", text).put("ts", System.currentTimeMillis());
    }
}
