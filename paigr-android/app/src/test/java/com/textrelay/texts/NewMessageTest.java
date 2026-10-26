package com.textrelay.texts;

import static com.textrelay.texts.NativeAppTest.allText;
import static com.textrelay.texts.NativeAppTest.find;
import static com.textrelay.texts.NativeAppTest.waitFor;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.view.View;

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
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.List;

/**
 * New message: a conversation started here with a number. The conversation is keyed from the number the way the relay
 * phone keys it, the first text carries the number inside its sealed request (and the conversation's name for the
 * inbox), and a number already listed opens its own conversation. When the relay phone folds the new conversation into
 * one it already had with that number, the screen follows it there.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w320dp-h427dp-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class NewMessageTest {
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

    private static ThreadScreen.Item itemSaying(MainActivity a, String text) {
        for (ThreadScreen.Item it : a.thread.itemsForTests()) if (it.m != null && text.equals(it.m.text)) return it;
        return null;
    }

    /** The newest thing in the relay phone's outbox, once there is one not seen before. */
    private static JSONObject newestOutbox(RealInbox s, java.util.Set<String> seen) throws Exception {
        JSONObject[] got = new JSONObject[1];
        waitFor("the outbox", () -> {
            try {
                JSONArray items = s.call("GET", "/v2/relay/outbox", s.link.getString("relayToken"), null).optJSONArray("items");
                for (int i = 0; items != null && i < items.length(); i++) {
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

    /** The inbox's own listing of a conversation, as the server keeps it. */
    private static JSONObject listed(RealInbox s, String conv) throws Exception {
        JSONArray items = s.call("GET", "/v2/inbox", s.link.getString("token"), null).optJSONArray("items");
        for (int i = 0; items != null && i < items.length(); i++) if (conv.equals(items.getJSONObject(i).optString("conv"))) return items.getJSONObject(i);
        return null;
    }

    private static MainActivity open(RealInbox s) throws Exception {
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = c.get();
        waitFor("the list", () -> a.inbox.convs.size() >= 9 && a.inbox.loadedInbox);
        return a;
    }

    /** New message, from the list's button, with a number typed in. */
    private static void compose(MainActivity a, String number) throws Exception {
        find(a.list.root, "New message").performClick();
        ShadowLooper.idleMainLooper();
        assertSame(a.compose, a.top());
        assertEquals("compose", a.inbox.view);
        a.compose.to.setText(number);
        find(a.compose.root, "Next").performClick();
        ShadowLooper.idleMainLooper();
    }

    @Test
    public void aTextToANewNumberStartsAConversationThatTheRelayPhoneSendsAsSms() throws Exception {
        RealInbox s = start("demo");
        MainActivity a = open(s);
        Link l = prefs.link();
        int before = a.inbox.convs.size();
        compose(a, "(801) 555-0177");
        // Straight into the conversation, keyed from the number as the relay phone will key it, named for the number.
        assertSame(a.thread, a.top());
        String conv = l.convIdForTests("to:8015550177");
        assertEquals(conv, a.inbox.current);
        assertEquals("8015550177", a.inbox.startedWith(conv));
        Model.Conv c = a.inbox.convs.get(conv);
        assertNotNull(c);
        assertEquals("(801) 555-0177", c.name);
        assertEquals(before + 1, a.inbox.convs.size());
        String screen = allText(a.thread.root);
        assertTrue(screen, screen.contains("(801) 555-0177"));
        assertTrue(screen, screen.contains("Your first text to (801) 555-0177 goes out as SMS"));
        assertTrue("nothing in it yet", a.inbox.thread(conv).isEmpty());

        // The first text: the request names the number inside its seal; the inbox gets the conversation, with its name.
        java.util.Set<String> seen = new java.util.HashSet<>();
        a.thread.input.setText("Hi, is the bike still for sale?");
        find(a.thread.root, "Send").performClick();
        ShadowLooper.idleMainLooper();
        JSONObject out = newestOutbox(s, seen);
        assertEquals(conv, out.getString("conv"));
        JSONObject request = l.open(out.getString("o"), Crypto.aadOut(conv));
        assertEquals("Hi, is the bike still for sale?", request.getString("text"));
        assertEquals("8015550177", request.getString("to"));
        JSONObject summary = listed(s, conv);
        assertNotNull("the inbox lists it now", summary);
        assertEquals("(801) 555-0177", l.open(summary.getString("ne"), Crypto.aadName(conv)).getString("name"));
        assertTrue(summary.optBoolean("started"));
        String everything = s.dump().toString();
        assertFalse("the number never shows on the server", everything.contains("8015550177"));
        waitFor("the message in the thread", () -> {
            Model.Msg m = saying(a, conv, "Hi, is the bike still for sale?");
            return m != null && !m.temp;
        });
        assertFalse("the first-text notice is gone", allText(a.thread.root).contains("Your first text"));
        s.call("POST", "/v2/relay/outbox/result", s.link.getString("relayToken"),
                new JSONObject().put("id", out.getString("id")).put("status", "sent").put("via", "sms").put("canReply", false));
        waitFor("sent as SMS", () -> {
            ThreadScreen.Item it = itemSaying(a, "Hi, is the bike still for sale?");
            return it != null && "Sent as SMS".equals(it.status);
        });
        // No open reply on the relay phone, but SMS fallback is on: no "wait for their next text" notice.
        s.call("POST", "/v2/relay/heartbeat", s.link.getString("relayToken"), new JSONObject().put("listening", true).put("smsFallback", true));
        waitFor("the relay's state", () -> a.inbox.relay != null && Boolean.TRUE.equals(a.inbox.relay.smsFallback));
        a.changed(Inbox.LIST | Inbox.THREAD, false);
        assertFalse(allText(a.thread.root), allText(a.thread.root).contains("You can reply after"));

        // Back on the list: there it is, newest, with the text. And still there after the app starts again.
        a.onBackPressed();
        waitFor("the list", () -> a.list.root.getVisibility() == View.VISIBLE);
        Model.Conv top = a.inbox.list("").get(0);
        assertEquals(conv, top.conv);
        assertEquals("out", top.lastDir);
        assertEquals("Hi, is the bike still for sale?", top.lastText);
        assertTrue(allText(a.list.root).contains("You: Hi, is the bike still for sale?"));
        a.inbox.reloadEverything(null);
        waitFor("reloaded", () -> a.inbox.loadedInbox && a.inbox.convs.containsKey(conv));
        assertEquals("(801) 555-0177", a.inbox.convs.get(conv).name);
    }

    @Test
    public void aNumberAlreadyListedOpensItsConversation() throws Exception {
        RealInbox s = start("demo");
        MainActivity a = open(s);
        int before = a.inbox.convs.size();
        Model.Conv code = named(a, "72975");
        assertNotNull(code);
        compose(a, "72975");
        assertSame(a.thread, a.top());
        assertEquals(code.conv, a.inbox.current);
        assertNull(a.inbox.startedWith(code.conv));
        assertEquals(before, a.inbox.convs.size());
        waitFor("its thread", () -> !a.inbox.thread(code.conv).isEmpty());
        assertFalse(allText(a.thread.root).contains("Your first text"));
        a.onBackPressed();
        waitFor("the list", () -> a.list.root.getVisibility() == View.VISIBLE);

        // The same number again, typed another way, is the same conversation; nothing typed is no conversation.
        compose(a, "+18015550177");
        String conv = a.inbox.current;
        a.onBackPressed();
        waitFor("the list", () -> a.list.root.getVisibility() == View.VISIBLE);
        assertFalse("left without a word: not listed", a.inbox.convs.containsKey(conv));
        assertEquals(before, a.inbox.convs.size());
        compose(a, "801-555-0177");
        assertEquals(conv, a.inbox.current);
        a.onBackPressed();
        waitFor("the list", () -> a.list.root.getVisibility() == View.VISIBLE);
        find(a.list.root, "New message").performClick();
        ShadowLooper.idleMainLooper();
        a.compose.to.setText("call me maybe");
        find(a.compose.root, "Next").performClick();
        ShadowLooper.idleMainLooper();
        assertSame("still composing", a.compose, a.top());
        a.onBackPressed();
        waitFor("the list", () -> a.list.root.getVisibility() == View.VISIBLE);
        assertEquals("inbox", a.inbox.view);
        assertEquals(before, a.inbox.convs.size());
    }

    /** The phone's contact picker, as Robolectric can stand it in: one row, with a number and a name. */
    public static class FakeContacts extends ContentProvider {
        @Override
        public boolean onCreate() {
            return true;
        }

        @Override
        public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
            MatrixCursor c = new MatrixCursor(new String[] { ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME });
            if ("7".equals(uri.getLastPathSegment())) c.addRow(new Object[] { "+1 801-555-0142", "Jules Park" });
            return c;
        }

        @Override
        public String getType(Uri uri) {
            return ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE;
        }

        @Override
        public Uri insert(Uri uri, ContentValues values) {
            return null;
        }

        @Override
        public int delete(Uri uri, String selection, String[] args) {
            return 0;
        }

        @Override
        public int update(Uri uri, ContentValues values, String selection, String[] args) {
            return 0;
        }
    }

    @Test
    public void aContactPickedGivesTheNumberAndItsNameToTheConversation() throws Exception {
        RealInbox s = start("demo");
        MainActivity a = open(s);
        Link l = prefs.link();
        ContentProvider contacts = Robolectric.buildContentProvider(FakeContacts.class).create(ContactsContract.AUTHORITY).get();
        ShadowContentResolver.registerProviderInternal(ContactsContract.AUTHORITY, contacts);
        find(a.list.root, "New message").performClick();
        ShadowLooper.idleMainLooper();
        find(a.compose.root, "Pick a contact").performClick();
        Intent asked = org.robolectric.Shadows.shadowOf(a).getNextStartedActivityForResult().intent;
        assertEquals(Intent.ACTION_PICK, asked.getAction());
        assertEquals(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, asked.getData());
        Uri row = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, "7");
        a.onActivityResult(MainActivity.REQ_CONTACT, Activity.RESULT_OK, new Intent().setData(row));
        assertEquals("+1 801-555-0142", a.compose.to.getText().toString());
        find(a.compose.root, "Next").performClick();
        ShadowLooper.idleMainLooper();
        String conv = l.convIdForTests("to:+18015550142");
        assertEquals(conv, a.inbox.current);
        assertEquals("Jules Park", a.inbox.convs.get(conv).name);
        assertTrue(allText(a.thread.root).contains("Jules Park"));
        assertTrue(allText(a.thread.root).contains("(801) 555-0142"));
        java.util.Set<String> seen = new java.util.HashSet<>();
        a.thread.input.setText("Hey Jules");
        find(a.thread.root, "Send").performClick();
        ShadowLooper.idleMainLooper();
        JSONObject out = newestOutbox(s, seen);
        assertEquals("+18015550142", l.open(out.getString("o"), Crypto.aadOut(conv)).getString("to"));
        assertEquals("Jules Park", l.open(listed(s, conv).getString("ne"), Crypto.aadName(conv)).getString("name"));
        // A contact without a number can't be texted.
        a.onBackPressed();
        waitFor("the list", () -> a.list.root.getVisibility() == View.VISIBLE);
        find(a.list.root, "New message").performClick();
        ShadowLooper.idleMainLooper();
        a.onActivityResult(MainActivity.REQ_CONTACT, Activity.RESULT_OK, new Intent().setData(Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, "8")));
        assertEquals("", a.compose.to.getText().toString());
    }

    @Test
    public void whenTheRelayPhoneFoldsItIntoTheirConversationTheScreenFollows() throws Exception {
        RealInbox s = start("demo");
        MainActivity a = open(s);
        Link l = prefs.link();
        Model.Conv maya = named(a, "Maya Chen");
        compose(a, "801 555 0199");
        String conv = l.convIdForTests("to:8015550199");
        assertEquals(conv, a.inbox.current);
        java.util.Set<String> seen = new java.util.HashSet<>();
        a.thread.input.setText("Is this Maya?");
        find(a.thread.root, "Send").performClick();
        ShadowLooper.idleMainLooper();
        JSONObject out = newestOutbox(s, seen);
        // The relay phone knows that number as Maya's: it sent the text through her conversation and folds this one into it.
        s.call("POST", "/v2/relay/outbox/result", s.link.getString("relayToken"),
                new JSONObject().put("id", out.getString("id")).put("status", "sent").put("via", "rcs").put("canReply", true));
        JSONObject merged = s.call("POST", "/v2/relay/merge", s.link.getString("relayToken"),
                new JSONObject().put("from", conv).put("into", maya.conv).put("mr", l.mergeRecordForTests(conv, maya.conv)));
        assertEquals(merged.toString(), 200, merged.getInt("_status"));
        waitFor("Maya's conversation on screen instead", () -> maya.conv.equals(a.inbox.current) && a.top() == a.thread
                && saying(a, maya.conv, "Is this Maya?") != null);
        assertFalse(a.inbox.convs.containsKey(conv));
        assertTrue(allText(a.thread.root).contains("Maya Chen"));
        assertNotNull(itemSaying(a, "Is this Maya?"));
        waitFor("the word", () -> allText(a.getWindow().getDecorView()).contains("That number is Maya Chen: continued here."));
        // Texting the number again goes to Maya's conversation now.
        a.onBackPressed();
        waitFor("the list", () -> a.list.root.getVisibility() == View.VISIBLE);
        compose(a, "(801) 555-0199");
        assertEquals(maya.conv, a.inbox.current);
    }
}
