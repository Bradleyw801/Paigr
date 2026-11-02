package com.textrelay.relay;

import android.content.ComponentName;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Once notification access is granted, Android hands this service every notification as it's posted. */
public class RelayListenerService extends NotificationListenerService {
    /** The connected listener, for re-reading what Messages is showing right now. Null while Android has it unbound. */
    private static volatile RelayListenerService current;
    /** Notifications are read off the main thread, in the order they came: matching one may look in the message store. */
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "relay-notifications");
        t.setDaemon(true);
        return t;
    });

    /** Whether Android has this listener connected right now. */
    static boolean bound() {
        return current != null;
    }

    /** Messages' notifications on screen right now; null when the listener isn't connected (or refuses for a moment). */
    static StatusBarNotification[] activeNow() {
        RelayListenerService s = current;
        if (s == null) return null;
        try {
            return s.getActiveNotifications();
        } catch (RuntimeException e) {
            return null;   // the system sometimes refuses this right after a rebind
        }
    }

    @Override
    public void onListenerConnected() {
        current = this;
        Relay relay = Relay.get(this);
        relay.listening = true;
        relay.listenerSince = System.currentTimeMillis();
        // Picks up texts that arrived while the relay was down, and restores reply handles. The system can refuse the
        // read for a moment right after connecting: then again, a little later, a few times.
        rescanSoon(relay, 0, 0);
        RelayService.start(this);
    }

    private void rescanSoon(Relay relay, long delayMs, int attempt) {
        Runnable go = () -> WORK.execute(() -> {
            boolean read;
            try {
                read = relay.rescan(false);
            } catch (RuntimeException e) {
                android.util.Log.w("Relay", "rescan failed", e);
                read = true;   // not the system refusing: no point asking again
            }
            if (!read && bound() && attempt < 3) rescanSoon(relay, 2_000L * (attempt + 1), attempt + 1);
            else relay.rescanned();
        });
        if (delayMs <= 0) go.run();
        else new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(go, delayMs);
    }

    @Override
    public void onListenerDisconnected() {
        if (current == this) current = null;
        Relay relay = Relay.get(this);
        relay.listening = false;
        relay.listenerLostAt = System.currentTimeMillis();
        requestRebind(new ComponentName(this, RelayListenerService.class));
    }

    @Override
    public void onDestroy() {
        if (current == this) current = null;
        super.onDestroy();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        Relay relay = Relay.get(this);
        WORK.execute(() -> {
            try {
                relay.onNotification(sbn);
            } catch (RuntimeException e) {
                // A notification in a shape nobody expected: noted, never fatal (an uncaught one here ends the process).
                android.util.Log.w("Relay", "notification not read", e);
                relay.lastError = "Problem reading a notification: " + e.getClass().getSimpleName();
            }
        });
    }

    /**
     * Messages took a conversation's notification down itself: it was opened on this phone (or marked read), so the
     * conversation is read, and every device hears so. A swipe by the user says nothing about reading.
     */
    @Override
    public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap, int reason) {
        if (sbn == null || !Parser.MESSAGES_PACKAGE.equals(sbn.getPackageName())) return;
        if (reason != REASON_CLICK && reason != REASON_APP_CANCEL && reason != REASON_APP_CANCEL_ALL) return;
        Relay relay = Relay.get(this);
        WORK.execute(() -> {
            try {
                relay.onNotificationRead(sbn);
            } catch (RuntimeException e) {
                android.util.Log.w("Relay", "removed notification not read", e);
            }
        });
    }
}
