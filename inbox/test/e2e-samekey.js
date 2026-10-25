// A dishonest server sends, in /v2/sync, an existing message's key carrying another message's seal (here a fresh seal
// of the same payload, as a resync makes): the page still shows each message once, in time order. (Started as an
// independent reviewer's check, kept as a regression.)
//   node test/e2e-samekey.js
const assert = require("assert");
const path = require("path");
const { spawn } = require("child_process");
const { chromium } = require("./playwright");
const TR2 = require("../web/tr2.js");

const PORT = 9500 + Math.floor(Math.random() * 300);
const ORIGIN = "http://127.0.0.1:" + PORT;
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const pad13 = (n) => String(n).padStart(13, "0");
const fp = (n) => n.toString(16).padStart(16, "0");

async function call(p, o) {
  o = o || {};
  const headers = Object.assign({}, o.headers || {});
  let body = o.body;
  if (body !== undefined) { body = JSON.stringify(body); headers["content-type"] = "application/json"; }
  const res = await fetch(ORIGIN + p, { method: o.method || (body !== undefined ? "POST" : "GET"), headers, body });
  const t = await res.text();
  let d = {};
  try { d = t ? JSON.parse(t) : {}; } catch { d = { raw: t }; }
  d._status = res.status;
  return d;
}

(async () => {
  const server = spawn(process.execPath, [path.join(__dirname, "serve.js"), String(PORT)], { stdio: ["ignore", "ignore", "inherit"] });
  let browser, failed = false;
  try {
    for (let i = 0; i < 50; i++) { try { await fetch(ORIGIN + "/index.html"); break; } catch { await sleep(100); } }
    const relay = { token: TR2.newToken() }, texts = { token: TR2.newToken() };
    const code = TR2.newCode(), s = await TR2.linkSecrets(code);
    assert.strictEqual((await call("/v2/link/ask", { body: { lookup: s.lookup, token: relay.token, role: "relay" } }))._status, 200);
    const K = TR2.newKey(), tid = TR2.newTid();
    const answer = await TR2.sealJson(s.boxKey, { v: 2, tid, key: TR2.b64u(K) }, TR2.AAD.box);
    assert.strictEqual((await call("/v2/link/adopt", { body: { lookup: s.lookup, answer, create: { tid, token: texts.token } } }))._status, 200);
    const keys = await TR2.inboxKeys(K);
    const H = { "x-relay-token": relay.token };
    const wA = await TR2.convId(keys, "c-andy");
    const t0 = Date.now() - 3600000;
    const item = async (n, ts, text) => {
      const f = await TR2.fpId(keys, fp(n));
      return { conv: wA, fp: f, ts, dir: "in", src: "notif",
        e: await TR2.sealJson(keys.enc, { text, sender: "Andy", name: "Andy", group: false, ts, fp: f }, TR2.AAD.message(wA, "in")),
        ne: await TR2.sealJson(keys.enc, { name: "Andy", group: false }, TR2.AAD.name(wA)),
        mt: await TR2.matchToken(keys, "in", text) };
    };
    const r = await call("/v2/relay/ingest", { headers: H, body: { items: [
      await item(1, t0, "First: the old one"), await item(2, t0 + 60000, "Second message"), await item(3, t0 + 120000, "Third message")] } });
    assert.strictEqual(r.stored, 3, JSON.stringify(r));
    const msgs = (await call("/_debug/dump"))["v2-messages"];
    const kSecond = "m:" + pad13(t0 + 60000) + ":" + await TR2.fpId(keys, fp(2));
    const second = msgs[tid + "/" + kSecond];
    assert.ok(second, "second stored");
    // What the server hands out: the second message's key and record, with a fresh seal of the first one's payload.
    const swapped = Object.assign({}, second, { e: (await item(1, t0, "First: the old one")).e });
    delete swapped.cv;

    browser = await chromium.launch();
    const page = await (await browser.newContext()).newPage();
    await page.goto(ORIGIN + "/");
    await page.evaluate((l) => localStorage.setItem("relay.link2", l), JSON.stringify({ tid, token: texts.token, key: TR2.b64u(K) }));
    await page.reload();
    await page.waitForSelector('#conv-list li:has-text("Andy")', { timeout: 15000 });
    await page.click('#conv-list li:has-text("Andy")');
    await page.waitForSelector('#msg-list:has-text("Third message")', { timeout: 10000 });
    const count = async () => { const t = await page.textContent("#msg-list"); return t.split("First: the old one").length - 1; };
    assert.strictEqual(await count(), 1, "control: shown once before");

    let injected = false;
    await page.route(u => u.pathname === "/v2/sync", async route => {
      const res = await route.fetch();
      const data = await res.json();
      if (!injected) { injected = true; data.items = (data.items || []).concat([swapped]); }
      route.fulfill({ response: res, body: JSON.stringify(data) });
    });
    for (let i = 0; i < 80 && !injected; i++) await sleep(200);
    await sleep(1500);
    const n = await count();
    const order = await page.$$eval("#msg-list li.msg", ls => ls.map(l => (l.querySelector(".text") || {}).textContent || ""));
    console.log("injected:", injected, "| times shown:", n, "| order:", JSON.stringify(order));
    if (n !== 1) { failed = true; console.log("FAIL    one message is shown " + n + " times after a same-key update (app.js mergeMessage)"); }
    else console.log("ok      shown once");
  } finally {
    if (browser) await browser.close();
    server.kill();
  }
  process.exit(failed ? 1 : 0);
})().catch(e => { console.error(e); process.exit(2); });
