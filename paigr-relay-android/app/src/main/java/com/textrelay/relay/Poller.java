package com.textrelay.relay;

import android.util.Log;

import java.io.IOException;

/**
 * Polls the inbox server for replies and sends heartbeats, and keeps the regular checks of the message store going.
 * One per process. The doorbell wakes it the moment a reply is queued; while the doorbell is connected the fallback
 * interval stretches, and when it isn't the server's own interval applies.
 */
final class Poller extends Thread {
    static final int CONNECTED_SECONDS = 45;

    private final Relay relay;
    private final Object signal = new Object();
    private volatile boolean running = true;
    private volatile boolean poked;

    Poller(Relay relay) {
        super("relay-poller");
        setDaemon(true);
        this.relay = relay;
    }

    void shutdown() {
        running = false;
        interrupt();
    }

    /** Check now, whatever the interval says. */
    void wake() {
        synchronized (signal) {
            poked = true;
            signal.notifyAll();
        }
    }

    @Override
    public void run() {
        long backoff = 2_000;
        while (running) {
            if (!relay.prefs.configured()) {
                pause(5_000);
                continue;
            }
            Hedy hedy = relay.hedy();
            try {
                int pollSeconds = relay.processOutbox(hedy);
                if (System.currentTimeMillis() - relay.lastHeartbeat > 60_000) relay.heartbeat(hedy);
                relay.storeSync.deepIfDue();
                String mergeProblem = null;
                try {
                    relay.flushMerges(hedy);
                } catch (IOException | RuntimeException e) {
                    // tried again on the next poll; said in this phone's own words
                    mergeProblem = "Merging conversations stopped partway ("
                            + (e instanceof Hedy.ApiException ? String.valueOf(((Hedy.ApiException) e).code) : e.getClass().getSimpleName())
                            + "); it tries again on the next check.";
                }
                relay.lastError = mergeProblem;
                relay.torDown = false;
                relay.prefs.setVerified(true);
                if (relay.prefs.link() != null && relay.prefs.wrapTag().isEmpty()) relay.fileWrap();
                backoff = 2_000;
                if (relay.lastQueued > 0) pause(pollSeconds * 1000L);
                else pause((relay.doorbellConnected() ? Math.max(pollSeconds, CONNECTED_SECONDS) : pollSeconds) * 1000L);
            } catch (Hedy.ApiException e) {
                if (e.unlinked) {
                    relay.unlinked();   // taken out of its inbox: back to showing its code
                    pause(5_000);
                    continue;
                }
                relay.lastError = "Server: " + e.getMessage();
                if (e.keyRejected()) relay.prefs.setVerified(false);
                pause(e.keyRejected() ? 60_000 : backoff);
                backoff = Math.min(backoff * 2, 60_000);
            } catch (IOException e) {
                relay.torDown = e instanceof Tor.Down;
                relay.lastError = e instanceof Tor.Down ? e.getMessage() : "No connection to the inbox server (" + e.getClass().getSimpleName() + ")";
                pause(backoff);
                backoff = Math.min(backoff * 2, 60_000);
            } catch (RuntimeException e) {
                Log.w("Relay", "poll failed", e);
                relay.lastError = "Problem: " + e.getClass().getSimpleName();   // its own words only, never a server's
                pause(backoff);
                backoff = Math.min(backoff * 2, 60_000);
            }
        }
    }

    /** Sleeps up to ms, or until wake() is called. */
    private void pause(long ms) {
        synchronized (signal) {
            if (poked) {
                poked = false;
                return;
            }
            try {
                signal.wait(ms);
            } catch (InterruptedException e) {
                // shutting down
            }
            poked = false;
        }
    }
}
