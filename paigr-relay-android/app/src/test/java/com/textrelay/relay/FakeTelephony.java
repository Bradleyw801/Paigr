package com.textrelay.relay;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;

import java.util.List;

/**
 * A stand-in for Android's message store (the sms, mms and mms-sms providers) over an in-memory SQLite database, with
 * the same tables and columns Relay reads. Tests add threads and messages, then let Relay read them.
 */
public class FakeTelephony extends ContentProvider {
    private SQLiteDatabase db;
    /** Reading this MMS's parts fails, as a provider sometimes does. */
    volatile long failPartsFor = -1;

    @Override
    public boolean onCreate() {
        db = SQLiteDatabase.create(null);
        db.execSQL("CREATE TABLE sms (_id INTEGER PRIMARY KEY, thread_id INTEGER, address TEXT, date INTEGER, type INTEGER, read INTEGER, body TEXT)");
        db.execSQL("CREATE TABLE pdu (_id INTEGER PRIMARY KEY, thread_id INTEGER, date INTEGER, msg_box INTEGER, read INTEGER, m_type INTEGER)");
        db.execSQL("CREATE TABLE part (_id INTEGER PRIMARY KEY, mid INTEGER, seq INTEGER, ct TEXT, text TEXT)");
        db.execSQL("CREATE TABLE addr (_id INTEGER PRIMARY KEY, msg_id INTEGER, address TEXT, type INTEGER)");
        db.execSQL("CREATE TABLE threads (_id INTEGER PRIMARY KEY, recipient_ids TEXT, message_count INTEGER, date INTEGER, read INTEGER)");
        db.execSQL("CREATE TABLE canonical_addresses (_id INTEGER PRIMARY KEY, address TEXT)");
        return true;
    }

    // ------------------------------------------------------------------ for tests

    /** A conversation with these people; returns its thread id. */
    long thread(long id, String... addresses) {
        StringBuilder ids = new StringBuilder();
        for (String a : addresses) {
            long cid;
            try (Cursor c = db.rawQuery("SELECT _id FROM canonical_addresses WHERE address = ?", new String[] { a })) {
                if (c.moveToFirst()) cid = c.getLong(0);
                else {
                    ContentValues v = new ContentValues();
                    v.put("address", a);
                    cid = db.insert("canonical_addresses", null, v);
                }
            }
            if (ids.length() > 0) ids.append(' ');
            ids.append(cid);
        }
        ContentValues v = new ContentValues();
        v.put("_id", id);
        v.put("recipient_ids", ids.toString());
        v.put("message_count", 0);
        v.put("date", 0);
        v.put("read", 1);
        db.insertWithOnConflict("threads", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        return id;
    }

    /** An SMS: type 1 received, 2 sent, 5 failed. Returns its id. */
    long sms(long thread, String address, long date, int type, boolean read, String body) {
        ContentValues v = new ContentValues();
        v.put("thread_id", thread);
        v.put("address", address);
        v.put("date", date);
        v.put("type", type);
        v.put("read", read ? 1 : 0);
        v.put("body", body);
        long id = db.insert("sms", null, v);
        touch(thread, date, read);
        return id;
    }

    /** An MMS (box 1 received, 2 sent) with its text and attachments (mime types). Returns its id. */
    long mms(long thread, String from, long date, int box, boolean read, String text, String... mimes) {
        ContentValues v = new ContentValues();
        v.put("thread_id", thread);
        v.put("date", date / 1000);
        v.put("msg_box", box);
        v.put("read", read ? 1 : 0);
        v.put("m_type", box == 1 ? 132 : 128);
        long id = db.insert("pdu", null, v);
        int seq = 0;
        ContentValues smil = new ContentValues();
        smil.put("mid", id);
        smil.put("seq", -1);
        smil.put("ct", "application/smil");
        db.insert("part", null, smil);
        if (text != null) {
            ContentValues p = new ContentValues();
            p.put("mid", id);
            p.put("seq", seq++);
            p.put("ct", "text/plain");
            p.put("text", text);
            db.insert("part", null, p);
        }
        for (String m : mimes) {
            ContentValues p = new ContentValues();
            p.put("mid", id);
            p.put("seq", seq++);
            p.put("ct", m);
            db.insert("part", null, p);
        }
        if (from != null) {
            ContentValues a = new ContentValues();
            a.put("msg_id", id);
            a.put("address", from);
            a.put("type", 137);
            db.insert("addr", null, a);
        }
        touch(thread, date, read);
        return id;
    }

    /** The thread as Android shows it: its newest message's time, and read only when everything in it is. */
    void markRead(long thread, boolean read) {
        ContentValues v = new ContentValues();
        v.put("read", read ? 1 : 0);
        db.update("threads", v, "_id = ?", new String[] { Long.toString(thread) });
        db.update("sms", v, "thread_id = ?", new String[] { Long.toString(thread) });
        db.update("pdu", v, "thread_id = ?", new String[] { Long.toString(thread) });
    }

    private void touch(long thread, long date, boolean read) {
        db.execSQL("UPDATE threads SET message_count = message_count + 1, date = MAX(date, ?), read = CASE WHEN ? = 0 THEN 0 ELSE read END WHERE _id = ?",
                new Object[] { date, read ? 1 : 0, thread });
    }

    // ------------------------------------------------------------------ provider

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sortOrder) {
        String table;
        List<String> path = uri.getPathSegments();
        switch (uri.getAuthority()) {
            case "sms":
                table = "sms";
                break;
            case "mms":
                if (path.isEmpty()) table = "pdu";
                else if (path.get(0).equals("part")) {
                    table = "part";
                    if (failPartsFor >= 0 && args != null && args.length > 0 && Long.toString(failPartsFor).equals(args[0])) {
                        throw new IllegalStateException("the message store refused for a moment");
                    }
                }
                else if (path.size() == 2 && path.get(1).equals("addr")) {
                    table = "addr";
                    selection = (selection == null ? "" : "(" + selection + ") AND ") + "msg_id = " + Long.parseLong(path.get(0));
                } else throw new IllegalArgumentException("unknown uri " + uri);
                break;
            case "mms-sms":
                if (!path.isEmpty() && path.get(0).equals("conversations")) table = "threads";
                else if (!path.isEmpty() && path.get(0).equals("canonical-addresses")) table = "canonical_addresses";
                else throw new IllegalArgumentException("unknown uri " + uri);
                break;
            default:
                throw new IllegalArgumentException("unknown uri " + uri);
        }
        return db.query(table, projection, selection, args, null, null, sortOrder);
    }

    @Override
    public String getType(Uri uri) {
        return null;
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
