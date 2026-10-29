package com.textrelay.texts;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.provider.Settings;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One notification per conversation, in the system's messaging style, that opens that conversation. Normal alerts
 * make a sound and can pop up; quiet ones only appear in the shade; with alerts off there are none. They form one
 * group, and the group's summary is posted whenever any of them is up: some phones show a grouped notification only
 * under its summary (with no summary the sound played and nothing appeared), and Android shows a lone one on its own.
 * What they count is what the inbox counts: a conversation's notification says how many messages the inbox holds as
 * unread there, and goes away once it holds none, wherever the conversation was read.
 */
final class Notifier {
    static final String CHANNEL = "messages";
    static final String CHANNEL_QUIET = "messages-quiet";
    static final String GROUP = "texts";
    static final int SUMMARY_ID = 2;
    private static final int KEEP = 6;

    private final Context context;
    private final NotificationManager nm;
    private final Prefs prefs;
    /** Recent messages per conversation, so a second text joins the first instead of replacing it. */
    private final Map<String, List<JSONObject>> recent = new LinkedHashMap<>();
    /** The inbox's unread count for each conversation it has told this phone about. */
    private final Map<String, Integer> unread = new java.util.HashMap<>();
    /** Cancelled by this run since last posted: the system's list can lag a cancel for a moment. */
    private final java.util.Set<String> cancelled = new java.util.HashSet<>();
    /** Which conversation a notification is for, in its extras (so the summary can count what's up after a restart). */
    static final String EXTRA_CONV = "com.textrelay.texts.conv";

    Notifier(Context context) {
        this.context = context.getApplicationContext();
        this.nm = this.context.getSystemService(NotificationManager.class);
        this.prefs = new Prefs(this.context);
        NotificationChannel channel = new NotificationChannel(CHANNEL, this.context.getString(R.string.channel_messages), NotificationManager.IMPORTANCE_HIGH);
        channel.setSound(Settings.System.DEFAULT_NOTIFICATION_URI,
                new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT).build());
        channel.enableVibration(true);
        channel.setShowBadge(true);
        NotificationChannel quiet = new NotificationChannel(CHANNEL_QUIET, this.context.getString(R.string.channel_messages_quiet), NotificationManager.IMPORTANCE_LOW);
        quiet.setShowBadge(true);
        nm.createNotificationChannel(channel);
        nm.createNotificationChannel(quiet);
    }

    /** The channel new messages use in the chosen alert mode. */
    String channel() {
        return Prefs.ALERT_QUIET.equals(prefs.alertMode()) ? CHANNEL_QUIET : CHANNEL;
    }

    /** Messages that didn't open on this phone: in a notification of their own, never in a conversation's. */
    static final int UNOPENED_ID = 6;
    private int unopened = 0;

    synchronized void show(List<JSONObject> messages) {
        if (Prefs.ALERT_OFF.equals(prefs.alertMode())) return;
        List<String> touched = new ArrayList<>();
        int unopenedBefore = unopened;
        for (JSONObject m : messages) {
            // Where the server filed one that didn't open says nothing: it isn't shown as that conversation's.
            if (m.optBoolean("unchecked", false)) {
                unopened++;
                continue;
            }
            String conv = m.optString("conv", "");
            if (conv.isEmpty()) continue;
            List<JSONObject> list = recent.get(conv);
            if (list == null) recent.put(conv, list = new ArrayList<>());
            list.add(m);
            while (list.size() > KEEP) list.remove(0);
            if (!touched.contains(conv)) touched.add(conv);
        }
        for (String conv : touched) {
            cancelled.remove(conv);
            nm.notify(idFor(conv), build(conv, recent.get(conv)));
        }
        if (unopened > unopenedBefore) nm.notify(UNOPENED_ID, unopenedNotice());
        summarize();
    }

    /**
     * The inbox's unread counts: a conversation it holds nothing unread in has no notification here, whether it was
     * read on this phone, on the relay phone, on the page or on another device. Counts take effect on the next post.
     */
    synchronized void unread(Map<String, Integer> counts) {
        boolean gone = false;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            String conv = e.getKey();
            int n = e.getValue() == null ? 0 : e.getValue();
            if (n > 0) {
                unread.put(conv, n);
                continue;
            }
            unread.remove(conv);
            if (recent.remove(conv) != null | shownConvs().contains(conv)) {
                cancel(conv);
                gone = true;
            }
        }
        if (gone) summarize();
    }

    /** The conversations whose notifications are up now (the system's word, so it holds after a restart too). */
    private java.util.Set<String> shownConvs() {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        try {
            for (android.service.notification.StatusBarNotification sbn : nm.getActiveNotifications()) {
                String conv = sbn.getNotification().extras.getString(EXTRA_CONV);
                if (conv != null && sbn.getId() == idFor(conv)) out.add(conv);
            }
        } catch (RuntimeException ignored) {
            // the system wouldn't say: what this run posted stands in
        }
        out.removeAll(cancelled);
        out.addAll(recent.keySet());
        return out;
    }

    private void cancel(String conv) {
        cancelled.add(conv);
        nm.cancel(idFor(conv));
    }

    /** How many unread messages a conversation's notification counts: the inbox's figure, else what came in here. */
    private int countOf(String conv) {
        Integer n = unread.get(conv);
        List<JSONObject> l = recent.get(conv);
        int here = l == null ? 0 : l.size();
        return n != null && n > 0 ? n : Math.max(1, here);
    }

    /** The group's summary: up while any conversation's notification (or the unopened notice) is, silent, gone with the last. */
    private void summarize() {
        if (shownConvs().isEmpty() && unopened == 0) nm.cancel(SUMMARY_ID);
        else nm.notify(SUMMARY_ID, summary());
    }

    /** The conversation id of the sample notification (Settings, "Test notification"); tapping it opens the list. */
    static final String TEST = "test";

    /** A sample, through the same path a text takes, so what the shade shows can be checked on the phone itself. */
    synchronized void test() {
        try {
            JSONObject m = new JSONObject().put("conv", TEST).put("name", "Paigr").put("sender", "Paigr").put("ts", System.currentTimeMillis())
                    .put("text", "This is how a new text shows up. Swipe it away when you're done.");
            show(java.util.Collections.singletonList(m));
        } catch (org.json.JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private Notification unopenedNotice() {
        return new Notification.Builder(context, channel())
                .setSmallIcon(R.drawable.ic_status)
                .setColor(Ui.BRAND.base)
                .setContentTitle("Paigr")
                .setContentText("A message came in that couldn't be checked on this phone.")
                .setStyle(new Notification.BigTextStyle())
                .setCategory(Notification.CATEGORY_STATUS)
                .setGroup(GROUP)
                .setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open(null))
                .setDeleteIntent(swipedAway(UNOPENED))
                .build();
    }

    /** What the unopened notice's swipe reports in place of a conversation. */
    static final String UNOPENED = "unopened";

    /**
     * The notification was swiped away: the next text in that conversation starts a fresh one, not the old list again,
     * and the summary goes with the last of them.
     */
    synchronized void dismissed(String conv) {
        if (conv == null) return;
        if (UNOPENED.equals(conv)) unopened = 0;
        else {
            recent.remove(conv);
            cancel(conv);   // gone already; this keeps the system's list and the summary's in step
        }
        summarize();
    }

    /** The conversation is on screen: its notification goes away. */
    synchronized void clear(String conv) {
        if (conv == null) return;
        recent.remove(conv);
        cancel(conv);
        summarize();
    }

    synchronized void clearAll() {
        java.util.Set<String> up = shownConvs();
        recent.clear();
        unread.clear();
        cancelled.clear();
        cancelled.addAll(up);
        unopened = 0;
        nm.cancelAll();
    }

    static final int RELAY_ID = 4;

    /**
     * The relay phone's connection actually failed (no check-in for 10 minutes), or it came back. One notification
     * either way, replacing the previous one. Coming back never makes a sound.
     */
    void relayState(boolean online, long lastSeen) {
        if (Prefs.ALERT_OFF.equals(prefs.alertMode())) return;
        Intent intent = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(context, RELAY_ID, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        long minutes = Math.max(1, (System.currentTimeMillis() - lastSeen) / 60000);
        Notification n = new Notification.Builder(context, online ? CHANNEL_QUIET : channel())
                .setSmallIcon(R.drawable.ic_status)
                .setColor(Ui.BRAND.base)
                .setContentTitle(online ? "Relay phone is back" : "Relay phone offline")
                .setContentText(online ? "Texts are flowing again." : "No check-in for " + minutes + " min. Open Paigr and tap Reconnect, or check its Wi-Fi and power.")
                .setStyle(new Notification.BigTextStyle())
                .setCategory(Notification.CATEGORY_STATUS)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build();
        nm.notify(RELAY_ID, n);
    }

    private Notification build(String conv, List<JSONObject> list) {
        // The conversation as the latest message that opened here names it. One that didn't open names no one, and
        // never takes a name from the messages around it.
        JSONObject named = null;
        for (int i = list.size() - 1; i >= 0 && named == null; i--) {
            if (!list.get(i).optBoolean("unchecked", false)) named = list.get(i);
        }
        String name = named == null ? "" : named.optString("name", "");
        if (name.isEmpty() && named != null) name = named.optString("sender", "");
        if (name.isEmpty()) name = "Paigr";   // never empty: Android shows an unnamed sender as "You"
        boolean group = named != null && named.optBoolean("group", false);
        Person me = new Person.Builder().setName("You").build();
        Notification.MessagingStyle style = new Notification.MessagingStyle(me);
        if (group) {
            style.setConversationTitle(name);
            style.setGroupConversation(true);
        }
        for (JSONObject m : list) {
            String sender = senderOf(m);
            String text = Words.snippet(m.optString("text", ""));
            if (text.isEmpty()) text = label(m.optJSONObject("media"));
            Person from = new Person.Builder().setName(sender).setKey(sender).build();
            style.addMessage(new Notification.MessagingStyle.Message(text, m.optLong("ts", System.currentTimeMillis()), from));
        }
        return new Notification.Builder(context, channel())
                .setSmallIcon(R.drawable.ic_status)
                .setColor(Ui.BRAND.base)
                .setStyle(style)
                .setContentTitle(name)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setGroup(GROUP)
                .setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)   // the text alerts; the summary never does
                .setAutoCancel(true)
                .setOnlyAlertOnce(false)
                .setContentIntent(open(TEST.equals(conv) ? null : conv))
                .setDeleteIntent(swipedAway(conv))
                .setNumber(countOf(conv))
                .addExtras(extrasFor(conv))
                .build();
    }

    private static android.os.Bundle extrasFor(String conv) {
        android.os.Bundle b = new android.os.Bundle();
        b.putString(EXTRA_CONV, conv);
        return b;
    }

    private PendingIntent swipedAway(String conv) {
        Intent i = new Intent(context, Keepalive.class).setAction(Keepalive.ACTION_DISMISSED).putExtra(MainActivity.EXTRA_CONV, conv);
        return PendingIntent.getBroadcast(context, idFor(conv), i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Who a message is from, as it opened here: its own sender or name, or "Paigr" for one that didn't open. */
    static String senderOf(JSONObject m) {
        if (m.optBoolean("unchecked", false)) return "Paigr";
        String s = m.optString("sender", "");
        if (s.isEmpty()) s = m.optString("name", "");
        return s.isEmpty() ? "Paigr" : s;
    }

    private Notification summary() {
        java.util.Set<String> shown = shownConvs();
        int n = 0;
        for (String conv : shown) n += countOf(conv);
        String text;
        if (shown.isEmpty()) text = "A message came in that couldn't be checked on this phone.";
        else text = n + (n == 1 ? " unread message" : " unread messages") + " in " + shown.size() + (shown.size() == 1 ? " conversation" : " conversations");
        return new Notification.Builder(context, channel())
                .setSmallIcon(R.drawable.ic_status)
                .setColor(Ui.BRAND.base)
                .setContentTitle("Paigr")
                .setContentText(text)
                .setGroup(GROUP)
                .setGroupSummary(true)
                .setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open(null))
                .build();
    }

    private PendingIntent open(String conv) {
        Intent intent = new Intent(context, MainActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (conv != null) intent.putExtra(MainActivity.EXTRA_CONV, conv);
        return PendingIntent.getActivity(context, conv == null ? 0 : idFor(conv), intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static int idFor(String conv) {
        return 1000 + (conv.hashCode() & 0x7fffffff) % 1_000_000;
    }

    static String label(JSONObject media) {
        if (media == null) return "Attachment";
        String t = media.optString("mime", "").toLowerCase();
        if (t.startsWith("image/")) return "Picture";
        if (t.startsWith("video/")) return "Video";
        if (t.startsWith("audio/")) return "Voice message";
        return "Attachment";
    }
}
