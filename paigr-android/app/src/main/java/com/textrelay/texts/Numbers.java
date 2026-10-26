package com.textrelay.texts;

/**
 * Phone numbers as people type them, for starting a conversation. The relay phone keys a started conversation from
 * the number alone (PROTOCOL.md, New conversations), so every device writes it the same way: the digits, with a
 * leading + kept. A number without a country code is sent as typed; the relay phone's SIM dials it as a local number.
 */
final class Numbers {
    private Numbers() {}

    /** The number as it goes to the relay phone, or null when what was typed isn't one (3 to 20 digits). */
    static String canonical(String typed) {
        if (typed == null) return null;
        String t = typed.trim();
        if (t.isEmpty()) return null;
        boolean plus = t.startsWith("+");
        String digits = t.replaceAll("[^0-9]", "");
        // What people also type around a number: spaces, dashes, dots, brackets, and a leading + or 00.
        if (!t.replaceAll("[0-9 .()\\-+]", "").isEmpty()) return null;
        if (!plus && digits.startsWith("00") && digits.length() > 10) {
            plus = true;
            digits = digits.substring(2);
        }
        if (digits.length() < 3 || digits.length() > 20) return null;
        return (plus ? "+" : "") + digits;
    }

    /** A number as people write it: (801) 555-0123 for a North American number, otherwise as it is. */
    static String pretty(String number) {
        if (number == null) return "";
        String d = number.replaceAll("[^0-9]", "");
        if (d.length() == 11 && d.startsWith("1")) d = d.substring(1);
        if (d.length() == 10) return "(" + d.substring(0, 3) + ") " + d.substring(3, 6) + "-" + d.substring(6);
        return number;
    }

    /** The part of a number that identifies a person: its last ten digits for a full-length number, all of them for a short code. */
    static String key(String number) {
        if (number == null) return "";
        String d = number.replaceAll("[^0-9]", "");
        return d.length() >= 10 ? d.substring(d.length() - 10) : d;
    }

    /** Whether a conversation's name is just a number (someone not in the relay phone's contacts). */
    static boolean isNumber(String name) {
        return name != null && name.matches("[+()0-9 .\\-]{3,}") && name.replaceAll("[^0-9]", "").length() >= 3;
    }
}
