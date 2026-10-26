// inbox2.js, driven the way Relay, Texts and the web page drive it: real sealing (web/tr2.js), keyed ids, tokens.
const assert = require("assert");
const path = require("path");
const TR2 = require("../web/tr2.js");
const { caller2, col, rings, pushes, files, tickets, issued, base } = require("./harness");
const v2 = caller2(path.join(__dirname, "../functions/inbox2.js"));
const watch = caller2(path.join(__dirname, "../functions/watch2.js"));
const sliced = (() => {
  const fs = require("fs");
  const src = fs.readFileSync(path.join(__dirname, "../functions/inbox2.js"), "utf8").replace("const MERGE_BUDGET_MS = 4500;", "const MERGE_BUDGET_MS = 1;");
  const tmp = path.join(require("os").tmpdir(), "inbox2-sliced-" + process.pid + ".js");
  fs.writeFileSync(tmp, src);
  return caller2(tmp);
})();
const realNow = Date.now.bind(Date);
let offset = 0;
Date.now = () => realNow() + offset;
const H = (token) => ({ "x-relay-token": token });
const pad13 = (n) => String(n).padStart(13, "0");
const fp = (n) => n.toString(16).padStart(16, "0");

/** A device's id as the inbox lists it (the device list carries no names the server could read). */
const idOf = (dev) => require("crypto").createHash("sha256").update(dev.token).digest("hex").slice(0, 12);

function phone(role, label) {
  return { role, label: label || role, token: TR2.newToken(), tid: null, keys: null };
}
const call = (p, method, route, body, query) => v2(method, route, body, query, p ? H(p.token) : {});

// ---------------------------------------------------------------- linking, as the apps do it

async function asks(dev) {
  const code = TR2.newCode();
  const s = await TR2.linkSecrets(code);
  const r = await v2("POST", "/v2/link/ask", { lookup: s.lookup, token: dev.token, role: dev.role, label: dev.label });
  assert.strictEqual(r._status, 200, JSON.stringify(r));
  return Object.assign({ code }, s);
}

async function waits(dev, offer) {
  const r = await v2("POST", "/v2/link/wait", { lookup: offer.lookup, token: dev.token });
  if (r._status !== 200) return r;
  const box = await TR2.openJson(offer.boxKey, r.answer, "tr2:box");
  assert.strictEqual(box.tid, r.tid);
  dev.tid = r.tid;
  dev.keys = await TR2.inboxKeys(TR2.unb64u(box.key));
  return r;
}

async function invites(dev, role) {
  const code = TR2.newCode();
  const s = await TR2.linkSecrets(code);
  const box = await TR2.sealJson(s.boxKey, { v: 2, tid: dev.tid, key: TR2.b64u(dev.keys.raw) }, "tr2:box");
  const r = await call(dev, "POST", "/v2/link/invite", { lookup: s.lookup, box, role: role || "inbox" });
  assert.strictEqual(r._status, 200, JSON.stringify(r));
  return Object.assign({ code }, s);
}

/** What Texts does with a code it scanned: join an invite, or bring an asking device in (starting an inbox if need be). */
async function scans(dev, code, opts) {
  const s = await TR2.linkSecrets(code);
  const claim = dev.tid
    ? await v2("POST", "/v2/link/claim", { lookup: s.lookup }, null, H(dev.token))
    : await v2("POST", "/v2/link/claim", { lookup: s.lookup, token: dev.token, label: dev.label });
  if (claim._status !== 200) return claim;
  if (claim.kind === "invite") {
    const box = await TR2.openJson(s.boxKey, claim.box, "tr2:box");
    dev.tid = box.tid;
    dev.keys = await TR2.inboxKeys(TR2.unb64u(box.key));
    return claim;
  }
  if (opts && opts.onAsk) opts.onAsk(claim);
  let raw = dev.keys && dev.keys.raw, tid = dev.tid, create = null;
  if (!tid) {
    raw = TR2.newKey();
    tid = TR2.newTid();
    create = { tid, token: dev.token, label: dev.label };
  }
  const answer = await TR2.sealJson(s.boxKey, { v: 2, tid, key: TR2.b64u(raw) }, "tr2:box");
  const r = create
    ? await v2("POST", "/v2/link/adopt", { lookup: s.lookup, answer, create })
    : await v2("POST", "/v2/link/adopt", { lookup: s.lookup, answer }, null, H(dev.token));
  if (r._status === 200 && create) {
    dev.tid = tid;
    dev.keys = await TR2.inboxKeys(raw);
  }
  return Object.assign({ claim }, r);
}

// ---------------------------------------------------------------- the relay phone's side of a message

async function item(relay, x) {
  const k = relay.keys;
  const dir = x.dir || "in";
  const out = { conv: await TR2.convId(k, x.conv), fp: await TR2.fpId(k, x.fp), ts: x.ts, dir, src: x.src || "notif" };
  if (x.read) out.read = true;
  if (x.history) out.history = true;
  if (x.status) out.status = x.status;
  if (x.wantMedia) out.wantMedia = true;
  const media = x.media ? { mime: x.media.mime, w: x.media.w || 0, h: x.media.h || 0, bytes: x.media.bytes || 0 } : undefined;
  out.e = await TR2.sealJson(k.enc, { text: x.text || "", sender: x.sender || "", name: x.name || "", group: !!x.group, ts: x.ts, fp: out.fp, media },
    TR2.AAD.message(out.conv, dir));
  if (x.name) out.ne = await TR2.sealJson(k.enc, { name: x.name, group: !!x.group }, TR2.AAD.name(out.conv));
  const mt = await TR2.matchToken(k, dir, x.text, x.media && x.media.mime);
  if (mt) out.mt = mt;
  if (x.media) {
    out.media = {};
    if (x.media.path) out.media.path = x.media.path;
    if (x.media.preview) out.media.preview = x.media.preview;
  }
  return out;
}

async function ingest(relay, items, extra) {
  const wire = [];
  for (const x of items) wire.push(await item(relay, x));
  return call(relay, "POST", "/v2/relay/ingest", Object.assign({ items: wire }, extra || {}));
}

async function send(dev, conv, text) {
  const wconv = await TR2.convId(dev.keys, conv);
  const ts = Date.now();
  const e = await TR2.sealJson(dev.keys.enc, { text, ts }, TR2.AAD.message(wconv, "out"));
  const o = await TR2.sealJson(dev.keys.enc, { text, n: TR2.b64u(TR2.random(16)), ts }, TR2.AAD.out(wconv));
  return call(dev, "POST", "/v2/send", { conv: wconv, e, o, mt: await TR2.matchToken(dev.keys, "out", text, "") });
}

const W = (dev, conv) => TR2.convId(dev.keys, conv);
const key = async (dev, ts, n) => "m:" + pad13(ts) + ":" + await TR2.fpId(dev.keys, fp(n));
const messagesOf = (tid) => [...col("v2-messages").entries()].filter(([k]) => k.startsWith(tid + "/")).map(([, v]) => v);
const convOf = async (dev, conv) => col("v2-convs").get(dev.tid + "/" + await W(dev, conv));
const open = (dev, sealed, aad) => TR2.openJson(dev.keys.enc, sealed, aad);
async function textsOf(dev, conv) {
  const wc = await W(dev, conv);
  const out = [];
  for (const m of messagesOf(dev.tid).filter(m => m.conv === wc)) out.push((await open(dev, m.e, TR2.AAD.message(wc, m.dir))).text);
  return out;
}
const mediaPath = (tid, wconv, ts, wfp, kind) => "/media/v2/" + tid + "/" + wconv + "/" + pad13(ts) + "-" + wfp + "-" + (kind === "preview" ? "p" : "o") + ".enc";

(async () => {
  // ================================================================ 1. two brand-new phones, one scan
  const relay = phone("relay", "Home phone"), texts = phone("inbox", "Pocket phone");
  const offer = await asks(relay);
  assert.strictEqual((await waits(relay, offer))._status, 202, "nobody has scanned yet");
  // the code read out loud, typed with dashes and lower case, works the same
  const typed = offer.code.toLowerCase().replace(/(.{4})/g, "$1-").slice(0, 14);
  let asked = null;
  const linked = await scans(texts, typed, { onAsk: c => { asked = c; } });
  assert.strictEqual(linked._status, 200, JSON.stringify(linked));
  assert.strictEqual(asked.kind, "ask"); assert.strictEqual(asked.role, "relay"); assert.strictEqual(asked.linked, false);
  assert.strictEqual(linked.created, true);
  assert.strictEqual((await waits(relay, offer))._status, 200);
  assert.strictEqual(relay.tid, texts.tid);
  assert.deepStrictEqual(relay.keys.raw, texts.keys.raw);
  let r = await call(relay, "GET", "/v2/ping");
  assert.strictEqual(r.role, "relay"); assert.strictEqual(r.tid, relay.tid);
  r = await call(texts, "GET", "/v2/ping");
  assert.strictEqual(r.role, "inbox");
  // the answer can be fetched again (a reply lost on the way), but the code is used up
  assert.strictEqual((await waits(relay, offer))._status, 200);
  assert.strictEqual((await scans(phone("inbox"), offer.code))._status, 404);
  // the server never held the inbox key, a token, or the code: only hashes and sealed boxes
  const everything = JSON.stringify([...col("v2-offers").values(), ...col("v2-devices").values(), ...col("v2-tenants").values()]);
  assert.ok(!everything.includes(TR2.b64u(texts.keys.raw)));
  assert.ok(!everything.includes(relay.token) && !everything.includes(texts.token));
  assert.ok(!everything.includes(offer.code));
  // roles are kept apart
  assert.strictEqual((await call(texts, "POST", "/v2/relay/ingest", { items: [] }))._status, 403);
  assert.strictEqual((await call(relay, "GET", "/v2/inbox"))._status, 403);
  assert.strictEqual((await call(phone("inbox"), "GET", "/v2/inbox")).unlinked, true);
  assert.strictEqual((await v2("GET", "/v2/inbox", null, null, { "x-relay-token": "short" }))._status, 403);

  // ================================================================ 2. dedup, as in v1, on match tokens
  assert.strictEqual((await call(texts, "POST", "/v2/push/register", { subscriptionId: "apppush_test1" })).count, 1);
  const t0 = Date.now() - 3600000;
  const conv = "c1aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
  r = await ingest(relay, [{ conv, name: "Andy", sender: "Andy", text: "Checking in  on the checks", ts: t0, fp: fp(1) }]);
  assert.strictEqual(r.stored, 1);
  r = await ingest(relay, [{ conv, name: "Andy", sender: "+18015550123", text: "Checking in on the checks", ts: t0 + 4000, fp: fp(2), src: "store" }]);
  assert.strictEqual(r.stored, 0); assert.strictEqual(r.folded, 1);
  let c = await convOf(texts, conv);
  assert.strictEqual(c.unread, 1);
  assert.strictEqual((await open(texts, c.ne, TR2.AAD.name(c.conv))).name, "Andy");
  assert.strictEqual((await open(texts, c.lastE, TR2.AAD.message(c.conv, c.lastDir))).text, "Checking in  on the checks");
  // sealed for its own conversation and direction: under any other it doesn't open
  await assert.rejects(open(texts, c.lastE, TR2.AAD.message(await W(texts, "c2bbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"), "in")));
  await assert.rejects(open(texts, c.lastE, TR2.AAD.message(c.conv, "out")), "a text someone sent never opens as one this side sent");
  assert.strictEqual(pushes.length, 1);
  assert.strictEqual(pushes[0].title, "New text"); assert.ok(!JSON.stringify(pushes[0]).includes("Checking"));
  assert.ok(!JSON.stringify([...col("v2-messages").values()]).includes("Checking"), "no readable text on the server");
  assert.ok(!JSON.stringify([...col("v2-messages").values()]).includes("Andy"), "no readable names on the server");
  assert.ok(!JSON.stringify([...col("v2-messages").values()]).includes(conv), "no local conversation ids on the server");
  // the store first, then the notification
  r = await ingest(relay, [{ conv, name: "Andy", sender: "+18015550123", text: "Second one", ts: t0 + 60000, fp: fp(3), src: "store" }]);
  assert.strictEqual(r.stored, 1);
  r = await ingest(relay, [{ conv, name: "Andy", sender: "Andy", text: "Second one", ts: t0 + 61000, fp: fp(4) }]);
  assert.strictEqual(r.folded, 1);
  assert.strictEqual((await convOf(texts, conv)).unread, 2);
  // the relay phone learns which of its items folded, by its own (keyed) fp
  assert.strictEqual(r.results[0].fp, await TR2.fpId(relay.keys, fp(4)));
  assert.strictEqual(r.results[0].k, await key(relay, t0 + 60000, 3));

  // a reply from Texts and its copies: one message, sent through the relay phone
  const sent = await send(texts, conv, "On it, Andy");
  assert.strictEqual(sent._status, 200, JSON.stringify(sent));
  assert.ok(rings.some(x => x[0] === "tr2/" + relay.tid + "/o" && x[1] === "queued"));
  let ob = await call(relay, "GET", "/v2/relay/outbox");
  assert.strictEqual(ob.items.length, 1);
  assert.strictEqual(ob.items[0].conv, await W(relay, conv));
  assert.strictEqual(ob.items[0].e, undefined, "the relay phone acts on the sealed request only");
  const request = await open(relay, ob.items[0].o, TR2.AAD.out(await W(relay, conv)));
  assert.strictEqual(request.text, "On it, Andy"); assert.ok(request.n && request.ts);
  // sealed for that conversation alone: opened as any other, or as a message, it fails
  await assert.rejects(open(relay, ob.items[0].o, TR2.AAD.out(await W(relay, "c2bbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"))));
  await assert.rejects(open(relay, ob.items[0].o, TR2.AAD.message(await W(relay, conv), "out")));
  // inbox devices see the reply as a message; the request stays between the server and the relay phone
  assert.ok((await call(texts, "GET", "/v2/thread", null, { conv: await W(texts, conv) })).items.every(x => x.o === undefined));
  assert.strictEqual((await call(texts, "POST", "/v2/send", { conv: await W(texts, conv), e: ob.items[0].o }))._status, 400, "a send needs its request");
  offset += 2 * 3600000;
  await call(relay, "POST", "/v2/relay/outbox/result", { id: sent.id, status: "sent", via: "rcs", canReply: true });
  assert.strictEqual((await call(relay, "GET", "/v2/relay/outbox")).items.length, 0);
  assert.strictEqual(col("v2-messages").get(texts.tid + "/" + sent.id).o, undefined, "the request isn't kept once acted on");
  assert.ok([...col("v2-feed").values()].every(v => v.o === undefined && v.cv === undefined), "nor in the arrival log");
  r = await ingest(relay, [{ conv, name: "Andy", dir: "out", text: "On it, Andy", ts: Date.now() + 2000, fp: fp(5), src: "store" }]);
  assert.strictEqual(r.folded, 1, JSON.stringify(r));
  r = await ingest(relay, [{ conv, name: "Andy", dir: "out", text: "Typed on the relay phone", ts: Date.now() + 5000, fp: fp(6), src: "store" }]);
  assert.strictEqual(r.stored, 1);
  c = await convOf(texts, conv);
  assert.strictEqual(c.lastDir, "out"); assert.strictEqual((await open(texts, c.lastE, TR2.AAD.message(c.conv, "out"))).text, "Typed on the relay phone"); assert.strictEqual(c.unread, 2);
  // the echo of a Texts reply in the notification, before the relay reports it sent, and the store's copy
  const sent3 = await send(texts, conv, "Three ways");
  r = await ingest(relay, [{ conv, name: "Andy", dir: "out", text: "Three ways", ts: Date.now() + 800, fp: fp(600) }]);
  assert.strictEqual(r.results[0].status, "folded"); assert.strictEqual(r.results[0].k, sent3.id);
  await call(relay, "POST", "/v2/relay/outbox/result", { id: sent3.id, status: "sent", via: "rcs", canReply: true });
  r = await ingest(relay, [{ conv, name: "Andy", dir: "out", text: "Three ways", ts: Date.now() + 900, fp: fp(601), src: "store" }]);
  assert.strictEqual(r.results[0].status, "folded");
  assert.strictEqual((await textsOf(texts, conv)).filter(t => t === "Three ways").length, 1);
  assert.deepStrictEqual(col("v2-messages").get(texts.tid + "/" + sent3.id).from.sort(), ["notif", "store", "texts"]);
  r = await ingest(relay, [{ conv, name: "Andy", dir: "out", text: "Three ways", ts: Date.now() + 60000, fp: fp(602), src: "store" }]);
  assert.strictEqual(r.results[0].status, "stored");

  // full history: quiet, and logged once per conversation
  const hist = Array.from({ length: 30 }, (_, i) => ({ conv, name: "Andy", sender: "+18015550123", dir: i % 3 ? "in" : "out", text: "old " + i, ts: t0 - 86400000 * 30 + i * 60000, fp: fp(100 + i), src: "store", history: true }));
  r = await ingest(relay, hist);
  assert.strictEqual(r.stored, 30);
  assert.strictEqual((await convOf(texts, conv)).unread, 2);
  const feedVals = [...col("v2-feed").entries()].filter(([k]) => k.startsWith(texts.tid + "/")).map(([, v]) => v);
  assert.strictEqual(feedVals.filter(v => v.history).length, 0);
  const wconv = await W(texts, conv);
  assert.strictEqual(feedVals.filter(v => v.touch && v.conv === wconv).length, 1);
  const touchSync = await call(texts, "GET", "/v2/sync", null, { feed: "f:" + pad13(Date.now() - 60000) });
  assert.deepStrictEqual(touchSync.reload, [await W(texts, conv)]);
  assert.ok(touchSync.items.every(x => !x.cv && !x.qs), "index fields stay on the server");
  assert.ok(touchSync.items.some(x => x.k === sent3.id));
  // read on the relay phone
  await ingest(relay, [], { reads: { [await W(relay, conv)]: Date.now() + 120000 } });
  assert.strictEqual((await convOf(texts, conv)).unread, 0);

  // pictures: two through the notification, three in the store: one more, not three
  const conv3 = "c3cccccccccccccccccccccccccccccc";
  const pic = (n, src, dt) => ({ conv: conv3, name: "Megan", sender: "Megan", text: "", ts: t0 + dt, fp: fp(n), src, media: { mime: "image/jpeg" } });
  await ingest(relay, [pic(400, "notif", 0), pic(401, "notif", 1000)]);
  r = await ingest(relay, [pic(402, "store", 500), pic(403, "store", 1500), pic(404, "store", 2500)]);
  assert.strictEqual(r.stored, 1); assert.strictEqual(r.folded, 2);
  // an attachment goes up only when the inbox still needs it; its details come sealed with the copy that brings it
  const want = (n, dt, extra) => Object.assign({ conv: conv3, name: "Megan", sender: "Megan", text: "", ts: t0 + dt, fp: fp(n), src: "store", media: { mime: "image/jpeg" }, wantMedia: true }, extra || {});
  r = await ingest(relay, [want(410, 600000)]);
  assert.strictEqual(r.results[0].status, "stored"); assert.strictEqual(r.results[0].needMedia, true);
  const wc3 = await W(relay, conv3), wfp410 = await TR2.fpId(relay.keys, fp(410));
  // sealed bytes aren't text: every byte value, invalid UTF-8 included, is stored as sent
  const sealedBytes = Buffer.concat([Buffer.from([0xff, 0xfe, 0x00, 0xc3, 0x28, 0x7b, 0x22]), require("crypto").randomBytes(4000)]);
  const put = await v2("POST", "/v2/media/put", sealedBytes, null, Object.assign(H(relay.token), { "x-relay-conv": wc3, "x-relay-fp": wfp410, "x-relay-ts": String(t0 + 600000), "x-relay-kind": "original", "content-type": "application/octet-stream" }));
  assert.strictEqual(put._status, 200, JSON.stringify(put));
  assert.strictEqual(put.path, mediaPath(relay.tid, wc3, t0 + 600000, wfp410, "original"));
  assert.strictEqual(files.get(put.path).type, "application/zip");
  assert.ok(Buffer.from(files.get(put.path).b64, "base64").equals(sealedBytes));
  assert.strictEqual(put.bytes, sealedBytes.length);
  r = await ingest(relay, [want(410, 600000, { media: { mime: "image/jpeg", path: put.path, bytes: 10, w: 4, h: 3 } })]);
  assert.strictEqual(r.results[0].status, "known"); assert.strictEqual(r.results[0].media, "attached"); assert.ok(!r.results[0].needMedia);
  const withPic = col("v2-messages").get(relay.tid + "/" + await key(relay, t0 + 600000, 410));
  assert.strictEqual(withPic.media.path, put.path);
  assert.strictEqual((await open(texts, withPic.e, TR2.AAD.message(withPic.conv, withPic.dir))).media.w, 4);
  // a media path must be this inbox's and this conversation's
  r = await ingest(relay, [want(413, 700000, { media: { mime: "image/jpeg", path: mediaPath("f".repeat(32), wc3, t0, wfp410, "original") } })]);
  assert.deepStrictEqual(col("v2-messages").get(relay.tid + "/" + await key(relay, t0 + 700000, 413)).media, {});
  const gotUrl = await call(texts, "GET", "/v2/media", null, { p: put.path });
  assert.ok(gotUrl.url.endsWith(put.path));
  // large files go through a ticket confined to this inbox's folder
  const tk = await call(relay, "POST", "/v2/media/ticket", { conv: wc3, fp: wfp410, ts: t0 + 600000, bytes: 9000000, kind: "original" });
  assert.strictEqual(tk._status, 200, JSON.stringify(tk));
  assert.strictEqual(tickets[tickets.length - 1].pathPrefix, "/media/v2/" + relay.tid + "/");
  assert.strictEqual(tickets[tickets.length - 1].contentType, "application/zip");
  assert.strictEqual((await call(relay, "POST", "/v2/media/complete", { ticket: tk.ticket, path: tk.path }))._status, 200);
  assert.strictEqual((await call(relay, "POST", "/v2/media/ticket", { conv: wc3, fp: wfp410, ts: t0, bytes: 40 * 1024 * 1024 }))._status, 413);

  const conv4Name = "c4dddddddddddddddddddddddddddddd";
  // An inbox device sends a picture: it stores the sealed file first, under its own conversation, then sends with the
  // path in the open and inside the sealed request (the relay phone fetches it from there and sends it as the text).
  {
    const sentAt = Date.now();
    const sentFp = await TR2.fpId(texts.keys, fp(777));
    const up = await v2("POST", "/v2/media/put", Buffer.from("sealed-picture-bytes"), null, Object.assign(H(texts.token),
      { "x-relay-conv": wc3, "x-relay-fp": sentFp, "x-relay-ts": String(sentAt), "x-relay-kind": "original", "content-type": "application/octet-stream" }));
    assert.strictEqual(up._status, 200, JSON.stringify(up));
    const media = { mime: "image/jpeg", w: 40, h: 30, bytes: 20 };
    const e = await TR2.sealJson(texts.keys.enc, { text: "", ts: sentAt, fp: sentFp, media }, TR2.AAD.message(wc3, "out"));
    const o = await TR2.sealJson(texts.keys.enc, { text: "", n: TR2.b64u(TR2.random(16)), ts: sentAt, media: { path: up.path, mime: "image/jpeg", bytes: 20 } }, TR2.AAD.out(wc3));
    const sent = await call(texts, "POST", "/v2/send", { conv: wc3, e, o, mt: await TR2.matchToken(texts.keys, "out", "", "image/jpeg"), media: { path: up.path } });
    assert.strictEqual(sent._status, 200, JSON.stringify(sent));
    const stored = col("v2-messages").get(texts.tid + "/" + sent.id);
    assert.deepStrictEqual(stored.media, { path: up.path }, "the path, this inbox's and this conversation's, is kept with the message");
    assert.strictEqual((await convOf(texts, conv3)).lastMedia, true);
    // the relay phone finds the path in the request it opens, and only its own inbox's files are ever named
    const ob = await call(relay, "GET", "/v2/relay/outbox");
    const mine = ob.items.find((i) => i.id === sent.id);
    assert.strictEqual((await open(relay, mine.o, TR2.AAD.out(wc3))).media.path, up.path);
    const elsewhere = await call(texts, "POST", "/v2/send", { conv: wc3, e, o, media: { path: mediaPath("f".repeat(32), wc3, sentAt, sentFp, "original") } });
    assert.strictEqual(elsewhere._status, 200);
    assert.strictEqual(col("v2-messages").get(texts.tid + "/" + elsewhere.id).media, undefined, "a path outside this inbox is dropped");
    const otherConv = await call(texts, "POST", "/v2/send", { conv: wc3, e, o, media: { path: mediaPath(texts.tid, await W(texts, conv4Name), sentAt, sentFp, "original") } });
    assert.strictEqual(col("v2-messages").get(texts.tid + "/" + otherConv.id).media, undefined, "or another conversation's");
    for (const id of [elsewhere.id, otherConv.id]) await call(relay, "POST", "/v2/relay/outbox/result", { id, status: "failed", code: "no_attach" });
    const failed = col("v2-messages").get(texts.tid + "/" + otherConv.id);
    assert.strictEqual(failed.status, "failed"); assert.strictEqual(failed.code, "no_attach", "a new failure code passes as any other");
    await call(relay, "POST", "/v2/relay/outbox/result", { id: sent.id, status: "sent", via: "mms" });
    assert.strictEqual(col("v2-messages").get(texts.tid + "/" + sent.id).via, "mms", "sent as MMS from the relay phone itself");
    await call(relay, "POST", "/v2/relay/outbox/result", { id: sent.id, status: "sent", via: "pigeon" });
    assert.strictEqual(col("v2-messages").get(texts.tid + "/" + sent.id).via, "rcs", "anything but sms or mms reads as rcs");
  }

  // a history copy of a text relayed weeks ago folds into it, however many came after
  const conv4 = "c4dddddddddddddddddddddddddddddd";
  const weeksAgo = t0 - 21 * 86400000;
  await ingest(relay, [{ conv: conv4, name: "Sam", sender: "Sam", text: "See you Saturday", ts: weeksAgo, fp: fp(500) }]);
  for (let b = 0; b < 6; b++) {
    await ingest(relay, Array.from({ length: 50 }, (_, i) => ({ conv: conv4, name: "Sam", sender: "Sam", text: "later " + b + "-" + i, ts: weeksAgo + 86400000 + (b * 50 + i) * 60000, fp: fp(1000 + b * 50 + i) })));
  }
  r = await ingest(relay, [{ conv: conv4, name: "Sam", sender: "+18015550444", text: "See you Saturday", ts: weeksAgo + 2000, fp: fp(501), src: "store", history: true }]);
  assert.strictEqual(r.folded, 1, JSON.stringify(r));

  // which conversation a thread belongs to is the relay phone's own call: the server isn't asked
  assert.strictEqual((await call(relay, "POST", "/v2/relay/locate", { probes: [] }))._status, 404);

  // merge
  const conv5 = "c5eeeeeeeeeeeeeeeeeeeeeeeeeeeeee";
  await ingest(relay, [
    { conv: conv5, name: "Sam (store)", sender: "+18015550444", text: "Old thread text", ts: weeksAgo - 86400000, fp: fp(700), src: "store", history: true },
    { conv: conv5, name: "Sam (store)", sender: "+18015550444", text: "later 0-3", ts: weeksAgo + 86400000 + 3 * 60000 + 1500, fp: fp(701), src: "store", history: true }
  ]);
  // a reply written in conv5 just before the merge: moved with it, never folded away, still sent and reported
  const queued5 = await send(texts, conv5, "On the way, Sam");
  const epochBefore = (await call(texts, "GET", "/v2/inbox")).epoch;
  const w4 = await W(relay, conv4), w5 = await W(relay, conv5);
  const record = await TR2.sealJson(relay.keys.enc, { from: w5, into: w4 }, TR2.AAD.merge(w4));
  const m = await call(relay, "POST", "/v2/relay/merge", { from: w5, into: w4, mr: record });
  assert.strictEqual(m.moved, 2); assert.strictEqual(m.folded, 1);
  assert.strictEqual(await convOf(texts, conv5), undefined);
  const movedMsg = col("v2-messages").get(relay.tid + "/" + await key(relay, weeksAgo - 86400000, 700));
  assert.strictEqual(movedMsg.conv, w4);
  assert.strictEqual(movedMsg.cv, relay.tid + "/" + w4);
  assert.strictEqual((await open(texts, movedMsg.ne, TR2.AAD.name(w4))).name, "Sam");
  // what was sealed for conv5 still opens only as conv5's; the relay phone's record, kept with conv4, vouches for it
  await assert.rejects(open(texts, movedMsg.e, TR2.AAD.message(w4, movedMsg.dir)));
  assert.strictEqual((await open(texts, movedMsg.e, TR2.AAD.message(w5, movedMsg.dir))).text, "Old thread text");
  const c4 = await convOf(texts, conv4);
  assert.deepStrictEqual(c4.merged, [record]);
  assert.deepStrictEqual(await open(texts, c4.merged[0], TR2.AAD.merge(w4)), { from: w5, into: w4 });
  const queuedNow = col("v2-messages").get(texts.tid + "/" + queued5.id);
  assert.strictEqual(queuedNow.status, "queued"); assert.strictEqual(queuedNow.conv, w4); assert.ok(queuedNow.o);
  assert.strictEqual((await call(texts, "GET", "/v2/thread", null, { conv: w4 })).conversation.merged.length, 1);
  await call(relay, "POST", "/v2/relay/outbox/result", { id: queued5.id, status: "sent", via: "rcs" });
  assert.strictEqual((await call(texts, "GET", "/v2/sync", null, { feed: "f:" + pad13(Date.now() - 60000) })).epoch, epochBefore + 1);
  // a big merge in slices
  const conv6 = "c6ffffffffffffffffffffffffffffff", conv7 = "c7aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
  const base6 = weeksAgo - 30 * 86400000;
  await ingest(relay, [{ conv: conv7, name: "Kim", sender: "Kim", text: "hello there", ts: base6, fp: fp(899) }]);
  for (let b = 0; b < 3; b++) {
    await ingest(relay, Array.from({ length: 30 }, (_, i) => ({ conv: conv6, name: "Kim (store)", sender: "+18015550666", text: "batch " + b + " #" + i, ts: base6 + 3600000 + (b * 30 + i) * 600000, fp: fp(900 + b * 30 + i), src: "store", history: true })));
  }
  let calls = 0, mr;
  do { mr = await sliced("POST", "/v2/relay/merge", { from: await W(relay, conv6), into: await W(relay, conv7) }, null, H(relay.token)); calls++; } while (mr.more && calls < 20);
  assert.ok(calls > 1, "expected more than one slice, got " + calls);
  const w6 = await W(relay, conv6), w7 = await W(relay, conv7);
  assert.strictEqual(messagesOf(relay.tid).filter(x => x.conv === w6).length, 0);
  assert.strictEqual(messagesOf(relay.tid).filter(x => x.conv === w7).length, 91);

  // a reply's result and the relay phone's copy of it arriving at the same moment: the result is never undone
  const raced = await send(texts, conv, "Racing the copy");
  const racedKey = texts.tid + "/" + raced.id;
  const realPutRace = base.data.put;
  let interleaved = false;
  base.data.put = async function (c, k, v, o) {
    if (c === "v2-messages" && k === racedKey && o && o.ifVersion !== undefined && !interleaved && v.from && v.from.includes("notif")) {
      interleaved = true;   // ingest is about to write the copy into the reply it read while queued: the result lands first
      base.data.put = realPutRace;
      assert.strictEqual((await call(relay, "POST", "/v2/relay/outbox/result", { id: raced.id, status: "sent", via: "rcs" }))._status, 200);
    }
    return realPutRace.call(this, c, k, v, o);
  };
  r = await ingest(relay, [{ conv, name: "Andy", dir: "out", text: "Racing the copy", ts: Date.now() + 500, fp: fp(650) }]);
  base.data.put = realPutRace;
  assert.ok(interleaved, "the race happened");
  assert.strictEqual(r.results[0].status, "folded");
  const afterRace = col("v2-messages").get(racedKey);
  assert.strictEqual(afterRace.status, "sent", "still sent");
  assert.strictEqual(afterRace.o, undefined); assert.strictEqual(afterRace.qs, undefined);
  assert.ok(afterRace.from.includes("notif"), "and the copy folded in");
  assert.strictEqual((await call(relay, "GET", "/v2/relay/outbox")).items.length, 0, "nothing goes out again");

  // wake, heartbeat
  const w = await call(texts, "POST", "/v2/relay/wake", { kind: "refresh" });
  ob = await call(relay, "GET", "/v2/relay/outbox");
  assert.strictEqual(ob.wake, w.at); assert.strictEqual(ob.wakeKind, "refresh");
  const sn = await TR2.sealJson(relay.keys.enc, { note: "Could not read an attachment (image/heic)", history: "paused: no access" }, TR2.AAD.note);
  const hb = await call(relay, "POST", "/v2/relay/heartbeat", { listening: true, model: "Example Phone 2", note: "plain words", sn, store: { allowed: true, lastScan: 5, history: { state: "running", done: 10, total: 40, note: "plain" } } });
  assert.strictEqual(hb.wakeKind, "refresh");
  const st = await call(texts, "GET", "/v2/status");
  assert.strictEqual(st.relay.store.history.total, 40);
  assert.strictEqual(st.relay.seen, undefined);
  // what the relay phone says about itself is sealed; nothing it says stays on the server in plain words
  assert.strictEqual((await open(texts, st.relay.sn, TR2.AAD.note)).note, "Could not read an attachment (image/heic)");
  assert.ok(!JSON.stringify(col("v2-state").get(texts.tid + "/relay")).includes("plain"));
  // a cursor older than the log resets the device
  assert.strictEqual((await call(texts, "GET", "/v2/sync", null, { feed: "f:" + pad13(Date.now() - 4 * 86400000) })).reset, true);
  // the inbox
  const inb = await call(texts, "GET", "/v2/inbox");
  assert.strictEqual(inb.items.length, 4);
  assert.ok(inb.items.every(x => x.conv && !x.conv.includes("/")));
  assert.ok(/^m:[0-9]{13}:/.test(inb.latestKey));
  const th = await call(texts, "GET", "/v2/thread", null, { conv: await W(texts, conv) });
  assert.ok(th.items.length > 5 && th.items.every(x => !x.cv));

  // ================================================================ 3. a second inbox on the same server sees none of it
  const relayB = phone("relay"), textsB = phone("inbox");
  const offerB = await asks(relayB);
  assert.strictEqual((await scans(textsB, offerB.code))._status, 200);
  await waits(relayB, offerB);
  assert.notStrictEqual(relayB.tid, relay.tid);
  assert.strictEqual((await call(textsB, "GET", "/v2/inbox")).items.length, 0);
  assert.strictEqual((await call(textsB, "GET", "/v2/thread", null, { conv: await W(texts, conv) })).items.length, 0);
  assert.strictEqual((await call(textsB, "GET", "/v2/media", null, { p: put.path }))._status, 400);
  assert.strictEqual((await call(relayB, "POST", "/v2/media/complete", { ticket: tk.ticket, path: tk.path }))._status, 400);
  assert.strictEqual((await call(relayB, "GET", "/v2/relay/outbox")).items.length, 0);
  assert.strictEqual((await call(relayB, "POST", "/v2/relay/outbox/result", { id: sent.id, status: "sent" }))._status, 404);
  const sealedX = await TR2.sealJson(textsB.keys.enc, { text: "x" }, "tr2:m");
  assert.strictEqual((await call(textsB, "POST", "/v2/send", { conv: await W(texts, conv), e: sealedX, o: sealedX }))._status, 404);
  const tB = await call(textsB, "GET", "/v2/ticket");
  assert.strictEqual(tB.channel, "tr2/" + textsB.tid + "/i");
  assert.strictEqual((await call(relayB, "GET", "/v2/ticket")).channel, "tr2/" + relayB.tid + "/o");
  assert.ok(!rings.some(x => x[0].includes(textsB.tid) && x[1] === "messages"));

  // ================================================================ 4. more devices, and a new relay phone
  // a linked relay phone invites another inbox device (a browser types the code)
  const browser = phone("inbox", "Laptop");
  const inv = await invites(relay);
  assert.strictEqual((await call(relay, "POST", "/v2/link/status", { lookup: inv.lookup })).state, "open");
  r = await scans(browser, inv.code);
  assert.strictEqual(r.kind, "invite", JSON.stringify(r));
  assert.strictEqual(browser.tid, relay.tid);
  assert.strictEqual((await call(relay, "POST", "/v2/link/status", { lookup: inv.lookup })).state, "done");
  assert.strictEqual((await call(browser, "GET", "/v2/inbox")).items.length, inb.items.length);
  assert.strictEqual((await scans(phone("inbox"), inv.code))._status, 404, "an invite works once");
  // a linked phone can't take an invite, and a newer invite replaces the older one
  const inv2 = await invites(relay);
  assert.strictEqual((await scans(texts, inv2.code))._status, 409);
  const inv3 = await invites(relay);
  assert.strictEqual((await scans(phone("inbox"), inv2.code))._status, 404);
  assert.strictEqual((await scans(phone("inbox"), inv3.code))._status, 200);
  // a relay phone can't invite another relay phone; an inbox device can invite one
  const s4 = await TR2.linkSecrets(TR2.newCode());
  assert.strictEqual((await call(relay, "POST", "/v2/link/invite", { lookup: s4.lookup, box: await TR2.sealJson(s4.boxKey, {}, "tr2:box"), role: "relay" }))._status, 403);
  // the phone with the SIM is replaced: the new Relay shows its code, the linked Texts scans it
  const relay2 = phone("relay", "Pixel");
  const offer2 = await asks(relay2);
  let replaced = null;
  r = await scans(texts, offer2.code, { onAsk: c => { replaced = c; } });
  assert.strictEqual(r._status, 200, JSON.stringify(r));
  assert.strictEqual(replaced.linked, true); assert.strictEqual(replaced.hasRelay, true); assert.strictEqual(replaced.relay.model, "Example Phone 2");
  await waits(relay2, offer2);
  assert.strictEqual(relay2.tid, texts.tid);
  assert.deepStrictEqual(relay2.keys.raw, texts.keys.raw);
  assert.strictEqual((await call(relay, "GET", "/v2/ping")).unlinked, true, "the old relay phone is out");
  assert.strictEqual((await call(relay2, "GET", "/v2/relay/outbox"))._status, 200);
  let devs = await call(texts, "GET", "/v2/devices");
  assert.strictEqual(devs.items.filter(d => d.role === "relay").length, 1);
  assert.ok(devs.items.some(d => d.you && d.role === "inbox"));
  // the new relay phone carries on where the old one left off: its copies still fold
  r = await ingest(relay2, [{ conv, name: "Andy", sender: "Andy", text: "Second one", ts: t0 + 61000, fp: fp(4) }]);
  assert.strictEqual(r.known, 1);
  // a device removed from Settings is out at once, with its notifications and the invite it was showing
  assert.strictEqual((await call(browser, "POST", "/v2/push/register", { subscriptionId: "apppush_laptop" })).count, 2);
  const laptopInvite = await invites(browser);
  const extra = devs.items.find(d => d.id === idOf(browser));
  assert.strictEqual((await call(texts, "POST", "/v2/devices/remove", { id: extra.id }))._status, 200);
  assert.strictEqual((await call(browser, "GET", "/v2/inbox")).unlinked, true);
  assert.strictEqual((await call(relay2, "POST", "/v2/devices/remove", { id: extra.id }))._status, 403);
  assert.deepStrictEqual(col("v2-state").get(texts.tid + "/push").ids, ["apppush_test1"]);
  assert.strictEqual(col("v2-offers").get(laptopInvite.lookup), undefined, "its invite is gone");
  assert.strictEqual((await scans(phone("inbox", "Laptop again"), laptopInvite.code))._status, 404, "a removed device's invite lets nobody in");

  // a push registration on its way while the device is removed doesn't bring its notifications back
  const pusher = phone("inbox", "Pusher");
  assert.strictEqual((await scans(pusher, (await invites(texts)).code)).kind, "invite");
  const pusherId = (await call(texts, "GET", "/v2/devices")).items.find(d => d.id === idOf(pusher)).id;
  const realPutPush = base.data.put;
  base.data.put = async function (c, k, v, o) {
    if (c === "v2-state" && k === texts.tid + "/push" && v.ids && v.ids.includes("apppush_pusher")) {
      base.data.put = realPutPush;
      const out = await realPutPush.call(this, c, k, v, o);
      assert.strictEqual((await call(texts, "POST", "/v2/devices/remove", { id: pusherId }))._status, 200);
      return out;
    }
    return realPutPush.call(this, c, k, v, o);
  };
  r = await call(pusher, "POST", "/v2/push/register", { subscriptionId: "apppush_pusher" });
  base.data.put = realPutPush;
  assert.strictEqual(r.unlinked, true);
  assert.ok(!col("v2-state").get(texts.tid + "/push").ids.includes("apppush_pusher"));

  // and an invite whose device is gone by any route lets nobody in either
  const ghost = phone("inbox", "Ghost");
  assert.strictEqual((await scans(ghost, (await invites(texts)).code)).kind, "invite");
  const ghostInvite = await invites(ghost);
  for (const [k, v] of [...col("v2-devices").entries()]) if (v.label === "Ghost") col("v2-devices").delete(k);
  assert.ok(col("v2-offers").get(ghostInvite.lookup), "still filed");
  assert.strictEqual((await scans(phone("inbox", "Through the ghost"), ghostInvite.code))._status, 404);

  // a device removed while it is filing its wrap stays out (the record read before the removal is never written back)
  const tablet = phone("inbox", "Tablet");
  assert.strictEqual((await scans(tablet, (await invites(texts)).code)).kind, "invite");
  const tabletId = (await call(texts, "GET", "/v2/devices")).items.find(d => d.id === idOf(tablet)).id;
  const realPut = base.data.put;
  base.data.put = async function (c, k, v, o) {
    if (c === "v2-devices" && v && v.wrap) {
      base.data.put = realPut;
      assert.strictEqual((await call(texts, "POST", "/v2/devices/remove", { id: tabletId }))._status, 200);
    }
    return realPut.call(this, c, k, v, o);
  };
  const pTablet = await TR2.phoneSecrets("0123456789abcdef", "inbox");
  r = await call(tablet, "POST", "/v2/wrap", { tag: pTablet.tag, wrap: await TR2.sealJson(pTablet.wrapKey, { v: 2 }, "tr2:wrap") });
  base.data.put = realPut;
  assert.strictEqual(r.unlinked, true, JSON.stringify(r));
  assert.strictEqual((await call(tablet, "GET", "/v2/ping")).unlinked, true);
  assert.strictEqual((await v2("POST", "/v2/link/recover", { tag: pTablet.tag }))._status, 404);
  // nor can it come back by filing wraps over and over
  for (const t of ["1", "2"]) assert.strictEqual((await call(tablet, "POST", "/v2/wrap", { tag: t.repeat(64), wrap: await TR2.sealJson(pTablet.wrapKey, {}, "tr2:wrap") })).unlinked, true);

  // a code isn't used up by a device the inbox can't take: it still works once there's room
  const filler = [];
  while ((await call(texts, "GET", "/v2/devices")).items.length < 8) {
    const f = phone("inbox", "Filler");
    assert.strictEqual((await scans(f, (await invites(texts)).code)).kind, "invite");
    filler.push(f);
  }
  const ninth = phone("inbox", "Ninth");
  const fullInvite = await invites(texts);
  assert.strictEqual((await scans(ninth, fullInvite.code))._status, 409);
  await call(filler[0], "POST", "/v2/leave");
  assert.strictEqual((await scans(ninth, fullInvite.code)).kind, "invite", "the invite was still open");
  // the same for a device asking to be let in: nothing is handed over until it's in
  const asking = phone("inbox", "Asking");
  const askOffer = await asks(asking);
  assert.strictEqual((await scans(texts, askOffer.code))._status, 409);
  assert.strictEqual((await waits(asking, askOffer))._status, 202);
  await call(filler[1], "POST", "/v2/leave");
  assert.strictEqual((await scans(texts, askOffer.code))._status, 200);
  assert.strictEqual((await waits(asking, askOffer))._status, 200);
  assert.strictEqual((await call(asking, "GET", "/v2/ping")).tid, texts.tid);
  for (const d of [ninth, asking].concat(filler.slice(2))) await call(d, "POST", "/v2/leave");

  // ================================================================ 5. reinstalled on the same phone
  const pRelay = await TR2.phoneSecrets("3f2a9c7b1e4d5a60", "relay");
  const wrap = await TR2.sealJson(pRelay.wrapKey, { v: 2, tid: relay2.tid, token: relay2.token, key: TR2.b64u(relay2.keys.raw) }, "tr2:wrap");
  assert.strictEqual((await call(relay2, "POST", "/v2/wrap", { tag: pRelay.tag, wrap }))._status, 200);
  const back = await v2("POST", "/v2/link/recover", { tag: pRelay.tag });
  assert.strictEqual(back._status, 200);
  const unwrapped = await TR2.openJson(pRelay.wrapKey, back.wrap, "tr2:wrap");
  assert.strictEqual(unwrapped.token, relay2.token);
  assert.strictEqual((await v2("POST", "/v2/link/recover", { tag: (await TR2.phoneSecrets("3f2a9c7b1e4d5a61", "relay")).tag }))._status, 404);
  // replaced again: the old phone's wrap goes with it
  const relay3 = phone("relay");
  const offer3 = await asks(relay3);
  await scans(texts, offer3.code);
  await waits(relay3, offer3);
  assert.strictEqual((await v2("POST", "/v2/link/recover", { tag: pRelay.tag }))._status, 404);
  // unlinking on purpose removes the device and its wrap
  const pTexts = await TR2.phoneSecrets("aaaabbbbccccdddd", "inbox");
  await call(texts, "POST", "/v2/wrap", { tag: pTexts.tag, wrap: await TR2.sealJson(pTexts.wrapKey, { v: 2 }, "tr2:wrap") });
  assert.strictEqual((await v2("POST", "/v2/link/recover", { tag: pTexts.tag }))._status, 200);

  // ================================================================ 6. moving a v1 inbox in
  const v1conv = "c9abababababababababababababababab".slice(0, 32);
  const wv1 = await W(texts, v1conv);
  const oldTs = t0 - 5 * 86400000;
  const v1key = "m:" + pad13(oldTs) + ":" + await TR2.fpId(texts.keys, fp(3000));
  const twinKey = "m:" + pad13(oldTs + 3000) + ":" + await TR2.fpId(texts.keys, fp(3001));
  const moved = {
    k: v1key, conv: wv1, dir: "in", status: "received", ts: oldTs, src: "notif", from: ["notif", "store"], twins: [twinKey],
    e: await TR2.sealJson(texts.keys.enc, { text: "Moved over", sender: "Pat", name: "Pat", group: false, ts: oldTs, moved: true }, TR2.AAD.message(wv1, "in")),
    ne: await TR2.sealJson(texts.keys.enc, { name: "Pat", group: false }, TR2.AAD.name(wv1)),
    mt: await TR2.matchToken(texts.keys, "in", "Moved over", "")
  };
  const queuedKey = "m:" + pad13(oldTs + 9000) + ":o" + "abcd1234";
  const queued = { k: queuedKey, conv: wv1, dir: "out", status: "queued", ts: oldTs + 9000, src: "texts", e: await TR2.sealJson(texts.keys.enc, { text: "never went", ts: oldTs + 9000, moved: true }, TR2.AAD.message(wv1, "out")) };
  const summary = { conv: wv1, ne: moved.ne, lastTs: oldTs + 9000, lastE: queued.e, lastDir: "out", lastMedia: false, canReply: true, unread: 1 };
  r = await call(texts, "POST", "/v2/import", { messages: [moved, queued], convs: [summary] });
  assert.strictEqual(r.messages, 2); assert.strictEqual(r.convs, 1);
  assert.strictEqual((await convOf(texts, v1conv)).unread, 1);
  assert.strictEqual(col("v2-messages").get(texts.tid + "/" + queuedKey).status, "failed", "a reply queued in v1 is never sent by surprise");
  assert.strictEqual((await call(relay3, "GET", "/v2/relay/outbox")).items.length, 0);
  // sent again (an interrupted move picks up again): nothing doubles
  await call(texts, "POST", "/v2/import", { messages: [moved, queued], convs: [summary] });
  assert.strictEqual((await convOf(texts, v1conv)).unread, 1);
  assert.strictEqual(messagesOf(texts.tid).filter(x => x.conv === wv1).length, 2);
  // the relay phone sending the store's copy later: known through the moved message's twins
  r = await ingest(relay3, [{ conv: v1conv, name: "Pat", sender: "+18015557777", text: "Moved over", ts: oldTs + 3000, fp: fp(3001), src: "store", history: true }]);
  assert.strictEqual(r.results[0].status, "known", JSON.stringify(r));
  assert.strictEqual((await call(texts, "POST", "/v2/import/done"))._status, 200);
  assert.ok(rings.some(x => x[0] === "tr2/" + texts.tid + "/i" && x[1] === "reload"));

  // ================================================================ 7. offers expire; the watch reports a silent relay phone
  const late = phone("relay");
  const lateOffer = await asks(late);
  offset += 16 * 60000;
  assert.strictEqual((await scans(phone("inbox"), lateOffer.code))._status, 404);
  assert.strictEqual((await waits(late, lateOffer))._status, 410);
  await call(relay3, "POST", "/v2/relay/heartbeat", { listening: true });
  col("v2-state").set(texts.tid + "/push", { ids: ["apppush_test1"] });
  const pushesBefore = pushes.length;
  offset += 11 * 60000;
  let wr = await watch("POST", "/", null, null, {});
  assert.ok(wr.reported >= 1, JSON.stringify(wr));
  assert.strictEqual(pushes.length, pushesBefore + 1);
  assert.strictEqual(pushes[pushes.length - 1].title, "Relay phone offline");
  wr = await watch("POST", "/", null, null, {});
  assert.strictEqual(wr.reported, 0, "reported once");
  await call(relay3, "POST", "/v2/relay/heartbeat", { listening: true });
  assert.strictEqual(pushes[pushes.length - 1].title, "Relay phone is back");
  assert.ok(col("v2-state").get(texts.tid + "/relay").seen);

  // ================================================================ 8. names: each device seals its own, the server keeps none it could read
  {
    const sn = await TR2.sealJson(texts.keys.enc, { name: "Pocket phone" }, TR2.AAD.device(idOf(texts)));
    assert.strictEqual((await call(texts, "POST", "/v2/devices/name", { sn }))._status, 200);
    const listed = (await call(texts, "GET", "/v2/devices")).items;
    const mine = listed.find(d => d.id === idOf(texts));
    assert.strictEqual(mine.sn, sn, "the list carries the sealed name");
    assert.ok(listed.every(d => d.label === undefined && d.model === undefined), "and no plain names: " + JSON.stringify(listed));
    assert.deepStrictEqual(await TR2.openJson(texts.keys.enc, mine.sn, TR2.AAD.device(mine.id)), { name: "Pocket phone" });
    assert.strictEqual((await call(texts, "POST", "/v2/devices/name", { sn: "Call 1 800 555 0100" }))._status, 400, "only something sealed");
    assert.strictEqual((await call(texts, "POST", "/v2/devices/name", { sn: "e1." + "A".repeat(500) }))._status, 400, "and short");
    assert.strictEqual(col("v2-devices").get(require("crypto").createHash("sha256").update(texts.token).digest("hex")).sn, sn, "unchanged by a refusal");
    assert.strictEqual((await call(phone("inbox"), "POST", "/v2/devices/name", { sn }))._status, 403, "a device in no inbox names nothing");
  }

  // ================================================================ 9. leaving
  assert.strictEqual((await call(texts, "POST", "/v2/leave"))._status, 200);
  assert.strictEqual((await call(texts, "GET", "/v2/ping")).unlinked, true);
  assert.strictEqual((await v2("POST", "/v2/link/recover", { tag: pTexts.tag }))._status, 404);
  assert.strictEqual((await v2("POST", "/v2/link/ask", { lookup: "x", token: "y", role: "relay" }))._status, 400);
  assert.strictEqual((await v2("POST", "/v2/nope", {}))._status, 404);
  console.log("all v2 server tests passed");
})().catch(e => { console.error(e); process.exit(1); });
