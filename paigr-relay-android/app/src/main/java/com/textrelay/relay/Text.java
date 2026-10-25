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

    /** Shortens to at most max chars without splitting an emoji in half. */
    static String clip(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        int end = max - 1;
        if (end > 0 && Character.isHighSurrogate(s.charAt(end - 1))) end--;
        return s.substring(0, end) + "…";
    }
}
