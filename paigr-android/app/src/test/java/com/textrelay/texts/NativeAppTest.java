package com.textrelay.texts;

import android.app.Application;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The app's own screens against the real inbox server (functions/inbox2.js, run by relay-hedy's test/serve.js), with a
 * stand-in relay phone: linking with one scan, texts both ways, the list from disk while the inbox is away, a phone
 * taken out of its inbox, keypad keys, and everything e2e-dishonest.js checks the web page against.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w320dp-h427dp-hdpi")
public class NativeAppTest {
    private final List<RealInbox> servers = new ArrayList<>();
    private Application app;
    Prefs prefs;

    /** Which inbox server the app talks to: the hosted platform's stand-in here, the self-hosted one in a subclass. */
    protected String serverKind() {
        return RealInbox.STAND_IN;
    }

    @Before
    public void setUp() {
        Assume.assumeTrue("needs Node and relay-hedy", RealInbox.available(serverKind()));
        app = RuntimeEnvironment.getApplication();
        prefs = new Prefs(app);
        Inbox.resetForTests();
        MainActivity.androidIdForTests = "5f3a9c2e7b1d4e60";
        ScreensTest.noAnimations();
    }

    @After
    public void tearDown() {
        Inbox.resetForTests();
        MainActivity.androidIdForTests = null;
        for (RealInbox s : servers) s.close();
    }

    RealInbox start(String scenario) throws Exception {
        RealInbox s = new RealInbox(scenario, serverKind());
        servers.add(s);
        prefs.setServer(s.origin);
        Link l = s.texts();
        if (l != null) prefs.setLink(l);
        return s;
    }

    /** Waits as time passes on the phone (the app's five-second checks run). */
    static void waitFor(String what, BooleanSupplier ok) throws InterruptedException {
        long until = System.currentTimeMillis() + 30_000;
        while (!ok.getAsBoolean()) {
            if (System.currentTimeMillis() > until) fail("timed out waiting for " + what);
            ShadowLooper.idleMainLooper(250, TimeUnit.MILLISECONDS);
            Thread.sleep(25);
        }
        ShadowLooper.idleMainLooper();
    }

    private static Model.Conv named(MainActivity a, String name) {
        for (Model.Conv c : a.inbox.convs.values()) if (name.equals(c.name)) return c;
        return null;
    }

    private static Model.Msg msg(MainActivity a, String conv, String k) {
        for (Model.Msg m : a.inbox.thread(conv)) if (m.k.equals(k)) return m;
        return null;
    }

    static View find(View v, String description) {
        if (description.contentEquals(v.getContentDescription() == null ? "" : v.getContentDescription())) return v;
        if (v instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
                View f = find(((ViewGroup) v).getChildAt(i), description);
                if (f != null) return f;
            }
        }
        return null;
    }

    static String allText(View v) {
        StringBuilder b = new StringBuilder();
        if (v instanceof android.widget.TextView && v.isShown()) b.append(((android.widget.TextView) v).getText()).append('\n');
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) b.append(allText(((ViewGroup) v).getChildAt(i)));
        return b.toString();
    }

    // ------------------------------------------------------------------ linking

    @Test
    public void twoNewPhonesLinkWithOneScanAndNothingPlainReachesTheServer() throws Exception {
        RealInbox s = start("ask");
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = c.get();
        assertSame(a.connect, a.top());
        a.link(s.link.getString("qr"), "link");   // what the scanner read off Relay's screen
        waitFor("the private inbox", () -> prefs.link() != null && a.top() == a.list);
        Link l = prefs.link();

        // The relay phone gets this inbox and its key, sealed with the code's box key; the server only had the lookup.
        Crypto.LinkSecrets secrets = Crypto.linkSecrets(s.link.getString("code"));
        JSONObject w = s.call("POST", "/v2/link/wait", "", new JSONObject().put("lookup", secrets.lookup).put("token", s.link.getString("relayToken")));
        assertEquals(200, w.getInt("_status"));
        JSONObject box = Crypto.openJson(secrets.boxKey, w.getString("answer"), Crypto.AAD_BOX);
        assertEquals(l.tid, box.getString("tid"));
        assertEquals(l.keyB64(), box.getString("key"));

        // This phone named itself, sealed for its own id: the device list opens it, and the server never had it plain.
        String name = Opener.plainName(Inbox.deviceLabel());
        waitFor("the sealed name", () -> {
            try {
                JSONArray items = s.call("GET", "/v2/devices", l.token, null).getJSONArray("items");
                for (int i = 0; i < items.length(); i++) if (items.getJSONObject(i).optBoolean("you") && !items.getJSONObject(i).optString("sn").isEmpty()) return true;
            } catch (Exception ignored) {
                // not yet
            }
            return false;
        });
        JSONArray items = s.call("GET", "/v2/devices", l.token, null).getJSONArray("items");
        for (int i = 0; i < items.length(); i++) {
            JSONObject d = items.getJSONObject(i);
            if (!d.optBoolean("you")) continue;
            assertEquals(Opener.deviceId(l.token), d.getString("id"));
            assertEquals(name, l.open(d.getString("sn"), "tr2:dev:" + d.getString("id")).getString("name"));
        }
        if (name.length() > 3) assertFalse(s.dump().toString().contains(name));
        // ...and filed its wrap, so a reinstall gets back in without a code.
        waitFor("the wrap", () -> !prefs.wrapTag().isEmpty());
        c.pause().stop().destroy();
    }

    @Test
    public void aCodeThatIsntRelaysSaysSoInTheAppsOwnWords() throws Exception {
        start("ask");
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        a.link("https://example.com/", "link");
        ShadowLooper.idleMainLooper();
        assertTrue(allText(a.connect.root).contains("That isn't a code from Paigr Relay"));
        a.link("TEXTRELAY:RELAY:K7Q4M2XD9PRT", "link");
        assertTrue(allText(a.connect.root).contains("from an older Paigr Relay"));
        // A code for another inbox server (one you run yourself, say): a phone not in an inbox yet asks first.
        a.link("TEXTRELAY2:elsewhere.example/K7Q4M2XD9PRT", "link");
        ShadowLooper.idleMainLooper();
        android.app.Dialog asked = org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertNotNull(asked);
        String question = allText(asked.getWindow().getDecorView());
        assertTrue(question, question.contains("Use this inbox server?") && question.contains("elsewhere.example"));
        asked.dismiss();   // Cancel: nothing happens
        ShadowLooper.idleMainLooper();
        assertFalse(allText(a.connect.root).contains("Linking"));
        // A good code that nobody asked with: the inbox's answer, in words this app knows.
        a.link("K7Q4-M2XD-9PRT", "link");
        waitFor("the answer", () -> allText(a.connect.root).contains("expired") || allText(a.connect.root).contains("inbox"));
        assertNull(prefs.link());
    }

    @Test
    public void aPhoneInAnInboxTurnsDownACodeForAnotherServer() throws Exception {
        start("demo");
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        a.link("TEXTRELAY2:elsewhere.example/K7Q4M2XD9PRT", "approve");
        ShadowLooper.idleMainLooper();
        assertEquals("That code is for a different inbox server (elsewhere.example).", String.valueOf(a.snack.text()));
        assertNull(org.robolectric.shadows.ShadowDialog.getLatestDialog());
    }

    // ------------------------------------------------------------------ texts both ways

    @Test
    public void textsComeInAndARepliesGoesOutSealedForItsConversationAlone() throws Exception {
        RealInbox s = start("demo");
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = c.get();
        waitFor("the list", () -> a.inbox.convs.size() >= 9);
        Model.Conv maya = named(a, "Maya Chen");
        assertNotNull(maya);
        assertEquals(2, maya.unread);
        assertEquals("On my way at 6:30", maya.lastText);
        Model.Conv crew = named(a, "Climbing crew");
        assertTrue(crew.group);
        assertEquals("Jordan", crew.lastSender);

        a.openConversation(maya.conv);
        // There at once, with nothing in between for a small phone to stutter through: the list is already gone.
        assertEquals(View.VISIBLE, a.thread.root.getVisibility());
        assertEquals(View.GONE, a.list.root.getVisibility());
        waitFor("Maya's thread", () -> a.inbox.thread(maya.conv).size() >= 7 && a.inbox.cursors.containsKey(maya.conv));
        List<Model.Msg> t = a.inbox.thread(maya.conv);
        assertEquals("Look what I found at the market", t.get(0).text);
        Model.Msg pic = t.get(1);
        assertEquals("image/png", pic.media.mime);
        assertNotNull(pic.media.preview);
        assertFalse(pic.media.blocked);
        for (Model.Msg m : t) assertFalse(m.k, m.unchecked);
        waitFor("read", () -> named(a, "Maya Chen").unread == 0);
        String screen = allText(a.thread.root);
        assertTrue(screen, screen.contains("Doors open at 7"));
        // Messages that arrived after it opened are drawn where they belong, not sliding or fading in.
        android.widget.ListView rows = a.thread.list;
        for (int i = 0; i < rows.getChildCount(); i++) {
            View row = rows.getChildAt(i);
            assertEquals(1f, row.getAlpha(), 0f);
            assertEquals(0f, row.getTranslationY(), 0f);
        }
        assertTrue(screen, screen.contains("Maya Chen"));

        // A reply: sealed twice, for every inbox device and for the relay phone to act on, both for this conversation.
        a.thread.input.setText("See you there!");
        find(a.thread.root, "Send").performClick();
        ShadowLooper.idleMainLooper();
        assertEquals("", a.thread.input.getText().toString());
        String relay = s.link.getString("relayToken");
        JSONObject[] got = new JSONObject[1];
        waitFor("the outbox", () -> {
            try {
                JSONArray items = s.call("GET", "/v2/relay/outbox", relay, null).optJSONArray("items");
                if (items != null && items.length() > 0) got[0] = items.getJSONObject(0);
            } catch (Exception ignored) {
                // not yet
            }
            return got[0] != null;
        });
        Link l = prefs.link();
        assertEquals(maya.conv, got[0].getString("conv"));
        JSONObject o = l.open(got[0].getString("o"), Crypto.aadOut(maya.conv));
        assertEquals("See you there!", o.getString("text"));
        assertEquals(22, o.getString("n").length());
        try {
            l.open(got[0].getString("o"), Crypto.aadOut(crew.conv));
            fail("a reply opened as another conversation's");
        } catch (java.security.GeneralSecurityException expected) {
            // bound to Maya's conversation alone
        }
        String id = got[0].getString("id");
        waitFor("the reply shown as sending", () -> {
            for (Model.Msg m : a.inbox.thread(maya.conv)) if (m.k.equals(id)) return true;
            return false;
        });
        // The relay phone sent it as an SMS: the thread says so.
        s.call("POST", "/v2/relay/outbox/result", relay, new JSONObject().put("id", id).put("status", "sent").put("via", "sms"));
        waitFor("sent as SMS", () -> {
            Model.Msg m = msg(a, maya.conv, id);
            return m != null && "sent".equals(m.status) && "sms".equals(m.via);
        });
        waitFor("drawn", () -> allText(a.thread.root).contains("Sent as SMS"));

        // A reply the relay phone couldn't send says why, in this app's words.
        Model.Conv lena = named(a, "Lena");
        a.onBackPressed();
        a.openConversation(lena.conv);
        waitFor("Lena's thread", () -> allText(a.thread.root).contains("Running late"));
        waitFor("the reason", () -> allText(a.thread.root).contains("Turn on SMS fallback in Paigr Relay"));
        c.pause().stop().destroy();
    }

    // ------------------------------------------------------------------ quick to open

    @Test
    public void theListAndRecentConversationsShowAtOnceEvenWhileTheInboxIsAway() throws Exception {
        RealInbox s = start("demo");
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = c.get();
        waitFor("the list", () -> a.inbox.convs.size() >= 9);
        waitFor("conversations fetched ahead", () -> a.inbox.msgs.size() >= Inbox.PREFETCH);
        Model.Conv maya = named(a, "Maya Chen");
        a.inbox.flushDiskForTests();
        c.pause().stop().destroy();
        s.close();   // the inbox is away (no signal, say)
        Inbox.resetForTests();

        ActivityController<MainActivity> c2 = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity b = c2.get();
        waitFor("the list from disk", () -> b.inbox.convs.size() >= 9);
        assertTrue(b.inbox.fromDisk);
        assertEquals(9, b.list.list.getAdapter().getCount());
        b.openConversation(maya.conv);
        waitFor("Maya's thread from disk", () -> b.inbox.thread(maya.conv).size() >= 7);
        assertTrue(allText(b.thread.root).contains("Doors open at 7"));
        c2.pause().stop().destroy();
    }

    // ------------------------------------------------------------------ taken out of the inbox

    @Test
    public void aPhoneTakenOutOfItsInboxAsksForACodeAndForgetsEverything() throws Exception {
        RealInbox s = start("demo");
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = c.get();
        waitFor("the list", () -> a.inbox.convs.size() >= 9);
        assertEquals(200, s.call("POST", "/v2/leave", prefs.link().token, new JSONObject()).getInt("_status"));
        waitFor("the link screen", () -> a.top() == a.connect);
        assertNull(prefs.link());
        assertTrue(a.inbox.convs.isEmpty());
        assertTrue(allText(a.connect.root).contains("isn't linked to your inbox any more"));
        c.pause().stop().destroy();
    }

    // ------------------------------------------------------------------ keypad keys

    private static KeyEvent key(int code, int action, int meta) {
        long now = android.os.SystemClock.uptimeMillis();
        return new KeyEvent(now, now, action, code, 0, meta, 1, 0, 0, android.view.InputDevice.SOURCE_KEYBOARD);
    }

    private static void press(MainActivity a, int code) {
        a.dispatchKeyEvent(key(code, KeyEvent.ACTION_DOWN, 0));
        a.dispatchKeyEvent(key(code, KeyEvent.ACTION_UP, 0));
        ShadowLooper.idleMainLooper();
    }

    @Test
    public void typingSearchesArrowsAndOkOpenAndEnterSends() throws Exception {
        start("demo");
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = c.get();
        waitFor("the list", () -> a.list.list.getAdapter().getCount() >= 9);
        press(a, KeyEvent.KEYCODE_L);
        String shown = allText(a.list.root);
        assertTrue(shown, shown.contains("Lena"));
        assertFalse(shown, shown.contains("Priya Shah"));
        a.onBackPressed();
        ShadowLooper.idleMainLooper();
        assertEquals(9, a.list.list.getAdapter().getCount());

        // The ring's arrows move through the list and OK opens the conversation under the highlight.
        a.list.list.requestFocus();
        a.list.list.setSelection(0);
        press(a, KeyEvent.KEYCODE_DPAD_DOWN);
        int at = a.list.list.getSelectedItemPosition();
        assertTrue("the highlight moved down: " + at, at > 0);
        Model.Conv second = (Model.Conv) a.list.list.getAdapter().getItem(at);
        press(a, KeyEvent.KEYCODE_DPAD_CENTER);
        waitFor("the conversation", () -> a.top() == a.thread && second.conv.equals(a.thread.conv));

        // Typing goes straight into the reply; a keyboard's Enter sends it.
        press(a, KeyEvent.KEYCODE_O);
        press(a, KeyEvent.KEYCODE_K);
        assertEquals("ok", a.thread.input.getText().toString());
        int before = a.inbox.thread(second.conv).size();
        press(a, KeyEvent.KEYCODE_ENTER);
        assertEquals("", a.thread.input.getText().toString());
        assertEquals(before + 1, a.inbox.thread(second.conv).size());
        c.pause().stop().destroy();
    }

    // ------------------------------------------------------------------ a dishonest server

    @Test
    public void whatADishonestServerChangesNeverShowsAsAnyonesWords() throws Exception {
        RealInbox s = start("dishonest");
        JSONObject k = s.link.getJSONObject("keys");
        long t0 = s.link.getLong("t0");
        String andy = s.link.getString("andy"), megan = s.link.getString("megan");
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = c.get();
        waitFor("the list", () -> a.inbox.convs.containsKey(andy) && a.inbox.convs.containsKey(megan));
        a.openConversation(andy);
        waitFor("Andy's thread", () -> a.inbox.cursors.containsKey(andy) && msg(a, andy, k.optString("kT")) != null);
        List<Model.Msg> t = a.inbox.thread(andy);

        // 1. a text someone sent can't be shown as one the user sent
        Model.Msg owe = msg(a, andy, k.getString("kOwe"));
        assertNotNull(owe);
        assertTrue(!owe.out() || owe.unchecked);
        assertFalse(owe.text.contains("I owe you"));
        // 2, 2b. the same sealed message filed again (as it was, or sealed afresh) shows once, at its sealed time
        int seen = 0;
        Model.Msg still = null;
        for (Model.Msg m : t) if ("Are we still on for Friday?".equals(m.text)) {
            seen++;
            still = m;
        }
        assertEquals(1, seen);
        assertEquals(t0 + 20000, still.ts);
        // 8. ...and a picture the server gave it isn't shown with it
        assertNull(still.media);
        // 3. nothing sealed: unchecked, without the picture the server put on it
        Model.Msg fab = msg(a, andy, k.getString("kFab"));
        assertTrue(fab.unchecked);
        assertNull(fab.media);
        // 4. the server's own words never show
        Model.Msg err = msg(a, andy, k.getString("kErr"));
        assertNotNull(err);
        // 5. an unchecked message shows no attachment
        Model.Msg mix = msg(a, andy, k.getString("kMix"));
        assertTrue(mix.unchecked);
        assertNull(mix.media);
        // 6. the sealed type and size win over the server's
        Model.Msg pic = msg(a, andy, k.getString("kPic"));
        assertEquals("image/png", pic.media.mime);
        assertEquals(s.link.getLong("pngBytes"), pic.media.bytes);
        assertNotNull(pic.media.path);
        // 7. a picture can't be moved onto another message of the same conversation
        Model.Msg pic2 = msg(a, andy, k.getString("kPic2"));
        assertTrue(pic2.media.blocked);
        assertNull(pic2.media.path);
        // control: a file path naming another inbox is blocked
        Model.Msg other = msg(a, andy, k.getString("kT"));
        assertTrue(other.media.blocked);
        assertNull(other.media.path);
        waitFor("drawn", () -> allText(a.thread.root).contains("couldn't be checked"));
        String screen = allText(a.thread.root);
        assertFalse(screen, screen.contains("relay-help"));
        assertFalse(screen, screen.contains("I owe you"));
        assertFalse(screen, screen.contains("Video"));
        // the picture that is Andy's own opens, and only it
        Model.Msg[] holder = { pic };
        android.graphics.Bitmap[] b = new android.graphics.Bitmap[1];
        a.inbox.media.picture(holder[0].media.path, 200, got -> b[0] = got);
        waitFor("Andy's picture", () -> b[0] != null);
        boolean[] done = { false };
        android.graphics.Bitmap[] wrong = { null };
        a.inbox.media.picture(pic2.media.path == null ? "/media/v2/" + "f".repeat(32) + "/x/0-y-o.enc" : pic2.media.path, 200, got -> {
            wrong[0] = got;
            done[0] = true;
        });
        waitFor("refused", () -> done[0]);
        assertNull(wrong[0]);

        a.onBackPressed();
        a.openConversation(megan);
        waitFor("Megan's thread", () -> a.inbox.cursors.containsKey(megan) && msg(a, megan, k.optString("kX")) != null);
        // 9. a message filed under another time shows the time it was sealed with
        assertEquals(t0 + 60000, msg(a, megan, k.getString("kMeg")).ts);
        // 9b. a seal written another way never opens: no second copy of the words
        assertTrue(msg(a, megan, k.getString("kSpelled")).unchecked);
        int six = 0;
        for (Model.Msg m : a.inbox.thread(megan)) if (m.text.contains("See you at 6")) six++;
        assertEquals(1, six);
        // control: a merge record moved into another conversation's summary vouches for nothing
        Model.Msg x = msg(a, megan, k.getString("kX"));
        assertTrue(x.unchecked);
        assertFalse(x.text.contains("Words sealed for X"));
        c.pause().stop().destroy();
    }
}
