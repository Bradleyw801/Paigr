// Sets up a private inbox on a running test/serve.js the way two real phones would (the relay phone asks, a Texts
// device creates the inbox), then has the "relay phone" file a few conversations: texts both ways, a group, pictures,
// a reply that failed. Prints the inbox device's link as JSON, for the Texts app's own tests and screenshots.
//   node test/seed-native.js http://127.0.0.1:8899 [demo|empty]
const zlib = require("zlib");
const TR2 = require("../web/tr2.js");

const ORIGIN = process.argv[2];
const SCENARIO = process.argv[3] || "demo";

async function call(p, o) {
  o = o || {};
  const headers = Object.assign({}, o.headers || {});
  let body = o.body;
  if (body !== undefined && !Buffer.isBuffer(body)) { body = JSON.stringify(body); headers["content-type"] = "application/json"; }
  const res = await fetch(ORIGIN + p, { method: o.method || (body !== undefined ? "POST" : "GET"), headers, body });
  const text = await res.text();
  let d = {};
  try { d = text ? JSON.parse(text) : {}; } catch { d = { raw: text }; }
  if (!res.ok) throw new Error(p + " " + res.status + " " + text);
  return d;
}

/** A small photo-like PNG: a sky running from dusk to night over a dark hill. */
function picture(w, h, a, b) {
  const crc = (buf) => {
    let c, n, t = [];
    for (n = 0; n < 256; n++) { c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; t[n] = c >>> 0; }
    c = 0xffffffff;
    for (const x of buf) c = t[(c ^ x) & 0xff] ^ (c >>> 8);
    return (c ^ 0xffffffff) >>> 0;
  };
  const chunk = (type, data) => {
    const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
    const td = Buffer.concat([Buffer.from(type), data]);
    const c = Buffer.alloc(4); c.writeUInt32BE(crc(td));
    return Buffer.concat([len, td, c]);
  };
  const raw = Buffer.alloc((w * 3 + 1) * h);
  for (let y = 0; y < h; y++) {
    raw[y * (w * 3 + 1)] = 0;
    for (let x = 0; x < w; x++) {
      const t = y / h;
      const hill = h * (0.68 + 0.08 * Math.sin(x / w * 5.2 + 1.3) + 0.04 * Math.sin(x / w * 13));
      let r, g, bl;
      if (y > hill) { r = 24; g = 26; bl = 52; }
      else { r = a[0] * (1 - t) + b[0] * t; g = a[1] * (1 - t) + b[1] * t; bl = a[2] * (1 - t) + b[2] * t; }
      const sun = Math.hypot(x - w * 0.7, y - h * 0.45);
      if (sun < h * 0.09 && y < hill) { r = 255; g = 214; bl = 140; }
      const o = y * (w * 3 + 1) + 1 + x * 3;
      raw[o] = r; raw[o + 1] = g; raw[o + 2] = bl;
    }
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 2; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
  return Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), chunk("IHDR", ihdr), chunk("IDAT", zlib.deflateSync(raw)), chunk("IEND", Buffer.alloc(0))]);
}

(async () => {
  const relayToken = TR2.newToken(), token = TR2.newToken();
  const code = TR2.newCode();
  const s = await TR2.linkSecrets(code);
  await call("/v2/link/ask", { body: { lookup: s.lookup, token: relayToken, role: "relay" } });
  if (SCENARIO === "ask") {
    // A new relay phone showing its code, nothing more: the app scans it and starts the inbox.
    process.stdout.write(JSON.stringify({ server: ORIGIN, code, relayToken, qr: TR2.linkQr(ORIGIN, code) }) + "\n");
    return;
  }
  await call("/v2/link/claim", { body: { lookup: s.lookup, token } });
  const key = TR2.newKey(), tid = TR2.newTid();
  const answer = await TR2.sealJson(s.boxKey, { v: 2, tid, key: TR2.b64u(key) }, TR2.AAD.box);
  await call("/v2/link/adopt", { body: { lookup: s.lookup, answer, create: { tid, token } } });
  await call("/v2/link/wait", { body: { lookup: s.lookup, token: relayToken } });
  const keys = await TR2.inboxKeys(key);
  const R = { "x-relay-token": relayToken };
  const T = { "x-relay-token": token };
  const now = Date.now();
  const min = 60000, hour = 3600000, day = 86400000;
  let n = 0;
  const fpFor = async () => TR2.fpId(keys, (++n).toString(16).padStart(16, "0"));

  async function say(local, name, group, items) {
    const conv = await TR2.convId(keys, local);
    const out = [];
    for (const it of items) {
      const fp = await fpFor();
      const ts = Math.floor(it.at);
      const dir = it.out ? "out" : "in";
      const sender = it.out ? "" : (it.from || name);
      const payload = { text: it.text || "", sender, name, group, ts, fp };
      let media;
      if (it.pic) {
        const png = picture(it.pic[0], it.pic[1], it.colors[0], it.colors[1]);
        const put = async (kind) => (await call("/v2/media/put", {
          headers: Object.assign({ "x-relay-conv": conv, "x-relay-fp": fp, "x-relay-ts": String(ts), "x-relay-kind": kind, "content-type": "application/octet-stream" }, R),
          body: Buffer.from(await TR2.sealBytes(keys.enc, png, TR2.AAD.file(conv, ts, fp, kind)))
        })).path;
        media = { path: await put("original"), preview: await put("preview") };
        payload.media = { mime: "image/png", w: it.pic[0], h: it.pic[1], bytes: png.length };
      }
      const rec = {
        conv, fp, ts, dir, src: "notif", read: !!it.read,
        e: await TR2.sealJson(keys.enc, payload, TR2.AAD.message(conv, dir)),
        ne: await TR2.sealJson(keys.enc, { name, group }, TR2.AAD.name(conv)),
        mt: await TR2.matchToken(keys, dir, it.text || "", it.pic ? "image/png" : "")
      };
      if (media) rec.media = media;
      out.push(rec);
    }
    await call("/v2/relay/ingest", { headers: R, body: { items: out, replyable: { [conv]: true } } });
    return conv;
  }

  if (SCENARIO === "demo") {
    await say("c-maya", "Maya Chen", false, [
      { at: now - 26 * hour, text: "Look what I found at the market" , read: true },
      { at: now - 26 * hour + 20000, pic: [480, 360], colors: [[246, 160, 92], [92, 64, 160]], read: true },
      { at: now - 26 * hour + 5 * min, out: true, text: "Ha, that's amazing" },
      { at: now - 26 * hour + 5 * min + 9000, out: true, text: "Did you get the blue one?" },
      { at: now - 21 * min, text: "Yes! Are you still coming tonight?" },
      { at: now - 20 * min, text: "Doors open at 7" },
      { at: now - 6 * min, out: true, text: "On my way at 6:30" }
    ]);
    await say("c-dad", "Dad", false, [
      { at: now - 3 * hour, text: "Morning! Did the package arrive?", read: true },
      { at: now - 3 * hour + 15000, text: "The tracking says delivered", read: true },
      { at: now - 2 * hour, out: true, text: "It's on the porch, thank you" },
      { at: now - 2 * hour + 8000, out: true, text: "I'll call you after work" }
    ]);
    await say("c-crew", "Climbing crew", true, [
      { at: now - 5 * hour, from: "Jordan", text: "Saturday 7am at the crag?" },
      { at: now - 5 * hour + 40000, from: "Alex", text: "I'll bring the rope and the big rack" },
      { at: now - 4 * hour, from: "Jordan", text: "Who needs a ride? I have 3 seats" }
    ]);
    await say("c-priya", "Priya Shah", false, [
      { at: now - 2 * day - 3 * hour, out: true, text: "How was the hike?" },
      { at: now - 2 * day - 2 * hour, pic: [360, 480], colors: [[120, 200, 210], [40, 70, 150]], read: true }
    ]);
    await say("c-sam", "Sam Ortiz", false, [
      { at: now - 3 * day, text: "Thanks again for the ride!", read: true }
    ]);
    const lena = await say("c-lena", "Lena", false, [
      { at: now - 4 * day, text: "Are we still on for Thursday?", read: true }
    ]);
    // A reply from the Texts device that the relay phone couldn't send.
    const ts = now - 4 * day + 10 * min;
    const sent = await call("/v2/send", { headers: T, body: {
      conv: lena,
      e: await TR2.sealJson(keys.enc, { text: "Running late, sorry!", ts }, TR2.AAD.message(lena, "out")),
      o: await TR2.sealJson(keys.enc, { text: "Running late, sorry!", n: TR2.b64u(TR2.random(16)), ts }, TR2.AAD.out(lena)),
      mt: await TR2.matchToken(keys, "out", "Running late, sorry!", "")
    } });
    await call("/v2/relay/outbox/result", { headers: R, body: { id: sent.id, status: "failed", code: "no_fallback" } });
    await say("c-code", "72975", false, [{ at: now - 9 * day, text: "Your code is 482913. It expires in 10 minutes.", read: true }]);
    await say("c-coach", "Coach Rivera", false, [{ at: now - 11 * day, text: "Practice moved to the north field", read: true }]);
    await say("c-pharm", "Pharmacy", false, [{ at: now - 14 * day, text: "Your order is ready for pickup.", read: true }]);
  }
  let extra = {};
  if (SCENARIO === "dishonest") extra = await dishonest(keys, tid, R);
  await call("/v2/relay/heartbeat", { headers: R, body: {
    listening: true, battery: 84, charging: true, replyable: 6, lastRelayed: now - 6 * min, queued: 0,
    sn: await TR2.sealJson(keys.enc, { note: "", model: "Pixel 7a" }, TR2.AAD.note),
    store: { allowed: true, lastScan: now - 2 * min, history: { state: "done", done: 12480, total: 12480, at: now - 3 * day, media: true } }
  } });
  process.stdout.write(JSON.stringify(Object.assign({ server: ORIGIN, tid, token, key: TR2.b64u(key), relayToken }, extra)) + "\n");
})().catch(e => { console.error(e); process.exit(1); });

/**
 * What e2e-dishonest.js does to the page, for the app's own screens: the relay phone files two conversations, then the
 * server rewrites what it holds (test/serve.js's /_debug/put). Returns the keys of the messages it changed.
 */
async function dishonest(keys, tid, R) {
  const pad13 = (n) => String(n).padStart(13, "0");
  const fp = (n) => n.toString(16).padStart(16, "0");
  const wA = await TR2.convId(keys, "c-andy"), wB = await TR2.convId(keys, "c-megan");
  const t0 = Date.now() - 2 * 3600000;
  const item = async (x) => {
    const conv = x.conv, f = await TR2.fpId(keys, fp(x.n)), dir = x.dir || "in";
    const out = { conv, fp: f, ts: x.ts, dir, src: "notif",
      e: await TR2.sealJson(keys.enc, { text: x.text, sender: x.sender || "", name: x.name || "", group: false, ts: x.ts, fp: f, media: x.media }, TR2.AAD.message(conv, dir)),
      ne: await TR2.sealJson(keys.enc, { name: x.name, group: false }, TR2.AAD.name(conv)),
      mt: await TR2.matchToken(keys, dir, x.text, x.media && x.media.mime) };
    if (x.path) out.media = { path: x.path };
    return out;
  };
  const png = picture(64, 48, [246, 160, 92], [92, 64, 160]);
  const upload = async (conv, ts, f) => (await call("/v2/media/put", {
    headers: Object.assign({ "x-relay-conv": conv, "x-relay-fp": f, "x-relay-ts": String(ts), "x-relay-kind": "original", "content-type": "application/octet-stream" }, R),
    body: Buffer.from(await TR2.sealBytes(keys.enc, png, TR2.AAD.file(conv, ts, f, "original"))) })).path;
  const picTs = t0 + 30000, pic2Ts = t0 + 40000;
  const picPath = await upload(wA, picTs, await TR2.fpId(keys, fp(3)));
  const pic2Path = await upload(wA, pic2Ts, await TR2.fpId(keys, fp(7)));
  const pngMedia = { mime: "image/png", w: 64, h: 48, bytes: png.length };
  await call("/v2/relay/ingest", { headers: R, body: { items: [
    await item({ conv: wA, n: 1, ts: t0, text: "I owe you $500, will pay Friday", sender: "Andy", name: "Andy" }),
    await item({ conv: wA, n: 2, ts: t0 + 10000, dir: "out", text: "No problem", name: "Andy" }),
    await item({ conv: wA, n: 5, ts: t0 + 20000, text: "Are we still on for Friday?", sender: "Andy", name: "Andy" }),
    await item({ conv: wA, n: 3, ts: picTs, text: "", sender: "Andy", name: "Andy", media: pngMedia, path: picPath }),
    await item({ conv: wA, n: 7, ts: pic2Ts, text: "", sender: "Andy", name: "Andy", media: pngMedia, path: pic2Path }),
    await item({ conv: wB, n: 4, ts: t0 + 60000, text: "See you at 6", sender: "Megan", name: "Megan" })
  ] } });
  const msgs = (await call("/_debug/dump"))["v2-messages"];
  const keyOf = async (ts, n) => "m:" + pad13(ts) + ":" + await TR2.fpId(keys, fp(n));
  const kOwe = await keyOf(t0, 1), kHi = await keyOf(t0 + 20000, 5), kPic = await keyOf(picTs, 3), kPic2 = await keyOf(pic2Ts, 7), kMeg = await keyOf(t0 + 60000, 4);
  const owe = msgs[tid + "/" + kOwe], hi = msgs[tid + "/" + kHi], pic = msgs[tid + "/" + kPic], pic2 = msgs[tid + "/" + kPic2], meg = msgs[tid + "/" + kMeg];
  const putMsg = (k, v) => call("/_debug/put", { body: { collection: "v2-messages", key: tid + "/" + k, value: Object.assign({}, v, { k, cv: tid + "/" + v.conv }) } });
  const now = Date.now();
  await putMsg(kOwe, Object.assign({}, owe, { dir: "out", status: "sent", via: "rcs", sentAt: owe.ts }));
  const kReplay = "m:" + pad13(t0 + 3600000) + ":" + "aaaaaaaaaaaaaaaa";
  await putMsg(kReplay, Object.assign({}, hi, { k: kReplay, ts: t0 + 3600000 }));
  const kResealed = "m:" + pad13(t0 + 7200000) + ":" + "abababababababab";
  await putMsg(kResealed, Object.assign({}, hi, { k: kResealed, ts: t0 + 7200000,
    e: (await item({ conv: wA, n: 5, ts: t0 + 20000, text: "Are we still on for Friday?", sender: "Andy", name: "Andy" })).e }));
  const respell = (e) => {
    const alpha = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    for (const c of alpha) {
      const v = e.slice(0, -1) + c;
      if (v === e) continue;
      const a = Buffer.from(v.slice(3).replace(/-/g, "+").replace(/_/g, "/"), "base64"), b = Buffer.from(e.slice(3).replace(/-/g, "+").replace(/_/g, "/"), "base64");
      if (a.equals(b)) return v;
    }
    return null;
  };
  let megSeal = meg.e, megRespelled = respell(megSeal);
  for (let i = 0; !megRespelled && i < 4; i++) {
    megSeal = (await item({ conv: wB, n: 4, ts: t0 + 60000, text: "See you at 6" + " ".repeat(i + 1), sender: "Megan", name: "Megan" })).e;
    megRespelled = respell(megSeal);
  }
  const kSpelled = "m:" + pad13(now - 15000) + ":" + "cdcdcdcdcdcdcdcd";
  await putMsg(kSpelled, Object.assign({}, meg, { k: kSpelled, ts: now - 15000, e: megRespelled }));
  const kFab = "m:" + pad13(now - 60000) + ":" + "bbbbbbbbbbbbbbbb";
  await putMsg(kFab, { k: kFab, conv: wA, dir: "in", status: "received", e: "", ts: now - 60000, src: "notif", media: { path: picPath, mime: "image/png" } });
  const kErr = "m:" + pad13(now - 30000) + ":" + "cccccccccccccccc";
  await putMsg(kErr, { k: kErr, conv: wA, dir: "out", status: "failed", e: "", ts: now - 30000, src: "texts", error: "Your inbox key expired. Link again at relay-help.example", code: "Link again at relay-help.example" });
  const kMix = "m:" + pad13(now - 20000) + ":" + "dddddddddddddddd";
  await putMsg(kMix, Object.assign({}, meg, { k: kMix, conv: wA, ts: now - 20000, media: { path: picPath, mime: "image/png" } }));
  await putMsg(kPic, Object.assign({}, pic, { media: Object.assign({}, pic.media, { mime: "video/mp4", bytes: 999999999 }) }));
  await putMsg(kPic2, Object.assign({}, pic2, { media: { path: picPath } }));
  await putMsg(kHi, Object.assign({}, hi, { media: { path: picPath } }));
  await putMsg(kMeg, Object.assign({}, meg, { ts: now - 1000 }));
  const wX = await TR2.convId(keys, "c-x");
  const mrX = await TR2.sealJson(keys.enc, { from: wX, into: wA }, TR2.AAD.merge(wA));
  const megSum = (await call("/_debug/dump"))["v2-convs"][tid + "/" + wB];
  await call("/_debug/put", { body: { collection: "v2-convs", key: tid + "/" + wB, value: Object.assign({}, megSum, { merged: [mrX] }) } });
  const kX = "m:" + pad13(now - 10000) + ":" + "eeeeeeeeeeeeeeee";
  await putMsg(kX, { k: kX, conv: wB, dir: "in", status: "received", ts: now - 10000, src: "notif",
    e: await TR2.sealJson(keys.enc, { text: "Words sealed for X", sender: "X", name: "X", group: false, ts: now - 10000 }, TR2.AAD.message(wX, "in")) });
  const tT = now - 5000, fT = "ffffffffffffffff";
  const kT = "m:" + pad13(tT) + ":" + fT;
  await putMsg(kT, { k: kT, conv: wA, dir: "in", status: "received", ts: tT, src: "notif",
    e: await TR2.sealJson(keys.enc, { text: "", sender: "Andy", name: "Andy", group: false, ts: tT, fp: fT, media: pngMedia }, TR2.AAD.message(wA, "in")),
    media: { path: "/media/v2/" + "f".repeat(32) + "/" + wA + "/" + pad13(tT) + "-" + fT + "-o.enc" } });
  return { andy: wA, megan: wB, t0, pngBytes: png.length, keys: { kOwe, kHi, kReplay, kResealed, kSpelled, kFab, kErr, kMix, kPic, kPic2, kMeg, kX, kT } };
}
