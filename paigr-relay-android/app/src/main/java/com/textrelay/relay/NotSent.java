package com.textrelay.relay;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Why a reply wasn't sent. A private inbox is told only the code: each device puts it in its own words (the same as
 * these), so nothing in the server's words ever shows in a conversation. The first version's inbox shows the words.
 */
final class NotSent {
    static final String STOPPED = "stopped";
    static final String OLD = "old";
    static final String FUTURE = "future";
    static final String AGAIN = "again";
    static final String UNKNOWN_CONV = "unknown_conv";
    static final String UNCHECKED = "unchecked";
    static final String NO_FALLBACK = "no_fallback";
    static final String GROUP = "group";
    static final String NO_NUMBER = "no_number";
    static final String SMS_FAILED = "sms_failed";
    static final String NO_ATTACH = "no_attach";
    static final String ATTACH_PERM = "attach_perm";
    static final String TOO_BIG = "too_big";
    static final String MMS_FAILED = "mms_failed";
    static final String ATTACH_FETCH = "attach_fetch";

    private static final Map<String, String> WORDS = new HashMap<>();
    static {
        WORDS.put(STOPPED, "The relay phone stopped while sending this. Check the conversation on that phone before sending it again.");
        WORDS.put(OLD, "This reply is too old to send now. Send it again if you still want to.");
        WORDS.put(FUTURE, "This reply's time is off, so the relay phone didn't send it. Send it again.");
        WORDS.put(AGAIN, "This reply was already handled once, so it wasn't sent again.");
        WORDS.put(UNKNOWN_CONV, "The relay phone doesn't know this conversation, so it didn't send this reply.");
        WORDS.put(UNCHECKED, "The relay phone couldn't check this reply, so it didn't send it. Send it again.");
        WORDS.put(NO_FALLBACK, "The relay phone has no open notification for this conversation, and SMS fallback is not allowed yet (Relay app, step 5). It works again after their next text.");
        WORDS.put(GROUP, "The relay phone has no open notification for this conversation. Group conversations can't go as SMS. It works again after their next text.");
        WORDS.put(NO_NUMBER, "The relay phone has no open notification for this conversation, and no phone number is known for it, so it can't go as SMS. It works again after their next text.");
        WORDS.put(SMS_FAILED, "SMS fallback failed on the relay phone.");
        WORDS.put(NO_ATTACH, "The relay phone can't send an attachment to this conversation: Messages' reply takes none, and no number is known for MMS.");
        WORDS.put(ATTACH_PERM, "Attachments go out as MMS from the relay phone, which needs SMS fallback allowed (Relay app, step 5).");
        WORDS.put(TOO_BIG, "Too big to send as MMS from the relay phone.");
        WORDS.put(MMS_FAILED, "MMS failed on the relay phone.");
        WORDS.put(ATTACH_FETCH, "The relay phone couldn't fetch the attachment from the inbox.");
    }

    private NotSent() {}

    static String words(String code) {
        String w = code != null ? WORDS.get(code) : null;
        return w != null ? w : "The relay phone could not send it.";
    }

    /** A failed reply's report: its code for a private inbox, its words for the first version's. */
    static JSONObject report(String id, String code) {
        return Json.obj("id", id, "status", "failed", "code", code, "error", words(code));
    }
}
