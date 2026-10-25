package com.textrelay.relay;

import android.content.Context;
import android.content.SharedPreferences;

/** Everything Relay remembers, in one private preferences file. */
final class Prefs {
    private static final int SEEN_LIMIT = 200;   // message fingerprints kept per conversation

    private final SharedPreferences sp;
    private final Object lock = new Object();
    private final String defaultServer;
    private final Context app;

    Prefs(Context context) {
        app = context.getApplicationContext();
        sp = app.getSharedPreferences("relay", Context.MODE_PRIVATE);
        defaultServer = app.getString(R.string.default_server);
        if (!sp.contains("install_ts")) {
            sp.edit().putLong("install_ts", System.currentTimeMillis()).apply();
        }
        // A link stored by a build from before these were kept: it counts as linked, from now, on this phone. The first
        // version's key (kept until this phone is in a private inbox) belongs to this phone the same way.
        if (sp.contains("link2") && (!sp.getBoolean("ever_linked", false) || sp.getLong("link2_at", 0) == 0 || !sp.contains("here2"))) {
            SharedPreferences.Editor e = sp.edit().putBoolean("ever_linked", true);
            if (sp.getLong("link2_at", 0) == 0) e.putLong("link2_at", System.currentTimeMillis()).putLong("link2_grace", 0);
            if (!sp.contains("here2")) e.putString("here2", here());
            e.commit();
        } else if (!sp.getString("key", "").isEmpty() && !sp.contains("here2")) {
            sp.edit().putString("here2", here()).commit();
        }
        // Spent reply requests kept by a build from before it noted whose they were: they're the stored link's inbox's.
        if (sp.contains("link2") && !sp.contains("nonces_tid")) {
            Link l = Link.parse(sp.getString("link2", null));
            if (l != null) sp.edit().putString("nonces_tid", l.tid).commit();
        }
    }

    private volatile String hereFor;
    private volatile String hereHash;

    /**
     * This phone, as the stored link knows it: a hash of the Android ID the app has here (Android gives the app another
     * one on every other phone). Empty when the phone has none worth trusting.
     */
    private String here() {
        String id = Relay.androidIdForTests != null ? Relay.androidIdForTests : Pairing.androidId(app);
        if (id == null) return "";
        if (!id.equals(hereFor)) {
            hereHash = Pairing.sha256Hex("textrelay v2 here:" + id);
            hereFor = id;
        }
        return hereHash;
    }

    /**
     * Whether the stored link was made on another phone and came here with a copy of the app's data (backups leave it
     * out, but a rooted phone can copy anything). Such a link is dropped where it's read, before anything uses it.
     */
    private boolean copiedHere() {
        String stored = sp.getString("here2", "");
        String now = here();
        return !stored.isEmpty() && !now.isEmpty() && !stored.equals(now);
    }

    /** Texts older than this were already on the phone before Relay and are never relayed. */
    long installTs() {
        return sp.getLong("install_ts", 0);
    }

    // ---- server ----

    String server() {
        return sp.getString("server", defaultServer);
    }

    /** The first version's device key; one copied here from another phone (see link()) goes. */
    String key() {
        String k = sp.getString("key", "");
        if (!k.isEmpty() && copiedHere()) {
            sp.edit().remove("key").remove("verified").putBoolean("link_copied", true).commit();
            return "";
        }
        return k;
    }

    /** Relaying somewhere: a private inbox (v2), or the first version's inbox with its device key. */
    boolean configured() {
        return link() != null || (!key().isEmpty() && !server().isEmpty());
    }

    // ---- the private inbox (v2) ----

    private volatile String linkRaw;
    private volatile Link linkParsed;

    /** This phone's place in a private inbox, or null when it has none (yet). A link copied from another phone goes. */
    Link link() {
        String raw = sp.getString("link2", null);
        if (raw == null) return null;
        if (copiedHere()) {
            synchronized (lock) {
                if (sp.getString("link2", null) != null && copiedHere()) {
                    sp.edit().remove("link2").remove("link2_at").remove("link2_grace").remove("wrap2").remove("verified")
                            .putBoolean("link_copied", true).commit();
                }
            }
            return null;
        }
        if (!raw.equals(linkRaw)) {
            linkParsed = Link.parse(raw);
            linkRaw = raw;
        }
        return linkParsed;
    }

    /**
     * Joins a private inbox. The first version's key goes: from now on everything goes to the private inbox. `recovered`
     * when it's this phone's own link back after a reinstall: nothing made before that moment is sent (see spendRequest).
     */
    void setLink(Link link, boolean recovered) {
        SharedPreferences.Editor e = sp.edit().putString("link2", link.toJson()).putString("here2", here()).putLong("link2_at", System.currentTimeMillis())
                .putLong("link2_grace", recovered ? 0 : 2 * 60_000L).putBoolean("ever_linked", true).remove("link_copied")
                .remove("key").remove("wrap2").putBoolean("verified", false);
        // Another inbox's requests can't open with this one's key: what was remembered about them (and the floor that
        // came from them) belongs to that inbox, so a new one starts clean. Spent requests no inbox is noted for (kept by
        // an older build) stay: they may be this one's.
        String whose = sp.getString("nonces_tid", null);
        if (whose != null && !link.tid.equals(whose)) e.remove("nonces2").remove("nonce_floor").remove("unopened2").remove("unopened_overflow_at");
        e.putString("nonces_tid", link.tid).commit();
    }

    /** True once, after a link that came from another phone was dropped (Relay says so). */
    boolean takeLinkCopied() {
        if (!sp.getBoolean("link_copied", false)) return false;
        sp.edit().remove("link_copied").commit();
        return true;
    }

    void setLink(Link link) {
        setLink(link, false);
    }

    void clearLink() {
        sp.edit().remove("link2").remove("link2_at").remove("link2_grace").remove("wrap2").remove("verified").commit();
    }

    /** When this phone joined (or got back into) its private inbox; 0 when it isn't in one. */
    long linkedAt() {
        return sp.getLong("link2_at", 0);
    }

    /**
     * Whether this install has ever been in a private inbox. Only an install that never was gets back in by itself
     * after a reinstall: one that left an inbox (or was told it isn't in one) waits for a code, so nobody can put it back
     * into an inbox it left.
     */
    boolean everLinked() {
        return sp.getBoolean("ever_linked", false);
    }

    // ---- replies the private inbox asked this phone to send ----

    /**
     * How long a reply request is good for: a reply that waited longer for this phone (it was off, or the server held
     * it back) is reported failed, not sent late. The nonces of those acted on are kept a day longer.
     */
    static final long REQUEST_MAX_AGE_MS = 2 * 86_400_000L;
    private static final long NONCE_KEEP_MS = REQUEST_MAX_AGE_MS + 86_400_000L;
    /** How many acted-on requests are remembered at most (tests make it small). */
    static volatile int nonceLimit = 20000;

    private org.json.JSONObject nonces() {
        try {
            return new org.json.JSONObject(sp.getString("nonces2", "{}"));
        } catch (org.json.JSONException e) {
            return new org.json.JSONObject();
        }
    }

    /** What became of a request this phone already acted on (its report), or null when it hasn't. */
    org.json.JSONObject spentRequest(String nonce) {
        synchronized (lock) {
            org.json.JSONObject e = nonces().optJSONObject(nonce);
            return e != null ? e.optJSONObject("r") : null;
        }
    }

    /**
     * Whether to act on a reply request made at `made` (the sending device's clock) with this nonce: null when it may
     * go, and then the nonce is spent, with `placeholder` as its outcome until recordRequest gives the real one;
     * otherwise why not (a NotSent code). A request older than two days, dated more than a day ahead, from before this
     * phone joined its inbox (with two minutes' grace for the clocks, none after a reinstall), or made no later than
     * the floor (below), is never sent; `refuse` refuses it for another reason (NotSent.UNKNOWN_CONV, UNCHECKED). A
     * refused request is spent too, with its refusal as its outcome: it can't be sent later, once a clock catches up or
     * its conversation turns up.
     *
     * A request is remembered until it could never pass again: made more than three days ago (this phone's clock), so
     * the age check refuses it, and spent more than three days ago. Forgetting one raises the floor to when it was made;
     * so a forgotten request never passes again, whatever this phone's clock does, and the floor stays days in the past
     * (a request dated far ahead is simply remembered until then). Past nonceLimit, the earliest made go first.
     */
    String spendRequest(String nonce, long made, long now, org.json.JSONObject placeholder, String refuse) {
        return spendRequest(nonce, made, now, placeholder, refuse, 0);
    }

    /**
     * The same, for a request in a conversation this phone has known since `since` (0: since before it kept that): one
     * made more than two minutes before then is refused (NotSent.UNKNOWN_CONV) once the memory of requests that opened
     * nowhere has overflowed, since it may have been one of those, reported failed back then.
     */
    String spendRequest(String nonce, long made, long now, org.json.JSONObject placeholder, String refuse, long since) {
        synchronized (lock) {
            org.json.JSONObject all = nonces();
            if (all.has(nonce)) return NotSent.AGAIN;
            long joined = linkedAt();
            long grace = sp.getLong("link2_grace", 2 * 60_000L);
            long floor = sp.getLong("nonce_floor", 0);
            String why = refuse;
            if (why == null && (made < now - REQUEST_MAX_AGE_MS || (joined > 0 && made < joined - grace) || made <= floor)) why = NotSent.OLD;
            if (why == null && made > now + 86_400_000L) why = NotSent.FUTURE;
            // Only once the memory of requests that opened nowhere has had to forget one early (only a flood of them
            // does that): until then every such request is remembered by its tag, and phones' clocks needn't agree.
            if (why == null && since > 0 && made < since - Hedy2.KNOWN_GRACE_MS && sp.getLong("unopened_overflow_at", 0) >= now - UNOPENED_KEEP_MS) {
                why = NotSent.UNKNOWN_CONV;
            }
            java.util.List<String> kept = new java.util.ArrayList<>();
            for (java.util.Iterator<String> it = all.keys(); it.hasNext(); ) {
                String k = it.next();
                org.json.JSONObject e = all.optJSONObject(k);
                if (e == null) continue;
                long eMade = madeOf(e);
                if (e.optLong("at") < now - NONCE_KEEP_MS && eMade < now - NONCE_KEEP_MS) floor = Math.max(floor, eMade);
                else kept.add(k);
            }
            final org.json.JSONObject source = all;
            kept.sort((a, b) -> Long.compare(madeOf(source.optJSONObject(a)), madeOf(source.optJSONObject(b))));
            int drop = Math.max(0, kept.size() - nonceLimit + 1);
            org.json.JSONObject next = new org.json.JSONObject();
            for (int i = 0; i < kept.size(); i++) {
                org.json.JSONObject e = all.optJSONObject(kept.get(i));
                if (i < drop) floor = Math.max(floor, madeOf(e));   // forgotten to make room: the earliest made go first
                else put(next, kept.get(i), e);
            }
            org.json.JSONObject outcome = why == null ? placeholder : Json.obj("status", "failed", "code", why, "error", NotSent.words(why));
            put(next, nonce, Json.obj("at", now, "made", made, "r", outcome));
            sp.edit().putString("nonces2", next.toString()).putLong("nonce_floor", floor).commit();
            return why;
        }
    }

    String spendRequest(String nonce, long made, long now, org.json.JSONObject placeholder) {
        return spendRequest(nonce, made, now, placeholder, null);
    }

    /** When a remembered request was made (its own word for it); entries from before this was kept, when it was spent. */
    private static long madeOf(org.json.JSONObject e) {
        return e.has("made") ? e.optLong("made") : e.optLong("at");
    }

    /** How long a request that opened for none of this phone's conversations is remembered: longer than one could pass. */
    private static final long UNOPENED_KEEP_MS = NONCE_KEEP_MS + 86_400_000L;
    /** How many of those are remembered at most; past it, the earliest seen go first (tests make it small). */
    static volatile int unopenedLimit = 2000;

    private org.json.JSONObject unopened() {
        try {
            return new org.json.JSONObject(sp.getString("unopened2", "{}"));
        } catch (org.json.JSONException e) {
            return new org.json.JSONObject();
        }
    }

    /**
     * Remembers a reply request, by its seal's tag, that opened for none of this phone's conversations and was
     * reported failed: if it opens later (filed under its own conversation once that turns up here), it is refused then
     * too, never sent after the person was told it failed.
     */
    void rememberUnopened(String seal, long now) {
        if (seal == null || seal.isEmpty()) return;
        synchronized (lock) {
            org.json.JSONObject all = unopened();
            if (all.has(seal)) return;
            java.util.List<String> kept = new java.util.ArrayList<>();
            for (java.util.Iterator<String> it = all.keys(); it.hasNext(); ) {
                String k = it.next();
                if (all.optLong(k) >= now - UNOPENED_KEEP_MS) kept.add(k);
            }
            final org.json.JSONObject source = all;
            kept.sort((a, b) -> Long.compare(source.optLong(a), source.optLong(b)));
            org.json.JSONObject next = new org.json.JSONObject();
            int drop = Math.max(0, kept.size() - unopenedLimit + 1);
            for (int i = drop; i < kept.size(); i++) put(next, kept.get(i), all.optLong(kept.get(i)));
            put(next, seal, now);
            SharedPreferences.Editor e = sp.edit().putString("unopened2", next.toString());
            if (drop > 0) e.putLong("unopened_overflow_at", now);   // forgotten early: see spendRequest
            e.commit();
        }
    }

    /** What the private inbox's client (Hedy2) checks reply requests against. */
    Hedy2.Requests requests() {
        return new Hedy2.Requests() {
            @Override
            public void rememberUnopened(String tag, long now) {
                Prefs.this.rememberUnopened(tag, now);
            }

            @Override
            public boolean wasUnopened(String tag) {
                return Prefs.this.wasUnopened(tag);
            }

            @Override
            public long knownSince(String conv) {
                return Prefs.this.knownSince(conv);
            }
        };
    }

    /** Since when this phone has known a conversation (its name or first text); 0 for one known before this was kept. */
    long knownSince(String conv) {
        return conv == null ? 0 : sp.getLong("since:" + conv, 0);
    }

    /** Whether a request with this seal was reported failed because it opened for none of this phone's conversations. */
    boolean wasUnopened(String seal) {
        if (seal == null || seal.isEmpty()) return false;
        synchronized (lock) {
            return unopened().has(seal);
        }
    }

    /** The outcome of a request just acted on, reported again if the same request ever comes back. */
    void recordRequest(String nonce, org.json.JSONObject report) {
        synchronized (lock) {
            org.json.JSONObject all = nonces();
            org.json.JSONObject e = all.optJSONObject(nonce);
            if (e == null) return;
            put(e, "r", report);
            put(all, nonce, e);
            sp.edit().putString("nonces2", all.toString()).commit();
        }
    }

    private static void put(org.json.JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (org.json.JSONException ignored) {
            // plain keys
        }
    }

    /** The tag of the wrap filed for getting back in after a reinstall, once the inbox has it. */
    String wrapTag() {
        return sp.getString("wrap2", "");
    }

    void setWrapTag(String tag) {
        sp.edit().putString("wrap2", tag).apply();
    }

    /** Every conversation id this phone has used, for turning the inbox's keyed ids back into its own. */
    java.util.Set<String> allConversations() {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (java.util.Map.Entry<String, ?> e : sp.getAll().entrySet()) {
            String k = e.getKey();
            if (k.startsWith("name:")) out.add(k.substring(5));
            else if (k.startsWith("seen:")) out.add(k.substring(5));
            else if ((k.startsWith("th:") || k.startsWith("nk:")) && e.getValue() instanceof String) out.add((String) e.getValue());
            else if (k.startsWith("al:") && e.getValue() instanceof String) {
                // this phone's own merges: where a conversation went, and what went into it
                out.add(k.substring(3));
                for (String c : ((String) e.getValue()).split(",")) if (!c.isEmpty()) out.add(c);
            }
        }
        return out;
    }

    /** The conversation this phone folded `conv` into (its own merges), or `conv` itself. */
    String foldedInto(String conv) {
        for (java.util.Map.Entry<String, ?> e : sp.getAll().entrySet()) {
            String k = e.getKey();
            if (!k.startsWith("al:") || !(e.getValue() instanceof String)) continue;
            for (String c : ((String) e.getValue()).split(",")) if (c.equals(conv)) return k.substring(3);
        }
        return conv;
    }

    void setServer(String server, String key) {
        SharedPreferences.Editor e = sp.edit().putString("server", server).putString("key", key).remove("verified");
        if (key != null && !key.isEmpty()) e.putString("here2", here());
        if (Tor.isOnion(server)) e.putBoolean(Tor.PREF, true);   // an onion server is only ever reached through Tor
        e.commit();
    }

    /** "Connect through Tor" (see Tor). Always on while the inbox server is a .onion address. */
    boolean tor() {
        return sp.getBoolean(Tor.PREF, false) || onionServer();
    }

    /** Whether the inbox server is a .onion address. */
    boolean onionServer() {
        Link l = link();
        return Tor.isOnion(l != null ? l.server : server());
    }

    void setTor(boolean on) {
        sp.edit().putBoolean(Tor.PREF, on || onionServer()).commit();
    }

    boolean verified() {
        return sp.getBoolean("verified", false);
    }

    void setVerified(boolean verified) {
        sp.edit().putBoolean("verified", verified).apply();
    }

    // ---- the status notification ----

    static final String STATUS_SHOWN = "shown";
    static final String STATUS_HIDDEN = "hidden";

    /**
     * "shown" (a silent status notification while Relay runs) or "hidden" (none; Relay keeps running through its
     * notification access). Earlier versions' "standard" and "minimal" both read as shown: Android raises a background
     * service's minimal notification to an ordinary one anyway.
     */
    String statusMode() {
        return STATUS_HIDDEN.equals(sp.getString("status_mode", STATUS_SHOWN)) ? STATUS_HIDDEN : STATUS_SHOWN;
    }

    void setStatusMode(String mode) {
        sp.edit().putString("status_mode", STATUS_HIDDEN.equals(mode) ? STATUS_HIDDEN : STATUS_SHOWN).apply();
    }

    // ---- reconnect requests from the inbox devices ----

    /** The newest reconnect request from an inbox device that this phone has acted on (server time, ms). */
    long wakeHandled() {
        return sp.getLong("wake_handled", 0);
    }

    void setWakeHandled(long at) {
        sp.edit().putLong("wake_handled", at).apply();
    }

    // ---- call forwarding (the guide) ----

    /** The number of the phone the user carries, as last typed in the guide. */
    String carryNumber() {
        return sp.getString("carry_number", "");
    }

    void setCarryNumber(String number) {
        sp.edit().putString("carry_number", number == null ? "" : number.trim()).apply();
    }

    // ---- conversations: which store thread and which notification belong to which conversation ----

    /** The conversation a store thread belongs to, or null before it has been matched. */
    String threadConv(long threadId) {
        return sp.getString("th:" + threadId, null);
    }

    void setThreadConv(long threadId, String conv) {
        sp.edit().putString("th:" + threadId, conv).apply();
    }

    /** A notification conversation that turned out to be one the store brought in first. */
    String notificationConv(String defaultConv) {
        return sp.getString("nk:" + defaultConv, null);
    }

    void setNotificationConv(String defaultConv, String conv) {
        sp.edit().putString("nk:" + defaultConv, conv).apply();
    }

    /** The people in a conversation, as Store.key() of their numbers, when known. */
    java.util.Set<String> participants(String conv) {
        String v = sp.getString("pa:" + conv, "");
        java.util.Set<String> out = new java.util.TreeSet<>();
        for (String p : v.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    void setParticipants(String conv, java.util.Collection<String> keys) {
        java.util.Set<String> sorted = new java.util.TreeSet<>(keys);
        sorted.remove("");
        if (sorted.isEmpty()) return;
        sp.edit().putString("pa:" + conv, String.join(",", sorted)).apply();
    }

    /** True or false once seen; null when not known (conversations from before Relay 0.7). */
    Boolean group(String conv) {
        if (!sp.contains("grp:" + conv)) return null;
        return sp.getBoolean("grp:" + conv, false);
    }

    void setGroup(String conv, boolean group) {
        sp.edit().putBoolean("grp:" + conv, group).apply();
    }

    /** Every conversation this phone has relayed or brought in. */
    java.util.List<String> conversations() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String k : sp.getAll().keySet()) if (k.startsWith("name:")) out.add(k.substring(5));
        return out;
    }

    boolean knows(String conv) {
        return sp.contains("name:" + conv) || sp.contains("seen:" + conv);
    }

    /** A conversation the message store brought in, rather than one of Messages' notifications. */
    boolean fromStore(String conv) {
        return sp.getBoolean("fromstore:" + conv, false);
    }

    void setFromStore(String conv) {
        sp.edit().putBoolean("fromstore:" + conv, true).apply();
    }

    /** The store thread known to belong to a notification conversation, or -1. */
    long linkedThread(String conv) {
        return sp.getLong("lk:" + conv, -1);
    }

    void setLinkedThread(String conv, long threadId) {
        sp.edit().putLong("lk:" + conv, threadId).apply();
    }

    // ---- merges waiting for the inbox ----

    /** A merge the inbox still has to do: `from` folds into `into`. */
    static final class Merge {
        final String from;
        final String into;
        /** Not before this time (ms); 0 for now. */
        final long next;
        /** When the inbox first confirmed it, or 0; a merge is repeated once a while later for anything that was in flight. */
        final long confirmed;

        Merge(String from, String into, long next, long confirmed) {
            this.from = from;
            this.into = into;
            this.next = next;
            this.confirmed = confirmed;
        }
    }

    java.util.List<Merge> merges() {
        synchronized (lock) {
            java.util.List<Merge> out = new java.util.ArrayList<>();
            org.json.JSONArray a = mergeList();
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject o = a.optJSONObject(i);
                if (o != null) out.add(new Merge(o.optString("from"), o.optString("into"), o.optLong("next"), o.optLong("confirmed")));
            }
            return out;
        }
    }

    /**
     * Conversations this phone folded into `into`, directly or through ones folded into those: a reply written for one
     * of them just before the merge still goes out, to the same people.
     */
    java.util.Set<String> mergedInto(String into) {
        java.util.Set<String> out = new java.util.TreeSet<>();
        for (String c : sp.getString("al:" + into, "").split(",")) if (!c.isEmpty()) out.add(c);
        return out;
    }

    void addMerge(String from, String into) {
        synchronized (lock) {
            java.util.Set<String> al = mergedInto(into);
            al.add(from);
            al.addAll(mergedInto(from));
            al.remove(into);
            sp.edit().putString("al:" + into, String.join(",", al)).remove("al:" + from).apply();
            org.json.JSONArray a = mergeList();
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject o = a.optJSONObject(i);
                if (o != null && from.equals(o.optString("from")) && into.equals(o.optString("into"))) return;
            }
            a.put(Json.obj("from", from, "into", into, "next", 0L, "confirmed", 0L));
            sp.edit().putString("merges", a.toString()).commit();
        }
    }

    /** Records a try: confirmed by the inbox, or to be tried again after `retryAt`. A second confirmation ends it. */
    void mergeTried(String from, String into, boolean ok, long now, long retryAt) {
        synchronized (lock) {
            org.json.JSONArray a = mergeList();
            org.json.JSONArray next = new org.json.JSONArray();
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                if (from.equals(o.optString("from")) && into.equals(o.optString("into"))) {
                    if (ok && o.optLong("confirmed") > 0) continue;   // done twice: finished
                    if (ok) o = Json.obj("from", from, "into", into, "next", retryAt, "confirmed", now);
                    else o = Json.obj("from", from, "into", into, "next", retryAt, "confirmed", o.optLong("confirmed"));
                }
                next.put(o);
            }
            sp.edit().putString("merges", next.toString()).commit();
        }
    }

    void removeMerge(String from, String into) {
        synchronized (lock) {
            org.json.JSONArray a = mergeList();
            org.json.JSONArray next = new org.json.JSONArray();
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject o = a.optJSONObject(i);
                if (o != null && !(from.equals(o.optString("from")) && into.equals(o.optString("into")))) next.put(o);
            }
            sp.edit().putString("merges", next.toString()).commit();
        }
    }

    private org.json.JSONArray mergeList() {
        try {
            return new org.json.JSONArray(sp.getString("merges", "[]"));
        } catch (org.json.JSONException | ClassCastException e) {
            return new org.json.JSONArray();
        }
    }

    // ---- replies already sent whose report to the inbox may have been lost ----

    org.json.JSONObject outboxReport(String id) {
        synchronized (lock) {
            org.json.JSONObject all = outboxReports();
            org.json.JSONObject o = all.optJSONObject(id);
            return o != null ? o.optJSONObject("report") : null;
        }
    }

    /** Kept the moment a reply has gone out, before reporting it, so a lost report never leads to sending it again. */
    void setOutboxReport(String id, org.json.JSONObject report) {
        synchronized (lock) {
            org.json.JSONObject all = outboxReports();
            long cutoff = System.currentTimeMillis() - 2 * 86_400_000L;
            org.json.JSONObject kept = new org.json.JSONObject();
            for (java.util.Iterator<String> it = all.keys(); it.hasNext(); ) {
                String k = it.next();
                org.json.JSONObject o = all.optJSONObject(k);
                if (o != null && o.optLong("at") >= cutoff) {
                    try {
                        kept.put(k, o);
                    } catch (org.json.JSONException ignored) {
                        // plain key
                    }
                }
            }
            try {
                kept.put(id, Json.obj("report", report, "at", System.currentTimeMillis()));
            } catch (org.json.JSONException ignored) {
                // plain key
            }
            sp.edit().putString("outbox_reports", kept.toString()).commit();
        }
    }

    void clearOutboxReport(String id) {
        synchronized (lock) {
            org.json.JSONObject all = outboxReports();
            if (all.remove(id) != null) sp.edit().putString("outbox_reports", all.toString()).commit();
        }
    }

    private org.json.JSONObject outboxReports() {
        try {
            return new org.json.JSONObject(sp.getString("outbox_reports", "{}"));
        } catch (org.json.JSONException e) {
            return new org.json.JSONObject();
        }
    }

    /** Points every thread and notification that led to `from` at `into`, and forgets `from`. */
    void fold(String from, String into) {
        synchronized (lock) {
            SharedPreferences.Editor e = sp.edit();
            for (java.util.Map.Entry<String, ?> en : sp.getAll().entrySet()) {
                String k = en.getKey();
                if ((k.startsWith("th:") || k.startsWith("nk:")) && from.equals(en.getValue())) e.putString(k, into);
            }
            String moved = sp.getString("rt:" + from, "");
            if (!moved.isEmpty()) e.putString("rt:" + into, keepRecent(sp.getString("rt:" + into, "") + ";" + moved));
            for (String p : new String[] { "name:", "pa:", "grp:", "number:", "fromstore:", "seen:", "lk:", "rt:" }) e.remove(p + from);
            e.apply();
        }
    }

    // ---- recent texts, for telling which conversation a store thread is ----

    private static final int RECENT_LIMIT = 40;   // per conversation

    /** What two copies of a text share, as this phone compares them: never leaves the phone. */
    private static String recentHash(String text) {
        String t = Crypto.matchText(text, null);
        return t.isEmpty() ? "" : Parser.hash(t, 6);
    }

    /** Remembers a text a notification showed in a conversation (direction, time, and a hash of its words). */
    void noteRecent(String conv, boolean out, long ts, String text) {
        String h = recentHash(text);
        if (h.isEmpty() || ts <= 0) return;
        synchronized (lock) {
            String cur = sp.getString("rt:" + conv, "");
            sp.edit().putString("rt:" + conv, keepRecent(cur + ";" + (out ? "o" : "i") + "|" + ts + "|" + h)).apply();
        }
    }

    private static String keepRecent(String joined) {
        java.util.TreeMap<Long, java.util.List<String>> byTs = new java.util.TreeMap<>();
        java.util.Set<String> unique = new java.util.LinkedHashSet<>();
        for (String e : joined.split(";")) if (e.split("\\|").length == 3) unique.add(e);
        for (String e : unique) {
            try {
                byTs.computeIfAbsent(Long.parseLong(e.split("\\|")[1]), k -> new java.util.ArrayList<>()).add(e);
            } catch (NumberFormatException ignored) {
                // not one of ours
            }
        }
        java.util.List<String> all = new java.util.ArrayList<>();
        for (java.util.List<String> l : byTs.values()) all.addAll(l);
        return String.join(";", all.subList(Math.max(0, all.size() - RECENT_LIMIT), all.size()));
    }

    /**
     * The conversations whose recent notification texts include this one: the same words, the same direction, close in
     * time (three minutes for a received text, ten for a sent one, as the inbox folds copies).
     */
    java.util.List<String> recentlyShowing(boolean out, long ts, String text) {
        String h = recentHash(text);
        java.util.List<String> out2 = new java.util.ArrayList<>();
        if (h.isEmpty()) return out2;
        long window = out ? 10 * 60_000L : 3 * 60_000L;
        String dir = out ? "o" : "i";
        for (java.util.Map.Entry<String, ?> en : sp.getAll().entrySet()) {
            if (!en.getKey().startsWith("rt:") || !(en.getValue() instanceof String)) continue;
            for (String e : ((String) en.getValue()).split(";")) {
                String[] p = e.split("\\|");
                if (p.length != 3 || !p[0].equals(dir) || !p[2].equals(h)) continue;
                try {
                    if (Math.abs(Long.parseLong(p[1]) - ts) <= window) {
                        out2.add(en.getKey().substring(3));
                        break;
                    }
                } catch (NumberFormatException ignored) {
                    // not one of ours
                }
            }
        }
        return out2;
    }

    // ---- the message store ----

    /** Messages newer than this (ms) have been read from the store by the live check. */
    long storeMark() {
        return sp.getLong("store_mark", 0);
    }

    void setStoreMark(long ts) {
        sp.edit().putLong("store_mark", ts).apply();
    }


    SharedPreferences raw() {
        return sp;
    }

    // ---- conversations ----

    void setName(String conv, String name) {
        if (name == null || name.isEmpty()) return;
        if (!name.equals(sp.getString("name:" + conv, null))) {
            SharedPreferences.Editor e = sp.edit().putString("name:" + conv, name);
            if (!knows(conv)) e.putLong("since:" + conv, System.currentTimeMillis());   // first known now (see knownSince)
            e.apply();
        }
    }

    String name(String conv) {
        return sp.getString("name:" + conv, "that conversation");
    }

    /** The other side's phone number for a one-to-one conversation, when Messages ever told us. */
    String number(String conv) {
        return sp.getString("number:" + conv, null);
    }

    void setNumber(String conv, String number) {
        if (number == null || number.isEmpty()) return;
        if (!number.equals(sp.getString("number:" + conv, null))) {
            sp.edit().putString("number:" + conv, number).apply();
        }
    }

    /** Records a message fingerprint. False when it was already there, meaning already relayed. */
    boolean markSeen(String conv, String fingerprint) {
        synchronized (lock) {
            String key = "seen:" + conv;
            String current = sp.getString(key, "");
            if (("," + current + ",").contains("," + fingerprint + ",")) return false;
            String next = current.isEmpty() ? fingerprint : current + "," + fingerprint;
            String[] parts = next.split(",");
            if (parts.length > SEEN_LIMIT) {
                StringBuilder b = new StringBuilder();
                for (int i = parts.length - SEEN_LIMIT; i < parts.length; i++) {
                    if (b.length() > 0) b.append(',');
                    b.append(parts[i]);
                }
                next = b.toString();
            }
            SharedPreferences.Editor e = sp.edit().putString(key, next);
            // The first text of a conversation this phone didn't know: from now on it knows it (see Hedy2.outbox).
            if (!knows(conv)) e.putLong("since:" + conv, System.currentTimeMillis());
            e.apply();
            return true;
        }
    }
}
