package com.textrelay.texts;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.widget.ListView;

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
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Paigr's screens drawn the size of a small keypad phone's screen (2.8", 480 × 640), against the real inbox server, into
 * app/build/screens: to look at, and to check that every screen draws with real data.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w320dp-h427dp-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ScreensTest {
    private RealInbox server;
    private Application app;

    @Before
    public void setUp() throws Exception {
        Assume.assumeTrue("needs Node and relay-hedy", RealInbox.available());
        app = RuntimeEnvironment.getApplication();
        Inbox.resetForTests();
        MainActivity.androidIdForTests = "5f3a9c2e7b1d4e60";
        server = new RealInbox("demo");
        Prefs p = new Prefs(app);
        p.setServer(server.origin);
        p.setLink(server.texts());
    }

    @After
    public void tearDown() {
        Inbox.resetForTests();
        MainActivity.androidIdForTests = null;
        if (server != null) server.close();
    }

    static void waitFor(String what, BooleanSupplier ok) throws InterruptedException {
        long until = System.currentTimeMillis() + 20_000;
        while (!ok.getAsBoolean()) {
            if (System.currentTimeMillis() > until) fail("timed out waiting for " + what);
            ShadowLooper.idleMainLooper();
            Thread.sleep(25);
        }
        ShadowLooper.idleMainLooper();
    }

    static void settle() throws InterruptedException {
        for (int i = 0; i < 12; i++) {
            ShadowLooper.idleMainLooper();
            Thread.sleep(40);
        }
        ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS);
    }

    /** As with Android's "remove animations": screens appear at once. */
    static void noAnimations() {
        org.robolectric.util.ReflectionHelpers.callStaticMethod(android.animation.ValueAnimator.class, "setDurationScale",
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(float.class, 0f));
    }

    static void shot(android.app.Activity a, String name) throws Exception {
        View root = a.getWindow().getDecorView();
        settle();
        Bitmap b = Bitmap.createBitmap(Math.max(1, root.getWidth()), Math.max(1, root.getHeight()), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(b));
        File dir = new File("build/screens");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, name + ".png"))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }

    /** A text from the other side, filed the way the relay phone files one (as ConversationFeaturesTest does). */
    static void arrives(RealInbox s, Link l, String conv, String text) throws Exception {
        ConversationFeaturesTest.arrivesForTests(s, l, conv, "Maya Chen", "Maya Chen", text);
    }

    private static int find(ListView list, String name) {
        for (int i = 0; i < list.getAdapter().getCount(); i++) {
            Object o = list.getAdapter().getItem(i);
            if (o instanceof Model.Conv && name.equals(((Model.Conv) o).name)) return i;
        }
        return -1;
    }

    private void walk(String theme) throws Exception {
        noAnimations();
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = c.get();
        waitFor("the list", () -> a.list != null && a.list.list.getAdapter().getCount() >= 9);
        waitFor("pictures fetched ahead", () -> a.inbox.msgs.size() >= 6);
        shot(a, theme + "-1-inbox");
        // a keypad's arrows: a highlight moves through the list
        a.list.list.requestFocus();
        for (int code : new int[] { android.view.KeyEvent.KEYCODE_DPAD_DOWN, android.view.KeyEvent.KEYCODE_DPAD_DOWN }) {
            a.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code));
            a.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code));
        }
        shot(a, theme + "-1b-keys");
        // the relay phone stopped checking in
        long seen = a.inbox.relay.lastSeen;
        a.inbox.relay.lastSeen = System.currentTimeMillis() - 25 * 60_000L;
        a.changed(Inbox.RELAY, false);
        shot(a, theme + "-1c-offline");
        a.inbox.relay.lastSeen = seen;
        a.changed(Inbox.RELAY, false);

        a.list.list.setSelection(0);
        settle();
        int maya = find(a.list.list, "Maya Chen");
        assertTrue(maya >= 0);
        a.list.list.performItemClick(a.list.list.getChildAt(maya - a.list.list.getFirstVisiblePosition()), maya, 0);
        waitFor("Maya's thread", () -> a.thread != null && a.thread.itemsForTests().size() >= 8);
        Thread.sleep(800);   // the picture
        shot(a, theme + "-2-thread");
        a.thread.list.setSelection(0);
        Thread.sleep(600);
        shot(a, theme + "-2b-thread-top");
        Model.Msg picture = null;
        for (Model.Msg m : a.inbox.thread(a.thread.conv)) if (m.media != null) picture = m;
        a.openViewer(picture);
        Thread.sleep(1200);
        shot(a, theme + "-2c-viewer");
        a.onBackPressed();
        settle();

        a.thread.input.setText("See you there!");
        shot(a, theme + "-3-typing");
        // what arrives as a reaction and as a quoted reply is drawn as what it is
        Link l = new Prefs(app).link();
        String maya2 = a.thread.conv;
        arrives(server, l, maya2, "Loved “On my way at 6:30”");
        arrives(server, l, maya2, "> On my way at 6:30\nPerfect, see you!");
        NativeAppTest.waitFor("the reaction and the quote", () -> {   // time passes: the next sync brings them
            boolean chip = false, quote = false;
            for (ThreadScreen.Item it : a.thread.itemsForTests()) {
                if (it.chips != null) chip = true;
                if (it.quote != null) quote = true;
            }
            return chip && quote;
        });
        a.thread.input.setText("");
        Thread.sleep(300);
        shot(a, theme + "-3b-reactions");
        ThreadScreen.Item doors = null;
        for (ThreadScreen.Item it : a.thread.itemsForTests()) if (it.m != null && "Doors open at 7".equals(it.m.text)) doors = it;
        // replying to one message, with a picture attached, and the emoji panel
        a.thread.replyTo(doors.m);
        byte[] photo = ConversationFeaturesTest.pngForTests(640, 480);
        android.net.Uri uri = android.net.Uri.parse("content://com.example.pick/photos/market.png");
        org.robolectric.Shadows.shadowOf(a.getContentResolver()).registerInputStreamSupplier(uri, () -> new java.io.ByteArrayInputStream(photo));
        a.thread.attach(uri);
        a.thread.input.setText("On it");
        Thread.sleep(500);
        shot(a, theme + "-3d-reply-attach");
        a.thread.attach(null);
        a.thread.replyTo(null);
        NativeAppTest.find(a.thread.root, "Emoji").performClick();
        settle();
        shot(a, theme + "-3e-emoji");
        a.onBackPressed();   // closes the panel
        settle();
        a.onBackPressed();
        settle();

        int crew = find(a.list.list, "Climbing crew");
        a.openConversation(((Model.Conv) a.list.list.getAdapter().getItem(crew)).conv);
        waitFor("the group", () -> a.thread.itemsForTests().size() >= 4);
        shot(a, theme + "-4-group");
        a.onBackPressed();
        settle();

        int lena = find(a.list.list, "Lena");
        a.openConversation(((Model.Conv) a.list.list.getAdapter().getItem(lena)).conv);
        waitFor("Lena's thread", () -> a.thread.itemsForTests().size() >= 3);
        shot(a, theme + "-5-failed");
        a.onBackPressed();
        settle();

        a.openConversation(((Model.Conv) a.list.list.getAdapter().getItem(find(a.list.list, "Maya Chen"))).conv);
        waitFor("Maya again", () -> a.thread.itemsForTests().size() >= 8);
        a.openDetails();
        waitFor("the details", () -> a.details != null && a.inbox.complete.contains(a.inbox.current));
        Thread.sleep(600);
        shot(a, theme + "-6-details");
        a.onBackPressed();
        a.onBackPressed();
        settle();

        a.openSettings();
        Thread.sleep(800);
        settle();
        shot(a, theme + "-7-settings");
        a.onBackPressed();
        settle();

        a.list.startSearch('c');
        shot(a, theme + "-8-search");
        c.pause().stop().destroy();
    }

    @Test
    public void light() throws Exception {
        walk("light");
    }

    @Test
    @Config(qualifiers = "+night")
    public void dark() throws Exception {
        walk("dark");
    }

    @Test
    @Config(qualifiers = "+night")
    public void connect() throws Exception {
        new Prefs(app).setLink(null);
        noAnimations();
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        settle();
        shot(a, "dark-0-connect");
    }

    /** Connect through Tor, with Tor not running: what the list and Settings say, light and dark. */
    private void tor(String theme) throws Exception {
        noAnimations();
        java.net.ServerSocket gone = new java.net.ServerSocket(0);
        Tor.socksPort = gone.getLocalPort();
        gone.close();
        new Prefs(app).setTor(true);
        try {
            MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
            waitFor("the banner", () -> a.inbox.torDown);
            shot(a, theme + "-9-tor-down");
            a.openSettings();
            settle();
            android.widget.TextView label = null;
            for (View v : all(a.settings.root)) {
                if (v instanceof android.widget.TextView && "Connect through Tor".contentEquals(((android.widget.TextView) v).getText())) label = (android.widget.TextView) v;
            }
            assertTrue(label != null);
            View v = label;
            int y = 0;
            while (!(v.getParent() instanceof android.widget.ScrollView)) {
                y += v.getTop();
                v = (View) v.getParent();
            }
            ((android.widget.ScrollView) v.getParent()).scrollTo(0, Math.max(0, y - a.ui.dp(120)));
            Thread.sleep(400);
            shot(a, theme + "-9b-tor-settings");
        } finally {
            Tor.socksPort = 9050;
        }
    }

    private static java.util.List<View> all(View v) {
        java.util.List<View> out = new java.util.ArrayList<>();
        out.add(v);
        if (v instanceof android.view.ViewGroup) {
            for (int i = 0; i < ((android.view.ViewGroup) v).getChildCount(); i++) out.addAll(all(((android.view.ViewGroup) v).getChildAt(i)));
        }
        return out;
    }

    @Test
    public void torLight() throws Exception {
        tor("light");
    }

    @Test
    @Config(qualifiers = "+night")
    public void torDark() throws Exception {
        tor("dark");
    }

    @Test
    public void connectLight() throws Exception {
        new Prefs(app).setLink(null);
        noAnimations();
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        settle();
        a.connect.typeCode();
        shot(a, "light-0-connect");
    }
}
