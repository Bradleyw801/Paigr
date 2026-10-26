package com.textrelay.texts;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import android.view.View;
import android.widget.PopupWindow;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static com.textrelay.texts.NativeAppTest.allText;
import static com.textrelay.texts.NativeAppTest.find;
import static com.textrelay.texts.NativeAppTest.waitFor;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * What a conversation offers beyond plain texts: reactions, replies that quote, the emoji panel and attachments. All
 * of it travels as what Messages already understands (text in the forms phones show as reactions, "> " quotes, and
 * sealed files the relay phone sends on), against the real stand-in inbox.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w320dp-h427dp-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ConversationFeaturesTest {
    private final List<RealInbox> servers = new ArrayList<>();
    private Application app;
    private Prefs prefs;

    @Before
    public void setUp() {
        Assume.assumeTrue("needs Node and relay-hedy", RealInbox.available());
        app = RuntimeEnvironment.getApplication();
        prefs = new Prefs(app);
        Inbox.resetForTests();
        MainActivity.androidIdForTests = "5f3a9c2e7b1d4e60";
        ScreensTest.noAnimations();
    }

    @After
    public void tearDown() {
        Inbox.resetForTests();
        MainActivity.androidIdForTests = null;
        for (RealInbox s : servers) s.close();
    }

    private RealInbox start(String scenario) throws Exception {
        RealInbox s = new RealInbox(scenario);
        servers.add(s);
        prefs.setServer(s.origin);
        Link l = s.texts();
        if (l != null) prefs.setLink(l);
        return s;
    }

    private static Model.Conv named(MainActivity a, String name) {
        for (Model.Conv c : a.inbox.convs.values()) if (name.equals(c.name)) return c;
        return null;
    }

    private static Model.Msg saying(MainActivity a, String conv, String text) {
        for (Model.Msg m : a.inbox.thread(conv)) if (text.equals(m.text)) return m;
        return null;
    }

    private static ThreadScreen.Item itemFor(MainActivity a, String k) {
        for (ThreadScreen.Item it : a.thread.itemsForTests()) if (it.m != null && it.m.k.equals(k)) return it;
        return null;
    }

    private static ThreadScreen.Item itemSaying(MainActivity a, String text) {
        for (ThreadScreen.Item it : a.thread.itemsForTests()) if (it.m != null && text.equals(it.m.text)) return it;
        return null;
    }

    static void arrivesForTests(RealInbox s, Link l, String conv, String name, String sender, String text) throws Exception {
        arrives(s, l, conv, name, sender, text);
    }

    static byte[] pngForTests(int w, int h) {
        return png(w, h);
    }

    /** A text from the other side, filed the way the relay phone files one. */
    private static void arrives(RealInbox s, Link l, String conv, String name, String sender, String text) throws Exception {
        arrives(s, l, conv, name, sender, text, System.currentTimeMillis());
    }

    /** The same, timed as the relay phone's clock had it. */
    private static void arrives(RealInbox s, Link l, String conv, String name, String sender, String text, long ts) throws Exception {
        String fp = l.fpId("test-" + ts + "-" + Crypto.hex(Crypto.random(3)));
        JSONObject payload = new JSONObject().put("text", text).put("sender", sender).put("name", name).put("group", false).put("ts", ts).put("fp", fp);
        JSONObject rec = new JSONObject().put("conv", conv).put("fp", fp).put("ts", ts).put("dir", "in").put("src", "notif").put("read", false)
                .put("e", l.seal(payload, Crypto.aadMessage(conv, "in")))
                .put("ne", l.seal(new JSONObject().put("name", name).put("group", false), Crypto.aadName(conv)))
                .put("mt", l.matchToken("in", text, ""));
        JSONObject r = s.call("POST", "/v2/relay/ingest", s.link.getString("relayToken"),
                new JSONObject().put("items", new JSONArray().put(rec)).put("replyable", new JSONObject().put(conv, true)));
        assertEquals(r.toString(), 200, r.getInt("_status"));
    }

    /** The newest thing in the relay phone's outbox, once there is one newer than `after` ids. */
    private static JSONObject newestOutbox(RealInbox s, java.util.Set<String> seen) throws Exception {
        JSONObject[] got = new JSONObject[1];
        waitFor("the outbox", () -> {
            try {
                JSONArray items = s.call("GET", "/v2/relay/outbox", s.link.getString("relayToken"), null).optJSONArray("items");
                if (items == null) return false;
                for (int i = 0; i < items.length(); i++) {
                    JSONObject it = items.getJSONObject(i);
                    if (!seen.contains(it.getString("id"))) got[0] = it;
                }
            } catch (Exception ignored) {
                // not yet
            }
            return got[0] != null;
        });
        seen.add(got[0].getString("id"));
        return got[0];
    }

    /** The bytes the stand-in server holds at a path (its test-only /_files route). */
    private static byte[] fetch(String url) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    private static PopupWindow menu() {
        PopupWindow p = Shadows.shadowOf(RuntimeEnvironment.getApplication()).getLatestPopupWindow();
        assertNotNull("the long-press menu", p);
        return p;
    }

    private static void longPress(MainActivity a, ThreadScreen.Item it) {
        int pos = a.thread.itemsForTests().indexOf(it);
        a.thread.list.setSelection(pos + a.thread.list.getHeaderViewsCount());
        ShadowLooper.idleMainLooper();
        a.thread.longPressForTests(pos);
        ShadowLooper.idleMainLooper();
    }

    private static void sent(RealInbox s, String id, String via) throws Exception {
        s.call("POST", "/v2/relay/outbox/result", s.link.getString("relayToken"), new JSONObject().put("id", id).put("status", "sent").put("via", via));
    }

    // ------------------------------------------------------------------ reactions and quoted replies

    /**
     * The forms Google writes a reaction in (Reacted 👍 to "…", with or without the quotes, a name in front, or "your
     * message"), and a reaction timed a little before the text it names (the relay phone's clock against this one's,
     * on a text sent from here) all end up as chips under that text, never as rows of their own.
     */
    @Test
    public void googlesReactionFormsAndAReactionTimedBeforeItsTextStillAttach() throws Exception {
        RealInbox s = start("demo");
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = c.get();
        waitFor("the list", () -> a.inbox.convs.size() >= 9);
        Model.Conv maya = named(a, "Maya Chen");
        a.openConversation(maya.conv);
        waitFor("Maya's thread", () -> a.inbox.thread(maya.conv).size() >= 7 && a.inbox.cursors.containsKey(maya.conv));
        Link l = prefs.link();
        java.util.Set<String> seen = new java.util.HashSet<>();
        a.thread.input.setText("See you at the north gate, by the big tree.");
        find(a.thread.root, "Send").performClick();
        ShadowLooper.idleMainLooper();
        JSONObject out = newestOutbox(s, seen);
        sent(s, out.getString("id"), "rcs");
        waitFor("sent", () -> {
            Model.Msg m = saying(a, maya.conv, "See you at the north gate, by the big tree.");
            return m != null && "sent".equals(m.status);
        });
        Model.Msg mine = saying(a, maya.conv, "See you at the north gate, by the big tree.");
        int rows = a.thread.itemsForTests().size();

        // Her reaction, as Google's notification words it, timed 20 seconds before this phone timed the text.
        arrives(s, l, maya.conv, "Maya Chen", "Maya Chen", "Reacted ❤️ to \"See you at the north gate, by the big tree.\"", mine.ts - 20_000);
        waitFor("the heart on the text", () -> {
            ThreadScreen.Item it = itemFor(a, mine.k);
            return it != null && it.chips != null && it.chips.size() == 1 && "❤️".equals(it.chips.get(0).emoji);
        });
        assertEquals(rows, a.thread.itemsForTests().size());
        // The same with her name in front and no quotes, cut short, with a full stop; and "your message".
        arrives(s, l, maya.conv, "Maya Chen", "Maya Chen", "Maya Chen reacted 👍 to See you at the north gate, by the…");
        arrives(s, l, maya.conv, "Maya Chen", "Maya Chen", "Reacted 😂 to your message.");
        waitFor("three chips", () -> {
            ThreadScreen.Item it = itemFor(a, mine.k);
            return it != null && it.chips != null && it.chips.size() == 3;
        });
        assertEquals(rows, a.thread.itemsForTests().size());
        assertNull(itemSaying(a, "Reacted 😂 to your message."));
        String screen = allText(a.thread.root);
        assertFalse(screen, screen.contains("Reacted"));
        a.onBackPressed();
        waitFor("the list again", () -> a.list.root.getVisibility() == View.VISIBLE);
        waitFor("the snippet", () -> allText(a.list.root).contains("😂 to your message"));
    }

    @Test
    public void reactionsAndQuotedRepliesTravelAsTextAndDrawAsWhatTheyAre() throws Exception {
        RealInbox s = start("demo");
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = c.get();
        waitFor("the list", () -> a.inbox.convs.size() >= 9);
        Model.Conv maya = named(a, "Maya Chen");
        a.openConversation(maya.conv);
        waitFor("Maya's thread", () -> a.inbox.thread(maya.conv).size() >= 7 && a.inbox.cursors.containsKey(maya.conv));
        Link l = prefs.link();
        Model.Msg onMyWay = saying(a, maya.conv, "On my way at 6:30");
        Model.Msg doors = saying(a, maya.conv, "Doors open at 7");
        assertNotNull(onMyWay);
        assertNotNull(doors);
        int rows = a.thread.itemsForTests().size();

        // Maya's phone reacts, as an iPhone writes it: no row of its own, a heart under the message it names.
        arrives(s, l, maya.conv, "Maya Chen", "Maya Chen", "Loved “On my way at 6:30”");
        waitFor("the heart", () -> {
            ThreadScreen.Item it = itemFor(a, onMyWay.k);
            return it != null && it.chips != null && it.chips.size() == 1 && "❤️".equals(it.chips.get(0).emoji);
        });
        assertEquals(rows, a.thread.itemsForTests().size());
        assertNull(itemSaying(a, "Loved “On my way at 6:30”"));
        ThreadScreen.Item reacted = itemFor(a, onMyWay.k);
        assertEquals(1, reacted.chips.get(0).count);
        assertFalse(reacted.chips.get(0).mine);
        assertEquals("Maya Chen", reacted.chips.get(0).by.get(0));
        String screen = allText(a.thread.root);
        assertTrue(screen, screen.contains("❤️"));
        assertFalse(screen, screen.contains("Loved"));
        // The list says it in a few words, never the whole form.
        a.onBackPressed();
        waitFor("the list again", () -> a.list.root.getVisibility() == View.VISIBLE);
        waitFor("the snippet", () -> allText(a.list.root).contains("❤️ to “On my way at 6:30”"));
        a.openConversation(maya.conv);

        // Maya answers one message in particular: her reply shows what it answers, and tapping that jumps there.
        arrives(s, l, maya.conv, "Maya Chen", "Maya Chen", "> On my way at 6:30\nPerfect, see you!");
        waitFor("the quoted reply", () -> itemSaying(a, "> On my way at 6:30\nPerfect, see you!") != null);
        ThreadScreen.Item quoted = itemSaying(a, "> On my way at 6:30\nPerfect, see you!");
        assertNotNull(quoted.quote);
        assertEquals("On my way at 6:30", quoted.quote.quoted);
        assertEquals("Perfect, see you!", quoted.quote.body);
        assertEquals(onMyWay.k, quoted.quoteKey);
        assertEquals("You", quoted.quoteWho);
        screen = allText(a.thread.root);
        assertTrue(screen, screen.contains("Perfect, see you!"));
        assertFalse(screen, screen.contains("> On my way"));

        // Reacting from here: a long press offers the quick six; the text goes out in the form their phone shows as a reaction.
        java.util.Set<String> seen = new java.util.HashSet<>();
        longPress(a, itemFor(a, doors.k));
        View thumbs = find(menu().getContentView(), "React 👍");
        assertNotNull(thumbs);
        assertNotNull(find(menu().getContentView(), "Reply"));
        thumbs.performClick();
        ShadowLooper.idleMainLooper();
        JSONObject out = newestOutbox(s, seen);
        JSONObject o = l.open(out.getString("o"), Crypto.aadOut(maya.conv));
        assertEquals("Liked “Doors open at 7”", o.getString("text"));
        waitFor("our thumbs up drawn as a chip", () -> {
            ThreadScreen.Item it = itemFor(a, doors.k);
            return it != null && it.chips != null && it.chips.size() == 1 && it.chips.get(0).mine;
        });
        assertNull(itemSaying(a, "Liked “Doors open at 7”"));
        sent(s, out.getString("id"), "sms");
        waitFor("sent", () -> {
            Model.Msg m = saying(a, maya.conv, "Liked “Doors open at 7”");
            return m != null && "sent".equals(m.status);
        });
        assertNull("a sent reaction stays folded into its message", itemSaying(a, "Liked “Doors open at 7”"));

        // Tapping our own chip takes it back.
        ThreadScreen.Item withChip = itemFor(a, doors.k);
        longPress(a, withChip);
        View mine = find(menu().getContentView(), "Remove 👍");
        assertNotNull(mine);
        mine.performClick();
        ShadowLooper.idleMainLooper();
        out = newestOutbox(s, seen);
        assertEquals("Removed a like from “Doors open at 7”", l.open(out.getString("o"), Crypto.aadOut(maya.conv)).getString("text"));
        waitFor("the chip gone", () -> {
            ThreadScreen.Item it = itemFor(a, doors.k);
            return it != null && it.chips == null;
        });

        // Replying to one message: the composer shows what it answers, and the text goes out quoting it.
        longPress(a, itemFor(a, doors.k));
        find(menu().getContentView(), "Reply").performClick();
        ShadowLooper.idleMainLooper();
        assertEquals(doors.k, a.thread.replyToForTests().k);
        String composer = allText(a.thread.root);
        assertTrue(composer, composer.contains("Replying to Maya Chen"));
        assertTrue(composer, composer.contains("Doors open at 7"));
        a.thread.input.setText("On it");
        find(a.thread.root, "Send").performClick();
        ShadowLooper.idleMainLooper();
        assertNull(a.thread.replyToForTests());
        out = newestOutbox(s, seen);
        assertEquals("> Doors open at 7\nOn it", l.open(out.getString("o"), Crypto.aadOut(maya.conv)).getString("text"));
        waitFor("our reply drawn with its quote", () -> {
            ThreadScreen.Item it = itemSaying(a, "> Doors open at 7\nOn it");
            return it != null && it.quote != null && doors.k.equals(it.quoteKey);
        });
        assertEquals("Maya Chen", itemSaying(a, "> Doors open at 7\nOn it").quoteWho);

        // The emoji panel types into the reply; Back closes it.
        assertFalse(a.thread.emojisOpenForTests());
        find(a.thread.root, "Emoji").performClick();
        ShadowLooper.idleMainLooper();
        assertTrue(a.thread.emojisOpenForTests());
        a.thread.pickEmojiForTests("😂");
        assertEquals("😂", a.thread.input.getText().toString());
        a.onBackPressed();
        assertFalse(a.thread.emojisOpenForTests());
        assertEquals(View.VISIBLE, a.thread.root.getVisibility());
        c.pause().stop().destroy();
    }

    // ------------------------------------------------------------------ marking read

    @Test
    public void markAllAsReadClearsEveryCountHereAndOnTheInboxAndTheNotifications() throws Exception {
        RealInbox s = start("demo");
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = c.get();
        waitFor("the list", () -> a.inbox.convs.size() >= 9);
        Model.Conv maya = named(a, "Maya Chen");
        Model.Conv crew = named(a, "Climbing crew");
        assertEquals(2, maya.unread);
        assertEquals(3, crew.unread);
        // Two notifications stand, as if the texts had arrived while the app was closed.
        android.app.NotificationManager nm = app.getSystemService(android.app.NotificationManager.class);
        SyncService.notifier(app).show(java.util.Arrays.asList(
                new JSONObject().put("conv", maya.conv).put("name", "Maya Chen").put("sender", "Maya Chen").put("text", "Doors open at 7").put("ts", System.currentTimeMillis()),
                new JSONObject().put("conv", crew.conv).put("name", "Climbing crew").put("sender", "Jordan").put("group", true).put("text", "Who needs a ride?").put("ts", System.currentTimeMillis())));
        assertEquals(3, Shadows.shadowOf(nm).getAllNotifications().size());   // two conversations and the summary

        // One conversation from its row's long press: only that one.
        android.widget.ListView rows = a.list.list;
        int at = -1;
        for (int i = 0; i < rows.getAdapter().getCount(); i++) if (rows.getAdapter().getItem(i) == crew || ((Model.Conv) rows.getAdapter().getItem(i)).conv.equals(crew.conv)) at = i;
        assertTrue(at >= 0);
        rows.setSelection(at);
        ShadowLooper.idleMainLooper();
        rows.getOnItemLongClickListener().onItemLongClick(rows, rows.getChildAt(Math.max(0, at - rows.getFirstVisiblePosition())), at, at);
        View one = find(menu().getContentView(), "Mark as read");
        assertNotNull(one);
        one.performClick();
        ShadowLooper.idleMainLooper();
        assertEquals(0, named(a, "Climbing crew").unread);
        assertEquals(2, named(a, "Maya Chen").unread);
        assertNull(Shadows.shadowOf(nm).getNotification(Notifier.idFor(crew.conv)));
        assertNotNull(Shadows.shadowOf(nm).getNotification(Notifier.idFor(maya.conv)));

        // Everything, from the list's menu: counts here, counts on the inbox, and the notifications.
        find(a.list.root, "More").performClick();
        ShadowLooper.idleMainLooper();
        View all = find(menu().getContentView(), "Mark all as read");
        assertNotNull(all);
        all.performClick();
        ShadowLooper.idleMainLooper();
        for (Model.Conv cv : a.inbox.convs.values()) assertEquals(cv.name, 0, cv.unread);
        assertEquals(0, Shadows.shadowOf(nm).getAllNotifications().size());
        String screen = allText(a.list.root);
        assertFalse(screen, screen.contains("marked read") && !screen.contains("1 conversation"));
        waitFor("the inbox's counts", () -> {
            try {
                JSONObject convs = s.dump().getJSONObject("v2-convs");
                for (java.util.Iterator<String> k = convs.keys(); k.hasNext(); ) if (convs.getJSONObject(k.next()).optInt("unread", 0) > 0) return false;
                return true;
            } catch (Exception e) {
                return false;
            }
        });
        // A sync afterwards brings nothing back.
        waitFor("a sync", () -> a.inbox.convs.size() >= 9);
        for (Model.Conv cv : a.inbox.convs.values()) assertEquals(cv.name, 0, cv.unread);
        // With nothing unread the menu says so, and offers nothing to do.
        find(a.list.root, "More").performClick();
        ShadowLooper.idleMainLooper();
        assertNotNull(find(menu().getContentView(), "Nothing unread"));
        assertFalse(find(menu().getContentView(), "Nothing unread").isEnabled());
        menu().dismiss();
        c.pause().stop().destroy();
    }

    // ------------------------------------------------------------------ attachments

    private static byte[] png(int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        cv.drawColor(Color.rgb(246, 160, 92));
        android.graphics.Paint p = new android.graphics.Paint();
        p.setColor(Color.rgb(92, 64, 160));
        cv.drawCircle(w / 2f, h / 2f, Math.min(w, h) / 3f, p);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, out);
        return out.toByteArray();
    }

    /** A stream of `n` zero bytes, without holding them. */
    private static InputStream zeros(long n) {
        return new InputStream() {
            long left = n;

            @Override
            public int read() {
                if (left <= 0) return -1;
                left--;
                return 0;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (left <= 0) return -1;
                int k = (int) Math.min(len, left);
                java.util.Arrays.fill(b, off, off + k, (byte) 0);
                left -= k;
                return k;
            }
        };
    }

    @Test
    public void aPictureGoesOutSealedAndTheRelayPhoneIsToldWhereItIs() throws Exception {
        RealInbox s = start("demo");
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = c.get();
        waitFor("the list", () -> a.inbox.convs.size() >= 9);
        Model.Conv maya = named(a, "Maya Chen");
        a.openConversation(maya.conv);
        waitFor("Maya's thread", () -> a.inbox.thread(maya.conv).size() >= 7 && a.inbox.cursors.containsKey(maya.conv));
        Link l = prefs.link();
        byte[] picture = png(640, 480);
        Uri uri = Uri.parse("content://com.example.pick/photos/market.png");
        Shadows.shadowOf(app.getContentResolver()).registerInputStreamSupplier(uri, () -> new ByteArrayInputStream(picture));

        // Picked: it waits in the composer, named and sized, until the message goes.
        assertNull(a.thread.pendingForTests());
        a.thread.attach(uri);
        ShadowLooper.idleMainLooper();
        assertNotNull(a.thread.pendingForTests());
        assertEquals("image/png", a.thread.pendingForTests().mime);
        String composer = allText(a.thread.root);
        assertTrue(composer, composer.contains("market.png"));
        assertTrue(composer, composer.contains("Picture"));
        find(a.thread.root, "Remove the attachment").performClick();
        assertNull(a.thread.pendingForTests());
        a.thread.attach(uri);
        ShadowLooper.idleMainLooper();

        a.thread.input.setText("From the market");
        find(a.thread.root, "Send").performClick();
        ShadowLooper.idleMainLooper();
        assertNull(a.thread.pendingForTests());
        // Drawn at once from this phone's copy, while it's sealed and stored.
        waitFor("the picture row", () -> {
            ThreadScreen.Item it = itemSaying(a, "From the market");
            return it != null && it.m.media != null && it.m.media.localUri != null && "Sending…".equals(it.status);
        });

        java.util.Set<String> seen = new java.util.HashSet<>();
        JSONObject out = newestOutbox(s, seen);
        JSONObject o = l.open(out.getString("o"), Crypto.aadOut(maya.conv));
        assertEquals("From the market", o.getString("text"));
        JSONObject media = o.getJSONObject("media");
        assertEquals("image/png", media.getString("mime"));
        assertEquals("market.png", media.getString("name"));
        assertEquals(picture.length, media.getInt("bytes"));
        String path = media.getString("path");
        assertTrue(path, path.startsWith("/media/v2/" + l.tid + "/" + maya.conv + "/") && path.endsWith("-o.enc"));
        // Every inbox device sees the same file, by the record the message carries: sealed for this conversation and message.
        JSONObject record = null;
        JSONObject held = s.dump().getJSONObject("v2-messages");
        for (java.util.Iterator<String> keys = held.keys(); keys.hasNext(); ) {
            JSONObject v = held.getJSONObject(keys.next());
            if (v.optJSONObject("media") != null && path.equals(v.getJSONObject("media").optString("path"))) record = v;
        }
        assertNotNull("the inbox holds the message by its file", record);
        JSONObject shown = l.open(record.getString("e"), Crypto.aadMessage(maya.conv, "out"));
        assertEquals(640, shown.getJSONObject("media").getInt("w"));
        assertEquals(480, shown.getJSONObject("media").getInt("h"));
        String fp = shown.getString("fp");
        assertTrue(path, path.contains("-" + fp + "-o.enc"));
        byte[] stored = fetch(s.origin + "/_files" + path);
        assertFalse("the server holds nothing of the picture plain", new String(stored, java.nio.charset.StandardCharsets.ISO_8859_1).contains("IHDR"));
        byte[] back = l.openFile(stored, Crypto.aadFile(maya.conv, shown.getLong("ts"), fp, "original"));
        assertEquals(picture.length, back.length);

        // The relay phone sent it as MMS: the thread says so.
        sent(s, out.getString("id"), "mms");
        waitFor("sent as MMS", () -> allText(a.thread.root).contains("Sent as MMS"));

        // A file too big for the inbox is refused here, in words, before anything is uploaded.
        Uri huge = Uri.parse("content://com.example.pick/files/holiday.mov");
        Shadows.shadowOf(app.getContentResolver()).registerInputStreamSupplier(huge, () -> zeros(Inbox.ATTACH_MAX + 1));
        a.thread.attach(huge);
        ShadowLooper.idleMainLooper();
        assertNotNull(a.thread.pendingForTests());   // the picker didn't say its size, so it's read as it goes out
        find(a.thread.root, "Send").performClick();
        ShadowLooper.idleMainLooper();
        waitFor("the refusal", () -> allText(a.thread.root).contains("too big to send (25 MB at most)"));
        c.pause().stop().destroy();
    }
}
