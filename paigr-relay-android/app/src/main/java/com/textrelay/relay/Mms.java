package com.textrelay.relay;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.telephony.SmsManager;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An attachment sent from this phone itself, as MMS: when Messages' reply action takes no files, the picture or file
 * still reaches the other side. The message is a plain m-send-req (OMA MMS 1.2) that this phone's MMS service sends
 * through the carrier; Android files it in the phone's own message store, so it shows in Messages like any sent
 * picture. The carrier's limits are read from the phone, and a picture is shrunk to fit them.
 */
final class Mms {
    private static final String TAG = "Mms";
    private static final String ACTION_SENT = "com.textrelay.relay.MMS_SENT";
    /** What the carrier takes when it doesn't say (Android's own defaults). */
    static final int DEFAULT_MAX_BYTES = 300 * 1024, DEFAULT_MAX_WIDTH = 640, DEFAULT_MAX_HEIGHT = 480;
    /** Room for the headers, the layout and the text beside the file. */
    private static final int OVERHEAD = 3072;
    /** How long a send may take before it's reported failed (the carrier can be slow to answer). */
    static final long SEND_WAIT_MS = 3 * 60_000L;
    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.currentTimeMillis() & 0xFFFF));

    private Mms() {}

    /** The carrier's limits as this phone knows them: {max bytes, max image width, max image height}. */
    static int[] limits(SmsManager sms) {
        int bytes = DEFAULT_MAX_BYTES, w = DEFAULT_MAX_WIDTH, h = DEFAULT_MAX_HEIGHT;
        try {
            Bundle c = sms.getCarrierConfigValues();
            if (c != null) {
                bytes = c.getInt(SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE, bytes);
                w = c.getInt(SmsManager.MMS_CONFIG_MAX_IMAGE_WIDTH, w);
                h = c.getInt(SmsManager.MMS_CONFIG_MAX_IMAGE_HEIGHT, h);
            }
        } catch (RuntimeException e) {
            // no carrier config to read: the defaults
        }
        if (bytes <= 0) bytes = DEFAULT_MAX_BYTES;
        if (w <= 0) w = DEFAULT_MAX_WIDTH;
        if (h <= 0) h = DEFAULT_MAX_HEIGHT;
        return new int[] { bytes, w, h };
    }

    /** A file as it will go: its bytes and type, after shrinking a picture to the carrier's limits. */
    static final class Part {
        final byte[] bytes;
        final String mime;

        Part(byte[] bytes, String mime) {
            this.bytes = bytes;
            this.mime = mime;
        }
    }

    /**
     * The file as the carrier takes it, or null when it can't be made to fit. A picture is scaled to the carrier's
     * image size and, if still too big, re-encoded as a smaller JPEG; an animated GIF is sent as it is or not at
     * all (a still of it would say less); anything else goes as it is when it fits.
     */
    static Part fit(byte[] bytes, String mime, String text, int[] limits) {
        String type = Media.normalize(mime);
        int budget = limits[0] - OVERHEAD - text.getBytes(StandardCharsets.UTF_8).length;
        if (budget <= 0) return null;
        boolean picture = type.startsWith("image/");
        if (!picture || type.equals("image/gif")) return bytes.length <= budget ? new Part(bytes, type) : null;
        int maxW = Math.max(limits[1], limits[2]), maxH = Math.min(limits[1], limits[2]);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        boolean fitsSize = bounds.outWidth > 0 && bounds.outHeight > 0
                && Math.max(bounds.outWidth, bounds.outHeight) <= maxW && Math.min(bounds.outWidth, bounds.outHeight) <= maxH
                && Media.exifRotation(bytes, type) == 0;
        if (bytes.length <= budget && fitsSize && (type.equals("image/jpeg") || type.equals("image/png"))) return new Part(bytes, type);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        float scale = Math.min(1f, Math.min((float) maxW / Math.max(bounds.outWidth, bounds.outHeight), (float) maxH / Math.min(bounds.outWidth, bounds.outHeight)));
        int quality = 85;
        for (int tries = 0; tries < 8; tries++) {
            byte[] jpeg = jpeg(bytes, scale, quality);
            if (jpeg == null) return null;
            if (jpeg.length <= budget) return new Part(jpeg, "image/jpeg");
            if (quality > 50) quality -= 15;
            else scale *= 0.75f;
            if (Math.max(bounds.outWidth, bounds.outHeight) * scale < 200) return null;
        }
        return null;
    }

    private static byte[] jpeg(byte[] bytes, float scale, int quality) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            int sample = 1;
            while (scale * sample <= 0.5f) sample *= 2;
            o.inSampleSize = sample;
            Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            if (decoded == null) return null;
            float left = scale * sample;   // what sampling didn't already shrink
            int rotation = Media.exifRotation(bytes, "image/jpeg");
            Bitmap out = decoded;
            if (left < 0.999f || rotation != 0) {
                Matrix m = new Matrix();
                if (left < 0.999f) m.postScale(left, left);
                if (rotation != 0) m.postRotate(rotation);
                out = Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), m, true);
            }
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            boolean ok = out.compress(Bitmap.CompressFormat.JPEG, quality, buf);
            if (out != decoded) out.recycle();
            decoded.recycle();
            return ok ? buf.toByteArray() : null;
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ the message itself

    /** Well-known WSP content types (OMA WAP-230), by name; anything else is written out as text. */
    private static int wellKnown(String type) {
        switch (type) {
            case "text/plain": return 0x03;
            case "image/gif": return 0x1D;
            case "image/jpeg": return 0x1E;
            case "image/png": return 0x20;
            case "application/vnd.wap.multipart.related": return 0x33;
            default: return -1;
        }
    }

    /** An MMS writer: WSP's integers, strings and lengths, as the PDU needs them. */
    static final class Pdu {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        void octet(int b) {
            out.write(b & 0xFF);
        }

        void shortInt(int v) {
            octet(0x80 | (v & 0x7F));
        }

        void uintvar(long v) {
            byte[] b = new byte[5];
            int n = 0;
            do {
                b[n++] = (byte) (v & 0x7F);
                v >>>= 7;
            } while (v > 0);
            while (n-- > 0) octet(b[n] | (n > 0 ? 0x80 : 0));
        }

        void valueLength(int n) {
            if (n < 31) octet(n);
            else {
                octet(31);
                uintvar(n);
            }
        }

        void longInt(long v) {
            byte[] b = new byte[8];
            int n = 0;
            while (v > 0 || n == 0) {
                b[7 - n++] = (byte) (v & 0xFF);
                v >>>= 8;
            }
            octet(n);
            out.write(b, 8 - n, n);
        }

        void textString(String s) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            if (b.length > 0 && (b[0] & 0xFF) >= 0x80) octet(0x7F);
            out.write(b, 0, b.length);
            octet(0);
        }

        void quotedString(String s) {
            octet(0x22);
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            out.write(b, 0, b.length);
            octet(0);
        }

        /** A content type with parameters, in general form: its length, the type, then the parameters. */
        void contentType(String type, byte[] params) {
            ByteArrayOutputStream v = new ByteArrayOutputStream();
            int known = wellKnown(type);
            if (known >= 0) v.write(0x80 | known);
            else {
                byte[] b = type.getBytes(StandardCharsets.UTF_8);
                v.write(b, 0, b.length);
                v.write(0);
            }
            if (params != null) v.write(params, 0, params.length);
            valueLength(v.size());
            byte[] b = v.toByteArray();
            out.write(b, 0, b.length);
        }

        byte[] bytes() {
            return out.toByteArray();
        }
    }

    /** The SMIL that lays the message out: the file on top, the words (if any) beneath. */
    static String smil(String kind, String fileName, boolean withText) {
        String media = "image".equals(kind) ? "img" : "video".equals(kind) ? "video" : "audio".equals(kind) ? "audio" : "ref";
        return "<smil><head><layout><root-layout width=\"320px\" height=\"480px\"/>"
                + "<region id=\"Image\" left=\"0\" top=\"0\" width=\"100%\" height=\"80%\" fit=\"meet\"/>"
                + "<region id=\"Text\" left=\"0\" top=\"80%\" width=\"100%\" height=\"20%\" fit=\"scroll\"/></layout></head>"
                + "<body><par dur=\"8000ms\"><" + media + " src=\"" + fileName + "\" region=\"Image\"/>"
                + (withText ? "<text src=\"text_0.txt\" region=\"Text\"/>" : "") + "</par></body></smil>";
    }

    static String fileName(String mime) {
        String t = Media.normalize(mime);
        String ext = t.equals("image/jpeg") ? "jpg" : t.equals("image/png") ? "png" : t.equals("image/gif") ? "gif"
                : t.equals("video/mp4") ? "mp4" : t.equals("video/3gpp") ? "3gp" : t.equals("audio/mpeg") ? "mp3"
                : t.equals("audio/mp4") ? "m4a" : t.equals("application/pdf") ? "pdf" : t.substring(t.indexOf('/') + 1).replaceAll("[^a-z0-9]", "");
        return (t.startsWith("image/") ? "image" : t.startsWith("video/") ? "video" : t.startsWith("audio/") ? "audio" : "file") + "_0." + ext;
    }

    /** An m-send-req to `numbers` with `part` and `text`: what the carrier's MMSC is handed. */
    static byte[] sendRequest(List<String> numbers, Part part, String text) {
        Pdu p = new Pdu();
        p.octet(0x8C); p.octet(0x80);                       // X-Mms-Message-Type: m-send-req
        p.octet(0x98); p.textString("T" + Long.toHexString(System.currentTimeMillis()) + Integer.toHexString(SEQ.incrementAndGet() & 0xFFFF));   // X-Mms-Transaction-ID
        p.octet(0x8D); p.shortInt(0x12);                    // X-Mms-MMS-Version: 1.2
        p.octet(0x85); p.longInt(System.currentTimeMillis() / 1000);   // Date
        p.octet(0x89); p.valueLength(1); p.octet(0x81);     // From: insert-address-token (the network fills it in)
        for (String n : numbers) {
            p.octet(0x97);                                  // To
            p.textString(n + "/TYPE=PLMN");
        }
        p.octet(0x8A); p.octet(0x80);                       // X-Mms-Message-Class: Personal
        Pdu expiry = new Pdu();                             // X-Mms-Expiry: a week, relative
        expiry.octet(0x81);
        expiry.longInt(7 * 86400L);
        p.octet(0x88);
        p.valueLength(expiry.out.size());
        p.out.write(expiry.bytes(), 0, expiry.out.size());
        p.octet(0x8F); p.octet(0x81);                       // X-Mms-Priority: Normal
        p.octet(0x86); p.octet(0x81);                       // X-Mms-Delivery-Report: No
        p.octet(0x90); p.octet(0x81);                       // X-Mms-Read-Report: No
        // Content-Type, last of the headers: multipart/related, starting at the layout.
        ByteArrayOutputStream params = new ByteArrayOutputStream();
        params.write(0x8A);                                 // Start
        byte[] start = "<smil>".getBytes(StandardCharsets.UTF_8);
        params.write(start, 0, start.length);
        params.write(0);
        params.write(0x89);                                 // Type
        byte[] smilType = "application/smil".getBytes(StandardCharsets.UTF_8);
        params.write(smilType, 0, smilType.length);
        params.write(0);
        p.octet(0x84);
        p.contentType("application/vnd.wap.multipart.related", params.toByteArray());

        // The body: the layout, the file, and the words.
        String kind = part.mime.startsWith("image/") ? "image" : part.mime.startsWith("video/") ? "video" : part.mime.startsWith("audio/") ? "audio" : "file";
        boolean withText = !text.isEmpty();
        String name = fileName(part.mime);
        List<byte[][]> parts = new ArrayList<>();
        parts.add(partOf("application/smil", null, "<smil>", "smil.xml", smil(kind, name, withText).getBytes(StandardCharsets.UTF_8)));
        parts.add(partOf(part.mime, null, "<" + name + ">", name, part.bytes));
        if (withText) parts.add(partOf("text/plain", new byte[] { (byte) 0x81, (byte) 0xEA }, "<text_0.txt>", "text_0.txt", text.getBytes(StandardCharsets.UTF_8)));
        p.uintvar(parts.size());
        for (byte[][] e : parts) {
            p.uintvar(e[0].length);
            p.uintvar(e[1].length);
            p.out.write(e[0], 0, e[0].length);
            p.out.write(e[1], 0, e[1].length);
        }
        return p.bytes();
    }

    /** One part's headers (its type, id and name) and data. */
    private static byte[][] partOf(String type, byte[] typeParams, String contentId, String location, byte[] data) {
        Pdu h = new Pdu();
        ByteArrayOutputStream params = new ByteArrayOutputStream();
        if (typeParams != null) params.write(typeParams, 0, typeParams.length);
        params.write(0x85);                                 // Name
        byte[] n = location.getBytes(StandardCharsets.UTF_8);
        params.write(n, 0, n.length);
        params.write(0);
        h.contentType(type, params.toByteArray());
        h.octet(0xC0);                                      // Content-ID
        h.quotedString(contentId);
        h.octet(0x8E);                                      // Content-Location
        h.textString(location);
        return new byte[][] { h.bytes(), data };
    }

    // ------------------------------------------------------------------ sending

    /** Whether the carrier accepted the message; the error code when not (SmsManager.MMS_ERROR_*), 0 for a timeout. */
    static final class Outcome {
        final boolean sent;
        final int error;

        Outcome(boolean sent, int error) {
            this.sent = sent;
            this.error = error;
        }
    }

    /** Hands the message to this phone's MMS service and waits for its answer (up to SEND_WAIT_MS). */
    static Outcome send(Context context, SmsManager sms, byte[] pdu) throws IOException {
        File dir = new File(context.getCacheDir(), "mms");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("no room for the message");
        int n = SEQ.incrementAndGet() & 0x7FFFFFFF;
        File f = new File(dir, "send-" + n + ".pdu");
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(pdu);
        }
        Uri uri = Files.uriFor(context, f);
        CountDownLatch done = new CountDownLatch(1);
        int[] result = { Integer.MIN_VALUE };
        String action = ACTION_SENT + "." + n;
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                result[0] = getResultCode();
                done.countDown();
            }
        };
        IntentFilter filter = new IntentFilter(action);
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED);
        else context.registerReceiver(r, filter);
        try {
            Intent sentIntent = new Intent(action).setPackage(context.getPackageName());
            PendingIntent sent = PendingIntent.getBroadcast(context, n, sentIntent, PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
            sms.sendMultimediaMessage(context, uri, null, null, sent);
            if (!done.await(SEND_WAIT_MS, TimeUnit.MILLISECONDS)) return new Outcome(false, 0);
            int code = result[0];
            return new Outcome(code == Activity.RESULT_OK, code);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome(false, 0);
        } finally {
            try {
                context.unregisterReceiver(r);
            } catch (RuntimeException ignored) {
                // already gone
            }
            if (!f.delete()) f.deleteOnExit();
        }
    }

    static String describe(Outcome o) {
        if (o.sent) return "sent";
        switch (o.error) {
            case 0: return "no answer from the MMS service";
            case SmsManager.MMS_ERROR_UNSPECIFIED: return "MMS failed";
            case SmsManager.MMS_ERROR_INVALID_APN: return "no MMS APN on this phone";
            case SmsManager.MMS_ERROR_UNABLE_CONNECT_MMS: return "couldn't connect to the carrier's MMS service";
            case SmsManager.MMS_ERROR_HTTP_FAILURE: return "the carrier's MMS service refused it";
            case SmsManager.MMS_ERROR_IO_ERROR: return "couldn't read the message to send";
            case SmsManager.MMS_ERROR_RETRY: return "the carrier asked to try again";
            case SmsManager.MMS_ERROR_CONFIGURATION_ERROR: return "MMS isn't configured on this phone";
            case SmsManager.MMS_ERROR_NO_DATA_NETWORK: return "no mobile data for MMS";
            default: return "MMS failed (" + o.error + ")";
        }
    }

    static void log(String what) {
        Log.i(TAG, what.replaceAll("[0-9]{6,}", "…"));   // never a number
    }

    /** The recipient numbers as the carrier wants them: digits and a leading plus, nothing else. */
    static List<String> numbers(List<String> raw) {
        List<String> out = new ArrayList<>();
        for (String r : raw) {
            if (r == null) continue;
            String n = r.trim().replaceAll("[\\s().-]", "");
            if (n.isEmpty() || !n.matches("\\+?[0-9*#]{3,20}")) continue;
            if (!out.contains(n)) out.add(n);
        }
        return out;
    }

    static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.US);
    }
}
