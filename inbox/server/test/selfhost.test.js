// The self-hosted server, started the way people start it (node server/paigr-server.js, set up by environment
// variables), driven over HTTP and its realtime socket the way the phones drive it: linking, texts both ways, the
// doorbell, pictures by both upload routes, the page, then a restart that keeps everything and a relay phone the
// watch reports offline.
//   node server/test/selfhost.test.js
const assert = require("assert");
const fs = require("fs");
const os = require("os");
const net = require("net");
const path = require("path");
const crypto = require("crypto");
const { spawn } = require("child_process");
const TR2 = require("../../web/tr2.js");

const SERVER = path.join(__dirname, "..", "paigr-server.js");
const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), "paigr-selfhost-"));
let port = 0;
let origin = "";
let proc = null;
let output = "";

function freePort() {
  return new Promise((resolve) => {
    const s = net.createServer();
    s.listen(0, "127.0.0.1", () => { const p = s.address().port; s.close(() => resolve(p)); });
  });
}

async function startServer(extraEnv) {
  output = "";
  proc = spawn(process.execPath, [SERVER], {
    env: Object.assign({}, process.env, { PORT: String(port), HOST: "127.0.0.1", DATA_DIR: dataDir }, extraEnv || {}),
    stdio: ["ignore", "pipe", "pipe"]
  });
  proc.stdout.on("data", (d) => { output += d; });
  proc.stderr.on("data", (d) => { output += d; });
  const until = Date.now() + 15000;
  for (;;) {
    try {
      const r = await fetch(origin + "/healthz");
      if (r.ok) return;
    } catch { /* not up yet */ }
    if (Date.now() > until) throw new Error("the server didn't start:\n" + output);
    await new Promise((r) => setTimeout(r, 100));
  }
}

async function stopServer() {
  const p = proc;
  proc = null;
  const code = await new Promise((resolve) => { p.on("exit", (c) => resolve(c)); p.kill("SIGTERM"); });
  assert.strictEqual(code, 0, "a stop asked for is a clean exit:\n" + output);
}

/** One call, as a phone makes it: (method, route, JSON body or Buffer, query, headers). The reply's JSON, with _status. */
async function v2(method, route, body, query, headers) {
  const h = Object.assign({ accept: "application/json" }, headers || {});
  let b;
  if (Buffer.isBuffer(body)) { b = body; h["content-type"] = h["content-type"] || "application/octet-stream"; }
  else if (body !== undefined && body !== null) { b = JSON.stringify(body); h["content-type"] = "application/json"; }
  const qs = query ? "?" + new URLSearchParams(query).toString() : "";
  const res = await fetch(origin + route + qs, { method, headers: h, body: b });
  const text = await res.text();
  let data = {};
  try { data = text ? JSON.parse(text) : {}; } catch { data = { raw: text }; }
  return Object.assign({ _status: res.status }, data);
}
const H = (dev) => ({ "x-relay-token": dev.token });
const call = (dev, method, route, body, query) => v2(method, route, body, query, H(dev));
const phone = (role, label) => ({ role, label: label || role, token: TR2.newToken(), tid: null, keys: null });

async function link(relay, texts) {
  const code = TR2.newCode();
  const s = await TR2.linkSecrets(code);
  assert.strictEqual((await v2("POST", "/v2/link/ask", { lookup: s.lookup, token: relay.token, role: "relay", label: relay.label }))._status, 200);
  assert.strictEqual((await v2("POST", "/v2/link/wait", { lookup: s.lookup, token: relay.token }))._status, 202);
  const claim = await v2("POST", "/v2/link/claim", { lookup: s.lookup, token: texts.token, label: texts.label });
  assert.strictEqual(claim._status, 200, JSON.stringify(claim));
  assert.strictEqual(claim.kind, "ask");
  const raw = TR2.newKey(), tid = TR2.newTid();
  const answer = await TR2.sealJson(s.boxKey, { v: 2, tid, key: TR2.b64u(raw) }, TR2.AAD.box);
  const adopt = await v2("POST", "/v2/link/adopt", { lookup: s.lookup, answer, create: { tid, token: texts.token, label: texts.label } });
  assert.strictEqual(adopt._status, 200, JSON.stringify(adopt));
  texts.tid = tid;
  texts.keys = await TR2.inboxKeys(raw);
  const r = await v2("POST", "/v2/link/wait", { lookup: s.lookup, token: relay.token });
  assert.strictEqual(r._status, 200, JSON.stringify(r));
  const box = await TR2.openJson(s.boxKey, r.answer, TR2.AAD.box);
  relay.tid = box.tid;
  relay.keys = await TR2.inboxKeys(TR2.unb64u(box.key));
}

/** A socket on the device's own channel; `rings` fills with what the server sends it. */
async function doorbell(dev) {
  const t = await call(dev, "GET", "/v2/ticket");
  assert.strictEqual(t._status, 200, JSON.stringify(t));
  assert.strictEqual(t.webSocketUrl, "ws://127.0.0.1:" + port + "/_realtime");
  assert.strictEqual(t.channel, "tr2/" + dev.tid + "/" + (dev.role === "relay" ? "o" : "i"));
  const ws = new WebSocket(t.webSocketUrl + "?ticket=" + encodeURIComponent(t.ticket));
  const rings = [];
  ws.onmessage = (e) => { const m = JSON.parse(e.data); rings.push({ type: m.type, payload: JSON.parse(m.payloadJson) }); };
  await new Promise((resolve, reject) => { ws.onopen = resolve; ws.onerror = () => reject(new Error("the doorbell didn't open")); });
  return { ws, rings, ticket: t };
}

async function ringOf(bell, type, ms) {
  const until = Date.now() + (ms || 5000);
  for (;;) {
    const r = bell.rings.find((x) => x.type === type);
    if (r) return r;
    if (Date.now() > until) throw new Error("no " + type + " ring; heard " + JSON.stringify(bell.rings));
    await new Promise((res) => setTimeout(res, 25));
  }
}

const pad13 = (n) => String(n).padStart(13, "0");
const fp = (n) => n.toString(16).padStart(16, "0");

async function sealedText(relay, conv, text, ts, n) {
  const k = relay.keys;
  const wconv = await TR2.convId(k, conv);
  const wfp = await TR2.fpId(k, fp(n));
  const out = { conv: wconv, fp: wfp, ts, dir: "in", src: "notif" };
  out.e = await TR2.sealJson(k.enc, { text, sender: "Andy", name: "Andy", group: false, ts, fp: wfp }, TR2.AAD.message(wconv, "in"));
  out.ne = await TR2.sealJson(k.enc, { name: "Andy", group: false }, TR2.AAD.name(wconv));
  const mt = await TR2.matchToken(k, "in", text, "");
  if (mt) out.mt = mt;
  return out;
}

/** The raw socket: a hand-made handshake, so the frames themselves can be checked. */
function rawSocket(ticket) {
  return new Promise((resolve, reject) => {
    const s = net.connect(port, "127.0.0.1");
    const key = crypto.randomBytes(16).toString("base64");
    let buf = Buffer.alloc(0);
    let upgraded = false;
    s.on("error", reject);
    s.on("data", (d) => {
      buf = Buffer.concat([buf, d]);
      if (!upgraded) {
        const end = buf.indexOf("\r\n\r\n");
        if (end < 0) return;
        const head = buf.subarray(0, end).toString();
        buf = buf.subarray(end + 4);
        if (!/^HTTP\/1\.1 101 /.test(head)) return resolve({ refused: head.split("\r\n")[0] });
        const accept = crypto.createHash("sha1").update(key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").digest("base64");
        assert.ok(head.includes("Sec-WebSocket-Accept: " + accept));
        upgraded = true;
        resolve({ s, frames: () => frames(), write: (op, payload, masked) => s.write(frame(op, payload, masked !== false)) });
      }
    });
    function frames() {
      const out = [];
      while (buf.length >= 2) {
        const len = buf[1] & 0x7f;
        if (len > 125 || buf.length < 2 + len) break;
        out.push({ op: buf[0] & 0x0f, payload: buf.subarray(2, 2 + len) });
        buf = buf.subarray(2 + len);
      }
      return out;
    }
    function frame(op, payload, masked) {
      const mask = crypto.randomBytes(4);
      const body = Buffer.from(payload);
      if (masked) for (let i = 0; i < body.length; i++) body[i] ^= mask[i & 3];
      return Buffer.concat([Buffer.from([0x80 | op, (masked ? 0x80 : 0) | body.length]), masked ? mask : Buffer.alloc(0), body]);
    }
    s.write("GET /_realtime?ticket=" + encodeURIComponent(ticket) + " HTTP/1.1\r\nHost: 127.0.0.1:" + port
      + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: " + key + "\r\nSec-WebSocket-Version: 13\r\n\r\n");
  });
}

/** Node's SQLite, without its "experimental" notice. */
function quietly(fn) {
  const emit = process.emitWarning;
  process.emitWarning = (w, ...rest) => (/SQLite/.test(String(w && w.message || w)) ? undefined : emit.call(process, w, ...rest));
  try { return fn(); } finally { process.emitWarning = emit; }
}

async function until(fn, what) {
  const end = Date.now() + 5000;
  for (;;) {
    const v = fn();
    if (v) return v;
    if (Date.now() > end) throw new Error("waited for " + what);
    await new Promise((r) => setTimeout(r, 20));
  }
}

(async () => {
  port = await freePort();
  origin = "http://127.0.0.1:" + port;
  await startServer();
  assert.ok(/Paigr server [0-9.]+ is running/.test(output), output);
  assert.ok(!/ExperimentalWarning/.test(output), "no warnings on start");

  // ================================================================ the page, and only this server in it
  let res = await fetch(origin + "/");
  assert.strictEqual(res.status, 200);
  const csp = res.headers.get("content-security-policy");
  assert.ok(csp.includes("connect-src 'self' ws://127.0.0.1:" + port + " wss://127.0.0.1:" + port + " blob:"), csp);
  assert.ok(csp.includes("frame-ancestors 'none'"));
  const html = await res.text();
  assert.ok(html.includes("<title>Paigr</title>"));
  assert.ok(!/hedyassist|https:/.test(html.match(/Content-Security-Policy" content="([^"]*)"/)[1]), "the page's own policy names nothing outside");
  res = await fetch(origin + "/styles.css");
  const etag = res.headers.get("etag");
  assert.ok(etag && res.headers.get("cache-control") === "no-cache");
  assert.strictEqual((await fetch(origin + "/styles.css", { headers: { "if-none-match": etag } })).status, 304);
  assert.strictEqual((await fetch(origin + "/sw.js")).headers.get("cache-control"), "no-store");
  assert.strictEqual((await fetch(origin + "/fonts/Figtree.woff2")).headers.get("content-type"), "font/woff2");
  assert.strictEqual((await fetch(origin + "/nope.html")).status, 404);
  assert.strictEqual((await fetch(origin + "/../server/paigr-server.js")).status, 404);
  assert.strictEqual((await fetch(origin + "/%2e%2e/server/store.js")).status, 404);
  assert.deepStrictEqual(await (await fetch(origin + "/__hedy/push/config")).json(), { enabled: false, allowVisitorSubscriptions: false });
  assert.strictEqual((await fetch(origin + "/api/inbox")).status, 404);
  assert.strictEqual((await fetch(origin + "/")).headers.get("x-frame-options"), "DENY");

  // ================================================================ two phones, one scan
  const relay = phone("relay", "Home phone"), texts = phone("inbox", "Pocket phone");
  await link(relay, texts);
  assert.strictEqual(relay.tid, texts.tid);
  assert.strictEqual((await call(relay, "GET", "/v2/ping")).role, "relay");
  assert.strictEqual((await call(texts, "GET", "/v2/ping")).role, "inbox");
  assert.strictEqual((await call(phone("inbox"), "GET", "/v2/inbox")).unlinked, true);

  // ================================================================ the doorbell
  const bell = await doorbell(texts);
  const relayBell = await doorbell(relay);
  // a ticket opens one socket, once
  const again = new WebSocket(bell.ticket.webSocketUrl + "?ticket=" + encodeURIComponent(bell.ticket.ticket));
  await new Promise((resolve, reject) => { again.onopen = () => reject(new Error("a used ticket opened a socket")); again.onerror = resolve; });
  assert.ok((await rawSocket("made-up")).refused.includes("401"));

  // ================================================================ a text comes in
  const conv = "c1aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
  const t0 = Date.now() - 60000;
  let r = await call(relay, "POST", "/v2/relay/ingest", { items: [await sealedText(relay, conv, "Running late, 10 min", t0, 1)] });
  assert.strictEqual(r.stored, 1, JSON.stringify(r));
  const ring = await ringOf(bell, "messages");
  assert.ok(Array.isArray(ring.payload.convs) && ring.payload.convs.length === 1);
  assert.ok(!JSON.stringify(bell.rings).includes("Running"), "a ring carries no text");
  r = await call(texts, "GET", "/v2/inbox");
  assert.strictEqual(r._status, 200);
  const wconv = await TR2.convId(texts.keys, conv);
  const c = r.items.find((x) => x.conv === wconv);
  assert.strictEqual((await TR2.openJson(texts.keys.enc, c.lastE, TR2.AAD.message(wconv, c.lastDir))).text, "Running late, 10 min");
  r = await call(texts, "GET", "/v2/thread", null, { conv: wconv });
  assert.strictEqual(r.items.length, 1);

  // ================================================================ a reply goes out
  const e = await TR2.sealJson(texts.keys.enc, { text: "No rush", ts: Date.now() }, TR2.AAD.message(wconv, "out"));
  const o = await TR2.sealJson(texts.keys.enc, { text: "No rush", n: TR2.b64u(TR2.random(16)), ts: Date.now() }, TR2.AAD.out(wconv));
  r = await call(texts, "POST", "/v2/send", { conv: wconv, e, o, mt: await TR2.matchToken(texts.keys, "out", "No rush", "") });
  assert.strictEqual(r._status, 200, JSON.stringify(r));
  await ringOf(relayBell, "queued");
  r = await call(relay, "GET", "/v2/relay/outbox");
  assert.strictEqual(r.items.length, 1);
  assert.strictEqual((await TR2.openJson(relay.keys.enc, r.items[0].o, TR2.AAD.out(r.items[0].conv))).text, "No rush");
  r = await call(relay, "POST", "/v2/relay/outbox/result", { id: r.items[0].id, status: "sent" });
  assert.strictEqual(r._status, 200, JSON.stringify(r));
  await ringOf(bell, "status");

  // ================================================================ pictures: an upload ticket, and sent whole
  const picture = crypto.randomBytes(300 * 1024);
  const wfp = await TR2.fpId(texts.keys, fp(7));
  r = await call(texts, "POST", "/v2/media/ticket", { conv: wconv, fp: wfp, ts: t0, bytes: picture.length, kind: "original" });
  assert.strictEqual(r._status, 200, JSON.stringify(r));
  assert.ok(r.uploadUrl.startsWith(origin + "/_upload/"));
  const mediaPath = r.path;
  // the wrong type, or more than was asked for, isn't taken
  assert.strictEqual((await fetch(r.uploadUrl, { method: "PUT", headers: { "content-type": "image/png" }, body: picture })).status, 403);
  assert.strictEqual((await fetch(r.uploadUrl, { method: "PUT", headers: r.headers, body: Buffer.concat([picture, Buffer.from("x")]) })).status, 413);
  assert.strictEqual((await call(texts, "POST", "/v2/media/complete", { path: mediaPath, ticket: r.ticket }))._status, 500, "nothing uploaded yet");
  res = await fetch(r.uploadUrl, { method: "PUT", headers: r.headers, body: picture });
  assert.strictEqual(res.status, 200);
  const done = await call(texts, "POST", "/v2/media/complete", { path: mediaPath, ticket: r.ticket });
  assert.strictEqual(done.bytes, picture.length, JSON.stringify(done));
  assert.strictEqual((await fetch(r.uploadUrl, { method: "PUT", headers: r.headers, body: picture })).status, 403, "a ticket is used once");
  r = await call(relay, "GET", "/v2/media", null, { p: mediaPath });
  assert.strictEqual(r._status, 200, JSON.stringify(r));
  res = await fetch(r.url);
  assert.strictEqual(res.status, 200);
  assert.ok(Buffer.from(await res.arrayBuffer()).equals(picture));
  const signed = new URL(r.url);
  signed.searchParams.set("exp", String(Number(signed.searchParams.get("exp")) + 1000));
  assert.strictEqual((await fetch(signed)).status, 403, "a link's time can't be stretched");
  const other = new URL(r.url);
  other.pathname = other.pathname.replace(/-o\.enc$/, "-p.enc");
  assert.strictEqual((await fetch(other)).status, 403, "a link opens only its own file");
  assert.strictEqual((await call(phone("inbox"), "GET", "/v2/media", null, { p: mediaPath })).unlinked, true);
  // sent whole, in the request itself
  const small = crypto.randomBytes(40 * 1024);
  r = await v2("POST", "/v2/media/put", small, null, Object.assign(H(relay), { "x-relay-conv": wconv, "x-relay-fp": await TR2.fpId(relay.keys, fp(8)), "x-relay-ts": String(t0), "x-relay-kind": "preview" }));
  assert.strictEqual(r._status, 200, JSON.stringify(r));
  const smallPath = r.path;
  r = await call(texts, "GET", "/v2/media", null, { p: smallPath });
  assert.ok(Buffer.from(await (await fetch(r.url)).arrayBuffer()).equals(small));
  // a request larger than any the inbox takes is turned away before it's read
  res = await fetch(origin + "/v2/media/put", { method: "POST", headers: Object.assign(H(relay), { "content-type": "application/octet-stream" }), body: Buffer.alloc(6 * 1024 * 1024) }).catch(() => null);
  assert.ok(!res || res.status === 413);

  // ================================================================ the socket's frames, by hand
  let t = await call(texts, "GET", "/v2/ticket");
  let raw = await rawSocket(t.ticket);
  raw.write(0x9, "are you there");
  await until(() => raw.frames().find((f) => f.op === 0xa && f.payload.toString() === "are you there"), "a pong");
  raw.write(0x8, Buffer.from([0x03, 0xe8]));
  await new Promise((resolve) => raw.s.on("close", resolve));
  t = await call(texts, "GET", "/v2/ticket");
  raw = await rawSocket(t.ticket);
  raw.write(0x1, "unmasked", false);
  const closing = await until(() => raw.frames().find((f) => f.op === 0x8), "a close");
  assert.strictEqual(closing.payload.readUInt16BE(0), 1002, "a frame a client didn't mask closes the socket");

  // ================================================================ nothing readable on the server
  const dbFile = path.join(dataDir, "paigr.db");
  const { DatabaseSync } = quietly(() => require("node:sqlite"));
  let db = new DatabaseSync(dbFile, { readOnly: true });
  const all = JSON.stringify(db.prepare("SELECT c, k, v FROM items").all());
  db.close();
  for (const words of ["Running late", "No rush", "Andy", conv, relay.token, texts.token]) assert.ok(!all.includes(words), "the server holds no " + words);
  assert.strictEqual(fs.statSync(path.join(dataDir, "files")).mode & 0o777, 0o700, "only the server's own user reads what it keeps");

  // ================================================================ a restart keeps everything
  bell.ws.close();
  relayBell.ws.close();
  await stopServer();
  // the relay phone's last check-in, eleven minutes ago: the watch reports it once the server is back
  db = new DatabaseSync(dbFile);
  const row = db.prepare("SELECT v FROM items WHERE c = 'v2-state' AND k = ?").get(relay.tid + "/relay");
  assert.ok(!row, "no check-in yet");
  const long = Date.now() - 11 * 60000;
  db.prepare("INSERT INTO items (c, k, v, ver, exp) VALUES ('v2-state', ?, ?, 1, NULL)").run(relay.tid + "/relay", JSON.stringify({ lastSeen: long, seen: pad13(long), listening: true }));
  db.close();
  await startServer({ WATCH_SECONDS: "1" });
  assert.ok(/\(\d+ items\)/.test(output));
  assert.strictEqual((await call(texts, "GET", "/v2/ping"))._status, 200);
  r = await call(texts, "GET", "/v2/thread", null, { conv: wconv });
  assert.strictEqual(r.items.length, 2, "both texts are still there");
  r = await call(texts, "GET", "/v2/media", null, { p: mediaPath });
  assert.ok(Buffer.from(await (await fetch(r.url)).arrayBuffer()).equals(picture), "and the picture");
  const later = await doorbell(texts);
  const offline = await ringOf(later, "relay", 4000);
  assert.strictEqual(offline.payload.online, false);
  assert.ok(offline.payload.silentFor >= 11 * 60000 - 5000);
  later.rings.length = 0;
  r = await call(relay, "POST", "/v2/relay/heartbeat", { listening: true, battery: 80, version: "1.2" });
  assert.strictEqual(r._status, 200, JSON.stringify(r));
  assert.strictEqual((await ringOf(later, "relay")).payload.online, true, "and says when it's back");
  later.ws.close();
  await stopServer();

  // ================================================================ settings people get wrong are named
  const bad = spawn(process.execPath, [SERVER], { env: Object.assign({}, process.env, { PUBLIC_URL: "paigr.example.com", DATA_DIR: dataDir }) });
  let said = "";
  bad.stderr.on("data", (d) => { said += d; });
  assert.strictEqual(await new Promise((resolve) => bad.on("exit", resolve)), 2);
  assert.ok(said.includes("PUBLIC_URL must be like https://"), said);

  fs.rmSync(dataDir, { recursive: true, force: true });
  console.log("selfhost.test.js: all passed");
})().catch((e) => {
  console.error(e && e.stack || e);
  if (output) console.error("--- server output ---\n" + output);
  if (proc) proc.kill("SIGKILL");
  process.exit(1);
});
