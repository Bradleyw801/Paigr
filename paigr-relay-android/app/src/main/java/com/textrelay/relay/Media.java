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
        /** Null when the file is too big for the inbox: only its preview and size go up, and it stays on this phone. */
        final Blob original;
        /** Null when the original is its own preview, or isn't a picture or a video. */
        final Blob preview;
        /** The file's size, stored or not. */
        final long bytes;

        Prepared(Blob original, Blob preview) {
            this(original, preview, original != null ? original.bytes.length : 0);
        }

        Prepared(Blob original, Blob preview, long bytes) {
            this.original = original;
            this.preview = preview;
            this.bytes = bytes;
        }
    }

    /** How Relay gets at attachment bytes; tests supply their own. */
    interface Reader {
        Prepared read(Uri uri, String mime) throws IOException;
    }

    /**
     * Reads the file whole and prepares it. A video gets a poster frame as its preview, so threads show a picture of it
     * instead of a plain tile. One too big for the inbox still gets its preview and size up: the thread shows what it
     * is, and that it's on this phone.
     */
    static Reader reader(Context context) {
        ContentResolver resolver = context.getContentResolver();
        return (uri, mime) -> {
            String type = normalize(mime);
            byte[] bytes;
            try {
                bytes = readAll(resolver, uri);
            } catch (TooBig big) {
                return new Prepared(null, type.startsWith("video/") ? poster(context, uri) : null, big.bytes);
            }
            Prepared p = prepare(bytes, mime);
            if (p.preview == null && p.original != null && p.original.mime.startsWith("video/")) {
                Blob poster = poster(context, uri);
                if (poster != null) return new Prepared(new Blob(p.original.bytes, p.original.mime, poster.w, poster.h), poster);
            }
            return p;
        };
    }

    /** A frame from near the start of a video, as a JPEG preview (upright, PREVIEW_EDGE on its longest side); null when it can't be read. */
    static Blob poster(Context context, Uri uri) {
        android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
        Bitmap frame = null;
        try {
            r.setDataSource(context, uri);
            String d = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            long duration = d == null ? 0 : Long.parseLong(d);
            long at = Math.min(1_000_000L, duration * 1000 / 4);   // a quarter in, up to a second: past any black lead-in
            frame = r.getFrameAtTime(at, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) frame = r.getFrameAtTime();
            if (frame == null) return null;
            float scale = Math.min(1f, (float) PREVIEW_EDGE / Math.max(frame.getWidth(), frame.getHeight()));
            Bitmap out = scale < 1f ? Bitmap.createScaledBitmap(frame, Math.max(1, Math.round(frame.getWidth() * scale)), Math.max(1, Math.round(frame.getHeight() * scale)), true) : frame;
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            out.compress(Bitmap.CompressFormat.JPEG, PREVIEW_QUALITY, buf);
            Blob preview = new Blob(buf.toByteArray(), "image/jpeg", out.getWidth(), out.getHeight());
            if (out != frame) out.recycle();
            return preview.bytes.length > 0 ? preview : null;
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        } finally {
            if (frame != null) frame.recycle();
            try {
                r.release();
            } catch (RuntimeException | IOException ignored) {
                // released as far as it goes
            }
        }
    }

    /** A file the inbox can't keep whole, with how big it is. */
    static final class TooBig extends IOException {
        final long bytes;

        TooBig(long bytes) {
            super("attachment larger than " + MAX_BYTES + " bytes");
            this.bytes = bytes;
        }
    }

    /**
     * Keeps the original as it is and adds a JPEG preview for pictures that need one. A HEIC or HEIF picture, which the
     * inbox can't store, becomes a JPEG first (turned upright, at most JPEG_EDGE pixels on its longest side).
     */
    static Prepared prepare(byte[] bytes, String mime) throws IOException {
        if (bytes == null || bytes.length == 0) throw new IOException("empty attachment");
        if (bytes.length > MAX_BYTES) throw new TooBig(bytes.length);
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
                if (out.size() > MAX_BYTES) {
                    // Too big to keep: only how big it is matters now, so the rest is counted, not kept.
                    long total = out.size();
                    out = null;
                    while ((n = is.read(buf)) > 0) total += n;
                    throw new TooBig(total);
                }
            }
            return out.toByteArray();
        }
    }
}
