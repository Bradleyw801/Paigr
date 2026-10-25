package com.textrelay.texts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** What the screens show, as it opened on this phone (see Opener): conversations, messages, the relay phone, devices. */
final class Model {
    private Model() {}

    static final class Conv {
        String conv = "";
        String name = "Unknown";
        boolean group;
        long lastTs;
        String lastText = "";
        String lastDir = "in";
        String lastSender = "";
        boolean canReply = true;
        int unread;
        List<String> aliases = Collections.emptyList();
        String aliasKey = "";

        Conv copy() {
            Conv c = new Conv();
            c.conv = conv;
            c.name = name;
            c.group = group;
            c.lastTs = lastTs;
            c.lastText = lastText;
            c.lastDir = lastDir;
            c.lastSender = lastSender;
            c.canReply = canReply;
            c.unread = unread;
            c.aliases = aliases;
            c.aliasKey = aliasKey;
            return c;
        }

        /** The fields a summary from the inbox can change (the page's SUMMARY_FIELDS). */
        boolean sameSummary(Conv o) {
            return o != null && name.equals(o.name) && group == o.group && lastTs == o.lastTs && lastText.equals(o.lastText)
                    && lastDir.equals(o.lastDir) && lastSender.equals(o.lastSender) && unread == o.unread && canReply == o.canReply
                    && aliasKey.equals(o.aliasKey);
        }
    }

    static final class Media {
        String mime = "";
        long w, h, bytes;
        /** Where the original and the preview are stored, only when they're this message's own files. */
        String path, preview;
        /** The server named a file that isn't this message's: nothing of it is shown. */
        boolean blocked;
        /** A file on this phone, by its uri: what's being sent, shown before (and after) it's stored. */
        String localUri;

        boolean same(Media o) {
            return o != null && mime.equals(o.mime) && w == o.w && h == o.h && bytes == o.bytes && Objects.equals(path, o.path)
                    && Objects.equals(preview, o.preview) && blocked == o.blocked;
        }

        /** "Picture", "Video", "Voice message" or "Attachment". */
        String kind() {
            return Opener.mediaKind(mime);
        }
    }

    static final class Msg {
        String k = "";
        String conv = "";
        String dir = "in";
        String status = "";
        long ts;
        boolean read, history;
        String via = "", src = "", code = "";
        /** Which message this is, so each shows once (see Opener.openMessage). */
        String seal = "";
        boolean unchecked;
        String text = "", sender = "", name = "";
        boolean group, moved;
        Media media;
        /** Written here and not yet handed to the inbox. */
        boolean temp;

        boolean out() {
            return "out".equals(dir);
        }

        boolean same(Msg o) {
            return o != null && k.equals(o.k) && conv.equals(o.conv) && dir.equals(o.dir) && status.equals(o.status) && ts == o.ts
                    && read == o.read && history == o.history && via.equals(o.via) && src.equals(o.src) && code.equals(o.code)
                    && seal.equals(o.seal) && unchecked == o.unchecked && text.equals(o.text) && sender.equals(o.sender)
                    && name.equals(o.name) && group == o.group && moved == o.moved && temp == o.temp
                    && (media == null ? o.media == null : media.same(o.media));
        }
    }

    /** What the relay phone says about its message store and the full-history sync. */
    static final class Store {
        boolean allowed;
        long lastScan;
        String historyState = "";
        long historyDone, historyTotal, historyAt;
        boolean historyMedia;
        String historyNote = "";
    }

    static final class Relay {
        long lastSeen;
        Boolean listening;
        double battery = Double.NaN;
        boolean charging;
        double queued = Double.NaN;
        Boolean smsFallback;
        /** In its own words, sealed by it: nothing the server wrote. */
        String note = "";
        String model = "";
        Store store;
    }

    static final class Device {
        String id = "";
        String role = "";
        long at;
        boolean you;
        /** As that device sealed it for itself; "" for anything else. */
        String name = "";
    }

    /** A thread's messages, in the order they were sealed, each sealed message once (the page's tidy()). */
    static List<Msg> tidy(List<Msg> arr) {
        java.util.Map<String, Integer> at = new java.util.HashMap<>();
        List<Msg> out = new ArrayList<>();
        for (Msg m : arr) {
            if (m == null) continue;
            if (!m.seal.isEmpty() && at.containsKey(m.seal)) {
                int i = at.get(m.seal);
                if (out.get(i).unchecked && !m.unchecked) out.set(i, m);   // the copy that opens as this conversation's is shown
                continue;
            }
            if (!m.seal.isEmpty()) at.put(m.seal, out.size());
            out.add(m);
        }
        out.sort(BY_TIME);
        return out;
    }

    static final java.util.Comparator<Msg> BY_TIME = (a, b) -> {
        int t = Long.compare(a.ts, b.ts);
        return t != 0 ? t : a.k.compareTo(b.k);
    };

    /** Messages from the same side, and the same sender, within a few minutes, drawn as one run. */
    static boolean sameRun(Msg a, Msg b) {
        return a != null && b != null && a.dir.equals(b.dir) && a.sender.equals(b.sender) && Math.abs(a.ts - b.ts) < 3 * 60_000L;
    }
}
