package com.textrelay.texts;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The built-in QR scanner: the camera fills the screen, a box in the middle shows where to hold the code, and the
 * first Relay code it sees is handed back to the page. "Enter the code instead" closes it for typing.
 */
public class ScanActivity extends Activity {
    static final String EXTRA_TEXT = "text";
    /** Closed with "Enter the code instead". */
    static final int RESULT_TYPE = RESULT_FIRST_USER;
    private static final int ASK_CAMERA = 1;

    private TextureView preview;
    private TextView hint;
    private HandlerThread thread;
    private Handler handler;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private Size size;
    private final AtomicBoolean done = new AtomicBoolean();
    private volatile long saidNotOurs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        preview = new TextureView(this);
        root.addView(preview, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(new Frame(this), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        Ui ui = new Ui(this);
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(24);
        bottom.setPadding(pad, pad, pad, pad + dp(8));
        hint = ui.label("Point the camera at the code on Paigr Relay.", 15.5f, Ui.BOLD, Color.WHITE);
        hint.setGravity(Gravity.CENTER);
        bottom.addView(hint);
        TextView type = ui.pill("Type the code instead", R.drawable.ic_keyboard, ui.rounded(0x33FFFFFF, 23), Color.WHITE, v -> {
            setResult(RESULT_TYPE);
            finish();
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(16);
        bottom.addView(type, blp);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        root.addView(bottom, lp);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(4), dp(8), pad, dp(8));
        top.addView(ui.iconButton(R.drawable.ic_back, "Back", Color.WHITE, v -> finish()));
        TextView title = ui.label("Scan the code", 20f, Ui.BLACK, Color.WHITE);
        title.setPadding(dp(6), 0, 0, 0);
        top.addView(title);
        root.addView(top, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        root.setFitsSystemWindows(true);
        setContentView(root);
        setResult(RESULT_CANCELED);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.CAMERA }, ASK_CAMERA);
            return;
        }
        start();
    }

    @Override
    protected void onPause() {
        stop();
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != ASK_CAMERA) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) start();
        else hint.setText("Paigr needs the camera to scan the code. You can type the code instead.");
    }

    private void start() {
        if (thread == null) {
            thread = new HandlerThread("texts-scan");
            thread.start();
            handler = new Handler(thread.getLooper());
        }
        if (preview.isAvailable()) open();
        else preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                open();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
                fit();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture st) {
                // each preview frame; decoding happens on the reader's frames
            }
        });
    }

    private void open() {
        if (camera != null || thread == null) return;
        CameraManager cm = getSystemService(CameraManager.class);
        try {
            String id = null;
            for (String c : cm.getCameraIdList()) {
                Integer facing = cm.getCameraCharacteristics(c).get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    id = c;
                    break;
                }
                if (id == null) id = c;
            }
            if (id == null) {
                problem("This phone has no camera Paigr can use. You can type the code instead.");
                return;
            }
            StreamConfigurationMap map = cm.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            size = pick(map != null ? map.getOutputSizes(ImageFormat.YUV_420_888) : null);
            reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 2);
            reader.setOnImageAvailableListener(this::frame, handler);
            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice c) {
                    camera = c;
                    session();
                }

                @Override
                public void onDisconnected(CameraDevice c) {
                    c.close();
                    camera = null;
                }

                @Override
                public void onError(CameraDevice c, int error) {
                    c.close();
                    camera = null;
                    problem("The camera couldn't start. Close other camera apps and try again, or type the code instead.");
                }
            }, handler);
        } catch (CameraAccessException | SecurityException | IllegalArgumentException e) {
            problem("The camera couldn't start. You can type the code instead.");
        }
    }

    /** About 1280×720: plenty for a QR code on a screen, and quick to read. */
    private static Size pick(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return new Size(1280, 720);
        Size best = sizes[0];
        long bestScore = Long.MAX_VALUE;
        for (Size s : sizes) {
            long score = Math.abs((long) s.getWidth() * s.getHeight() - 1280L * 720);
            if (s.getWidth() * 9 != s.getHeight() * 16) score += 100_000;   // prefer 16:9
            if (score < bestScore) {
                best = s;
                bestScore = score;
            }
        }
        return best;
    }

    @SuppressWarnings("deprecation")   // the list form of createCaptureSession works on every version this app runs on
    private void session() {
        try {
            SurfaceTexture st = preview.getSurfaceTexture();
            if (st == null || camera == null) return;
            st.setDefaultBufferSize(size.getWidth(), size.getHeight());
            Surface view = new Surface(st);
            runOnUiThread(this::fit);
            camera.createCaptureSession(Arrays.asList(view, reader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession s) {
                    if (camera == null) return;
                    session = s;
                    try {
                        CaptureRequest.Builder b = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        b.addTarget(view);
                        b.addTarget(reader.getSurface());
                        b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                        s.setRepeatingRequest(b.build(), null, handler);
                    } catch (CameraAccessException | IllegalStateException e) {
                        problem("The camera stopped. You can type the code instead.");
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession s) {
                    problem("The camera couldn't start. You can type the code instead.");
                }
            }, handler);
        } catch (CameraAccessException | IllegalStateException e) {
            problem("The camera couldn't start. You can type the code instead.");
        }
    }

    /** Shows the preview at its own proportions, filling the screen (the sides are cropped rather than squashed). */
    private void fit() {
        if (size == null || preview.getWidth() == 0) return;
        float vw = preview.getWidth();
        float vh = preview.getHeight();
        boolean portrait = vh >= vw;
        float content = portrait ? (float) size.getHeight() / size.getWidth() : (float) size.getWidth() / size.getHeight();
        float view = vw / vh;
        Matrix m = new Matrix();
        if (content > view) m.setScale(content / view, 1f, vw / 2, vh / 2);
        else m.setScale(1f, view / content, vw / 2, vh / 2);
        preview.setTransform(m);
    }

    private void frame(ImageReader r) {
        Image img = r.acquireLatestImage();
        if (img == null) return;
        try {
            if (done.get()) return;
            String text = QrDecoder.decode(img);
            if (text == null) return;
            if (!QrDecoder.ours(text)) {
                long now = System.currentTimeMillis();
                if (now - saidNotOurs > 3_000) {
                    saidNotOurs = now;
                    runOnUiThread(() -> hint.setText("That isn't a code from Paigr Relay. Scan the code it shows."));
                }
                return;
            }
            if (!done.compareAndSet(false, true)) return;
            runOnUiThread(() -> {
                setResult(RESULT_OK, new Intent().putExtra(EXTRA_TEXT, text.trim()));
                finish();
            });
        } finally {
            img.close();
        }
    }

    private void problem(String text) {
        runOnUiThread(() -> hint.setText(text));
    }

    private void stop() {
        try {
            if (session != null) session.close();
        } catch (RuntimeException ignored) {
            // already closed
        }
        session = null;
        if (camera != null) camera.close();
        camera = null;
        if (reader != null) reader.close();
        reader = null;
        if (thread != null) thread.quitSafely();
        thread = null;
        handler = null;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** Darkens everything but the box in the middle, and marks its corners. */
    static final class Frame extends View {
        private final Paint dim = new Paint();
        private final Paint corner = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path hole = new Path();

        Frame(Context context) {
            super(context);
            dim.setColor(0x99000000);
            corner.setColor(0xFF8FD8DD);
            corner.setStyle(Paint.Style.STROKE);
            corner.setStrokeWidth(6 * context.getResources().getDisplayMetrics().density);
            corner.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth();
            float h = getHeight();
            float side = Math.min(w, h) * 0.7f;
            float l = (w - side) / 2;
            float t = (h - side) / 2.4f;
            RectF box = new RectF(l, t, l + side, t + side);
            hole.reset();
            hole.addRect(0, 0, w, h, Path.Direction.CW);
            hole.addRoundRect(box, 24, 24, Path.Direction.CCW);
            c.drawPath(hole, dim);
            float k = side / 7;
            c.drawLine(box.left, box.top + k, box.left, box.top, corner);
            c.drawLine(box.left, box.top, box.left + k, box.top, corner);
            c.drawLine(box.right - k, box.top, box.right, box.top, corner);
            c.drawLine(box.right, box.top, box.right, box.top + k, corner);
            c.drawLine(box.left, box.bottom - k, box.left, box.bottom, corner);
            c.drawLine(box.left, box.bottom, box.left + k, box.bottom, corner);
            c.drawLine(box.right - k, box.bottom, box.right, box.bottom, corner);
            c.drawLine(box.right, box.bottom, box.right, box.bottom - k, corner);
        }
    }
}
