package com.textrelay.relay;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Hands one of this app's own files to another app, by a grant: the MMS service reads a message to send, and
 * Messages reads an attachment handed to its reply. Only files under the cache's "mms" folder, by name; nothing is
 * listed, written or deleted through here, and no other app can reach it without a grant for the exact file.
 */
public final class Files extends ContentProvider {
    static String authority(Context c) {
        return c.getPackageName() + ".files";
    }

    /** The uri another app is granted for `f` (a file under the cache's mms folder). */
    static Uri uriFor(Context c, File f) {
        return new Uri.Builder().scheme("content").authority(authority(c)).appendPath("mms").appendPath(f.getName()).build();
    }

    /** Writes `bytes` where another app can be granted them, named for their type; returns the uri to grant. */
    static Uri share(Context c, byte[] bytes, String mime) throws java.io.IOException {
        File dir = new File(c.getCacheDir(), "mms");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("no room for the attachment");
        String ext = Mms.fileName(mime);
        ext = ext.substring(ext.lastIndexOf('.'));
        File f = new File(dir, "share-" + Long.toHexString(System.currentTimeMillis()) + ext);
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
            out.write(bytes);
        }
        f.deleteOnExit();
        return uriFor(c, f);
    }

    /** Throws out shared files older than a day (Messages has long read what it was handed). */
    static void sweep(Context c) {
        File dir = new File(c.getCacheDir(), "mms");
        File[] old = dir.listFiles();
        if (old == null) return;
        long cutoff = System.currentTimeMillis() - 86_400_000L;
        for (File f : old) if (f.lastModified() < cutoff && !f.delete()) f.deleteOnExit();
    }

    private File fileOf(Uri uri) throws FileNotFoundException {
        java.util.List<String> parts = uri.getPathSegments();
        if (parts.size() != 2 || !"mms".equals(parts.get(0))) throw new FileNotFoundException(uri.toString());
        String name = parts.get(1);
        if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.startsWith(".")) throw new FileNotFoundException(uri.toString());
        File f = new File(new File(getContext().getCacheDir(), "mms"), name);
        if (!f.isFile()) throw new FileNotFoundException(uri.toString());
        return f;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("read only");
        return ParcelFileDescriptor.open(fileOf(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        try {
            String name = fileOf(uri).getName();
            String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.US) : "";
            String t = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            return t != null ? t : "application/octet-stream";
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File f;
        try {
            f = fileOf(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        String[] cols = projection != null ? projection : new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE };
        MatrixCursor c = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
        }
        c.addRow(row);
        return c;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
