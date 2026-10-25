package com.textrelay.relay;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads the phone's own message store with READ_SMS: SMS, MMS, and the RCS messages Google Messages files there.
 * End-to-end encrypted RCS conversations don't show up here readably (their rows are missing or empty); empty rows are
 * skipped, and those conversations come through from Messages' notifications instead.
 */
final class Store {
    static final Uri SMS = Uri.parse("content://sms");
    static final Uri MMS = Uri.parse("content://mms");
    static final Uri MMS_PART = Uri.parse("content://mms/part");
    static final Uri THREADS = Uri.parse("content://mms-sms/conversations?simple=true");
    static final Uri CANONICAL = Uri.parse("content://mms-sms/canonical-addresses");
    static final Uri ALL = Uri.parse("content://mms-sms/");

    private static final int SMS_INBOX = 1, SMS_SENT = 2, SMS_FAILED = 5;
    private static final int MMS_INBOX = 1, MMS_SENT = 2, MMS_FAILED = 5;
    private static final int MMS_NOTIFICATION_IND = 130;   // an MMS that hasn't been downloaded yet
    private static final int ADDR_FROM = 137;
    private static final String SELF_TOKEN = "insert-address-token";

    /** One conversation in the store: who is in it, how many messages, when the last one was, and whether all are read. */
    static final class Thread {
        final long id;
        final List<String> addresses;
        final int count;
        final long date;
        final boolean read;

        Thread(long id, List<String> addresses, int count, long date, boolean read) {
            this.id = id;
            this.addresses = addresses;
            this.count = count;
            this.date = date;
            this.read = read;
        }
    }

    static final class Part {
        final long id;
        final String mime;
        final Uri uri;

        Part(long id, String mime) {
            this.id = id;
            this.mime = mime;
            this.uri = Uri.parse("content://mms/part/" + id);
        }
    }

    static final class Msg {
        final String source;   // "sms" or "mms"
        final long id;
        final long threadId;
        final long ts;         // milliseconds
        final boolean out;     // sent from this phone
        final boolean failed;
        final boolean read;
        final String address;  // the other side of an SMS, or the sender of an incoming MMS; null for an outgoing MMS
        final String text;
        final List<Part> media;

        Msg(String source, long id, long threadId, long ts, boolean out, boolean failed, boolean read, String address,
            String text, List<Part> media) {
            this.source = source;
            this.id = id;
            this.threadId = threadId;
            this.ts = ts;
            this.out = out;
            this.failed = failed;
            this.read = read;
            this.address = address;
            this.text = text;
            this.media = media;
        }

        /** The same key every time this message is read, so the inbox stores it once. */
        String key() {
            return source + ":" + id;
        }
    }

    /** Messages in time order, and how far they safely reach: everything up to upTo is in msgs. */
    static final class Page {
        final List<Msg> msgs;
        /** A kind of message filled the page: read again from upTo for the rest. */
        final boolean more;
        final long upTo;

        Page(List<Msg> msgs, boolean more, long upTo) {
            this.msgs = msgs;
            this.more = more;
            this.upTo = upTo;
        }
    }

    /** What one query read: the readable messages, how many rows there were, and the time of the last row. */
    private static final class Rows {
        final List<Msg> msgs = new ArrayList<>();
        int count;
        long lastTs;
    }

    private final ContentResolver cr;

    Store(Context context) {
        this.cr = context.getApplicationContext().getContentResolver();
    }

    /** Every conversation, the most recent first. */
    List<Thread> threads() {
        Map<Long, String> canonical = new HashMap<>();
        try (Cursor c = cr.query(CANONICAL, new String[] { "_id", "address" }, null, null, null)) {
            while (c != null && c.moveToNext()) canonical.put(c.getLong(0), c.getString(1));
        }
        List<Thread> out = new ArrayList<>();
        try (Cursor c = cr.query(THREADS, new String[] { "_id", "recipient_ids", "message_count", "date", "read" }, null, null, "date DESC")) {
            while (c != null && c.moveToNext()) {
                List<String> addresses = new ArrayList<>();
                String ids = c.getString(1);
                if (ids != null) {
                    for (String part : ids.trim().split("\\s+")) {
                        if (part.isEmpty()) continue;
                        try {
                            String a = canonical.get(Long.parseLong(part));
                            if (a != null && !a.isEmpty() && !addresses.contains(a)) addresses.add(a);
                        } catch (NumberFormatException ignored) {
                            // not an id
                        }
                    }
                }
                out.add(new Thread(c.getLong(0), addresses, c.getInt(2), c.getLong(3), c.getInt(4) != 0));
            }
        }
        return out;
    }

    /** One conversation by its id, or null when it's gone. */
    Thread thread(long id) {
        Map<Long, String> canonical = new HashMap<>();
        try (Cursor c = cr.query(CANONICAL, new String[] { "_id", "address" }, null, null, null)) {
            while (c != null && c.moveToNext()) canonical.put(c.getLong(0), c.getString(1));
        }
        try (Cursor c = cr.query(THREADS, new String[] { "_id", "recipient_ids", "message_count", "date", "read" },
                "_id = ?", new String[] { Long.toString(id) }, null)) {
            if (c == null || !c.moveToFirst()) return null;
            List<String> addresses = new ArrayList<>();
            String ids = c.getString(1);
            if (ids != null) {
                for (String part : ids.trim().split("\\s+")) {
                    try {
                        String a = part.isEmpty() ? null : canonical.get(Long.parseLong(part));
                        if (a != null && !a.isEmpty() && !addresses.contains(a)) addresses.add(a);
                    } catch (NumberFormatException ignored) {
                        // not an id
                    }
                }
            }
            return new Thread(c.getLong(0), addresses, c.getInt(2), c.getLong(3), c.getInt(4) != 0);
        }
    }

    /** Messages from fromTs on, in every conversation, oldest first. */
    Page since(long fromTs, int limit) {
        return page(sms("date >= ?", new String[] { Long.toString(fromTs) }, "date ASC", limit),
                mms("date >= ?", new String[] { Long.toString(fromTs / 1000) }, "date ASC", limit), limit);
    }

    /** One conversation's messages from fromTs on, oldest first: a page of its history. */
    Page inThread(long threadId, long fromTs, int limit) {
        String t = Long.toString(threadId);
        return page(sms("thread_id = ? AND date >= ?", new String[] { t, Long.toString(fromTs) }, "date ASC", limit),
                mms("thread_id = ? AND date >= ?", new String[] { t, Long.toString(fromTs / 1000) }, "date ASC", limit), limit);
    }

    /** A conversation's newest messages, newest first. */
    List<Msg> latest(long threadId, int n) {
        String[] args = { Long.toString(threadId) };
        List<Msg> out = new ArrayList<>(sms("thread_id = ?", args, "date DESC", n).msgs);
        out.addAll(mms("thread_id = ?", args, "date DESC", n).msgs);
        Collections.sort(out, (a, b) -> Long.compare(b.ts, a.ts));
        return out.size() > n ? new ArrayList<>(out.subList(0, n)) : out;
    }

    private static Page page(Rows sms, Rows mms, int limit) {
        long upTo = Long.MAX_VALUE;
        boolean more = false;
        if (sms.count >= limit) {
            upTo = sms.lastTs;
            more = true;
        }
        if (mms.count >= limit) {
            upTo = Math.min(upTo, mms.lastTs);
            more = true;
        }
        List<Msg> out = new ArrayList<>();
        for (Msg m : sms.msgs) if (m.ts <= upTo) out.add(m);
        for (Msg m : mms.msgs) if (m.ts <= upTo) out.add(m);
        Collections.sort(out, (a, b) -> a.ts != b.ts ? Long.compare(a.ts, b.ts) : a.key().compareTo(b.key()));
        if (!more) upTo = out.isEmpty() ? 0 : out.get(out.size() - 1).ts;
        return new Page(out, more, upTo);
    }

    private Rows sms(String selection, String[] args, String order, int limit) {
        Rows rows = new Rows();
        String where = "(" + selection + ") AND type IN (" + SMS_INBOX + "," + SMS_SENT + "," + SMS_FAILED + ")";
        try (Cursor c = cr.query(SMS, new String[] { "_id", "thread_id", "address", "date", "type", "read", "body" },
                where, args, order + " LIMIT " + limit)) {
            while (c != null && c.moveToNext()) {
                rows.count++;
                rows.lastTs = c.getLong(3);
                String body = c.getString(6);
                if (body == null || body.isEmpty()) continue;   // nothing readable (an encrypted message, or an empty row)
                int type = c.getInt(4);
                rows.msgs.add(new Msg("sms", c.getLong(0), c.getLong(1), c.getLong(3), type != SMS_INBOX, type == SMS_FAILED,
                        c.getInt(5) != 0, c.getString(2), body, Collections.emptyList()));
            }
        }
        return rows;
    }

    private Rows mms(String selection, String[] args, String order, int limit) {
        Rows rows = new Rows();
        String where = "(" + selection + ") AND msg_box IN (" + MMS_INBOX + "," + MMS_SENT + "," + MMS_FAILED
                + ") AND (m_type IS NULL OR m_type != " + MMS_NOTIFICATION_IND + ")";
        try (Cursor c = cr.query(MMS, new String[] { "_id", "thread_id", "date", "msg_box", "read" }, where, args, order + " LIMIT " + limit)) {
            while (c != null && c.moveToNext()) {
                rows.count++;
                rows.lastTs = c.getLong(2) * 1000;
                long id = c.getLong(0);
                int box = c.getInt(3);
                boolean outgoing = box != MMS_INBOX;
                StringBuilder text = new StringBuilder();
                List<Part> media = new ArrayList<>();
                try (Cursor p = cr.query(MMS_PART, new String[] { "_id", "ct", "text" }, "mid = ?", new String[] { Long.toString(id) }, "seq ASC")) {
                    while (p != null && p.moveToNext()) {
                        String mime = p.getString(1) == null ? "" : p.getString(1).toLowerCase(Locale.US);
                        if (mime.equals("text/plain")) {
                            String t = p.getString(2);
                            if (t != null && !t.isEmpty()) {
                                if (text.length() > 0) text.append('\n');
                                text.append(t);
                            }
                        } else if (mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/")) {
                            media.add(new Part(p.getLong(0), mime));
                        }
                    }
                }
                if (text.length() == 0 && media.isEmpty()) continue;   // nothing readable
                rows.msgs.add(new Msg("mms", id, c.getLong(1), c.getLong(2) * 1000, outgoing, box == MMS_FAILED, c.getInt(4) != 0,
                        outgoing ? null : sender(id), text.toString(), media));
            }
        }
        return rows;
    }

    private String sender(long mmsId) {
        try (Cursor c = cr.query(Uri.parse("content://mms/" + mmsId + "/addr"), new String[] { "address", "type" },
                "type = ?", new String[] { Integer.toString(ADDR_FROM) }, null)) {
            while (c != null && c.moveToNext()) {
                String a = c.getString(0);
                if (a != null && !a.isEmpty() && !SELF_TOKEN.equals(a)) return a;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ addresses and text

    /**
     * The part of an address that identifies a person: an email as is, otherwise its digits, the last ten for a
     * full-length number (so +1 801…, 1-801… and (801)… match) and all of them for a short code.
     */
    static String key(String address) {
        if (address == null) return "";
        String a = address.trim();
        if (a.contains("@")) return a.toLowerCase(Locale.US);
        String d = a.replaceAll("[^0-9]", "");
        return d.length() >= 10 ? d.substring(d.length() - 10) : d;
    }

    /** A number as people write it: (801) 555-0123 for a US number, otherwise as stored. */
    static String pretty(String address) {
        if (address == null) return "";
        String d = address.replaceAll("[^0-9]", "");
        if (d.length() == 11 && d.startsWith("1")) d = d.substring(1);
        if (d.length() == 10 && !address.contains("@")) return "(" + d.substring(0, 3) + ") " + d.substring(3, 6) + "-" + d.substring(6);
        return address.trim();
    }

    /** True when a conversation title is just a phone number (an unknown sender) rather than a name. */
    static boolean isNumber(String title) {
        return title != null && title.matches("[+()0-9 .\\-]{3,}") && title.replaceAll("[^0-9]", "").length() >= 3;
    }

    /** Text as the inbox compares it: whitespace collapsed, invisible characters gone. */
    static String norm(String text) {
        if (text == null) return "";
        return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)
                .replaceAll("[\\u200b-\\u200d\\ufeff]", "").replaceAll("\\s+", " ").trim();
    }
}
