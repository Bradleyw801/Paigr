package com.textrelay.texts;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** The list of conversations: newest first, a keycap for each, and the relay phone's state at the top. */
final class InboxScreen extends Screen {
    private final LinearLayout header;
    private final LinearLayout titleRow;
    private final LinearLayout searchRow;
    private final EditText search;
    private final LinearLayout chip;
    private final Ui.Light chipLight;
    private final TextView chipText;
    private final LinearLayout banner;
    private final Ui.Light bannerLight;
    private final TextView bannerText;
    private final TextView bannerAction;
    private final FrameLayout more;
    private final LinearLayout power;
    final ListView list;
    private final Pull pull;
    private final LinearLayout empty;
    private final TextView emptyTitle;
    private final TextView emptySub;
    private final Adapter adapter = new Adapter();
    private String filter = "";

    InboxScreen(MainActivity a) {
        super(a);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(ui.bg);

        header = new LinearLayout(a);
        header.setOrientation(LinearLayout.VERTICAL);
        titleRow = new LinearLayout(a);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(ui.dp(16), 0, ui.dp(6), 0);
        titleRow.setMinimumHeight(ui.dp(58));
        TextView title = ui.label("Paigr", 27f, Ui.BLACK, ui.text);
        title.setLetterSpacing(-0.025f);
        title.setSingleLine(true);
        // On a narrow screen the chip and three buttons leave the title little room: it shrinks before it wraps.
        title.setAutoSizeTextTypeUniformWithConfiguration(18, 27, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        chip = new LinearLayout(a);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(ui.dp(6), 0, ui.dp(9), 0);
        chip.setBackground(ui.pressable(ui.rounded(ui.dark ? ui.surface : ui.surface2, 15), 15));
        chip.setFocusable(true);
        chip.setClickable(true);
        chip.setOnClickListener(v -> a.openSettings());
        chipLight = new Ui.Light(a);
        chip.addView(chipLight, new LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)));
        chipText = ui.line("Relay", 13f, Ui.HEAVY, ui.text2);
        chipText.setPadding(ui.dp(3), 0, 0, 0);
        chip.addView(chipText);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(30));
        clp.setMarginEnd(ui.dp(4));
        titleRow.addView(chip, clp);
        titleRow.addView(ui.iconButton(R.drawable.ic_search, "Search", ui.text, v -> startSearch(0)));
        more = ui.iconButton(R.drawable.ic_more, "More", ui.text, v -> moreMenu(v));
        titleRow.addView(more);
        titleRow.addView(ui.iconButton(R.drawable.ic_gear, "Settings", ui.text, v -> a.openSettings()));
        header.addView(titleRow);

        searchRow = new LinearLayout(a);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setPadding(ui.dp(6), ui.dp(7), ui.dp(10), ui.dp(7));
        searchRow.setVisibility(View.GONE);
        searchRow.addView(ui.iconButton(R.drawable.ic_back, "Close search", ui.text, v -> endSearch()));
        search = new EditText(a);
        search.setHint("Search conversations");
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setTypeface(ui.face(Ui.MEDIUM));
        search.setTextSize(16f);
        search.setTextColor(ui.text);
        search.setHintTextColor(ui.text3);
        search.setBackground(ui.rounded(ui.surface, 22));
        search.setPadding(ui.dp(16), 0, ui.dp(16), 0);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int af) {}

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {}

            @Override
            public void afterTextChanged(Editable s) {
                filter = s.toString().trim();
                render();
            }
        });
        search.setOnKeyListener((v, code, e) -> {
            if (e.getAction() == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_DOWN) {
                focusList();
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, ui.dp(44), 1f);
        slp.setMarginStart(ui.dp(4));
        searchRow.addView(search, slp);
        header.addView(searchRow);

        banner = new LinearLayout(a);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setPadding(ui.dp(10), ui.dp(4), ui.dp(4), ui.dp(4));
        banner.setVisibility(View.GONE);
        bannerLight = new Ui.Light(a);
        banner.addView(bannerLight, new LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)));
        bannerText = ui.line("", 14f, Ui.BOLD, ui.text);
        bannerText.setPadding(ui.dp(6), 0, ui.dp(6), 0);
        banner.addView(bannerText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bannerAction = ui.textButton("Reconnect", ui.link, v -> inbox.reconnectRelay());
        bannerAction.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8));
        banner.addView(bannerAction);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.setMargins(ui.dp(12), 0, ui.dp(12), ui.dp(6));
        header.addView(banner, blp);

        // Until Android lets Paigr run while the phone sleeps, texts can wait for its next waking: say so, once a week.
        power = new LinearLayout(a);
        power.setGravity(Gravity.CENTER_VERTICAL);
        power.setPadding(ui.dp(12), ui.dp(4), ui.dp(4), ui.dp(4));
        power.setVisibility(View.GONE);
        power.setBackground(ui.rounded(Ui.blend(ui.surface, ui.warn, ui.dark ? 0.16f : 0.1f), 14));
        TextView powerText = ui.label("Texts can be late while this phone sleeps. Allow background use so Paigr keeps listening.", 13.5f, Ui.BOLD, ui.text);
        powerText.setPadding(0, ui.dp(4), ui.dp(6), ui.dp(4));
        power.addView(powerText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView later = ui.textButton("Not now", ui.text2, v -> {
            a.prefs.snoozePower();
            power.setVisibility(View.GONE);
        });
        later.setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8));
        power.addView(later);
        TextView allow = ui.textButton("Allow", ui.link, v -> askBackground());
        allow.setPadding(ui.dp(8), ui.dp(8), ui.dp(10), ui.dp(8));
        power.addView(allow);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.setMargins(ui.dp(12), 0, ui.dp(12), ui.dp(6));
        header.addView(power, plp);
        col.addView(header);

        list = new ListView(a);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setCacheColorHint(Color.TRANSPARENT);
        list.setSelector(selector());
        list.setDrawSelectorOnTop(false);
        list.setVerticalScrollBarEnabled(false);
        list.setClipToPadding(false);
        list.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        list.setScrollingCacheEnabled(false);
        list.setAnimationCacheEnabled(false);
        list.setAdapter(adapter);
        list.setItemsCanFocus(false);
        list.setOnItemClickListener((p, v, pos, id) -> {
            Model.Conv c = adapter.getItem(pos);
            if (c == null) return;
            if (searchRow.getVisibility() == View.VISIBLE) hideKeyboard();
            a.openConversation(c.conv);
        });
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            Model.Conv c = adapter.getItem(pos);
            if (c == null) return false;
            rowMenu(v, c);
            return true;
        });

        FrameLayout body = new FrameLayout(a);
        pull = new Pull(a, list);
        body.addView(pull, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        empty = new LinearLayout(a);
        empty.setOrientation(LinearLayout.VERTICAL);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(ui.dp(32), 0, ui.dp(32), ui.dp(48));
        Ui.Keycap k = new Ui.Keycap(ui);
        k.set(Ui.BRAND, "", false);
        k.setGlyph(R.drawable.ic_sms);
        empty.addView(k, new LinearLayout.LayoutParams(ui.dp(60), ui.dp(60)));
        emptyTitle = ui.label("No texts yet", 18f, Ui.BLACK, ui.text);
        emptyTitle.setGravity(Gravity.CENTER);
        emptyTitle.setPadding(0, ui.dp(16), 0, ui.dp(4));
        empty.addView(emptyTitle);
        emptySub = ui.label("New texts to your relay phone show up here.", 14f, Ui.BODY, ui.text2);
        emptySub.setGravity(Gravity.CENTER);
        empty.addView(emptySub);
        empty.setVisibility(View.GONE);
        body.addView(empty, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        col.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root = col;
        render();
        relay();
    }

    // ------------------------------------------------------------------ menus

    private int unreadConversations() {
        int n = 0;
        for (Model.Conv c : inbox.convs.values()) if (c.unread > 0) n++;
        return n;
    }

    /** The list's menu: what there is to do with every conversation at once. */
    private void moreMenu(View anchor) {
        int n = unreadConversations();
        PopupWindow pop = popup();
        LinearLayout menu = (LinearLayout) pop.getContentView();
        menu.addView(action(R.drawable.ic_done_all, n == 0 ? "Nothing unread" : "Mark all as read", n > 0, () -> {
            pop.dismiss();
            inbox.markAllRead();
            a.say(n == 1 ? "1 conversation marked read." : n + " conversations marked read.", 2000);
        }));
        pop.showAsDropDown(anchor, -ui.dp(120), -ui.dp(6));
        menu.getChildAt(0).requestFocus();
    }

    /** One conversation's menu, from a long press on its row. */
    private void rowMenu(View anchor, Model.Conv c) {
        PopupWindow pop = popup();
        LinearLayout menu = (LinearLayout) pop.getContentView();
        menu.addView(action(R.drawable.ic_check, c.unread > 0 ? "Mark as read" : "Read", c.unread > 0, () -> {
            pop.dismiss();
            inbox.markRead(c.conv, true);
        }));
        int n = unreadConversations();
        if (n > (c.unread > 0 ? 1 : 0)) menu.addView(action(R.drawable.ic_done_all, "Mark all as read", true, () -> {
            pop.dismiss();
            inbox.markAllRead();
            a.say(n + " conversations marked read.", 2000);
        }));
        pop.showAsDropDown(anchor, ui.dp(60), -anchor.getHeight() / 2);
        menu.getChildAt(0).requestFocus();
    }

    private PopupWindow popup() {
        LinearLayout menu = new LinearLayout(a);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6));
        menu.setBackground(ui.rounded(ui.dark ? ui.surface2 : ui.surface, 16));
        menu.setElevation(ui.dp(8));
        PopupWindow pop = new PopupWindow(menu, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        pop.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        pop.setElevation(ui.dp(8));
        return pop;
    }

    private View action(int icon, String label, boolean enabled, Runnable onClick) {
        TextView t = ui.label(label, 15f, Ui.BOLD, enabled ? ui.text : ui.text3);
        Drawable d = a.getDrawable(icon).mutate();
        d.setTint(enabled ? ui.text2 : ui.text3);
        d.setBounds(0, 0, ui.dp(19), ui.dp(19));
        t.setCompoundDrawablesRelative(d, null, null, null);
        t.setCompoundDrawablePadding(ui.dp(12));
        t.setPadding(ui.dp(12), ui.dp(11), ui.dp(18), ui.dp(11));
        t.setBackground(ui.pressable(12));
        t.setFocusable(enabled);
        t.setClickable(enabled);
        t.setEnabled(enabled);
        t.setContentDescription(label);
        if (enabled) t.setOnClickListener(v -> onClick.run());
        return t;
    }

    /** The highlight a row gets: a ripple under a finger, a filled bar for a keypad's arrows. */
    private Drawable selector() {
        StateListDrawable keys = new StateListDrawable();
        GradientDrawable fill = ui.rounded(ui.dark ? ui.surface : ui.surface2, 16);
        GradientDrawable bar = ui.rounded(ui.text, 2);
        LayerDrawable focused = new LayerDrawable(new Drawable[] { new InsetDrawable(fill, ui.dp(6), ui.dp(2), ui.dp(6), ui.dp(2)),
                new InsetDrawable(bar, ui.dp(8), ui.dp(16), 0, ui.dp(16)) });
        focused.setLayerWidth(1, ui.dp(3));
        focused.setLayerGravity(1, Gravity.START | Gravity.FILL_VERTICAL);
        keys.addState(new int[] { android.R.attr.state_focused }, focused);
        keys.addState(new int[0], new ColorDrawable(Color.TRANSPARENT));
        return new LayerDrawable(new Drawable[] { ui.pressable(0), keys });
    }

    @Override
    void insets(int top, int bottom, boolean keyboard) {
        header.setPadding(0, top, 0, 0);
        list.setPadding(0, ui.dp(2), 0, bottom + ui.dp(12));
    }

    @Override
    void shown() {
        inbox.view = "inbox";
        render();
        relay();
        powerBanner();
        if (!list.hasFocus() && !search.hasFocus()) list.requestFocus();
    }

    /** Whether the background-use banner shows: linked, alerts on, not yet allowed, not snoozed. */
    void powerBanner() {
        android.os.PowerManager pm = a.getSystemService(android.os.PowerManager.class);
        boolean exempt = pm == null || pm.isIgnoringBatteryOptimizations(a.getPackageName());
        boolean show = !exempt && a.prefs.paired() && !Prefs.ALERT_OFF.equals(a.prefs.alertMode()) && !a.prefs.powerSnoozed();
        power.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void askBackground() {
        try {
            a.startActivity(new android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:" + a.getPackageName())));
        } catch (android.content.ActivityNotFoundException e) {
            try {
                a.startActivity(new android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (android.content.ActivityNotFoundException ignored) {
                a.say("This phone has no background-use setting to open.", 3000);
            }
        }
    }

    @Override
    void changed(int what, boolean end) {
        if ((what & Inbox.LIST) != 0) render();
        if ((what & Inbox.RELAY) != 0) relay();
    }

    @Override
    boolean back() {
        if (searchRow.getVisibility() == View.VISIBLE) {
            endSearch();
            return true;
        }
        return false;
    }

    /** Typing on a hardware keyboard searches. */
    @Override
    boolean typed(KeyEvent e) {
        int c = printable(e);
        if (c == 0 || search.hasFocus()) return false;
        startSearch(c);
        return true;
    }

    void startSearch(int first) {
        titleRow.setVisibility(View.GONE);
        searchRow.setVisibility(View.VISIBLE);
        search.requestFocus();
        if (first != 0) {
            search.append(new String(Character.toChars(first)));
        } else {
            InputMethodManager im = a.getSystemService(InputMethodManager.class);
            if (im != null) im.showSoftInput(search, 0);
        }
    }

    void endSearch() {
        hideKeyboard();
        search.setText("");
        searchRow.setVisibility(View.GONE);
        titleRow.setVisibility(View.VISIBLE);
        list.requestFocus();
    }

    private void focusList() {
        list.requestFocus();
    }

    private void hideKeyboard() {
        InputMethodManager im = a.getSystemService(InputMethodManager.class);
        if (im != null) im.hideSoftInputFromWindow(search.getWindowToken(), 0);
    }

    private void render() {
        List<Model.Conv> items = inbox.list(filter);
        adapter.set(items);
        boolean none = items.isEmpty() && (inbox.loadedInbox || inbox.fromDisk || !filter.isEmpty());
        empty.setVisibility(none ? View.VISIBLE : View.GONE);
        if (!filter.isEmpty()) {
            emptyTitle.setText("Nothing matches");
            emptySub.setText("No conversation has “" + filter + "” in its name or last message.");
        } else {
            emptyTitle.setText("No texts yet");
            emptySub.setText("New texts to your relay phone show up here.");
        }
    }

    private void relay() {
        Inbox.Health h = Inbox.health(inbox.relay, System.currentTimeMillis());
        int color = colorOf(h.level);
        boolean trying = inbox.reconnecting();
        chipLight.set(color, trying);
        chipText.setText(h.chip);
        if (inbox.torDown) {
            // Nothing reaches the inbox until Tor runs: that comes before anything about the relay phone.
            boolean orbot = Tor.orbotInstalled(a);
            banner.setVisibility(View.VISIBLE);
            bannerLight.set(ui.bad, false);
            bannerText.setText("Tor isn't running");
            banner.setBackground(ui.rounded(Ui.blend(ui.surface, ui.bad, ui.dark ? 0.16f : 0.1f), 14));
            bannerAction.setText(orbot ? "Start Orbot" : "Get Orbot");
            bannerAction.setOnClickListener(v -> {
                if (orbot) Tor.startOrbot(a);
                else Tor.getOrbot(a);
            });
            bannerAction.setVisibility(View.VISIBLE);
            return;
        }
        bannerAction.setText("Reconnect");
        bannerAction.setOnClickListener(v -> inbox.reconnectRelay());
        boolean show = h.fix && inbox.relay != null;
        banner.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            bannerLight.set(color, trying);
            bannerText.setText(trying ? "Reconnecting…" : h.title);
            banner.setBackground(ui.rounded(Ui.blend(ui.dark ? ui.surface : ui.surface, color, ui.dark ? 0.16f : 0.1f), 14));
            bannerAction.setVisibility(trying ? View.GONE : View.VISIBLE);
        }
    }

    int colorOf(String level) {
        switch (level) {
            case "ok": return ui.ok;
            case "warn": return ui.warn;
            case "bad": return ui.bad;
            default: return ui.text3;
        }
    }

    // ------------------------------------------------------------------ rows

    /** One conversation: its keycap, name and time, the last message, and how many are unread. */
    final class Row extends LinearLayout {
        final Ui.Keycap key;
        final TextView name;
        final TextView time;
        final TextView snippet;
        final TextView badge;

        Row(Context c) {
            super(c);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(ui.dp(14), ui.dp(9), ui.dp(14), ui.dp(9));
            setMinimumHeight(ui.dp(64));
            key = new Ui.Keycap(ui);
            addView(key, new LinearLayout.LayoutParams(ui.dp(46), ui.dp(46)));
            LinearLayout texts = new LinearLayout(c);
            texts.setOrientation(VERTICAL);
            LinearLayout top = new LinearLayout(c);
            top.setGravity(Gravity.CENTER_VERTICAL);
            name = ui.line("", 15.5f, Ui.BOLD, ui.text);
            top.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            time = ui.line("", 12f, Ui.BOLD, ui.text3);
            time.setPadding(ui.dp(8), 0, 0, 0);
            top.addView(time);
            texts.addView(top);
            LinearLayout bottom = new LinearLayout(c);
            bottom.setGravity(Gravity.CENTER_VERTICAL);
            bottom.setPadding(0, ui.dp(3), 0, 0);
            snippet = ui.line("", 14f, Ui.BODY, ui.text2);
            bottom.addView(snippet, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            badge = ui.label("", 11.5f, Ui.HEAVY, Color.WHITE);
            badge.setGravity(Gravity.CENTER);
            badge.setMinWidth(ui.dp(20));
            badge.setPadding(ui.dp(6), 0, ui.dp(6), 0);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(20));
            blp.setMarginStart(ui.dp(8));
            bottom.addView(badge, blp);
            texts.addView(bottom);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tlp.setMarginStart(ui.dp(12));
            addView(texts, tlp);
        }

        /** What the row last wore, so a bind while scrolling sets only what changed (typefaces and drawables cost). */
        private Boolean wasUnread;
        private Ui.Hue badgeHue;

        void bind(Model.Conv c) {
            Ui.Hue h = Ui.hueFor(c.conv);
            key.set(h, c.name, c.group);
            name.setText(c.name);
            time.setText(Format.listTime(a, c.lastTs));
            String who = "out".equals(c.lastDir) ? "You: " : c.group && !c.lastSender.isEmpty() ? c.lastSender + ": " : "";
            snippet.setText(who + Words.snippet(c.lastText).replace('\n', ' '));
            boolean unread = c.unread > 0;
            if (wasUnread == null || wasUnread != unread) {
                wasUnread = unread;
                name.setTypeface(ui.face(unread ? Ui.BLACK : Ui.BOLD));
                snippet.setTypeface(ui.face(unread ? Ui.BOLD : Ui.BODY));
                snippet.setTextColor(unread ? ui.text : ui.text2);
                time.setTypeface(ui.face(unread ? Ui.BLACK : Ui.BOLD));
                badge.setVisibility(unread ? View.VISIBLE : View.GONE);
            }
            time.setTextColor(unread ? (ui.dark ? Ui.blend(h.hi, Color.WHITE, 0.35f) : h.base) : ui.text3);
            if (unread) {
                badge.setText(c.unread > 99 ? "99+" : String.valueOf(c.unread));
                if (badgeHue != h) {
                    badgeHue = h;
                    badge.setBackground(ui.rounded(h.base, 10));
                }
            }
            setContentDescription(c.name + (unread ? ", " + c.unread + " unread" : "") + ". " + snippet.getText());
        }
    }

    private final class Adapter extends BaseAdapter {
        private List<Model.Conv> items = new ArrayList<>();

        void set(List<Model.Conv> next) {
            items = next;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Model.Conv getItem(int position) {
            return position >= 0 && position < items.size() ? items.get(position) : null;
        }

        @Override
        public long getItemId(int position) {
            return items.get(position).conv.hashCode();
        }

        @Override
        public boolean hasStableIds() {
            return true;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Row r = convertView instanceof Row ? (Row) convertView : new Row(parent.getContext());
            r.bind(items.get(position));
            return r;
        }
    }

    // ------------------------------------------------------------------ pull to refresh

    /**
     * Pulling the list down from its top brings a ring down; letting go far enough refreshes. The list itself scrolls
     * on its own; this only steps in once it's at the top and the finger keeps going down.
     */
    final class Pull extends FrameLayout {
        private final ListView child;
        private final Art.Ring ring;
        private final int slop;
        private final int size;
        private final float threshold;
        private float startY;
        private boolean dragging;
        private boolean refreshing;
        private float distance;

        Pull(Context c, ListView child) {
            super(c);
            this.child = child;
            addView(child, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            size = ui.dp(42);
            threshold = ui.dp(70);
            ring = new Art.Ring(ui);
            LayoutParams lp = new LayoutParams(size, size, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            addView(ring, lp);
            ring.setTranslationY(-size);
            ring.setAlpha(0f);
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
        }

        private boolean atTop() {
            return !child.canScrollVertically(-1);
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            if (refreshing) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startY = e.getY();
                    dragging = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (!dragging && atTop() && e.getY() - startY > slop) {
                        dragging = true;
                        startY = e.getY();
                        return true;
                    }
                    break;
                default:
                    break;
            }
            return false;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (!dragging) return super.onTouchEvent(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    distance = Math.max(0, Math.min(threshold * 1.5f, (e.getY() - startY) * 0.55f));
                    ring.setTranslationY(distance - size);
                    ring.setAlpha(Math.min(1f, distance / (threshold * 0.6f)));
                    ring.progress(distance / threshold);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    if (distance >= threshold) start();
                    else settle();
                    return true;
                default:
                    return true;
            }
        }

        private void start() {
            refreshing = true;
            ring.animate().translationY(ui.dp(14)).setDuration(160).start();
            ring.spin(true);
            inbox.refresh(() -> {
                refreshing = false;
                ring.spin(false);
                settle();
            });
        }

        private void settle() {
            distance = 0;
            ring.animate().translationY(-size).alpha(0f).setDuration(200).start();
        }
    }

    /** Tests: pull to refresh as a finger would. */
    void refreshForTests() {
        pull.start();
    }
}
