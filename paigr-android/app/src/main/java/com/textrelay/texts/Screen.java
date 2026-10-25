package com.textrelay.texts;

import android.view.KeyEvent;
import android.view.View;

/** One of Texts' screens: built once, shown and hidden by MainActivity, told when the inbox changes. */
abstract class Screen {
    final MainActivity a;
    final Ui ui;
    final Inbox inbox;
    View root;

    Screen(MainActivity a) {
        this.a = a;
        this.ui = a.ui;
        this.inbox = a.inbox;
    }

    /** It's on screen now. */
    void shown() {}

    /** It left the screen. */
    void hidden() {}

    /** Back was pressed while it's on top: true when it handled that itself. */
    boolean back() {
        return false;
    }

    /** The status and navigation bars' sizes (or the keyboard's, when it's up). */
    void insets(int top, int bottom, boolean keyboard) {}

    void changed(int what, boolean end) {}

    /** A key pressed while it's on top: true when it took it. */
    boolean typed(KeyEvent e) {
        return false;
    }

    /** Its top is a conversation's colour (white status bar icons). */
    boolean coloredTop() {
        return false;
    }

    /** A printable character from a hardware keyboard, with nothing held that makes it a shortcut; 0 otherwise. */
    static int printable(KeyEvent e) {
        if (e.isCtrlPressed() || e.isAltPressed() || e.isMetaPressed()) return 0;
        int c = e.getUnicodeChar();
        return c > 0 && !Character.isISOControl(c) && !Character.isWhitespace(c) ? c : 0;
    }
}
