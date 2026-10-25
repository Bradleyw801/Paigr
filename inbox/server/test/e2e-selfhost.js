// The page in a browser, against the self-hosted server: the server's own strict policy holds (nothing violates it), the
// page's realtime socket opens on the server's own /_realtime and rings it on time, pictures come over the server's
// signed links, and the page says this server sends no browser notifications.
//   node server/test/e2e-selfhost.js
const assert = require("assert");
const path = require("path");
const { spawn } = require("child_process");
const { chromium } = require("../../test/playwright");
const TR2 = require("../../web/tr2.js");

const PORT = 11200 + Math.floor(Math.random() * 300);
const ORIGIN = "http://127.0.0.1:" + PORT;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const fp = (n) => n.toString(16).padStart(16, "0");

function seed() {
  return new Promise((resolve, reject) => {
    const p = spawn(process.execPath, [path.join(__dirname, "..", "..", "test", "seed-native.js"), ORIGIN, "demo"], { stdio: ["ignore", "pipe", "inherit"] });
    let out = "";
    p.stdout.on("data", (d) => { out += d; });
    p.on("exit", (code) => (code === 0 ? resolve(JSON.parse(out.split("\n")[0])) : reject(new Error("seeding failed"))));
  });
}

(async () => {
  const server = spawn(process.execPath, [path.join(__dirname, "serve-selfhost.js"), String(PORT)], { stdio: ["ignore", "ignore", "inherit"] });
  let browser;
  try {
    for (let i = 0; i < 80; i++) { try { await fetch(ORIGIN + "/healthz"); break; } catch { await sleep(100); } }
    const link = await seed();
    const keys = await TR2.inboxKeys(TR2.unb64u(link.key));

    browser = await chromium.launch();
    const context = await browser.newContext();
    await context.addInitScript(() => { window.__csp = []; document.addEventListener("securitypolicyviolation", (e) => window.__csp.push(e.violatedDirective + " " + e.blockedURI)); });
    const page = await context.newPage();
    const errors = [];
    page.on("pageerror", (e) => errors.push(String(e)));
    const sockets = [];
    page.on("websocket", (ws) => {
      const s = { url: ws.url(), frames: [], closed: false };
      sockets.push(s);
      ws.on("framereceived", (f) => s.frames.push(String(f.payload)));
      ws.on("close", () => { s.closed = true; });
    });
    await page.goto(ORIGIN + "/");
    await page.evaluate((l) => localStorage.setItem("relay.link2", l), JSON.stringify({ tid: link.tid, token: link.token, key: link.key }));
    await page.reload();
    await page.waitForSelector('#view-inbox:not([hidden]) #conv-list li:has-text("Maya Chen")', { timeout: 15000 });

    // the socket, on this server
    for (let i = 0; i < 100 && !sockets.some((s) => !s.closed); i++) await sleep(50);
    const live = sockets.find((s) => !s.closed);
    assert.ok(live, "a realtime socket: " + JSON.stringify(sockets));
    assert.strictEqual(live.url.replace(/\?.*$/, ""), "ws://127.0.0.1:" + PORT + "/_realtime");

    // A text comes in: the ring brings it at once (the page's own checks are much further apart)
    const conv = await TR2.convId(keys, "c-pat");
    const f = await TR2.fpId(keys, fp(901));
    const ts = Date.now();
    const item = {
      conv, fp: f, ts, dir: "in", src: "notif",
      e: await TR2.sealJson(keys.enc, { text: "Through the socket", sender: "Pat", name: "Pat", group: false, ts, fp: f }, TR2.AAD.message(conv, "in")),
      ne: await TR2.sealJson(keys.enc, { name: "Pat", group: false }, TR2.AAD.name(conv)),
      mt: await TR2.matchToken(keys, "in", "Through the socket", "")
    };
    const r = await fetch(ORIGIN + "/v2/relay/ingest", { method: "POST", headers: { "content-type": "application/json", "x-relay-token": link.relayToken }, body: JSON.stringify({ items: [item] }) });
    assert.strictEqual(r.status, 200);
    const began = Date.now();
    await page.waitForSelector('#conv-list li:has-text("Pat")', { timeout: 4000 });
    console.log("ok      the ring brought a new text in " + (Date.now() - began) + " ms");
    assert.ok(live.frames.some((x) => JSON.parse(x).type === "messages"), JSON.stringify(live.frames));
    assert.ok(!live.frames.join("").includes("Through"), "a ring carries no text");

    // a picture, over this server's signed link, into a blob
    await page.click('#conv-list li:has-text("Maya Chen")');
    await page.waitForSelector("#msg-list img.pic[src^='blob:']", { timeout: 10000 });
    console.log("ok      a picture opened from this server's signed link");
    await page.click("#back-btn");

    // no browser notifications from this server, and the page says so
    await page.click("#settings-btn");
    await page.waitForFunction(() => /doesn't send notifications to browsers/.test(document.getElementById("push-state").textContent), null, { timeout: 5000 });
    assert.ok(await page.evaluate(() => document.getElementById("push-btn").disabled));
    console.log("ok      the page says this server sends no browser notifications");

    assert.deepStrictEqual(await page.evaluate(() => window.__csp), [], "nothing violates the server's policy");
    assert.deepStrictEqual(errors, []);
    console.log("ok      no policy violations, no page errors");
    console.log("e2e-selfhost.js: all passed");
  } finally {
    if (browser) await browser.close();
    server.kill();
  }
})().catch((e) => { console.error(e); process.exit(1); });
