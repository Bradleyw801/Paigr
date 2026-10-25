package com.textrelay.texts;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One scan links two phones (PROTOCOL.md, Linking; web/app.js linkWithCode). Relay shows a code: on a phone that isn't
 * set up it asks to be let in, on one that is (Link a device) it invites. Texts reads it and either joins that inbox,
 * brings the new phone into its own inbox (a new relay phone takes over), or, when neither phone has one yet, starts a
 * new private inbox for the two of them. The server only ever sees the code's lookup, never the code.
 */
final class Linker {
    interface Callbacks {
        void busy(boolean on);

        void failed(String text);

        /** Asks the person; `yes` runs if they agree, `no` if they don't. */
        void confirm(String title, String question, String agree, Runnable yes, Runnable no);

        /** This phone is linked now (or another device was brought in); `message` says which. */
        void done(String message, boolean joined);
    }

    private final Inbox inbox;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean working;

    Linker(Inbox inbox) {
        this.inbox = inbox;
    }

    static final class Parsed {
        final String server;
        final String code;

        Parsed(String server, String code) {
            this.server = server;
            this.code = code;
        }
    }

    private static final Pattern QR = Pattern.compile("^TEXTRELAY2:([a-z0-9.-]+(?::[0-9]{1,5})?)/([0-9a-z-]{12,20})$", Pattern.CASE_INSENSITIVE);
    private static final Pattern URL = Pattern.compile("^(https?://[^/#?\\s]+)/?(?:[?][^#]*)?#link=([0-9A-Za-z-]{12,20})$");
    private static final Pattern CODE = Pattern.compile("^[0-9a-hjkmnp-tv-z]{12}$");

    /**
     * The code in what Relay's QR code holds (TEXTRELAY2:<server host>/<CODE>), in a link to the inbox page
     * (https://<server>/#link=<CODE>), or typed; null when it's none of these.
     */
    static Parsed parse(String text) {
        String s = text == null ? "" : text.trim();
        String server = null;
        String code = s;
        Matcher q = QR.matcher(s);
        if (q.matches()) {
            String host = q.group(1).toLowerCase(Locale.US);
            // A .onion server is reached through Tor, which encrypts it end to end: it has no https.
            server = (host.matches("^(?:127\\.0\\.0\\.1|localhost)(?::.*)?$") || Tor.isOnion(host) ? "http://" : "https://") + host;
            code = q.group(2);
        } else {
            Matcher m = URL.matcher(s);
            if (m.matches()) {
                server = m.group(1);
                code = m.group(2);
            }
        }
        String n = Crypto.normCode(code);
        if (n.length() != 12 || !CODE.matcher(n).matches()) return null;
        return new Parsed(server, n.toUpperCase(Locale.US));
    }

    static String pretty(String code) {
        String c = code.toUpperCase(Locale.US);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < c.length(); i++) {
            if (i > 0 && i % 4 == 0) b.append('-');
            b.append(c.charAt(i));
        }
        return b.toString();
    }

    private static String origin(String server) {
        String s = server == null ? "" : server.trim().toLowerCase(Locale.US);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** A code from Relay, scanned or typed. */
    void link(String raw, Callbacks cb) {
        Parsed parsed = parse(raw);
        if (parsed == null) {
            cb.failed(String.valueOf(raw).trim().toUpperCase(Locale.US).startsWith("TEXTRELAY:")
                    ? "That code is from an older Paigr Relay. Update it on that phone, then scan the code it shows."
                    : "That isn't a code from Paigr Relay. Enter the 12 letters and numbers it shows, or scan its code.");
            return;
        }
        Link mine = inbox.link;
        String server = mine != null ? mine.server : inbox.prefs.server();
        if (parsed.server != null && !origin(parsed.server).equals(origin(server))) {
            String shown = parsed.server.replaceFirst("^https?://", "");
            if (mine != null) {
                cb.failed("That code is for a different inbox server (" + shown + ").");
                return;
            }
            // Not in an inbox yet: the code's server (one you run yourself, say) becomes this phone's, if you say so.
            cb.confirm("Use this inbox server?", shown + "\n\nLink with it only if you know who runs it.", "Use it",
                    () -> start(parsed, parsed.server, null, cb), () -> {});
            return;
        }
        start(parsed, server, mine, cb);
    }

    private void start(Parsed parsed, String server, Link mine, Callbacks cb) {
        if (working) return;
        working = true;
        cb.busy(true);
        if (Tor.isOnion(server)) inbox.prefs.setTor(true);   // an onion server is only ever reached through Tor
        String token = mine == null ? Crypto.newToken() : null;
        new Thread(() -> {
            try {
                Crypto.LinkSecrets s = Crypto.linkSecrets(parsed.code);
                JSONObject body = new JSONObject().put("lookup", s.lookup);
                if (mine == null) body.put("token", token);
                JSONObject claim = (mine != null ? new Hedy(mine) : new Hedy(server, null)).post("/v2/link/claim", body);
                if ("invite".equals(claim.optString("kind"))) {
                    JSONObject box;
                    try {
                        box = Crypto.openJson(s.boxKey, claim.optString("box", ""), Crypto.AAD_BOX);
                    } catch (GeneralSecurityException e) {
                        throw new Refused("That code didn't open. Make a new one in Paigr Relay.");
                    }
                    if (!claim.optString("tid").equals(box.optString("tid")) || !(box.opt("key") instanceof String)) {
                        throw new Refused("That code didn't open. Make a new one in Paigr Relay.");
                    }
                    Link l = Link.parse(new JSONObject().put("tid", box.optString("tid")).put("token", token).put("key", box.optString("key")).toString(), server);
                    if (l == null) throw new Refused("That code didn't open. Make a new one in Paigr Relay.");
                    join(l, "Linked. Your messages are loading.", cb);
                    return;
                }
                if (mine != null) {
                    // A phone waiting to be let into this inbox: a new relay phone taking over, or a browser.
                    boolean relay = "relay".equals(claim.optString("role"));
                    Runnable adopt = () -> new Thread(() -> {
                        try {
                            String answer = Crypto.sealJson(s.boxKey, new JSONObject().put("v", 2).put("tid", mine.tid).put("key", mine.keyB64()), Crypto.AAD_BOX);
                            new Hedy(mine).post("/v2/link/adopt", new JSONObject().put("lookup", s.lookup).put("answer", answer));
                            finish(cb, relay ? "Done. That phone is your relay phone now. It connects in a few seconds." : "Done. That device is linked.", false);
                        } catch (IOException | JSONException | RuntimeException e) {
                            fail(cb, e);
                        }
                    }, "texts-adopt").start();
                    if (relay && claim.optBoolean("hasRelay", false)) {
                        main.post(() -> cb.confirm("Switch relay phone?", "Make the phone showing this code your relay phone? Your current relay phone stops relaying for this inbox.",
                                "Switch", adopt, () -> finish(cb, null, false)));
                    } else {
                        adopt.run();
                    }
                    return;
                }
                if (!"relay".equals(claim.optString("role"))) {
                    throw new Refused("That device isn't linked either. Scan the code Paigr Relay shows on the phone with the SIM card.");
                }
                // Neither phone has an inbox yet: this one starts a private inbox for the two of them.
                byte[] key = Crypto.random(32);
                String tid = Crypto.newTid();
                String answer = Crypto.sealJson(s.boxKey, new JSONObject().put("v", 2).put("tid", tid).put("key", Crypto.b64u(key)), Crypto.AAD_BOX);
                new Hedy(server, null).post("/v2/link/adopt", new JSONObject().put("lookup", s.lookup).put("answer", answer)
                        .put("create", new JSONObject().put("tid", tid).put("token", token)));
                join(new Link(server, tid, token, key), "Linked. Your private inbox is ready.", cb);
            } catch (Refused e) {
                main.post(() -> {
                    working = false;
                    cb.busy(false);
                    cb.failed(e.getMessage());
                });
            } catch (IOException | JSONException | RuntimeException e) {
                fail(cb, e);
            }
        }, "texts-link").start();
    }

    private static final class Refused extends Exception {
        Refused(String text) {
            super(text);
        }
    }

    /** This phone joins `l`: checked first, then kept, and the background checks follow it. */
    private void join(Link l, String message, Callbacks cb) throws IOException {
        new Hedy(l).get("/v2/ping");
        main.post(() -> {
            working = false;
            cb.busy(false);
            inbox.adopt(l);
            cb.done(message, true);
        });
    }

    private void finish(Callbacks cb, String message, boolean joined) {
        main.post(() -> {
            working = false;
            cb.busy(false);
            if (message != null) cb.done(message, joined);
        });
    }

    private void fail(Callbacks cb, Exception e) {
        main.post(() -> {
            working = false;
            cb.busy(false);
            cb.failed(Inbox.words(e));
        });
    }
}
