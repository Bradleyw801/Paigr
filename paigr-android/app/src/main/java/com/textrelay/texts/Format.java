package com.textrelay.texts;

import android.content.Context;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Times, days and sizes, as the screens write them (the phone's 12 or 24 hour setting included). Rows ask for these
 * while a list scrolls, so nothing here makes a formatter or a calendar per call: the formatters are kept, the day's
 * bounds are worked out once a day, and each day's label once.
 */
final class Format {
    private Format() {}

    private static final long DAY_MS = 86_400_000L;
    private static final Object LOCK = new Object();
    private static DateFormat time;
    private static long timeAt;
    private static SimpleDateFormat weekday, thisYear, otherYear;
    private static Locale forLocale;
    /** Midnight at the start of today and of yesterday, and of tomorrow (when the day moves on), local time. */
    private static long today, yesterday, tomorrow;
    private static final Map<Long, String> dayLabels = new HashMap<>();
    private static final Date scratch = new Date();

    /** Reads the phone's time setting again (called when the app comes to the front; it also refreshes itself). */
    static void refresh(Context c) {
        synchronized (LOCK) {
            time = android.text.format.DateFormat.getTimeFormat(c);
            timeAt = System.currentTimeMillis();
        }
    }

    private static void ready(Context c) {
        long now = System.currentTimeMillis();
        if (time == null || now - timeAt > 10 * 60_000L) {
            time = android.text.format.DateFormat.getTimeFormat(c);
            timeAt = now;
        }
    }

    private static void readyDays() {
        long now = System.currentTimeMillis();
        Locale l = Locale.getDefault();
        if (today != 0 && now >= today && now < tomorrow && l.equals(forLocale)) return;
        Calendar d = Calendar.getInstance();
        d.set(Calendar.HOUR_OF_DAY, 0);
        d.set(Calendar.MINUTE, 0);
        d.set(Calendar.SECOND, 0);
        d.set(Calendar.MILLISECOND, 0);
        today = d.getTimeInMillis();
        d.add(Calendar.DAY_OF_YEAR, 1);
        tomorrow = d.getTimeInMillis();
        d.add(Calendar.DAY_OF_YEAR, -2);
        yesterday = d.getTimeInMillis();
        if (!l.equals(forLocale)) {
            weekday = new SimpleDateFormat("EEE", l);
            thisYear = new SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(l, "EEE MMM d"), l);
            otherYear = new SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(l, "EEE MMM d yyyy"), l);
            forLocale = l;
        }
        dayLabels.clear();
    }

    static String clock(Context c, long ts) {
        if (ts <= 0) return "";
        synchronized (LOCK) {
            ready(c);
            scratch.setTime(ts);
            return time.format(scratch);
        }
    }

    /** The local day a time falls in, as a number (so two times on one day share it). */
    private static long dayOf(long ts) {
        return Math.floorDiv(ts + TimeZone.getDefault().getOffset(ts), DAY_MS);
    }

    /** The list's time: the time today, the weekday this week, the date before that. */
    static String listTime(Context c, long ts) {
        if (ts <= 0) return "";
        synchronized (LOCK) {
            readyDays();
            if (ts >= today && ts < tomorrow) return clock(c, ts);
            if (ts < today && ts >= today - 5 * DAY_MS) {
                scratch.setTime(ts);
                return weekday.format(scratch);
            }
            return label(ts, true);
        }
    }

    /** "Today", "Yesterday", or the weekday and date. */
    static String day(long ts) {
        synchronized (LOCK) {
            readyDays();
            if (ts >= today && ts < tomorrow) return "Today";
            if (ts >= yesterday && ts < today) return "Yesterday";
            return label(ts, false);
        }
    }

    private static String label(long ts, boolean dateOnly) {
        long key = dayOf(ts) * 2 + (dateOnly ? 1 : 0);
        String s = dayLabels.get(key);
        if (s != null) return s;
        scratch.setTime(ts);
        Calendar d = Calendar.getInstance();
        d.setTimeInMillis(ts);
        boolean sameYear = d.get(Calendar.YEAR) == Calendar.getInstance().get(Calendar.YEAR);
        if (dateOnly) {
            Locale l = Locale.getDefault();
            s = new SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(l, sameYear ? "MMM d" : "MMM d yyyy"), l).format(scratch);
        } else {
            s = (sameYear ? thisYear : otherYear).format(scratch);
        }
        if (dayLabels.size() > 400) dayLabels.clear();
        dayLabels.put(key, s);
        return s;
    }

    static String ago(long ts, long now) {
        if (ts <= 0) return "never";
        long s = Math.max(0, (now - ts) / 1000);
        if (s < 50) return "just now";
        if (s < 3600) return Math.max(1, Math.round(s / 60.0)) + " min ago";
        if (s < 86400) return Math.round(s / 3600.0) + " h ago";
        return Math.round(s / 86400.0) + " d ago";
    }

    static String bytes(long n) {
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return Math.round(n / 1024.0) + " KB";
        return String.format(Locale.US, "%.1f MB", n / 1048576.0);
    }

    static String count(long n) {
        return java.text.NumberFormat.getIntegerInstance().format(n);
    }

    /** Tests: forgets what's cached, so a changed clock or locale shows. */
    static void resetForTests() {
        synchronized (LOCK) {
            time = null;
            today = 0;
            forLocale = null;
            dayLabels.clear();
        }
    }
}
