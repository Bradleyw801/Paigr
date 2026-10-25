package com.textrelay.texts;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.VideoView;

import java.io.File;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** A picture full size (pinch or double-tap to zoom), or a video or voice message playing, over everything else. */
final class Viewer extends Screen {
    private final Zoom image;
    private final VideoView video;
    private final ProgressBar wait;
    private final TextView caption;
    private final LinearLayout bar;
    private final TextView glyph;
    private Model.Msg m;
    private boolean open;
    private int token;

    Viewer(MainActivity a) {
        super(a);
        FrameLayout f = new FrameLayout(a);
        f.setBackgroundColor(Color.BLACK);
        f.setClickable(true);
        f.setVisibility(View.GONE);
        image = new Zoom(a);
        f.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        video = new VideoView(a);
        video.setVisibility(View.GONE);
        f.addView(video, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        glyph = ui.label("Voice message", 16f, Ui.HEAVY, Color.WHITE);
        android.graphics.drawable.Drawable d = a.getDrawable(R.drawable.ic_sms).mutate();
        d.setTint(Color.WHITE);
        d.setBounds(0, 0, ui.dp(48), ui.dp(48));
        glyph.setCompoundDrawablesRelative(null, d, null, null);
        glyph.setCompoundDrawablePadding(ui.dp(12));
        glyph.setGravity(Gravity.CENTER);
        glyph.setVisibility(View.GONE);
        f.addView(glyph, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        wait = new ProgressBar(a);
        wait.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        f.addView(wait, new FrameLayout.LayoutParams(ui.dp(40), ui.dp(40), Gravity.CENTER));

        bar = new LinearLayout(a);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0x99000000);
        bar.setPadding(ui.dp(4), ui.dp(4), ui.dp(8), ui.dp(4));
        bar.addView(ui.iconButton(R.drawable.ic_close, "Close", Color.WHITE, v -> close()));
        caption = ui.line("", 13.5f, Ui.BOLD, 0xE6FFFFFF);
        caption.setPadding(ui.dp(4), 0, ui.dp(8), 0);
        bar.addView(caption, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView save = ui.pill("Save", R.drawable.ic_save, ui.rounded(0x33FFFFFF, 20), Color.WHITE, v -> {
            if (m != null) save(a, m);
        });
        save.setMinHeight(ui.dp(40));
        bar.addView(save);
        f.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        root = f;
    }

    @Override
    void insets(int top, int bottom, boolean keyboard) {
        bar.setPadding(ui.dp(4), ui.dp(4), ui.dp(8), bottom + ui.dp(4));
        ((FrameLayout.LayoutParams) image.getLayoutParams()).topMargin = top;
    }

    boolean isOpen() {
        return open;
    }

    void open(Model.Msg msg) {
        Model.Media media = msg.media;
        if (media == null || (media.path == null && media.preview == null)) return;
        String kind = media.kind();
        if (!"Picture".equals(kind) && !"Video".equals(kind) && !"Voice message".equals(kind)) {
            save(a, msg);   // any other file: saved, for the phone's own apps to open
            return;
        }
        m = msg;
        open = true;
        int t = ++token;
        root.setVisibility(View.VISIBLE);
        root.bringToFront();
        WindowInsetsController c = a.getWindow().getInsetsController();
        if (c != null) c.setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        image.setImageDrawable(null);
        image.setVisibility(View.GONE);
        video.setVisibility(View.GONE);
        glyph.setVisibility(View.GONE);
        wait.setVisibility(View.VISIBLE);
        caption.setText(kind + (media.w > 0 && media.h > 0 ? ", " + media.w + "×" + media.h : "") + (media.bytes > 0 ? ", " + Format.bytes(media.bytes) : ""));
        int screen = Math.max(a.getResources().getDisplayMetrics().widthPixels, a.getResources().getDisplayMetrics().heightPixels);
        if ("Picture".equals(kind)) {
            image.setVisibility(View.VISIBLE);
            String preview = media.preview;
            if (preview != null) {
                // the preview at once, the original when it's here
                Bitmap quick = inbox.media.cached(preview, Math.min(ui.dp(220), (int) (a.getResources().getDisplayMetrics().widthPixels * 0.7f)));
                if (quick != null) image.show(quick);
            }
            String full = media.path != null ? media.path : preview;
            inbox.media.picture(full, screen * 2, b -> {
                if (t != token || !open) return;
                wait.setVisibility(View.GONE);
                if (b != null) image.show(b);
                else if (image.getDrawable() == null) a.say("Couldn't open the picture.", 3000);
            });
            return;
        }
        String path = media.path != null ? media.path : media.preview;
        inbox.media.bytes(path, bytes -> {
            if (t != token || !open) return;
            wait.setVisibility(View.GONE);
            if (bytes == null) {
                a.say("Couldn't open it.", 3000);
                return;
            }
            try {
                File f = inbox.media.playable(bytes, media.mime);
                boolean voice = "Voice message".equals(kind);
                glyph.setVisibility(voice ? View.VISIBLE : View.GONE);
                video.setVisibility(View.VISIBLE);
                MediaController mc = new MediaController(a);
                mc.setAnchorView(video);
                video.setMediaController(mc);
                video.setVideoURI(Uri.fromFile(f));
                video.setOnPreparedListener(p -> {
                    if (t != token) return;
                    video.start();
                    mc.show(0);
                });
                video.setOnErrorListener((p, what, extra) -> {
                    a.say("This phone can't play it here. Save it to open it in another app.", 4500);
                    return true;
                });
            } catch (java.io.IOException e) {
                a.say("Couldn't open it.", 3000);
            }
        });
    }

    void close() {
        if (!open) return;
        open = false;
        token++;
        try {
            video.stopPlayback();
        } catch (RuntimeException ignored) {
            // not playing
        }
        video.setVisibility(View.GONE);
        image.setImageDrawable(null);
        root.setVisibility(View.GONE);
        inbox.media.forgetPlayable();
        a.bars();
    }

    // ------------------------------------------------------------------ saving

    static String extension(String mime) {
        switch (mime == null ? "" : mime.toLowerCase(Locale.US)) {
            case "image/jpeg": return "jpg";
            case "image/png": return "png";
            case "image/gif": return "gif";
            case "image/webp": return "webp";
            case "image/heic": return "heic";
            case "video/mp4": return "mp4";
            case "video/3gpp": return "3gp";
            case "video/webm": return "webm";
            case "audio/mpeg": return "mp3";
            case "audio/mp4": return "m4a";
            case "audio/aac": return "aac";
            case "audio/amr": return "amr";
            case "audio/ogg": return "ogg";
            case "audio/webm": return "weba";
            case "application/pdf": return "pdf";
            case "text/vcard": case "text/x-vcard": return "vcf";
            default: return "bin";
        }
    }

    /** An attachment saved to Downloads, as the original when there is one. */
    static void save(MainActivity a, Model.Msg m) {
        Model.Media media = m.media;
        if (media == null) return;
        String path = media.path != null ? media.path : media.preview;
        String mime = media.path != null ? (media.mime.isEmpty() ? "application/octet-stream" : media.mime) : "image/jpeg";
        a.say("Saving…", 1500);
        a.inbox.media.bytes(path, bytes -> {
            if (bytes == null) {
                a.say("Couldn't save it.", 3000);
                return;
            }
            String name = "Paigr-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + "." + extension(mime);
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                v.put(MediaStore.Downloads.MIME_TYPE, mime);
                v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = a.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new java.io.IOException("no place to save");
                try (OutputStream out = a.getContentResolver().openOutputStream(uri)) {
                    if (out == null) throw new java.io.IOException("no place to save");
                    out.write(bytes);
                }
                a.say("Saved to Downloads as " + name + ".", 3500);
            } catch (java.io.IOException | RuntimeException e) {
                a.say("Couldn't save it to Downloads.", 3000);
            }
        });
    }

    // ------------------------------------------------------------------ zooming

    /** A picture that fits the screen, zooms with two fingers or a double tap, and pans when zoomed. */
    static final class Zoom extends ImageView {
        private final Matrix matrix = new Matrix();
        private final float[] v = new float[9];
        private final ScaleGestureDetector pinch;
        private final GestureDetector taps;
        private float fit = 1f;

        Zoom(Context c) {
            super(c);
            setScaleType(ScaleType.MATRIX);
            pinch = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector d) {
                    float s = scale();
                    float f = d.getScaleFactor();
                    float next = Math.max(fit, Math.min(fit * 5f, s * f));
                    matrix.postScale(next / s, next / s, d.getFocusX(), d.getFocusY());
                    keepInside();
                    return true;
                }
            });
            taps = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    float s = scale();
                    float target = s > fit * 1.2f ? fit : fit * 2.5f;
                    matrix.postScale(target / s, target / s, e.getX(), e.getY());
                    keepInside();
                    return true;
                }

                @Override
                public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                    if (scale() <= fit * 1.01f) return false;
                    matrix.postTranslate(-dx, -dy);
                    keepInside();
                    return true;
                }
            });
        }

        void show(Bitmap b) {
            setImageBitmap(b);
            post(this::reset);
        }

        private float scale() {
            matrix.getValues(v);
            return v[Matrix.MSCALE_X];
        }

        private void reset() {
            if (getDrawable() == null || getWidth() == 0) return;
            float dw = getDrawable().getIntrinsicWidth(), dh = getDrawable().getIntrinsicHeight();
            fit = Math.min(getWidth() / dw, getHeight() / dh);
            matrix.reset();
            matrix.postScale(fit, fit);
            matrix.postTranslate((getWidth() - dw * fit) / 2f, (getHeight() - dh * fit) / 2f);
            setImageMatrix(matrix);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            reset();
        }

        private void keepInside() {
            if (getDrawable() == null) return;
            RectF r = new RectF(0, 0, getDrawable().getIntrinsicWidth(), getDrawable().getIntrinsicHeight());
            matrix.mapRect(r);
            float dx = 0, dy = 0;
            if (r.width() <= getWidth()) dx = (getWidth() - r.width()) / 2f - r.left;
            else if (r.left > 0) dx = -r.left;
            else if (r.right < getWidth()) dx = getWidth() - r.right;
            if (r.height() <= getHeight()) dy = (getHeight() - r.height()) / 2f - r.top;
            else if (r.top > 0) dy = -r.top;
            else if (r.bottom < getHeight()) dy = getHeight() - r.bottom;
            matrix.postTranslate(dx, dy);
            setImageMatrix(matrix);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            boolean a = pinch.onTouchEvent(e);
            boolean b = taps.onTouchEvent(e);
            return a || b || super.onTouchEvent(e) || true;
        }
    }
}
