package com.textrelay.texts;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** The few drawings Texts has: the two phones on the linking screen, and the pull-to-refresh ring. */
final class Art {
    private Art() {}

    /** The relay phone and this one, with a signal on the arc between them. Drawn once: it never animates. */
    static final class Pair extends View {
        private final Ui ui;
        private final Paint phone = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint lip = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint beam = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint spark = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path arc = new Path();
        private final PathMeasure measure = new PathMeasure();
        private final float[] at = new float[2];
        private final RectF r = new RectF();
        private static final float T = 0.72f;   // where the signal sits on the arc

        Pair(Ui ui) {
            super(ui.context);
            this.ui = ui;
            glass.setColor(0x38FFFFFF);
            lip.setColor(0x40000000);
            beam.setStyle(Paint.Style.STROKE);
            beam.setStrokeWidth(ui.dp(2.5f));
            beam.setStrokeCap(Paint.Cap.ROUND);
            beam.setColor(Ui.alpha(ui.text2, 0.55f));
            beam.setPathEffect(new DashPathEffect(new float[] { ui.dp(5), ui.dp(5) }, 0));
            spark.setColor(0xFFF5C44A);
            halo.setColor(0x38F5C44A);
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(ui.dp(196), ui.dp(112));
        }

        private Shader warm, cool;
        private final Path clip = new Path();

        private void phone(Canvas c, float left, float top, float w, float h, boolean relay) {
            float rad = ui.dp(14);
            r.set(left, top, left + w, top + h);
            if (warm == null) {
                warm = new LinearGradient(0, top, 0, top + h, 0xFFE86A3A, 0xFFC22F6C, Shader.TileMode.CLAMP);
                cool = new LinearGradient(0, top, 0, top + h, 0xFF1C9AA2, 0xFF3A5BEB, Shader.TileMode.CLAMP);
            }
            phone.setShader(relay ? warm : cool);
            c.drawRoundRect(r, rad, rad, phone);
            c.save();
            clip.reset();
            clip.addRoundRect(r, rad, rad, Path.Direction.CW);
            c.clipPath(clip);
            c.drawRect(left, top + h - ui.dp(4), left + w, top + h, lip);
            c.restore();
            r.set(left + ui.dp(7), top + ui.dp(8), left + w - ui.dp(7), top + ui.dp(46));
            c.drawRoundRect(r, ui.dp(6), ui.dp(6), glass);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth();
            float pw = ui.dp(54), ph = ui.dp(82), top = ui.dp(24);
            float lx = 0, rx = w - pw;
            arc.reset();
            float x0 = lx + pw - ui.dp(4), x1 = rx + ui.dp(4), y = top + ui.dp(8);
            arc.moveTo(x0, y);
            arc.cubicTo(x0 + ui.dp(18), ui.dp(2), x1 - ui.dp(18), ui.dp(2), x1, y);
            c.drawPath(arc, beam);
            phone(c, lx, top, pw, ph, true);
            phone(c, rx, top, pw, ph, false);
            measure.setPath(arc, false);
            measure.getPosTan(measure.getLength() * T, at, null);
            c.drawCircle(at[0], at[1], ui.dp(10), halo);
            c.drawCircle(at[0], at[1], ui.dp(6), spark);
        }
    }

    /** The ring that comes down when the list is pulled, and turns while it refreshes. */
    static final class Ring extends View {
        private final Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private float progress;
        private ValueAnimator spin;
        private float angle;

        Ring(Ui ui) {
            super(ui.context);
            disc.setColor(ui.dark ? ui.surface2 : ui.surface);
            disc.setShadowLayer(ui.dp(3), 0, ui.dp(1), 0x40000000);
            setLayerType(LAYER_TYPE_SOFTWARE, null);   // the disc's shadow
            arc.setStyle(Paint.Style.STROKE);
            arc.setStrokeWidth(ui.dp(2.6f));
            arc.setStrokeCap(Paint.Cap.ROUND);
            arc.setColor(ui.dark ? 0xFF8FD8DD : Ui.BRAND.base);
        }

        void progress(float p) {
            progress = Math.max(0f, Math.min(1f, p));
            invalidate();
        }

        void spin(boolean on) {
            if (on && spin == null) {
                spin = ValueAnimator.ofFloat(0f, 360f);
                spin.setDuration(900);
                spin.setRepeatCount(ValueAnimator.INFINITE);
                spin.setInterpolator(new LinearInterpolator());
                spin.addUpdateListener(a -> {
                    angle = (float) a.getAnimatedValue();
                    invalidate();
                });
                spin.start();
            } else if (!on && spin != null) {
                spin.cancel();
                spin = null;
                angle = 0;
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            spin(false);
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float rad = Math.min(cx, cy) - getResources().getDisplayMetrics().density * 4;
            c.drawCircle(cx, cy, rad, disc);
            float inner = rad * 0.52f;
            box.set(cx - inner, cy - inner, cx + inner, cy + inner);
            float sweep = spin != null ? 270f : 300f * progress;
            float start = spin != null ? angle : -90f + 200f * progress;
            c.drawArc(box, start, sweep, false, arc);
        }
    }
}
