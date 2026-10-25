package com.textrelay.relay;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * Reads an attachment Messages handed us and, for pictures, makes a small preview for the thread view.
 * The original bytes are never changed: the inbox gets exactly what arrived on this phone.
 */
final class Media {
    /** The inbox server keeps files up to this size. */
    static final int MAX_BYTES = 25 * 1024 * 1024;
    /** Longest side of a preview, in pixels. */
    static final int PREVIEW_EDGE = 1024;
    /** A picture this small, with its longest side at most twice PREVIEW_EDGE, is shown as it is; no preview is made. */
    static final int PREVIEW_SKIP_BYTES = 350 * 1024;
    private static final int PREVIEW_QUALITY = 82;
    /** A HEIC or HEIF picture becomes a JPEG this size at most (longest side), at this quality. */
    static final int JPEG_EDGE = 4096;
    private static final int JPEG_QUALITY = 90;

    /** What the inbox can store: Hedy's file storage keeps only these types. */
    private static final java.util.Set<String> STORABLE = new java.util.HashSet<>(java.util.Arrays.asList(
            "image/jpeg", "image/png", "image/gif", "image/webp", "video/mp4", "video/webm",
            "audio/mpeg", "audio/mp4", "audio/webm", "application/pdf"));
    /** Pictures this phone turns into JPEG first, because the inbox can't keep them as they are. */
    private static final java.util.Set<String> TO_JPEG = new java.util.HashSet<>(java.util.Arrays.asList(
            "image/heic", "image/heif", "image/bmp", "image/x-ms-bmp"));

    private Media() {}

    static boolean storable(String mime) {
        return STORABLE.contains(normalize(mime));
    }

    /** True when the inbox can keep this attachment, as it is or once this phone has turned it into a JPEG. */
    static boolean sendable(String mime) {
        String t = normalize(mime);
        return STORABLE.contains(t) || TO_JPEG.contains(t);
    }

    static final class Blob {
        final byte[] bytes;
        final String mime;
        final int w;   // 0 when unknown
        final int h;

        Blob(byte[] bytes, String mime, int w, int h) {
            this.bytes = bytes;
            this.mime = mime;
            this.w = w;
            this.h = h;
        }
    }

    static final class Prepared {
        final Blob original;
        /** Null when the original is its own preview, or isn't a picture. */
        final Blob preview;

        Prepared(Blob original, Blob preview) {
            this.original = original;
            this.preview = preview;
        }
    }

    /** How Relay gets at attachment bytes; tests supply their own. */
    interface Reader {
        Prepared read(Uri uri, String mime) throws IOException;
    }

    static Reader reader(Context context) {
        ContentResolver resolver = context.getContentResolver();
        return (uri, mime) -> prepare(readAll(resolver, uri), mime);
    }

    /**
     * Keeps the original as it is and adds a JPEG preview for pictures that need one. A HEIC or HEIF picture, which the
     * inbox can't store, becomes a JPEG first (turned upright, at most JPEG_EDGE pixels on its longest side).
     */
    static Prepared prepare(byte[] bytes, String mime) throws IOException {
        if (bytes == null || bytes.length == 0) throw new IOException("empty attachment");
        if (bytes.length > MAX_BYTES) throw new IOException("attachment larger than " + MAX_BYTES + " bytes");
        String type = normalize(mime);
        if (TO_JPEG.contains(type)) {
            Blob jpeg = toJpeg(bytes);
            if (jpeg == null) return new Prepared(new Blob(bytes, type, 0, 0), null);   // can't be read here; the inbox learns what it was
            bytes = jpeg.bytes;
            type = jpeg.mime;
        }
        if (!type.startsWith("image/")) return new Prepared(new Blob(bytes, type, 0, 0), null);

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        int rotation = exifRotation(bytes, type);
        int w = bounds.outWidth;
        int h = bounds.outHeight;
        if (rotation == 90 || rotation == 270) {
            int t = w;
            w = h;
            h = t;
        }
        if (w <= 0 || h <= 0) return new Prepared(new Blob(bytes, type, 0, 0), null);   // this phone can't decode it; the inbox still gets the file

        Blob original = new Blob(bytes, type, w, h);
        boolean smallEnough = bytes.length <= PREVIEW_SKIP_BYTES && Math.max(w, h) <= PREVIEW_EDGE * 2;
        if ("image/gif".equals(type) || smallEnough) return new Prepared(original, null);
        return new Prepared(original, jpegPreview(bytes, Math.max(bounds.outWidth, bounds.outHeight), rotation));
    }

    /**
     * A picture in any format Android reads, as a JPEG. ImageDecoder turns it upright; where it can't read the picture,
     * BitmapFactory tries, turned by the picture's EXIF orientation. Null when neither can read it.
     */
    private static Blob toJpeg(byte[] bytes) {
        Bitmap bitmap = null;
        try {
            try {
                android.graphics.ImageDecoder.Source source = android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes));
                bitmap = android.graphics.ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
                    decoder.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE);
                    int w = info.getSize().getWidth();
                    int h = info.getSize().getHeight();
                    int longest = Math.max(w, h);
                    if (longest > JPEG_EDGE) {
                        float k = (float) JPEG_EDGE / longest;
                        decoder.setTargetSize(Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)));
                    }
                });
            } catch (IOException | RuntimeException e) {
                bitmap = decodeUpright(bytes);
            }
            if (bitmap == null) return null;
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, buf)) return null;
            byte[] out = buf.toByteArray();
            return out.length > 0 && out.length <= MAX_BYTES ? new Blob(out, "image/jpeg", bitmap.getWidth(), bitmap.getHeight()) : null;
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        } finally {
            if (bitmap != null) bitmap.recycle();
        }
    }

    private static Bitmap decodeUpright(byte[] bytes) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        int longest = Math.max(bounds.outWidth, bounds.outHeight);
        if (longest <= 0) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        int sample = 1;
        while (longest / (sample * 2) >= JPEG_EDGE) sample *= 2;
        o.inSampleSize = sample;
        Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        if (decoded == null) return null;
        int rotation = orientation(bytes);
        float scale = Math.min(1f, (float) JPEG_EDGE / Math.max(decoded.getWidth(), decoded.getHeight()));
        if (rotation == 0 && scale >= 1f) return decoded;
        Matrix m = new Matrix();
        if (scale < 1f) m.postScale(scale, scale);
        if (rotation != 0) m.postRotate(rotation);
        Bitmap out = Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), m, true);
        if (out != decoded) decoded.recycle();
        return out;
    }

    /** EXIF orientation of any picture ExifInterface reads (JPEG, HEIF, PNG, WebP), in degrees. */
    private static int orientation(byte[] bytes) {
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            switch (new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    return 90;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    return 180;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    return 270;
                default:
                    return 0;
            }
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    private static Blob jpegPreview(byte[] bytes, int longest, int rotation) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        int sample = 1;
        while (longest / (sample * 2) >= PREVIEW_EDGE) sample *= 2;
        o.inSampleSize = sample;
        Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        if (decoded == null) return null;
        float scale = Math.min(1f, (float) PREVIEW_EDGE / Math.max(decoded.getWidth(), decoded.getHeight()));
        Bitmap out = decoded;
        if (scale < 1f || rotation != 0) {
            Matrix m = new Matrix();
            if (scale < 1f) m.postScale(scale, scale);
            if (rotation != 0) m.postRotate(rotation);
            out = Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), m, true);
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        out.compress(Bitmap.CompressFormat.JPEG, PREVIEW_QUALITY, buf);
        Blob preview = new Blob(buf.toByteArray(), "image/jpeg", out.getWidth(), out.getHeight());
        if (out != decoded) out.recycle();
        decoded.recycle();
        return preview;
    }

    /** Degrees a JPEG wants to be turned, from its EXIF orientation; 0 when there is none. */
    static int exifRotation(byte[] bytes, String mime) {
        if (!"image/jpeg".equals(mime)) return 0;
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            switch (new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                case ExifInterface.ORIENTATION_TRANSPOSE:
                    return 90;
                case ExifInterface.ORIENTATION_ROTATE_180:
                case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                    return 180;
                case ExifInterface.ORIENTATION_ROTATE_270:
                case ExifInterface.ORIENTATION_TRANSVERSE:
                    return 270;
                default:
                    return 0;
            }
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    static String normalize(String mime) {
        if (mime == null) return "application/octet-stream";
        String t = mime.toLowerCase(Locale.US);
        int semi = t.indexOf(';');
        if (semi >= 0) t = t.substring(0, semi);
        t = t.trim();
        if (t.equals("image/jpg")) t = "image/jpeg";
        return t.isEmpty() ? "application/octet-stream" : t;
    }

    static byte[] readAll(ContentResolver resolver, Uri uri) throws IOException {
        InputStream in;
        try {
            in = resolver.openInputStream(uri);
        } catch (SecurityException e) {
            throw new IOException("no permission to read the attachment", e);
        }
        if (in == null) throw new IOException("attachment not found");
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = is.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BYTES) throw new IOException("attachment larger than " + MAX_BYTES + " bytes");
            }
            return out.toByteArray();
        }
    }
}
