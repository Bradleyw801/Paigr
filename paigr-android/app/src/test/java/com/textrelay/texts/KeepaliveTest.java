package com.textrelay.texts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlarmManager;
import android.app.Application;
import android.content.Intent;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlarmManager;
import org.robolectric.shadows.ShadowApplication;

/**
 * The listener's insurance: the quarter-hourly alarm Android fires through doze, which starts the service again when
 * it was stopped and otherwise makes it check the inbox at once; gone when alerts are off or the phone leaves its inbox.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class KeepaliveTest {
    private Application app;
    private Prefs prefs;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences("texts", android.content.Context.MODE_PRIVATE).edit().clear().commit();
        prefs = new Prefs(app);
        prefs.setServer("http://127.0.0.1:9");
        byte[] key = new byte[32];
        prefs.setLink(new Link(prefs.server(), "00112233445566778899aabbccddeeff", "tokTexts0123456789abcdefghijklmnopqrstuvwxy", key));
    }

    @After
    public void tearDown() {
        SyncService.stop(app);
    }

    private ShadowAlarmManager alarms() {
        return shadowOf(app.getSystemService(AlarmManager.class));
    }

    @Test
    public void startingTheServiceSetsTheAlarmAndStoppingItClearsIt() {
        assertNull(alarms().peekNextScheduledAlarm());
        SyncService.start(app);
        ShadowAlarmManager.ScheduledAlarm a = alarms().peekNextScheduledAlarm();
        assertNotNull("an alarm", a);
        assertEquals(AlarmManager.RTC_WAKEUP, a.getType());
        assertTrue("allowed while the phone dozes", a.isAllowWhileIdle());
        long inMs = a.getTriggerAtMs() - System.currentTimeMillis();
        assertTrue("about a quarter of an hour away: " + inMs, inMs > 14 * 60_000L && inMs <= 15 * 60_000L);
        SyncService.stop(app);
        assertNull(alarms().peekNextScheduledAlarm());
    }

    @Test
    public void theAlarmStartsAStoppedServiceAndSetsTheNextOne() {
        ShadowApplication sa = shadowOf(app);
        sa.clearStartedServices();
        new Keepalive().onReceive(app, new Intent(Keepalive.ACTION));
        Intent started = sa.getNextStartedService();
        assertNotNull("the service is started again", started);
        assertEquals(SyncService.class.getName(), started.getComponent().getClassName());
        assertNotNull("and the next alarm is set", alarms().peekNextScheduledAlarm());
    }

    @Test
    public void nothingRunsWithAlertsOffOrOutOfAnInbox() {
        prefs.setAlertMode(Prefs.ALERT_OFF);
        ShadowApplication sa = shadowOf(app);
        sa.clearStartedServices();
        new Keepalive().onReceive(app, new Intent(Keepalive.ACTION));
        assertNull(sa.getNextStartedService());
        assertNull(alarms().peekNextScheduledAlarm());
        prefs.setAlertMode(Prefs.ALERT_NORMAL);
        prefs.setLink(null);
        new Keepalive().onReceive(app, new Intent(Keepalive.ACTION));
        assertNull(sa.getNextStartedService());
        assertNull(alarms().peekNextScheduledAlarm());
        // and a stray intent is ignored
        prefs.setLink(new Link(prefs.server(), "00112233445566778899aabbccddeeff", "tokTexts0123456789abcdefghijklmnopqrstuvwxy", new byte[32]));
        new Keepalive().onReceive(app, new Intent("something.else"));
        assertNull(sa.getNextStartedService());
    }

    @Test
    public void aCheckHoldsTheCpuAwakeOnlyWhileItRuns() {
        android.os.PowerManager.WakeLock lock = Keepalive.wake(app, "check");
        assertNotNull(lock);
        assertTrue(lock.isHeld());
        assertFalse(lock.isHeld() && Keepalive.WAKE_MS > 60_000L);   // never more than a minute
        lock.release();
        assertFalse(lock.isHeld());
    }
}
