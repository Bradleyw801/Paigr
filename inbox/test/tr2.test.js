// Checks web/tr2.js (WebCrypto) against test/vectors.json (Node's crypto module).
const assert = require("assert");
const TR2 = require("../web/tr2.js");
const V = require("./vectors.json");
const fromHex = (h) => Uint8Array.from(Buffer.from(h, "hex"));
const enc = new TextEncoder(), dec = new TextDecoder();

(async () => {
  // reactions and quoted replies, as texts carry them: the page reads and writes exactly what the apps do
  const W = TR2.words;
  for (const c of V.words.parse) {
    assert.deepStrictEqual(W.reaction(c.text), c.reaction, "reaction " + JSON.stringify(c.text));
    assert.deepStrictEqual(W.quote(c.text), c.quote, "quote " + JSON.stringify(c.text));
    assert.strictEqual(W.snippet(c.text), c.snippet, "snippet " + JSON.stringify(c.text));
  }
  for (const c of V.words.reactionText) assert.strictEqual(W.reactionText(c.emoji, c.to, c.kind, c.removed), c.text);
  for (const c of V.words.quoteText) assert.strictEqual(W.quoteText(c.original, c.kind, c.reply), c.text);
  for (const [a, b, same] of V.words.sameWords) assert.strictEqual(W.sameWords(a, b), same, JSON.stringify([a, b]));

  const keys = await TR2.inboxKeys(fromHex(V.key));
  assert.strictEqual(await TR2.convId(keys, V.convId.local), V.convId.wire);
  assert.strictEqual(await TR2.fpId(keys, V.fpId.local), V.fpId.wire);
  for (const m of V.match) {
    assert.strictEqual(TR2.matchText(m.text, m.mime), m.matchText, "matchText " + JSON.stringify(m.text));
    assert.strictEqual(await TR2.matchToken(keys, m.dir, m.text, m.mime), m.mt, "mt " + JSON.stringify(m.text));
  }

  // what each seal is bound to: a conversation, or one file of it
  const A = V.aads;
  assert.strictEqual(TR2.AAD.message(A.conv, "in"), A.messageIn);
  assert.strictEqual(TR2.AAD.message(A.conv, "out"), A.messageOut);
  assert.strictEqual(TR2.AAD.name(A.conv), A.name);
  assert.strictEqual(TR2.AAD.file(A.conv, A.ts, A.fp, "original"), A.fileOriginal);
  assert.strictEqual(TR2.AAD.file(A.conv, A.shortTs, A.fp, "preview"), A.filePreviewShortTs);
  assert.strictEqual(TR2.AAD.merge(A.conv), A.merge);
  assert.strictEqual(TR2.AAD.out(A.conv), A.out);
  assert.deepStrictEqual(TR2.fileOf(A.path), { tid: "00112233445566778899aabbccddeeff", conv: A.conv, aad: A.fileOriginal,
    stem: "1700000000123-" + A.fp, kind: "o" });
  assert.strictEqual(TR2.stemOf(A.ts, A.fp), "1700000000123-" + A.fp);
  assert.strictEqual(TR2.stemOf(A.shortTs, A.fp), "0000000012345-" + A.fp);
  assert.strictEqual(TR2.fileOf("/media/v2/x/y.enc"), null);

  // sealing with the vector's IV gives the vector exactly; opening it gives the payload back
  const iv = fromHex(V.iv);
  assert.strictEqual(V.message.aad, A.messageIn);
  assert.strictEqual(await TR2.seal(keys.enc, enc.encode(V.message.plain), V.message.aad, iv), V.message.sealed);
  assert.strictEqual(dec.decode(await TR2.open(keys.enc, V.message.sealed, A.messageIn)), V.message.plain);
  await assert.rejects(TR2.open(keys.enc, V.message.sealed, A.name), "a different AAD must not open it");
  await assert.rejects(TR2.open(keys.enc, V.message.sealed, A.messageOut), "nor as the other direction");
  await assert.rejects(TR2.open(keys.enc, V.message.sealed, TR2.AAD.message("0".repeat(32), "in")), "nor another conversation's");
  const tampered = V.message.sealed.slice(0, -2) + (V.message.sealed.endsWith("A") ? "BB" : "AA");
  await assert.rejects(TR2.open(keys.enc, tampered, A.messageIn));
  const p = JSON.parse(V.message.plain);
  assert.strictEqual(TR2.stemOf(p.ts, p.fp), TR2.fileOf(A.path).stem, "a message's payload names its files' stem");
  // a reply request, a merge record and the status note, each with its own AAD
  for (const [v, aad] of [[V.request, TR2.AAD.out(A.conv)], [V.merge, TR2.AAD.merge(A.conv)], [V.note, TR2.AAD.note]]) {
    assert.strictEqual(v.aad, aad);
    assert.strictEqual(await TR2.seal(keys.enc, enc.encode(v.plain), aad, iv), v.sealed);
    assert.deepStrictEqual(await TR2.openJson(keys.enc, v.sealed, aad), JSON.parse(v.plain));
    await assert.rejects(TR2.open(keys.enc, v.sealed, A.messageIn));
  }
  await assert.rejects(TR2.open(keys.enc, V.merge.sealed, TR2.AAD.merge(V.merge.from)), "a merge record is bound to its into");
  const file = fromHex(V.file.plainHex);
  assert.strictEqual(V.file.aad, A.fileOriginal);
  assert.strictEqual(Buffer.from(await TR2.sealBytes(keys.enc, file, V.file.aad, iv)).toString("hex"), V.file.sealedHex);
  assert.deepStrictEqual(Buffer.from(await TR2.openBytes(keys.enc, fromHex(V.file.sealedHex), V.file.aad)), Buffer.from(file));
  await assert.rejects(TR2.openBytes(keys.enc, fromHex(V.file.sealedHex), A.filePreviewShortTs));

  // a random IV each time: the same text never looks the same twice
  const a = await TR2.seal(keys.enc, enc.encode("same"), "tr2:m"), b = await TR2.seal(keys.enc, enc.encode("same"), "tr2:m");
  assert.notStrictEqual(a, b);

  // linking
  assert.strictEqual(TR2.normCode(V.link.code), V.link.norm);
  assert.strictEqual(TR2.normCode(" k7q4 m2xd 9prt "), V.link.norm);
  const link = await TR2.linkSecrets(V.link.code);
  assert.strictEqual(link.lookup, V.link.lookup);
  const box = await TR2.openJson(link.boxKey, V.link.box, "tr2:box");
  assert.strictEqual(box.key, TR2.b64u(fromHex(V.key)));
  assert.deepStrictEqual(TR2.parseLink("https://relay--x.apps.example.com/#link=K7Q4M2XD9PRT"), { server: "https://relay--x.apps.example.com", code: "K7Q4M2XD9PRT" });
  assert.deepStrictEqual(TR2.parseLink("TEXTRELAY2:relay--x.apps.example.com/K7Q4M2XD9PRT"), { server: "https://relay--x.apps.example.com", code: "K7Q4M2XD9PRT" });
  assert.deepStrictEqual(TR2.parseLink("textrelay2:127.0.0.1:8790/k7q4m2xd9prt"), { server: "http://127.0.0.1:8790", code: "K7Q4M2XD9PRT" });
  // an onion server: reached through Tor, over http (Tor encrypts it end to end)
  const onion = "abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvwx.onion";
  assert.deepStrictEqual(TR2.parseLink("TEXTRELAY2:" + onion + "/K7Q4M2XD9PRT"), { server: "http://" + onion, code: "K7Q4M2XD9PRT" });
  assert.deepStrictEqual(TR2.parseLink(TR2.linkQr("http://" + onion, "K7Q4M2XD9PRT")), { server: "http://" + onion, code: "K7Q4M2XD9PRT" });
  assert.strictEqual(TR2.parseLink("TEXTRELAY2:onion.example.com/K7Q4M2XD9PRT").server, "https://onion.example.com");
  assert.strictEqual(TR2.linkQr("https://Relay--x.apps.example.com/", "k7q4m2xd9prt"), "TEXTRELAY2:relay--x.apps.example.com/K7Q4M2XD9PRT");
  assert.ok(!/^https?:/i.test(TR2.linkQr("https://a.example", "K7Q4M2XD9PRT")), "the QR code is not a web address");
  assert.strictEqual(TR2.parseLink("TEXTRELAY2:a.example/K7Q4M2XD9PRT/x"), null);
  assert.deepStrictEqual(TR2.parseLink("k7q4-m2xd-9prt"), { server: null, code: "K7Q4M2XD9PRT" });
  assert.strictEqual(TR2.parseLink("TEXTRELAY:LINK:K7Q4M2XD9PRT"), null);
  assert.strictEqual(TR2.parseLink("https://evil.example/#link=SHORT"), null);
  assert.strictEqual(TR2.prettyCode("K7Q4M2XD9PRT"), "K7Q4-M2XD-9PRT");
  const c = TR2.newCode();
  assert.ok(/^[0-9A-HJKMNP-TV-Z]{12}$/.test(c), c);
  assert.ok(TR2.parseLink(TR2.linkUrl("https://a.example", c)));
  assert.deepStrictEqual(TR2.parseLink(TR2.linkQr("https://a.example", c)), { server: "https://a.example", code: c });

  // a phone's own secrets
  const phone = await TR2.phoneSecrets(V.phone.androidId, V.phone.role);
  assert.strictEqual(phone.tag, V.phone.tag);
  const wrap = await TR2.openJson(phone.wrapKey, V.phone.wrap, "tr2:wrap");
  assert.strictEqual(wrap.token, "tok");
  assert.notStrictEqual((await TR2.phoneSecrets(V.phone.androidId, "inbox")).tag, V.phone.tag);

  assert.strictEqual(TR2.newToken().length, 43);
  assert.ok(/^[0-9a-f]{32}$/.test(TR2.newTid()));

  // a sealed value has one spelling: padding, or other bits in the unused part of its last character, never opens
  {
    const keys = await TR2.inboxKeys(TR2.newKey());
    let e = null;
    for (let i = 0; i < 6 && !e; i++) {
      const x = await TR2.sealJson(keys.enc, { text: "see you soon" + " ".repeat(i), ts: 1700000000000, fp: "0123456789abcdef" }, TR2.AAD.message("c-mom", "in"));
      if ((x.length - 3) % 4 === 2) e = x;   // one byte in the last group: four unused bits
    }
    assert.ok(e);
    const alpha = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    let others = 0;
    for (const c of alpha) {
      const v = e.slice(0, -1) + c;
      if (v === e) continue;
      try { await TR2.openJson(keys.enc, v, TR2.AAD.message("c-mom", "in")); others++; } catch { /* refused */ }
    }
    assert.strictEqual(others, 0, others + " other spellings open");
    await assert.rejects(TR2.openJson(keys.enc, e + "==", TR2.AAD.message("c-mom", "in")));
    assert.ok((await TR2.openJson(keys.enc, e, TR2.AAD.message("c-mom", "in"))).text.startsWith("see you soon"), "the one spelling opens");
    assert.deepStrictEqual(Array.from(TR2.unb64u(TR2.b64u(new Uint8Array([1, 2, 3, 250])))), [1, 2, 3, 250]);
    assert.throws(() => TR2.unb64u("AQIDBA="));
  }
  console.log("tr2: all checks passed");
})().catch(e => { console.error(e); process.exit(1); });
