package com.textrelay.relay;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/** The same test vectors the web page's tr2.js is checked against, made by Node's own crypto module. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class CryptoTest {

    static JSONObject vectors() throws Exception {
        try (InputStream in = CryptoTest.class.getResourceAsStream("/vectors.json")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new JSONObject(out.toString("UTF-8"));
        }
    }

    static byte[] fromHex(String h) {
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    @Test
    public void keysIdsAndMatchTokensAreTheSameAsEverywhereElse() throws Exception {
        JSONObject v = vectors();
        byte[] k = fromHex(v.getString("key"));
        assertEquals(v.getString("enc"), Crypto.hex(Crypto.hkdf(k, "textrelay v2 enc")));
        assertEquals(v.getString("mac"), Crypto.hex(Crypto.hkdf(k, "textrelay v2 mac")));
        Link link = new Link("https://inbox.example", "00112233445566778899aabbccddeeff", Crypto.newToken(), k);
        assertEquals(v.getJSONObject("convId").getString("wire"), link.convId(v.getJSONObject("convId").getString("local")));
        assertEquals(v.getJSONObject("fpId").getString("wire"), link.fpId(v.getJSONObject("fpId").getString("local")));
        JSONArray cases = v.getJSONArray("match");
        for (int i = 0; i < cases.length(); i++) {
            JSONObject c = cases.getJSONObject(i);
            String mime = c.getString("mime").isEmpty() ? null : c.getString("mime");
            assertEquals("matchText of " + c.getString("text"), c.getString("matchText"), Crypto.matchText(c.getString("text"), mime));
            assertEquals("mt of " + c.getString("text"), c.getString("mt"), link.matchToken(c.getString("dir"), c.getString("text"), mime));
        }
    }

    @Test
    public void eachSealIsBoundToTheSamePlaceAsOnTheOtherDevices() throws Exception {
        JSONObject a = vectors().getJSONObject("aads");
        String conv = a.getString("conv"), fp = a.getString("fp");
        assertEquals(a.getString("messageIn"), Crypto.aadMessage(conv, "in"));
        assertEquals(a.getString("messageOut"), Crypto.aadMessage(conv, "out"));
        assertEquals(a.getString("name"), Crypto.aadName(conv));
        assertEquals(a.getString("fileOriginal"), Crypto.aadFile(conv, a.getLong("ts"), fp, "original"));
        assertEquals(a.getString("filePreviewShortTs"), Crypto.aadFile(conv, a.getLong("shortTs"), fp, "preview"));
        assertEquals(a.getString("merge"), Crypto.aadMerge(conv));
        assertEquals(a.getString("out"), Crypto.aadOut(conv));
    }

    @Test
    public void sealingGivesTheVectorAndOpensOnlyWithTheRightKeyAndLabel() throws Exception {
        JSONObject v = vectors();
        byte[] enc = fromHex(v.getString("enc"));
        byte[] iv = fromHex(v.getString("iv"));
        JSONObject m = v.getJSONObject("message");
        String aad = m.getString("aad");
        byte[] plain = m.getString("plain").getBytes(StandardCharsets.UTF_8);
        assertEquals(m.getString("sealed"), Crypto.seal(enc, plain, aad, iv));
        assertArrayEquals(plain, Crypto.open(enc, m.getString("sealed"), aad));
        JSONObject opened = Crypto.openJson(enc, m.getString("sealed"), aad);
        assertEquals("Hi 👋 there", opened.getString("text"));
        assertEquals(640, opened.getJSONObject("media").getInt("w"));
        String conv = v.getJSONObject("aads").getString("conv");
        assertEquals(Crypto.pad13(opened.getLong("ts")) + "-" + opened.getString("fp"),
                v.getJSONObject("aads").getString("path").replaceAll(".*/([0-9]{13}-[0-9a-f]+)-o\\.enc$", "$1"));
        for (String other : new String[] { Crypto.aadName(conv), Crypto.aadMessage(conv, "out"), Crypto.aadMessage("00000000000000000000000000000000", "in") }) {
            try {
                Crypto.open(enc, m.getString("sealed"), other);
                fail("another label, or another conversation's, must not open it: " + other);
            } catch (GeneralSecurityException expected) {
                // good
            }
        }
        try {
            Crypto.open(Crypto.random(32), m.getString("sealed"), aad);
            fail("another key must not open it");
        } catch (GeneralSecurityException expected) {
            // good
        }
        JSONObject f = v.getJSONObject("file");
        String faad = f.getString("aad");
        assertEquals(f.getString("sealedHex"), Crypto.hex(Crypto.sealBytes(enc, fromHex(f.getString("plainHex")), faad, iv)));
        assertArrayEquals(fromHex(f.getString("plainHex")), Crypto.openBytes(enc, fromHex(f.getString("sealedHex")), faad));
        assertNotEquals(Crypto.seal(enc, plain, aad), Crypto.seal(enc, plain, aad));   // a fresh IV every time
        // a reply request, a merge record and the status note, each sealed with its own label
        String[][] sealed = { { "request", Crypto.aadOut(conv) }, { "merge", Crypto.aadMerge(conv) }, { "note", Crypto.AAD_NOTE } };
        for (String[] x : sealed) {
            JSONObject o = v.getJSONObject(x[0]);
            assertEquals(o.getString("aad"), x[1]);
            assertEquals(o.getString("sealed"), Crypto.seal(enc, o.getString("plain").getBytes(StandardCharsets.UTF_8), x[1], iv));
            assertEquals(new JSONObject(o.getString("plain")).toString(), Crypto.openJson(enc, o.getString("sealed"), x[1]).toString());
        }
    }

    @Test
    public void aCodeAndAPhoneGiveTheSameSecretsAsOnTheOtherDevices() throws Exception {
        JSONObject v = vectors();
        JSONObject l = v.getJSONObject("link");
        assertEquals(l.getString("norm"), Crypto.normCode(l.getString("code")));
        Crypto.LinkSecrets s = Crypto.linkSecrets(l.getString("code"));
        assertEquals(l.getString("lookup"), s.lookup);
        assertEquals(l.getString("boxKey"), Crypto.hex(s.boxKey));
        JSONObject box = Crypto.openJson(s.boxKey, l.getString("box"), "tr2:box");
        assertEquals(Crypto.b64u(fromHex(v.getString("key"))), box.getString("key"));
        assertEquals(s.lookup, Crypto.linkSecrets(" k7q4 m2xd 9prt ").lookup);

        JSONObject p = v.getJSONObject("phone");
        Crypto.PhoneSecrets ps = Crypto.phoneSecrets(p.getString("androidId"), p.getString("role"));
        assertEquals(p.getString("tag"), ps.tag);
        assertEquals(p.getString("wrapKey"), Crypto.hex(ps.wrapKey));
        assertEquals("tok", Crypto.openJson(ps.wrapKey, p.getString("wrap"), "tr2:wrap").getString("token"));

        String code = Crypto.newCode();
        assertTrue(code, code.matches("[0-9A-HJKMNP-TV-Z]{12}"));
        assertEquals("TEXTRELAY2:inbox.example/" + code, Crypto.linkQr("https://Inbox.example/", code));
        assertEquals("TEXTRELAY2:127.0.0.1:8790/" + code, Crypto.linkQr("http://127.0.0.1:8790", code.toLowerCase(java.util.Locale.US)));
        assertEquals(43, Crypto.newToken().length());
        assertTrue(Crypto.newTid().matches("[0-9a-f]{32}"));
    }
}
