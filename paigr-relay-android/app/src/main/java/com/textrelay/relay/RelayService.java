package com.textrelay.relay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * Keeps the relay at the front of Android's queue with a silent status notification. With the status notification set
 * to hidden it doesn't run at all: the notification listener Android keeps bound holds the relay up on its own, and the
 * watchdog brings it back if Android ever drops it.
 */
public class RelayService extends Service {
    /** The status notification's channel: silent, no badge. */
    static final String CHANNEL = "relay-status";
    /** Earlier versions' "minimal" channel; Android raised it to an ordinary one, so it is removed. */
    static final String OLD_MINIMAL = "relay-minimal";
    static final int NOTIFICATION_ID = 1;

    /** Starts the relay: always the poll loop, and the status notification unless it is set to hidden. */
    static void start(Context context) {
        Relay relay = Relay.get(context);
        relay.startPoller();
        Intent intent = new Intent(context, RelayService.class);
        if (Prefs.STATUS_HIDDEN.equals(relay.prefs.statusMode())) {
            context.stopService(intent);
            return;
        }
        try {
            context.startForegroundService(intent);
        } catch (RuntimeException ignored) {
            // Background-start restrictions; the listener still works without the foreground service.
        }
    }

    static void createChannels(Context context) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        NotificationChannel status = new NotificationChannel(CHANNEL, context.getString(R.string.channel_status), NotificationManager.IMPORTANCE_LOW);
        status.setShowBadge(false);
        status.setSound(null, null);
        status.enableVibration(false);
        status.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        nm.createNotificationChannel(status);
        if (nm.getNotificationChannel(OLD_MINIMAL) != null) nm.deleteNotificationChannel(OLD_MINIMAL);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = build(this);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
        Relay relay = Relay.get(this);
        relay.startPoller();
        Watchdog.schedule(this);
        if (Prefs.STATUS_HIDDEN.equals(relay.prefs.statusMode())) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    static Notification build(Context context) {
        Intent open = new Intent(context, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_status)
                .setContentIntent(pi)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_SECRET)
                .setContentTitle("Forwarding texts to your inbox")
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
