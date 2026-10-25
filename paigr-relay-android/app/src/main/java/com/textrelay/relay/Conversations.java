package com.textrelay.relay;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Decides which conversation a message belongs to, so the same people never show up twice in the inbox. Messages'
 * notifications and the phone's message store name conversations differently; a store thread joins the conversation
 * its notifications use, found by the people in it, by its messages (the same text at the same time), or by name. A
 * match needs exactly one candidate; anything doubtful stays separate, and is merged later if its messages show it was
 * the same conversation after all.
 */
final class Conversations {
    /** Asks the inbox which conversations already hold some messages of a thread (one list per probe). */
    interface Locator {
        List<List<String>> locate(List<Store.Msg> probes) throws IOException;
    }

    /** Finds the store thread a notification's newest texts are in; null when there is none or it's unclear. */
    interface ThreadFinder {
        Store.Thread threadOf(Parser.Snapshot s);
    }

    /** Told when two conversations turn out to be one, so what still waits for the inbox follows. */
    interface FoldListener {
        void folded(String from, String into);
    }

    volatile FoldListener onFold;

    private final Context context;
    private final Prefs prefs;
    private final Map<String, String> names = new HashMap<>();
    /** The newest message time each notification conversation was looked up in the store for. */
    private final Map<String, Long> lookedUp = new HashMap<>();

    Conversations(Context context, Prefs prefs) {
        this.context = context.getApplicationContext();
        this.prefs = prefs;
    }

    // ------------------------------------------------------------------ store threads

    /**
     * The conversation for a store thread, matched or made the first time it is seen. Null when the inbox couldn't be
     * asked about it just now; the thread is tried again later rather than split off on a guess. The inbox is asked
     * without holding the lock, so a notification arriving meanwhile is never held up by the network.
     */
    String forThread(Store.Thread t, Store store, Locator locator) {
        List<String> keys = keysOf(t.addresses);
        synchronized (this) {
            String mapped = prefs.threadConv(t.id);
            if (mapped != null) return mapped;
            String conv = byPeople(keys);
            if (conv != null) {
                link(conv, t);
                return conv;
            }
        }
        String located = null;
        if (locator != null) {
            try {
                located = byMessages(store.latest(t.id, 12), locator);
            } catch (IOException e) {
                return null;
            }
        }
        synchronized (this) {
            String mapped = prefs.threadConv(t.id);   // a notification may have claimed it meanwhile
            if (mapped != null) return mapped;
            if (located != null && !fits(located, keys)) located = null;
            String conv = located != null ? located : byName(keys, keys.size() > 1, t.addresses);
            if (conv == null) {
                conv = Parser.hash("thread:" + String.join(",", keys) + ":" + t.id, 16);
                prefs.setFromStore(conv);
            }
            link(conv, t);
            return conv;
        }
    }

    /** Ties a thread to a conversation and remembers who is in it. */
    private void link(String conv, Store.Thread t) {
        List<String> keys = keysOf(t.addresses);
        prefs.setThreadConv(t.id, conv);
        if (prefs.linkedThread(conv) < 0) prefs.setLinkedThread(conv, t.id);
        if (prefs.participants(conv).isEmpty()) prefs.setParticipants(conv, keys);
        if (prefs.group(conv) == null) prefs.setGroup(conv, keys.size() > 1);
        if (!prefs.knows(conv)) prefs.setName(conv, title(t.addresses));
        if (keys.size() == 1 && prefs.number(conv) == null) prefs.setNumber(conv, t.addresses.get(0));
    }

    /** The one conversation with exactly these people in it. */
    private String byPeople(List<String> keys) {
        if (keys.isEmpty()) return null;
        Set<String> want = new TreeSet<>(keys);
        String hit = null;
        for (String c : prefs.conversations()) {
            if (!prefs.participants(c).equals(want)) continue;
            if (hit != null) return null;
            hit = c;
        }
        return hit;
    }

    /**
     * The one conversation the inbox already holds this thread's messages in. A probe that matches more than one
     * conversation says nothing (a short "ok" sent in two places at once). The rest must agree, on at least two
     * messages, one of them received: the same text sent to several people one by one, a greeting say, is no evidence
     * of whose thread this is.
     */
    private static String byMessages(List<Store.Msg> latest, Locator locator) throws IOException {
        List<Store.Msg> probes = new ArrayList<>();
        for (Store.Msg m : latest) {
            if (Store.norm(m.text).length() >= 2) probes.add(m);
            if (probes.size() == 8) break;
        }
        if (probes.size() < 2) return null;
        List<List<String>> found = locator.locate(probes);
        String hit = null;
        int agreeing = 0;
        boolean received = false;
        for (int i = 0; i < found.size() && i < probes.size(); i++) {
            List<String> convs = found.get(i);
            if (convs == null || convs.size() != 1) continue;
            String c = convs.get(0);
            if (hit != null && !hit.equals(c)) return null;
            hit = c;
            agreeing++;
            if (!probes.get(i).out) received = true;
        }
        return hit != null && agreeing >= 2 && received ? hit : null;
    }

    /** Whether what this phone knows about a conversation (who is in it, one-to-one or group) fits these people. */
    private boolean fits(String conv, List<String> keys) {
        Set<String> pa = prefs.participants(conv);
        if (!pa.isEmpty() && !pa.equals(new TreeSet<>(keys))) return false;
        Boolean g = prefs.group(conv);
        return g == null || g == (keys.size() > 1);
    }

    /** The one conversation whose name is this thread's contact (one-to-one) or set of names (group). */
    private String byName(List<String> keys, boolean group, List<String> addresses) {
        if (keys.isEmpty()) return null;
        Set<String> named = new TreeSet<>();
        for (String a : addresses) named.add(nameFor(a).toLowerCase(Locale.US));
        String hit = null;
        for (String c : prefs.conversations()) {
            if (!prefs.participants(c).isEmpty() || prefs.fromStore(c)) continue;   // people known and different
            Boolean g = prefs.group(c);
            if (g != null && g != group) continue;
            String title = prefs.name(c).trim();
            boolean match;
            if (!group) {
                String number = prefs.number(c);
                String contact = contactName(addresses.get(0));
                match = (number != null && Store.key(number).equals(keys.get(0)))
                        || (Store.isNumber(title) && Store.key(title).equals(keys.get(0)))
                        || (contact != null && title.equalsIgnoreCase(contact));
            } else {
                Set<String> have = new TreeSet<>();
                for (String part : title.split("\\s*(?:,|&| and )\\s*")) if (!part.isEmpty()) have.add(part.toLowerCase(Locale.US));
                match = have.equals(named);
            }
            if (!match) continue;
            if (hit != null) return null;
            hit = c;
        }
        return hit;
    }

    // ------------------------------------------------------------------ notifications

    /**
     * The conversation for a Messages notification. Conversations relayed before keep their id. When the notification's
     * newest text is also in the message store, its thread and the conversation become one: a new notification joins
     * the conversation that thread already has, and a thread the store brought in on its own folds into this one.
     */
    /** The conversation this notification already belongs to, or null if it is new here. Changes nothing. */
    synchronized String knownForNotification(Parser.Snapshot s) {
        String mapped = prefs.notificationConv(s.id);
        if (mapped != null) return mapped;
        return prefs.knows(s.id) ? s.id : null;
    }

    synchronized String forNotification(Parser.Snapshot s, ThreadFinder finder) {
        String own = s.id;
        String mapped = prefs.notificationConv(own);
        boolean known = mapped != null || prefs.knows(own);
        String conv = mapped != null ? mapped : own;

        long newest = 0;
        for (Parser.Msg m : s.messages) if (!m.self) newest = Math.max(newest, m.time);
        Long last = lookedUp.get(conv);
        boolean look = finder != null && (!known || prefs.linkedThread(conv) < 0) && (last == null || newest > last);
        Store.Thread t = look ? finder.threadOf(s) : null;
        if (look) lookedUp.put(conv, newest);
        if (t != null) {
            String has = prefs.threadConv(t.id);
            if (has == null || has.equals(conv)) {
                if (fits(conv, keysOf(t.addresses))) link(conv, t);
            } else if (!known) {
                prefs.setNotificationConv(own, has);   // a new notification id for a conversation already here
                conv = has;
            } else if (prefs.fromStore(has) && !prefs.fromStore(conv) && fits(conv, keysOf(t.addresses))) {
                prefs.fold(has, conv);                  // the store brought this thread in before its notifications showed whose it was
                prefs.addMerge(has, conv);
                link(conv, t);
                FoldListener l = onFold;
                if (l != null) l.folded(has, conv);
            }
            return conv;
        }
        if (known) return conv;

        // New, and its thread isn't readable (no message access, or encrypted RCS that stays out of the store): match a
        // conversation the store brought in by the people in it, or by name.
        Set<String> keys = new TreeSet<>();
        for (String n : numbersOf(s)) keys.add(Store.key(n));
        keys.remove("");
        String hit = null;
        boolean unique = true;
        for (String c : prefs.conversations()) {
            if (c.equals(own) || !prefs.fromStore(c)) continue;
            Set<String> pa = prefs.participants(c);
            boolean match;
            if (!s.group && !keys.isEmpty()) {
                match = pa.size() == 1 && keys.contains(pa.iterator().next());
            } else {
                Boolean g = prefs.group(c);
                match = (g == null || g == s.group) && (prefs.name(c).trim().equalsIgnoreCase(s.title.trim())
                        || (!s.group && Store.isNumber(s.title) && pa.size() == 1 && pa.contains(Store.key(s.title))));
            }
            if (!match) continue;
            if (hit != null) unique = false;
            hit = c;
        }
        if (hit == null || !unique) return own;
        prefs.setNotificationConv(own, hit);
        return hit;
    }

    /** What a one-to-one notification says for certain about who it is with: its tel: number. */
    synchronized void learn(String conv, Parser.Snapshot s) {
        prefs.setGroup(conv, s.group);
        if (s.group) return;
        String number = null;
        for (Parser.Msg m : s.messages) if (m.number != null) number = m.number;
        if (number == null) return;
        if (prefs.number(conv) == null) prefs.setNumber(conv, number);
        if (prefs.participants(conv).isEmpty()) prefs.setParticipants(conv, keysOf(java.util.Collections.singletonList(number)));
    }

    /** The numbers a notification's people stand for: tel: URIs, and contacts' numbers when Contacts is allowed. */
    List<String> numbersOf(Parser.Snapshot s) {
        List<String> out = new ArrayList<>();
        for (Parser.Msg m : s.messages) if (m.number != null && !out.contains(m.number)) out.add(m.number);
        for (String uri : s.personUris) {
            if (uri.startsWith("tel:")) {
                String n = Uri.decode(uri.substring(4)).replaceAll("[^0-9+]", "");
                if (n.length() >= 3 && !out.contains(n)) out.add(n);
            } else if (uri.startsWith("content://com.android.contacts") && contactsAllowed()) {
                for (String n : contactNumbers(uri)) if (!out.contains(n)) out.add(n);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ names

    /** What to call an address: the contact's name when Contacts is allowed, otherwise the number as people write it. */
    String nameFor(String address) {
        if (address == null || address.isEmpty()) return "Unknown";
        String contact = contactName(address);
        return contact != null ? contact : Store.pretty(address);
    }

    private String title(List<String> addresses) {
        if (addresses.isEmpty()) return "Unknown";
        List<String> parts = new ArrayList<>();
        for (String a : addresses) parts.add(nameFor(a));
        return String.join(", ", parts);
    }

    static List<String> keysOf(List<String> addresses) {
        Set<String> keys = new TreeSet<>();
        for (String a : addresses) keys.add(Store.key(a));
        keys.remove("");
        return new ArrayList<>(keys);
    }

    private boolean contactsAllowed() {
        return context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED;
    }

    /** The contact's name for an address, or null when there is none or Contacts isn't allowed. */
    synchronized String contactName(String address) {
        if (address == null || address.isEmpty() || !contactsAllowed()) return null;
        String key = Store.key(address);
        if (names.containsKey(key)) return names.get(key);
        String found = null;
        try (Cursor c = context.getContentResolver().query(
                Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address)),
                new String[] { ContactsContract.PhoneLookup.DISPLAY_NAME }, null, null, null)) {
            if (c != null && c.moveToFirst()) found = c.getString(0);
        } catch (RuntimeException ignored) {
            // no contacts provider, or the lookup refused
        }
        names.put(key, found);
        return found;
    }

    private List<String> contactNumbers(String contactUri) {
        List<String> out = new ArrayList<>();
        try {
            Uri lookup = ContactsContract.Contacts.lookupContact(context.getContentResolver(), Uri.parse(contactUri));
            if (lookup == null) return out;
            long id = Long.parseLong(lookup.getLastPathSegment());
            try (Cursor c = context.getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[] { ContactsContract.CommonDataKinds.Phone.NUMBER },
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?", new String[] { Long.toString(id) }, null)) {
                while (c != null && c.moveToNext()) if (c.getString(0) != null) out.add(c.getString(0));
            }
        } catch (RuntimeException ignored) {
            // not a contact URI this phone can resolve
        }
        return out;
    }
}
