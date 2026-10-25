package com.textrelay.relay;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * A short guide for leaving this phone (the one with the SIM) at home: get it ready to sit on a charger, set up the
 * phone you carry, forward calls through the carrier, and try it.
 */
public class WizardActivity extends Activity {
    /** Brad White's apps: the F-Droid repository Relay and Texts install and update from. */
    static final String REPO_URL = "https://bradleyw801.github.io/sidephone-apps/";
    static final int PAGES = 5;

    /** How a carrier spells call forwarding. */
    enum Carrier {
        GSM("AT&T, T-Mobile, Mint, Cricket, or most carriers outside the US", "**21*%s#", "##21#", "*#21#"),
        VERIZON("Verizon, Xfinity Mobile or Boost Mobile", "*72%s", "*73", null),
        US_CELLULAR("U.S. Cellular", "*72%s", "*720", null);

        final String label;
        final String on;
        final String off;
        final String check;

        Carrier(String label, String on, String off, String check) {
            this.label = label;
            this.on = on;
            this.off = off;
            this.check = check;
        }

        String forwardTo(String number) {
            return String.format(Locale.US, on, number);
        }

        /** A best guess from the SIM's carrier name; the person can change it. */
        static Carrier guess(String simOperator) {
            String s = simOperator == null ? "" : simOperator.toLowerCase(Locale.US);
            if (s.contains("verizon") || s.contains("xfinity") || s.contains("boost")) return VERIZON;
            if (s.contains("cellular") && (s.contains("u.s") || s.contains("us "))) return US_CELLULAR;
            if (s.contains("uscellular")) return US_CELLULAR;
            return GSM;
        }
    }

    private Relay relay;
    private Ui ui;
    private FrameLayout body;
    private TextView back;
    private TextView next;
    private TextView step;
    private int page;
    private Carrier carrier;
    private MainActivity.LinkPanel pairing;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable pageTick;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        relay = Relay.get(this);
        ui = new Ui(this);
        TelephonyManager tm = getSystemService(TelephonyManager.class);
        carrier = Carrier.guess(tm != null ? tm.getSimOperatorName() : null);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        root.setBackgroundColor(ui.bg);
        body = new FrameLayout(this);
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(12));
        back = ui.secondary("Back", 0, v -> go(page - 1));
        step = ui.label("", 13f, Ui.HEAVY, ui.text3);
        step.setGravity(Gravity.CENTER);
        next = ui.primary("Next", 0, Ui.BRAND, v -> {
            if (page == PAGES - 1) finish();
            else go(page + 1);
        });
        bar.addView(back, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(step, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f));
        bar.addView(next, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(bar);
        setContentView(root);
        go(savedInstanceState != null ? savedInstanceState.getInt("page", 0) : 0);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("page", page);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pageTick != null) handler.post(pageTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (pageTick != null) handler.removeCallbacks(pageTick);
    }

    @Override
    protected void onDestroy() {
        if (pairing != null) pairing.stop();
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")   // plain Activity back handling; this runs on 29+
    public void onBackPressed() {
        if (page > 0) go(page - 1);
        else super.onBackPressed();
    }

    void go(int to) {
        if (pageTick != null) handler.removeCallbacks(pageTick);
        pageTick = null;
        page = Math.max(0, Math.min(PAGES - 1, to));
        body.removeAllViews();
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(ui.dp(16), ui.dp(18), ui.dp(16), ui.dp(16));
        switch (page) {
            case 0: overview(col); break;
            case 1: ready(col); break;
            case 2: carry(col); break;
            case 3: forwarding(col); break;
            default: tryIt(col); break;
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(col);
        body.addView(scroll);
        back.setVisibility(page == 0 ? View.INVISIBLE : View.VISIBLE);
        next.setText(page == PAGES - 1 ? "Done" : "Next");
        step.setText((page + 1) + " of " + PAGES);
        if (pageTick != null) handler.post(pageTick);
    }

    // ------------------------------------------------------------------ pages

    private void overview(LinearLayout col) {
        col.addView(title("Leave this phone at home"));
        LinearLayout g = ui.group();
        Ui.Row home = ui.row(R.drawable.ic_home, Ui.BRAND.hi, "Stays home", null);
        home.sub("This phone, with your SIM, on Wi-Fi and a charger");
        g.addView(home.view);
        Ui.Row carry = ui.row(R.drawable.ic_phone, Ui.TEXTS.hi, "Goes with you", null);
        carry.sub("Any phone with Paigr. A basic phone still works for calls.");
        g.addView(carry.view);
        Ui.Row calls = ui.row(R.drawable.ic_forward, Ui.BRAND.hi, "Calls", null);
        calls.sub("Your carrier forwards them. Step 4 sets that up.");
        g.addView(calls.view);
        col.addView(g, spaced());
    }

    private void ready(LinearLayout col) {
        col.addView(title("Get this phone ready"));
        LinearLayout g = ui.group();
        Ui.Row access = ui.row(R.drawable.ic_bell, ui.ok, "Notification access", v -> open(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        Ui.Row battery = ui.row(R.drawable.ic_charge, ui.ok, "Background use", v -> open(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + getPackageName()))));
        Ui.Row paired = ui.row(R.drawable.ic_beacon, ui.ok, "Linked to your inbox", null);
        Ui.Row power = ui.row(R.drawable.ic_battery, ui.ok, "Charging", null);
        Ui.Row wifi = ui.row(R.drawable.ic_wifi, ui.ok, "On Wi-Fi", null);
        for (Ui.Row r : new Ui.Row[] { access, battery, paired, power, wifi }) g.addView(r.view);
        col.addView(g, spaced());
        col.addView(hint("Keep Messages' notifications on. Silent is fine."));
        col.addView(hint("Weak signal at home? Turn on Wi-Fi calling."));
        pageTick = new Runnable() {
            @Override
            public void run() {
                NotificationManager nm = getSystemService(NotificationManager.class);
                boolean granted = nm.isNotificationListenerAccessGranted(new ComponentName(WizardActivity.this, RelayListenerService.class));
                state(access, granted, granted ? "Allowed" : "Not allowed yet. Tap to turn on.", !granted);
                boolean unrestricted = getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
                state(battery, unrestricted, unrestricted ? "Unrestricted" : "Android may pause it. Tap to allow.", !unrestricted);
                boolean linked = relay.prefs.configured();
                state(paired, linked, linked ? "Yes" : "Not yet. The main screen shows a code for Paigr.", false);
                boolean charging = charging();
                state(power, charging, charging ? "Yes" : "Plug it in where it will stay", false);
                boolean onWifi = onWifi();
                state(wifi, onWifi, onWifi ? "Yes" : "Mobile data works too, but Wi-Fi is steadier", false);
                handler.postDelayed(this, 2_000);
            }
        };
    }

    private void state(Ui.Row r, boolean ok, String line, boolean action) {
        int color = ok ? ui.ok : ui.warn;
        r.icon.setBackground(ui.rounded(ui.chipBg(color), 9));
        ((ImageView) r.icon.getChildAt(0)).setImageTintList(android.content.res.ColorStateList.valueOf(color));
        r.sub(line);
        ui.endValue(r, ok ? "" : action ? "Fix" : "", action);
        if (ok) {
            r.end.removeAllViews();
            r.end.addView(ui.icon(R.drawable.ic_check, ui.ok, 20));
        }
    }

    private void carry(LinearLayout col) {
        col.addView(title("Set up the phone you carry"));
        col.addView(sub("1. Install Paigr"));
        col.addView(para("Scan this with it, add Brad White's apps in F-Droid or App Library, and install Paigr."));
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(12));
        card.setBackground(ui.rounded(Color.WHITE, 22));
        ImageView repo = new ImageView(this);
        repo.setImageBitmap(Pairing.qr(REPO_URL, ui.dp(180)));
        repo.setContentDescription("QR code for " + REPO_URL);
        card.addView(repo, new LinearLayout.LayoutParams(ui.dp(180), ui.dp(180)));
        TextView url = ui.label(REPO_URL.replace("https://", ""), 12.5f, Ui.BOLD, 0xFF5A6080);
        url.setTextIsSelectable(true);
        url.setGravity(Gravity.CENTER);
        url.setPadding(0, ui.dp(6), 0, 0);
        card.addView(url);
        LinearLayout.LayoutParams clp = spaced();
        clp.leftMargin = clp.rightMargin = ui.dp(24);
        col.addView(card, clp);
        col.addView(sub("2. Link it"));
        if (pairing != null) pairing.stop();
        pairing = null;
        if (!relay.prefs.configured()) {
            col.addView(para("In Paigr, scan the code on the main screen here."));
        } else {
            col.addView(para("This phone is linked. To add the phone you carry, make a code and scan it with Paigr."));
            pairing = new MainActivity.LinkPanel(this, relay, ui);
            col.addView(pairing.button(), spaced());
            col.addView(pairing.view());
        }
        col.addView(hint("Carrying a basic phone? Skip this. Your texts wait in your inbox."));
    }

    private void forwarding(LinearLayout col) {
        col.addView(title("Forward your calls"));
        col.addView(para("Your carrier can ring the phone you carry instead."));
        col.addView(ui.primary("Open call settings", R.drawable.ic_forward, Ui.BRAND, v -> open(new Intent(TelecomManager.ACTION_SHOW_CALL_SETTINGS))), spaced());
        col.addView(hint("Choose Call forwarding, then Always forward, and enter the other phone's number."));

        LinearLayout codes = new LinearLayout(this);
        codes.setOrientation(LinearLayout.VERTICAL);
        codes.setVisibility(View.GONE);
        TextView expand = ui.textButton("Use your carrier's code", ui.link, null);
        expand.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        expand.setPadding(ui.dp(4), ui.dp(14), ui.dp(8), ui.dp(10));
        expand.setCompoundDrawablePadding(ui.dp(6));
        chevron(expand, false);
        expand.setOnClickListener(v -> {
            boolean open = codes.getVisibility() != View.VISIBLE;
            codes.setVisibility(open ? View.VISIBLE : View.GONE);
            chevron(expand, open);
        });
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.topMargin = ui.dp(4);
        col.addView(expand, elp);
        col.addView(codes);

        EditText number = new EditText(this);
        number.setSingleLine(true);
        number.setInputType(InputType.TYPE_CLASS_PHONE);
        number.setHint("Number of the phone you carry");
        number.setText(relay.prefs.carryNumber());
        number.setTypeface(ui.face(Ui.BOLD));
        number.setTextColor(ui.text);
        number.setHintTextColor(ui.text3);
        number.setBackground(ui.rounded(ui.surface, 16));
        number.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
        codes.addView(number, spaced());
        TextView carrierHead = ui.label("Carrier", 13f, Ui.HEAVY, ui.text2);
        carrierHead.setPadding(ui.dp(4), ui.dp(14), 0, ui.dp(6));
        codes.addView(carrierHead);
        LinearLayout choices = ui.group();
        TextView[] picks = new TextView[Carrier.values().length];
        TextView check = ui.secondary("Check forwarding", 0, v -> {
            if (carrier.check != null) dial(carrier.check);
        });
        for (Carrier c : Carrier.values()) {
            TextView t = ui.label(c.label, 14.5f, Ui.BOLD, ui.text);
            t.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
            t.setBackground(ui.pressable(0));
            t.setFocusable(true);
            t.setClickable(true);
            t.setTag(c);
            picks[c.ordinal()] = t;
            t.setOnClickListener(v -> {
                carrier = c;
                mark(picks);
                check.setVisibility(carrier.check != null ? View.VISIBLE : View.GONE);
            });
            choices.addView(t);
        }
        mark(picks);
        codes.addView(choices);
        codes.addView(ui.primary("Forward all calls", R.drawable.ic_forward, Ui.BRAND, v -> {
            String n = digits(number.getText().toString());
            if (n.replace("+", "").length() < 7) {
                Toast.makeText(this, "Type the number of the phone you carry.", Toast.LENGTH_SHORT).show();
                return;
            }
            relay.prefs.setCarryNumber(n);
            dial(carrier.forwardTo(n));
        }), spaced());
        codes.addView(ui.secondary("Stop forwarding", 0, v -> dial(carrier.off)), spaced());
        check.setVisibility(carrier.check != null ? View.VISIBLE : View.GONE);
        codes.addView(check, spaced());
        codes.addView(hint("Each opens the dialer with the code filled in. Tap call."));
        col.addView(hint("Forwarded calls may use your plan's minutes."));
    }

    private void chevron(TextView t, boolean open) {
        android.graphics.drawable.Drawable d = getDrawable(open ? R.drawable.ic_up : R.drawable.ic_down).mutate();
        d.setTint(ui.link);
        d.setBounds(0, 0, ui.dp(18), ui.dp(18));
        t.setCompoundDrawablesRelative(null, null, d, null);
    }

    private void mark(TextView[] picks) {
        for (TextView t : picks) {
            boolean on = t.getTag() == carrier;
            android.graphics.drawable.Drawable d = on ? getDrawable(R.drawable.ic_check).mutate() : null;
            if (d != null) {
                d.setTint(Ui.BRAND.hi);
                d.setBounds(0, 0, ui.dp(18), ui.dp(18));
            }
            t.setCompoundDrawablesRelative(null, null, d, null);
            t.setTypeface(ui.face(on ? Ui.HEAVY : Ui.BOLD));
        }
    }

    private void tryIt(LinearLayout col) {
        col.addView(title("Try it"));
        LinearLayout g = ui.group();
        String[][] steps = {
                { "Text this number from another phone", "It shows up in Paigr within a few seconds." },
                { "Call this number", "With forwarding on, the phone you carry rings." },
                { "Reply from the phone you carry", "It goes out from here, from your number." } };
        for (int i = 0; i < steps.length; i++) {
            Ui.Row r = ui.row(0, 0, (i + 1) + ". " + steps[i][0], null);
            r.sub(steps[i][1]);
            g.addView(r.view);
        }
        col.addView(g, spaced());
        col.addView(hint("Then leave this phone plugged in and on Wi-Fi. If it stops checking in, Paigr says so."));
    }

    // ------------------------------------------------------------------ helpers

    /** Opens the dialer with a carrier code filled in; the person taps call. No call permission is needed. */
    private void dial(String code) {
        open(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(code))));
    }

    static String digits(String s) {
        String t = s == null ? "" : s.trim();
        String d = t.replaceAll("[^0-9]", "");
        return t.startsWith("+") ? "+" + d : d;
    }

    private boolean charging() {
        try {
            Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            return b != null && b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean onWifi() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm == null) return false;
        Network n = cm.getActiveNetwork();
        NetworkCapabilities caps = n != null ? cm.getNetworkCapabilities(n) : null;
        return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "This device doesn't have that screen.", Toast.LENGTH_LONG).show();
        }
    }

    private LinearLayout.LayoutParams spaced() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = ui.dp(10);
        return lp;
    }

    private TextView title(String s) {
        TextView t = ui.label(s, 24f, Ui.BLACK, ui.text);
        t.setLetterSpacing(-0.02f);
        t.setPadding(ui.dp(4), 0, 0, ui.dp(6));
        return t;
    }

    private TextView sub(String s) {
        TextView t = ui.label(s, 17f, Ui.HEAVY, ui.text);
        t.setPadding(ui.dp(4), ui.dp(18), 0, ui.dp(2));
        return t;
    }

    private TextView para(String s) {
        TextView t = ui.label(s, 15f, Ui.BODY, ui.text2);
        t.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4));
        return t;
    }

    private TextView hint(String s) {
        TextView t = ui.label(s, 13f, Ui.MEDIUM, ui.text3);
        t.setPadding(ui.dp(4), ui.dp(10), ui.dp(4), 0);
        return t;
    }
}
