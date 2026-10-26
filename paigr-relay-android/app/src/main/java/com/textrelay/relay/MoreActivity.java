package com.textrelay.relay;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

/** Relay's settings: message history, SMS fallback, the status notification, the inbox server, and updates. */
public class MoreActivity extends Activity {
    private Relay relay;
    private Prefs prefs;
    private Ui ui;
    private Ui.Row historyState;
    private Ui.Row allowStore;
    private Ui.Segmented media;
    private LinearLayout fullHistory;
    private TextView syncHistory;
    private Ui.Row smsState;
    private Ui.Row allowSms;
    private Ui.Segmented status;
    private Ui.Row server;
    private Ui.Segmented tor;
    private TextView torSub;
    private Ui.Row orbot;
    private volatile boolean checkingTor;
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
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == MainActivity.ASK_STORE && relay.storeSync.allowed()) {
            relay.storeSync.start();
            relay.storeSync.request(StoreSync.DAYS_2, false);
            relay.historySync.resume();
        }
        refresh();
    }

    private View build() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(ui.dp(12), 0, ui.dp(12), ui.dp(24));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setMinimumHeight(ui.dp(56));
        top.addView(ui.iconButton(R.drawable.ic_back, "Back", ui.text, v -> finish()));
        TextView title = ui.label("More", 20f, Ui.BLACK, ui.text);
        title.setPadding(ui.dp(6), 0, 0, 0);
        top.addView(title);
        col.addView(top);

        // ---- message history
        col.addView(ui.section("Message history"));
        LinearLayout history = ui.group();
        historyState = ui.row(R.drawable.ic_history, Ui.BRAND.hi, "Message history", null);
        history.addView(historyState.view);
        allowStore = ui.row(R.drawable.ic_lock, ui.warn, "Allow message access", v -> ask(
                new String[] { Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS }, MainActivity.ASK_STORE));
        ui.endValue(allowStore, "Allow", true);
        history.addView(allowStore.view);
        LinearLayout choice = new LinearLayout(this);
        fullHistory = choice;
        choice.setOrientation(LinearLayout.VERTICAL);
        choice.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(14));
        choice.addView(ui.label("Full history brings in", 14f, Ui.BOLD, ui.text2));
        media = ui.segmented(new String[] { "Text only", "With pictures" }, new String[] { "text", "media" }, v -> {
            relay.historySync.setMedia("media".equals(v));
            refresh();
        });
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = ui.dp(10);
        choice.addView(media.view, mlp);
        syncHistory = ui.primary("Sync full history", R.drawable.ic_sync, Ui.BRAND, v -> startHistory());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = ui.dp(12);
        choice.addView(syncHistory, slp);
        history.addView(choice);
        col.addView(history);

        // ---- SMS fallback
        col.addView(ui.section("SMS fallback"));
        LinearLayout sms = ui.group();
        smsState = ui.row(R.drawable.ic_sms, Ui.BRAND.hi, "SMS fallback", null);
        sms.addView(smsState.view);
        allowSms = ui.row(R.drawable.ic_lock, ui.warn, "Allow SMS fallback", v -> ask(
                new String[] { Manifest.permission.SEND_SMS, Manifest.permission.READ_CONTACTS }, MainActivity.ASK_SMS));
        ui.endValue(allowSms, "Allow", true);
        sms.addView(allowSms.view);
        col.addView(sms);

        // ---- the status notification
        col.addView(ui.section("Status notification"));
        LinearLayout note = ui.group();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(14));
        status = ui.segmented(new String[] { "Shown", "Hidden" }, new String[] { Prefs.STATUS_SHOWN, Prefs.STATUS_HIDDEN }, v -> {
            prefs.setStatusMode(v);
            if (prefs.configured()) RelayService.start(this);   // starts or stops the status notification
            refresh();
        });
        box.addView(status.view);
        TextView why = ui.label("It keeps running either way.", 13f, Ui.BODY, ui.text3);
        why.setPadding(ui.dp(2), ui.dp(10), 0, 0);
        box.addView(why);
        note.addView(box);
        Ui.Row android = ui.row(R.drawable.ic_bell, Ui.BRAND.hi, "Android notification settings", v -> {
            RelayService.createChannels(this);
            MainActivity.open(this, new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()), null);
        });
        ui.endValue(android, "", true);
        note.addView(android.view);
        col.addView(note);

        // ---- how this phone reaches the inbox
        col.addView(ui.section("Connection"));
        LinearLayout connection = ui.group();
        LinearLayout torBox = new LinearLayout(this);
        torBox.setOrientation(LinearLayout.VERTICAL);
        torBox.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(14));
        torBox.addView(ui.label("Connect through Tor", 14f, Ui.BOLD, ui.text2));
        tor = ui.segmented(new String[] { "Off", "On" }, new String[] { "off", "on" }, v -> {
            boolean on = "on".equals(v);
            if (on == prefs.tor()) return;
            prefs.setTor(on);
            relay.torDown = false;
            relay.torChanged();
            refresh();
        });
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = ui.dp(10);
        torBox.addView(tor.view, tlp);
        torSub = ui.label("", 13f, Ui.BODY, ui.text3);
        torSub.setPadding(ui.dp(2), ui.dp(10), 0, 0);
        torBox.addView(torSub);
        connection.addView(torBox);
        orbot = ui.row(R.drawable.ic_retry, Ui.BRAND.hi, "Start Orbot", v -> {
            if (Tor.orbotInstalled(this)) Tor.startOrbot(this);
            else Tor.getOrbot(this);
        });
        orbot.view.setVisibility(View.GONE);
        connection.addView(orbot.view);
        col.addView(connection);

        // ---- advanced and about
        col.addView(ui.section("Advanced"));
        LinearLayout adv = ui.group();
        server = ui.row(R.drawable.ic_server, Ui.BRAND.hi, "Inbox server", v -> editServer());
        adv.addView(server.view);
        Ui.Row info = ui.row(R.drawable.ic_info, Ui.BRAND.hi, "App info", v -> MainActivity.open(this,
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())), null));
        ui.endValue(info, "", true);
        adv.addView(info.view);
        Ui.Row updates = ui.row(R.drawable.ic_forward, Ui.BRAND.hi, "Get updates", v -> MainActivity.open(this,
                new Intent(Intent.ACTION_VIEW, Uri.parse(WizardActivity.REPO_URL)), null));
        updates.sub("Brad White's apps, in F-Droid or App Library");
        adv.addView(updates.view);
        col.addView(adv);
        TextView version = ui.label("Paigr Relay " + version(), 12.5f, Ui.BOLD, ui.text3);
        version.setGravity(Gravity.CENTER);
        version.setPadding(0, ui.dp(18), 0, 0);
        col.addView(version);

        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        scroll.setBackgroundColor(ui.bg);
        scroll.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private String version() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    void refresh() {
        long now = System.currentTimeMillis();
        boolean store = relay.storeSync.allowed();
        boolean contacts = checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED;
        JSONObject h = relay.historySync.status();
        boolean running = relay.historySync.isRunning();
        historyState.view.setVisibility(store ? View.VISIBLE : View.GONE);
        historyState.title.setText(contacts ? "On, with names" : "On, numbers without names");
        historyState.sub(MainActivity.historyLine(h, now));
        allowStore.view.setVisibility(store && contacts ? View.GONE : View.VISIBLE);
        allowStore.title.setText(store ? "Allow contacts" : "Allow message access");
        allowStore.sub(store ? "Names for numbers" : "Every conversation, texts sent from here, one-time codes");
        fullHistory.setVisibility(store ? View.VISIBLE : View.GONE);
        ui.select(media, relay.historySync.media() ? "media" : "text");
        syncHistory.setEnabled(store && prefs.configured() && !running);
        syncHistory.setAlpha(syncHistory.isEnabled() ? 1f : 0.5f);
        syncHistory.setText(running ? "Syncing…" : "done".equals(h.optString("state")) ? "Sync full history again" : "Sync full history");

        boolean sms = relay.canSendSms();
        smsState.title.setText(sms ? "On" : "Off");
        smsState.sub(sms ? "A plain SMS when Messages can't reply, and to new numbers; pictures and files as MMS"
                : "Replies need Messages' notification; no new numbers or attachments from Paigr");
        allowSms.view.setVisibility(sms && contacts ? View.GONE : View.VISIBLE);
        allowSms.title.setText(sms ? "Allow contacts" : "Allow SMS fallback");
        allowSms.sub(sms ? "So it knows more numbers" : "Texts to one person or a new number, attachments to anyone");
        ui.select(status, Prefs.STATUS_HIDDEN.equals(prefs.statusMode()) ? Prefs.STATUS_HIDDEN : Prefs.STATUS_SHOWN);
        server.sub(prefs.link() != null ? host(prefs.server()) + ", linked" : host(prefs.server()));
        ui.endValue(server, "", prefs.link() == null);
        connection();
    }

    /** Whether Tor is on, and running: Tor's port on this phone answers that, off the main thread. */
    private void connection() {
        boolean on = prefs.tor();
        boolean onion = prefs.onionServer();
        ui.select(tor, on ? "on" : "off");
        tor.view.setAlpha(onion ? 0.55f : 1f);
        for (TextView t : tor.options) t.setEnabled(!onion);
        if (!on) {
            torSub.setText("Off. The inbox server sees this phone's IP address.");
            orbot.view.setVisibility(View.GONE);
            return;
        }
        if (checkingTor) return;
        checkingTor = true;
        String always = onion ? "Always on: the inbox server is an onion address. " : "";
        boolean installed = Tor.orbotInstalled(this);
        new Thread(() -> {
            boolean running = Tor.running();
            runOnUiThread(() -> {
                checkingTor = false;
                if (isDestroyed() || !prefs.tor()) return;
                if (running) torSub.setText(always + "The inbox server doesn't see this phone's IP address.");
                else if (installed) torSub.setText(always + "Tor isn't running, so texts wait here. Start it in Orbot.");
                else torSub.setText(always + "Texts wait here until Tor runs. Get Orbot to run it.");
                orbot.title.setText(installed ? "Start Orbot" : "Get Orbot");
                ((android.widget.ImageView) orbot.icon.getChildAt(0)).setImageResource(installed ? R.drawable.ic_retry : R.drawable.ic_forward);
                orbot.view.setVisibility(running ? View.GONE : View.VISIBLE);
            });
        }, "tor-check").start();
    }

    /**
     * What the inbox server field takes: an https address, or a .onion one (with or without http://, which is how
     * Tor reaches it). The address as it's kept, or null with `why` saying what's wrong.
     */
    static String serverAddress(String typed, String[] why) {
        String s = typed == null ? "" : typed.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        String lower = s.toLowerCase(java.util.Locale.US);
        if (!lower.contains("://") && Tor.isOnion(lower)) lower = "http://" + lower;
        if (lower.matches("^http://(?:[a-z0-9-]+\\.)*[a-z2-7]{56}\\.onion(?::[0-9]{1,5})?$")) return lower;
        if (lower.startsWith("http://") && Tor.isOnion(lower)) {
            why[0] = "That onion address isn't whole: it has 56 letters and digits before .onion.";
            return null;
        }
        if (lower.matches("^https://[a-z0-9.-]+(?::[0-9]{1,5})?$")) return lower;
        why[0] = "Use an https:// address, or a .onion one.";
        return null;
    }

    /** Asks Android; once Android stops asking (denied twice), opens App info to allow it there. */
    private void ask(String[] permissions, int code) {
        String key = "asked_" + code;
        boolean wouldAsk = !prefs.raw().getBoolean(key, false);
        for (String p : permissions) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED && shouldShowRequestPermissionRationale(p)) wouldAsk = true;
        }
        if (!wouldAsk) {
            Toast.makeText(this, "Allow it under Permissions.", Toast.LENGTH_LONG).show();
            MainActivity.open(this, new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())), null);
            return;
        }
        prefs.raw().edit().putBoolean(key, true).apply();
        requestPermissions(permissions, code);
    }

    private static String host(String server) {
        return server.replaceFirst("^https?://", "");
    }

    private void startHistory() {
        if (!prefs.configured()) {
            Toast.makeText(this, "Link this phone first.", Toast.LENGTH_SHORT).show();
            return;
        }
        relay.historySync.start();
        refresh();
    }

    /** Only before linking, and only if you run your own inbox server. */
    private void editServer() {
        if (prefs.link() != null) {
            Toast.makeText(this, "Linked to this server. It changes only when this phone links again.", Toast.LENGTH_LONG).show();
            return;
        }
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(ui.dp(20), ui.dp(20), ui.dp(16), ui.dp(12));
        card.setBackground(ui.rounded(ui.surface, 24));
        card.addView(ui.label("Inbox server", 18f, Ui.HEAVY, ui.text));
        TextView m = ui.label("Only if you run your own: its https address, or its .onion address.", 14f, Ui.BODY, ui.text2);
        m.setPadding(0, ui.dp(6), 0, ui.dp(10));
        card.addView(m);
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        field.setText(prefs.server());
        field.setTypeface(ui.face(Ui.MEDIUM));
        field.setTextColor(ui.text);
        card.addView(field);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.END);
        buttons.addView(ui.textButton("Cancel", ui.text2, v -> d.dismiss()));
        buttons.addView(ui.textButton("Save", ui.link, v -> {
            String[] why = new String[1];
            String s = serverAddress(field.getText().toString(), why);
            if (s == null) {
                Toast.makeText(this, why[0], Toast.LENGTH_LONG).show();
                return;
            }
            if (!s.equals(prefs.server())) {
                prefs.setServer(s, prefs.key());
                relay.pending.forget();   // a code belongs to one server
            }
            d.dismiss();
            refresh();
        }));
        card.addView(buttons);
        d.setContentView(card);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            w.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - ui.dp(32), ui.dp(420)), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        d.show();
    }
}
