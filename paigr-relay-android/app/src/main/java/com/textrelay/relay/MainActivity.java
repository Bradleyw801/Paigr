package com.textrelay.relay;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.IOException;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * Relay's one screen: how it's doing, in a word, and what (if anything) it still needs. Not linked yet, it shows its
 * code for Texts to scan. Linked, it can link another device and sync now. Everything else is under More.
 */
public class MainActivity extends Activity {
    static final int ASK_NOTIFICATIONS = 1, ASK_SMS = 2, ASK_STORE = 3;

    private Relay relay;
    private Prefs prefs;
    private Ui ui;
    private LinearLayout hero;
    private Ui.Light heroLight;
    private TextView heroWord;
    private TextView heroLine;
    private TextView details;
    private LinearLayout actions;
    private TextView syncButton;
    private ConnectPanel connect;
    private LinkPanel link;
    private TextView todoHead;
    private LinearLayout todo;
    private Ui.Row accessRow;
    private Ui.Row infoRow;
    private Ui.Row batteryRow;
    private Ui.Row historyRow;
    private Ui.Row smsRow;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 2_000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        relay = Relay.get(this);
        prefs = relay.prefs;
        ui = new Ui(this);
        setContentView(build());
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, ASK_NOTIFICATIONS);
        }
        if (prefs.configured()) {
            RelayService.start(this);
            Watchdog.check(this);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(tick);
        connect.resume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
        connect.pause();
    }

    @Override
    protected void onDestroy() {
        link.stop();
        connect.stop();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == ASK_STORE && relay.storeSync.allowed()) {
            relay.storeSync.start();
            relay.storeSync.request(StoreSync.DAYS_2, false);
            relay.historySync.resume();
        }
        refresh();
    }

    private View build() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(ui.dp(14), ui.dp(4), ui.dp(14), ui.dp(24));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(ui.dp(4), 0, 0, 0);
        top.setMinimumHeight(ui.dp(58));
        TextView title = ui.label("Paigr Relay", 27f, Ui.BLACK, ui.text);
        title.setLetterSpacing(-0.025f);
        top.addView(title);
        LinearLayout.LayoutParams betaLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        betaLp.setMarginStart(ui.dp(9));
        top.addView(ui.tag("BETA", Ui.BRAND.base), betaLp);
        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        top.addView(ui.iconButton(R.drawable.ic_gear, "More", ui.text, v -> startActivity(new Intent(this, MoreActivity.class))));
        col.addView(top);

        // ---- how it's doing, in a word (tap for the details)
        hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(ui.dp(18), ui.dp(18), ui.dp(18), ui.dp(16));
        hero.setBackground(ui.pressable(ui.gradient(Ui.BRAND.hi, Ui.BRAND.partner, GradientDrawable.Orientation.TR_BL, 26), 26));
        hero.setFocusable(true);
        hero.setClickable(true);
        hero.setOnClickListener(v -> {
            if (relay.torDown) {
                // What it says to do: start Tor (or get Orbot), from right here
                if (Tor.orbotInstalled(this)) Tor.startOrbot(this);
                else Tor.getOrbot(this);
                return;
            }
            details.setVisibility(details.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        });
        heroLight = new Ui.Light(this);
        FrameLayout beacon = new FrameLayout(this);
        beacon.setBackground(ui.rounded(0x29FFFFFF, 27));
        beacon.addView(heroLight, new FrameLayout.LayoutParams(ui.dp(34), ui.dp(34), Gravity.CENTER));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ui.dp(54), ui.dp(54));
        blp.bottomMargin = ui.dp(12);
        hero.addView(beacon, blp);
        heroWord = ui.label("", 26f, Ui.BLACK, Color.WHITE);
        heroWord.setLetterSpacing(-0.02f);
        hero.addView(heroWord);
        heroLine = ui.label("", 14.5f, Ui.BOLD, 0xE6FFFFFF);
        heroLine.setPadding(0, ui.dp(3), 0, 0);
        hero.addView(heroLine);
        details = ui.label("", 13f, Ui.MEDIUM, 0xDDFFFFFF);
        details.setPadding(0, ui.dp(12), 0, 0);
        details.setLineSpacing(ui.dp(3), 1f);
        details.setVisibility(View.GONE);
        hero.addView(details);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = ui.dp(4);
        col.addView(hero, hlp);

        // ---- not linked yet: this phone's code, for Texts to scan
        connect = new ConnectPanel(this, relay, ui, this::onConnected);
        col.addView(connect.view());

        // ---- linked: link another device, sync now
        actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        link = new LinkPanel(this, relay, ui);
        TextView linkButton = link.button();
        actions.addView(linkButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        syncButton = ui.secondary("Sync now", R.drawable.ic_sync, v -> syncNow());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        slp.setMarginStart(ui.dp(8));
        actions.addView(syncButton, slp);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = ui.dp(12);
        col.addView(actions, alp);
        col.addView(link.view());

        // ---- what it still needs: only what's missing
        todoHead = ui.section("Finish setting up");
        col.addView(todoHead);
        todo = ui.group();
        accessRow = ui.row(R.drawable.ic_bell, ui.warn, "Notification access", v -> openListenerSettings());
        accessRow.sub("How it reads your texts");
        ui.endValue(accessRow, "Turn on", true);
        todo.addView(accessRow.view);
        infoRow = ui.row(R.drawable.ic_info, ui.text3, "Android says it's restricted?", v -> openAppInfo());
        infoRow.sub("App info, ⋮ menu, Allow restricted settings");
        ui.endValue(infoRow, "", true);
        todo.addView(infoRow.view);
        batteryRow = ui.row(R.drawable.ic_charge, ui.warn, "Background use", v -> askBatteryExemption());
        batteryRow.sub("So Android never pauses it");
        ui.endValue(batteryRow, "Allow", true);
        todo.addView(batteryRow.view);
        col.addView(todo);

        // ---- the rest
        col.addView(ui.section("This phone"));
        LinearLayout more = ui.group();
        historyRow = ui.row(R.drawable.ic_history, Ui.BRAND.hi, "Message history", v -> startActivity(new Intent(this, MoreActivity.class)));
        more.addView(historyRow.view);
        smsRow = ui.row(R.drawable.ic_sms, Ui.BRAND.hi, "SMS fallback", v -> startActivity(new Intent(this, MoreActivity.class)));
        more.addView(smsRow.view);
        Ui.Row guide = ui.row(R.drawable.ic_home, Ui.BRAND.hi, "Leave this phone at home", v -> startActivity(new Intent(this, WizardActivity.class)));
        guide.sub("Calls, charging and Wi-Fi, step by step");
        ui.endValue(guide, "", true);
        more.addView(guide.view);
        col.addView(more);

        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        scroll.setBackgroundColor(ui.bg);
        scroll.setClipToPadding(false);
        scroll.addView(col);
        return scroll;
    }

    private void onConnected() {
        RelayService.start(this);
        Watchdog.schedule(this);
        Toast.makeText(this, "Connected to your private inbox", Toast.LENGTH_SHORT).show();
        refresh();
    }

    void refresh() {
        long now = System.currentTimeMillis();
        NotificationManager nm = getSystemService(NotificationManager.class);
        boolean access = nm.isNotificationListenerAccessGranted(new ComponentName(this, RelayListenerService.class));
        boolean unrestricted = getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
        boolean linked = prefs.link() != null;
        boolean configured = prefs.configured();
        boolean contacts = checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED;
        boolean store = relay.storeSync.allowed();
        boolean sms = relay.canSendSms();

        connect.refresh();

        // The card: one word for how it's doing, one line on why.
        String word;
        String line;
        int light = Color.WHITE;
        boolean trying = false;
        if (!configured) {
            word = "Not linked";
            line = "Scan the code below with Paigr";
        } else if (relay.torDown) {
            word = "Tor isn't running";
            line = Tor.orbotInstalled(this) ? "Tap to start Orbot. Texts wait here until then." : "Tap to get Orbot. Texts wait here until then.";
            light = 0xFFFFD27A;
            trying = true;
        } else if (!access) {
            word = "Paused";
            line = "Turn on notification access below";
            light = 0xFFFFD27A;
        } else if (linked && !prefs.verified()) {
            word = "Connecting";
            line = "Waiting for the first check-in";
            trying = true;
        } else if (relay.lastError != null && !relay.doorbellConnected() && now - relay.lastSync > 5 * 60_000L) {
            word = "Can't reach the inbox";
            line = "It keeps trying. Check Wi-Fi or data.";
            light = 0xFFFFD27A;
            trying = true;
        } else {
            word = "Relaying";
            line = relay.lastRelayed > 0 ? "Last text " + Text.ago(relay.lastRelayed, now) : "Ready for the next text";
        }
        heroWord.setText(word);
        heroLine.setText(line);
        heroLight.set(light, trying);
        hero.setVisibility(configured ? View.VISIBLE : View.GONE);
        actions.setVisibility(configured ? View.VISIBLE : View.GONE);
        link.view().setVisibility(configured ? View.VISIBLE : View.GONE);
        details.setText(diagnostics(now, store));
        syncButton.setEnabled(configured);

        accessRow.view.setVisibility(access ? View.GONE : View.VISIBLE);
        infoRow.view.setVisibility(access ? View.GONE : View.VISIBLE);
        batteryRow.view.setVisibility(unrestricted ? View.GONE : View.VISIBLE);
        boolean any = !access || !unrestricted;
        todoHead.setVisibility(any ? View.VISIBLE : View.GONE);
        todo.setVisibility(any ? View.VISIBLE : View.GONE);

        JSONObject h = relay.historySync.status();
        historyRow.sub(store ? shortHistory(h, now) : "Off");
        ui.endValue(historyRow, store ? "" : "Turn on", true);
        smsRow.sub(sms ? (contacts ? "On" : "On, numbers from Messages only") : "Off");
        ui.endValue(smsRow, sms ? "" : "Turn on", true);
    }

    /** Everything the card knows, for whoever taps it. */
    private String diagnostics(long now, boolean store) {
        StringBuilder s = new StringBuilder();
        s.append(prefs.link() != null ? "Linked to your private inbox, end-to-end encrypted." : prefs.configured() ? "First-version inbox (not encrypted)." : "Not connected to an inbox yet.");
        s.append("\n").append(relay.listening ? "Reading Messages notifications." : "Not reading notifications yet.");
        s.append("\nInstant channel: ").append(relay.doorbellConnected() ? "connected" : "reconnecting (polling meanwhile)");
        s.append("\nLast text forwarded: ").append(Text.ago(relay.lastRelayed, now));
        if (store) s.append("\nMessage store checked: ").append(Text.ago(relay.storeSync.lastScan, now));
        int waiting = relay.outbound.size();
        if (waiting > 0) s.append("\nWaiting to reach the inbox: ").append(waiting);
        s.append("\nLast check with the inbox: ").append(Text.ago(relay.lastSync, now));
        s.append("\nConversations you can reply to: ").append(relay.replyableCount());
        if (relay.lastQueued > 0) s.append("\nReplies waiting to go out: ").append(relay.lastQueued);
        if (relay.lastResync > 0) s.append("\nLast sync: ").append(Text.ago(relay.lastResync, now));
        if (relay.lastHidden > 0) {
            s.append("\nAndroid hid a text it took for a one-time code (").append(Text.ago(relay.lastHidden, now)).append(")")
                    .append(store ? "; it comes from the message store instead." : ". Turn on message history so texts like it come through.");
        }
        if (relay.lastError != null) s.append("\nLast problem: ").append(relay.lastError);
        else if (relay.lastMediaError != null) s.append("\nLast attachment problem: ").append(relay.lastMediaError);
        else if (relay.storeSync.lastError != null) s.append("\nLast problem: ").append(relay.storeSync.lastError);
        return s.toString();
    }

    static String shortHistory(JSONObject h, long now) {
        NumberFormat n = NumberFormat.getIntegerInstance(Locale.US);
        long done = h.optLong("done");
        long total = Math.max(done, h.optLong("total"));
        switch (h.optString("state")) {
            case "running": return "Syncing " + n.format(done) + " of " + n.format(total);
            case "paused": return "Paused at " + n.format(done) + " of " + n.format(total);
            case "done": return n.format(done) + " messages, " + Text.ago(h.optLong("at"), now);
            default: return "New texts only";
        }
    }

    /** The full-history line More shows. */
    static String historyLine(JSONObject h, long now) {
        NumberFormat n = NumberFormat.getIntegerInstance(Locale.US);
        String state = h.optString("state");
        long done = h.optLong("done");
        long total = Math.max(done, h.optLong("total"));
        String note = h.optString("note", "");
        switch (state) {
            case "running":
                return "Syncing: " + n.format(done) + " of about " + n.format(total) + " messages." + (note.isEmpty() ? "" : " " + note + ".");
            case "paused":
                return "Paused at " + n.format(done) + " of about " + n.format(total) + " messages. " + note;
            case "done":
                return "Done " + Text.ago(h.optLong("at"), now) + ": " + n.format(done) + " messages"
                        + (h.optBoolean("media") ? ", with pictures and videos." : ", text only.");
            default:
                return "New texts only, so far. A full sync brings in the rest; running it again only fills gaps.";
        }
    }

    private void syncNow() {
        if (!prefs.configured()) {
            Toast.makeText(this, "Link this phone first.", Toast.LENGTH_SHORT).show();
            return;
        }
        relay.reconnect(false);
        Toast.makeText(this, "Syncing with your inbox…", Toast.LENGTH_SHORT).show();
    }

    private void openListenerSettings() {
        Intent detail = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                        new ComponentName(this, RelayListenerService.class).flattenToString());
        open(this, detail, new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
    }

    private void openAppInfo() {
        open(this, new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())), null);
    }

    private void askBatteryExemption() {
        open(this, new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())),
                new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
    }

    static void open(Activity a, Intent primary, Intent fallback) {
        try {
            a.startActivity(primary);
        } catch (ActivityNotFoundException e) {
            if (fallback != null) {
                try {
                    a.startActivity(fallback);
                } catch (ActivityNotFoundException ignored) {
                    Toast.makeText(a, "This phone doesn't have that settings screen.", Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    /** A code's card: the QR code on white (cameras read it best there), the code itself, and a line under it. */
    static final class CodeCard {
        final LinearLayout view;
        final ImageView qr;
        final TextView code;
        final TextView caption;
        final TextView note;

        CodeCard(Activity a, Ui ui, String description) {
            view = new LinearLayout(a);
            view.setOrientation(LinearLayout.VERTICAL);
            view.setGravity(Gravity.CENTER_HORIZONTAL);
            view.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16));
            view.setBackground(ui.rounded(Color.WHITE, 26));
            qr = new ImageView(a);
            qr.setAdjustViewBounds(true);
            qr.setContentDescription(description);
            int side = Math.min(ui.dp(220), a.getResources().getDisplayMetrics().widthPixels - ui.dp(92));
            view.addView(qr, new LinearLayout.LayoutParams(side, side));
            code = ui.label("", 21f, Ui.BLACK, 0xFF14172E);
            code.setLetterSpacing(0.06f);
            code.setGravity(Gravity.CENTER);
            code.setPadding(0, ui.dp(10), 0, 0);
            view.addView(code);
            caption = ui.label("", 14f, Ui.BOLD, 0xFF5A6080);
            caption.setGravity(Gravity.CENTER);
            caption.setPadding(0, ui.dp(4), 0, 0);
            view.addView(caption);
            note = ui.label("", 13f, Ui.MEDIUM, ui.text3);
            note.setGravity(Gravity.CENTER);
            note.setPadding(ui.dp(8), ui.dp(10), ui.dp(8), 0);
        }
    }

    // ------------------------------------------------------------------ connecting this phone

    /**
     * Not in a private inbox yet: shows this phone's code as a QR code, and waits. Texts on the other phone scans it,
     * and that is all: two new phones get their own private inbox, a linked Texts brings this phone into its inbox (a new
     * relay phone), and a reinstalled Relay gets back in by itself before any code shows. Until then a phone on the
     * first version's inbox keeps relaying there. The inbox's address is under More, Advanced.
     */
    static final class ConnectPanel {
        private final Activity activity;
        private final Relay relay;
        private final Runnable onConnected;
        private final LinearLayout root;
        private final CodeCard card;
        private final TextView below;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private volatile boolean polling;
        private volatile boolean asking;
        private volatile boolean stopped;   // the screen is gone: what a thread brings back is dropped
        private boolean visible;
        private String error;
        private final Runnable poll = new Runnable() {
            @Override
            public void run() {
                if (!polling) return;
                ask();
                handler.postDelayed(this, 3_000);
            }
        };

        ConnectPanel(Activity activity, Relay relay, Ui ui, Runnable onConnected) {
            this.activity = activity;
            this.relay = relay;
            this.onConnected = onConnected;
            root = new LinearLayout(activity);
            root.setOrientation(LinearLayout.VERTICAL);
            card = new CodeCard(activity, ui, "QR code for linking this phone");
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = ui.dp(4);
            root.addView(card.view, lp);
            below = card.note;
            below.setTextColor(ui.text2);
            root.addView(below);
        }

        View view() {
            return root;
        }

        private boolean inPrivateInbox() {
            return relay.prefs.link() != null;
        }

        void resume() {
            visible = true;
            startPolling();
        }

        void pause() {
            visible = false;
            stopPolling();
        }

        private void startPolling() {
            if (!visible || inPrivateInbox() || polling) return;
            polling = true;
            handler.post(poll);
        }

        private void stopPolling() {
            polling = false;
            handler.removeCallbacks(poll);
        }

        void stop() {
            stopped = true;
            pause();
        }

        void refresh() {
            boolean linked = inPrivateInbox();
            boolean old = !linked && relay.prefs.configured();   // still on the first version's inbox
            root.setVisibility(linked ? View.GONE : View.VISIBLE);
            if (linked) return;
            startPolling();   // also when this phone was just taken out of its inbox
            Relay.PendingLink pl = relay.pending;
            String c = pl.code;
            if (c != null && c.equals(pl.filedFor)) {
                if (!c.equals(card.qr.getTag())) {
                    card.qr.setImageBitmap(Pairing.qr(Crypto.linkQr(relay.prefs.server(), c), Math.max(320, card.qr.getLayoutParams().width)));
                    card.qr.setTag(c);
                }
                card.code.setText(Pairing.pretty(c));
            } else {
                card.qr.setImageDrawable(null);
                card.qr.setTag(null);
                card.code.setText(pl.recovered ? "Making a code…" : "Checking this phone…");
            }
            card.caption.setText(old ? "Relaying to your inbox (first version). To make it private, open Paigr and scan this code."
                    : "On your other phone, open Paigr and scan this code.");
            below.setText(error != null ? error : "A new code comes every 15 minutes.");
        }

        /** First a reinstalled phone tries getting back in; then this phone's code is filed and checked on. */
        private void ask() {
            if (asking || inPrivateInbox()) return;
            asking = true;
            String base = relay.prefs.server();
            Relay.PendingLink pl = relay.pending;
            new Thread(() -> {
                Link got = null;
                String problem = null;
                try {
                    if (!pl.recovered) {
                        pl.recovered = true;
                        got = relay.recover();
                    }
                    if (got == null) {
                        if (pl.code == null || pl.secrets == null) {
                            String c = Crypto.newCode();
                            pl.secrets = Crypto.linkSecrets(c);
                            pl.token = Crypto.newToken();
                            pl.filedFor = null;
                            pl.code = c;
                        }
                        if (!pl.code.equals(pl.filedFor)) {
                            Hedy2.ask(base, pl.secrets, pl.token);
                            pl.filedFor = pl.code;
                        } else {
                            got = Hedy2.waitForScan(base, pl.secrets, pl.token);
                        }
                    }
                } catch (Hedy.ApiException e) {
                    if (e.code == 410 || e.code == 409) {
                        pl.forget();   // expired (or, very rarely, taken): a new code next time round
                    } else {
                        problem = (e.code == 429 ? "Too many tries just now." : "Couldn't get a code: " + e.reason + ".") + " It keeps trying.";
                    }
                } catch (IOException e) {
                    problem = e instanceof Tor.Down ? e.getMessage() : "Can't reach the inbox. Check this phone's connection; it keeps trying.";
                }
                if (got != null && relay.prefs.link() == null) relay.joined(got);
                boolean done = got != null;
                String p = problem;
                activity.runOnUiThread(() -> {
                    asking = false;
                    if (stopped) return;
                    if (done) {
                        pl.forget();
                        error = null;
                        stopPolling();
                        refresh();
                        onConnected.run();
                        return;
                    }
                    error = p;
                    refresh();
                });
            }, "relay-connect").start();
        }
    }

    // ------------------------------------------------------------------ linking another device

    /** Link a device: a code (an invite to this inbox) for another phone's Texts to scan, for 15 minutes. */
    static final class LinkPanel {
        private final Activity activity;
        private final Relay relay;
        private final Ui ui;
        private final LinearLayout root;
        private final CodeCard card;
        private final TextView make;
        private final TextView note;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private long expiresAt;
        private volatile String watching;   // the invite's lookup, while it's on screen
        private volatile boolean stopped;
        private final Runnable countdown = new Runnable() {
            @Override
            public void run() {
                long left = expiresAt - System.currentTimeMillis();
                if (left <= 0) {
                    hideCode("That code expired. Tap Link a device when the other phone is ready.");
                    return;
                }
                long minutes = (left + 59_999) / 60_000;
                card.note.setText("Expires in " + minutes + " min");
                if (watching != null) checkUsed(watching);
                handler.postDelayed(this, 4_000);
            }
        };

        LinkPanel(Activity activity, Relay relay, Ui ui) {
            this.activity = activity;
            this.relay = relay;
            this.ui = ui;
            make = ui.primary("Link a device", R.drawable.ic_plus, Ui.BRAND, v -> start());
            root = new LinearLayout(activity);
            root.setOrientation(LinearLayout.VERTICAL);
            card = new CodeCard(activity, ui, "QR code for linking a phone");
            card.caption.setText("Scan with Paigr on the other phone. No camera? Type the code.");
            card.view.addView(card.note);
            card.note.setTextColor(0xFF8A90AE);
            card.view.setVisibility(View.GONE);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = ui.dp(12);
            root.addView(card.view, lp);
            note = ui.label("", 13.5f, Ui.BOLD, ui.text2);
            note.setGravity(Gravity.CENTER);
            note.setPadding(ui.dp(8), ui.dp(10), ui.dp(8), 0);
            note.setVisibility(View.GONE);
            root.addView(note);
        }

        TextView button() {
            return make;
        }

        View view() {
            return root;
        }

        void stop() {
            stopped = true;
            handler.removeCallbacks(countdown);
            watching = null;
        }

        private void say(String text) {
            note.setText(text);
            note.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
        }

        private void hideCode(String message) {
            handler.removeCallbacks(countdown);
            watching = null;
            expiresAt = 0;
            card.view.setVisibility(View.GONE);
            say(message);
            make.setText("Link a device");
            make.setEnabled(true);
        }

        private void checkUsed(String lookup) {
            Hedy2 h = relay.hedy2();
            if (h == null) return;
            new Thread(() -> {
                String st;
                try {
                    st = h.linkStatus(lookup);
                } catch (IOException e) {
                    return;   // checked again in a few seconds
                }
                if (!"done".equals(st)) return;
                activity.runOnUiThread(() -> {
                    if (lookup.equals(watching)) hideCode("Linked. That device has your inbox now.");
                });
            }, "relay-link-status").start();
        }

        private void start() {
            if (!relay.prefs.configured()) {
                say("Link this phone first: Paigr scans its code.");
                return;
            }
            make.setEnabled(false);
            say("Making a code…");
            String fresh = Crypto.newCode();
            Link at = relay.prefs.link();
            Hedy2 h = at != null ? relay.hedy2() : null;
            String linkServer = at != null ? at.server : relay.prefs.server();   // kept: the phone may be unlinked meanwhile
            new Thread(() -> {
                String error = null;
                String lookup = null;
                try {
                    if (h != null) {
                        Crypto.LinkSecrets s = Crypto.linkSecrets(fresh);
                        h.invite(s, "inbox");
                        lookup = s.lookup;
                    } else {
                        relay.hedy().addPairingCode(Pairing.sha256Hex(Pairing.normalize(fresh)), "inbox", Pairing.MINUTES);
                    }
                } catch (Hedy.ApiException e) {
                    error = "Couldn't make a code: " + e.reason + ".";
                } catch (IOException e) {
                    error = e instanceof Tor.Down ? e.getMessage() : "Couldn't reach the inbox. Check the connection, then try again.";
                }
                String failed = error;
                String watch = lookup;
                activity.runOnUiThread(() -> {
                    if (stopped) return;
                    if (failed != null) {
                        say(failed);
                        make.setEnabled(true);
                        return;
                    }
                    String shown = h != null ? Crypto.linkQr(linkServer, fresh) : Pairing.payload(Pairing.LINK, fresh);
                    card.qr.setImageBitmap(Pairing.qr(shown, Math.max(320, card.qr.getLayoutParams().width)));
                    card.code.setText(Pairing.pretty(fresh));
                    card.view.setVisibility(View.VISIBLE);
                    say("");
                    make.setText("New code");
                    make.setEnabled(true);
                    watching = watch;
                    expiresAt = System.currentTimeMillis() + Pairing.MINUTES * 60_000L;
                    handler.removeCallbacks(countdown);
                    handler.post(countdown);
                });
            }, "relay-link-code").start();
        }
    }
}
