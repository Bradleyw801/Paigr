package com.textrelay.texts;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Reactions and quoted replies read and written the same here as on the web page (test/vectors.json, `words`). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class WordsTest {
    private static JSONObject vectors() throws Exception {
        try (java.io.InputStream in = WordsTest.class.getResourceAsStream("/vectors.json")) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return new JSONObject(out.toString("UTF-8")).getJSONObject("words");
        }
    }

    @Test
    public void readsEveryFormThePageDoes() throws Exception {
        JSONArray cases = vectors().getJSONArray("parse");
        assertTrue(cases.length() > 30);
        for (int i = 0; i < cases.length(); i++) {
            JSONObject c = cases.getJSONObject(i);
            String text = c.getString("text");
            Words.Reaction r = Words.reaction(text);
            if (c.isNull("reaction")) assertNull(text, r);
            else {
                JSONObject want = c.getJSONObject("reaction");
                assertNotNull(text, r);
                assertEquals(text, want.getString("emoji"), r.emoji);
                assertEquals(text, want.isNull("to") ? null : want.getString("to"), r.to);
                assertEquals(text, want.isNull("kind") ? null : want.getString("kind"), r.kind);
                assertEquals(text, want.getBoolean("removed"), r.removed);
            }
            Words.Quote q = Words.quote(text);
            if (c.isNull("quote")) assertNull(text, q);
            else {
                assertNotNull(text, q);
                assertEquals(text, c.getJSONObject("quote").getString("quoted"), q.quoted);
                assertEquals(text, c.getJSONObject("quote").getString("body"), q.body);
            }
            assertEquals(text, c.getString("snippet"), Words.snippet(text));
        }
    }

    @Test
    public void writesWhatThePageWrites() throws Exception {
        JSONObject v = vectors();
        JSONArray rt = v.getJSONArray("reactionText");
        for (int i = 0; i < rt.length(); i++) {
            JSONObject c = rt.getJSONObject(i);
            String got = Words.reactionText(c.getString("emoji"), c.isNull("to") ? null : c.getString("to"), c.isNull("kind") ? null : c.getString("kind"), c.getBoolean("removed"));
            assertEquals(c.getString("text"), got);
            // and what it wrote reads back as itself
            Words.Reaction back = Words.reaction(got);
            assertNotNull(got, back);
            assertEquals(c.getString("emoji"), back.emoji);
            assertEquals(c.getBoolean("removed"), back.removed);
        }
        JSONArray qt = v.getJSONArray("quoteText");
        for (int i = 0; i < qt.length(); i++) {
            JSONObject c = qt.getJSONObject(i);
            String got = Words.quoteText(c.getString("original"), c.isNull("kind") ? null : c.getString("kind"), c.getString("reply"));
            assertEquals(c.getString("text"), got);
            assertNotNull(Words.quote(got));
        }
        JSONArray sw = v.getJSONArray("sameWords");
        for (int i = 0; i < sw.length(); i++) {
            JSONArray c = sw.getJSONArray(i);
            assertEquals(c.toString(), c.getBoolean(2), Words.sameWords(c.getString(0), c.getString(1)));
        }
    }

    @Test
    public void emojiAndKinds() {
        assertTrue(Words.isEmoji("👍"));
        assertTrue(Words.isEmoji(Words.HEART));
        assertFalse(Words.isEmoji("ok"));
        assertFalse(Words.isEmoji(""));
        Model.Media m = new Model.Media();
        m.mime = "image/gif";
        assertEquals("image", Words.kindOf(m));
        m.mime = "video/mp4";
        assertEquals("video", Words.kindOf(m));
        m.mime = "application/pdf";
        assertEquals("file", Words.kindOf(m));
        assertNull(Words.kindOf(null));
    }
}
