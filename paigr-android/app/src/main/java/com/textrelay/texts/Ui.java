package com.textrelay.texts;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The look shared by Texts and Relay: a deep midnight (or light paper) frame, one typeface (Figtree), and a colour for
 * every conversation, worn by its keycap badge, its header and the bubbles sent in it. Everything here is built in
 * code from the framework's own views and drawables.
 */
final class Ui {
    /** A conversation's colour: the fill, the colour its gradients run to, a lighter top for keycaps, and two tints. */
    static final class Hue {
        final int base;
        final int partner;
        final int hi;
        /** An incoming bubble on the midnight frame. */
        final int tintDark;
        /** A soft wash on the paper frame. */
        final int tintLight;

        Hue(int base, int partner, int hi) {
            this.base = base;
            this.partner = partner;
            this.hi = hi;
            this.tintDark = blend(0xFF232849, base, 0.26f);
            this.tintLight = blend(0xFFFFFFFF, base, 0.13f);
        }
    }

    /** Twelve colours round the wheel; white text reads on every base and partner at 4.5:1 or better. */
    static final Hue[] HUES = {
            new Hue(0xFFC4471A, 0xFFB8325A, 0xFFDC6435),   // ember
            new Hue(0xFF9A5800, 0xFFB4441A, 0xFFB8740F),   // amber
            new Hue(0xFF557A12, 0xFF2E7D45, 0xFF6E9420),   // moss
            new Hue(0xFF2C7D3E, 0xFF0E7A75, 0xFF3F9952),   // fern
            new Hue(0xFF0F7B63, 0xFF0B6F8A, 0xFF1F977B),   // jade
            new Hue(0xFF0B7C84, 0xFF2764C9, 0xFF1C9AA2),   // lagoon
            new Hue(0xFF1F6EC0, 0xFF4A4CD6, 0xFF3A88D6),   // sky
            new Hue(0xFF3A5BEB, 0xFF6A3FE0, 0xFF5F7BF5),   // cobalt
            new Hue(0xFF5348D6, 0xFF8B3FA8, 0xFF6D63E8),   // indigo
            new Hue(0xFF7446DE, 0xFFA93CB5, 0xFF8E66EA),   // grape
            new Hue(0xFF9C38B2, 0xFFC22F6C, 0xFFB356C8),   // orchid
            new Hue(0xFFC22F6C, 0xFFC8392F, 0xFFD65289),   // berry
    };

    /** Texts' own colour, where no conversation's applies: lagoon running into cobalt. */
    static final Hue BRAND = new Hue(0xFF0B7C84, 0xFF3A5BEB, 0xFF1C9AA2);

    private static final java.util.regex.Pattern HEX = java.util.regex.Pattern.compile("^[0-9a-f]{8}.*");

    /** The same conversation always gets the same colour; its id is already a keyed hash, so its first digits do. */
    static Hue hueFor(String conv) {
        String s = conv == null ? "" : conv;
        long h;
        if (HEX.matcher(s).matches()) {
            h = Long.parseLong(s.substring(0, 8), 16);
        } else {
            h = 0xcbf29ce484222325L;   // FNV-1a, for ids that aren't hex
            for (int i = 0; i < s.length(); i++) {
                h ^= s.charAt(i);
                h *= 0x100000001b3L;
            }
            h &= 0x7fffffffffffffffL;
        }
        return HUES[(int) (h % HUES.length)];
    }

    static final int BODY = 500, MEDIUM = 600, BOLD = 700, HEAVY = 800, BLACK = 860;

    final Context context;
    final boolean dark;
    final float density;
    final int bg, surface, surface2, text, text2, text3, line, ok, warn, bad, focus, scrim, snackBg, snackText, link;

    Ui(Context context) {
        this.context = context;
        this.dark = (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        this.density = context.getResources().getDisplayMetrics().density;
        if (dark) {
            bg = 0xFF181B36;
            surface = 0xFF232849;
            surface2 = 0xFF2D3358;
            text = 0xFFEEF0FA;
            text2 = 0xFFA9AFCB;
            text3 = 0xFF7C83A6;
            line = 0x14FFFFFF;
            ok = 0xFF34C38F;
            warn = 0xFFF5A524;
            bad = 0xFFF26B6B;
            focus = 0xCCEEF0FA;
            scrim = 0xB30B0D1E;
            snackBg = 0xFFEEF0FA;
            snackText = 0xFF181B36;
            link = 0xFF8FD8DD;
        } else {
            bg = 0xFFF3F4FA;
            surface = 0xFFFFFFFF;
            surface2 = 0xFFE7E9F4;
            text = 0xFF14172E;
            text2 = 0xFF5A6080;
            text3 = 0xFF8A90AE;
            line = 0xFFE2E5F1;
            ok = 0xFF1E8A5C;
            warn = 0xFFA86200;
            bad = 0xFFC8322F;
            focus = 0xCC14172E;
            scrim = 0x8014172E;
            snackBg = 0xFF14172E;
            snackText = 0xFFF3F4FA;
            link = 0xFF0B6F76;
        }
    }

    int dp(float v) {
        return Math.round(v * density);
    }

    /** An incoming bubble's colour in this conversation. */
    int tint(Hue h) {
        return dark ? h.tintDark : h.tintLight;
    }

    // ------------------------------------------------------------------ type

    private static final android.util.SparseArray<Typeface> FACES = new android.util.SparseArray<>();

    /** Figtree at this weight (its variable font, bundled); the system's sans if this phone can't load it. */
    Typeface face(int weight) {
        synchronized (FACES) {
            Typeface t = FACES.get(weight);
            if (t != null) return t;
            try {
                t = new Typeface.Builder(context.getAssets(), "fonts/Figtree.ttf")
                        .setFontVariationSettings("'wght' " + weight)
                        .setWeight(Math.min(1000, Math.max(1, weight)))
                        .build();
            } catch (RuntimeException e) {
                t = null;
            }
            if (t == null) t = Typeface.create(Typeface.DEFAULT, Math.min(1000, weight), false);
            FACES.put(weight, t);
            return t;
        }
    }

    TextView label(CharSequence s, float sp, int weight, int color) {
        return dress(new TextView(context), s, sp, weight, color);
    }

    /** A real button (so a screen reader says so) with none of the platform's button styling. */
    TextView button(CharSequence s, float sp, int weight, int color) {
        return dress(new android.widget.Button(context, null, 0, 0), s, sp, weight, color);
    }

    private TextView dress(TextView t, CharSequence s, float sp, int weight, int color) {
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(face(weight));
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setLineSpacing(dp(2), 1f);
        return t;
    }

    /** One line, ending in "…" when it doesn't fit. */
    TextView line(CharSequence s, float sp, int weight, int color) {
        TextView t = label(s, sp, weight, color);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    /** A small rounded tag in white letters on a pill of the given colour, like the BETA beside the app's name. */
    TextView tag(String text, int fill) {
        TextView t = label(text, 10f, HEAVY, 0xFFFFFFFF);
        t.setLetterSpacing(0.09f);
        t.setSingleLine(true);
        t.setPadding(dp(7), dp(3), dp(7), dp(3));
        t.setBackground(rounded(fill, 99));
        return t;
    }

    // ------------------------------------------------------------------ shapes

    GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    GradientDrawable gradient(int from, int to, GradientDrawable.Orientation o, float radiusDp) {
        GradientDrawable d = new GradientDrawable(o, new int[] { from, to });
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    GradientDrawable stroke(int color, float widthDp, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.TRANSPARENT);
        d.setStroke(dp(widthDp), color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    int ripple() {
        return dark ? 0x33FFFFFF : 0x1F14172E;
    }

    /**
     * A background that answers a touch with a ripple and a key press with a ring: every tappable thing here can be
     * reached with a keypad's arrows and OK as well as a finger.
     */
    Drawable pressable(Drawable content, float radiusDp) {
        Drawable mask = rounded(Color.WHITE, radiusDp);
        RippleDrawable r = new RippleDrawable(ColorStateList.valueOf(ripple()), content, mask);
        StateListDrawable ring = new StateListDrawable();
        ring.addState(new int[] { android.R.attr.state_focused }, stroke(focus, 2, radiusDp));
        ring.addState(new int[0], new ColorDrawable(Color.TRANSPARENT));
        return new LayerDrawable(new Drawable[] { r, ring });
    }

    Drawable pressable(float radiusDp) {
        return pressable(null, radiusDp);
    }

    /** Clips a view (a picture, say) to rounded corners on the GPU. */
    void clip(View v, float radiusDp) {
        float r = dp(radiusDp);
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
        v.setClipToOutline(true);
    }

    // ------------------------------------------------------------------ pieces

    ImageView icon(int res, int color, float sizeDp) {
        ImageView i = new ImageView(context);
        i.setImageResource(res);
        i.setImageTintList(ColorStateList.valueOf(color));
        i.setScaleType(ImageView.ScaleType.FIT_CENTER);
        i.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return i;
    }

    /** A 44 dp square button holding one icon. */
    FrameLayout iconButton(int res, String description, int color, View.OnClickListener onClick) {
        FrameLayout f = new FrameLayout(context);
        f.setBackground(pressable(14));
        f.setFocusable(true);
        f.setClickable(true);
        f.setContentDescription(description);
        f.setOnClickListener(onClick);
        ImageView i = icon(res, color, 22);
        f.addView(i, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        f.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        return f;
    }

    /** A rounded button: filled with a gradient (the main action) or with the surface colour. */
    TextView pill(String label, int iconRes, Drawable fill, int textColor, View.OnClickListener onClick) {
        TextView t = button(label, 15.5f, HEAVY, textColor);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setPadding(dp(18), 0, dp(18), 0);
        t.setMinHeight(dp(46));
        t.setBackground(pressable(fill, 23));
        t.setFocusable(true);
        t.setClickable(true);
        t.setOnClickListener(onClick);
        if (iconRes != 0) {
            Drawable d = context.getDrawable(iconRes).mutate();
            d.setTint(textColor);
            d.setBounds(0, 0, dp(19), dp(19));
            t.setCompoundDrawablesRelative(d, null, null, null);
            t.setCompoundDrawablePadding(dp(8));
        }
        return t;
    }

    TextView primary(String label, int iconRes, Hue h, View.OnClickListener onClick) {
        return pill(label, iconRes, gradient(h.base, h.partner, GradientDrawable.Orientation.TL_BR, 23), Color.WHITE, onClick);
    }

    TextView secondary(String label, int iconRes, View.OnClickListener onClick) {
        return pill(label, iconRes, rounded(surface, 23), text, onClick);
    }

    /** Text that acts as a button, for the lesser choice under a main one. */
    TextView textButton(String label, int color, View.OnClickListener onClick) {
        TextView t = button(label, 15f, HEAVY, color);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(16), dp(11), dp(16), dp(11));
        t.setBackground(pressable(20));
        t.setFocusable(true);
        t.setClickable(true);
        t.setOnClickListener(onClick);
        return t;
    }

    /** A section's small heading. */
    TextView section(String s) {
        TextView t = label(s, 13f, HEAVY, text2);
        t.setPadding(dp(8), dp(16), dp(8), dp(7));
        return t;
    }

    /** Rows of one kind, on one rounded card, with a hairline between them. */
    LinearLayout group() {
        LinearLayout g = new LinearLayout(context);
        g.setOrientation(LinearLayout.VERTICAL);
        g.setBackground(rounded(surface, 18));
        g.setClipToOutline(true);
        GradientDrawable divider = new GradientDrawable();
        divider.setColor(line);
        divider.setSize(1, Math.max(1, dp(1)));
        g.setDividerDrawable(divider);
        g.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
        return g;
    }

    /** The small rounded square an icon sits in at the start of a row. */
    FrameLayout chip(int res, int fg, int bgColor) {
        FrameLayout f = new FrameLayout(context);
        f.setBackground(rounded(bgColor, 9));
        f.addView(icon(res, fg, 17), new FrameLayout.LayoutParams(dp(17), dp(17), Gravity.CENTER));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(30), dp(30));
        lp.setMarginEnd(dp(12));
        f.setLayoutParams(lp);
        return f;
    }

    int chipBg(int color) {
        return blend(dark ? surface : 0xFFFFFFFF, color, dark ? 0.22f : 0.14f);
    }

    /** A settings row: icon, title, an optional line under it, and whatever goes at its end. */
    static final class Row {
        final LinearLayout view;
        final TextView title;
        final TextView sub;
        final FrameLayout icon;
        final LinearLayout end;

        Row(LinearLayout view, FrameLayout icon, TextView title, TextView sub, LinearLayout end) {
            this.view = view;
            this.icon = icon;
            this.title = title;
            this.sub = sub;
            this.end = end;
        }

        Row sub(CharSequence s) {
            sub.setText(s);
            sub.setVisibility(s == null || s.length() == 0 ? View.GONE : View.VISIBLE);
            return this;
        }
    }

    Row row(int iconRes, int iconColor, String title, View.OnClickListener onClick) {
        LinearLayout r = new LinearLayout(context);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(54));
        r.setPadding(dp(14), dp(10), dp(12), dp(10));
        FrameLayout chip = null;
        if (iconRes != 0) {
            chip = chip(iconRes, iconColor, chipBg(iconColor));
            r.addView(chip);
        }
        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = label(title, 15.5f, BOLD, text);
        TextView s = label("", 13f, BODY, text3);
        s.setPadding(0, dp(2), 0, 0);
        s.setVisibility(View.GONE);
        texts.addView(t);
        texts.addView(s);
        r.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout end = new LinearLayout(context);
        end.setGravity(Gravity.CENTER_VERTICAL);
        r.addView(end, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (onClick != null) {
            r.setBackground(pressable(0));
            r.setFocusable(true);
            r.setClickable(true);
            r.setOnClickListener(onClick);
        }
        return new Row(r, chip, t, s, end);
    }

    /** A row's end: a short value, then a chevron when it opens something. */
    void endValue(Row r, String value, boolean chevron) {
        r.end.removeAllViews();
        if (value != null && !value.isEmpty()) {
            TextView v = label(value, 13.5f, HEAVY, text2);
            v.setPadding(dp(8), 0, chevron ? dp(2) : 0, 0);
            r.end.addView(v);
        }
        if (chevron) r.end.addView(icon(R.drawable.ic_chevron, text3, 18));
    }

    /** Choices side by side, one of them on. */
    static final class Segmented {
        final LinearLayout view;
        final TextView[] options;
        final String[] values;

        Segmented(LinearLayout view, TextView[] options, String[] values) {
            this.view = view;
            this.options = options;
            this.values = values;
        }
    }

    interface Choice {
        void chose(String value);
    }

    Segmented segmented(String[] labels, String[] values, Choice onChoose) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setPadding(dp(3), dp(3), dp(3), dp(3));
        box.setBackground(rounded(dark ? bg : surface2, 13));
        TextView[] opts = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            TextView t = label(labels[i], 13.5f, HEAVY, text2);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(6), dp(8), dp(6), dp(8));
            t.setFocusable(true);
            t.setClickable(true);
            String v = values[i];
            t.setOnClickListener(x -> onChoose.chose(v));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.setMarginStart(dp(3));
            box.addView(t, lp);
            opts[i] = t;
        }
        return new Segmented(box, opts, values);
    }

    void select(Segmented s, String value) {
        for (int i = 0; i < s.options.length; i++) {
            boolean on = s.values[i].equals(value);
            TextView t = s.options[i];
            t.setTextColor(on ? text : text2);
            LayerDrawable fill = new LayerDrawable(new Drawable[] { rounded(dark ? surface2 : surface, 10) });
            t.setBackground(pressable(on ? fill : null, 10));
            t.setSelected(on);
        }
    }

    // ------------------------------------------------------------------ the keycap badge

    /**
     * A conversation's badge, drawn like a key on a phone keypad: a rounded square in the conversation's
     * colour, lighter at the top with a darker lip at the bottom, holding initials or the group glyph.
     */
    static final class Keycap extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint lip = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shine = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint letters = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint.FontMetrics fm = new Paint.FontMetrics();
        private final RectF box = new RectF();
        private Hue hue = BRAND;
        private String initials = "";
        private String named;
        private Drawable glyph;
        private Drawable groupGlyph;
        private boolean glass;
        /** The gradient for this colour and height, made once (rows redraw often while the list scrolls). */
        private Shader shader;
        private int shaderFor;

        Keycap(Ui ui) {
            super(ui.context);
            letters.setColor(Color.WHITE);
            letters.setTypeface(ui.face(HEAVY));
            letters.setTextAlign(Paint.Align.CENTER);
            lip.setColor(0x3D000000);
            shine.setColor(0x38FFFFFF);
        }

        void set(Hue h, String name, boolean group) {
            hue = h;
            if (group) {
                initials = "";
                if (groupGlyph == null) {
                    groupGlyph = getContext().getDrawable(R.drawable.ic_group).mutate();
                    groupGlyph.setTint(Color.WHITE);
                }
                glyph = groupGlyph;
            } else {
                if (named == null || !named.equals(name)) {
                    initials = Ui.initials(name);
                    named = name;
                }
                glyph = null;
            }
            invalidate();
        }

        /** A drawn glyph in place of initials. */
        void setGlyph(int res) {
            initials = "";
            glyph = getContext().getDrawable(res).mutate();
            glyph.setTint(Color.WHITE);
            invalidate();
        }

        /** On a coloured header: a light glass key instead of a coloured one. */
        void setGlass(boolean on) {
            glass = on;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float r = Math.min(w, h) * 0.32f;
            box.set(0, 0, w, h);
            if (glass) {
                fill.setShader(null);
                fill.setColor(0x2EFFFFFF);
            } else {
                int key = hue.base * 31 + (int) h;
                if (shader == null || shaderFor != key) {
                    shader = new LinearGradient(0, 0, 0, h * 0.75f, hue.hi, hue.base, Shader.TileMode.CLAMP);
                    shaderFor = key;
                }
                fill.setShader(shader);
            }
            // Rounded rectangles clipped by plain rectangles: no path clipping, which a small phone's GPU does slowly.
            c.drawRoundRect(box, r, r, fill);
            float lipH = Math.max(2f, h * 0.07f);
            c.save();
            c.clipRect(0, h - lipH, w, h);
            c.drawRoundRect(box, r, r, lip);
            c.restore();
            c.save();
            c.clipRect(0, 0, w, Math.max(1f, h * 0.025f));
            c.drawRoundRect(box, r, r, shine);
            c.restore();
            if (glyph != null) {
                int s = (int) (Math.min(w, h) * 0.5f);
                int l = (int) ((w - s) / 2), t = (int) ((h - lipH - s) / 2);
                glyph.setBounds(l, t, l + s, t + s);
                glyph.draw(c);
            } else if (!initials.isEmpty()) {
                letters.setTextSize(h * (initials.length() > 1 ? 0.36f : 0.42f));
                letters.getFontMetrics(fm);
                float y = (h - lipH) / 2f - (fm.ascent + fm.descent) / 2f;
                c.drawText(initials, w / 2f, y, letters);
            }
        }
    }

    /**
     * A picture with rounded corners, drawn through a shader: cheaper than clipping a view to an outline, which a
     * small phone's GPU does slowly for every row. Fills its box the way CENTER_CROP would.
     */
    static final class Picture extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF box = new RectF();
        private final android.graphics.Matrix matrix = new android.graphics.Matrix();
        private android.graphics.Bitmap bitmap;
        private final float radius;

        Picture(Ui ui, float radiusDp) {
            super(ui.context);
            radius = ui.dp(radiusDp);
        }

        void setBitmap(android.graphics.Bitmap b) {
            if (b == bitmap) return;
            bitmap = b;
            paint.setShader(b == null ? null : new android.graphics.BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
            invalidate();
        }

        android.graphics.Bitmap getBitmap() {
            return bitmap;
        }

        @Override
        protected void onDraw(Canvas c) {
            if (bitmap == null || paint.getShader() == null) return;
            float w = getWidth(), h = getHeight();
            float scale = Math.max(w / bitmap.getWidth(), h / bitmap.getHeight());
            matrix.setScale(scale, scale);
            matrix.postTranslate((w - bitmap.getWidth() * scale) / 2f, (h - bitmap.getHeight() * scale) / 2f);
            paint.getShader().setLocalMatrix(matrix);
            box.set(0, 0, w, h);
            c.drawRoundRect(box, radius, radius, paint);
        }
    }

    /** Up to two letters (or digits) for a name; "#" for a name with neither. */
    static String initials(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) return "#";
        StringBuilder out = new StringBuilder();
        if (Numbers.isNumber(n)) {   // a number for a name: its first two digits
            String d = n.replaceAll("[^0-9]", "");
            return d.length() >= 2 ? d.substring(0, 2) : (d.isEmpty() ? "#" : d);
        }
        for (String part : n.split("\\s+")) {
            if (part.isEmpty()) continue;
            int cp = part.codePointAt(0);
            if (!Character.isLetterOrDigit(cp)) continue;
            out.appendCodePoint(Character.toUpperCase(cp));
            if (out.codePointCount(0, out.length()) == 2) break;
        }
        return out.length() == 0 ? "#" : out.toString();
    }

    // ------------------------------------------------------------------ the status light

    /** A small light with a soft halo; it breathes only while something is being tried. */
    static final class Light extends View {
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
        private ValueAnimator pulse;
        private float phase;

        Light(Context c) {
            super(c);
        }

        void set(int color, boolean breathing) {
            dot.setColor(color);
            halo.setColor(color);
            if (breathing && pulse == null) {
                pulse = ValueAnimator.ofFloat(0f, 1f);
                pulse.setDuration(1400);
                pulse.setRepeatCount(ValueAnimator.INFINITE);
                pulse.addUpdateListener(a -> {
                    phase = (float) a.getAnimatedValue();
                    invalidate();
                });
                pulse.start();
            } else if (!breathing && pulse != null) {
                pulse.cancel();
                pulse = null;
                phase = 0;
            }
            invalidate();
        }

        @Override
        protected void onDetachedFromWindow() {
            if (pulse != null) pulse.cancel();
            pulse = null;
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float r = Math.min(cx, cy) * 0.5f;
            float grow = pulse != null ? phase : 0.55f;
            halo.setAlpha((int) (70 * (1f - (pulse != null ? phase : 0.3f))));
            c.drawCircle(cx, cy, r + (Math.min(cx, cy) - r) * grow, halo);
            c.drawCircle(cx, cy, r, dot);
        }
    }

    // ------------------------------------------------------------------ messages that come and go

    /** A short message at the bottom of the screen, in the app's own look. */
    static final class Snack {
        private final Ui ui;
        private final TextView view;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Runnable hide;

        Snack(Ui ui, FrameLayout root) {
            this.ui = ui;
            view = ui.label("", 14.5f, MEDIUM, ui.snackText);
            view.setBackground(ui.rounded(ui.snackBg, 16));
            view.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12));
            view.setGravity(Gravity.CENTER);
            view.setVisibility(View.GONE);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            lp.setMargins(ui.dp(16), 0, ui.dp(16), ui.dp(76));
            root.addView(view, lp);
            view.setOnClickListener(v -> dismiss());
            hide = this::dismiss;
        }

        void show(CharSequence text, long ms) {
            view.setText(text);
            handler.removeCallbacks(hide);
            if (view.getVisibility() != View.VISIBLE) {
                view.setAlpha(0f);
                view.setTranslationY(ui.dp(12));
                view.setVisibility(View.VISIBLE);
                view.animate().alpha(1f).translationY(0).setDuration(160).setInterpolator(new DecelerateInterpolator()).start();
            }
            view.bringToFront();
            handler.postDelayed(hide, ms > 0 ? ms : 2800);
        }

        void dismiss() {
            handler.removeCallbacks(hide);
            if (view.getVisibility() != View.VISIBLE) return;
            view.animate().alpha(0f).translationY(ui.dp(8)).setDuration(140).withEndAction(() -> view.setVisibility(View.GONE)).start();
        }

        CharSequence text() {
            return view.getVisibility() == View.VISIBLE ? view.getText() : "";
        }
    }

    /** A question with two answers, on a rounded card in the app's look. */
    static Dialog confirm(Activity a, Ui ui, String title, String message, String yes, boolean danger, Runnable onYes) {
        Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(ui.dp(20), ui.dp(20), ui.dp(16), ui.dp(12));
        card.setBackground(ui.rounded(ui.surface, 24));
        TextView t = ui.label(title, 18f, HEAVY, ui.text);
        card.addView(t);
        if (message != null && !message.isEmpty()) {
            TextView m = ui.label(message, 14.5f, BODY, ui.text2);
            m.setPadding(0, ui.dp(8), ui.dp(4), 0);
            card.addView(m);
        }
        LinearLayout buttons = new LinearLayout(a);
        buttons.setGravity(Gravity.END);
        buttons.setPadding(0, ui.dp(14), 0, 0);
        TextView no = ui.textButton("Cancel", ui.text2, v -> d.dismiss());
        TextView ok = ui.textButton(yes, danger ? ui.bad : ui.text, v -> {
            d.dismiss();
            onYes.run();
        });
        buttons.addView(no);
        buttons.addView(ok);
        card.addView(buttons);
        d.setContentView(card);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(Math.min(a.getResources().getDisplayMetrics().widthPixels - ui.dp(32), ui.dp(400)), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        d.show();
        ok.requestFocus();
        return d;
    }

    // ------------------------------------------------------------------ colour arithmetic

    /** `a` moved `t` of the way to `b`. */
    static int blend(int a, int b, float t) {
        float u = 1f - t;
        int r = Math.round(Color.red(a) * u + Color.red(b) * t);
        int g = Math.round(Color.green(a) * u + Color.green(b) * t);
        int bl = Math.round(Color.blue(a) * u + Color.blue(b) * t);
        return Color.argb(255, r, g, bl);
    }

    static int alpha(int color, float a) {
        return (color & 0x00FFFFFF) | (Math.round(255 * a) << 24);
    }

    /** Status and navigation bars in this colour, with icons that read on it. */
    static void bars(Window w, int color, boolean lightIcons) {
        if (w == null) return;
        w.setStatusBarColor(color);
        w.setNavigationBarColor(color);
        View decor = w.getDecorView();
        int flags = decor.getSystemUiVisibility();
        if (lightIcons) flags &= ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        else flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        decor.setSystemUiVisibility(flags);
    }

    /** The navigation bar alone (a thread's header colours the status bar, the composer sits on the frame). */
    static void navBar(Window w, int color, boolean lightIcons) {
        if (w == null) return;
        w.setNavigationBarColor(color);
        View decor = w.getDecorView();
        int flags = decor.getSystemUiVisibility();
        if (lightIcons) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        else flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        decor.setSystemUiVisibility(flags);
    }

    static void statusBar(Window w, int color, boolean lightIcons) {
        if (w == null) return;
        w.setStatusBarColor(color);
        View decor = w.getDecorView();
        int flags = decor.getSystemUiVisibility();
        if (lightIcons) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        else flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        decor.setSystemUiVisibility(flags);
    }
}
