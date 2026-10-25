// The page as the Texts app runs it: a stand-in for the app's bridge (window.TextsNative), against test/serve.js.
// A brand-new pair linked by a scan, the link handed to the app, a picture saved through the app, and a reinstall
// getting back in by itself.
const assert = require("assert");
const path = require("path");
const { spawn } = require("child_process");
const { chromium } = require("./playwright");
const TR2 = require("../web/tr2.js");

const PORT = 8900 + Math.floor(Math.random() * 90);
const ORIGIN = "http://127.0.0.1:" + PORT;
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

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

// What the Texts app gives the page. Its own storage lives in localStorage under "native.*" here.
function bridge() {
  const get = (k) => localStorage.getItem("native." + k) || "";
  const set = (k, v) => { if (v) localStorage.setItem("native." + k, v); else localStorage.removeItem("native." + k); };
  window.__calls = [];
  window.TextsNative = {
    isApp: () => true, version: () => "0.6", key: () => get("key"), setKey: (k) => set("key", k),
    link: () => get("link"), setLink: (j) => { window.__calls.push(["setLink", j]); set("link", j); },
    recovery: () => get("recovery") || "done", deviceName: () => "Sidephone S1",
    scanQr: () => window.__calls.push(["scanQr"]), openedConversation: () => {},
    notificationsAllowed: () => true, requestNotifications: () => {}, alertMode: () => "normal", setAlertMode: () => {},
    statusMode: () => "shown", setStatusMode: () => {}, batteryExempt: () => true, requestBattery: () => {},
    saveFile: (name, mime, b64) => { window.__calls.push(["saveFile", name, mime, b64]); return "Saved to Downloads as " + name + "."; }
  };
}

(async () => {
  const server = spawn(process.execPath, [path.join(__dirname, "serve.js"), String(PORT)], { stdio: ["ignore", "ignore", "inherit"] });
  let browser;
  try {
    for (let i = 0; i < 50; i++) { try { await fetch(ORIGIN + "/index.html"); break; } catch { await sleep(100); } }
    browser = await chromium.launch();
    const ctx = await browser.newContext();
    await ctx.addInitScript(bridge);
    const page = await ctx.newPage();
    const errors = [];
    page.on("pageerror", e => errors.push(String(e)));

    // ---------------------------------------------------------- two brand-new phones: Relay shows a code, Texts scans it
    await page.goto(ORIGIN + "/");
    await page.waitForSelector("#view-connect:not([hidden]) #scan-btn:not([hidden])");
    const relay = { token: TR2.newToken() };
    const code = TR2.newCode();
    const s = await TR2.linkSecrets(code);
    assert.strictEqual((await call("/v2/link/ask", { body: { lookup: s.lookup, token: relay.token, role: "relay", label: "Unihertz Titan 2 Elite" } }))._status, 200);
    await page.click("#scan-btn");
    assert.deepStrictEqual(await page.evaluate(() => window.__calls.map(c => c[0])), ["scanQr"]);
    await page.evaluate((u) => window.textsScanned(u), TR2.linkQr(ORIGIN, code));   // what the scanner read
    await page.waitForSelector("#view-inbox:not([hidden])", { timeout: 15000 });
    const handed = await page.evaluate(() => window.__calls.filter(c => c[0] === "setLink").map(c => JSON.parse(c[1])));
    assert.strictEqual(handed.length, 1, "the link went to the app");
    assert.ok(/^[0-9a-f]{32}$/.test(handed[0].tid) && handed[0].token.length === 43 && handed[0].key.length === 43);
    assert.strictEqual(await page.evaluate(() => localStorage.getItem("relay.link2")), null, "the app keeps it, not the page");
    let r = await call("/v2/link/wait", { body: { lookup: s.lookup, token: relay.token } });
    assert.strictEqual(r._status, 200);
    const box = await TR2.openJson(s.boxKey, r.answer, "tr2:box");
    assert.strictEqual(box.tid, handed[0].tid);
    const keys = await TR2.inboxKeys(TR2.unb64u(box.key));
    const H = { "x-relay-token": relay.token };
    // the name the app gave this phone, sealed by the page for the phone's own id: the server never had it in the open
    let mine = null;
    for (let i = 0; i < 50 && !(mine && mine.sn); i++) {
      if (i) await sleep(100);
      mine = ((await call("/v2/devices", { headers: { "x-relay-token": handed[0].token } })).items || []).find(d => d.you);
    }
    assert.deepStrictEqual(await TR2.openJson(keys.enc, mine.sn, TR2.AAD.device(mine.id)), { name: "Sidephone S1" });
    assert.ok(!JSON.stringify((await call("/_debug/dump"))["v2-devices"]).includes("Sidephone S1"), "no plain name on the server");

    // ---------------------------------------------------------- a picture comes in; saved through the app
    const png = Buffer.from("89504e470d0a1a0a0000000d4948445200000001000000010806000000" + "1f15c4890000000d4944415478da63f8cfc0f01f0005000201" + "e2266e170000000049454e44ae426082", "hex");
    const conv = await TR2.convId(keys, "c-sam"), fpw = await TR2.fpId(keys, "00000000000000c1"), ts = Date.now();
    const put = await call("/v2/media/put", { headers: Object.assign({ "x-relay-conv": conv, "x-relay-fp": fpw, "x-relay-ts": String(ts), "x-relay-kind": "original", "content-type": "application/octet-stream" }, H), body: Buffer.from(await TR2.sealBytes(keys.enc, png, TR2.AAD.file(conv, ts, fpw, "original"))) });
    assert.ok(put.path, JSON.stringify(put));
    r = await call("/v2/relay/ingest", { headers: H, body: { items: [{
      conv, fp: fpw, ts, dir: "in", src: "notif", media: { path: put.path },
      e: await TR2.sealJson(keys.enc, { text: "", sender: "Sam", name: "Sam", group: false, ts, fp: fpw, media: { mime: "image/png", w: 1, h: 1, bytes: png.length } }, TR2.AAD.message(conv, "in")),
      ne: await TR2.sealJson(keys.enc, { name: "Sam", group: false }, TR2.AAD.name(conv)), mt: await TR2.matchToken(keys, "in", "", "image/png")
    }] } });
    assert.strictEqual(r.stored, 1);
    await page.waitForSelector('#conv-list li:has-text("Sam")', { timeout: 15000 });
    await page.click('#conv-list li:has-text("Sam")');
    await page.waitForSelector("#msg-list img.pic[src^='blob:']", { timeout: 10000 });
    await page.click("#msg-list img.pic");
    await page.waitForSelector("#lightbox:not([hidden]) #lightbox-img[src^='blob:']");
    assert.strictEqual((await page.textContent("#lightbox-open")).trim(), "Save");
    await page.click("#lightbox-open");
    await page.waitForFunction(() => window.__calls.some(c => c[0] === "saveFile"));
    const saved = await page.evaluate(() => window.__calls.find(c => c[0] === "saveFile"));
    assert.strictEqual(saved[2], "image/png");
    assert.ok(/^Paigr-\d{8}-\d{6}\.png$/.test(saved[1]), saved[1]);
    assert.deepStrictEqual(Buffer.from(saved[3], "base64"), png, "the app gets the opened picture");
    await page.click("#lightbox-img");   // closes it
    await page.click("#back-btn");

    // ---------------------------------------------------------- a thread full of pictures: each opens as it comes into view
    const pat = await TR2.convId(keys, "c-pat");
    const items = [];
    for (let i = 0; i < 40; i++) {
      const f = await TR2.fpId(keys, "00000000000001" + i.toString(16).padStart(2, "0")), t = Date.now() - (40 - i) * 60000;
      const up = await call("/v2/media/put", { headers: Object.assign({ "x-relay-conv": pat, "x-relay-fp": f, "x-relay-ts": String(t), "x-relay-kind": "original", "content-type": "application/octet-stream" }, H), body: Buffer.from(await TR2.sealBytes(keys.enc, png, TR2.AAD.file(pat, t, f, "original"))) });
      items.push({
        conv: pat, fp: f, ts: t, dir: "in", src: "notif", media: { path: up.path },
        e: await TR2.sealJson(keys.enc, { text: "", sender: "Pat", name: "Pat", group: false, ts: t, fp: f, media: { mime: "image/png", w: 400, h: 300, bytes: png.length } }, TR2.AAD.message(pat, "in")),
        ne: await TR2.sealJson(keys.enc, { name: "Pat", group: false }, TR2.AAD.name(pat)), mt: await TR2.matchToken(keys, "in", "", "image/png")
      });
    }
    assert.strictEqual((await call("/v2/relay/ingest", { headers: H, body: { items } })).stored, 40);
    await page.waitForSelector('#conv-list li:has-text("Pat")', { timeout: 15000 });
    const opened = () => page.evaluate(() => performance.getEntriesByType("resource").filter(e => e.name.includes("/v2/media?p=")).length);
    const before = await opened();
    await page.click('#conv-list li:has-text("Pat")');
    await page.waitForSelector("#msg-list img.pic[src^='blob:']", { timeout: 10000 });
    await sleep(800);
    const first = (await opened()) - before;
    assert.ok(first > 0 && first < 25, "only the pictures in view were opened, not all 40: " + first);
    console.log("pictures opened on first view: " + first + " of 40");
    await page.evaluate(() => { document.querySelector(".msg-scroll").scrollTop = 0; });
    await page.waitForFunction((n) => performance.getEntriesByType("resource").filter(e => e.name.includes("/v2/media?p=")).length > n, before + first, { timeout: 10000 });
    await page.click("#back-btn");

    // ---------------------------------------------------------- reinstalled: the app finds its inbox again
    const linkJson = await page.evaluate(() => localStorage.getItem("native.link"));
    await page.evaluate(() => { localStorage.clear(); localStorage.setItem("native.recovery", "pending"); });
    await page.reload();
    await page.waitForSelector("#view-connect:not([hidden])");
    await page.waitForFunction(() => !document.getElementById("connect-note").hidden && /linked before/.test(document.getElementById("connect-note").textContent));
    await page.evaluate((j) => { localStorage.setItem("native.link", j); localStorage.setItem("native.recovery", "done"); window.textsLinked(); }, linkJson);
    await page.waitForSelector('#view-inbox:not([hidden]) #conv-list li:has-text("Sam")', { timeout: 15000 });
    assert.ok((await page.textContent("#toast")).includes("Linked again"));

    // ---------------------------------------------------------- a code from an older Relay says to update it
    await page.evaluate(() => window.textsScanned("TEXTRELAY:RELAY:K7Q4M2XD9PRT"));
    await page.waitForFunction(() => /older Paigr Relay/.test(document.getElementById("toast").textContent));

    // ---------------------------------------------------------- a Texts from before private inboxes asks to be updated first
    const oldCtx = await browser.newContext();
    await oldCtx.addInitScript(() => {
      window.TextsNative = { isApp: () => true, version: () => "0.5", key: () => "hak_inbox.test", setKey: () => {}, scanQr: () => {},
        openedConversation: () => {}, notificationsAllowed: () => true, requestNotifications: () => {}, alertMode: () => "normal",
        setAlertMode: () => {}, statusMode: () => "shown", setStatusMode: () => {}, batteryExempt: () => true, requestBattery: () => {} };
    });
    const old = await oldCtx.newPage();
    old.on("pageerror", e => errors.push("old app: " + e));
    await old.goto(ORIGIN + "/");
    await old.waitForSelector("#move-banner:not([hidden])");
    assert.ok((await old.textContent("#move-banner")).includes("update Paigr on this phone"));
    const oldCode = TR2.newCode();
    await call("/v2/link/ask", { body: { lookup: (await TR2.linkSecrets(oldCode)).lookup, token: TR2.newToken(), role: "relay", label: "x" } });
    await old.evaluate((u) => window.textsScanned(u), TR2.linkQr(ORIGIN, oldCode));
    await old.waitForFunction(() => /Update Paigr on this phone first/.test(document.getElementById("toast").textContent));
    await oldCtx.close();

    assert.deepStrictEqual(errors, [], errors.join("\n"));
    console.log("e2e-native: all checks passed");
  } finally {
    if (browser) await browser.close();
    server.kill();
  }
})().catch(e => { console.error(e); process.exit(1); });
