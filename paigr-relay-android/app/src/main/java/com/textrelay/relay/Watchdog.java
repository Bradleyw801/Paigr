package com.textrelay.relay;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.service.notification.NotificationListenerService;

/**
 * Every 15 minutes, puts the relay back on its feet if Android quietly took it down: restarts the foreground
 * service and the poller, and re-binds the notification listener when access is granted but nothing is arriving.
 */
public class Watchdog extends BroadcastReceiver {
    static final String ACTION = "com.textrelay.relay.WATCHDOG";
    private static final long INTERVAL = AlarmManager.INTERVAL_FIFTEEN_MINUTES;

    static void schedule(Context context) {
        AlarmManager am = context.getSystemService(AlarmManager.class);
        if (am == null) return;
        PendingIntent pi = pending(context);
        am.cancel(pi);
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + INTERVAL, INTERVAL, pi);
    }

    private static PendingIntent pending(Context context) {
        Intent i = new Intent(context, Watchdog.class).setAction(ACTION);
        return PendingIntent.getBroadcast(context, 7, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        check(context);
    }

    /** One pass of the checks; also called when the app opens. */
    static void check(Context context) {
        Relay relay = Relay.get(context);
        if (!relay.prefs.configured()) return;
        RelayService.start(context);
        relay.startPoller();
        ComponentName listener = new ComponentName(context, RelayListenerService.class);
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        boolean granted = nm != null && nm.isNotificationListenerAccessGranted(listener);
        if (granted && !relay.listening) rebind(context, listener);
    }

    /** The documented way to make Android reconnect a listener it dropped: flip the component off and on, then ask. */
    static void rebind(Context context, ComponentName listener) {
        PackageManager pm = context.getPackageManager();
        try {
            pm.setComponentEnabledSetting(listener, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
            pm.setComponentEnabledSetting(listener, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        } catch (RuntimeException ignored) {
            // some ROMs refuse; the rebind request below still helps
        }
        try {
            NotificationListenerService.requestRebind(listener);
        } catch (RuntimeException ignored) {
            // not bound yet; the system binds it once access is granted
        }
    }
}
