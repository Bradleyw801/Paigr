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
 * make a sound and can pop up; quiet ones only appear in the shade; with alerts off there are none.
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
        for (String conv : touched) nm.notify(idFor(conv), build(conv, recent.get(conv)));
        if (unopened > unopenedBefore) nm.notify(UNOPENED_ID, unopenedNotice());
        if (recent.size() > 1) nm.notify(SUMMARY_ID, summary());
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
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open(null))
                .build();
    }

    /** The conversation is on screen: its notification goes away. */
    synchronized void clear(String conv) {
        if (conv == null) return;
        recent.remove(conv);
        nm.cancel(idFor(conv));
        if (recent.size() <= 1) nm.cancel(SUMMARY_ID);
    }

    synchronized void clearAll() {
        recent.clear();
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
                .setShortcutId(conv)
                .setAutoCancel(true)
                .setOnlyAlertOnce(false)
                .setContentIntent(open(conv))
                .setNumber(list.size())
                .build();
    }

    /** Who a message is from, as it opened here: its own sender or name, or "Paigr" for one that didn't open. */
    static String senderOf(JSONObject m) {
        if (m.optBoolean("unchecked", false)) return "Paigr";
        String s = m.optString("sender", "");
        if (s.isEmpty()) s = m.optString("name", "");
        return s.isEmpty() ? "Paigr" : s;
    }

    private Notification summary() {
        int n = 0;
        for (List<JSONObject> l : recent.values()) n += l.size();
        return new Notification.Builder(context, channel())
                .setSmallIcon(R.drawable.ic_status)
                .setColor(Ui.BRAND.base)
                .setContentTitle("Paigr")
                .setContentText(n + " new messages in " + recent.size() + " conversations")
                .setGroup(GROUP)
                .setGroupSummary(true)
                .setAutoCancel(true)
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
