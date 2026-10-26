// Reactions, quoted replies, the emoji panel and attachments on the page, against test/serve.js: what arrives in the
// forms phones use is drawn as what it is, and what the page sends travels the same way (vectors shared with the apps).
//   node test/e2e-features.js
const assert = require("assert");
const path = require("path");
const { spawn } = require("child_process");
const { chromium } = require("./playwright");
const TR2 = require("../web/tr2.js");

const PORT = 10000 + Math.floor(Math.random() * 500);
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

/** A tiny PNG (1x1 would do, but a few pixels make a picture). */
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
async function check(name, fn) {
  try { await fn(); results.push([name, "ok"]); console.log("ok      " + name); }
  catch (e) { results.push([name, "FAIL"]); console.log("FAIL    " + name + "\n        " + String(e && e.stack || e).split("\n").slice(0, 3).join("\n        ")); }
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
    const R = { "x-relay-token": relay.token };
    const conv = await TR2.convId(keys, "c-maya");
    let n = 0;
    const arrives = async (text, out, at) => {
      const ts = at || Date.now();
      const fp = await TR2.fpId(keys, (++n).toString(16).padStart(16, "0"));
      const dir = out ? "out" : "in";
      await call("/v2/relay/ingest", { headers: R, body: { items: [{ conv, fp, ts, dir, src: "notif", read: !!out,
        e: await TR2.sealJson(keys.enc, { text, sender: out ? "" : "Maya Chen", name: "Maya Chen", group: false, ts, fp }, TR2.AAD.message(conv, dir)),
        ne: await TR2.sealJson(keys.enc, { name: "Maya Chen", group: false }, TR2.AAD.name(conv)), mt: await TR2.matchToken(keys, dir, text, "") }], replyable: { [conv]: true } } });
      return ts;
    };
    const t0 = Date.now() - 20 * 60000;
    await arrives("Yes! Are you still coming tonight?", false, t0);
    await arrives("Doors open at 7", false, t0 + 60000);
    await arrives("On my way at 6:30", true, t0 + 14 * 60000);
    await call("/v2/relay/heartbeat", { headers: R, body: { listening: true, sn: await TR2.sealJson(keys.enc, { note: "", history: "" }, TR2.AAD.note) } });

    browser = await chromium.launch();
    const page = await (await browser.newContext()).newPage();
    await page.goto(ORIGIN + "/");
    await page.evaluate((l) => localStorage.setItem("relay.link2", l), JSON.stringify({ tid, token: texts.token, key: TR2.b64u(K) }));
    await page.reload();
    await page.waitForSelector('#view-inbox:not([hidden]) #conv-list li:has-text("Maya Chen")', { timeout: 15000 });
    await page.click('#conv-list li:has-text("Maya Chen")');
    await page.waitForSelector('#msg-list:has-text("On my way at 6:30")', { timeout: 10000 });

    // ------------------------------------------------ what arrives
    await arrives("Loved “On my way at 6:30”");
    await check("a. a reaction from their phone folds under the message it names, as a chip", async () => {
      await page.waitForSelector('#msg-list li:has-text("On my way at 6:30") .chip', { timeout: 15000 });
      const list = await page.textContent("#msg-list");
      assert.ok(list.includes("❤️"), list);
      assert.ok(!list.includes("Loved"), list);
      assert.strictEqual(await page.getAttribute('#msg-list li:has-text("On my way at 6:30") .chip', "title"), "❤️ from Maya Chen. Tap to react too");
    });
    await arrives("> On my way at 6:30\nPerfect, see you!");
    await check("b. a quoted reply shows what it answers, and the inbox list says only the answer", async () => {
      await page.waitForSelector('#msg-list li:has-text("Perfect, see you!")', { timeout: 15000 });
      const li = page.locator('#msg-list li:has-text("Perfect, see you!")');
      assert.strictEqual(await li.locator(".quote-who").textContent(), "You");
      assert.strictEqual(await li.locator(".quote-text").textContent(), "On my way at 6:30");
      assert.ok(!(await li.textContent()).includes("> On my way"));
      await page.click("#back-btn");
      await page.waitForSelector('#conv-list li:has-text("Perfect, see you!")', { timeout: 10000 });
      const row = await page.textContent('#conv-list li:has-text("Maya Chen")');
      assert.ok(!row.includes(">"), row);
      await page.click('#conv-list li:has-text("Maya Chen")');
      await page.waitForSelector('#msg-list:has-text("Perfect, see you!")', { timeout: 10000 });
    });

    // ------------------------------------------------ reacting and replying from here
    const outbox = async (after) => {
      for (let i = 0; i < 100; i++) {
        const items = (await call("/v2/relay/outbox", { headers: R })).items || [];
        const fresh = items.filter(it => !after.has(it.id));
        if (fresh.length) { after.add(fresh[0].id); return fresh[0]; }
        await sleep(100);
      }
      throw new Error("nothing reached the outbox");
    };
    const seen = new Set();
    await check("c. a long press offers the quick six; one goes out as the text their phone shows as a reaction", async () => {
      await page.click('#msg-list li:has-text("Doors open at 7") .bubble', { button: "right" });
      await page.waitForSelector("#msg-menu", { timeout: 5000 });
      await page.click('#msg-menu button[aria-label="React 👍"]');
      const it = await outbox(seen);
      const o = await TR2.openJson(keys.enc, it.o, TR2.AAD.out(conv));
      assert.strictEqual(o.text, "Liked “Doors open at 7”");
      await page.waitForSelector('#msg-list li:has-text("Doors open at 7") .chip.mine', { timeout: 10000 });
      assert.ok(!(await page.textContent("#msg-list")).includes("Liked"));
      await call("/v2/relay/outbox/result", { headers: R, body: { id: it.id, status: "sent", via: "sms" } });
    });
    await check("d. tapping an own chip takes it back", async () => {
      await page.click('#msg-list li:has-text("Doors open at 7") .chip.mine');
      const it = await outbox(seen);
      const o = await TR2.openJson(keys.enc, it.o, TR2.AAD.out(conv));
      assert.strictEqual(o.text, "Removed a like from “Doors open at 7”");
      await page.waitForSelector('#msg-list li:has-text("Doors open at 7"):not(:has(.chip))', { timeout: 10000 });
    });
    await check("e. Reply quotes the message, and the reply shows what it answers", async () => {
      await page.click('#msg-list li:has-text("Doors open at 7") .bubble', { button: "right" });
      await page.click('#msg-menu button:has-text("Reply")');
      assert.strictEqual(await page.textContent("#reply-who"), "Replying to Maya Chen");
      await page.fill("#msg-input", "On it");
      await page.click("#send-btn");
      const it = await outbox(seen);
      const o = await TR2.openJson(keys.enc, it.o, TR2.AAD.out(conv));
      assert.strictEqual(o.text, "> Doors open at 7\nOn it");
      assert.ok(await page.isHidden("#reply-bar"));
      const li = page.locator('#msg-list li:has-text("On it")');
      assert.strictEqual(await li.locator(".quote-text").textContent(), "Doors open at 7");
      assert.strictEqual(await li.locator(".quote-who").textContent(), "Maya Chen");
    });
    await check("f. the emoji panel types into the reply", async () => {
      await page.click("#emoji-btn");
      await page.waitForSelector("#emoji-panel:not([hidden])");
      await page.click('#emoji-panel button:has-text("😂")');
      assert.strictEqual(await page.inputValue("#msg-input"), "😂");
      await page.keyboard.press("Escape");
      assert.ok(await page.isHidden("#emoji-panel"));
      await page.fill("#msg-input", "");
    });

    // ------------------------------------------------ an attachment
    await check("g. a picture goes out sealed, named for every device in the payload and for the relay phone in the request", async () => {
      const picture = png();
      await page.setInputFiles("#attach-input", { name: "market.png", mimeType: "image/png", buffer: picture });
      await page.waitForSelector("#attach-bar:not([hidden])");
      assert.strictEqual(await page.textContent("#attach-name"), "market.png");
      await page.fill("#msg-input", "From the market");
      await page.click("#send-btn");
      const it = await outbox(seen);
      const o = await TR2.openJson(keys.enc, it.o, TR2.AAD.out(conv));
      assert.strictEqual(o.text, "From the market");
      assert.strictEqual(o.media.mime, "image/png");
      assert.strictEqual(o.media.name, "market.png");
      assert.strictEqual(o.media.bytes, picture.length);
      assert.ok(o.media.path.startsWith("/media/v2/" + tid + "/" + conv + "/") && o.media.path.endsWith("-o.enc"), o.media.path);
      const held = Object.values((await call("/_debug/dump"))["v2-messages"]).find(v => v.media && v.media.path === o.media.path);
      assert.ok(held, "the inbox holds the message by its file");
      const e = await TR2.openJson(keys.enc, held.e, TR2.AAD.message(conv, "out"));
      assert.strictEqual(e.media.w, 8);
      assert.strictEqual(e.media.h, 6);
      assert.ok(o.media.path.includes("-" + e.fp + "-o.enc"));
      const stored = Buffer.from(await (await fetch(ORIGIN + "/_files" + o.media.path)).arrayBuffer());
      assert.ok(!stored.includes("IHDR"), "stored sealed");
      const back = await TR2.openBytes(keys.enc, new Uint8Array(stored), TR2.AAD.file(conv, e.ts, e.fp, "original"));
      assert.strictEqual(back.length, picture.length);
      await call("/v2/relay/outbox/result", { headers: R, body: { id: it.id, status: "sent", via: "mms" } });
      await page.waitForSelector('#msg-list:has-text("Sent as MMS")', { timeout: 10000 });
      assert.ok(await page.isVisible('#msg-list li:has-text("From the market") img.pic'));
    });
    await check("h. a failed attachment says why in the page's words", async () => {
      await page.setInputFiles("#attach-input", { name: "clip.mp4", mimeType: "video/mp4", buffer: Buffer.alloc(3000, 1) });
      await page.click("#send-btn");
      const it = await outbox(seen);
      await call("/v2/relay/outbox/result", { headers: R, body: { id: it.id, status: "failed", code: "too_big" } });
      await page.waitForSelector('#msg-list:has-text("Too big to send as MMS from the relay phone.")', { timeout: 10000 });
    });

    // ------------------------------------------------ a new conversation, from a number
    await check("i. New message: a number typed here opens a conversation the relay phone keys from it; the first text names the number inside its request", async () => {
      await page.click("#back-btn");
      await page.waitForSelector("#view-inbox:not([hidden])");
      await page.click("#compose-btn");
      await page.waitForSelector("#view-compose:not([hidden])");
      await page.fill("#to-input", "(801) 555-0177");
      await page.click("#compose-next");
      await page.waitForSelector("#view-thread:not([hidden])");
      const started = await TR2.convId(keys, "to:8015550177");
      assert.strictEqual(await page.evaluate(() => document.getElementById("thread-name").textContent), "(801) 555-0177");
      const notice = await page.textContent("#thread-notice");
      assert.ok(notice.includes("Your first text to (801) 555-0177 goes out as SMS"), notice);
      await page.fill("#msg-input", "Hi, is the bike still for sale?");
      await page.click("#send-btn");
      const it = await outbox(seen);
      assert.strictEqual(it.conv, started);
      const request = await TR2.openJson(keys.enc, it.o, TR2.AAD.out(started));
      assert.strictEqual(request.text, "Hi, is the bike still for sale?");
      assert.strictEqual(request.to, "8015550177");
      // the inbox lists it now, under the name this device sealed
      const listed = (await call("/v2/inbox", { headers: { "x-relay-token": texts.token } })).items.find(c => c.conv === started);
      assert.ok(listed && listed.started, "listed");
      assert.strictEqual((await TR2.openJson(keys.enc, listed.ne, TR2.AAD.name(started))).name, "(801) 555-0177");
      await call("/v2/relay/outbox/result", { headers: R, body: { id: it.id, status: "sent", via: "sms", canReply: false } });
      await page.waitForSelector('#msg-list:has-text("Sent as SMS")', { timeout: 10000 });
      assert.ok(await page.isHidden("#thread-notice") || !(await page.textContent("#thread-notice")).includes("first text"));
      // back on the list: there it is; the same number typed another way is the same conversation
      await page.click("#back-btn");
      await page.waitForSelector('#conv-list li:has-text("(801) 555-0177")');
      await page.click("#compose-btn");
      await page.fill("#to-input", "+1 801 555 0177");
      await page.click("#compose-next");
      await page.waitForSelector('#view-thread:not([hidden]) #msg-list:has-text("Hi, is the bike still for sale?")');
      // and a conversation left without a word isn't listed
      await page.click("#back-btn");
      await page.click("#compose-btn");
      await page.fill("#to-input", "801-555-0199");
      await page.click("#compose-next");
      await page.waitForSelector("#view-thread:not([hidden])");
      await page.click("#back-btn");
      await page.waitForSelector("#view-inbox:not([hidden])");
      assert.ok(!(await page.textContent("#conv-list")).includes("(801) 555-0199"));
      // what isn't a number stays on the compose screen
      await page.click("#compose-btn");
      await page.fill("#to-input", "call me maybe");
      await page.click("#compose-next");
      await sleep(300);
      assert.ok(await page.isVisible("#view-compose"));
      await page.keyboard.press("Escape");
      await page.waitForSelector("#view-inbox:not([hidden])");
    });
    await check("j. the relay phone folds a started conversation into the one it had with that number: the page follows", async () => {
      await page.click("#compose-btn");
      await page.fill("#to-input", "801 555 0123");
      await page.click("#compose-next");
      await page.waitForSelector("#view-thread:not([hidden])");
      const started = await TR2.convId(keys, "to:8015550123");
      await page.fill("#msg-input", "Is this Maya?");
      await page.click("#send-btn");
      const it = await outbox(seen);
      assert.strictEqual(it.conv, started);
      await call("/v2/relay/outbox/result", { headers: R, body: { id: it.id, status: "sent", via: "rcs", canReply: true } });
      const merged = await call("/v2/relay/merge", { headers: R, body: { from: started, into: conv, mr: await TR2.sealJson(keys.enc, { from: started, into: conv }, TR2.AAD.merge(conv)) } });
      assert.strictEqual(merged._status, 200, JSON.stringify(merged));
      await page.waitForSelector('#view-thread:not([hidden]) #msg-list:has-text("Is this Maya?")', { timeout: 15000 });
      await page.waitForFunction(() => document.getElementById("thread-name").textContent === "Maya Chen", null, { timeout: 15000 });
      await page.waitForSelector('#toast:has-text("That number is Maya Chen: continued here.")', { timeout: 10000 });
      await page.click("#back-btn");
      await page.waitForSelector("#view-inbox:not([hidden])");
      assert.ok(!(await page.textContent("#conv-list")).includes("(801) 555-0123"));
      // texting the number again goes to Maya's conversation now
      await page.click("#compose-btn");
      await page.fill("#to-input", "(801) 555-0123");
      await page.click("#compose-next");
      await page.waitForFunction(() => document.getElementById("thread-name").textContent === "Maya Chen", null, { timeout: 10000 });
    });
    await check("k. Google's reaction forms, and a reaction timed before the text it names, still attach as chips", async () => {
      // in Maya's conversation after j; a text from here, then her reactions as Google words them
      await page.fill("#msg-input", "See you at the north gate, by the big tree.");
      await page.click("#send-btn");
      const it = await outbox(seen);
      await call("/v2/relay/outbox/result", { headers: R, body: { id: it.id, status: "sent", via: "rcs" } });
      await page.waitForSelector('#msg-list li:has-text("See you at the north gate") :text("Sent")', { timeout: 10000 });
      const sentAt = (await TR2.openJson(keys.enc, it.o, TR2.AAD.out(conv))).ts;
      await arrives("Reacted ❤️ to \"See you at the north gate, by the big tree.\"", false, sentAt - 20000);
      await page.waitForSelector('#msg-list li:has-text("See you at the north gate") .chip', { timeout: 15000 });
      await arrives("Maya Chen reacted 👍 to See you at the north gate, by the…", false, Date.now());
      await arrives("Reacted 😂 to your message.", false, Date.now() + 1);
      await page.waitForFunction(() => document.querySelectorAll('#msg-list li .chip').length >= 3 && [...document.querySelectorAll("#msg-list li")].every(li => !li.textContent.includes("Reacted")), null, { timeout: 15000 });
      const chips = await page.$$eval('#msg-list li:has-text("See you at the north gate") .chip', els => els.map(e => e.textContent));
      assert.ok(chips.some(c => c.includes("❤️")) && chips.some(c => c.includes("👍")) && chips.some(c => c.includes("😂")), chips.join(","));
    });
    if (process.env.SHOT) {   // a look at it: SHOT=/path/to.png node test/e2e-features.js
      await page.setViewportSize({ width: 400, height: 760 });
      await page.click('#msg-list li:has-text("Doors open at 7") .bubble', { button: "right" });
      await page.waitForSelector("#msg-menu");
      await page.screenshot({ path: process.env.SHOT });
    }
  } finally {
    if (browser) await browser.close();
    server.kill();
  }
  const failed = results.filter(x => x[1] === "FAIL");
  console.log(failed.length ? "FAILED " + failed.length + " of " + results.length : "all " + results.length + " passed");
  process.exit(failed.length ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
