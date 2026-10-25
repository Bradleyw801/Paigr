// Device names in the list are each device's own, sealed for its own id: a browser that leaves an inbox and is invited
// back into the same one (a new token, so a new id) names itself again, and its row shows that name. (Started as an
// independent reviewer's check, kept as a regression.)
//   node test/e2e-names.js
const assert = require("assert");
const path = require("path");
const crypto = require("crypto");
const { spawn } = require("child_process");
const { chromium } = require("./playwright");
const TR2 = require("../web/tr2.js");

const PORT = 11000 + Math.floor(Math.random() * 500);
const ORIGIN = "http://127.0.0.1:" + PORT;
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const sha = (t) => crypto.createHash("sha256").update(t).digest("hex");

async function call(p, o) {
  o = o || {};
  const headers = Object.assign({}, o.headers || {});
  let body = o.body;
  if (body !== undefined) { body = JSON.stringify(body); headers["content-type"] = "application/json"; }
  const res = await fetch(ORIGIN + p, { method: o.method || (body !== undefined ? "POST" : "GET"), headers, body });
  const text = await res.text();
  let d = {};
  try { d = text ? JSON.parse(text) : {}; } catch { d = { raw: text }; }
  d._status = res.status;
  return d;
}

(async () => {
  const server = spawn(process.execPath, [path.join(__dirname, "serve.js"), String(PORT)], { stdio: ["ignore", "ignore", "inherit"] });
  let browser, failed = 0;
  const ok = (cond, name, detail) => { console.log((cond ? "ok      " : "FAIL    ") + name + (cond ? "" : "\n        " + detail)); if (!cond) failed++; };
  try {
    for (let i = 0; i < 50; i++) { try { await fetch(ORIGIN + "/index.html"); break; } catch { await sleep(100); } }
    const relay = TR2.newToken(), texts = TR2.newToken();
    const s = await TR2.linkSecrets(TR2.newCode());
    await call("/v2/link/ask", { body: { lookup: s.lookup, token: relay, role: "relay" } });
    const K = TR2.newKey(), tid = TR2.newTid();
    const adopted = await call("/v2/link/adopt", { body: { lookup: s.lookup, answer: await TR2.sealJson(s.boxKey, { v: 2, tid, key: TR2.b64u(K) }, TR2.AAD.box), create: { tid, token: texts } } });
    assert.strictEqual(adopted._status, 200, JSON.stringify(adopted));
    const keys = await TR2.inboxKeys(K);
    await call("/v2/relay/heartbeat", { headers: { "x-relay-token": relay }, body: { listening: true, sn: await TR2.sealJson(keys.enc, { note: "", history: "", model: "Pixel 8" }, TR2.AAD.note) } });

    const invite = async () => {
      const code = TR2.newCode(), c = await TR2.linkSecrets(code);
      const r = await call("/v2/link/invite", { headers: { "x-relay-token": texts }, body: { lookup: c.lookup, role: "inbox",
        box: await TR2.sealJson(c.boxKey, { v: 2, tid, key: TR2.b64u(K) }, TR2.AAD.box) } });
      assert.strictEqual(r._status, 200, JSON.stringify(r));
      return code;
    };
    const tokenOf = async (page) => JSON.parse(await page.evaluate(() => localStorage.getItem("relay.link2"))).token;
    const snOf = async (token) => ((await call("/_debug/dump"))["v2-devices"][sha(token)] || {}).sn;

    browser = await chromium.launch();
    const laptop = await (await browser.newContext()).newPage();
    // first time in: the laptop joins by an invite and seals its name
    await laptop.goto(ORIGIN + "/#link=" + (await invite()));
    await laptop.waitForSelector("#view-connect:not([hidden])");
    await laptop.click("#connect-btn");
    await laptop.waitForSelector("#view-inbox:not([hidden])", { timeout: 15000 });
    const t1 = await tokenOf(laptop);
    for (let i = 0; i < 50 && !(await snOf(t1)); i++) await sleep(100);
    ok(!!(await snOf(t1)), "1. the laptop seals its name when it first joins", "no sn on its record");

    // removed from the inbox (from the phone), then invited back into the same inbox
    const removed = await call("/v2/devices/remove", { headers: { "x-relay-token": texts }, body: { id: sha(t1).slice(0, 12) } });
    assert.strictEqual(removed._status, 200, JSON.stringify(removed));
    await laptop.reload();
    await laptop.waitForSelector("#view-connect:not([hidden])", { timeout: 20000 });
    await laptop.goto("about:blank");   // a hash-only change wouldn't load the page again
    await laptop.goto(ORIGIN + "/#link=" + (await invite()));
    await laptop.waitForSelector("#view-connect:not([hidden])");
    await laptop.click("#connect-btn");
    await laptop.waitForSelector("#view-inbox:not([hidden])", { timeout: 15000 }).catch(async (e) => {
      console.log("        connect-error: " + JSON.stringify(await laptop.textContent("#connect-error").catch(() => "")));
      throw e;
    });
    const t2 = await tokenOf(laptop);
    assert.notStrictEqual(t1, t2);
    for (let i = 0; i < 30 && !(await snOf(t2)); i++) await sleep(100);
    await laptop.reload();   // a restart doesn't help either: the mark still matches
    await laptop.waitForSelector("#view-inbox:not([hidden])", { timeout: 15000 });
    for (let i = 0; i < 30 && !(await snOf(t2)); i++) await sleep(100);
    ok(!!(await snOf(t2)), "2. back in the same inbox with a new token, the laptop seals its name for its new id", "no sn on its new record");
    await laptop.click("#settings-btn");
    await laptop.waitForFunction(() => document.querySelectorAll("#device-list li").length >= 3, null, { timeout: 10000 });
    await sleep(300);
    const mine = (await laptop.$$eval("#device-list li", els => els.map(e => e.textContent))).find(t => t.includes("(this device)")) || "";
    ok(/Chrome on Linux \(this device\)/.test(mine), "3. and its row in the device list shows its name", JSON.stringify(mine));
  } finally {
    if (browser) await browser.close();
    server.kill();
  }
  console.log(failed ? "e2e-names: " + failed + " check(s) fail" : "e2e-names: all checks passed");
  process.exit(failed ? 1 : 0);
})().catch(e => { console.error(e); process.exit(2); });
