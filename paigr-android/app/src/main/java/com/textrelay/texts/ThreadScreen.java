package com.textrelay.texts;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.Editable;
import android.text.InputType;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One conversation, in its colour: the header, your bubbles and the send button wear it. Reactions sit under the
 * message they're to, a quoted reply shows what it answers, pictures and files go out from the composer, and the
 * keypad's arrows scroll while the keyboard types straight into the reply. Rows are bound while the list scrolls,
 * so nothing here makes a drawable, a formatter or a regex match it can keep from last time.
 */
final class ThreadScreen extends Screen {
    private static final int DAY = 0, IN = 1, OUT = 2;

    String conv;
    private Ui.Hue hue = Ui.BRAND;
    private final FrameLayout headerBox;
    private final LinearLayout header;
    private final LinearLayout findBar;
    private final Ui.Keycap key;
    private final TextView name;
    private final TextView backBadge;
    private final EditText find;
    private final TextView findCount;
    private final TextView notice;
    final ListView list;
    private final TextView earlier;
    private final LinearLayout composer;
    private final LinearLayout replyBar;
    private final View replyRule;
    private final TextView replyWho;
    private final TextView replyText;
    private final LinearLayout attachBar;
    private final Ui.Picture attachPicture;
    private final FrameLayout attachChip;
    private final TextView attachName;
    private final TextView attachSize;
    final EditText input;
    private final TextView emojiButton;
    private final FrameLayout attachButton;
    private final FrameLayout send;
    private final ImageView sendIcon;
    private final Emoji.Panel emojis;
    private final Adapter adapter = new Adapter();
    private final Map<String, String> drafts = new HashMap<>();
    /** Messages showing their time (tapped). */
    private final Set<String> timed = new HashSet<>();
    private boolean loadingEarlier;
    /** The newest message is in view: anything new keeps it there. False once the reader scrolls back. */
    private boolean atEnd = true;
    private boolean scrolling;
    private String query = "";
    private final List<int[]> marks = new ArrayList<>();   // [message index in the thread, start]
    private int mark = -1;
    private int width;
    /** The message a reply is to, while one is being written. */
    private Model.Msg replyTo;
    /** The file about to go out with the next message. */
    private Inbox.Attachment pending;
    /** What the emoji panel's pick does: into the reply, or a reaction to this message. */
    private Model.Msg reactTo;
    /** Links and search marks for each message's text, kept while the search is empty. */
    private final Map<String, Object[]> styledCache = new HashMap<>();
    private final Map<Integer, Drawable> tileIcons = new HashMap<>();

    ThreadScreen(MainActivity a) {
        super(a);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(ui.bg);

        headerBox = new FrameLayout(a);
        header = new LinearLayout(a);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(ui.dp(4), 0, ui.dp(4), 0);
        header.setMinimumHeight(ui.dp(58));
        FrameLayout back = ui.iconButton(R.drawable.ic_back, "Back", Color.WHITE, v -> a.onBackPressed());
        backBadge = ui.label("", 10.5f, Ui.BLACK, 0xFF14172E);
        backBadge.setGravity(Gravity.CENTER);
        backBadge.setMinWidth(ui.dp(17));
        backBadge.setPadding(ui.dp(4), 0, ui.dp(4), 0);
        backBadge.setBackground(ui.rounded(Color.WHITE, 9));
        backBadge.setVisibility(View.GONE);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(17), Gravity.TOP | Gravity.END);
        blp.topMargin = ui.dp(3);
        back.addView(backBadge, blp);
        header.addView(back);
        LinearLayout who = new LinearLayout(a);
        who.setGravity(Gravity.CENTER_VERTICAL);
        who.setPadding(ui.dp(4), ui.dp(6), ui.dp(8), ui.dp(6));
        who.setBackground(ui.pressable(16));
        who.setFocusable(true);
        who.setClickable(true);
        who.setContentDescription("Photos, files and links");
        who.setOnClickListener(v -> a.openDetails());
        key = new Ui.Keycap(ui);
        key.setGlass(true);
        who.addView(key, new LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)));
        name = ui.line("", 17f, Ui.BLACK, Color.WHITE);
        name.setLetterSpacing(-0.01f);
        name.setPadding(ui.dp(10), 0, 0, 0);
        who.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(who, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(ui.iconButton(R.drawable.ic_search, "Search in this conversation", Color.WHITE, v -> openFind()));
        header.addView(ui.iconButton(R.drawable.ic_grid, "Photos, files and links", Color.WHITE, v -> a.openDetails()));
        headerBox.addView(header);

        findBar = new LinearLayout(a);
        findBar.setGravity(Gravity.CENTER_VERTICAL);
        findBar.setPadding(ui.dp(4), ui.dp(7), ui.dp(4), ui.dp(7));
        findBar.setVisibility(View.GONE);
        findBar.addView(ui.iconButton(R.drawable.ic_close, "Close search", Color.WHITE, v -> closeFind()));
        find = new EditText(a);
        find.setHint("Search this conversation");
        find.setSingleLine(true);
        find.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        find.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        find.setTypeface(ui.face(Ui.MEDIUM));
        find.setTextSize(15.5f);
        find.setTextColor(Color.WHITE);
        find.setHintTextColor(0xB3FFFFFF);
        find.setBackground(ui.rounded(0x33FFFFFF, 20));
        find.setPadding(ui.dp(14), 0, ui.dp(14), 0);
        find.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int af) {}

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {}

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase(Locale.ROOT);
                collect(true);
            }
        });
        find.setOnEditorActionListener((v, id, e) -> {
            step(-1);
            return true;
        });
        findBar.addView(find, new LinearLayout.LayoutParams(0, ui.dp(40), 1f));
        findCount = ui.line("", 12.5f, Ui.HEAVY, Color.WHITE);
        findCount.setPadding(ui.dp(8), 0, ui.dp(2), 0);
        findBar.addView(findCount);
        findBar.addView(ui.iconButton(R.drawable.ic_up, "Earlier match", Color.WHITE, v -> step(-1)));
        findBar.addView(ui.iconButton(R.drawable.ic_down, "Later match", Color.WHITE, v -> step(1)));
        headerBox.addView(findBar);
        col.addView(headerBox);

        notice = ui.label("You can reply after their next text reaches the relay phone.", 13f, Ui.BOLD, ui.text);
        notice.setGravity(Gravity.CENTER);
        notice.setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8));
        notice.setVisibility(View.GONE);
        col.addView(notice);

        list = new ListView(a);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setStackFromBottom(true);
        list.setTranscriptMode(AbsListView.TRANSCRIPT_MODE_NORMAL);
        list.setCacheColorHint(Color.TRANSPARENT);
        list.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        list.setVerticalScrollBarEnabled(false);
        list.setScrollingCacheEnabled(false);   // no bitmaps of rows made for a fling: a small phone draws them faster itself
        list.setAnimationCacheEnabled(false);
        list.setClipToPadding(false);
        list.setPadding(0, ui.dp(6), 0, ui.dp(8));
        earlier = ui.textButton("Earlier messages", ui.link, v -> loadEarlier());
        earlier.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(10));
        LinearLayout earlierBox = new LinearLayout(a);
        earlierBox.setGravity(Gravity.CENTER);
        earlierBox.addView(earlier);
        list.addHeaderView(earlierBox, null, false);
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            Item it = adapter.getItem(pos - list.getHeaderViewsCount());
            if (it == null || it.m == null) return;
            if (it.type == OUT && "failed".equals(it.m.status) && !it.m.unchecked && (!it.m.text.isEmpty() || (it.m.media != null && it.m.media.localUri != null))) {
                inbox.sendAgain(it.m);
                return;
            }
            if (!timed.remove(it.m.k)) timed.add(it.m.k);
            adapter.notifyDataSetChanged();
        });
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            Item it = adapter.getItem(pos - list.getHeaderViewsCount());
            if (it == null || it.m == null) return false;
            actions(v, it);
            return true;
        });
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int state) {
                if (state != SCROLL_STATE_IDLE) scrolling = true;
                else {
                    scrolling = false;
                    atEnd = atEndNow();
                }
            }

            @Override
            public void onScroll(AbsListView view, int first, int visible, int total) {
                if (scrolling) atEnd = atEndNow();
                if (first == 0 && visible > 0 && total > 1 && conv != null && inbox.hasEarlier(conv) && !loadingEarlier
                        && view.getChildAt(0) != null && view.getChildAt(0).getTop() >= -ui.dp(4) && !inbox.finding) {
                    loadEarlier();
                }
            }
        });
        col.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- what the next message answers
        replyBar = new LinearLayout(a);
        replyBar.setGravity(Gravity.CENTER_VERTICAL);
        replyBar.setPadding(ui.dp(14), ui.dp(6), ui.dp(4), 0);
        replyBar.setVisibility(View.GONE);
        replyRule = new View(a);
        replyBar.addView(replyRule, new LinearLayout.LayoutParams(ui.dp(3), ui.dp(34)));
        LinearLayout replyLines = new LinearLayout(a);
        replyLines.setOrientation(LinearLayout.VERTICAL);
        replyLines.setPadding(ui.dp(10), 0, ui.dp(6), 0);
        replyWho = ui.line("", 12.5f, Ui.HEAVY, ui.text2);
        replyText = ui.line("", 13.5f, Ui.BODY, ui.text3);
        replyLines.addView(replyWho);
        replyLines.addView(replyText);
        replyBar.addView(replyLines, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        replyBar.addView(ui.iconButton(R.drawable.ic_close, "Cancel the reply", ui.text2, v -> replyTo(null)));
        col.addView(replyBar);

        // ---- what goes out with it
        attachBar = new LinearLayout(a);
        attachBar.setGravity(Gravity.CENTER_VERTICAL);
        attachBar.setPadding(ui.dp(12), ui.dp(6), ui.dp(4), 0);
        attachBar.setVisibility(View.GONE);
        attachPicture = new Ui.Picture(ui, 10);
        attachBar.addView(attachPicture, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        attachChip = ui.chip(R.drawable.ic_file, Ui.BRAND.hi, ui.chipBg(Ui.BRAND.hi));
        attachBar.addView(attachChip);
        LinearLayout attachLines = new LinearLayout(a);
        attachLines.setOrientation(LinearLayout.VERTICAL);
        attachLines.setPadding(ui.dp(10), 0, ui.dp(6), 0);
        attachName = ui.line("", 13.5f, Ui.BOLD, ui.text);
        attachSize = ui.line("", 12.5f, Ui.BODY, ui.text3);
        attachLines.addView(attachName);
        attachLines.addView(attachSize);
        attachBar.addView(attachLines, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        attachBar.addView(ui.iconButton(R.drawable.ic_close, "Remove the attachment", ui.text2, v -> attach(null)));
        col.addView(attachBar);

        emojis = new Emoji.Panel(a, ui, a.prefs);
        emojis.setVisibility(View.GONE);
        emojis.onPick = this::picked;

        composer = new LinearLayout(a);
        composer.setGravity(Gravity.BOTTOM);
        composer.setPadding(ui.dp(4), ui.dp(6), ui.dp(8), ui.dp(8));
        emojiButton = ui.button("\uD83D\uDE42", 20f, Ui.BODY, ui.text);
        emojiButton.setGravity(Gravity.CENTER);
        emojiButton.setContentDescription("Emoji");
        emojiButton.setBackground(ui.pressable(14));
        emojiButton.setFocusable(true);
        emojiButton.setClickable(true);
        emojiButton.setIncludeFontPadding(false);
        emojiButton.setOnClickListener(v -> toggleEmojis());
        composer.addView(emojiButton, new LinearLayout.LayoutParams(ui.dp(40), ui.dp(44)));
        attachButton = ui.iconButton(R.drawable.ic_plus, "Attach a picture or file", ui.text, v -> a.pickAttachment());
        composer.addView(attachButton, new LinearLayout.LayoutParams(ui.dp(40), ui.dp(44)));
        input = new EditText(a);
        input.setHint("Text message");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT);
        input.setMaxLines(5);
        input.setMinHeight(ui.dp(44));
        input.setTypeface(ui.face(Ui.BODY));
        input.setTextSize(15.5f);
        input.setTextColor(ui.text);
        input.setHintTextColor(ui.text3);
        input.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(10));
        GradientDrawable field = ui.rounded(ui.surface, 22);
        if (!ui.dark) field.setStroke(Math.max(1, ui.dp(1)), ui.line);
        input.setBackground(ui.pressable(field, 22));
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int af) {}

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {}

            @Override
            public void afterTextChanged(Editable s) {
                sendable();
                if (conv != null) drafts.put(conv, s.toString());
            }
        });
        // A hardware keyboard's Enter sends; Shift+Enter starts a new line. The on-screen keyboard's Enter is a new line.
        input.setOnKeyListener((v, code, e) -> {
            boolean soft = (e.getFlags() & KeyEvent.FLAG_SOFT_KEYBOARD) != 0 || e.getDeviceId() == android.view.KeyCharacterMap.VIRTUAL_KEYBOARD;
            if ((code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) && !e.isShiftPressed() && !soft) {
                if (e.getAction() == KeyEvent.ACTION_DOWN) sendNow();
                return true;
            }
            return false;
        });
        input.setOnFocusChangeListener((v, has) -> {
            if (has && emojis.getVisibility() == View.VISIBLE) showEmojis(false);
        });
        input.setOnClickListener(v -> {
            if (emojis.getVisibility() == View.VISIBLE) showEmojis(false);
        });
        composer.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        send = new FrameLayout(a);
        send.setContentDescription("Send");
        send.setFocusable(true);
        send.setClickable(true);
        send.setOnClickListener(v -> sendNow());
        sendIcon = ui.icon(R.drawable.ic_send, Color.WHITE, 22);
        send.addView(sendIcon, new FrameLayout.LayoutParams(ui.dp(22), ui.dp(22), Gravity.CENTER));
        send.setAlpha(0.45f);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44));
        slp.setMarginStart(ui.dp(8));
        composer.addView(send, slp);
        col.addView(composer);

        col.addView(emojis, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(236)));
        root = col;
    }

    @Override
    void insets(int top, int bottom, boolean keyboard) {
        header.setPadding(ui.dp(4), top, ui.dp(4), 0);
        findBar.setPadding(ui.dp(4), top + ui.dp(7), ui.dp(4), ui.dp(7));
        boolean panel = emojis.getVisibility() == View.VISIBLE;
        composer.setPadding(ui.dp(4), ui.dp(6), ui.dp(8), (panel ? 0 : bottom) + ui.dp(8));
        emojis.setPadding(0, 0, 0, panel ? bottom : 0);
        if (keyboard && panel) showEmojis(false);
    }

    @Override
    boolean coloredTop() {
        return true;
    }

    /** Shows `c`, in its colour, with whatever is held for it (the inbox brings the rest). */
    void open(String c) {
        if (conv != null && !conv.equals(c)) {
            closeFind();
            replyTo(null);
            attach(null);
            showEmojis(false);
        }
        conv = c;
        hue = Ui.hueFor(c);
        atEnd = true;
        timed.clear();
        styledCache.clear();
        Model.Conv cv = inbox.convs.get(c);
        name.setText(cv != null ? cv.name : "Conversation");
        key.set(hue, cv != null ? cv.name : "", cv != null && cv.group);
        headerBox.setBackground(ui.gradient(hue.base, hue.partner, GradientDrawable.Orientation.TL_BR, 0));
        findBar.setBackground(null);
        GradientDrawable glow = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { Ui.alpha(hue.base, ui.dark ? 0.24f : 0.10f), Ui.alpha(hue.base, 0f) });
        android.graphics.drawable.LayerDrawable bg = new android.graphics.drawable.LayerDrawable(new Drawable[] {
                new android.graphics.drawable.ColorDrawable(ui.bg), glow });
        bg.setLayerHeight(1, ui.dp(220));
        bg.setLayerGravity(1, Gravity.TOP | Gravity.FILL_HORIZONTAL);
        list.setBackground(bg);
        send.setBackground(ui.pressable(ui.gradient(hue.base, hue.partner, GradientDrawable.Orientation.TL_BR, 22), 22));
        replyRule.setBackground(ui.rounded(hue.base, 2));
        String draft = drafts.get(c);
        input.setText(draft != null ? draft : "");
        input.setSelection(input.getText().length());
        inbox.openThread(c);
        render(true);
        list.setSelection(adapter.getCount() + list.getHeaderViewsCount() - 1);
    }

    @Override
    void shown() {
        inbox.view = "thread";
        if (conv != null) inbox.current = conv;
        backCount();
        Configuration cfg = a.getResources().getConfiguration();
        boolean keys = cfg.keyboard != Configuration.KEYBOARD_NOKEYS && cfg.hardKeyboardHidden != Configuration.HARDKEYBOARDHIDDEN_YES;
        if (keys) input.requestFocus();
        else list.requestFocus();
    }

    @Override
    void hidden() {
        hideKeyboard();
        showEmojis(false);
    }

    @Override
    boolean back() {
        if (findBar.getVisibility() == View.VISIBLE) {
            closeFind();
            return true;
        }
        if (emojis.getVisibility() == View.VISIBLE) {
            showEmojis(false);
            return true;
        }
        return false;
    }

    /** Typing on a hardware keyboard goes into the reply, wherever the highlight is. */
    @Override
    boolean typed(KeyEvent e) {
        if (printable(e) == 0 || input.hasFocus() || find.hasFocus() || emojis.getVisibility() == View.VISIBLE) return false;
        input.requestFocus();
        input.setSelection(input.getText().length());
        return false;   // the key goes on to the reply, now that it has the focus
    }

    @Override
    void changed(int what, boolean end) {
        if (conv == null) return;
        if ((what & Inbox.LIST) != 0) {
            backCount();
            Model.Conv cv = inbox.convs.get(conv);
            if (cv != null) {
                name.setText(cv.name);
                key.set(hue, cv.name, cv.group);
            }
        }
        if ((what & Inbox.THREAD) != 0 && !inbox.finding) render(end);
    }

    private void backCount() {
        int n = inbox.unreadElsewhere();
        backBadge.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
        backBadge.setText(n > 99 ? "99+" : String.valueOf(n));
    }

    // ------------------------------------------------------------------ drawing the thread

    /** One reaction to a message, as drawn: the emoji, how many, and whether one of them is this phone's. */
    static final class Chip {
        final String emoji;
        int count;
        boolean mine;
        final List<String> by = new ArrayList<>();

        Chip(String emoji) {
            this.emoji = emoji;
        }
    }

    /** One row: a day, or a message with what's drawn around it. */
    static final class Item {
        int type;
        String day;
        Model.Msg m;
        /** Where the message is in the thread (search marks count that way). */
        int index;
        boolean runStart, runEnd, sender;
        String status;
        boolean failed;
        /** What this message quotes, when it answers another, and that message's key when it's here. */
        Words.Quote quote;
        String quoteKey;
        String quoteWho;
        /** Reactions to this message, in the order they first came. */
        List<Chip> chips;
    }

    /** Why a reply wasn't sent, in this app's words: the relay phone reports a code, nothing the server writes. */
    static String notSent(String code) {
        switch (code == null ? "" : code) {
            case "stopped": return "Relay stopped mid-send. Check the relay phone first.";
            case "old": return "Too old to send.";
            case "future": return "Its time was off.";
            case "again": return "Already handled once.";
            case "unknown_conv": return "The relay phone doesn't know this conversation.";
            case "unchecked": return "The relay phone couldn't check it.";
            case "no_fallback": return "No way to reply yet. Turn on SMS fallback in Paigr Relay.";
            case "group": return "Group replies work after their next text.";
            case "no_number": return "No number for SMS fallback.";
            case "sms_failed": return "SMS fallback failed.";
            case "before_move": return "From before the move.";
            case "local": return "Couldn't reach the inbox.";
            case "too_big_here": return "That file is too big to send (25 MB at most).";
            case "no_attach": return "Attachments can't be sent to this conversation from here.";
            case "attach_perm": return "Allow SMS fallback in Paigr Relay to send attachments.";
            case "too_big": return "Too big to send as MMS from the relay phone.";
            case "mms_failed": return "MMS failed on the relay phone.";
            case "attach_fetch": return "The relay phone couldn't fetch the attachment.";
            default: return "The relay phone couldn't send it.";
        }
    }

    /** The message's words as drawn: a quoted reply by its answer. */
    static String shown(Model.Msg m) {
        if (m.unchecked) return m.text;
        Words.Quote q = Words.quote(m.text);
        return q != null ? q.body : m.text;
    }

    /** Which attachment kind a reaction or a quote names a message by. */
    private static boolean kindMatches(Model.Msg m, String kind) {
        if (kind == null) return false;
        if ("link".equals(kind)) return !m.text.isEmpty() && URL.matcher(m.text).find();
        if (m.media == null) return false;
        String mime = m.media.mime == null ? "" : m.media.mime.toLowerCase(Locale.US);
        switch (kind) {
            case "image": return mime.startsWith("image/");
            case "video": return mime.startsWith("video/");
            case "audio": return mime.startsWith("audio/");
            default: return true;
        }
    }

    /** The newest message before `from` that `words` quote (or that is `kind` of attachment); -1 for none. */
    private static int target(List<Model.Msg> msgs, int from, String words, String kind) {
        for (int j = from - 1; j >= 0 && j >= from - 400; j--) {
            Model.Msg t = msgs.get(j);
            if (t.unchecked) continue;
            if (words != null ? Words.sameWords(words, shown(t)) || Words.sameWords(words, t.text) : kindMatches(t, kind)) return j;
        }
        return -1;
    }

    private static String kindOfQuoteLine(String quoted) {
        String q = quoted == null ? "" : quoted.trim().toLowerCase(Locale.ROOT);
        switch (q) {
            case "picture": return "image";
            case "video": return "video";
            case "voice message": return "audio";
            case "attachment": return "file";
            default: return null;
        }
    }

    private List<Item> build(List<Model.Msg> msgs) {
        List<Item> out = new ArrayList<>();
        Model.Conv cv = inbox.convs.get(conv);
        boolean group = cv != null && cv.group;
        String convName = cv != null ? cv.name : "";
        int n = msgs.size();
        // Reactions ride along as texts: each is attached to the message it names, and not drawn as a row of its own
        // (unless it couldn't be sent, which the reader has to see).
        boolean[] hidden = new boolean[n];
        Map<Integer, List<Model.Msg>> reactions = new HashMap<>();
        for (int i = 0; i < n; i++) {
            Model.Msg m = msgs.get(i);
            if (m.unchecked || m.media != null) continue;
            Words.Reaction r = Words.reaction(m.text);
            if (r == null) continue;
            int j = target(msgs, i, r.to, r.kind);
            if (j < 0) continue;
            List<Model.Msg> list = reactions.get(j);
            if (list == null) reactions.put(j, list = new ArrayList<>());
            list.add(m);
            hidden[i] = !(m.out() && "failed".equals(m.status));
        }
        int lastOut = -1;
        for (int i = 0; i < n; i++) if (msgs.get(i).out() && !hidden[i]) lastOut = i;
        String lastDay = "";
        String[] days = new String[n];
        for (int i = 0; i < n; i++) days[i] = Format.day(msgs.get(i).ts);
        Model.Msg prev = null;
        int prevI = -1;
        for (int i = 0; i < n; i++) {
            if (hidden[i]) continue;
            Model.Msg m = msgs.get(i);
            String day = days[i];
            if (!day.equals(lastDay)) {
                Item d = new Item();
                d.type = DAY;
                d.day = day + ", " + Format.clock(a, m.ts);
                out.add(d);
                lastDay = day;
            }
            int nextI = -1;
            for (int j = i + 1; j < n; j++) if (!hidden[j]) { nextI = j; break; }
            Model.Msg next = nextI >= 0 ? msgs.get(nextI) : null;
            Item it = new Item();
            it.type = m.out() ? OUT : IN;
            it.m = m;
            it.index = i;
            it.runStart = !Model.sameRun(prev, m) || (prevI >= 0 && !days[prevI].equals(day));
            it.runEnd = !Model.sameRun(m, next) || (next != null && !days[nextI].equals(day));
            it.sender = !m.out() && group && !m.sender.isEmpty() && it.runStart && !m.unchecked;
            if (m.out() && ("failed".equals(m.status) || "queued".equals(m.status) || i == lastOut)) {
                if ("queued".equals(m.status)) it.status = "Sending…";
                else if ("failed".equals(m.status)) {
                    it.failed = true;
                    boolean again = !m.unchecked && (!m.text.isEmpty() || (m.media != null && m.media.localUri != null));
                    it.status = "Not sent. " + notSent(m.code) + (again ? " Tap to retry." : "");
                } else if ("sms".equals(m.via)) it.status = "Sent as SMS";
                else if ("mms".equals(m.via)) it.status = "Sent as MMS";
                else if ("phone".equals(m.via)) it.status = "Sent from the relay phone";
                else it.status = "Sent";
            }
            if (!m.unchecked) {
                it.quote = Words.quote(m.text);
                if (it.quote != null) {
                    String kind = kindOfQuoteLine(it.quote.quoted);
                    int j = target(msgs, i, kind == null ? it.quote.quoted : null, kind);
                    if (j >= 0) {
                        Model.Msg t = msgs.get(j);
                        it.quoteKey = t.k;
                        it.quoteWho = t.out() ? "You" : !t.sender.isEmpty() ? t.sender : convName;
                    }
                }
            }
            List<Model.Msg> rs = reactions.get(i);
            if (rs != null) {
                LinkedHashMap<String, Chip> chips = new LinkedHashMap<>();
                for (Model.Msg r : rs) {
                    Words.Reaction x = Words.reaction(r.text);
                    if (x == null) continue;
                    String by = r.out() ? "You" : !r.sender.isEmpty() ? r.sender : convName;
                    Chip c = chips.get(x.emoji);
                    if (x.removed) {
                        if (c != null && c.by.remove(by)) {
                            c.count--;
                            c.mine = c.by.contains("You");
                            if (c.count <= 0) chips.remove(x.emoji);
                        }
                        continue;
                    }
                    if (c == null) chips.put(x.emoji, c = new Chip(x.emoji));
                    if (c.by.contains(by)) continue;
                    c.by.add(by);
                    c.count++;
                    if (r.out()) c.mine = true;
                }
                if (!chips.isEmpty()) it.chips = new ArrayList<>(chips.values());
            }
            out.add(it);
            prev = m;
            prevI = i;
        }
        return out;
    }

    /** Draws the thread again; keeps the reader where they are unless `end` (or they were at the bottom). */
    private void render(boolean end) {
        if (conv == null) return;
        Model.Conv cv = inbox.convs.get(conv);
        boolean canReply = cv == null || cv.canReply;
        notice.setVisibility(canReply ? View.GONE : View.VISIBLE);
        notice.setBackgroundColor(ui.tint(hue));
        earlier.setVisibility(inbox.hasEarlier(conv) ? View.VISIBLE : View.GONE);
        earlier.setText(loadingEarlier ? "Loading…" : "Earlier messages");
        // what's at the top now, to keep it there
        int headers = list.getHeaderViewsCount();
        int firstPos = Math.max(list.getFirstVisiblePosition(), headers);
        View top = list.getChildAt(firstPos - list.getFirstVisiblePosition());
        String anchor = null;
        int offset = 0;
        Item at = adapter.getItem(firstPos - headers);
        if (at != null && top != null) {
            anchor = at.m != null ? at.m.k : "day:" + at.day;
            offset = top.getTop();
        }
        adapter.set(build(inbox.thread(conv)));
        if (end) atEnd = true;
        if (atEnd) {
            list.setSelection(adapter.getCount() + list.getHeaderViewsCount() - 1);
        } else if (anchor != null) {
            int idx = adapter.indexOf(anchor);
            if (idx >= 0) list.setSelectionFromTop(idx + list.getHeaderViewsCount(), offset);
        }
    }

    private boolean atEndNow() {
        int count = list.getCount();
        if (count == 0 || list.getChildCount() == 0) return true;
        if (list.getLastVisiblePosition() < count - 1) return false;
        View last = list.getChildAt(list.getChildCount() - 1);
        return last == null || last.getBottom() <= list.getHeight() - list.getPaddingBottom() + ui.dp(4);
    }

    private void loadEarlier() {
        if (loadingEarlier || conv == null) return;
        loadingEarlier = true;
        earlier.setText("Loading…");
        String c = conv;
        inbox.loadEarlier(added -> {
            loadingEarlier = false;
            if (!c.equals(conv)) return;
            render(false);
        });
        // when the inbox didn't answer for this thread (another opened meanwhile), let it try again
        list.postDelayed(() -> {
            if (loadingEarlier && !c.equals(conv)) loadingEarlier = false;
        }, 15_000);
    }

    /** Scrolls to a message (one that was quoted), and shows its time so it stands out. */
    private void jumpTo(String k) {
        int idx = adapter.indexOf(k);
        if (idx < 0) return;
        timed.add(k);
        adapter.notifyDataSetChanged();
        list.setSelectionFromTop(idx + list.getHeaderViewsCount(), Math.max(0, list.getHeight() / 3));
    }

    // ------------------------------------------------------------------ writing: replies, attachments, emoji

    private void sendable() {
        boolean has = input.getText().toString().trim().length() > 0 || pending != null;
        send.setAlpha(has ? 1f : 0.45f);
    }

    private void sendNow() {
        String typed = input.getText().toString().trim();
        if ((typed.isEmpty() && pending == null) || conv == null) return;
        String text = typed;
        if (replyTo != null && !typed.isEmpty()) text = Words.quoteText(replyTo.text, Words.kindOf(replyTo.media), typed);
        Inbox.Attachment att = pending;
        input.setText("");
        drafts.remove(conv);
        replyTo(null);
        attach(null);
        inbox.send(conv, text, att);
    }

    /** Starts (or cancels) a reply to `m`: the composer shows what it answers, and the message goes out quoting it. */
    void replyTo(Model.Msg m) {
        replyTo = m;
        replyBar.setVisibility(m == null ? View.GONE : View.VISIBLE);
        if (m == null) return;
        Model.Conv cv = inbox.convs.get(conv);
        replyWho.setText("Replying to " + (m.out() ? "you" : !m.sender.isEmpty() ? m.sender : cv != null ? cv.name : "them"));
        String what = shown(m);
        if (what.isEmpty() && m.media != null) what = m.media.kind();
        replyText.setText(what.replace('\n', ' '));
        input.requestFocus();
        InputMethodManager im = a.getSystemService(InputMethodManager.class);
        if (im != null) im.showSoftInput(input, 0);
    }

    /** A file from this phone to go out with the next message (null takes it off again). */
    void attach(Uri uri) {
        if (uri == null) {
            pending = null;
            attachBar.setVisibility(View.GONE);
            attachPicture.setBitmap(null);
            sendable();
            return;
        }
        String[] d = Media.describeLocal(a.getContentResolver(), uri);
        long size = Long.parseLong(d[2]);
        if (size > Inbox.ATTACH_MAX) {
            a.say("That file is too big to send (25 MB at most).", 4000);
            return;
        }
        try {
            a.getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (RuntimeException ignored) {
            // a picker that grants for this once is enough: the file is read as it goes out
        }
        pending = new Inbox.Attachment(uri, d[0], d[1], size);
        attachBar.setVisibility(View.VISIBLE);
        attachName.setText(d[1]);
        attachSize.setText(Opener.mediaKind(d[0]) + (size > 0 ? ", " + Format.bytes(size) : ""));
        boolean picture = d[0].startsWith("image/");
        attachPicture.setVisibility(picture ? View.VISIBLE : View.GONE);
        attachChip.setVisibility(picture ? View.GONE : View.VISIBLE);
        attachPicture.setBitmap(null);
        if (picture) {
            String want = uri.toString();
            inbox.media.local(want, ui.dp(44), b -> {
                if (pending != null && want.equals(pending.uri.toString())) attachPicture.setBitmap(b);
            });
        }
        sendable();
        input.requestFocus();
    }

    private void toggleEmojis() {
        boolean open = emojis.getVisibility() != View.VISIBLE;
        reactTo = null;
        showEmojis(open);
    }

    private void showEmojis(boolean on) {
        if (on == (emojis.getVisibility() == View.VISIBLE)) return;
        emojis.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) {
            hideKeyboard();
            input.clearFocus();
            emojis.focusGrid();
        } else {
            reactTo = null;
        }
        insets(a.insetTop, a.insetBottom, false);
    }

    /** The emoji picked: a reaction to the message the panel was opened for, else into the reply where the cursor is. */
    private void picked(String emoji) {
        if (reactTo != null) {
            Model.Msg m = reactTo;
            showEmojis(false);
            inbox.react(m, emoji, false);
            return;
        }
        int at = Math.max(0, input.getSelectionStart());
        input.getText().insert(at, emoji);
    }

    private void hideKeyboard() {
        InputMethodManager im = a.getSystemService(InputMethodManager.class);
        if (im != null) im.hideSoftInputFromWindow(input.getWindowToken(), 0);
    }

    // ------------------------------------------------------------------ search inside the conversation

    private void openFind() {
        if (conv == null) return;
        inbox.finding = true;
        header.setVisibility(View.GONE);
        findBar.setVisibility(View.VISIBLE);
        find.setText("");
        find.requestFocus();
        InputMethodManager im = a.getSystemService(InputMethodManager.class);
        if (im != null) im.showSoftInput(find, 0);
        if (!inbox.complete.contains(conv)) {
            findCount.setText("Loading…");
            String c = conv;
            inbox.loadWhole(c, () -> {
                if (!c.equals(conv) || !inbox.finding) return;
                findCount.setText("");
                adapter.set(build(inbox.thread(conv)));
                collect(true);
            });
        }
    }

    void closeFind() {
        if (!inbox.finding && findBar.getVisibility() != View.VISIBLE) return;
        inbox.finding = false;
        query = "";
        marks.clear();
        mark = -1;
        findBar.setVisibility(View.GONE);
        header.setVisibility(View.VISIBLE);
        InputMethodManager im = a.getSystemService(InputMethodManager.class);
        if (im != null) im.hideSoftInputFromWindow(find.getWindowToken(), 0);
        render(true);
    }

    /** Every match of the search, and the newest one in view, like stepping up from the bottom. */
    private void collect(boolean reset) {
        marks.clear();
        List<Model.Msg> msgs = inbox.thread(conv);
        if (!query.isEmpty()) {
            for (int i = 0; i < msgs.size(); i++) {
                Model.Msg m = msgs.get(i);
                if (m.unchecked || adapter.indexOf(m.k) < 0) continue;
                String t = shown(m).toLowerCase(Locale.ROOT);
                for (int at = t.indexOf(query); at >= 0; at = t.indexOf(query, at + query.length())) marks.add(new int[] { i, at });
            }
        }
        if (query.isEmpty()) findCount.setText("");
        else if (marks.isEmpty()) findCount.setText("None");
        if (reset || mark < 0 || mark >= marks.size()) mark = marks.size() - 1;
        adapter.notifyDataSetChanged();
        focusMark();
    }

    private void step(int d) {
        if (marks.isEmpty()) return;
        mark = (mark + d + marks.size()) % marks.size();
        adapter.notifyDataSetChanged();
        focusMark();
    }

    private void focusMark() {
        if (mark < 0 || mark >= marks.size()) return;
        findCount.setText((mark + 1) + " of " + marks.size());
        Model.Msg m = inbox.thread(conv).get(marks.get(mark)[0]);
        int idx = adapter.indexOf(m.k);
        if (idx >= 0) list.setSelectionFromTop(idx + list.getHeaderViewsCount(), Math.max(0, list.getHeight() / 3));
    }

    // ------------------------------------------------------------------ what a long press offers

    private void actions(View anchor, Item it) {
        Model.Msg m = it.m;
        LinearLayout menu = new LinearLayout(a);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6));
        menu.setBackground(ui.rounded(ui.dark ? ui.surface2 : ui.surface, 16));
        menu.setElevation(ui.dp(8));
        PopupWindow pop = new PopupWindow(menu, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        pop.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        pop.setElevation(ui.dp(8));
        if (!m.unchecked && !m.temp) {
            LinearLayout bar = new LinearLayout(a);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.setPadding(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(6));
            for (String e : Emoji.QUICK) {
                boolean mine = false;
                if (it.chips != null) for (Chip c : it.chips) if (c.emoji.equals(e) && c.mine) mine = true;
                TextView t = ui.button(e, 22f, Ui.BODY, ui.text);
                t.setGravity(Gravity.CENTER);
                t.setIncludeFontPadding(false);
                t.setPadding(ui.dp(4), ui.dp(5), ui.dp(4), ui.dp(5));
                t.setContentDescription((mine ? "Remove " : "React ") + e);
                t.setBackground(ui.pressable(mine ? ui.rounded(ui.tint(hue), 12) : null, 12));
                t.setFocusable(true);
                t.setClickable(true);
                boolean remove = mine;
                t.setOnClickListener(v -> {
                    pop.dismiss();
                    inbox.react(m, e, remove);
                });
                bar.addView(t, new LinearLayout.LayoutParams(ui.dp(38), ui.dp(38)));
            }
            FrameLayout more = ui.iconButton(R.drawable.ic_plus, "More emoji", ui.text2, v -> {
                pop.dismiss();
                reactTo = m;
                showEmojis(true);
            });
            bar.addView(more, new LinearLayout.LayoutParams(ui.dp(38), ui.dp(38)));
            menu.addView(bar);
            menu.addView(action(R.drawable.ic_back, "Reply", () -> {
                pop.dismiss();
                replyTo(m);
            }));
        }
        if (!m.unchecked && !m.text.isEmpty()) menu.addView(action(R.drawable.ic_copy, "Copy text", () -> {
            pop.dismiss();
            ClipboardManager cb = a.getSystemService(ClipboardManager.class);
            if (cb != null) cb.setPrimaryClip(ClipData.newPlainText("Text", shown(m)));
            a.say("Copied.", 1500);
        }));
        if (m.media != null && (m.media.path != null || m.media.preview != null)) menu.addView(action(R.drawable.ic_save, "Save to Downloads", () -> {
            pop.dismiss();
            Viewer.save(a, m);
        }));
        if (m.out() && "failed".equals(m.status) && !m.unchecked && (!m.text.isEmpty() || (m.media != null && m.media.localUri != null))) {
            menu.addView(action(R.drawable.ic_retry, "Send again", () -> {
                pop.dismiss();
                inbox.sendAgain(m);
            }));
        }
        menu.addView(action(R.drawable.ic_info, Format.day(m.ts) + ", " + Format.clock(a, m.ts), pop::dismiss));
        pop.showAsDropDown(anchor, m.out() ? ui.dp(60) : ui.dp(12), -anchor.getHeight() / 2);
        if (menu.getChildCount() > 0) menu.getChildAt(0).requestFocus();
    }

    private View action(int icon, String label, Runnable onClick) {
        TextView t = ui.label(label, 15f, Ui.BOLD, ui.text);
        Drawable d = a.getDrawable(icon).mutate();
        d.setTint(ui.text2);
        d.setBounds(0, 0, ui.dp(19), ui.dp(19));
        t.setCompoundDrawablesRelative(d, null, null, null);
        t.setCompoundDrawablePadding(ui.dp(12));
        t.setPadding(ui.dp(12), ui.dp(11), ui.dp(18), ui.dp(11));
        t.setBackground(ui.pressable(12));
        t.setFocusable(true);
        t.setClickable(true);
        t.setContentDescription(label);
        t.setOnClickListener(v -> onClick.run());
        return t;
    }

    // ------------------------------------------------------------------ the rows

    private static final Pattern URL = Pattern.compile("\\bhttps?://[^\\s<>\"']+|\\bwww\\.[^\\s<>\"']+\\.[^\\s<>\"']+", Pattern.CASE_INSENSITIVE);

    private static String trimUrl(String u) {
        return u.replaceAll("[.,!?;:'\")\\]]+$", "");
    }

    /** Text with tappable links and the search's matches marked; nothing in a message is ever read as markup. */
    private CharSequence styled(Model.Msg m, String text, int msgIndex, boolean out) {
        String cacheKey = query.isEmpty() ? m.k + (out ? ":o" : ":i") : null;
        if (cacheKey != null) {
            Object[] hit = styledCache.get(cacheKey);
            if (hit != null && text.equals(hit[0])) return (CharSequence) hit[1];
        }
        SpannableString s = new SpannableString(text);
        Matcher u = URL.matcher(text);
        while (u.find()) {
            String raw = u.group();
            String url = trimUrl(raw);
            if (url.isEmpty()) continue;
            String href = url.toLowerCase(Locale.ROOT).startsWith("http") ? url : "https://" + url;
            s.setSpan(new Link(href, out ? Color.WHITE : ui.link), u.start(), u.start() + url.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (!query.isEmpty()) {
            for (int i = 0; i < marks.size(); i++) {
                int[] mk = marks.get(i);
                if (mk[0] != msgIndex) continue;
                int color = i == mark ? 0xFFFFB300 : (ui.dark ? 0x99B38600 : 0xFFFFE28A);
                s.setSpan(new BackgroundColorSpan(color), mk[1], Math.min(text.length(), mk[1] + query.length()), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        if (cacheKey != null) {
            if (styledCache.size() > 400) styledCache.clear();
            styledCache.put(cacheKey, new Object[] { text, s });
        }
        return s;
    }

    private final class Link extends ClickableSpan {
        final String href;
        final int color;

        Link(String href, int color) {
            this.href = href;
            this.color = color;
        }

        @Override
        public void onClick(View widget) {
            try {
                a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(href)));
            } catch (android.content.ActivityNotFoundException e) {
                a.say("Nothing on this phone can open that link.", 2500);
            }
        }

        @Override
        public void updateDrawState(TextPaint p) {
            p.setColor(color);
            p.setUnderlineText(true);
        }
    }

    /** A tap on a link opens it; anywhere else it's the row's tap (show the time, send again). */
    private static boolean tapLink(TextView t, MotionEvent e) {
        if (!(t.getText() instanceof Spannable)) return false;
        Layout layout = t.getLayout();
        if (layout == null) return false;
        int x = (int) e.getX() - t.getTotalPaddingLeft() + t.getScrollX();
        int y = (int) e.getY() - t.getTotalPaddingTop() + t.getScrollY();
        int line = layout.getLineForVertical(y);
        int off = layout.getOffsetForHorizontal(line, x);
        ClickableSpan[] spans = ((Spannable) t.getText()).getSpans(off, off, ClickableSpan.class);
        if (spans.length == 0) return false;
        if (e.getAction() == MotionEvent.ACTION_UP) spans[0].onClick(t);
        return true;
    }

    private int bubbleMax() {
        if (width == 0) width = a.getResources().getDisplayMetrics().widthPixels;
        return (int) (width * 0.78f);
    }

    private float[] corners(boolean out, boolean runStart, boolean runEnd) {
        float big = ui.dp(19), small = ui.dp(6);
        float tl = out ? big : (runStart ? big : small);
        float bl = out ? big : (runEnd ? big : small);
        float tr = out ? (runStart ? big : small) : big;
        float br = out ? (runEnd ? big : small) : big;
        return new float[] { tl, tl, tr, tr, br, br, bl, bl };
    }

    private Drawable bubble(boolean out, boolean runStart, boolean runEnd, boolean unchecked) {
        GradientDrawable d;
        if (unchecked) {
            d = new GradientDrawable();
            d.setColor(ui.dark ? ui.surface : ui.surface2);
            d.setStroke(Math.max(1, ui.dp(1)), ui.line);
        } else if (out) {
            d = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] { hue.base, hue.partner });
        } else {
            d = new GradientDrawable();
            if (ui.dark) d.setColor(hue.tintDark);
            else {
                d.setColor(Color.WHITE);
                d.setStroke(Math.max(1, ui.dp(1)), ui.line);
            }
        }
        d.setCornerRadii(corners(out, runStart, runEnd));
        return d;
    }

    /** An icon for a file tile, tinted for its side; one per icon and side, shared by every tile that shows it. */
    private Drawable tileIcon(int res, boolean out) {
        int k = res * 2 + (out ? 1 : 0);
        Drawable d = tileIcons.get(k);
        if (d == null) {
            d = a.getDrawable(res).mutate();
            d.setTint(out ? Color.WHITE : hue.base);
            d.setBounds(0, 0, ui.dp(20), ui.dp(20));
            tileIcons.put(k, d);
        }
        return d;
    }

    /** A message row: the sender (in a group), the bubble with its picture or file, the time, and its status. */
    final class MsgRow extends LinearLayout {
        final boolean out;
        final TextView sender;
        final Ui.Picture picture;
        final FrameLayout pictureBox;
        final ImageView play;
        final TextView tile;
        final LinearLayout bubble;
        final LinearLayout quoteBox;
        final View quoteRule;
        final TextView quoteWho;
        final TextView quoteText;
        final TextView text;
        final LinearLayout chips;
        final TextView stamp;
        final TextView status;
        String showing;
        /** The bubble shapes this row has worn, by run position (and whether the message checked), for this colour. */
        private final Drawable[] bubbles = new Drawable[8];
        private Ui.Hue bubblesFor;
        private Drawable pictureBg, tileBg, quoteBg, chipBg, chipMineBg;
        private int paddedTop = -1, paddedBottom = -1;

        MsgRow(Context c, boolean out) {
            super(c);
            this.out = out;
            setOrientation(VERTICAL);
            setGravity(out ? Gravity.END : Gravity.START);
            setPadding(ui.dp(10), 0, ui.dp(10), 0);
            sender = ui.line("", 12f, Ui.HEAVY, ui.text3);
            sender.setPadding(ui.dp(14), ui.dp(6), ui.dp(14), ui.dp(3));
            addView(sender);
            pictureBox = new FrameLayout(c);
            picture = new Ui.Picture(ui, 18);
            pictureBox.addView(picture, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            play = ui.icon(R.drawable.ic_play, Color.WHITE, 40);
            play.setBackground(ui.rounded(0x66000000, 26));
            play.setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10));
            pictureBox.addView(play, new FrameLayout.LayoutParams(ui.dp(52), ui.dp(52), Gravity.CENTER));
            LayoutParams plp = new LayoutParams(ui.dp(200), ui.dp(150));
            plp.bottomMargin = ui.dp(2);
            addView(pictureBox, plp);
            tile = ui.label("", 14f, Ui.BOLD, out ? Color.WHITE : ui.text);
            tile.setPadding(ui.dp(14), ui.dp(11), ui.dp(16), ui.dp(11));
            tile.setCompoundDrawablePadding(ui.dp(10));
            LayoutParams tlp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.bottomMargin = ui.dp(2);
            addView(tile, tlp);
            quoteBox = new LinearLayout(c);
            bubble = new Bubble(c, quoteBox);
            bubble.setOrientation(VERTICAL);
            bubble.setPadding(ui.dp(13), ui.dp(8), ui.dp(13), ui.dp(9));
            quoteBox.setGravity(Gravity.CENTER_VERTICAL);
            quoteBox.setPadding(0, 0, ui.dp(8), 0);
            quoteBox.setClickable(true);
            quoteBox.setFocusable(true);
            quoteRule = new View(c);
            quoteBox.addView(quoteRule, new LayoutParams(ui.dp(3), ViewGroup.LayoutParams.MATCH_PARENT));
            LinearLayout quoteLines = new LinearLayout(c);
            quoteLines.setOrientation(VERTICAL);
            quoteLines.setPadding(ui.dp(8), ui.dp(5), 0, ui.dp(5));
            quoteWho = ui.line("", 12f, Ui.HEAVY, out ? 0xE6FFFFFF : ui.text2);
            quoteText = ui.label("", 13.5f, Ui.BODY, out ? 0xD9FFFFFF : ui.text2);
            quoteText.setMaxLines(2);
            quoteText.setEllipsize(TextUtils.TruncateAt.END);
            quoteLines.addView(quoteWho);
            quoteLines.addView(quoteText);
            quoteBox.addView(quoteLines, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            LayoutParams qlp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            qlp.bottomMargin = ui.dp(6);
            bubble.addView(quoteBox, qlp);
            text = ui.label("", 15.5f, Ui.BODY, out ? Color.WHITE : ui.text);
            text.setLineSpacing(ui.dp(1.5f), 1f);
            text.setOnTouchListener((v, e) -> tapLink((TextView) v, e));
            bubble.addView(text, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            addView(bubble, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            chips = new LinearLayout(c);
            chips.setGravity(Gravity.CENTER_VERTICAL);
            chips.setPadding(ui.dp(6), ui.dp(3), ui.dp(6), 0);
            addView(chips, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            stamp = ui.line("", 11.5f, Ui.BOLD, ui.text3);
            stamp.setPadding(ui.dp(10), ui.dp(3), ui.dp(10), 0);
            addView(stamp);
            status = ui.label("", 11.5f, Ui.HEAVY, ui.text3);
            status.setPadding(ui.dp(10), ui.dp(3), ui.dp(6), 0);
            status.setGravity(out ? Gravity.END : Gravity.START);
            addView(status, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        private void colours() {
            if (bubblesFor == hue) return;
            bubblesFor = hue;
            java.util.Arrays.fill(bubbles, null);
            pictureBg = ui.rounded(ui.tint(hue), 18);
            tileBg = ui.pressable(bubble(out, true, true, false), 19);
            quoteBg = ui.rounded(out ? 0x2E000000 : ui.dark ? 0x1FFFFFFF : Ui.alpha(hue.base, 0.08f), 8);
            quoteRule.setBackground(ui.rounded(out ? 0xCCFFFFFF : hue.base, 2));
            chipBg = ui.rounded(ui.dark ? ui.surface2 : ui.surface, 12);
            GradientDrawable mine = ui.rounded(ui.tint(hue), 12);
            mine.setStroke(Math.max(1, ui.dp(1)), hue.base);
            chipMineBg = mine;
            quoteBox.setBackground(quoteBg);
        }

        private Drawable shape(boolean runStart, boolean runEnd, boolean unchecked) {
            int k = (runStart ? 4 : 0) | (runEnd ? 2 : 0) | (unchecked ? 1 : 0);
            Drawable d = bubbles[k];
            if (d == null) bubbles[k] = d = bubble(out, runStart, runEnd, unchecked);
            return d;
        }

        void bind(Item it) {
            colours();
            Model.Msg m = it.m;
            int max = bubbleMax();
            sender.setVisibility(it.sender ? VISIBLE : GONE);
            if (it.sender) sender.setText(m.sender);
            Model.Media media = m.media;
            String kind = media != null ? media.kind() : "";
            String preview = media == null ? null : media.preview != null ? media.preview : "Picture".equals(kind) ? media.path : null;
            boolean local = media != null && media.localUri != null && "Picture".equals(kind);
            boolean pic = media != null && (preview != null || local) && ("Picture".equals(kind) || "Video".equals(kind));
            pictureBox.setVisibility(pic ? VISIBLE : GONE);
            if (pic) {
                int w = Math.min(ui.dp(220), (int) (max * 0.9f));
                float ratio = media.w > 0 && media.h > 0 ? (float) media.h / media.w : 0.75f;
                ratio = Math.max(0.5f, Math.min(1.4f, ratio));
                LayoutParams lp = (LayoutParams) pictureBox.getLayoutParams();
                int h = Math.round(w * ratio);
                if (lp.width != w || lp.height != h) {
                    lp.width = w;
                    lp.height = h;
                    pictureBox.setLayoutParams(lp);
                }
                if (pictureBox.getBackground() != pictureBg) pictureBox.setBackground(pictureBg);
                play.setVisibility("Video".equals(kind) ? VISIBLE : GONE);
                String want = local ? "local:" + media.localUri : preview;
                showing = want;
                Bitmap b = local ? null : inbox.media.cached(preview, w);
                picture.setBitmap(b);
                if (b == null) {
                    Media.Got<Bitmap> then = got -> {
                        if (!want.equals(showing)) return;
                        if (got != null) picture.setBitmap(got);
                    };
                    if (local) inbox.media.local(media.localUri, w, then);
                    else inbox.media.picture(preview, w, then);
                }
                pictureBox.setOnClickListener(v -> a.openViewer(m));
            } else {
                showing = null;
                picture.setBitmap(null);
                pictureBox.setOnClickListener(null);
            }
            String tileText = null;
            int tileIconRes = R.drawable.ic_file;
            if (media != null && !pic) {
                boolean file = media.path != null || media.preview != null || media.localUri != null;
                if (file) {
                    tileText = kind + (media.bytes > 0 ? ", " + Format.bytes(media.bytes) : "");
                    tileIconRes = "Video".equals(kind) || "Voice message".equals(kind) ? R.drawable.ic_play : R.drawable.ic_file;
                } else {
                    tileText = media.blocked ? kind + " couldn't be checked, so it isn't shown." : kind + " is on the relay phone.";
                    tileIconRes = media.blocked ? R.drawable.ic_alert : R.drawable.ic_image;
                }
            }
            tile.setVisibility(tileText != null ? VISIBLE : GONE);
            if (tileText != null) {
                tile.setText(tileText);
                tile.setCompoundDrawablesRelative(tileIcon(tileIconRes, out), null, null, null);
                if (tile.getBackground() != tileBg) tile.setBackground(tileBg);
                tile.setMaxWidth(max);
                boolean opens = media.path != null || media.preview != null;
                tile.setOnClickListener(opens ? v -> a.openViewer(m) : null);
                tile.setClickable(opens);
            }
            String words = shown(m);
            boolean hasText = !words.isEmpty();
            bubble.setVisibility(hasText ? VISIBLE : GONE);
            if (hasText) {
                text.setText(m.unchecked ? words : styled(m, words, it.index, out));
                text.setTextColor(m.unchecked ? ui.text3 : out ? Color.WHITE : ui.text);
                text.setTypeface(ui.face(m.unchecked ? Ui.MEDIUM : Ui.BODY), m.unchecked ? android.graphics.Typeface.ITALIC : android.graphics.Typeface.NORMAL);
                Drawable shape = shape(it.runStart && !pic && tileText == null, it.runEnd, m.unchecked);
                if (bubble.getBackground() != shape) bubble.setBackground(shape);
                text.setMaxWidth(max - ui.dp(26));
                boolean quoted = it.quote != null;
                quoteBox.setVisibility(quoted ? VISIBLE : GONE);
                if (quoted) {
                    quoteWho.setVisibility(it.quoteWho != null ? VISIBLE : GONE);
                    if (it.quoteWho != null) quoteWho.setText(it.quoteWho);
                    quoteText.setText(it.quote.quoted);
                    quoteText.setMaxWidth(max - ui.dp(50));
                    String k = it.quoteKey;
                    quoteBox.setOnClickListener(k != null ? v -> jumpTo(k) : null);
                    quoteBox.setClickable(k != null);
                }
            }
            float alpha = "queued".equals(m.status) && out ? 0.7f : 1f;
            bubble.setAlpha(alpha);
            tile.setAlpha(alpha);
            pictureBox.setAlpha(alpha);
            chips.setVisibility(it.chips != null ? VISIBLE : GONE);
            if (it.chips != null) reactions(it.chips, m);
            boolean showTime = timed.contains(m.k);
            stamp.setVisibility(showTime ? VISIBLE : GONE);
            if (showTime) stamp.setText(Format.clock(a, m.ts) + (m.moved ? ", moved from your first inbox" : ""));
            status.setVisibility(it.status != null ? VISIBLE : GONE);
            if (it.status != null) {
                status.setText(it.status);
                status.setTextColor(it.failed ? ui.bad : ui.text3);
                status.setMaxWidth(max);
            }
            int top = it.runStart ? ui.dp(6) : ui.dp(1), bottom = it.runEnd ? ui.dp(5) : ui.dp(1);
            if (top != paddedTop || bottom != paddedBottom) {
                paddedTop = top;
                paddedBottom = bottom;
                setPadding(ui.dp(10), top, ui.dp(10), bottom);
            }
            StringBuilder said = new StringBuilder(out ? "You: " : m.sender.isEmpty() ? "" : m.sender + ": ");
            if (it.quote != null) said.append("replying to ").append(it.quoteWho != null ? it.quoteWho + ", " : "").append("“").append(it.quote.quoted).append("”: ");
            said.append(hasText ? words : kind).append(". ").append(Format.clock(a, m.ts));
            if (it.chips != null) for (Chip c : it.chips) said.append(". ").append(c.emoji).append(" from ").append(String.join(", ", c.by));
            if (it.status != null) said.append(". ").append(it.status);
            setContentDescription(said);
        }

        /** The reactions under a message: one chip per emoji, with how many; this phone's own stand out, and tap to take back. */
        private void reactions(List<Chip> list, Model.Msg m) {
            while (chips.getChildCount() > list.size()) chips.removeViewAt(chips.getChildCount() - 1);
            for (int i = 0; i < list.size(); i++) {
                Chip c = list.get(i);
                TextView t;
                if (i < chips.getChildCount()) t = (TextView) chips.getChildAt(i);
                else {
                    t = ui.button("", 12.5f, Ui.HEAVY, ui.text);
                    t.setIncludeFontPadding(false);
                    t.setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4));
                    t.setFocusable(true);
                    t.setClickable(true);
                    LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.setMarginEnd(ui.dp(4));
                    chips.addView(t, lp);
                }
                t.setText(c.count > 1 ? c.emoji + " " + c.count : c.emoji);
                Drawable want = c.mine ? chipMineBg : chipBg;
                if (t.getTag() != want) {
                    t.setBackground(want.getConstantState() != null ? want.getConstantState().newDrawable() : want);
                    t.setTag(want);
                }
                t.setContentDescription(c.emoji + " from " + String.join(", ", c.by) + (c.mine ? ". Tap to take yours back" : ". Tap to react too"));
                boolean mine = c.mine;
                t.setOnClickListener(v -> inbox.react(m, c.emoji, mine));
            }
        }
    }

    /**
     * A bubble whose quote block is as wide as the bubble: measured once as its words want, then the quote is given
     * that width and measured again for its height (a plain MATCH_PARENT child keeps its first height, cutting a
     * quote that wrapped).
     */
    static final class Bubble extends LinearLayout {
        private final View quote;

        Bubble(Context c, View quote) {
            super(c);
            this.quote = quote;
        }

        @Override
        protected void onMeasure(int w, int h) {
            LayoutParams lp = (LayoutParams) quote.getLayoutParams();
            lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            super.onMeasure(w, h);
            if (quote.getVisibility() == GONE) return;
            int inner = getMeasuredWidth() - getPaddingLeft() - getPaddingRight() - lp.leftMargin - lp.rightMargin;
            if (inner <= 0 || quote.getMeasuredWidth() >= inner) return;
            lp.width = inner;
            super.onMeasure(w, h);
            lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
        }
    }

    final class DayRow extends FrameLayout {
        final TextView label;

        DayRow(Context c) {
            super(c);
            label = ui.line("", 11.5f, Ui.HEAVY, ui.text2);
            label.setPadding(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(4));
            label.setBackground(ui.rounded(ui.dark ? 0x0FFFFFFF : 0x0D14172E, 10));
            LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
            lp.topMargin = ui.dp(12);
            lp.bottomMargin = ui.dp(6);
            addView(label, lp);
        }
    }

    private final class Adapter extends BaseAdapter {
        private List<Item> items = new ArrayList<>();
        private final Map<String, Integer> index = new HashMap<>();

        void set(List<Item> next) {
            items = next;
            index.clear();
            for (int i = 0; i < items.size(); i++) {
                Item it = items.get(i);
                index.put(it.m != null ? it.m.k : "day:" + it.day, i);
            }
            notifyDataSetChanged();
        }

        int indexOf(String key) {
            Integer i = index.get(key);
            return i == null ? -1 : i;
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Item getItem(int position) {
            return position >= 0 && position < items.size() ? items.get(position) : null;
        }

        @Override
        public long getItemId(int position) {
            Item it = items.get(position);
            return (it.m != null ? it.m.k : "day:" + it.day).hashCode();
        }

        @Override
        public boolean hasStableIds() {
            return true;
        }

        @Override
        public int getViewTypeCount() {
            return 3;
        }

        @Override
        public int getItemViewType(int position) {
            return items.get(position).type;
        }

        @Override
        public boolean isEnabled(int position) {
            return items.get(position).type != DAY;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Item it = items.get(position);
            if (it.type == DAY) {
                DayRow d = convertView instanceof DayRow ? (DayRow) convertView : new DayRow(parent.getContext());
                d.label.setText(it.day);
                return d;
            }
            boolean out = it.type == OUT;
            MsgRow r = convertView instanceof MsgRow && ((MsgRow) convertView).out == out ? (MsgRow) convertView : new MsgRow(parent.getContext(), out);
            r.bind(it);
            return r;
        }
    }

    /** Tests: the rows as drawn, and their text. */
    List<Item> itemsForTests() {
        return adapter.items;
    }

    /** Tests: the message a reply is being written to, and the file waiting to go out. */
    Model.Msg replyToForTests() {
        return replyTo;
    }

    Inbox.Attachment pendingForTests() {
        return pending;
    }

    boolean emojisOpenForTests() {
        return emojis.getVisibility() == View.VISIBLE;
    }

    void pickEmojiForTests(String emoji) {
        picked(emoji);
    }

    void longPressForTests(int position) {
        Item it = adapter.getItem(position);
        View v = list.getChildAt(Math.max(0, position + list.getHeaderViewsCount() - list.getFirstVisiblePosition()));
        if (it != null && v != null) actions(v, it);
    }
}
