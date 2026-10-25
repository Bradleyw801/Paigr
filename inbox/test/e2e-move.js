// The move from a first-version inbox goes only where this device's owner means it to: by itself into an inbox this
// device started (test/e2e.js covers that), and into an inbox joined by someone else's invite only after a yes.
// Started as an independent reviewer's proof test, kept as a regression.
//   node test/e2e-move.js
const assert = require("assert");
const path = require("path");
const { spawn } = require("child_process");
const { chromium } = require("./playwright");
const TR2 = require("../web/tr2.js");

const PORT = 9500 + Math.floor(Math.random() * 300);
const ORIGIN = "http://127.0.0.1:" + PORT;
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
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

/** Someone else's private inbox, already running, showing an invite code for one more device. */
async function theirInbox() {
  const relay = { token: TR2.newToken() }, texts = { token: TR2.newToken() };
  const s = await TR2.linkSecrets(TR2.newCode());
  await call("/v2/link/ask", { body: { lookup: s.lookup, token: relay.token, role: "relay" } });
  const K = TR2.newKey(), tid = TR2.newTid();
  const r = await call("/v2/link/adopt", { body: { lookup: s.lookup, answer: await TR2.sealJson(s.boxKey, { v: 2, tid, key: TR2.b64u(K) }, "tr2:box"), create: { tid, token: texts.token } } });
  assert.strictEqual(r._status, 200, JSON.stringify(r));
  await call("/v2/relay/heartbeat", { headers: { "x-relay-token": relay.token }, body: { listening: true } });
  const code = TR2.newCode(), s2 = await TR2.linkSecrets(code);
  const inv = await call("/v2/link/invite", { headers: { "x-relay-token": texts.token }, body: { lookup: s2.lookup, box: await TR2.sealJson(s2.boxKey, { v: 2, tid, key: TR2.b64u(K) }, "tr2:box"), role: "inbox" } });
  assert.strictEqual(inv._status, 200, JSON.stringify(inv));
  return { tid, texts, code, keys: await TR2.inboxKeys(K) };
}

/** What that inbox's own devices can read now. */
async function readable(their) {
  const th = await call("/v2/inbox", { headers: { "x-relay-token": their.texts.token } });
  const out = [];
  for (const c of th.items || []) {
    try { out.push((await TR2.openJson(their.keys.enc, c.lastE, TR2.AAD.message(c.conv, c.lastDir))).text); } catch { /* not sealed for them */ }
  }
  return out;
}

(async () => {
  const server = spawn(process.execPath, [path.join(__dirname, "serve.js"), String(PORT)], { stdio: ["ignore", "ignore", "inherit"] });
  let browser, failed = 0;
  const ok = (cond, name, detail) => { console.log((cond ? "ok      " : "FAIL    ") + name + (cond ? "" : "\n        " + detail)); if (!cond) failed++; };
  try {
    for (let i = 0; i < 50; i++) { try { await fetch(ORIGIN + "/index.html"); break; } catch { await sleep(100); } }
    // this device's first-version inbox: one private text
    const R1 = { "x-hedy-app-key": "hak_relay.test" };
    const r = await call("/api/relay/ingest", { headers: R1, body: { items: [{ conv: "c1aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", name: "My doctor", sender: "My doctor", text: "Your test results are in", ts: Date.now() - 3600000, fp: fp(1) }] } });
    assert.strictEqual(r.stored, 1, JSON.stringify(r));
    browser = await chromium.launch();

    // ------------------------------------------------ 1. a laptop with the first version's key joins their inbox and says no
    {
      const their = await theirInbox();
      const page = await (await browser.newContext()).newPage();
      const asked = [];
      page.on("dialog", d => { asked.push(d.message()); d.dismiss(); });
      await page.goto(ORIGIN + "/");
      await page.evaluate(() => localStorage.setItem("relay.key", "hak_inbox.test"));
      await page.reload();
      await page.waitForSelector("#conv-list li");
      await page.click("#move-banner button");
      await page.fill("#approve-input", their.code);
      await page.click("#approve-go");
      await page.waitForFunction(() => localStorage.getItem("relay.link2"), null, { timeout: 15000 });
      await sleep(2500);
      const seen = await readable(their);
      const moved = await page.evaluate(() => JSON.parse(localStorage.getItem("relay.moved2") || "{}"));
      ok(asked.length === 1 && /Every device linked to this inbox/.test(asked[0]), "1a. joining an inbox by invite asks before anything moves", JSON.stringify(asked));
      ok(!seen.includes("Your test results are in"), "1b. after a no, nothing of the first version reaches that inbox", JSON.stringify(seen));
      ok(moved.declined === true && moved.tid === their.tid, "1c. the no is kept for that inbox", JSON.stringify(moved));
      await page.reload();
      await page.waitForSelector("#view-inbox:not([hidden])");
      await sleep(1500);
      ok(asked.length === 1, "1d. and not asked again when the page opens again", JSON.stringify(asked));
      await page.context().close();
    }

    // ------------------------------------------------ 2. the same, saying yes: it all comes along, marked as moved
    {
      const their = await theirInbox();
      const page = await (await browser.newContext()).newPage();
      page.on("dialog", d => d.accept());
      await page.goto(ORIGIN + "/");
      await page.evaluate(() => localStorage.setItem("relay.key", "hak_inbox.test"));
      await page.reload();
      await page.waitForSelector("#conv-list li");
      await page.click("#move-banner button");
      await page.fill("#approve-input", their.code);
      await page.click("#approve-go");
      await page.waitForFunction(() => JSON.parse(localStorage.getItem("relay.moved2") || "{}").done, null, { timeout: 90000 });
      const seen = await readable(their);
      ok(seen.includes("Your test results are in"), "2a. after a yes, the first version's texts move in", JSON.stringify(seen));
      ok(await page.evaluate(() => !localStorage.getItem("relay.key")), "2b. and the first version's key is dropped", "");
      await page.waitForSelector('#conv-list li:has-text("My doctor")', { timeout: 15000 });
      await page.click('#conv-list li:has-text("My doctor")');
      await page.waitForSelector('#msg-list:has-text("Your test results are in")');
      const stamp = await page.textContent("#msg-list li.msg .stamp");
      ok(/moved from your first inbox/.test(stamp), "2c. a moved message says so", JSON.stringify(stamp));
      await page.context().close();
    }
  } finally {
    if (browser) await browser.close();
    server.kill();
  }
  console.log(failed ? failed + " move checks failed" : "e2e-move: all checks passed");
  process.exit(failed ? 1 : 0);
})().catch(e => { console.error(e); process.exit(2); });
