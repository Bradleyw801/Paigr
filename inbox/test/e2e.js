// The web page end to end in a real browser (Chromium via Playwright) against test/serve.js: a first-version inbox
// with a picture, the upgrade to a private inbox by entering Relay's code, the move (sealed, attachments included),
// live messages both ways, a second device by invite, and removing it.
const assert = require("assert");
const path = require("path");
const { spawn } = require("child_process");
const { chromium } = require("./playwright");
const TR2 = require("../web/tr2.js");

const PORT = 8790 + Math.floor(Math.random() * 100);
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
  let data = {};
  try { data = text ? JSON.parse(text) : {}; } catch { data = { raw: text }; }
  data._status = res.status;
  return data;
}
const R1 = { "x-hedy-app-key": "hak_relay.test" };

(async () => {
  const server = spawn(process.execPath, [path.join(__dirname, "serve.js"), String(PORT)], { stdio: ["ignore", "inherit", "inherit"] });
  let browser;
  try {
    for (let i = 0; i < 50; i++) { try { await fetch(ORIGIN + "/index.html"); break; } catch { await sleep(100); } }

    // ---------------------------------------------------------- a first-version inbox, with a picture
    const t0 = Date.now() - 3 * 3600000;
    const andy = "c1aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", megan = "c3cccccccccccccccccccccccccccccc";
    let r = await call("/api/relay/ingest", { headers: R1, body: { items: [
      { conv: andy, name: "Andy Graham", sender: "Andy Graham", text: "Lunch at Sundance tomorrow?", ts: t0, fp: fp(1) },
      { conv: andy, name: "Andy Graham", dir: "out", text: "Yes, noon works", ts: t0 + 60000, fp: fp(2), src: "store" }
    ] } });
    assert.strictEqual(r.stored, 2, JSON.stringify(r));
    const png = Buffer.from("89504e470d0a1a0a0000000d4948445200000001000000010806000000" + "1f15c4890000000d4944415478da63f8cfc0f01f0005000201" + "e2266e170000000049454e44ae426082", "hex");
    const up = await call("/api/relay/media", { headers: Object.assign({ "x-relay-conv": megan, "x-relay-fp": fp(3), "x-relay-ts": String(t0 + 120000), "x-relay-kind": "original", "content-type": "image/png" }, R1), body: png });
    assert.ok(up.path, JSON.stringify(up));
    r = await call("/api/relay/ingest", { headers: R1, body: { items: [
      { conv: megan, name: "Megan", sender: "Megan", text: "", ts: t0 + 120000, fp: fp(3), media: { mime: "image/png", path: up.path, w: 1, h: 1, bytes: png.length } }
    ] } });
    assert.strictEqual(r.stored, 1);

    // ---------------------------------------------------------- the page, linked to it with its key
    browser = await chromium.launch();
    const ctx = await browser.newContext();
    const page = await ctx.newPage();
    const errors = [];
    page.on("pageerror", e => errors.push(String(e)));
    page.on("console", m => { if (m.type() === "error" && !/WebSocket|ERR_CONNECTION_REFUSED|favicon|status of 404/.test(m.text())) errors.push(m.text()); });
    await page.goto(ORIGIN + "/");
    await page.evaluate(() => localStorage.setItem("relay.key", "hak_inbox.test"));
    await page.reload();
    await page.waitForSelector("#conv-list li");
    assert.strictEqual(await page.locator("#conv-list li").count(), 2);
    assert.ok((await page.textContent("#move-banner")).includes("Make your inbox private"));

    // ---------------------------------------------------------- Relay 0.9 shows its code; it's typed in here
    const relay = { token: TR2.newToken() };
    const code = TR2.newCode();
    const s = await TR2.linkSecrets(code);
    r = await call("/v2/link/ask", { body: { lookup: s.lookup, token: relay.token, role: "relay", label: "Unihertz Titan 2 Elite" } });
    assert.strictEqual(r._status, 200, JSON.stringify(r));
    await page.click("#move-banner button");
    await page.fill("#approve-input", TR2.prettyCode(code).toLowerCase());
    await page.click("#approve-go");
    for (let i = 0; i < 100; i++) {
      r = await call("/v2/link/wait", { body: { lookup: s.lookup, token: relay.token } });
      if (r._status === 200) break;
      await sleep(100);
    }
    assert.strictEqual(r._status, 200, JSON.stringify(r));
    const box = await TR2.openJson(s.boxKey, r.answer, "tr2:box");
    relay.tid = box.tid;
    relay.keys = await TR2.inboxKeys(TR2.unb64u(box.key));
    const H = { "x-relay-token": relay.token };
    assert.strictEqual((await call("/v2/ping", { headers: H })).role, "relay");

    // ---------------------------------------------------------- the move waits for the relay phone to switch over
    await page.waitForFunction(() => document.getElementById("move-banner").textContent.includes("Waiting for the relay phone"), null, { timeout: 10000 });
    await sleep(1500);
    assert.ok(!JSON.parse(await page.evaluate(() => localStorage.getItem("relay.moved2") || "{}")).started, "nothing moves before it has");
    // as Relay sends it: its words and its model sealed, numbers and switches in the open
    const sn = await TR2.sealJson(relay.keys.enc, { note: "Could not read an attachment (image/heic)", history: "", model: "Unihertz Titan 2 Elite" }, TR2.AAD.note);
    r = await call("/v2/relay/heartbeat", { headers: H, body: { listening: true, sn } });
    assert.strictEqual(r._status, 200, JSON.stringify(r));

    // ---------------------------------------------------------- the move: everything comes over, sealed
    await page.waitForFunction(() => !localStorage.getItem("relay.key") && JSON.parse(localStorage.getItem("relay.moved2") || "{}").done, null, { timeout: 40000 });
    // then the relay phone is asked to send its last week again, for anything that reached the old inbox meanwhile
    const afterMove = await call("/v2/relay/outbox", { headers: H });
    assert.strictEqual(afterMove.wakeKind, "resync", JSON.stringify(afterMove));
    const dump = await call("/_debug/dump");
    const v2msgs = Object.values(dump["v2-messages"]);
    assert.strictEqual(v2msgs.length, 3, "three messages moved");
    const everything = JSON.stringify([dump["v2-messages"], dump["v2-convs"]]);
    for (const word of ["Sundance", "Andy", "Megan", "noon", andy, megan]) assert.ok(!everything.includes(word), word + " readable on the server");
    const pic = v2msgs.find(m => m.media && m.media.path);
    assert.ok(pic && /^\/media\/v2\/[0-9a-f]{32}\//.test(pic.media.path), JSON.stringify(pic));
    assert.strictEqual(dump.files[pic.media.path].type, "application/zip");
    assert.strictEqual(dump.files[pic.media.path].bytes, png.length + 28);
    // the relay phone's later copy of a moved message is known, not stored twice
    const wAndy = await TR2.convId(relay.keys, andy);
    const item = async (x) => ({
      conv: await TR2.convId(relay.keys, x.conv), fp: await TR2.fpId(relay.keys, x.fp), ts: x.ts, dir: x.dir || "in", src: x.src || "notif",
      e: await TR2.sealJson(relay.keys.enc, { text: x.text, sender: x.sender || "", name: x.name || "", group: false, ts: x.ts, fp: await TR2.fpId(relay.keys, x.fp) },
        TR2.AAD.message(await TR2.convId(relay.keys, x.conv), x.dir || "in")),
      ne: x.name ? await TR2.sealJson(relay.keys.enc, { name: x.name, group: false }, TR2.AAD.name(await TR2.convId(relay.keys, x.conv))) : undefined,
      mt: await TR2.matchToken(relay.keys, x.dir || "in", x.text, "")
    });
    r = await call("/v2/relay/ingest", { headers: H, body: { items: [await item({ conv: andy, name: "Andy Graham", sender: "Andy Graham", text: "Lunch at Sundance tomorrow?", ts: t0, fp: fp(1) })] } });
    assert.strictEqual(r.known, 1, JSON.stringify(r));

    // ---------------------------------------------------------- the page reads it all
    await page.evaluate(() => { const b = document.getElementById("settings-back"); if (!b.closest("[hidden]")) b.click(); });
    await page.waitForSelector("#view-inbox:not([hidden]) #conv-list li");
    const names = await page.$$eval("#conv-list .conv-name", els => els.map(e => e.textContent));
    assert.deepStrictEqual(names.sort(), ["Andy Graham", "Megan"]);
    assert.ok((await page.textContent("#conv-list")).includes("Picture"));
    await page.click('#conv-list li:has-text("Megan")');
    await page.waitForSelector("#msg-list img.pic[src^='blob:']", { timeout: 10000 });
    const shown = await page.evaluate(async () => Array.from(new Uint8Array(await (await fetch(document.querySelector("#msg-list img.pic").src)).arrayBuffer())));
    assert.deepStrictEqual(Buffer.from(shown), png, "the picture opens to the original bytes");
    await page.click("#back-btn");

    // ---------------------------------------------------------- live: a text in, a reply out
    r = await call("/v2/relay/ingest", { headers: H, body: { items: [await item({ conv: andy, name: "Andy Graham", sender: "Andy Graham", text: "Running 5 min late 🏃", ts: Date.now(), fp: fp(10) })] } });
    assert.strictEqual(r.stored, 1);
    await page.waitForFunction(() => document.getElementById("conv-list").textContent.includes("Running 5 min late"), null, { timeout: 15000 });
    await page.click('#conv-list li:has-text("Andy Graham")');
    await page.waitForSelector('#msg-list:has-text("Running 5 min late")');
    await page.fill("#msg-input", "No rush, see you there");
    await page.click("#send-btn");
    let ob;
    for (let i = 0; i < 50; i++) {
      ob = await call("/v2/relay/outbox", { headers: H });
      if (ob.items && ob.items.length) break;
      await sleep(100);
    }
    assert.strictEqual(ob.items.length, 1);
    assert.strictEqual(ob.items[0].conv, wAndy);
    // the relay phone gets a request sealed for this conversation alone, with a nonce and the time it was made
    const request = await TR2.openJson(relay.keys.enc, ob.items[0].o, TR2.AAD.out(wAndy));
    assert.strictEqual(request.text, "No rush, see you there");
    assert.ok(/^[A-Za-z0-9_-]{22}$/.test(request.n) && Math.abs(request.ts - Date.now()) < 60000, JSON.stringify(request));
    await assert.rejects(TR2.openJson(relay.keys.enc, ob.items[0].o, "tr2:m"));
    await call("/v2/relay/outbox/result", { headers: H, body: { id: ob.items[0].id, status: "sent", via: "rcs", canReply: true } });
    await page.click("#back-btn");
    // what the relay phone said about itself shows in Settings, opened here
    await page.click("#settings-btn");
    await page.waitForFunction(() => document.getElementById("settings-relay").textContent.includes("image/heic"), null, { timeout: 10000 });
    await page.click("#settings-back");

    // ---------------------------------------------------------- a dishonest server can't pass one conversation off as another
    const dumpA = await call("/_debug/dump");
    const wMegan = await TR2.convId(relay.keys, megan);
    const andyMsg = Object.values(dumpA["v2-messages"]).find(m => m.conv === wAndy && m.dir === "in" && m.ts > t0 + 3600000);
    assert.ok(andyMsg, "Andy's live text is there");
    const fakeKey = "m:" + pad13(Date.now()) + ":ffffffffffffffff";
    await call("/_debug/put", { body: { collection: "v2-messages", key: relay.tid + "/" + fakeKey, value: Object.assign({}, andyMsg, { k: fakeKey, conv: wMegan, cv: relay.tid + "/" + wMegan }) } });
    const andySum = dumpA["v2-convs"][relay.tid + "/" + wAndy], meganSum = dumpA["v2-convs"][relay.tid + "/" + wMegan];
    await call("/_debug/put", { body: { collection: "v2-convs", key: relay.tid + "/" + wMegan, value: Object.assign({}, meganSum, { ne: andySum.ne, lastE: andySum.lastE }) } });
    await page.reload();
    await page.waitForSelector("#view-inbox:not([hidden]) #conv-list li");
    const swapped = await page.$$eval("#conv-list .conv-name", els => els.map(e => e.textContent));
    assert.strictEqual(swapped.filter(n => n === "Andy Graham").length, 1, "only Andy's own conversation is Andy's: " + swapped.join(", "));
    assert.ok(swapped.includes("Unknown"), swapped.join(", "));
    await page.click('#conv-list li:has-text("Unknown")');
    await page.waitForSelector('#msg-list:has-text("couldn\'t be checked")', { timeout: 10000 });
    assert.ok(!(await page.textContent("#msg-list")).includes("Running 5 min late"), "Andy's words never show as Megan's");
    await page.waitForSelector("#msg-list img.pic[src^='blob:']", { timeout: 10000 });   // Megan's own picture still opens
    await page.click("#back-btn");
    await call("/_debug/put", { body: { collection: "v2-convs", key: relay.tid + "/" + wMegan, value: meganSum } });

    // ---------------------------------------------------------- two conversations the relay phone found to be one
    const samThread = "c5eeeeeeeeeeeeeeeeeeeeeeeeeeeeee";
    const wSam = await TR2.convId(relay.keys, samThread);
    r = await call("/v2/relay/ingest", { headers: H, body: { items: [await item({ conv: samThread, name: "(801) 555-0123", sender: "(801) 555-0123", text: "From the message store", ts: Date.now() - 60000, fp: fp(20), src: "store" })] } });
    assert.strictEqual(r.stored, 1);
    const mr = await TR2.sealJson(relay.keys.enc, { from: wSam, into: wAndy }, TR2.AAD.merge(wAndy));
    r = await call("/v2/relay/merge", { headers: H, body: { from: wSam, into: wAndy, mr } });
    assert.strictEqual(r.more, false, JSON.stringify(r));
    await page.reload();
    await page.waitForSelector("#view-inbox:not([hidden]) #conv-list li");
    await page.click('#conv-list li:has-text("Andy Graham")');
    await page.waitForSelector('#msg-list:has-text("From the message store")', { timeout: 10000 });
    assert.ok(!(await page.textContent("#msg-list")).includes("couldn't be checked"), "the relay phone's word for the merge is enough");
    await page.click("#back-btn");

    // ---------------------------------------------------------- another device by invite: a laptop types Relay's code
    const code2 = TR2.newCode();
    const s2 = await TR2.linkSecrets(code2);
    r = await call("/v2/link/invite", { headers: H, body: { lookup: s2.lookup, box: await TR2.sealJson(s2.boxKey, { v: 2, tid: relay.tid, key: TR2.b64u(relay.keys.raw) }, "tr2:box"), role: "inbox" } });
    assert.strictEqual(r._status, 200, JSON.stringify(r));
    const ctx2 = await browser.newContext();
    const laptop = await ctx2.newPage();
    laptop.on("pageerror", e => errors.push("laptop: " + e));
    await laptop.goto(ORIGIN + "/#link=" + code2);   // opened from a link to the page: filled in, waits for a tap
    await laptop.waitForSelector("#view-connect:not([hidden])");
    assert.strictEqual((await laptop.inputValue("#code-input")).replace(/-/g, ""), code2);
    await laptop.click("#connect-btn");
    await laptop.waitForSelector("#view-inbox:not([hidden]) #conv-list li", { timeout: 15000 });
    assert.ok((await laptop.textContent("#conv-list")).includes("Andy Graham"));
    assert.ok((await laptop.textContent("#conv-list")).includes("No rush"));

    // ---------------------------------------------------------- the devices list, and removing the laptop
    await page.click("#settings-btn");
    await page.waitForFunction(() => document.querySelectorAll("#device-list li").length === 3, null, { timeout: 10000 });
    const devs = await page.$$eval("#device-list li", els => els.map(e => e.textContent));
    assert.strictEqual(devs.length, 3, devs.join(" | "));
    assert.ok(devs.some(d => d.includes("Relay phone")));
    assert.ok(devs.some(d => d.includes("(this device)")));
    page.once("dialog", d => d.accept());
    await page.locator("#device-list li", { hasText: "Chrome" }).filter({ hasNotText: "this device" }).locator("button").click();
    await page.waitForFunction(() => document.querySelectorAll("#device-list li").length === 2, null, { timeout: 10000 });
    await laptop.waitForSelector("#view-connect:not([hidden])", { timeout: 20000 });

    assert.deepStrictEqual(errors, [], errors.join("\n"));
    console.log("e2e: all browser checks passed");
  } finally {
    if (browser) await browser.close();
    server.kill();
  }
})().catch(e => { console.error(e); process.exit(1); });
