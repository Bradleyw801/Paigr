package com.textrelay.relay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Brings the relay back after a reboot or an app update, so a phone in a drawer never needs a tap. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            if (Relay.get(context).prefs.configured()) {
                RelayService.start(context);
                Watchdog.schedule(context);
            }
        }
    }
}
