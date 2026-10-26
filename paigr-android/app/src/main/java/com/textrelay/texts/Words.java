package com.textrelay.texts;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reactions and quoted replies, as texts carry them (web/tr2.js `words` is the same, and test/vectors.json holds what
 * both must say). Messages has no door for another app to send a reaction or a quoted reply, so both travel as ordinary
 * text, in the forms phones already understand: a reaction the way an iPhone writes one as a text (Loved “hi”, Reacted
 * 😮 to “hi”), which Messages shows as a reaction, and a quoted reply as a line starting "> " above the answer, which
 * reads as a quote anywhere. Every device parses them back out of the text to draw them.
 */
final class Words {
    private Words() {}

    static final String HEART = "❤️", THUMBS_UP = "👍", THUMBS_DOWN = "👎", LAUGH = "😂",
            EXCLAIM = "‼️", QUESTION = "❓";

    private static final Map<String, String> REACT_WORDS = new LinkedHashMap<>();
    private static final Map<String, String> REMOVE_WORDS = new LinkedHashMap<>();
    private static final Map<String, String> KIND_WORDS = new LinkedHashMap<>();
    /** How the apps say an attachment's kind ("👍 to a picture"), by reaction()'s kind. */
    static final Map<String, String> KIND_SAID = new LinkedHashMap<>();
    private static final Map<String, String> KIND_SENT = new LinkedHashMap<>();
    static {
        REACT_WORDS.put("Loved", HEART);
        REACT_WORDS.put("Liked", THUMBS_UP);
        REACT_WORDS.put("Disliked", THUMBS_DOWN);
        REACT_WORDS.put("Laughed at", LAUGH);
        REACT_WORDS.put("Emphasized", EXCLAIM);
        REACT_WORDS.put("Questioned", QUESTION);
        REMOVE_WORDS.put("a heart", HEART);
        REMOVE_WORDS.put("a like", THUMBS_UP);
        REMOVE_WORDS.put("a dislike", THUMBS_DOWN);
        REMOVE_WORDS.put("a laugh", LAUGH);
        REMOVE_WORDS.put("an exclamation", EXCLAIM);
        REMOVE_WORDS.put("a question mark", QUESTION);
        KIND_WORDS.put("an image", "image");
        KIND_WORDS.put("a sticker", "image");
        KIND_WORDS.put("a video", "video");
        KIND_WORDS.put("an audio message", "audio");
        KIND_WORDS.put("an attachment", "file");
        KIND_WORDS.put("a location", "file");
        KIND_WORDS.put("a contact", "file");
        KIND_WORDS.put("a link", "link");
        KIND_SAID.put("image", "a picture");
        KIND_SAID.put("video", "a video");
        KIND_SAID.put("audio", "a voice message");
        KIND_SAID.put("file", "an attachment");
        KIND_SAID.put("link", "a link");
        KIND_SENT.put("image", "an image");
        KIND_SENT.put("video", "a video");
        KIND_SENT.put("audio", "an audio message");
        KIND_SENT.put("file", "an attachment");
        KIND_SENT.put("link", "a link");
    }

    private static final String EMOJI = "[^\\p{L}\\p{N}\\s\"“”]{1,12}";
    /** The opening and closing quotes phones put round the words (straight, curly, low-9, guillemets, single). */
    private static final String OPEN = "[“\"„«‘']", CLOSE = "[”\"“»’']";
    /** What a reaction names: quoted words, or a kind of attachment. */
    private static final String WHAT = "(?:" + OPEN + "([\\s\\S]+?)" + CLOSE + "|(" + String.join("|", KIND_WORDS.keySet()) + "))";
    /** The same, as Google writes it: also "your message", and words with no quotes round them. */
    private static final String WHAT_G = "(?:" + OPEN + "([\\s\\S]+?)" + CLOSE + "|(" + String.join("|", KIND_WORDS.keySet()) + ")|(your message)|([\\s\\S]+?))";
    private static final Pattern[] REACTION = {
            Pattern.compile("^(Loved|Liked|Disliked|Laughed at|Emphasized|Questioned) " + WHAT + "\\.?$"),
            Pattern.compile("^(?:[\\s\\S]{1,60}? )?[Rr]eacted (?:with )?(" + EMOJI + ") to " + WHAT_G + "\\.?$"),
            Pattern.compile("^Removed (a heart|a like|a dislike|a laugh|an exclamation|a question mark|" + EMOJI + ") from " + WHAT + "\\.?$"),
            Pattern.compile("^(" + EMOJI + ") to " + WHAT_G + "\\.?$")
    };
    private static final Pattern QUOTE_LINE = Pattern.compile("^>( |$)");
    private static final Pattern CUT = Pattern.compile("(\\.\\.\\.|…)$");

    /** A text that is a reaction: the emoji, what it's to (the quoted text, or the kind of attachment), and whether it takes one back. */
    static final class Reaction {
        final String emoji;
        /** The quoted words, or null when it names an attachment instead. */
        final String to;
        /** "image", "video", "audio", "file" or "link", or null when it quotes words. */
        final String kind;
        final boolean removed;

        Reaction(String emoji, String to, String kind, boolean removed) {
            this.emoji = emoji;
            this.to = to;
            this.kind = kind;
            this.removed = removed;
        }

        /** What it's to, in a few words. */
        String what() {
            return to != null ? "“" + to + "”" : "own".equals(kind) ? "your message" : KIND_SAID.containsKey(kind) ? KIND_SAID.get(kind) : "an attachment";
        }
    }

    /**
     * The forms read: Apple's (Loved “hi”, Reacted 😮 to “hi”, Removed a heart from “hi”, Liked an image) and Google's
     * (👍 to “hi”; Reacted 👍 to “hi”, with or without the quotes, a name in front, or "your message" in place of the
     * words). An emoji is anything that isn't letters, digits or quotes, but a form that starts with one wants a real
     * symbol in it, so a line like "- to do" isn't a reaction. `kind` is "own" for "your message": the reader's own
     * newest message.
     */
    static Reaction reaction(String text) {
        String s = text == null ? "" : text.trim();
        for (int i = 0; i < REACTION.length; i++) {
            Matcher m = REACTION[i].matcher(s);
            if (!m.matches()) continue;
            String first = m.group(1);
            String emoji = i == 0 ? REACT_WORDS.get(first) : i == 2 && REMOVE_WORDS.containsKey(first) ? REMOVE_WORDS.get(first) : first;
            if (emoji == null || (i != 0 && !(i == 2 && REMOVE_WORDS.containsKey(first)) && !symbol(emoji))) continue;
            String to = m.group(2);
            String kind = m.group(3) != null ? KIND_WORDS.get(m.group(3)) : null;
            if (i == 1 || i == 3) {
                if (m.group(4) != null) kind = "own";
                else if (m.group(5) != null) to = m.group(5).trim();
            }
            if (to == null && kind == null) continue;
            return new Reaction(emoji, to, kind, i == 2);
        }
        return null;
    }

    /** Whether there is a symbol (an emoji, not punctuation such as a dash) in what matched as one. */
    private static boolean symbol(String emoji) {
        return emoji.codePoints().anyMatch(cp -> Character.getType(cp) == Character.OTHER_SYMBOL || cp == 0x203C || cp == 0x2049);
    }

    /** The text that reacts with `emoji` to a message: its words, or the kind of attachment it is (reaction()'s kinds). */
    static String reactionText(String emoji, String to, String kind, boolean removed) {
        String word = null;
        for (Map.Entry<String, String> e : REACT_WORDS.entrySet()) if (e.getValue().equals(emoji)) word = e.getKey();
        String what = to != null && !to.trim().isEmpty() ? "“" + to.trim() + "”" : KIND_SENT.containsKey(kind) ? KIND_SENT.get(kind) : "an attachment";
        if (removed) {
            String undo = null;
            for (Map.Entry<String, String> e : REMOVE_WORDS.entrySet()) if (e.getValue().equals(emoji)) undo = e.getKey();
            return "Removed " + (undo != null ? undo : emoji) + " from " + what;
        }
        return word != null ? word + " " + what : "Reacted " + emoji + " to " + what;
    }

    /** A text that quotes another and answers it. */
    static final class Quote {
        final String quoted;
        final String body;

        Quote(String quoted, String body) {
            this.quoted = quoted;
            this.body = body;
        }
    }

    static Quote quote(String text) {
        String[] lines = (text == null ? "" : text).split("\n", -1);
        StringBuilder q = new StringBuilder();
        int i = 0;
        while (i < lines.length && QUOTE_LINE.matcher(lines[i]).find()) {
            String l = lines[i].length() > 2 ? lines[i].substring(2).trim() : "";
            if (q.length() > 0 && !l.isEmpty()) q.append(' ');
            q.append(l);
            i++;
        }
        if (i == 0 || i >= lines.length) return null;
        StringBuilder body = new StringBuilder();
        for (int j = i; j < lines.length; j++) {
            if (j > i) body.append('\n');
            body.append(lines[j]);
        }
        String b = body.toString().trim();
        if (b.isEmpty()) return null;
        return new Quote(q.toString().trim(), b);
    }

    /** What `reply` looks like as an answer to a message that said `original` (or was `kind` of attachment, text-free). */
    static String quoteText(String original, String kind, String reply) {
        String line = Crypto.normText(original);
        if (line.isEmpty()) {
            String said = KIND_SAID.get(kind);
            line = said != null ? Character.toUpperCase(said.charAt(said.indexOf(' ') + 1)) + said.substring(said.indexOf(' ') + 2) : "Attachment";
        }
        if (line.length() > 80) line = line.substring(0, 79).trim() + "…";
        return "> " + line + "\n" + (reply == null ? "" : reply.trim());
    }

    private static final Pattern TRAILING = Pattern.compile("[.!?,;:\\s]+$");

    private static String even(String s) {
        return Crypto.normText(s).replace("“", "").replace("”", "").replace("\"", "").toLowerCase(Locale.ROOT);
    }

    /**
     * Whether a reaction's or a quote's words name a message that said `b`: spacing and quotes evened out, the end's
     * punctuation too (a phone quoting "ok." may write "ok"), and a cut allowed (a quote cut short, ending in …).
     */
    static boolean sameWords(String a, String b) {
        String x = even(a), y = even(b);
        if (x.isEmpty() || y.isEmpty()) return false;
        if (x.equals(y)) return true;
        String cut = CUT.matcher(x).replaceFirst("").trim();
        if (cut.length() >= 12 && !cut.equals(x) && y.startsWith(cut)) return true;
        String xs = TRAILING.matcher(x).replaceFirst(""), ys = TRAILING.matcher(y).replaceFirst("");
        return !xs.isEmpty() && xs.equals(ys);
    }

    /** A text as a list or a notification shows it: a reaction in a few words, a quoted reply by its answer. */
    static String snippet(String text) {
        Reaction r = reaction(text);
        if (r != null) return (r.removed ? "Removed " + r.emoji + " from " : r.emoji + " to ") + r.what();
        Quote q = quote(text);
        return q != null ? q.body : text == null ? "" : text;
    }

    /** Whether `s` is one emoji (or a few), as a reaction chip shows. */
    static boolean isEmoji(String s) {
        return s != null && !s.isEmpty() && s.matches(EMOJI);
    }

    /** The reaction kind of a message with this attachment, for reacting to one with no words. */
    static String kindOf(Model.Media media) {
        if (media == null) return null;
        String m = media.mime == null ? "" : media.mime.toLowerCase(Locale.US);
        if (m.startsWith("image/")) return "image";
        if (m.startsWith("video/")) return "video";
        if (m.startsWith("audio/")) return "audio";
        return "file";
    }
}
