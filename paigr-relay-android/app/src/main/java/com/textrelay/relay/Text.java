package com.textrelay.relay;

/** Small string helpers for the status screen. */
final class Text {
    private Text() {}

    static String ago(long timestamp, long now) {
        if (timestamp <= 0) return "never";
        long s = Math.max(0, (now - timestamp) / 1000);
        if (s < 60) return "just now";
        if (s < 3600) return (s / 60) + " min ago";
        if (s < 86400) return (s / 3600) + " h ago";
        return (s / 86400) + " d ago";
    }

    /** A size in plain units: "300 KB", "1.2 MB". */
    static String bytes(long n) {
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return Math.round(n / 1024.0) + " KB";
        return String.format(java.util.Locale.US, "%.1f MB", n / 1048576.0);
    }

    /** Shortens to at most max chars without splitting an emoji in half. */
    static String clip(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        int end = max - 1;
        if (end > 0 && Character.isHighSurrogate(s.charAt(end - 1))) end--;
        return s.substring(0, end) + "…";
    }
}
