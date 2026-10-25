package com.textrelay.relay;

import android.app.Activity;
import android.app.Application;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.PowerManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ScrollView;

import org.junit.After;
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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.robolectric.Shadows.shadowOf;

/**
 * Relay's screens drawn the size of the relay phone's screen (4", 1080 × 1200), into app/build/screens: to look at,
 * and to check that each of them draws in both themes.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w360dp-h400dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ScreensTest {
    private FakeHedy server;
    private Application app;
    private Relay relay;

    @Before
    public void setUp() throws Exception {
        server = new FakeHedy();
        Relay.reset();
        app = RuntimeEnvironment.getApplication();
        relay = Relay.get(app);
        relay.prefs.setServer(server.baseUrl(), "");
        android.provider.Settings.Secure.putString(app.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID, "5f3a9c2e7b1d4e60");
    }

    @After
    public void tearDown() throws Exception {
        Relay.reset();
        server.close();
        RelayTest.settle();
    }

    @Test
    public void light() throws Exception {
        walk("light");
    }

    @Test
    @Config(qualifiers = "w360dp-h400dp-night-xxhdpi")
    public void dark() throws Exception {
        walk("dark");
    }

    private void walk(String theme) throws Exception {
        org.robolectric.util.ReflectionHelpers.callStaticMethod(android.animation.ValueAnimator.class, "setDurationScale",
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(float.class, 0f));

        // A new phone: its code, for Texts to scan.
        ActivityController<MainActivity> first = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = first.get();
        View content = a.findViewById(android.R.id.content);
        waitFor("the code", () -> {
            ImageView qr = RelayTest.findQr(content, "QR code for linking this phone");
            return qr != null && qr.getDrawable() != null;
        });
        shot(a, theme + "-1-new");
        full(a, theme + "-1b-new-all");
        first.pause().stop().destroy();

        // Linked, but Android hasn't given it what it needs yet.
        relay.prefs.setLink(new Link(server.baseUrl(), FakeHedy.TID, RelayTest.TOKEN, RelayTest.INBOX_KEY));
        relay.prefs.setWrapTag("done");
        server.link = relay.prefs.link();
        relay.prefs.setVerified(true);
        a = Robolectric.buildActivity(MainActivity.class).setup().get();
        shot(a, theme + "-2-paused");
        full(a, theme + "-2b-paused-all");

        // Relaying.
        shadowOf(app.getSystemService(NotificationManager.class)).setNotificationListenerAccessGranted(new ComponentName(app, RelayListenerService.class), true);
        shadowOf(app.getSystemService(PowerManager.class)).setIgnoringBatteryOptimizations(app.getPackageName(), true);
        relay.listening = true;
        relay.lastRelayed = System.currentTimeMillis() - 2 * 60_000L;
        relay.lastSync = System.currentTimeMillis() - 20_000L;
        a.refresh();
        shot(a, theme + "-3-relaying");
        View card = (View) RelayTest.findText(a.findViewById(android.R.id.content), "Relaying").getParent();
        card.performClick();
        shot(a, theme + "-4-details");
        card.performClick();

        RelayTest.click(a, "Link a device");
        MainActivity linking = a;
        waitFor("the invite", () -> {
            ImageView qr = RelayTest.findQr(linking.findViewById(android.R.id.content), "QR code for linking a phone");
            return qr != null && RelayTest.shown(qr) && qr.getDrawable() != null;
        });
        full(a, theme + "-5-link-a-device");

        MoreActivity more = Robolectric.buildActivity(MoreActivity.class).setup().get();
        shot(more, theme + "-6-more");
        full(more, theme + "-6b-more-all");

        WizardActivity w = Robolectric.buildActivity(WizardActivity.class).setup().get();
        for (int page = 0; page < WizardActivity.PAGES; page++) {
            w.go(page);
            shot(w, theme + "-7-guide-" + (page + 1));
        }
        w.go(3);
        View codes = RelayTest.findText(w.findViewById(android.R.id.content), "Use your carrier's code");
        assertNotNull(codes);
        codes.performClick();
        full(w, theme + "-7b-guide-4-codes");

        // Connect through Tor, with Tor not running: the card says so, and More says what to do.
        java.net.ServerSocket gone = new java.net.ServerSocket(0);
        Tor.socksPort = gone.getLocalPort();
        gone.close();
        try {
            relay.prefs.setTor(true);
            relay.torDown = true;
            a.refresh();
            shot(a, theme + "-8-tor-down");
            MoreActivity torMore = Robolectric.buildActivity(MoreActivity.class).setup().resume().get();
            waitFor("the check", () -> RelayTest.allText(torMore.findViewById(android.R.id.content)).contains("Get Orbot"));
            full(torMore, theme + "-8b-more-tor");
        } finally {
            Tor.socksPort = 9050;
            relay.torDown = false;
        }
    }

    private static void waitFor(String what, BooleanSupplier ok) throws InterruptedException {
        long until = System.currentTimeMillis() + 15_000;
        while (!ok.getAsBoolean()) {
            if (System.currentTimeMillis() > until) fail("timed out waiting for " + what);
            RelayTest.tick();
            Thread.sleep(25);
        }
    }

    private static void settle() throws InterruptedException {
        for (int i = 0; i < 6; i++) {
            ShadowLooper.idleMainLooper();
            Thread.sleep(30);
        }
    }

    /** The screen as it is, bars and all. */
    private static void shot(Activity a, String name) throws Exception {
        settle();
        View root = a.getWindow().getDecorView();
        Bitmap b = Bitmap.createBitmap(Math.max(1, root.getWidth()), Math.max(1, root.getHeight()), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(b));
        save(b, name);
    }

    /** All of a scrolling screen, top to bottom. */
    private static void full(Activity a, String name) throws Exception {
        settle();
        ScrollView scroll = firstScroll(a.findViewById(android.R.id.content));
        assertNotNull(scroll);
        View inner = scroll.getChildAt(0);
        assertTrue(inner.getHeight() > 0);
        Bitmap b = Bitmap.createBitmap(inner.getWidth(), inner.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(new Ui(a).bg);
        inner.draw(c);
        save(b, name);
    }

    private static ScrollView firstScroll(View v) {
        if (v instanceof ScrollView) return (ScrollView) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                ScrollView s = firstScroll(g.getChildAt(i));
                if (s != null) return s;
            }
        }
        return null;
    }

    private static void save(Bitmap b, String name) throws Exception {
        File dir = new File("build/screens");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, name + ".png"))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }
}
