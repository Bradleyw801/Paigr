package com.textrelay.texts;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

/**
 * What keeps the listener alive when Android would rather it slept. The service and its socket do the work; this is
 * the insurance: an alarm every quarter of an hour that Android fires even while the phone dozes, which starts the
 * service again if it was stopped, and makes it check the inbox once, holding the CPU awake long enough to finish.
 * With "Allow background use" granted the socket stays up through doze and the alarm rarely finds anything to do.
 */
public final class Keepalive extends BroadcastReceiver {
    static final String ACTION = "com.textrelay.texts.KEEPALIVE";
    /** A conversation's notification was swiped away (Notifier). */
    static final String ACTION_DISMISSED = "com.textrelay.texts.DISMISSED";
    static final long INTERVAL_MS = 15 * 60_000L;
    /** How long a check may hold the CPU awake: a slow inbox answers well within this. */
    static final long WAKE_MS = 45_000L;
    private static final int REQUEST = 7;

    /** Sets (or moves) the next alarm; harmless to call often. */
    static void schedule(Context context) {
        AlarmManager am = context.getSystemService(AlarmManager.class);
        if (am == null) return;
        PendingIntent pi = pending(context);
        am.cancel(pi);
        // Inexact, so it needs no exact-alarm permission; allowed while idle, so doze only delays it to its next window.
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + INTERVAL_MS, pi);
    }

    static void cancel(Context context) {
        AlarmManager am = context.getSystemService(AlarmManager.class);
        if (am != null) am.cancel(pending(context));
    }

    private static PendingIntent pending(Context context) {
        Intent i = new Intent(context, Keepalive.class).setAction(ACTION);
        return PendingIntent.getBroadcast(context, REQUEST, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** A wake lock for one check, released by the caller (or by its timeout). */
    static PowerManager.WakeLock wake(Context context, String what) {
        PowerManager pm = context.getSystemService(PowerManager.class);
        if (pm == null) return null;
        PowerManager.WakeLock lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "paigr:" + what);
        lock.setReferenceCounted(false);
        lock.acquire(WAKE_MS);
        return lock;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (ACTION_DISMISSED.equals(intent.getAction())) {
            SyncService.notifier(context).dismissed(intent.getStringExtra(MainActivity.EXTRA_CONV));
            return;
        }
        if (!ACTION.equals(intent.getAction())) return;
        Prefs prefs = new Prefs(context);
        if (!prefs.paired() || Prefs.ALERT_OFF.equals(prefs.alertMode())) return;
        schedule(context);
        SyncService.nudge(context);
    }
}
