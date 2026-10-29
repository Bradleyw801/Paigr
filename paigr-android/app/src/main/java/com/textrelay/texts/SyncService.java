package com.textrelay.texts;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.IOException;

/**
 * Keeps checking the inbox while the app is closed. A foreground service is the only thing Android lets run this
 * often without Google's push, and Android shows a notification for it, silent, on its own channel. Android raises
 * an app's own "minimal" setting for such a notification to an ordinary one, so the way to hide it is Android's
 * setting for the channel, which it respects; Texts keeps listening either way. With alerts off it doesn't run.
 */
public class SyncService extends Service {
    /** The background notification's channel. */
    static final String CHANNEL = "status";
    /** 0.3's "standard" channel; one channel does now. */
    static final String OLD_STANDARD = "status-standard";
    static final int NOTIFICATION_ID = 1;

    private static volatile Sync sync;
    private static volatile Notifier notifier;
    private static final Object signal = new Object();
    private Thread loop;
    private Doorbell doorbell;
    private volatile boolean running;
    private long wrapTried;

    /** The running service, for the keepalive and the network to poke; null while it isn't running. */
    private static volatile SyncService instance;

    static void start(Context context) {
        Intent intent = new Intent(context, SyncService.class);
        if (Prefs.ALERT_OFF.equals(new Prefs(context).alertMode())) {
            Keepalive.cancel(context);
            context.stopService(intent);
            return;
        }
        try {
            context.startForegroundService(intent);
        } catch (RuntimeException ignored) {
            // Background-start restrictions; the next time the app opens it starts again.
        }
        Keepalive.schedule(context);
    }

    static void stop(Context context) {
        Keepalive.cancel(context);
        context.stopService(new Intent(context, SyncService.class));
    }

    /**
     * The keepalive alarm: the service is started if it was stopped, a stale socket is dropped so a fresh one opens,
     * and the inbox is checked once, now. Runs while the alarm's wake lock holds; the check takes one of its own.
     */
    static void nudge(Context context) {
        SyncService running = instance;
        if (running == null) {
            start(context);
            return;
        }
        Doorbell d = running.doorbell;
        if (d != null && !d.isConnected()) d.reconnect();
        wake();
    }

    static Notifier notifier(Context context) {
        Notifier n = notifier;
        if (n == null) notifier = n = new Notifier(context);
        return n;
    }

    static Sync sync(Context context) {
        Sync s = sync;
        if (s == null) {
            Notifier n = notifier(context);
            sync = s = new Sync(new Prefs(context), new Sync.Listener() {
                @Override
                public void onIncoming(java.util.List<org.json.JSONObject> messages) {
                    n.show(messages);
                }

                @Override
                public void onRelayState(boolean online, long lastSeen) {
                    n.relayState(online, lastSeen);
                }

                @Override
                public void onUnread(java.util.Map<String, Integer> unread) {
                    n.unread(unread);
                }
            });
        }
        return s;
    }

    /** Check now instead of at the next interval (the doorbell rang, or the app came to the front). */
    static void wake() {
        synchronized (signal) {
            signal.notifyAll();
        }
    }

    /** The screen is open (and which conversation is on it): a text for that one needs no announcing. */
    static void setScreenOpen(Context context, boolean open) {
        sync(context).screenOpen = open;
        if (open) new Prefs(context).touchUsed();
    }

    static void setOpenConversation(Context context, String conv) {
        sync(context).openConv = conv;
    }

    static void createChannels(Context context) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        NotificationChannel status = new NotificationChannel(CHANNEL, context.getString(R.string.channel_status), NotificationManager.IMPORTANCE_LOW);
        status.setShowBadge(false);
        status.setSound(null, null);
        status.enableVibration(false);
        status.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        nm.createNotificationChannel(status);
        if (nm.getNotificationChannel(OLD_STANDARD) != null) nm.deleteNotificationChannel(OLD_STANDARD);
    }

    /** True once the background notification is turned off in Android's settings. */
    static boolean statusHidden(Context context) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        NotificationChannel c = nm.getNotificationChannel(CHANNEL);
        return c != null && c.getImportance() == NotificationManager.IMPORTANCE_NONE;
    }

    private ConnectivityManager.NetworkCallback network;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels(this);
        instance = this;
        watchNetwork();
    }

    /**
     * A network coming or going: Wi-Fi to mobile and back leaves the socket silently dead until its next ping shows
     * it, so a new network drops it at once and a fresh one opens on the new path, with a check right away.
     */
    private void watchNetwork() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm == null) return;
        network = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network n) {
                Doorbell d = doorbell;
                if (d != null) d.reconnect();
                wake();
            }

            @Override
            public void onLost(Network n) {
                Sync s = sync;
                if (s != null) s.doorbellConnected = false;
            }
        };
        try {
            cm.registerNetworkCallback(new NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), network);
        } catch (RuntimeException e) {
            network = null;   // too many callbacks, or no permission: the pings catch a dead socket instead
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = build(this);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
        startLoop();
        Keepalive.schedule(this);
        return START_STICKY;
    }

    private synchronized void startLoop() {
        if (loop != null && loop.isAlive()) return;
        running = true;
        loop = new Thread(this::run, "texts-sync");
        loop.setDaemon(true);
        loop.start();
        if (doorbell == null) {
            Sync s = sync(this);
            Prefs p = new Prefs(this);
            Link l = p.link();
            s.link = l;
            Doorbell.Tickets tickets = l == null ? null : () -> new Hedy(l).get("/v2/ticket");
            doorbell = new Doorbell(l != null ? l.server : p.server(), "inbox", new Doorbell.Listener() {
                @Override
                public void onRing(String type, org.json.JSONObject payload) {
                    wake();
                }

                @Override
                public void onStateChange(boolean connected) {
                    s.doorbellConnected = connected;
                    if (connected) wake();   // catch up on anything that arrived while the socket was down
                }
            }, tickets);
            doorbell.start();
        }
    }

    private void run() {
        Prefs prefs = new Prefs(this);
        Sync s = sync(this);
        long backoff = 2_000;
        while (running) {
            if (!prefs.paired()) {
                stopSelf();
                return;
            }
            long wait;
            // The CPU stays awake for the check: a doorbell ring that woke the phone would otherwise let it doze off
            // again before the inbox answered, and the text would wait for the next alarm.
            PowerManager.WakeLock lock = Keepalive.wake(this, "check");
            try {
                Link l = prefs.link();
                s.link = l;
                wait = s.checkOnce(l != null ? new Hedy(l) : new Hedy(prefs.server(), prefs.key())) * 1000L;
                backoff = 2_000;
                // The wrap for getting back in after a reinstall, if filing it failed when the link was made.
                if (l != null && prefs.wrapTag().isEmpty() && System.currentTimeMillis() - wrapTried > 10 * 60_000L) {
                    wrapTried = System.currentTimeMillis();
                    MainActivity.fileWrap(this, prefs, l);
                }
            } catch (Hedy.ApiException e) {
                if (e.unlinked || (e.keyRejected() && prefs.link() == null)) {
                    rejected();
                    stopSelf();
                    return;
                }
                wait = backoff;
                backoff = Math.min(backoff * 2, 120_000);
            } catch (IOException | RuntimeException e) {
                wait = backoff;
                backoff = Math.min(backoff * 2, 120_000);
            } finally {
                if (lock != null && lock.isHeld()) lock.release();
            }
            synchronized (signal) {
                try {
                    signal.wait(wait);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    /** The server no longer accepts this phone: say so once, where a tap opens the app to link it again. */
    private void rejected() {
        Prefs p = new Prefs(this);
        if (p.link() != null) p.setLink(null);
        else p.setKey("");
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, Notifier.CHANNEL)
                .setSmallIcon(R.drawable.ic_status)
                .setColor(Ui.BRAND.base)
                .setContentTitle("Paigr needs to be linked again")
                .setContentText("This phone was removed from your inbox. In Paigr Relay, tap Link a device, then scan the code with Paigr.")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        getSystemService(NotificationManager.class).notify(3, n);
    }

    static Notification build(Context context) {
        Intent open = new Intent(context, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_status)
                .setColor(Ui.BRAND.base)
                .setContentIntent(pi)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_SECRET)
                .setContentTitle("Listening for new texts")
                .build();
    }

    @Override
    public void onDestroy() {
        running = false;
        if (instance == this) instance = null;
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null && network != null) {
            try {
                cm.unregisterNetworkCallback(network);
            } catch (RuntimeException ignored) {
                // never registered
            }
        }
        Thread t = loop;
        if (t != null) t.interrupt();
        Doorbell d = doorbell;
        doorbell = null;
        if (d != null) d.stop();
        Sync s = sync;
        if (s != null) s.doorbellConnected = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
