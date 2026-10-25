package com.textrelay.texts;

import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/** The relay phone, notifications, the devices in this inbox, and this phone. */
final class SettingsScreen extends Screen {
    static final String REPO_URL = "https://bradleyw801.github.io/sidephone-apps/";

    private final LinearLayout top;
    private final LinearLayout body;
    private final Ui.Row relayRow;
    private final Ui.Row reconnectRow;
    private final Ui.Row historyRow;
    private final Ui.Row approveRow;
    private final LinearLayout approvePanel;
    private final EditText approveCode;
    private final TextView approveGo;
    private final Ui.Segmented alert;
    private final TextView alertSub;
    private final LinearLayout statusBox;
    private final Ui.Segmented status;
    private final TextView statusSub;
    private final Ui.Row allowRow;
    private final Ui.Row batteryRow;
    private final LinearLayout devices;
    private final TextView devicesNote;
    private final Ui.Segmented tor;
    private final TextView torSub;
    private final Ui.Row orbotRow;

    SettingsScreen(MainActivity a) {
        super(a);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(ui.bg);
        top = new LinearLayout(a);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(ui.dp(4), 0, ui.dp(4), 0);
        top.setMinimumHeight(ui.dp(56));
        top.addView(ui.iconButton(R.drawable.ic_back, "Back", ui.text, v -> a.onBackPressed()));
        TextView title = ui.label("Settings", 20f, Ui.BLACK, ui.text);
        title.setPadding(ui.dp(6), 0, 0, 0);
        top.addView(title);
        col.addView(top);

        ScrollView scroll = new ScrollView(a);
        scroll.setClipToPadding(false);
        body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(ui.dp(12), 0, ui.dp(12), ui.dp(24));
        scroll.addView(body, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- the relay phone
        body.addView(ui.section("Relay phone"));
        LinearLayout relay = ui.group();
        relayRow = ui.row(R.drawable.ic_phone, ui.ok, "Relay phone", null);
        relay.addView(relayRow.view);
        reconnectRow = ui.row(R.drawable.ic_beacon, Ui.BRAND.hi, "Reconnect", v -> inbox.reconnectRelay());
        relay.addView(reconnectRow.view);
        Ui.Row resync = ui.row(R.drawable.ic_sync, Ui.BRAND.hi, "Resync", v -> inbox.resync(this::refresh));
        resync.sub("Reloads everything here");
        relay.addView(resync.view);
        historyRow = ui.row(R.drawable.ic_history, Ui.BRAND.hi, "Message history", null);
        relay.addView(historyRow.view);
        approveRow = ui.row(R.drawable.ic_scan, Ui.BRAND.hi, "Set up a new relay phone", v -> a.scan("approve"));
        approveRow.sub("Scan the code Paigr Relay shows on it");
        relay.addView(approveRow.view);
        approvePanel = new LinearLayout(a);
        approvePanel.setOrientation(LinearLayout.HORIZONTAL);
        approvePanel.setGravity(Gravity.CENTER_VERTICAL);
        approvePanel.setPadding(ui.dp(14), ui.dp(4), ui.dp(10), ui.dp(12));
        approvePanel.setVisibility(View.GONE);
        approveCode = new EditText(a);
        approveCode.setHint("XXXX-XXXX-XXXX");
        approveCode.setSingleLine(true);
        approveCode.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        approveCode.setImeOptions(EditorInfo.IME_ACTION_GO);
        approveCode.setFilters(new InputFilter[] { new InputFilter.AllCaps(), new InputFilter.LengthFilter(24) });
        approveCode.setTypeface(ui.face(Ui.HEAVY));
        approveCode.setTextSize(16f);
        approveCode.setTextColor(ui.text);
        approveCode.setHintTextColor(ui.text3);
        approveCode.setBackground(ui.rounded(ui.dark ? ui.bg : ui.surface2, 14));
        approveCode.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
        approveCode.setOnEditorActionListener((v, id, e) -> {
            a.link(approveCode.getText().toString(), "approve");
            return true;
        });
        approvePanel.addView(approveCode, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        approveGo = ui.primary("Link", 0, Ui.BRAND, v -> a.link(approveCode.getText().toString(), "approve"));
        approveGo.setMinHeight(ui.dp(42));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.setMarginStart(ui.dp(8));
        approvePanel.addView(approveGo, glp);
        relay.addView(approvePanel);
        body.addView(relay);

        // ---- notifications
        body.addView(ui.section("Notifications"));
        LinearLayout notes = ui.group();
        LinearLayout alertBox = choiceBox(R.drawable.ic_bell, ui.warn, "New texts");
        alert = ui.segmented(new String[] { "Sound", "Silent", "Off" }, new String[] { Prefs.ALERT_NORMAL, Prefs.ALERT_QUIET, Prefs.ALERT_OFF }, v -> {
            a.prefs.setAlertMode(v);
            if (Prefs.ALERT_OFF.equals(a.prefs.alertMode())) {
                SyncService.stop(a);
                SyncService.notifier(a).clearAll();
            } else if (a.prefs.paired()) {
                SyncService.start(a);
            }
            refresh();
        });
        alertBox.addView(alert.view, segmentParams());
        alertSub = ui.label("", 13f, Ui.BODY, ui.text3);
        alertSub.setPadding(ui.dp(2), ui.dp(8), 0, 0);
        alertBox.addView(alertSub);
        notes.addView(alertBox);
        statusBox = choiceBox(R.drawable.ic_eye, Ui.BRAND.hi, "Background notification");
        status = ui.segmented(new String[] { "Shown", "Hidden" }, new String[] { "shown", "hidden" }, v -> {
            boolean hide = "hidden".equals(v);
            if (hide != SyncService.statusHidden(a)) openStatusSettings();
        });
        statusBox.addView(status.view, segmentParams());
        statusSub = ui.label("", 13f, Ui.BODY, ui.text3);
        statusSub.setPadding(ui.dp(2), ui.dp(8), 0, 0);
        statusBox.addView(statusSub);
        notes.addView(statusBox);
        allowRow = ui.row(R.drawable.ic_alert, ui.warn, "Allow notifications", v -> openNotificationSettings());
        notes.addView(allowRow.view);
        batteryRow = ui.row(R.drawable.ic_charge, ui.warn, "Allow background use", v -> askBattery());
        batteryRow.sub("So texts keep arriving when the phone is idle");
        notes.addView(batteryRow.view);
        body.addView(notes);

        // ---- how this phone reaches the inbox
        body.addView(ui.section("Connection"));
        LinearLayout connection = ui.group();
        LinearLayout torBox = choiceBox(R.drawable.ic_shield, Ui.BRAND.hi, "Connect through Tor");
        tor = ui.segmented(new String[] { "Off", "On" }, new String[] { "off", "on" }, v -> {
            boolean on = "on".equals(v);
            if (on == a.prefs.tor()) return;
            a.prefs.setTor(on);
            reconnect();
            refresh();
        });
        torBox.addView(tor.view, segmentParams());
        torSub = ui.label("", 13f, Ui.BODY, ui.text3);
        torSub.setPadding(ui.dp(2), ui.dp(8), 0, 0);
        torBox.addView(torSub);
        connection.addView(torBox);
        orbotRow = ui.row(R.drawable.ic_retry, Ui.BRAND.hi, "Start Orbot", v -> {
            if (Tor.orbotInstalled(a)) Tor.startOrbot(a);
            else Tor.getOrbot(a);
        });
        orbotRow.view.setVisibility(View.GONE);
        connection.addView(orbotRow.view);
        body.addView(connection);

        // ---- devices
        body.addView(ui.section("Devices in your inbox"));
        devices = ui.group();
        body.addView(devices);
        devicesNote = ui.label("Texts, names and pictures are encrypted on these devices. The server can't read them.", 12.5f, Ui.MEDIUM, ui.text3);
        devicesNote.setPadding(ui.dp(8), ui.dp(8), ui.dp(8), 0);
        body.addView(devicesNote);

        // ---- this phone
        body.addView(ui.section("This phone"));
        LinearLayout phone = ui.group();
        Ui.Row updates = ui.row(R.drawable.ic_save, Ui.BRAND.hi, "Get updates", v -> {
            try {
                a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL)));
            } catch (ActivityNotFoundException e) {
                a.say(REPO_URL, 5000);
            }
        });
        updates.sub("Brad White's apps, in F-Droid or App Library");
        phone.addView(updates.view);
        Ui.Row unlink = ui.row(R.drawable.ic_unlink, ui.bad, "Unlink this phone", v -> Ui.confirm(a, ui, "Unlink this phone?",
                "It stops getting texts until you link it again with a code from Paigr Relay.", "Unlink", true, inbox::leave));
        unlink.title.setTextColor(ui.bad);
        phone.addView(unlink.view);
        body.addView(phone);
        TextView version = ui.label("Paigr " + MainActivity.VERSION, 12.5f, Ui.BOLD, ui.text3);
        version.setGravity(Gravity.CENTER);
        version.setPadding(0, ui.dp(18), 0, 0);
        body.addView(version);
        root = col;
    }

    private LinearLayout choiceBox(int icon, int color, String title) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(14));
        LinearLayout head = new LinearLayout(a);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(ui.chip(icon, color, ui.chipBg(color)));
        head.addView(ui.label(title, 15.5f, Ui.BOLD, ui.text));
        box.addView(head);
        return box;
    }

    private LinearLayout.LayoutParams segmentParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = ui.dp(10);
        return lp;
    }

    @Override
    void insets(int top, int bottom, boolean keyboard) {
        this.top.setPadding(ui.dp(4), top, ui.dp(4), 0);
        body.setPadding(ui.dp(12), 0, ui.dp(12), bottom + ui.dp(24));
    }

    @Override
    void shown() {
        inbox.view = "settings";
        approvePanel.setVisibility(View.GONE);
        refresh();
        loadDevices();
    }

    @Override
    void changed(int what, boolean end) {
        if ((what & Inbox.RELAY) != 0) relay();
    }

    void refresh() {
        relay();
        notifications();
        connection();
    }

    /** Whether Tor is on, and running: the answer comes from Tor's port on this phone, off the main thread. */
    private void connection() {
        boolean on = a.prefs.tor();
        boolean onion = a.prefs.onionServer();
        ui.select(tor, on ? "on" : "off");
        tor.view.setEnabled(!onion);
        tor.view.setAlpha(onion ? 0.55f : 1f);
        for (TextView t : tor.options) t.setEnabled(!onion);
        if (!on) {
            torSub.setText("Off. Your inbox server sees this phone's IP address.");
            orbotRow.view.setVisibility(View.GONE);
            return;
        }
        String always = onion ? "Always on: your inbox server is an onion address. " : "";
        boolean installed = Tor.orbotInstalled(a);
        new Thread(() -> {
            boolean running = Tor.running();
            a.runOnUiThread(() -> {
                if (!a.prefs.tor()) return;
                if (running) torSub.setText(always + "Your inbox server doesn't see this phone's IP address.");
                else if (installed) torSub.setText(always + "Tor isn't running, so nothing reaches your inbox. Start it in Orbot.");
                else torSub.setText(always + "Nothing reaches your inbox without Tor. Get Orbot to run it.");
                orbotRow.title.setText(installed ? "Start Orbot" : "Get Orbot");
                ((android.widget.ImageView) orbotRow.icon.getChildAt(0)).setImageResource(installed ? R.drawable.ic_retry : R.drawable.ic_forward);
                orbotRow.view.setVisibility(running ? View.GONE : View.VISIBLE);
            });
        }, "tor-check").start();
    }

    /** Connections made the old way go: the inbox's, and the background checks'. */
    private void reconnect() {
        if (inbox.isVisible()) inbox.startSync();
        if (a.prefs.paired() && !Prefs.ALERT_OFF.equals(a.prefs.alertMode())) {
            SyncService.stop(a);
            SyncService.start(a);
        }
    }

    private void relay() {
        long now = System.currentTimeMillis();
        Model.Relay r = inbox.relay;
        Inbox.Health h = Inbox.health(r, now);
        int color = "ok".equals(h.level) ? ui.ok : "warn".equals(h.level) ? ui.warn : "bad".equals(h.level) ? ui.bad : ui.text3;
        relayRow.title.setText(inbox.reconnecting() ? "Reconnecting…" : h.title);
        relayRow.icon.setBackground(ui.rounded(ui.chipBg(color), 9));
        ((android.widget.ImageView) relayRow.icon.getChildAt(0)).setImageTintList(android.content.res.ColorStateList.valueOf(color));
        StringBuilder s = new StringBuilder();
        if (r != null && r.lastSeen > 0) {
            s.append("Checked in ").append(Format.ago(r.lastSeen, now));
            if (!Double.isNaN(r.battery) && r.battery > 0) s.append(", ").append(Math.round(r.battery)).append("%").append(r.charging ? " charging" : "");
            if (!r.model.isEmpty()) s.append("\n").append(r.model);
            if (!Double.isNaN(r.queued) && r.queued > 0) s.append("\n").append((long) Math.floor(r.queued)).append(" waiting to upload");
            if (Boolean.FALSE.equals(r.smsFallback)) s.append("\nSMS fallback off");
            if (!r.note.isEmpty()) s.append("\n").append(r.note);
        } else {
            s.append("Open Relay on it and check it says Relaying.");
        }
        relayRow.sub(s);
        reconnectRow.view.setEnabled(!inbox.reconnecting());
        reconnectRow.view.setAlpha(inbox.reconnecting() ? 0.5f : 1f);
        historyRow.sub(history(r, now));
        historyRow.view.setVisibility(r != null && r.lastSeen > 0 ? View.VISIBLE : View.GONE);
    }

    /** What the relay phone says about its message store and the full history. */
    static String history(Model.Relay r, long now) {
        Model.Store st = r != null ? r.store : null;
        if (st == null || !st.allowed) return "Off. Turn on message access in Paigr Relay to bring in older texts.";
        switch (st.historyState) {
            case "running":
                return "Syncing " + Format.count(st.historyDone) + " of " + Format.count(st.historyTotal);
            case "paused":
                return "Paused at " + Format.count(st.historyDone) + " of " + Format.count(st.historyTotal)
                        + (st.historyNote.isEmpty() ? "" : ". " + st.historyNote);
            case "done":
                return Format.count(st.historyTotal) + " messages, synced " + Format.ago(st.historyAt, now) + (st.historyMedia ? ", with pictures" : ", text only");
            default:
                return "On for new texts. Sync the full history in Paigr Relay.";
        }
    }

    private void notifications() {
        String mode = a.prefs.alertMode();
        ui.select(alert, mode);
        alertSub.setText(Prefs.ALERT_OFF.equals(mode) ? "None. New texts are here when you open Paigr."
                : Prefs.ALERT_QUIET.equals(mode) ? "In the notification shade, with no sound or pop-up." : "A sound, and a pop-up on screen.");
        boolean hidden = SyncService.statusHidden(a);
        ui.select(status, hidden ? "hidden" : "shown");
        statusSub.setText(hidden ? "Turned off in Android's settings. Paigr still listens." : "Android shows it while Paigr listens for texts.");
        statusBox.setVisibility(Prefs.ALERT_OFF.equals(mode) ? View.GONE : View.VISIBLE);
        boolean allowed = a.getSystemService(NotificationManager.class).areNotificationsEnabled();
        allowRow.view.setVisibility(allowed ? View.GONE : View.VISIBLE);
        boolean exempt = a.getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(a.getPackageName());
        batteryRow.view.setVisibility(exempt || Prefs.ALERT_OFF.equals(mode) ? View.GONE : View.VISIBLE);
    }

    private void loadDevices() {
        inbox.devices(this::showDevices, e -> {
            devices.removeAllViews();
            Ui.Row r = ui.row(R.drawable.ic_alert, ui.warn, "Couldn't load the devices", null);
            r.sub(Inbox.words(e));
            devices.addView(r.view);
        });
    }

    private void showDevices(List<Model.Device> list) {
        devices.removeAllViews();
        String model = inbox.relay != null ? inbox.relay.model : "";
        for (Model.Device d : list) {
            boolean relay = "relay".equals(d.role);
            String title = relay ? "Relay phone" : d.name.isEmpty() ? "Texts" : d.name;
            if (d.you) title += " (this phone)";
            Ui.Row r = ui.row(relay ? R.drawable.ic_beacon : R.drawable.ic_phone, relay ? 0xFFE86A3A : Ui.BRAND.hi, title, null);
            String sub = (relay && !model.isEmpty() ? model + ". " : relay && !d.name.isEmpty() ? d.name + ". " : "")
                    + (d.at > 0 ? "Linked " + Format.day(d.at).replace("Today", "today").replace("Yesterday", "yesterday") : "");
            r.sub(sub);
            if (!d.you) {
                android.widget.FrameLayout remove = ui.iconButton(R.drawable.ic_trash, "Remove " + title, ui.text3, v -> Ui.confirm(a, ui,
                        "Remove this device?", "It stops getting your texts at once.", "Remove", true, () -> inbox.removeDevice(d.id, this::loadDevices)));
                r.end.addView(remove);
            }
            devices.addView(r.view);
        }
        if (list.isEmpty()) {
            Ui.Row r = ui.row(R.drawable.ic_phone, ui.text3, "No devices listed", null);
            devices.addView(r.view);
        }
    }

    // ------------------------------------------------------------------ bringing in another phone

    /** The scanner closed with "type it instead". */
    void typeCode() {
        approvePanel.setVisibility(View.VISIBLE);
        approveCode.setText("");
        approveCode.requestFocus();
        android.view.inputmethod.InputMethodManager im = a.getSystemService(android.view.inputmethod.InputMethodManager.class);
        if (im != null) im.showSoftInput(approveCode, 0);
    }

    void busy(boolean on) {
        approveRow.title.setText(on ? "Linking…" : "Set up a new relay phone");
        approveRow.view.setEnabled(!on);
        approveGo.setEnabled(!on);
    }

    void linkedAnother() {
        approvePanel.setVisibility(View.GONE);
        approveCode.setText("");
        loadDevices();
    }

    // ------------------------------------------------------------------ Android's own settings

    private void openStatusSettings() {
        SyncService.createChannels(a);
        Intent channel = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, a.getPackageName())
                .putExtra(Settings.EXTRA_CHANNEL_ID, SyncService.CHANNEL);
        try {
            a.startActivity(channel);
        } catch (ActivityNotFoundException e) {
            openNotificationSettings();
        }
    }

    private void openNotificationSettings() {
        if (Build.VERSION.SDK_INT >= 33 && a.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            a.requestPermissions(new String[] { android.Manifest.permission.POST_NOTIFICATIONS }, 1);
            return;
        }
        try {
            a.startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, a.getPackageName()));
        } catch (ActivityNotFoundException ignored) {
            // no such screen
        }
    }

    private void askBattery() {
        try {
            a.startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + a.getPackageName())));
        } catch (ActivityNotFoundException e) {
            try {
                a.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (ActivityNotFoundException ignored) {
                // no such screen
            }
        }
    }
}
