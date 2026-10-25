package com.textrelay.texts;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pictures and other attachments. A private inbox's files are sealed: fetched from storage, opened here as this
 * conversation's and this message's (the AAD its path names), and shown from memory. What came from storage stays on
 * disk still sealed, so a picture seen once doesn't download again; decoded pictures stay in memory while there's room.
 */
final class Media {
    private static final long MAX_BYTES = 64L * 1024 * 1024;

    interface Got<T> {
        void got(T value);
    }

    private final Inbox inbox;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "texts-media");
        t.setDaemon(true);
        return t;
    });
    private final LruCache<String, Bitmap> pictures;
    /** key -> who's waiting for it, so one file is fetched and opened once at a time */
    private final Map<String, List<Got<Bitmap>>> waiting = new HashMap<>();
    private int gen;

    Media(Inbox inbox) {
        this.inbox = inbox;
        int kb = (int) Math.min(Integer.MAX_VALUE, Runtime.getRuntime().maxMemory() / 1024 / 6);
        pictures = new LruCache<String, Bitmap>(kb) {
            @Override
            protected int sizeOf(String key, Bitmap b) {
                return Math.max(1, b.getByteCount() / 1024);
            }
        };
    }

    private static String key(String path, int width) {
        return path + "@" + width;
    }

    /** A picture already opened at this width, or null. */
    Bitmap cached(String path, int width) {
        return path == null ? null : pictures.get(key(path, width));
    }

    /** A picture, scaled to about `width` pixels wide; null when it couldn't be fetched or didn't open. */
    void picture(String path, int width, Got<Bitmap> then) {
        if (path == null) {
            then.got(null);
            return;
        }
        String k = key(path, width);
        Bitmap hit = pictures.get(k);
        if (hit != null) {
            then.got(hit);
            return;
        }
        List<Got<Bitmap>> list = waiting.get(k);
        if (list != null) {
            list.add(then);
            return;
        }
        waiting.put(k, list = new ArrayList<>());
        list.add(then);
        Link at = inbox.link;
        int g = gen;
        pool.execute(() -> {
            Bitmap b = null;
            try {
                b = decode(open(at, path), width);
            } catch (IOException | RuntimeException | java.security.GeneralSecurityException ignored) {
                b = null;
            }
            Bitmap got = b;
            main.post(() -> {
                List<Got<Bitmap>> who = waiting.remove(k);
                if (g != gen) return;
                if (got != null) pictures.put(k, got);
                if (who != null) for (Got<Bitmap> w : who) w.got(got);
            });
        });
    }

    /** Pictures of files on this phone (what's being sent), by their uri. */
    private final Map<String, Bitmap> locals = new HashMap<>();

    /** A picture of a file on this phone, scaled to about `width` wide, kept in memory (an attachment being sent). */
    void local(String uri, int width, Got<Bitmap> then) {
        String k = "local:" + uri + "@" + width;
        Bitmap hit = locals.get(k);
        if (hit != null) {
            then.got(hit);
            return;
        }
        pool.execute(() -> {
            Bitmap b = null;
            try {
                b = decode(readLocal(android.net.Uri.parse(uri), 24L * 1024 * 1024), width);
            } catch (IOException | RuntimeException ignored) {
                b = null;
            }
            Bitmap got = b;
            main.post(() -> {
                if (got != null) locals.put(k, got);
                then.got(got);
            });
        });
    }

    /** The bytes of a file on this phone, up to `max`; an IOException past that. */
    byte[] readLocal(android.net.Uri uri, long max) throws IOException {
        try (InputStream in = inbox.app.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("can't open it");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > max) throw new IOException("too big");
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    /** What a file on this phone is: its type, name and size, as the picker's provider says. */
    static String[] describeLocal(android.content.ContentResolver cr, android.net.Uri uri) {
        String type = cr.getType(uri);
        String name = null;
        long size = -1;
        try (android.database.Cursor c = cr.query(uri, new String[] { android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE }, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                name = c.getString(0);
                size = c.isNull(1) ? -1 : c.getLong(1);
            }
        } catch (RuntimeException ignored) {
            // a provider that won't say
        }
        if (name == null || name.isEmpty()) name = uri.getLastPathSegment() != null ? uri.getLastPathSegment() : "file";
        if (type == null || type.isEmpty()) {
            String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.US) : "";
            type = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (type == null) type = "application/octet-stream";
        }
        return new String[] { type, name, String.valueOf(size) };
    }

    /** A sealed file this phone just stored, kept here under its path, so it shows without a download. */
    void keepSealed(Link at, String path, byte[] sealed) {
        File kept = sealedFile(at, path);
        try (FileOutputStream out = new FileOutputStream(kept)) {
            out.write(sealed);
        } catch (IOException ignored) {
            // fetched when needed
        }
    }

    /** A picture's bytes, scaled to fit `maxSide` and written as a JPEG about `quality` (a preview, or what MMS takes). */
    static byte[] shrink(byte[] bytes, int maxSide, int quality) {
        Bitmap b = decode(bytes, maxSide);
        if (b == null) return null;
        if (b.getWidth() > maxSide || b.getHeight() > maxSide) {
            float k = Math.min((float) maxSide / b.getWidth(), (float) maxSide / b.getHeight());
            Bitmap small = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * k)), Math.max(1, Math.round(b.getHeight() * k)), true);
            if (small != b) b.recycle();
            b = small;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, quality, out);
        b.recycle();
        return out.toByteArray();
    }

    /** A picture's size in pixels, turned the way it was taken; {0, 0} when it isn't one. */
    static int[] size(byte[] bytes) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return new int[] { 0, 0 };
        int turn = rotation(bytes);
        return turn == 90 || turn == 270 ? new int[] { bounds.outHeight, bounds.outWidth } : new int[] { bounds.outWidth, bounds.outHeight };
    }

    /** A file, opened: the original of an attachment (to play or save). Null when it couldn't be had. */
    void bytes(String path, Got<byte[]> then) {
        Link at = inbox.link;
        int g = gen;
        pool.execute(() -> {
            byte[] b;
            try {
                b = open(at, path);
            } catch (IOException | RuntimeException | java.security.GeneralSecurityException e) {
                b = null;
            }
            byte[] got = b;
            main.post(() -> {
                if (g == gen) then.got(got);
            });
        });
    }

    /** An opened file written where this app alone can play it from; the folder is emptied when the player closes. */
    File playable(byte[] plain, String mime) throws IOException {
        File dir = new File(inbox.app.getCacheDir(), "play");
        if (!dir.isDirectory()) //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        String ext = Viewer.extension(mime);
        File f = new File(dir, "m" + System.nanoTime() + "." + ext);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(plain);
        }
        return f;
    }

    void forgetPlayable() {
        File dir = new File(inbox.app.getCacheDir(), "play");
        File[] all = dir.listFiles();
        if (all != null) for (File f : all) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    /** Nothing opened with one inbox's key stays once this phone is in another (or none). */
    void forget() {
        gen++;
        pictures.evictAll();
        locals.clear();
        waiting.clear();
        pool.execute(() -> {
            File[] all = sealedDir().listFiles();
            if (all != null) for (File f : all) //noinspection ResultOfMethodCallIgnored
                f.delete();
            forgetPlayable();
        });
    }

    // ------------------------------------------------------------------ fetching and opening

    private File sealedDir() {
        File d = new File(inbox.app.getCacheDir(), "sealed");
        if (!d.isDirectory()) //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        return d;
    }

    File sealedFile(Link at, String path) {
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(Crypto.utf8(at.tid + "\n" + path));
            return new File(sealedDir(), Crypto.hex(h).substring(0, 32) + ".enc");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** One of this inbox's files, opened: sealed for its conversation and message, so nothing else opens in its place. */
    private byte[] open(Link at, String path) throws IOException, java.security.GeneralSecurityException {
        if (at == null) throw new IOException("not linked");
        String[] file = Opener.fileOf(path);
        if (file == null || !file[0].equals(at.tid)) throw new IOException("That file isn't one of this inbox's.");
        File kept = sealedFile(at, path);
        byte[] sealed = null;
        if (kept.isFile()) {
            try {
                sealed = java.nio.file.Files.readAllBytes(kept.toPath());
            } catch (IOException e) {
                sealed = null;
            }
        }
        if (sealed != null) {
            try {
                return at.openFile(sealed, file[2]);
            } catch (java.security.GeneralSecurityException e) {
                //noinspection ResultOfMethodCallIgnored
                kept.delete();   // not what it should be: fetched again
            }
        }
        JSONObject r = new Hedy(at).get("/v2/media?p=" + Inbox.enc(path));
        String url = fileUrl(at, r.optString("url", ""));
        sealed = download(url);
        byte[] plain = at.openFile(sealed, file[2]);
        try (FileOutputStream out = new FileOutputStream(kept)) {
            out.write(sealed);
        } catch (IOException ignored) {
            // fetched again next time
        }
        return plain;
    }

    /** Where the server says a file is: only an https address, or the inbox's own server, is ever fetched. */
    static String fileUrl(Link at, String u) throws IOException {
        if (u.startsWith("https://")) return u;
        String base = at.server;
        if (u.startsWith("/") && !u.startsWith("//")) return base + u;
        if (sameOrigin(base, u)) return u;
        throw new IOException("That file's address isn't one this app opens.");
    }

    private static boolean sameOrigin(String a, String b) {
        try {
            URL x = new URL(a), y = new URL(b);
            return x.getProtocol().equalsIgnoreCase(y.getProtocol()) && x.getHost().equalsIgnoreCase(y.getHost())
                    && x.getPort() == y.getPort();
        } catch (IOException e) {
            return false;
        }
    }

    private static byte[] download(String url) throws IOException {
        HttpURLConnection c = Tor.open(new URL(url), 15_000, 60_000);
        try {
            c.setInstanceFollowRedirects(true);   // within https only: Android never follows one to http
            int status = c.getResponseCode();
            if (status != 200) throw new IOException("Could not load it (" + status + ").");
            long length = c.getContentLengthLong();
            if (length > MAX_BYTES) throw new IOException("That file is too big to open here.");
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(length > 0 ? (int) length : 64 * 1024);
                byte[] buf = new byte[16 * 1024];
                long total = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_BYTES) throw new IOException("That file is too big to open here.");
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    /** A picture's bytes as a bitmap about `width` pixels wide (never wider than it is), turned the way it was taken. */
    static Bitmap decode(byte[] bytes, int width) {
        if (bytes == null) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        int target = Math.max(64, width);
        while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= 64) sample *= 2;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        if (b == null) return null;
        int turn = rotation(bytes);
        if (turn != 0) {
            Matrix m = new Matrix();
            m.postRotate(turn);
            Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            if (r != b) b.recycle();
            b = r;
        }
        return b;
    }

    private static int rotation(byte[] bytes) {
        try {
            android.media.ExifInterface exif = new android.media.ExifInterface(new java.io.ByteArrayInputStream(bytes));
            int o = exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL);
            if (o == android.media.ExifInterface.ORIENTATION_ROTATE_90) return 90;
            if (o == android.media.ExifInterface.ORIENTATION_ROTATE_180) return 180;
            if (o == android.media.ExifInterface.ORIENTATION_ROTATE_270) return 270;
        } catch (IOException | RuntimeException ignored) {
            // no EXIF
        }
        return 0;
    }
}
