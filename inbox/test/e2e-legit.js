// Legitimate messages that must still show as checked, with their attachments, under the CSP. (Started as an independent
// reviewer's checks of the fixes, kept as regressions.)
//   - an incoming picture whose notification copy came first and whose message-store copy brought the file (ingest fold)
//   - the same across a merge (merge fold: the picture and its payload came from the conversation merged away)
//   - a reply sent from this page; a video played from a blob; the service worker registered
//   node test/e2e-legit.js
const assert = require("assert");
const path = require("path");
const { spawn } = require("child_process");
const { chromium } = require("./playwright");
const TR2 = require("../web/tr2.js");

const PORT = 10600 + Math.floor(Math.random() * 300);
const ORIGIN = "http://127.0.0.1:" + PORT;
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const pad13 = (n) => String(n).padStart(13, "0");
const fp = (n) => n.toString(16).padStart(16, "0");

async function call(p, o) {
  o = o || {};
  const headers = Object.assign({}, o.headers || {});
  let body = o.body;
  if (body !== undefined && !Buffer.isBuffer(body)) { body = JSON.stringify(body); headers["content-type"] = "application/json"; }
  const res = await fetch(ORIGIN + p, { method: o.method || (body !== undefined ? "POST" : "GET"), headers, body });
  const text = await res.text();
  let d = {};
  try { d = text ? JSON.parse(text) : {}; } catch { d = { raw: text }; }
  d._status = res.status;
  return d;
}

const results = [];
async function check(name, fn) {
  try { await fn(); results.push([name, "ok"]); console.log("ok      " + name); }
  catch (e) { results.push([name, "FAIL"]); console.log("FAIL    " + name + "\n        " + String(e && e.message || e).split("\n")[0]); }
}

(async () => {
  const server = spawn(process.execPath, [path.join(__dirname, "serve.js"), String(PORT)], { stdio: ["ignore", "ignore", "inherit"] });
  let browser;
  try {
    for (let i = 0; i < 50; i++) { try { await fetch(ORIGIN + "/index.html"); break; } catch { await sleep(100); } }
    const relay = { token: TR2.newToken() }, texts = { token: TR2.newToken() };
    const s = await TR2.linkSecrets(TR2.newCode());
    await call("/v2/link/ask", { body: { lookup: s.lookup, token: relay.token, role: "relay", label: "Relay" } });
    const K = TR2.newKey(), tid = TR2.newTid();
    await call("/v2/link/adopt", { body: { lookup: s.lookup, answer: await TR2.sealJson(s.boxKey, { v: 2, tid, key: TR2.b64u(K) }, TR2.AAD.box), create: { tid, token: texts.token, label: "Texts" } } });
    const keys = await TR2.inboxKeys(K);
    const H = { "x-relay-token": relay.token };
    const png = Buffer.from("89504e470d0a1a0a0000000d4948445200000001000000010806000000" + "1f15c4890000000d4944415478da63f8cfc0f01f0005000201" + "e2266e170000000049454e44ae426082", "hex");
    const upload = async (conv, ts, f, bytes) => (await call("/v2/media/put", {
      headers: Object.assign({ "x-relay-conv": conv, "x-relay-fp": f, "x-relay-ts": String(ts), "x-relay-kind": "original", "content-type": "application/octet-stream" }, H),
      body: Buffer.from(await TR2.sealBytes(keys.enc, bytes || png, TR2.AAD.file(conv, ts, f, "original"))) })).path;
    const item = async (x) => {
      const f = await TR2.fpId(keys, fp(x.n));
      const media = x.mime ? { mime: x.mime, w: 1, h: 1, bytes: png.length } : undefined;
      const out = { conv: x.conv, fp: f, ts: x.ts, dir: "in", src: x.src, wantMedia: !!x.want,
        e: await TR2.sealJson(keys.enc, { text: x.text || "", sender: x.name, name: x.name, group: false, ts: x.ts, fp: f, media }, TR2.AAD.message(x.conv, "in")),
        ne: await TR2.sealJson(keys.enc, { name: x.name, group: false }, TR2.AAD.name(x.conv)),
        mt: await TR2.matchToken(keys, "in", x.text || "", x.mime || "") };
      if (x.mime) out.media = x.upload ? { path: await upload(x.conv, x.ts, f) } : {};
      return out;
    };
    const t = Date.now() - 3600000;
    const wA = await TR2.convId(keys, "c-ann"), wB = await TR2.convId(keys, "c-bob-notif"), wC = await TR2.convId(keys, "c-bob-thread"), wV = await TR2.convId(keys, "c-vic");
    // 1. Ann's picture: the notification first (no file yet), then the store's copy with the file: folds, file attached late
    let r = await call("/v2/relay/ingest", { headers: H, body: { items: [await item({ conv: wA, n: 1, ts: t, src: "notif", name: "Ann", mime: "image/png", want: true })] } });
    assert.ok(r.results[0].needMedia, JSON.stringify(r));
    r = await call("/v2/relay/ingest", { headers: H, body: { items: [await item({ conv: wA, n: 2, ts: t + 20000, src: "store", name: "Ann", mime: "image/png", upload: true })] } });
    assert.strictEqual(r.results[0].status, "folded", JSON.stringify(r));
    assert.strictEqual(r.results[0].media, "attached", JSON.stringify(r));
    const kAnn = "m:" + pad13(t) + ":" + await TR2.fpId(keys, fp(1));
    // 2. Bob: the notification conversation has his picture without the file; the thread conversation has the store's copy
    //    with the file. The relay phone merges the thread into the notification conversation: the copy folds in, file and all.
    await call("/v2/relay/ingest", { headers: H, body: { items: [await item({ conv: wC, n: 3, ts: t + 60000, src: "notif", name: "Bob", mime: "image/png", want: true })] } });
    await call("/v2/relay/ingest", { headers: H, body: { items: [await item({ conv: wB, n: 4, ts: t + 80000, src: "store", name: "Bob", mime: "image/png", upload: true })] } });
    const m = await call("/v2/relay/merge", { headers: H, body: { from: wB, into: wC, mr: await TR2.sealJson(keys.enc, { from: wB, into: wC }, TR2.AAD.merge(wC)) } });
    assert.strictEqual(m.folded, 1, JSON.stringify(m));
    const kBob = "m:" + pad13(t + 60000) + ":" + await TR2.fpId(keys, fp(3));
    // 3. Vic's video
    await call("/v2/relay/ingest", { headers: H, body: { items: [await item({ conv: wV, n: 5, ts: t + 120000, src: "notif", name: "Vic", mime: "video/mp4", upload: true })] } });

    browser = await chromium.launch();
    const context = await browser.newContext();
    await context.addInitScript(() => { window.__csp = []; document.addEventListener("securitypolicyviolation", e => window.__csp.push(e.violatedDirective + " " + e.blockedURI)); });
    const page = await context.newPage();
    const errors = [];
    page.on("pageerror", e => errors.push(String(e)));
    await page.goto(ORIGIN + "/");
    await page.evaluate((l) => localStorage.setItem("relay.link2", l), JSON.stringify({ tid, token: texts.token, key: TR2.b64u(K) }));
    await page.reload();
    await page.waitForSelector('#view-inbox:not([hidden]) #conv-list li:has-text("Ann")', { timeout: 15000 });
    const row = (k) => page.evaluate((k) => { const li = document.querySelector('#msg-list li[data-k="' + k + '"]'); return li ? { text: li.textContent, img: (li.querySelector("img.pic") || {}).src || "" } : null; }, k);

    await page.click('#conv-list li:has-text("Ann")');
    await page.waitForSelector("#msg-list li.msg", { timeout: 10000 });
    await page.waitForSelector("#msg-list img.pic[src^='blob:']", { timeout: 8000 }).catch(() => {});
    const ann = await row(kAnn);
    await check("1. a picture whose file came with a later copy (ingest fold) shows, checked", async () => assert.ok(ann && /^blob:/.test(ann.img) && !/couldn't be checked/.test(ann.text), JSON.stringify(ann)));
    await page.fill("#msg-input", "Thanks Ann");
    await page.click("#send-btn");
    await sleep(1500);
    await check("4. a reply sent from this page shows as checked", async () => {
      const txt = await page.textContent("#msg-list");
      assert.ok(txt.includes("Thanks Ann") && !txt.includes("couldn't be checked"), txt);
    });
    await page.click("#back-btn");
    await page.click('#conv-list li:has-text("Bob")');
    await page.waitForSelector("#msg-list li.msg", { timeout: 10000 });
    await page.waitForSelector("#msg-list img.pic[src^='blob:']", { timeout: 8000 }).catch(() => {});
    const bob = await row(kBob);
    await check("2. a picture whose file and payload came from a conversation merged in (merge fold) shows, checked", async () => assert.ok(bob && /^blob:/.test(bob.img) && !/couldn't be checked/.test(bob.text), JSON.stringify(bob)));
    await page.click("#back-btn");
    await page.click('#conv-list li:has-text("Vic")');
    await page.waitForSelector("#msg-list .attach-tile", { timeout: 10000 });
    await page.click("#msg-list .attach-tile");
    await page.waitForSelector("#lightbox:not([hidden]) #lightbox-video[src^='blob:']", { timeout: 8000 }).catch(() => {});
    await sleep(500);
    const vsrc = await page.evaluate(() => document.getElementById("lightbox-video").getAttribute("src") || "");
    await check("3. a video opens from a blob in the player", async () => assert.ok(/^blob:/.test(vsrc), JSON.stringify(vsrc)));
    const sw = await page.evaluate(() => navigator.serviceWorker.getRegistration().then(r => !!r));
    await check("5. the service worker registers under the CSP", async () => assert.ok(sw));
    const csp = await page.evaluate(() => window.__csp);
    await check("6. no CSP violations (websocket, storage, blobs, worker)", async () => assert.deepStrictEqual(csp, []));
    await check("7. no page errors", async () => assert.deepStrictEqual(errors, []));
  } finally {
    if (browser) await browser.close();
    server.kill();
  }
  const failed = results.filter(x => x[1] === "FAIL");
  console.log("\n" + (results.length - failed.length) + " of " + results.length + " legitimate-message checks pass");
  process.exit(failed.length ? 1 : 0);
})().catch(e => { console.error(e); process.exit(2); });
