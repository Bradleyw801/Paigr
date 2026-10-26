package com.textrelay.texts;

import android.content.res.Configuration;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * New message: the number to text. Next opens the conversation (a new one, or the one this phone already shows under
 * that number), where the message itself is written like any other; the relay phone sends the first one as SMS.
 */
final class ComposeScreen extends Screen {
    private final LinearLayout header;
    final EditText to;
    private final TextView hint;
    private String pickedName;

    ComposeScreen(MainActivity a) {
        super(a);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(ui.bg);

        header = new LinearLayout(a);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(ui.dp(4), 0, ui.dp(12), 0);
        header.setMinimumHeight(ui.dp(58));
        header.addView(ui.iconButton(R.drawable.ic_back, "Back", ui.text, v -> a.onBackPressed()));
        TextView title = ui.label("New message", 22f, Ui.BLACK, ui.text);
        title.setLetterSpacing(-0.02f);
        title.setPadding(ui.dp(6), 0, 0, 0);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(header);

        // "To", the number, and the phone's own contact picker beside it.
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(ui.rounded(ui.surface, 22));
        row.setPadding(ui.dp(16), 0, ui.dp(4), 0);
        TextView label = ui.line("To", 15f, Ui.HEAVY, ui.text2);
        label.setPadding(0, 0, ui.dp(10), 0);
        row.addView(label);
        to = new EditText(a);
        to.setHint("Phone number");
        to.setSingleLine(true);
        to.setInputType(InputType.TYPE_CLASS_PHONE);
        to.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        to.setTypeface(ui.face(Ui.MEDIUM));
        to.setTextSize(17f);
        to.setTextColor(ui.text);
        to.setHintTextColor(ui.text3);
        to.setBackground(null);
        to.setPadding(0, 0, 0, 0);
        to.setOnEditorActionListener((v, id, e) -> {
            if (id == EditorInfo.IME_ACTION_NEXT || id == EditorInfo.IME_ACTION_DONE || (e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER && e.getAction() == KeyEvent.ACTION_DOWN)) {
                next();
                return true;
            }
            return false;
        });
        to.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int af) {}

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
                pickedName = null;   // typed over: whoever was picked isn't who this is to
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {}
        });
        row.addView(to, new LinearLayout.LayoutParams(0, ui.dp(50), 1f));
        row.addView(ui.iconButton(R.drawable.ic_group, "Pick a contact", ui.text2, v -> a.pickContact()));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.setMargins(ui.dp(14), ui.dp(8), ui.dp(14), 0);
        col.addView(row, rlp);

        hint = ui.label("Your relay phone sends the first text as SMS. A number you already text with opens its conversation.", 13.5f, Ui.BODY, ui.text2);
        hint.setPadding(ui.dp(22), ui.dp(12), ui.dp(22), 0);
        col.addView(hint);

        TextView next = ui.primary("Next", R.drawable.ic_forward, Ui.BRAND, v -> next());
        next.setContentDescription("Next");
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.setMargins(ui.dp(14), ui.dp(22), ui.dp(14), 0);
        col.addView(next, nlp);
        root = col;
    }

    void open() {
        to.setText("");
        pickedName = null;
    }

    /** Next: the conversation for the number, over the list, with this screen gone. */
    void next() {
        String conv = inbox.startConversation(to.getText().toString(), pickedName);
        if (conv == null) {
            a.say("Enter a phone number to text.", 2500);
            to.requestFocus();
            return;
        }
        hideKeyboard();
        a.startedConversation(conv);
    }

    /** A row from the phone's contact picker: its number goes in the field, and its name on the conversation. */
    void picked(Uri row) {
        String number = null;
        String name = null;
        try (Cursor c = a.getContentResolver().query(row, new String[] { ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME }, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                number = c.getString(0);
                name = c.getString(1);
            }
        } catch (RuntimeException e) {
            number = null;   // the picker gave something this phone can't read
        }
        if (number == null || number.trim().isEmpty()) {
            a.say("That contact has no phone number.", 2500);
            return;
        }
        to.setText(number.trim());
        to.setSelection(to.getText().length());
        pickedName = name != null && !name.trim().isEmpty() ? name.trim() : null;
    }

    @Override
    void insets(int top, int bottom, boolean keyboard) {
        header.setPadding(ui.dp(4), top, ui.dp(12), 0);
        root.setPadding(0, 0, 0, bottom);
    }

    @Override
    void shown() {
        inbox.view = "compose";
        to.requestFocus();
        Configuration cfg = a.getResources().getConfiguration();
        boolean keys = cfg.keyboard != Configuration.KEYBOARD_NOKEYS && cfg.hardKeyboardHidden != Configuration.HARDKEYBOARDHIDDEN_YES;
        if (!keys) {
            InputMethodManager im = a.getSystemService(InputMethodManager.class);
            if (im != null) im.showSoftInput(to, 0);
        }
    }

    @Override
    void hidden() {
        hideKeyboard();
    }

    /** Typing on a hardware keyboard goes into the number, wherever the highlight is. */
    @Override
    boolean typed(KeyEvent e) {
        if (printable(e) == 0 || to.hasFocus()) return false;
        to.requestFocus();
        to.setSelection(to.getText().length());
        return false;
    }

    private void hideKeyboard() {
        InputMethodManager im = a.getSystemService(InputMethodManager.class);
        if (im != null) im.hideSoftInputFromWindow(to.getWindowToken(), 0);
    }
}
