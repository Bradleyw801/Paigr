package com.textrelay.texts;

import android.media.Image;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Finds a QR code in a camera frame, and tells a Relay code from any other. */
final class QrDecoder {
    /** What Relay shows: TEXTRELAY2:<inbox server>/<code> (and a link to the page with a code, https://<server>/#link=<code>). */
    private static final Pattern OURS = Pattern.compile("TEXTRELAY2:[A-Z0-9.-]+(:[0-9]{1,5})?/[0-9A-Z-]{12,20}|HTTPS://[^/#?\\s]+/?#LINK=[0-9A-Z-]{12,20}");
    /** What Relay showed before private inboxes; the page says to update Relay. */
    private static final Pattern OLD = Pattern.compile("TEXTRELAY:(LINK|RELAY):[0-9A-Z]{12}");

    private QrDecoder() {}

    static boolean ours(String text) {
        if (text == null) return false;
        String t = text.trim().toUpperCase(Locale.US);
        return OURS.matcher(t).matches() || OLD.matcher(t).matches();
    }

    /** The QR code's text in a YUV camera frame, or null when there is none. */
    static String decode(Image image) {
        Image.Plane y = image.getPlanes()[0];
        ByteBuffer buf = y.getBuffer();
        int stride = y.getRowStride();
        int w = image.getWidth();
        int h = image.getHeight();
        byte[] data = new byte[stride * h];
        buf.get(data, 0, Math.min(buf.remaining(), data.length));
        return decode(data, stride, w, h);
    }

    /**
     * The QR code's text in a frame's brightness values (one byte per pixel, rows `stride` apart), looking at the
     * middle of the frame where the guide box is, then at all of it. Null when there is none.
     */
    static String decode(byte[] luminance, int stride, int width, int height) {
        int side = Math.min(width, height) * 3 / 4;
        String text = decode(luminance, stride, height, (width - side) / 2, (height - side) / 2, side, side);
        return text != null ? text : decode(luminance, stride, height, 0, 0, width, height);
    }

    private static String decode(byte[] luminance, int stride, int rows, int left, int top, int width, int height) {
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
        try {
            PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(luminance, stride, rows, left, top, width, height, false);
            Result r = new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(source)), hints);
            return r.getText();
        } catch (ReaderException | IllegalArgumentException e) {
            return null;
        }
    }
}
