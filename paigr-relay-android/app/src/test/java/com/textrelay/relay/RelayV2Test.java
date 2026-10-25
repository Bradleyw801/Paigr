package com.textrelay.relay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.RemoteInput;
import android.content.Intent;
import android.service.notification.StatusBarNotification;

import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

/**
 * Every RelayTest against a private inbox (v2): the fake server opens what the relay sealed, so the same checks hold.
 * Plus what only a private inbox does: the server never sees a readable word, linking by a scan, getting back in
 * after a reinstall, and stepping aside when another relay phone takes over.
 */
public class RelayV2Test extends RelayTest {
    @Override
    protected boolean v2() {
        return true;
    }

    @Test
    public void theServerSeesNoReadableTextNamesOrConversationIds() throws Exception {
        long now = System.currentTimeMillis();
        relay.onNotification(conversation("c1", "Alice Smith", false, "REPLY_C1", from("Alice Smith", "Meet at Sundance at 6?", now)));
        relay.drain();
        JSONObject call = server.calls("/api/relay/ingest").get(0);
        String raw = call.getJSONObject("_raw").toString();
        assertFalse(raw, raw.contains("Sundance"));
        assertFalse(raw, raw.contains("Alice"));
        JSONObject sealed = call.getJSONObject("_raw").getJSONArray("items").getJSONObject(0);
        String localConv = call.getJSONArray("items").getJSONObject(0).getString("conv");
        assertFalse(raw, raw.contains(localConv));                         // only the keyed id goes up
        assertEquals(relay.prefs.link().convId(localConv), sealed.getString("conv"));
        assertTrue(sealed.getString("e").startsWith("e1."));
        assertTrue(sealed.getString("ne").startsWith("e1."));
        assertEquals(relay.prefs.link().matchToken("in", "Meet at Sundance at 6?", null), sealed.getString("mt"));
        assertEquals("Alice Smith", call.getJSONArray("items").getJSONObject(0).getString("_name"));
        assertEquals(TOKEN, call.getString("_key"));
        assertEquals("", call.getJSONObject("_headers").optString("x-hedy-app-key-original", ""));
    }

    @Test
    public void anUnlinkedRelayShowsACodeAndJoinsTheInboxTextsMakesForIt() throws Exception {
        relay.prefs.raw().edit().clear().commit();   // a new install: never in an inbox
        relay.prefs.setServer(server.baseUrl(), "");
        // A text is on screen in Messages while this phone waits to be linked.
        long now = System.currentTimeMillis();
        StatusBarNotification waiting = conversation("c8", "Kai", false, "REPLY_C8", from("Kai", "Did you get my last one?", now));
        relay.active = () -> new StatusBarNotification[] { waiting };
        relay.onNotification(waiting);
        Relay.androidIdForTests = null;
        android.provider.Settings.Secure.putString(app.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID, "5f3a9c2e7b1d4e60");
        MainActivity activity = org.robolectric.Robolectric.buildActivity(MainActivity.class).setup().get();
        waitFor(() -> {
            tick();
            return !server.calls("/v2/link/ask").isEmpty();
        });
        // It first asked whether this phone was linked before (it wasn't), then filed its code.
        assertEquals(1, server.calls("/v2/link/recover").size());
        JSONObject asked = server.calls("/v2/link/ask").get(0);
        assertEquals("relay", asked.getString("role"));
        assertEquals("", asked.getString("_key"));   // linking needs no account and no key
        waitFor(() -> {
            tick();
            android.widget.ImageView qr = findQr(activity.findViewById(android.R.id.content), "QR code for linking this phone");
            return qr != null && qr.getDrawable() != null;
        });
        String screen = allText(activity.findViewById(android.R.id.content));
        assertTrue(screen, screen.contains("open Paigr and scan this code"));
        assertFalse(screen, screen.contains("127.0.0.1"));
        String payload = decode(findQr(activity.findViewById(android.R.id.content), "QR code for linking this phone"));
        // not a web address: a camera app shows it as text instead of opening the server's page with the code in it
        assertTrue(payload, payload.matches("TEXTRELAY2:127\\.0\\.0\\.1:[0-9]+/[0-9A-HJKMNP-TV-Z]{12}"));
        String code = payload.substring(payload.lastIndexOf('/') + 1);
        assertTrue(screen, screen.contains(Pairing.pretty(code)));
        Crypto.LinkSecrets s = Crypto.linkSecrets(code);
        assertEquals(s.lookup, asked.getString("lookup"));   // the server gets the lookup, never the code

        // Texts scans it and starts a new inbox: it seals the inbox key for this phone with the code's box key.
        byte[] inboxKey = Crypto.random(32);
        String token = server.asked.get(s.lookup);
        Link texts = new Link(server.baseUrl(), FakeHedy.TID, Crypto.newToken(), inboxKey);
        server.link = new Link(server.baseUrl(), FakeHedy.TID, token, inboxKey);   // the inbox has this phone now
        server.answers.put(s.lookup, texts.box(s.boxKey));
        waitFor(() -> {
            tick();
            return relay.prefs.link() != null;
        });
        Link got = relay.prefs.link();
        assertEquals(FakeHedy.TID, got.tid);
        assertEquals(token, got.token);
        assertEquals(texts.convId("abc"), got.convId("abc"));   // the same inbox key
        assertEquals("", relay.prefs.key());                     // nothing goes to the first version's inbox any more
        // It filed its wrap, so a reinstalled Relay gets back in by itself.
        waitFor(() -> !server.wraps.isEmpty());
        Crypto.PhoneSecrets p = Crypto.phoneSecrets("5f3a9c2e7b1d4e60", "relay");
        assertTrue(server.wraps.containsKey(p.tag));
        assertTrue(got.sameAs(Link.unwrap(server.baseUrl(), p.wrapKey, server.wraps.get(p.tag))));
        tick();
        assertTrue(allText(activity.findViewById(android.R.id.content)).contains("private inbox"));
        // What was on screen while it waited goes up as soon as it's in, without another text to set it off.
        waitFor(() -> {
            try {
                for (JSONObject it : allItems()) if (it.getString("text").equals("Did you get my last one?")) return true;
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            return false;
        });
    }

    @Test
    public void aReinstalledRelayGetsBackInWithoutAScan() throws Exception {
        Link before = relay.prefs.link();
        Crypto.PhoneSecrets p = Crypto.phoneSecrets("0a1b2c3d4e5f6789", "relay");
        server.wraps.put(p.tag, before.wrap(p.wrapKey));
        relay.prefs.raw().edit().clear().commit();   // reinstalled: nothing on the phone
        relay.prefs.setServer(server.baseUrl(), "");
        Relay.androidIdForTests = "0a1b2c3d4e5f6789";
        try {
            Link back = relay.recover();
            assertNotNull(back);
            assertTrue(back.sameAs(before));
            assertTrue(relay.prefs.link().sameAs(before));
            assertEquals(1, server.calls("/v2/link/recover").size());
            // back after a reinstall, nothing written before that moment is sent (the nonces it spent went with the app)
            long now = System.currentTimeMillis();
            String why = relay.prefs.spendRequest("beforebeforebeforebefore", now - 60_000L, now, Json.obj("status", "failed"));
            assertEquals(NotSent.OLD, why);
            // another phone (or the same phone after a factory reset) is not this phone
            Relay.reset();
            relay = Relay.get(app);
            relay.prefs.raw().edit().clear().commit();
            relay.prefs.setServer(server.baseUrl(), "");
            Relay.androidIdForTests = "ffffffffffffffff";
            assertNull(relay.recover());
        } finally {
            Relay.androidIdForTests = null;
        }
    }

    @Test
    public void whenAnotherRelayPhoneTakesOverThisOneStepsAsideAndShowsItsCode() throws Exception {
        relay.startPoller();
        waitFor(() -> !server.calls("/api/relay/heartbeat").isEmpty());
        server.revoked = true;   // Texts brought in a new relay phone
        relay.wakePoller();
        waitFor(() -> relay.prefs.link() == null);
        assertTrue(relay.lastError, relay.lastError.contains("removed from your inbox"));
        assertFalse(relay.prefs.configured());
        // Texts that arrive now are not sent anywhere.
        long now = System.currentTimeMillis();
        int before = server.calls("/api/relay/ingest").size();
        relay.onNotification(conversation("c9", "Zed", false, "REPLY_C9", from("Zed", "anyone there?", now)));
        Thread.sleep(300);
        assertEquals(before, server.calls("/api/relay/ingest").size());
    }

    @Test
    public void aReplySealedWithAnotherKeyIsReportedFailedNotSent() throws Exception {
        long now = System.currentTimeMillis();
        relay.onNotification(conversation("c2", "Bob", false, "REPLY_C2", from("Bob", "Call me?", now)));
        relay.drain();
        String conv = server.calls("/api/relay/ingest").get(0).getJSONArray("items").getJSONObject(0).getString("conv");
        Link stranger = new Link(server.baseUrl(), FakeHedy.TID, Crypto.newToken(), Crypto.random(32));
        server.pendingOutbox.add(new JSONObject().put("id", "m:1:bad").put("conv", conv).put("ts", now)
                .put("o", stranger.seal(Json.obj("text", "not for you", "n", "abcdefghijklmnopqrstuv", "ts", now), Crypto.aadOut(relay.prefs.link().convId(conv)))));
        relay.processOutbox(relay.hedy());
        assertNull(lastBroadcast("REPLY_C2"));
        List<JSONObject> reports = server.calls("/api/relay/outbox/result");
        assertEquals(1, reports.size());
        assertEquals("failed", reports.get(0).getString("status"));
        assertEquals(NotSent.UNCHECKED, reports.get(0).getString("code"));
        assertFalse("the inbox gets a code, never words", reports.get(0).has("error"));
    }

    /** The server stores replies; it must not be able to aim one at someone else, send one twice, or send it late. */
    @Test
    public void aReplyGoesOnlyToItsOwnConversationOnlyOnceAndOnlyWhileFresh() throws Exception {
        long now = System.currentTimeMillis();
        relay.onNotification(conversation("c2", "Bob", false, "REPLY_C2", from("Bob", "Your code is 551203", now)));
        relay.onNotification(conversation("c3", "Eve", false, "REPLY_C3", from("Eve", "hi", now)));
        relay.drain();
        String bob = null, eve = null;
        for (JSONObject it : allItems()) {
            if (it.getString("text").startsWith("Your code")) bob = it.getString("conv");
            if (it.getString("text").equals("hi")) eve = it.getString("conv");
        }
        assertNotNull(bob);
        assertNotNull(eve);

        // An incoming text dressed up as a reply: it doesn't open as a request.
        server.pendingOutbox.add(new JSONObject().put("id", "m:1:fake").put("conv", eve).put("ts", now)
                .put("o", relay.prefs.link().seal(Json.obj("text", "Your code is 551203", "sender", "Bob"), Crypto.aadMessage(relay.prefs.link().convId(bob), "in"))));
        // A real request for Bob, filed under Eve's conversation: it doesn't open there.
        server.pendingOutbox.add(new JSONObject().put("id", "m:2:moved").put("conv", eve).put("sealFor", bob).put("text", "for Bob only").put("ts", now));
        relay.processOutbox(relay.hedy());
        assertNull(lastBroadcast("REPLY_C3"));
        assertNull(lastBroadcast("REPLY_C2"));
        List<JSONObject> reports = server.calls("/api/relay/outbox/result");
        assertEquals(2, reports.size());
        for (JSONObject r : reports) assertEquals("failed", r.getString("status"));

        // The real one goes once. The same request again, under any id (a server repeating it, or a reply re-queued by a
        // race), is never sent again: what became of it the first time is reported again.
        server.pendingOutbox.add(new JSONObject().put("id", "m:3:real").put("conv", bob).put("n", "nonce0nonce0nonce0nonce").put("text", "On my way").put("ts", now));
        relay.processOutbox(relay.hedy());
        assertEquals("On my way", RemoteInput.getResultsFromIntent(lastBroadcast("REPLY_C2")).getCharSequence("reply_text").toString());
        server.pendingOutbox.add(new JSONObject().put("id", "m:4:again").put("conv", bob).put("n", "nonce0nonce0nonce0nonce").put("text", "On my way").put("ts", now));
        server.pendingOutbox.add(new JSONObject().put("id", "m:3:real").put("conv", bob).put("n", "nonce0nonce0nonce0nonce").put("text", "On my way").put("ts", now));
        relay.processOutbox(relay.hedy());
        assertEquals(1, sends(app, "REPLY_C2"));
        reports = server.calls("/api/relay/outbox/result");
        assertEquals("sent", reports.get(2).getString("status"));
        assertEquals("m:4:again", reports.get(3).getString("id"));
        assertEquals("sent", reports.get(3).getString("status"));
        assertEquals("sent", reports.get(4).getString("status"));

        // Held back for more than two days, from the future, or made before this phone joined its inbox: not sent.
        server.pendingOutbox.add(new JSONObject().put("id", "m:5:old").put("conv", bob).put("text", "late").put("ts", now).put("made", now - 3 * 86_400_000L));
        server.pendingOutbox.add(new JSONObject().put("id", "m:6:early").put("conv", bob).put("text", "early").put("ts", now)
                .put("made", relay.prefs.linkedAt() - 30 * 60_000L));
        server.pendingOutbox.add(new JSONObject().put("id", "m:7:ahead").put("conv", bob).put("text", "ahead").put("ts", now).put("made", now + 2 * 86_400_000L));
        relay.processOutbox(relay.hedy());
        assertEquals(1, sends(app, "REPLY_C2"));
        reports = server.calls("/api/relay/outbox/result");
        assertEquals(NotSent.OLD, reports.get(5).getString("code"));
        assertEquals(NotSent.OLD, reports.get(6).getString("code"));
        assertEquals(NotSent.FUTURE, reports.get(7).getString("code"));

        // Relay stopped in the middle of sending one (the request was spent, its outcome never recorded): it comes back
        // reported failed, never sent a second time.
        relay.prefs.spendRequest("crashcrashcrashcrash00", now, now, NotSent.report("m:0:first", NotSent.STOPPED));
        server.pendingOutbox.add(new JSONObject().put("id", "m:8:crash").put("conv", bob).put("n", "crashcrashcrashcrash00").put("text", "maybe").put("ts", now));
        relay.processOutbox(relay.hedy());
        assertEquals(1, sends(app, "REPLY_C2"));
        reports = server.calls("/api/relay/outbox/result");
        assertEquals("failed", reports.get(8).getString("status"));
        assertEquals(NotSent.STOPPED, reports.get(8).getString("code"));
        assertEquals("m:8:crash", reports.get(8).getString("id"));
    }

    @Test
    public void forgettingOldRequestsToMakeRoomNeverLetsOneGoAgain() throws Exception {
        int limit = Prefs.nonceLimit;
        Prefs.nonceLimit = 3;
        try {
            long now = System.currentTimeMillis();
            JSONObject done = Json.obj("status", "sent");
            assertNull(relay.prefs.spendRequest("first0first0first0first", now - 4000, now - 3000, done));
            for (int i = 0; i < 3; i++) assertNull(relay.prefs.spendRequest("later0later0later0later" + i, now - 1000, now - 900 + i, done));
            // "first…" had to be forgotten; asked again, it's refused as too old rather than sent
            assertNull(relay.prefs.spentRequest("first0first0first0first"));
            String why = relay.prefs.spendRequest("first0first0first0first", now - 4000, now, done);
            assertEquals(NotSent.OLD, why);
            // a new request still goes
            assertNull(relay.prefs.spendRequest("fresh0fresh0fresh0fresh", now, now, done));
        } finally {
            Prefs.nonceLimit = limit;
        }
    }

    @Test
    public void aReplyWrittenJustBeforeTwoConversationsBecameOneStillGoesOut() throws Exception {
        long now = System.currentTimeMillis();
        relay.onNotification(conversation("c7", "Lee", false, "REPLY_C7", from("Lee", "you around?", now)));
        relay.drain();
        String lee = server.calls("/api/relay/ingest").get(0).getJSONArray("items").getJSONObject(0).getString("conv");
        // This phone folded a thread of the message store into Lee's conversation; a reply was sealed for the thread.
        relay.prefs.addMerge("store-thread-of-lee", lee);
        server.pendingOutbox.add(new JSONObject().put("id", "m:1:merged").put("conv", lee).put("sealFor", "store-thread-of-lee").put("text", "yes").put("ts", now));
        relay.processOutbox(relay.hedy());
        assertEquals("yes", RemoteInput.getResultsFromIntent(lastBroadcast("REPLY_C7")).getCharSequence("reply_text").toString());
        // ...but not one sealed for a conversation this phone never merged into it
        server.pendingOutbox.add(new JSONObject().put("id", "m:2:other").put("conv", lee).put("sealFor", "someone-else").put("text", "no").put("ts", now));
        relay.processOutbox(relay.hedy());
        assertEquals(1, sends(app, "REPLY_C7"));
        // and the merge it asks the inbox for carries its sealed word for it
        relay.prefs.addMerge("other-thread", lee);
        relay.flushMerges(relay.hedy());
        JSONObject merge = server.calls("/api/relay/merge").get(0).getJSONObject("_raw");
        JSONObject said = Crypto.openJson(enc(), merge.getString("mr"), Crypto.aadMerge(merge.getString("into")));
        assertEquals(merge.getString("from"), said.getString("from"));
        assertEquals(merge.getString("into"), said.getString("into"));
    }

    private static byte[] enc() {
        return Crypto.hkdf(INBOX_KEY, "textrelay v2 enc");
    }

    @Test
    public void anInstallThatLeftAnInboxIsNeverPutBackByAWrap() throws Exception {
        Link before = relay.prefs.link();
        Crypto.PhoneSecrets p = Crypto.phoneSecrets("0a1b2c3d4e5f6789", "relay");
        server.wraps.put(p.tag, before.wrap(p.wrapKey));
        Relay.androidIdForTests = "0a1b2c3d4e5f6789";
        try {
            server.revoked = true;   // told it isn't in its inbox (whether true or not)
            relay.unlinked();
            server.revoked = false;
            assertNull(relay.recover());
            assertTrue(server.calls("/v2/link/recover").isEmpty());
            assertNull(relay.prefs.link());
        } finally {
            Relay.androidIdForTests = null;
        }
    }

    @Test
    public void aLinkThatCameFromAnotherPhoneIsDropped() throws Exception {
        try {
            Relay.androidIdForTests = "5555666677778888";   // linked on the old phone...
            connect(server.baseUrl(), KEY);
            assertNotNull(relay.prefs.link());
            Relay.androidIdForTests = "1111222233334444";   // ...and its data copied to this one
            assertNull("dropped wherever it's read, before anything uses it", relay.prefs.link());
            relay.checkSamePhone();
            assertTrue(relay.lastError, relay.lastError.contains("came from another phone"));
            assertNull(relay.hedy2());
            // linked on this phone, it stays
            connect(server.baseUrl(), KEY);
            relay.checkSamePhone();
            assertNotNull(relay.prefs.link());
        } finally {
            Relay.androidIdForTests = null;
        }
    }

    @Test
    public void whatTheRelayPhoneSaysAboutItselfGoesSealed() throws Exception {
        relay.lastMediaError = "The inbox can't store image/heic attachments; they stay on this phone";
        relay.heartbeat(relay.hedy());
        JSONObject hb = server.calls("/api/relay/heartbeat").get(0);
        String raw = hb.getJSONObject("_raw").toString();
        assertFalse(raw, raw.contains("heic"));
        assertFalse(raw, hb.getJSONObject("_raw").has("note"));
        assertTrue(hb.getJSONObject("_raw").getString("sn").startsWith("e1."));
        assertEquals(relay.lastMediaError, hb.getString("note"));   // what the inbox devices open
        // the phone's model goes sealed too: devices show only what they open
        assertFalse(raw, hb.getJSONObject("_raw").has("model"));
        assertEquals(android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL, Crypto.openJson(enc(), hb.getJSONObject("_raw").getString("sn"), Crypto.AAD_NOTE).getString("model"));
    }

    @Test
    public void textsThatArriveWhileUnlinkedGoUpOnceLinked() throws Exception {
        connect(server.baseUrl(), "");
        long now = System.currentTimeMillis();
        StatusBarNotification sbn = conversation("c5", "Dee", false, "REPLY_C5", from("Dee", "Are you there?", now));
        relay.onNotification(sbn);
        Thread.sleep(200);
        assertTrue(server.calls("/api/relay/ingest").isEmpty());
        connect(server.baseUrl(), KEY);   // linked again (Texts scanned its code)
        relay.active = () -> new StatusBarNotification[] { sbn };
        assertTrue(relay.rescan(false));
        relay.drain();
        boolean found = false;
        for (JSONObject it : allItems()) found |= it.getString("text").equals("Are you there?");
        assertTrue("the text still on screen went up", found);
    }

    private List<JSONObject> allItems() throws Exception {
        List<JSONObject> out = new java.util.ArrayList<>();
        for (JSONObject call : server.calls("/api/relay/ingest")) {
            org.json.JSONArray items = call.getJSONArray("items");
            for (int i = 0; i < items.length(); i++) out.add(items.getJSONObject(i));
        }
        return out;
    }

    private static int sends(android.app.Application app, String action) {
        int n = 0;
        for (Intent i : org.robolectric.Shadows.shadowOf(app).getBroadcastIntents()) if (action.equals(i.getAction())) n++;
        return n;
    }

    @Test
    public void linkADeviceFilesAnInviteTheOtherDeviceCanOpen() throws Exception {
        relay.prefs.setVerified(true);
        MainActivity activity = org.robolectric.Robolectric.buildActivity(MainActivity.class).setup().get();
        click(activity, "Link a device");
        android.widget.ImageView qr = findQr(activity.findViewById(android.R.id.content), "QR code for linking a phone");
        long until = System.currentTimeMillis() + 10_000;
        while (!(shown(qr) && qr.getDrawable() != null) && System.currentTimeMillis() < until) {
            Thread.sleep(20);
            org.robolectric.shadows.ShadowLooper.idleMainLooper();
        }
        String payload = decode(qr);
        assertTrue(payload, payload.matches("TEXTRELAY2:127\\.0\\.0\\.1:[0-9]+/[0-9A-HJKMNP-TV-Z]{12}"));
        Crypto.LinkSecrets s = Crypto.linkSecrets(payload.substring(payload.lastIndexOf('/') + 1));
        JSONObject filed = server.calls("/v2/link/invite").get(0);
        assertEquals("inbox", filed.getString("role"));
        assertEquals(s.lookup, filed.getString("lookup"));
        assertEquals(TOKEN, filed.getString("_key"));
        // What a new device gets from the code: this inbox and its key, and nothing without the code.
        JSONObject box = Crypto.openJson(s.boxKey, server.invites.get(s.lookup), Crypto.AAD_BOX);
        assertEquals(FakeHedy.TID, box.getString("tid"));
        assertEquals(Crypto.b64u(INBOX_KEY), box.getString("key"));
        // Once it's used, the screen says so.
        server.usedInvites.add(s.lookup);
        waitFor(() -> {
            tick();
            return allText(activity.findViewById(android.R.id.content)).contains("Linked. That device has your inbox now.");
        });
    }
}
