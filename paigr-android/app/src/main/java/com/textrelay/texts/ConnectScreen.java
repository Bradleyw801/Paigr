package com.textrelay.texts;

import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Linking this phone: scan the code Relay shows (or type it). Two new phones get a private inbox of their own. */
final class ConnectScreen extends Screen {
    private final Art.Pair art;
    private final TextView scan;
    private final TextView type;
    private final LinearLayout entry;
    private final EditText code;
    private final TextView go;
    private final TextView note;
    private final TextView error;
    private final LinearLayout column;
    private boolean busy;

    ConnectScreen(MainActivity a) {
        super(a);
        ScrollView scroll = new ScrollView(a);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(ui.bg);
        column = new LinearLayout(a);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL);
        column.setPadding(ui.dp(24), ui.dp(20), ui.dp(24), ui.dp(20));
        scroll.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        art = new Art.Pair(ui);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.bottomMargin = ui.dp(18);
        column.addView(art, alp);

        TextView title = ui.label("Link this phone", 24f, Ui.BLACK, ui.text);
        title.setGravity(Gravity.CENTER);
        title.setLetterSpacing(-0.02f);
        column.addView(title);
        TextView lead = ui.label("Open Paigr Relay on the phone with your SIM, then scan the code it shows.", 15f, Ui.BODY, ui.text2);
        lead.setGravity(Gravity.CENTER);
        lead.setMaxWidth(ui.dp(270));
        lead.setPadding(0, ui.dp(8), 0, ui.dp(20));
        column.addView(lead);

        scan = ui.primary("Scan code", R.drawable.ic_scan, Ui.BRAND, v -> {
            error(null);
            a.scan("link");
        });
        column.addView(scan, wide());

        type = ui.textButton("Type the code instead", ui.link, v -> typeCode());
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = ui.dp(6);
        column.addView(type, tlp);

        entry = new LinearLayout(a);
        entry.setOrientation(LinearLayout.VERTICAL);
        entry.setVisibility(View.GONE);
        code = new EditText(a);
        code.setHint("XXXX-XXXX-XXXX");
        code.setSingleLine(true);
        code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        code.setImeOptions(EditorInfo.IME_ACTION_GO);
        code.setFilters(new InputFilter[] { new InputFilter.AllCaps(), new InputFilter.LengthFilter(24) });
        code.setTypeface(ui.face(Ui.HEAVY));
        code.setTextSize(19f);
        code.setLetterSpacing(0.08f);
        code.setGravity(Gravity.CENTER);
        code.setTextColor(ui.text);
        code.setHintTextColor(ui.text3);
        code.setBackground(ui.pressable(ui.rounded(ui.surface, 16), 16));
        code.setPadding(ui.dp(14), ui.dp(13), ui.dp(14), ui.dp(13));
        code.setOnEditorActionListener((v, id, e) -> {
            if (id == EditorInfo.IME_ACTION_GO || (e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER && e.getAction() == KeyEvent.ACTION_DOWN)) {
                submit();
                return true;
            }
            return false;
        });
        entry.addView(code, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        go = ui.primary("Link", 0, Ui.BRAND, v -> submit());
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.topMargin = ui.dp(10);
        entry.addView(go, glp);
        LinearLayout.LayoutParams elp = wide();
        elp.topMargin = ui.dp(14);
        column.addView(entry, elp);

        note = ui.label("", 14f, Ui.MEDIUM, ui.text2);
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, ui.dp(12), 0, 0);
        note.setVisibility(View.GONE);
        column.addView(note);
        error = ui.label("", 14.5f, Ui.BOLD, ui.bad);
        error.setGravity(Gravity.CENTER);
        error.setPadding(0, ui.dp(12), 0, 0);
        error.setVisibility(View.GONE);
        column.addView(error);

        TextView lock = ui.label("End-to-end encrypted", 12.5f, Ui.MEDIUM, ui.text3);
        android.graphics.drawable.Drawable d = a.getDrawable(R.drawable.ic_lock).mutate();
        d.setTint(ui.text3);
        d.setBounds(0, 0, ui.dp(14), ui.dp(14));
        lock.setCompoundDrawablesRelative(d, null, null, null);
        lock.setCompoundDrawablePadding(ui.dp(6));
        lock.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = ui.dp(22);
        column.addView(lock, llp);
        root = scroll;
        recovering("pending".equals(MainActivity.recovery));
    }

    private LinearLayout.LayoutParams wide() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = lp.rightMargin = ui.dp(6);
        return lp;
    }

    @Override
    void insets(int top, int bottom, boolean keyboard) {
        column.setPadding(ui.dp(24), top + ui.dp(20), ui.dp(24), bottom + ui.dp(20));
    }

    @Override
    void shown() {
        scan.requestFocus();
    }

    @Override
    boolean back() {
        if (entry.getVisibility() == View.VISIBLE) {
            entry.setVisibility(View.GONE);
            type.setVisibility(View.VISIBLE);
            return true;
        }
        return false;
    }

    /** The scanner closed with "type it instead", or the person tapped that here. */
    void typeCode() {
        type.setVisibility(View.GONE);
        entry.setVisibility(View.VISIBLE);
        code.requestFocus();
        android.view.inputmethod.InputMethodManager im = a.getSystemService(android.view.inputmethod.InputMethodManager.class);
        if (im != null) im.showSoftInput(code, 0);
    }

    private void submit() {
        String c = code.getText().toString().trim();
        if (Crypto.normCode(c).length() < 12) {
            error("Enter the whole code Paigr Relay shows: 12 letters and numbers.");
            return;
        }
        error(null);
        a.link(c, "link");
    }

    void busy(boolean on) {
        busy = on;
        scan.setEnabled(!on);
        go.setEnabled(!on);
        scan.setAlpha(on ? 0.5f : 1f);
        go.setAlpha(on ? 0.5f : 1f);
        note.setText(on ? "Linking…" : "");
        note.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) error(null);
    }

    void error(String text) {
        boolean show = text != null && !text.isEmpty();
        error.setText(show ? text : "");
        error.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    void recovering(boolean on) {
        if (busy) return;
        note.setText(on ? "Checking whether this phone was linked before…" : "");
        note.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    /** Typing on a hardware keyboard goes into the code. */
    @Override
    boolean typed(KeyEvent e) {
        int c = printable(e);
        if (c == 0 || code.hasFocus()) return false;
        typeCode();
        code.append(String.valueOf((char) c).toUpperCase(java.util.Locale.US));
        return true;
    }
}
