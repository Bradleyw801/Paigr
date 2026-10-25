package com.textrelay.texts;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;

import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Texts' screens: linking this phone, the list of conversations, a conversation, its photos and files, and
 * settings, drawn by the app itself (the inbox lives in Inbox). Only a phone still on the first version's inbox shows
 * the web page instead, which moves that inbox into a private one (WebActivity).
 */
public class MainActivity extends Activity implements Inbox.Listener {
    static final String EXTRA_CONV = "conv";
    static final String VERSION = "1.2";
    /** "pending" while this phone asks the inbox whether it was linked before (a reinstall); "done" otherwise. */
    static volatile String recovery = "done";
    /** Tests set this in place of the phone's Android ID. */
    static volatile String androidIdForTests;
    private static final int REQ_NOTIFICATIONS = 1;
    static final int REQ_SCAN = 2;
    static final int REQ_ATTACH = 3;

    Ui ui;
    Inbox inbox;
    Prefs prefs;
    Linker linker;
    FrameLayout stage;
    Ui.Snack snack;
    ConnectScreen connect;
    InboxScreen list;
    ThreadScreen thread;
    DetailsScreen details;
    SettingsScreen settings;
    Viewer viewer;
    /** What's on screen, bottom first. */
    final List<Screen> stack = new ArrayList<>();
    int insetTop, insetBottom;
    /** What the scanner is open for: "link" (this phone) or "approve" (a new relay phone or another device). */
    private String scanFor;
    private boolean askedForNotifications;
    private int nightMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        if (!prefs.key().isEmpty()) {
            // Still on the first version's inbox: the page moves it into a private one.
            Intent web = new Intent(this, WebActivity.class);
            if (getIntent() != null && getIntent().getExtras() != null) web.putExtras(getIntent().getExtras());
            startActivity(web);
            finish();
            return;
        }
        nightMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        ui = new Ui(this);
        inbox = Inbox.get(this);
        linker = new Linker(inbox);
        Window w = getWindow();
        w.setDecorFitsSystemWindows(false);
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(ui.bg);
        w.getDecorView().setBackgroundColor(ui.bg);

        stage = new FrameLayout(this);
        stage.setBackgroundColor(ui.bg);
        setContentView(stage);
        snack = new Ui.Snack(ui, stage);
        stage.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
            insetTop = bars.top;
            insetBottom = Math.max(bars.bottom, ime.bottom);
            for (Screen s : all()) s.insets(insetTop, insetBottom, ime.bottom > 0);
            return WindowInsets.CONSUMED;
        });

        checkSamePhone();   // a link copied here from another phone goes before anything uses it
        inbox.useLink(prefs.link());
        inbox.listen(this);
        recoverIfNeeded();
        if (inbox.link == null) showConnect();
        else showList();
        String conv = getIntent() != null ? getIntent().getStringExtra(EXTRA_CONV) : null;
        if (conv != null && inbox.link != null) openConversation(conv);
    }

    private List<Screen> all() {
        List<Screen> out = new ArrayList<>();
        for (Screen s : new Screen[] { connect, list, thread, details, settings, viewer }) if (s != null) out.add(s);
        return out;
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        if ((c.uiMode & Configuration.UI_MODE_NIGHT_MASK) != nightMode) recreate();   // light or dark: drawn again
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String conv = intent != null ? intent.getStringExtra(EXTRA_CONV) : null;
        if (conv != null && !conv.isEmpty() && inbox.link != null) openConversation(conv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Format.refresh(this);
        if (inbox == null) return;
        SyncService.setScreenOpen(this, true);
        if (prefs.paired()) {
            SyncService.start(this);
            SyncService.wake();
        }
        if (inbox.link == null && prefs.link() != null) {
            // Linked meanwhile (getting back in after a reinstall finished while away).
            inbox.adopt(prefs.link());
            showList();
        }
        inbox.listen(this);
        inbox.resume();
        if (top() != null) top().shown();
        if (!askedForNotifications && Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askedForNotifications = true;
            requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, REQ_NOTIFICATIONS);
        }
    }

    @Override
    protected void onPause() {
        if (inbox != null) {
            SyncService.setScreenOpen(this, false);
            inbox.pause();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (inbox != null && inbox.listener == this) inbox.listen(null);
        if (viewer != null) viewer.close();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ the stack of screens

    Screen top() {
        return stack.isEmpty() ? null : stack.get(stack.size() - 1);
    }

    private void place(Screen s) {
        if (s.root.getParent() == null) {
            stage.addView(s.root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            s.insets(insetTop, insetBottom, false);
        }
    }

    /**
     * `s` on top, at once. No transition: a phone like the Sidephone has little to spare, and a screen that animates in
     * while its list is being built stutters there.
     */
    void push(Screen s) {
        place(s);
        Screen under = top();
        if (under == s) return;
        stack.remove(s);
        stack.add(s);
        s.root.setVisibility(View.VISIBLE);
        s.root.bringToFront();
        bringOverlaysToFront();
        s.shown();
        bars();
        if (under != null) under.root.setVisibility(View.GONE);
    }

    /** Back from the top screen to the one under it. */
    void pop() {
        if (stack.size() < 2) return;
        Screen s = stack.remove(stack.size() - 1);
        Screen under = top();
        under.root.setVisibility(View.VISIBLE);
        bringOverlaysToFront();
        under.shown();
        s.hidden();
        s.root.setVisibility(View.GONE);
        bars();
    }

    /** Only `s` on screen, with nothing under it. */
    void reset(Screen s) {
        place(s);
        for (Screen o : new ArrayList<>(stack)) {
            if (o != s) {
                o.hidden();
                o.root.setVisibility(View.GONE);
            }
        }
        stack.clear();
        stack.add(s);
        s.root.setVisibility(View.VISIBLE);
        s.root.bringToFront();
        bringOverlaysToFront();
        s.shown();
        bars();
    }

    private void bringOverlaysToFront() {
        if (viewer != null && viewer.isOpen()) viewer.root.bringToFront();
    }

    /** The status bar's icons: light on a conversation's colour, otherwise whatever reads on the frame. */
    void bars() {
        Screen s = top();
        boolean onColour = s != null && s.coloredTop();
        boolean light = onColour || ui.dark;
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.setSystemBarsAppearance(light ? 0 : WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
            c.setSystemBarsAppearance(ui.dark ? 0 : WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS, WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        }
    }

    // ------------------------------------------------------------------ where to go

    void showConnect() {
        if (connect == null) connect = new ConnectScreen(this);
        reset(connect);
    }

    void showList() {
        if (list == null) list = new InboxScreen(this);
        reset(list);
    }

    /** A conversation, over the list. */
    void openConversation(String conv) {
        if (list == null) showList();
        if (thread == null) thread = new ThreadScreen(this);
        if (top() != list) {
            // From a notification while something else is open: straight to it, over the list.
            while (stack.size() > 1) {
                Screen s = stack.remove(stack.size() - 1);
                s.hidden();
                s.root.setVisibility(View.GONE);
            }
        }
        thread.open(conv);
        push(thread);
    }

    void openDetails() {
        if (details == null) details = new DetailsScreen(this);
        details.open(inbox.current);
        push(details);
    }

    void openSettings() {
        if (settings == null) settings = new SettingsScreen(this);
        push(settings);
    }

    void openViewer(Model.Msg m) {
        if (viewer == null) viewer = new Viewer(this);
        place(viewer);
        viewer.open(m);
    }

    @Override
    @SuppressWarnings("deprecation")   // the screens handle back themselves; this asks them first
    public void onBackPressed() {
        if (inbox == null) {
            super.onBackPressed();
            return;
        }
        if (viewer != null && viewer.isOpen()) {
            viewer.close();
            return;
        }
        Screen s = top();
        if (s != null && s.back()) return;
        if (stack.size() > 1) {
            if (s == thread) inbox.closeThread();
            if (s == details) inbox.view = "thread";
            if (s == settings) inbox.view = "inbox";
            pop();
            return;
        }
        moveTaskToBack(true);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        Screen s = top();
        if (s != null && e.getAction() == KeyEvent.ACTION_DOWN && !(viewer != null && viewer.isOpen()) && s.typed(e)) return true;
        return super.dispatchKeyEvent(e);
    }

    // ------------------------------------------------------------------ what the inbox says

    @Override
    public void changed(int what, boolean end) {
        for (Screen s : stack) s.changed(what, end);
    }

    @Override
    public void unlinked(String message) {
        if (viewer != null) viewer.close();
        if (connect == null) connect = new ConnectScreen(this);
        connect.error(message);
        showConnect();
    }

    @Override
    public void say(String text, long ms) {
        if (snack != null) snack.show(text, ms);
    }

    @Override
    public void conversationGone() {
        while (top() != list && stack.size() > 1) pop();
        inbox.closeThread();
    }

    // ------------------------------------------------------------------ linking

    void scan(String what) {
        scanFor = what;
        startActivityForResult(new Intent(this, ScanActivity.class), REQ_SCAN);
    }

    /** Opens the phone's picker for a picture, a GIF or any file to send with the next message. */
    void pickAttachment() {
        Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
        pick.setType("*/*");
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.putExtra(Intent.EXTRA_MIME_TYPES, new String[] { "image/*", "video/*", "audio/*", "*/*" });
        try {
            startActivityForResult(Intent.createChooser(pick, "Attach"), REQ_ATTACH);
        } catch (android.content.ActivityNotFoundException e) {
            say("Nothing on this phone can pick a file.", 2500);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ATTACH) {
            android.net.Uri picked = resultCode == RESULT_OK && data != null ? data.getData() : null;
            if (picked != null && thread != null) thread.attach(picked);
            return;
        }
        if (requestCode != REQ_SCAN || inbox == null) return;
        String what = scanFor;
        scanFor = null;
        String text = resultCode == RESULT_OK && data != null ? data.getStringExtra(ScanActivity.EXTRA_TEXT) : null;
        if (text != null) {
            link(text, what);
            return;
        }
        if (resultCode != ScanActivity.RESULT_TYPE) return;
        if ("approve".equals(what) && settings != null) settings.typeCode();
        else if (connect != null) connect.typeCode();
    }

    /** A code from Relay: this phone joins (or starts) an inbox, or brings another phone into this one. */
    void link(String code, String what) {
        boolean here = !"approve".equals(what);
        linker.link(code, new Linker.Callbacks() {
            @Override
            public void busy(boolean on) {
                if (here && connect != null) connect.busy(on);
                if (!here && settings != null) settings.busy(on);
            }

            @Override
            public void failed(String text) {
                if (here && connect != null && top() == connect) connect.error(text);
                else say(text, 6000);
            }

            @Override
            public void confirm(String title, String question, String agree, Runnable yes, Runnable no) {
                boolean[] agreed = { false };
                Ui.confirm(MainActivity.this, ui, title, question, agree, false, () -> {
                    agreed[0] = true;
                    yes.run();
                }).setOnDismissListener(d -> {
                    if (!agreed[0]) no.run();
                });
            }

            @Override
            public void done(String message, boolean joined) {
                if (joined) {
                    showList();
                    inbox.resume();
                } else if (settings != null) {
                    settings.linkedAnother();
                }
                say(message, 4500);
            }
        });
    }

    /**
     * A copy of the app's data can reach another phone (backups leave it out, but a rooted phone can copy anything). A
     * link that came along isn't this phone's: Prefs drops it wherever it's read, and this phone links with a code.
     */
    private void checkSamePhone() {
        if (prefs.link() != null || !prefs.takeLinkCopied()) return;
        SyncService.stop(this);
        SyncService.notifier(this).clearAll();
    }

    /**
     * A reinstalled Texts on a phone that was linked: back in its inbox without a code, if the inbox still has it. Only
     * an install that has never been in an inbox does this (see Prefs.everLinked).
     */
    private void recoverIfNeeded() {
        if (prefs.link() != null || !prefs.key().isEmpty() || prefs.everLinked()) return;
        String id = androidIdForTests != null ? androidIdForTests : androidId(this);
        if (id == null) return;
        recovery = "pending";
        if (connect != null) connect.recovering(true);
        String server = prefs.server();
        new Thread(() -> {
            Link got = null;
            try {
                Crypto.PhoneSecrets p = Crypto.phoneSecrets(id, "inbox");
                Link l = recover(server, p);
                if (l != null && prefs.link() == null) {
                    prefs.setLink(l);
                    prefs.setWrapTag(p.tag);
                    got = l;
                }
            } catch (IOException | RuntimeException e) {
                android.util.Log.w("Texts", "couldn't ask about this phone: " + e.getMessage());
            } finally {
                recovery = "done";
            }
            Link l = got;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                if (connect != null) connect.recovering(false);
                if (l != null && inbox.link == null) {
                    inbox.adopt(l);
                    showList();
                    inbox.resume();
                    say("Linked again. Your messages are loading.", 4000);
                }
            });
        }, "texts-recover").start();
    }

    /** This phone's wrap, opened, when the inbox still has this phone; null when it doesn't. */
    static Link recover(String server, Crypto.PhoneSecrets p) throws IOException {
        JSONObject r;
        try {
            r = new Hedy(server, null).post("/v2/link/recover", new JSONObject().put("tag", p.tag));
        } catch (Hedy.ApiException e) {
            if (e.code == 404) return null;
            throw e;
        } catch (org.json.JSONException e) {
            return null;
        }
        try {
            Link l = Link.unwrap(server, p.wrapKey, r.optString("wrap"));
            new Hedy(l).get("/v2/ping");
            return l;
        } catch (java.security.GeneralSecurityException e) {
            return null;
        } catch (Hedy.ApiException e) {
            if (e.unlinked) return null;
            throw e;
        }
    }

    /** Files this phone's wrap with the inbox, so a reinstalled Texts gets back in by itself. */
    static void fileWrap(android.content.Context context, Prefs prefs, Link l) {
        String id = androidIdForTests != null ? androidIdForTests : androidId(context);
        if (id == null) return;
        try {
            Crypto.PhoneSecrets p = Crypto.phoneSecrets(id, "inbox");
            if (p.tag.equals(prefs.wrapTag())) return;
            new Hedy(l).post("/v2/wrap", new JSONObject().put("tag", p.tag).put("wrap", l.wrap(p.wrapKey)));
            if (l.sameAs(prefs.link())) prefs.setWrapTag(p.tag);
        } catch (IOException | org.json.JSONException | RuntimeException e) {
            android.util.Log.w("Texts", "couldn't file the wrap yet: " + e.getMessage());
        }
    }

    /**
     * The Android ID Texts has on this phone, which Android keeps the same when Texts is reinstalled here and makes
     * different on every other phone (and for apps signed by anyone else). Null when the phone has none worth trusting.
     */
    @android.annotation.SuppressLint("HardwareIds")
    static String androidId(android.content.Context context) {
        String id;
        try {
            id = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (RuntimeException e) {
            return null;
        }
        if (id == null) return null;
        id = id.trim().toLowerCase(java.util.Locale.US);
        if (id.length() < 8 || id.equals("9774d56d682e549c") || id.matches("0+")) return null;
        return id;
    }
}
