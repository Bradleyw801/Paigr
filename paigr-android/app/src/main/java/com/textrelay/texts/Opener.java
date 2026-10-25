package com.textrelay.texts;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Opens what the private inbox sends, exactly as the web page does (web/app.js, "sealing and opening"): everything
 * sealed for a conversation opens only as that conversation's (or one its sealed merge records vouch for), a message
 * only as its direction, and whatever doesn't open is shown as unchecked, never as anyone's words. Of what the server
 * writes in the open, only keys, conversations, directions, flags and where files are stored are used, and a file only
 * when it's the message's own. Safe to use from any thread.
 */
final class Opener {
    static final String UNCHECKED_TEXT = "Couldn't be checked on this phone, so it isn't shown.";
    private static final int OPENED_KEEP = 8000;

    final Link link;
    /** sealed value -> what it opened to and as what, so a sync doesn't open the same thing twice */
    private final Map<String, Object[]> opened = new ConcurrentHashMap<>();
    /** conv -> [records key, aliases] */
    private final Map<String, Object[]> aliasSets = new ConcurrentHashMap<>();

    Opener(Link link) {
        this.link = link;
    }

    /** A sealed value opened with the first of `aads` that fits; null when none does or it's missing. */
    JSONObject openBound(String sealed, List<String> aads) {
        if (sealed == null || sealed.isEmpty()) return null;
        Object[] hit = opened.get(sealed);
        if (hit != null && aads.contains((String) hit[1])) return (JSONObject) hit[0];
        for (String aad : aads) {
            JSONObject v;
            try {
                v = link.open(sealed, aad);
            } catch (GeneralSecurityException | RuntimeException e) {
                continue;
            }
            if (opened.size() > OPENED_KEEP) opened.clear();
            opened.put(sealed, new Object[] { v, aad });
            return v;
        }
        return null;
    }

    private static List<String> map(List<String> convs, java.util.function.Function<String, String> f) {
        List<String> out = new ArrayList<>(convs.size());
        for (String c : convs) out.add(f.apply(c));
        return out;
    }

    /** A conversation and the ones its merge records vouch for (merged into it, or into one of those). */
    List<String> aliasesOf(String conv, JSONArray merged) {
        List<String> records = new ArrayList<>();
        if (merged != null) for (int i = 0; i < merged.length(); i++) if (merged.opt(i) instanceof String) records.add(merged.optString(i));
        String key = String.join(",", records);
        Object[] hit = aliasSets.get(conv);
        if (hit != null && key.equals(hit[0])) {
            @SuppressWarnings("unchecked") List<String> l = (List<String>) hit[1];
            return l;
        }
        List<String> set = new ArrayList<>();
        set.add(conv);
        java.util.Set<String> done = new java.util.HashSet<>();
        for (boolean grew = true; grew; ) {
            grew = false;
            for (String r : records) {
                if (done.contains(r)) continue;
                for (String into : new ArrayList<>(set)) {
                    JSONObject v = openBound(r, Collections.singletonList(Crypto.aadMerge(into)));
                    if (v == null || !into.equals(v.opt("into")) || !(v.opt("from") instanceof String)) continue;
                    done.add(r);
                    String from = v.optString("from");
                    if (!set.contains(from)) {
                        set.add(from);
                        grew = true;
                    }
                    break;
                }
            }
        }
        List<String> list = Collections.unmodifiableList(set);
        aliasSets.put(conv, new Object[] { key, list });
        return list;
    }

    /** A finite number the server or a payload gave, or null. */
    static Double num(JSONObject o, String f) {
        if (o == null) return null;
        Object v = o.opt(f);
        if (!(v instanceof Number)) return null;
        double d = ((Number) v).doubleValue();
        return Double.isNaN(d) || Double.isInfinite(d) ? null : d;
    }

    private static String str(JSONObject o, String f) {
        Object v = o == null ? null : o.opt(f);
        return v instanceof String ? (String) v : "";
    }

    /** JavaScript's String(x || ""): what the page shows for a value that may not be a string. */
    private static String text(JSONObject o, String f) {
        Object v = o == null ? null : o.opt(f);
        if (v == null || v == JSONObject.NULL || Boolean.FALSE.equals(v) || "".equals(v)) return "";
        if (v instanceof Number && ((Number) v).doubleValue() == 0) return "";
        return String.valueOf(v);
    }

    private static boolean truthy(JSONObject o, String f) {
        Object v = o == null ? null : o.opt(f);
        if (v == null || v == JSONObject.NULL) return false;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).doubleValue() != 0 && !Double.isNaN(((Number) v).doubleValue());
        if (v instanceof String) return !((String) v).isEmpty();
        return true;
    }

    static String mediaKind(String mime) {
        String t = mime == null ? "" : mime.toLowerCase(Locale.US);
        if (t.startsWith("image/")) return "Picture";
        if (t.startsWith("video/")) return "Video";
        if (t.startsWith("audio/")) return "Voice message";
        return "Attachment";
    }

    /** A conversation's summary as the list shows it. */
    Model.Conv openConv(JSONObject c) {
        Model.Conv out = new Model.Conv();
        String conv = str(c, "conv");
        List<String> aliases = aliasesOf(conv, c.optJSONArray("merged"));
        String lastDir = "out".equals(c.opt("lastDir")) ? "out" : "in";
        JSONObject last = openBound(str(c, "lastE"), map(aliases, a -> Crypto.aadMessage(a, lastDir)));
        String ne = str(c, "ne");
        JSONObject n = ne.isEmpty() ? null : openBound(ne, map(aliases, Crypto::aadName));
        String fromName = n == null ? "" : text(n, "name");
        String fromLast = last == null ? "" : (!text(last, "name").isEmpty() ? text(last, "name") : text(last, "sender"));
        out.conv = conv;
        // A name the relay phone sealed beats one carried over from the first version's inbox (marked moved).
        boolean nMoved = n != null && truthy(n, "moved");
        boolean lastMoved = last != null && truthy(last, "moved");
        String name = !nMoved && !fromName.isEmpty() ? fromName : !lastMoved && !fromLast.isEmpty() ? fromLast
                : !fromName.isEmpty() ? fromName : !fromLast.isEmpty() ? fromLast : "Unknown";
        out.name = name;
        out.group = (n != null && truthy(n, "group")) || (last != null && truthy(last, "group"));
        Double lts = num(last, "ts");
        Double cts = num(c, "lastTs");
        out.lastTs = last != null && lts != null && lts > 0 ? lts.longValue() : cts != null ? cts.longValue() : 0;
        if (last == null) out.lastText = "";
        else {
            String t = text(last, "text");
            out.lastText = !t.isEmpty() ? t : truthy(last, "media") ? mediaKind(last.optJSONObject("media") != null ? last.optJSONObject("media").optString("mime", "") : "") : "";
        }
        out.lastDir = lastDir;
        out.lastSender = last == null ? "" : text(last, "sender");
        out.canReply = !Boolean.FALSE.equals(c.opt("canReply"));
        Double unread = num(c, "unread");
        out.unread = unread != null && unread > 0 ? (int) Math.floor(unread) : 0;
        out.aliases = aliases;
        out.aliasKey = String.join(",", aliases);
        return out;
    }

    private static final Pattern MEDIA_PATH = Pattern.compile("^/media/v2/([a-f0-9]{32})/([a-z0-9_-]{4,64})/([0-9]{13})-([a-z0-9]{8,32})-([op])\\.enc$");

    /** What a stored file is, from its path (tr2.js fileOf): [tid, conv, aad, stem, kind], or null. */
    static String[] fileOf(String path) {
        Matcher m = MEDIA_PATH.matcher(path == null ? "" : path);
        if (!m.matches()) return null;
        return new String[] { m.group(1), m.group(2), Crypto.aadFile(m.group(2), Long.parseLong(m.group(3)), m.group(4), m.group(5)),
                m.group(3) + "-" + m.group(4), m.group(5) };
    }

    /** Where the inbox stores one of a message's files (inbox2.js mediaPath). */
    static String mediaPath(String tid, String conv, long ts, String fp, String kind) {
        return "/media/v2/" + tid + "/" + conv + "/" + Crypto.pad13(ts) + "-" + fp + "-" + ("preview".equals(kind) || "p".equals(kind) ? "p" : "o") + ".enc";
    }

    static String stemOf(long ts, String fp) {
        return Crypto.pad13(ts) + "-" + fp;
    }

    private static final Pattern FP = Pattern.compile("^[a-z0-9]{1,40}$");

    /**
     * A message as the thread shows it: its text, sender, name, time and attachment details, opened as its
     * conversation's (or an alias's) and its direction's. One that doesn't open is unchecked: no words, no name, no
     * attachment.
     */
    Model.Msg openMessage(JSONObject m, List<String> aliases) {
        Model.Msg out = new Model.Msg();
        List<String> al = aliases != null ? aliases : Collections.singletonList(str(m, "conv"));
        String dir = "out".equals(m.opt("dir")) ? "out" : "in";
        JSONObject p = openBound(str(m, "e"), map(al, c -> Crypto.aadMessage(c, dir)));
        String ne = str(m, "ne");
        JSONObject n = ne.isEmpty() ? null : openBound(ne, map(al, Crypto::aadName));
        out.k = str(m, "k");
        out.conv = str(m, "conv");
        out.dir = dir;
        out.status = str(m, "status");
        Double ts = num(m, "ts");
        out.ts = ts != null ? ts.longValue() : 0;
        out.read = truthy(m, "read");
        out.history = truthy(m, "history");
        out.via = str(m, "via");
        out.src = str(m, "src");
        out.code = str(m, "code");
        String e = str(m, "e");
        // Which message this is, so each shows once: as its payload names it (its own time and keyed fingerprint) when
        // it opens, the same for a copy sealed again or filed again; otherwise its seal, which has one spelling.
        out.seal = e.length() >= 22 ? "e:" + e.substring(e.length() - 22) : "";
        String named = n != null && n.opt("name") instanceof String ? n.optString("name") : "";
        if (p == null) {
            out.text = UNCHECKED_TEXT;
            out.unchecked = true;
            return out;
        }
        out.text = str(p, "text");
        out.sender = str(p, "sender");
        out.name = !named.isEmpty() ? named : str(p, "name");
        out.group = (n != null && truthy(n, "group")) || truthy(p, "group");
        out.moved = truthy(p, "moved");
        Double pts = num(p, "ts");
        if (pts != null && pts > 0) out.ts = pts.longValue();   // the time it was sealed with, not the one it's filed under
        Object fp = p.opt("fp");
        if (fp instanceof String && FP.matcher((String) fp).matches() && pts != null && pts > 0) out.seal = "m:" + dir + ":" + stemOf(pts.longValue(), (String) fp);
        JSONObject about = p.optJSONObject("media");
        if (about != null) {
            Model.Media media = new Model.Media();
            if (about.opt("mime") instanceof String) media.mime = about.optString("mime");
            Double w = num(about, "w"), h = num(about, "h"), bytes = num(about, "bytes");
            if (w != null && w > 0) media.w = w.longValue();
            if (h != null && h > 0) media.h = h.longValue();
            if (bytes != null && bytes > 0) media.bytes = bytes.longValue();
            String stem = fp instanceof String && pts != null ? stemOf(pts.longValue(), (String) fp) : null;
            JSONObject where = m.optJSONObject("media");
            for (String[] f : new String[][] { { "path", "o" }, { "preview", "p" } }) {
                String at = where != null ? text(where, f[0]) : "";
                if (at.isEmpty()) continue;
                String[] file = fileOf(at);
                if (file != null && stem != null && file[3].equals(stem) && file[4].equals(f[1]) && file[0].equals(link.tid) && al.contains(file[1])) {
                    if ("path".equals(f[0])) media.path = at;
                    else media.preview = at;
                } else {
                    media.blocked = true;
                }
            }
            out.media = media;
        }
        return out;
    }

    /** A device's name as the server keeps it, shown only as a short plain name, never as a sentence or an address. */
    static String plainName(Object s) {
        String v = s == null || s == JSONObject.NULL ? "" : String.valueOf(s);
        v = v.replaceAll("[^\\p{L}\\p{N} ()+'_-]", " ").replaceAll("\\s+", " ").trim();
        return v.length() > 32 ? v.substring(0, 32) : v;
    }

    /** The relay phone's status: what it says about its own problems comes sealed, in its own words. */
    Model.Relay openRelay(JSONObject r) {
        if (r == null) return null;
        Model.Relay out = new Model.Relay();
        String sn = str(r, "sn");
        JSONObject said = sn.isEmpty() ? null : openBound(sn, Collections.singletonList("tr2:note"));
        out.note = said == null ? "" : text(said, "note");
        out.model = said == null ? "" : plainName(said.opt("model"));
        Double seen = num(r, "lastSeen");
        out.lastSeen = seen != null ? seen.longValue() : 0;
        Object listening = r.opt("listening");
        out.listening = listening instanceof Boolean ? (Boolean) listening : null;
        Double battery = num(r, "battery");
        out.battery = battery != null ? battery : Double.NaN;
        out.charging = truthy(r, "charging");
        Double queued = num(r, "queued");
        out.queued = queued != null ? queued : Double.NaN;
        Object sms = r.opt("smsFallback");
        out.smsFallback = sms instanceof Boolean ? (Boolean) sms : null;
        JSONObject st = r.optJSONObject("store");
        if (st != null) {
            Model.Store s = new Model.Store();
            s.allowed = truthy(st, "allowed");
            Double scan = num(st, "lastScan");
            s.lastScan = scan != null ? scan.longValue() : 0;
            JSONObject h = st.optJSONObject("history");
            if (h != null) {
                s.historyState = str(h, "state");
                Double d = num(h, "done"), t = num(h, "total"), at = num(h, "at");
                s.historyDone = d != null ? d.longValue() : 0;
                s.historyTotal = t != null ? t.longValue() : 0;
                s.historyAt = at != null ? at.longValue() : 0;
                s.historyMedia = truthy(h, "media");
                s.historyNote = said == null ? "" : text(said, "history");
            }
            out.store = s;
        }
        return out;
    }

    private static final Pattern DEVICE_ID = Pattern.compile("^[a-f0-9]{12}$");

    /** A listed device, with its name as that device sealed it for its own id; "" for anything else. */
    Model.Device openDevice(JSONObject d) {
        Model.Device out = new Model.Device();
        out.id = str(d, "id");
        out.role = str(d, "role");
        Double at = num(d, "at");
        out.at = at != null ? at.longValue() : 0;
        out.you = Boolean.TRUE.equals(d.opt("you"));
        String sn = str(d, "sn");
        if (!sn.isEmpty() && DEVICE_ID.matcher(out.id).matches()) {
            JSONObject said = openBound(sn, Collections.singletonList("tr2:dev:" + out.id));
            out.name = said == null ? "" : plainName(said.opt("name"));
        }
        return out;
    }

    /** A device's id as the inbox lists it: the first 12 hex digits of the SHA-256 of its token. */
    static String deviceId(String token) {
        try {
            return Crypto.hex(java.security.MessageDigest.getInstance("SHA-256").digest(Crypto.utf8(token))).substring(0, 12);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    void forget() {
        opened.clear();
        aliasSets.clear();
    }
}
