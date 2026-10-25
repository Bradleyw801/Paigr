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
        // Picks up texts that arrived while the relay was down, and restores reply handles.
        WORK.execute(() -> relay.rescan(false));
        RelayService.start(this);
    }

    @Override
    public void onListenerDisconnected() {
        if (current == this) current = null;
        Relay.get(this).listening = false;
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
        WORK.execute(() -> relay.onNotification(sbn));
    }
}
