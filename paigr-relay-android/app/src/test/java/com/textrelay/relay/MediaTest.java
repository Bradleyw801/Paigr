package com.textrelay.relay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.ExifInterface;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/** Real decoding and encoding, so the preview rules are checked on real JPEG and PNG bytes. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MediaTest {

    @Test
    public void aBigPictureKeepsItsOriginalAndGetsASmallPreview() throws IOException {
        byte[] jpeg = picture(2400, 1600, Bitmap.CompressFormat.JPEG);
        Media.Prepared p = Media.prepare(jpeg, "image/jpeg");

        assertSame(jpeg, p.original.bytes);   // the very same bytes, not a re-encode
        assertEquals("image/jpeg", p.original.mime);
        assertEquals(2400, p.original.w);
        assertEquals(1600, p.original.h);

        assertNotNull(p.preview);
        assertEquals("image/jpeg", p.preview.mime);
        assertEquals(1024, p.preview.w);
        assertTrue(p.preview.h == 682 || p.preview.h == 683);
        assertTrue(p.preview.bytes.length < jpeg.length);
        BitmapFactory.Options decoded = bounds(p.preview.bytes);
        assertEquals(1024, decoded.outWidth);
        assertEquals("image/jpeg", decoded.outMimeType);
    }

    @Test
    public void aHeicPictureBecomesAJpegTheInboxCanStore() throws IOException {
        // A picture Android can read, labelled the way Messages labels a HEIC photo.
        byte[] png = picture(2400, 1600, Bitmap.CompressFormat.PNG);
        Media.Prepared p = Media.prepare(png, "image/heic");
        assertEquals("image/jpeg", p.original.mime);
        assertEquals(2400, p.original.w);
        assertEquals(1600, p.original.h);
        assertEquals("image/jpeg", bounds(p.original.bytes).outMimeType);
        assertTrue(Media.storable(p.original.mime));
        assertNotNull(p.preview);
    }

    @Test
    public void aVeryLargeHeifPictureIsMadeSmallerOnTheWay() throws IOException {
        Media.Prepared p = Media.prepare(picture(5000, 2500, Bitmap.CompressFormat.PNG), "image/heif");
        assertEquals("image/jpeg", p.original.mime);
        assertEquals(Media.JPEG_EDGE, p.original.w);
        assertEquals(Media.JPEG_EDGE / 2, p.original.h);
    }

    @Test
    public void aHeicPictureThatCantBeReadStaysAsItWas() throws IOException {
        byte[] junk = new byte[2000];
        new java.util.Random(7).nextBytes(junk);
        Media.Prepared p = Media.prepare(junk, "image/heic");
        assertSame(junk, p.original.bytes);
        assertEquals("image/heic", p.original.mime);
        assertNull(p.preview);
        assertFalse(Media.storable(p.original.mime));
    }

    @Test
    public void aSmallPictureIsItsOwnPreview() throws IOException {
        byte[] png = picture(200, 150, Bitmap.CompressFormat.PNG);
        Media.Prepared p = Media.prepare(png, "image/png");
        assertSame(png, p.original.bytes);
        assertEquals(200, p.original.w);
        assertEquals(150, p.original.h);
        assertNull(p.preview);
    }

    @Test
    public void aSidewaysPhotoIsReportedAndPreviewedUpright() throws IOException {
        byte[] jpeg = withOrientation(picture(3200, 2400, Bitmap.CompressFormat.JPEG), ExifInterface.ORIENTATION_ROTATE_90);
        assertEquals(90, Media.exifRotation(jpeg, "image/jpeg"));
        Media.Prepared p = Media.prepare(jpeg, "image/jpeg");
        assertSame(jpeg, p.original.bytes);
        assertEquals(2400, p.original.w);   // as the viewer will see it
        assertEquals(3200, p.original.h);
        assertNotNull(p.preview);
        assertEquals(768, p.preview.w);     // turned upright, so the thread never shows it sideways
        assertEquals(1024, p.preview.h);
    }

    @Test
    public void aSmallSidewaysPhotoIsLeftToTheViewerToTurn() throws IOException {
        byte[] jpeg = withOrientation(picture(1600, 1200, Bitmap.CompressFormat.JPEG), ExifInterface.ORIENTATION_ROTATE_90);
        Media.Prepared p = Media.prepare(jpeg, "image/jpeg");
        assertTrue(jpeg.length <= Media.PREVIEW_SKIP_BYTES);
        assertNull(p.preview);              // browsers apply the EXIF orientation themselves
        assertEquals(1200, p.original.w);
        assertEquals(1600, p.original.h);
    }

    @Test
    public void mimeTypesAreTidiedUp() {
        assertEquals("image/jpeg", Media.normalize("image/jpg"));
        assertEquals("image/jpeg", Media.normalize("IMAGE/JPEG; charset=binary"));
        assertEquals("application/octet-stream", Media.normalize(null));
        assertEquals("application/octet-stream", Media.normalize("  "));
    }

    @Test
    public void otherFilesAreKeptWholeWithoutAPreview() throws IOException {
        byte[] clip = new byte[5000];
        Media.Prepared p = Media.prepare(clip, "video/mp4");
        assertSame(clip, p.original.bytes);
        assertEquals(0, p.original.w);
        assertNull(p.preview);
    }

    @Test
    public void somethingTooBigForTheInboxIsRefusedUpFront() {
        try {
            Media.prepare(new byte[Media.MAX_BYTES + 1], "image/jpeg");
            fail("expected an IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("larger"));
        }
    }

    private static byte[] picture(int w, int h, Bitmap.CompressFormat format) {
        Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        c.drawColor(Color.rgb(20, 120, 110));
        Paint paint = new Paint();
        for (int i = 0; i < 40; i++) {
            paint.setColor(Color.rgb((i * 37) % 256, (i * 91) % 256, (i * 53) % 256));
            c.drawCircle((w * (i % 8)) / 8f + w / 16f, (h * (i / 8)) / 5f + h / 10f, Math.min(w, h) / 12f, paint);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bm.compress(format, 90, out);
        bm.recycle();
        return out.toByteArray();
    }

    private static byte[] withOrientation(byte[] jpeg, int orientation) throws IOException {
        File f = File.createTempFile("relay-test", ".jpg");
        try {
            Files.write(f.toPath(), jpeg);
            ExifInterface exif = new ExifInterface(f.getAbsolutePath());
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, Integer.toString(orientation));
            exif.saveAttributes();
            return Files.readAllBytes(f.toPath());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    private static BitmapFactory.Options bounds(byte[] bytes) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        return o;
    }
}
