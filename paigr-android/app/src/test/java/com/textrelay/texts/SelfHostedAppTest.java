package com.textrelay.texts;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Every one of NativeAppTest's checks, against the server people run themselves (relay-hedy's server/paigr-server.js)
 * instead of the hosted platform's stand-in: its own storage, upload tickets and signed file links. And its realtime
 * socket, which the stand-in doesn't have: the app opens it, and hears the server ring.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w320dp-h427dp-hdpi")
public class SelfHostedAppTest extends NativeAppTest {
    @Override
    protected String serverKind() {
        return RealInbox.SELF_HOSTED;
    }

    @Test
    public void theAppOpensTheServersSocketAndHearsItRing() throws Exception {
        RealInbox s = start("demo");
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        waitFor("the socket", () -> a.inbox.doorbellUp());
        int before = a.inbox.ringsHeard;
        // Another of this inbox's phones finished bringing in old texts: the server rings every phone to reload.
        s.call("POST", "/v2/import/done", prefs.link().token, new JSONObject());
        waitFor("the ring", () -> a.inbox.ringsHeard > before);
    }
}
