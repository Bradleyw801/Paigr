package com.textrelay.relay;

import android.app.Notification;
import android.app.Person;
import android.app.RemoteInput;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.StatusBarNotification;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Reads a Google Messages notification as a conversation: who, what was said, and how to reply. */
final class Parser {
    static final String MESSAGES_PACKAGE = "com.google.android.apps.messaging";

    private Parser() {}

    static final class Msg {
        final long time;
        final String sender;   // display name; null for our own messages
        final boolean self;
        final String text;
        final Uri dataUri;     // picture or other attachment, when there is one
        final String mime;
        final String number;   // the sender's phone number when Messages put a tel: URI on them; else null

        Msg(long time, String sender, boolean self, String text, Uri dataUri, String mime) {
            this(time, sender, self, text, dataUri, mime, null);
        }

        Msg(long time, String sender, boolean self, String text, Uri dataUri, String mime, String number) {
            this.time = time;
            this.sender = sender;
            this.self = self;
            this.text = text;
            this.dataUri = dataUri;
            this.mime = mime;
            this.number = number;
        }

        /** Stable fingerprint, so a message is relayed once however often Messages re-posts it. */
        String fingerprint() {
            return hash(time + "\u0001" + sender + "\u0001" + text + "\u0001" + (mime != null ? mime : ""), 8);
        }
    }

    static final class Snapshot {
        final String id;       // short stable id for the conversation, shared with the inbox (before any matching)
        final String title;
        final boolean group;
        final List<Msg> messages;
        final Notification.Action reply;
        /** URIs Messages put on the people in it (tel:, or a contact), for telling which store thread it is. */
        final List<String> personUris;
        /**
         * Android 15 and later show apps like Relay a stand-in for a notification they take for a one-time code: the
         * app's own name, one message from nobody saying the content is hidden, timed when it was handed over. Nothing
         * in it is the conversation's, so its title, people and message are never used; only its reply action is.
         */
        final boolean hidden;

        Snapshot(String id, String title, boolean group, List<Msg> messages, Notification.Action reply, List<String> personUris,
                boolean hidden) {
            this.id = id;
            this.title = title;
            this.group = group;
            this.messages = messages;
            this.reply = reply;
            this.personUris = personUris;
            this.hidden = hidden;
        }
    }

    /** What Android puts in place of hidden content, in this phone's language; the English text if it can't be read. */
    private static volatile String hiddenText;

    static boolean isHiddenText(CharSequence text) {
        if (text == null) return false;
        String h = hiddenText;
        if (h == null) {
            h = "Sensitive notification content hidden";
            try {
                android.content.res.Resources r = android.content.res.Resources.getSystem();
                int id = r.getIdentifier("redacted_notification_message", "string", "android");
                if (id != 0) h = r.getString(id);
            } catch (RuntimeException ignored) {
                // keep the English text
            }
            hiddenText = h;
        }
        String t = text.toString().trim();
        return t.equals(h) || t.equals("Sensitive notification content hidden");
    }

    /** Null when the notification isn't a conversation (summaries, progress, setup prompts). */
    @SuppressWarnings("deprecation")   // the typed Bundle getters need API 33; this runs on 29+
    static Snapshot parse(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        if (n == null || n.extras == null) return null;
        int skip = Notification.FLAG_GROUP_SUMMARY | Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE;
        if ((n.flags & skip) != 0) return null;
        Bundle extras = n.extras;

        CharSequence conversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE);
        CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
        boolean group = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false);
        Person user = extras.getParcelable(Notification.EXTRA_MESSAGING_PERSON);

        List<Msg> messages = new ArrayList<>();
        List<String> uris = new ArrayList<>();
        java.util.ArrayList<Person> people = extras.getParcelableArrayList(Notification.EXTRA_PEOPLE_LIST);
        if (people != null) for (Person p : people) if (p != null && p.getUri() != null && !samePerson(p, user)) uris.add(p.getUri());
        Parcelable[] bundles = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
        boolean hidden;
        if (bundles != null && bundles.length > 0) {
            boolean nobody = true;
            for (Notification.MessagingStyle.Message m
                    : Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundles)) {
                Person from = m.getSenderPerson();
                boolean self = from == null || samePerson(from, user);
                if (!self && from.getUri() != null && !uris.contains(from.getUri())) uris.add(from.getUri());
                String sender = from != null && from.getName() != null ? from.getName().toString() : null;
                nobody &= from != null && blank(from.getName());
                CharSequence text = m.getText();
                messages.add(new Msg(m.getTimestamp(), sender, self, text != null ? text.toString() : "",
                        m.getDataUri(), m.getDataMimeType(), self ? null : telOf(from)));
            }
            hidden = messages.size() == 1
                    && (isHiddenText(messages.get(0).text) || nobody && user != null && blank(user.getName()));
        } else if (Notification.CATEGORY_MESSAGE.equals(n.category)) {
            // Plain notification: title is the sender, text is the message.
            CharSequence text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
            if (text == null) text = extras.getCharSequence(Notification.EXTRA_TEXT);
            if (text == null || title == null) return null;
            long when = n.when != 0 ? n.when : sbn.getPostTime();
            messages.add(new Msg(when, title.toString(), false, text.toString(), null, null));
            hidden = isHiddenText(text);
        } else {
            return null;
        }
        if (hidden) return new Snapshot(conversationId(sbn), "", false, new ArrayList<>(), replyAction(n), new ArrayList<>(), true);

        if (!group && conversationTitle != null && distinctSenders(messages) > 1) group = true;
        String name;
        if (conversationTitle != null && conversationTitle.length() > 0) name = conversationTitle.toString();
        else if (title != null && title.length() > 0) name = title.toString();
        else name = firstSender(messages);
        return new Snapshot(conversationId(sbn), name, group, messages, replyAction(n), uris, false);
    }

    private static boolean blank(CharSequence s) {
        return s == null || s.toString().trim().isEmpty();
    }

    /** One key per conversation: the conversation shortcut when Messages sets one, else tag and id. */
    static String conversationKey(StatusBarNotification sbn) {
        String id = sbn.getNotification().getShortcutId();
        if (id == null || id.isEmpty()) id = (sbn.getTag() != null ? sbn.getTag() : "") + "#" + sbn.getId();
        return sbn.getPackageName() + "/" + id;
    }

    /** Short id derived from the conversation key; the inbox only ever sees this, never the raw key. */
    static String conversationId(StatusBarNotification sbn) {
        return hash(conversationKey(sbn), 16);
    }

    /** The notification's inline-reply action, preferring the one marked as Reply. */
    static Notification.Action replyAction(Notification n) {
        if (n.actions == null) return null;
        Notification.Action fallback = null;
        for (Notification.Action a : n.actions) {
            RemoteInput[] inputs = a.getRemoteInputs();
            if (a.actionIntent == null || inputs == null || inputs.length == 0) continue;
            boolean freeForm = false;
            for (RemoteInput r : inputs) freeForm |= r.getAllowFreeFormInput();
            if (!freeForm) continue;
            if (a.getSemanticAction() == Notification.Action.SEMANTIC_ACTION_REPLY) return a;
            if (fallback == null) fallback = a;
        }
        return fallback;
    }

    static String hash(String raw, int bytes) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < bytes; i++) b.append(String.format(Locale.US, "%02x", d[i]));
            return b.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(raw.hashCode());
        }
    }

    /** The digits of a tel: URI on a notification Person, or null. */
    static String telOf(Person p) {
        if (p == null || p.getUri() == null) return null;
        String uri = p.getUri();
        if (!uri.startsWith("tel:")) return null;
        String number = Uri.decode(uri.substring(4)).replaceAll("[^0-9+]", "");
        return number.length() >= 7 ? number : null;
    }

    private static boolean samePerson(Person a, Person b) {
        if (b == null) return false;
        if (a.getKey() != null && b.getKey() != null) return a.getKey().equals(b.getKey());
        return a.getName() != null && b.getName() != null && a.getName().toString().equals(b.getName().toString());
    }

    private static int distinctSenders(List<Msg> messages) {
        Set<String> names = new HashSet<>();
        for (Msg m : messages) if (!m.self && m.sender != null) names.add(m.sender);
        return names.size();
    }

    private static String firstSender(List<Msg> messages) {
        for (Msg m : messages) if (!m.self && m.sender != null) return m.sender;
        return "Unknown";
    }
}
