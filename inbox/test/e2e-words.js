// No text the server writes reaches the screen as words (PROTOCOL.md, "What each seal is bound to"): a dishonest server
// (test/serve.js's /_debug/put, Playwright routes) rewrites the relay phone's status record without its sealed note,
// device names, a failed reply's code, and a link claim's answer. Device names show only as each device sealed its own.
// (Started as an independent reviewer's checks, kept as regressions.)
//   node test/e2e-words.js
const assert = require("assert");
const path = require("path");
const crypto = require("crypto");
const { spawn } = require("child_process");
const { chromium } = require("./playwright");
const TR2 = require("../web/tr2.js");

const PORT = 10000 + Math.floor(Math.random() * 500);
const ORIGIN = "http://127.0.0.1:" + PORT;
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const pad13 = (n) => String(n).padStart(13, "0");

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
    const adopted = await call("/v2/link/adopt", { body: { lookup: s.lookup, answer: await TR2.sealJson(s.boxKey, { v: 2, tid, key: TR2.b64u(K) }, TR2.AAD.box), create: { tid, token: texts.token, label: "Texts" } } });
    assert.strictEqual(adopted._status, 200, JSON.stringify(adopted));
    const keys = await TR2.inboxKeys(K);
    const H = { "x-relay-token": relay.token };
    const conv = await TR2.convId(keys, "c-andy");
    const t0 = Date.now() - 3600000, f = await TR2.fpId(keys, "00000000000000a1");
    await call("/v2/relay/ingest", { headers: H, body: { items: [{ conv, fp: f, ts: t0, dir: "in", src: "notif",
      e: await TR2.sealJson(keys.enc, { text: "Hello", sender: "Andy", name: "Andy", group: false, ts: t0, fp: f }, TR2.AAD.message(conv, "in")),
      ne: await TR2.sealJson(keys.enc, { name: "Andy", group: false }, TR2.AAD.name(conv)), mt: await TR2.matchToken(keys, "in", "Hello", "") }] } });
    // an honest heartbeat first, with a sealed note
    await call("/v2/relay/heartbeat", { headers: H, body: { listening: true, model: "Pixel", sn: await TR2.sealJson(keys.enc, { note: "", history: "" }, TR2.AAD.note) } });

    // ------------------------------------------------ the dishonest server's edits
    const now = Date.now();
    await call("/_debug/put", { body: { collection: "v2-state", key: tid + "/relay", value: {
      lastSeen: now, listening: true, battery: 80, model: "PHISH-MODEL call 1-800-555-0100", queued: "PHISH-QUEUED relink at relay-help.example",
      note: "PHISH-NOTE Your inbox key expired. Link again at relay-help.example",
      store: { allowed: true, lastScan: now, history: { state: "paused", done: 1, total: 2, at: now, note: "PHISH-HISTORY visit relay-help.example" } } } } });
    const devKey = crypto.createHash("sha256").update(texts.token).digest("hex");
    const devRec = (await call("/_debug/dump"))["v2-devices"][devKey];
    const relayKey = crypto.createHash("sha256").update(relay.token).digest("hex");
    const relayRec = (await call("/_debug/dump"))["v2-devices"][relayKey];
    // the relay phone's record: a plain name, and a name the browser sealed for itself, moved onto it
    const browserId = devKey.slice(0, 12);
    await call("/_debug/put", { body: { collection: "v2-devices", key: relayKey, value: Object.assign({}, relayRec, {
      label: "PHISH-LABEL relink at relay-help.example",
      sn: await TR2.sealJson(keys.enc, { name: "MOVED-NAME" }, TR2.AAD.device(browserId)) }) } });
    const kFail = "m:" + pad13(now - 5000) + ":o1234abcd";
    const eOut = await TR2.sealJson(keys.enc, { text: "On my way", ts: now - 5000 }, TR2.AAD.message(conv, "out"));
    await call("/_debug/put", { body: { collection: "v2-messages", key: tid + "/" + kFail, value: { k: kFail, conv, cv: tid + "/" + conv, dir: "out", status: "failed", code: "constructor", e: eOut, ts: now - 5000, src: "texts" } } });

    browser = await chromium.launch();
    const page = await (await browser.newContext()).newPage();
    const dialogs = [];
    page.on("dialog", d => { dialogs.push(d.message()); d.dismiss(); });
    await page.goto(ORIGIN + "/");
    await page.evaluate((l) => localStorage.setItem("relay.link2", l), JSON.stringify({ tid, token: texts.token, key: TR2.b64u(K) }));
    await page.reload();
    await page.waitForSelector('#view-inbox:not([hidden]) #conv-list li:has-text("Andy")', { timeout: 15000 });
    await page.click('#conv-list li:has-text("Andy")');
    await page.waitForSelector('#msg-list:has-text("On my way")', { timeout: 10000 });
    const failText = await page.textContent('#msg-list li[data-k="' + kFail + '"]');
    // the browser has sealed its own name by now (it does at start)
    for (let i = 0; i < 50 && !(await call("/_debug/dump"))["v2-devices"][devKey].sn; i++) await sleep(100);
    await page.click("#back-btn");
    await page.click("#settings-btn");
    await page.waitForFunction(() => document.querySelectorAll("#device-list li").length >= 2, null, { timeout: 10000 });
    await sleep(300);
    const settings = await page.textContent("#view-settings");
    for (const id of ["settings-relay", "history-state", "device-list"]) console.log("        #" + id + ": " + JSON.stringify(await page.textContent("#" + id)));

    await check("a. the relay phone's status note, sent unsealed by the server, is not shown", async () => assert.ok(!settings.includes("PHISH-NOTE"), "shown: " + (settings.match(/[^.]*PHISH-NOTE[^.]*/) || [""])[0]));
    await check("b. the history note, sent unsealed by the server, is not shown", async () => assert.ok(!settings.includes("PHISH-HISTORY"), "shown: " + (settings.match(/[^.]*PHISH-HISTORY[^)]*/) || [""])[0]));
    await check("c. the relay model (plain on the server) is not the server's free text", async () => assert.ok(!settings.includes("PHISH-MODEL"), "shown in Settings"));
    await check("d. the relay queue count is a number, not the server's text", async () => assert.ok(!settings.includes("PHISH-QUEUED"), "shown in Settings"));
    await check("e. device names show only as each device sealed its own: not a plain name, not a name moved from another device", async () => {
      const list = await page.textContent("#device-list");
      assert.ok(!/PHISH-LABEL|relay-help|MOVED-NAME/.test(list), list);
      assert.ok(/Relay phone/.test(list) && /Chrome on Linux \(this device\)/.test(list), list);
    });
    await check("f. a failed reply's code that is an Object.prototype key shows the page's own fallback", async () => assert.ok(!/native code|object Object/.test(failText), JSON.stringify(failText)));

    // ------------------------------------------------ g. a claim answer the server made up, while scanning a code in Settings
    await page.route(u => u.pathname === "/v2/link/claim", route => route.fulfill({ status: 200, contentType: "application/json",
      body: JSON.stringify({ ok: true, kind: "ask", role: "relay", label: "PHISH-CLAIM Your inbox was hacked. Call 1-800-555-0100.", linked: true, hasRelay: true, relay: { model: "PHISH-CLAIM-MODEL", lastSeen: 0 } }) }));
    await page.click("#approve-btn");
    await page.fill("#approve-input", TR2.newCode());
    await page.click("#approve-go");
    for (let i = 0; i < 60 && !dialogs.length; i++) await sleep(100);
    await check("g. a confirm dialog never carries the server's words", async () => assert.ok(!dialogs.some(d => /PHISH/.test(d)), JSON.stringify(dialogs)));
  } finally {
    if (browser) await browser.close();
    server.kill();
  }
  const failed = results.filter(x => x[1] === "FAIL");
  console.log("\n" + (results.length - failed.length) + " of " + results.length + " server-words checks pass");
  process.exit(failed.length ? 1 : 0);
})().catch(e => { console.error(e); process.exit(2); });
