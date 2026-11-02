// A smoke test against a running inbox server, hosted or self-hosted: a scratch inbox is made with a stand-in relay
// phone and one device, a text goes through in each direction with its result, a conversation is read, a picture is
// stored and fetched back, and both leave. Nothing readable ever reaches the server, so this is safe to run against a
// live one; what it leaves behind is one empty inbox's sealed records.
//   node test/smoke.js https://relay--bradwhite.apps.hedyassist.com
//   node test/smoke.js http://127.0.0.1:8787          (a self-hosted server)
const assert = require("assert");
const TR2 = require("../web/tr2.js");

const ORIGIN = (process.argv[2] || "").replace(/\/+$/, "");
if (!/^https?:\/\//.test(ORIGIN)) {
  console.error("usage: node test/smoke.js <server url>");
  process.exit(2);
}
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

async function call(p, o) {
  o = o || {};
  const headers = Object.assign({}, o.headers || {});
  let body = o.body;
  if (body !== undefined && !Buffer.isBuffer(body) && !(body instanceof Uint8Array)) { body = JSON.stringify(body); headers["content-type"] = "application/json"; }
  const res = await fetch(ORIGIN + p, { method: o.method || (body !== undefined ? "POST" : "GET"), headers, body });
  const text = await res.text();
  let d = {};
  try { d = text ? JSON.parse(text) : {}; } catch { d = { raw: text }; }
  d._status = res.status;
  return d;
}

/** A tiny PNG. */
function png() {
  const zlib = require("zlib");
  const w = 8, h = 6;
  const raw = Buffer.alloc((w * 3 + 1) * h);
  for (let y = 0; y < h; y++) { raw[y * (w * 3 + 1)] = 0; for (let x = 0; x < w; x++) { const i = y * (w * 3 + 1) + 1 + x * 3; raw[i] = 246; raw[i + 1] = 160; raw[i + 2] = 92; } }
  const crc = (buf) => { let c = ~0; for (const b of buf) { c ^= b; for (let k = 0; k < 8; k++) c = c & 1 ? (c >>> 1) ^ 0xEDB88320 : c >>> 1; } return (~c) >>> 0; };
  const chunk = (type, data) => { const len = Buffer.alloc(4); len.writeUInt32BE(data.length); const td = Buffer.concat([Buffer.from(type), data]); const c = Buffer.alloc(4); c.writeUInt32BE(crc(td)); return Buffer.concat([len, td, c]); };
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 2;
  return Buffer.concat([Buffer.from([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]), chunk("IHDR", ihdr), chunk("IDAT", zlib.deflateSync(raw)), chunk("IEND", Buffer.alloc(0))]);
}

const results = [];
async function step(name, fn) {
  const t = Date.now();
  try { await fn(); results.push([name, "ok"]); console.log("ok      " + name + " (" + (Date.now() - t) + " ms)"); }
  catch (e) { results.push([name, "FAIL"]); console.log("FAIL    " + name + "\n        " + String(e && e.stack || e).split("\n").slice(0, 4).join("\n        ")); }
}

(async () => {
  const relay = { token: TR2.newToken() }, texts = { token: TR2.newToken() };
  const R = { "x-relay-token": relay.token }, T = { "x-relay-token": texts.token };
  let tid, keys, conv;
  await step("link: the relay phone offers a code and the device adopts it into a new inbox", async () => {
    const s = await TR2.linkSecrets(TR2.newCode());
    const asked = await call("/v2/link/ask", { body: { lookup: s.lookup, token: relay.token, role: "relay", label: "Smoke relay" } });
    assert.strictEqual(asked._status, 200, JSON.stringify(asked));
    const K = TR2.newKey();
    tid = TR2.newTid();
    const adopted = await call("/v2/link/adopt", { body: { lookup: s.lookup, answer: await TR2.sealJson(s.boxKey, { v: 2, tid, key: TR2.b64u(K) }, TR2.AAD.box), create: { tid, token: texts.token, label: "Smoke device" } } });
    assert.strictEqual(adopted._status, 200, JSON.stringify(adopted));
    keys = await TR2.inboxKeys(K);
    conv = await TR2.convId(keys, "smoke-conv");
    const ping = await call("/v2/ping", { headers: T });
    assert.strictEqual(ping._status, 200, JSON.stringify(ping));
  });
  let feed;
  await step("the device reads an empty inbox and learns where the log starts", async () => {
    const inbox = await call("/v2/inbox", { headers: T });
    assert.strictEqual(inbox._status, 200, JSON.stringify(inbox));
    assert.ok(Array.isArray(inbox.items) && inbox.items.length === 0, JSON.stringify(inbox));
    assert.ok(/^f:[0-9]{13}/.test(inbox.feed || ""), JSON.stringify(inbox));
    feed = inbox.feed;
  });
  await step("the relay phone checks in with what it can do", async () => {
    const hb = await call("/v2/relay/heartbeat", { headers: R, body: { listening: true, battery: 90, charging: true, replyable: 1, queued: 0, smsFallback: true, mmsMax: 1000000 - 3072, imageReply: false,
      sn: await TR2.sealJson(keys.enc, { note: "", history: "", model: "Smoke phone" }, TR2.AAD.note) } });
    assert.strictEqual(hb._status, 200, JSON.stringify(hb));
    const status = await call("/v2/status", { headers: T });
    assert.strictEqual(status.relay.mmsMax, 1000000 - 3072, JSON.stringify(status));
    assert.strictEqual(status.relay.imageReply, false);
    assert.strictEqual(status.relay.smsFallback, true);
  });
  let n = 0;
  const arrives = async (text, out, media, at) => {
    const ts = at || Date.now();
    const fp = await TR2.fpId(keys, (++n).toString(16).padStart(16, "0"));
    const dir = out ? "out" : "in";
    const payload = { text, sender: out ? "" : "Sam", name: "Sam", group: false, ts, fp };
    if (media) payload.media = { mime: media.mime, w: media.w, h: media.h, bytes: media.bytes };
    const item = { conv, fp, ts, dir, src: "notif", read: !!out,
      e: await TR2.sealJson(keys.enc, payload, TR2.AAD.message(conv, dir)),
      ne: await TR2.sealJson(keys.enc, { name: "Sam", group: false }, TR2.AAD.name(conv)), mt: await TR2.matchToken(keys, dir, text, media ? media.mime : "") };
    if (media) item.media = { path: media.path };
    const r = await call("/v2/relay/ingest", { headers: R, body: { items: [item], replyable: { [conv]: true } } });
    assert.strictEqual(r._status, 200, JSON.stringify(r));
    return { ts, fp };
  };
  const syncUntil = async (pred, what) => {
    for (let i = 0; i < 40; i++) {
      const d = await fetch(ORIGIN + "/v2/sync?feed=" + encodeURIComponent(feed), { headers: T }).then(r => r.json());
      if (pred(d)) return d;
      await sleep(500);
    }
    throw new Error("never saw " + what);
  };
  await step("a text from the relay phone reaches the device, opened and unread", async () => {
    await arrives("Smoke test: hello", false);
    const d = await syncUntil(d => d.items.some(m => m.dir === "in"), "the text in the log");
    const m = d.items.find(m => m.dir === "in");
    const opened = await TR2.openJson(keys.enc, m.e, TR2.AAD.message(conv, "in"));
    assert.strictEqual(opened.text, "Smoke test: hello");
    const c = d.convs.find(c => c.conv === conv);
    assert.ok(c && c.unread === 1, JSON.stringify(d.convs));
    assert.ok(!("text" in m) && !("sender" in m), "nothing readable in the record");
  });
  let sent;
  await step("a reply from the device reaches the relay phone's outbox, goes out, and the device sees it sent", async () => {
    const ts = Date.now();
    const e = await TR2.sealJson(keys.enc, { text: "Smoke test: reply", ts }, TR2.AAD.message(conv, "out"));
    const o = await TR2.sealJson(keys.enc, { text: "Smoke test: reply", n: TR2.b64u(TR2.random(16)), ts }, TR2.AAD.out(conv));
    sent = await call("/v2/send", { headers: T, body: { conv, e, o, mt: await TR2.matchToken(keys, "out", "Smoke test: reply", "") } });
    assert.strictEqual(sent._status, 200, JSON.stringify(sent));
    let item = null;
    for (let i = 0; i < 20 && !item; i++) {
      const ob = await call("/v2/relay/outbox", { headers: R });
      item = (ob.items || []).find(x => x.id === sent.id);
      if (!item) await sleep(300);
    }
    assert.ok(item, "the reply in the outbox");
    assert.strictEqual((await TR2.openJson(keys.enc, item.o, TR2.AAD.out(conv))).text, "Smoke test: reply");
    const res = await call("/v2/relay/outbox/result", { headers: R, body: { id: sent.id, status: "sent", via: "rcs", canReply: true } });
    assert.strictEqual(res._status, 200, JSON.stringify(res));
    const d = await syncUntil(d => d.items.some(m => m.k === sent.id && m.status === "sent"), "the reply as sent");
    assert.strictEqual(d.items.find(m => m.k === sent.id).via, "rcs");
  });
  await step("reading the conversation on the device clears its count for every device", async () => {
    const r = await call("/v2/read", { headers: T, body: { conv } });
    assert.strictEqual(r._status, 200, JSON.stringify(r));
    const d = await syncUntil(d => d.convs.some(c => c.conv === conv && c.unread === 0), "unread 0");
    assert.ok(d.items.every(m => !m.seen), "a read is not a message");
  });
  await step("a text Messages kept quiet files its conversation under Filtered; the device's word moves it back", async () => {
    const quiet = await TR2.convId(keys, "c-smoke-quiet");
    const ts = Date.now();
    const fp = await TR2.fpId(keys, (++n).toString(16).padStart(16, "0"));
    const item = { conv: quiet, fp, ts, dir: "in", src: "store", quiet: true,
      e: await TR2.sealJson(keys.enc, { text: "Smoke test: quiet", sender: "Sam", name: "Sam", group: false, ts, fp }, TR2.AAD.message(quiet, "in")),
      ne: await TR2.sealJson(keys.enc, { name: "Sam", group: false }, TR2.AAD.name(quiet)), mt: await TR2.matchToken(keys, "in", "Smoke test: quiet", "") };
    const r = await call("/v2/relay/ingest", { headers: R, body: { items: [item] } });
    assert.strictEqual(r._status, 200, JSON.stringify(r));
    let d = await syncUntil(d => d.convs.some(c => c.conv === quiet && c.filtered === true && c.filteredBy === "relay"), "the conversation under Filtered");
    const back = await call("/v2/filter", { headers: T, body: { conv: quiet, filtered: false } });
    assert.strictEqual(back._status, 200, JSON.stringify(back));
    d = await syncUntil(d => d.convs.some(c => c.conv === quiet && c.filtered === false && c.filteredBy === "user"), "the device's word");
    assert.ok(d.items.every(m => !m.seen), "a filing is not a message");
  });
  await step("a picture from the relay phone is stored sealed and comes back byte for byte", async () => {
    const ts = Date.now();
    const fp = await TR2.fpId(keys, "smokepicture0000");
    const picture = png();
    const sealed = await TR2.sealBytes(keys.enc, new Uint8Array(picture), TR2.AAD.file(conv, ts, fp, "original"));
    const put = await call("/v2/media/put", { method: "POST", body: Buffer.from(sealed), headers: Object.assign({ "x-relay-conv": conv, "x-relay-fp": fp, "x-relay-ts": String(ts), "x-relay-kind": "original", "content-type": "application/octet-stream" }, R) });
    assert.strictEqual(put._status, 200, JSON.stringify(put));
    assert.ok(put.path.startsWith("/media/v2/" + tid + "/" + conv + "/"), put.path);
    const item = { conv, fp, ts, dir: "in", src: "notif",
      e: await TR2.sealJson(keys.enc, { text: "", sender: "Sam", name: "Sam", group: false, ts, fp, media: { mime: "image/png", w: 8, h: 6, bytes: picture.length } }, TR2.AAD.message(conv, "in")),
      mt: await TR2.matchToken(keys, "in", "", "image/png"), media: { path: put.path } };
    const r = await call("/v2/relay/ingest", { headers: R, body: { items: [item] } });
    assert.strictEqual(r._status, 200, JSON.stringify(r));
    const d = await syncUntil(d => d.items.some(m => m.media && m.media.path === put.path), "the picture's message");
    const got = await call("/v2/media?p=" + encodeURIComponent(put.path), { headers: T });
    assert.strictEqual(got._status, 200, JSON.stringify(got));
    const bytes = Buffer.from(await (await fetch(got.url)).arrayBuffer());
    assert.ok(!bytes.includes("IHDR"), "stored sealed");
    const back = await TR2.openBytes(keys.enc, new Uint8Array(bytes), TR2.AAD.file(conv, ts, fp, "original"));
    assert.strictEqual(Buffer.compare(Buffer.from(back), picture), 0, "the picture came back whole");
    // another conversation's path is refused
    const other = await call("/v2/media?p=" + encodeURIComponent(put.path.replace(conv, "0".repeat(32))), { headers: T });
    assert.ok(other._status >= 400, JSON.stringify(other));
  });
  await step("both leave, and the device's token is refused from then on", async () => {
    const left = await call("/v2/leave", { headers: T, body: {} });
    assert.strictEqual(left._status, 200, JSON.stringify(left));
    const refused = await call("/v2/ping", { headers: T });
    assert.ok(refused._status === 401 || refused._status === 403, JSON.stringify(refused));
    const relayLeft = await call("/v2/leave", { headers: R, body: {} });
    assert.strictEqual(relayLeft._status, 200, JSON.stringify(relayLeft));
  });
  const failed = results.filter(x => x[1] === "FAIL");
  console.log((failed.length ? "FAILED " + failed.length + " of " + results.length : "all " + results.length + " passed") + " against " + ORIGIN + (tid ? " (inbox " + tid + ")" : ""));
  process.exit(failed.length ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
