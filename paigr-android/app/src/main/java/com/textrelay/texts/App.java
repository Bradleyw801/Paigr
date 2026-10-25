package com.textrelay.texts;

import android.app.Application;

/** Starts before anything else in the app: how it connects (Tor or not) is known before its first request. */
public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Tor.init(this);
    }
}
