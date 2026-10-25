// The page against a dishonest server: test/serve.js's /_debug/put rewrites what the server holds, and Playwright routes
// stand in for a server that answers late or says something else. Each check is what the threat model promises
// (PROTOCOL.md, "What each seal is bound to"). Started as an independent reviewer's proof tests, kept as regressions.
//   node test/e2e-dishonest.js
const assert = require("assert");
const path = require("path");
const { spawn } = require("child_process");
const { chromium } = require("./playwright");
const TR2 = require("../web/tr2.js");

const PORT = 9100 + Math.floor(Math.random() * 300);
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

    // ------------------------------------------------ an inbox: relay + texts, made through the API
    const relay = { token: TR2.newToken() }, texts = { token: TR2.newToken() };
    const code = TR2.newCode(), s = await TR2.linkSecrets(code);
    assert.strictEqual((await call("/v2/link/ask", { body: { lookup: s.lookup, token: relay.token, role: "relay", label: "Relay" } }))._status, 200);
    const K = TR2.newKey(), tid = TR2.newTid();
    const answer = await TR2.sealJson(s.boxKey, { v: 2, tid, key: TR2.b64u(K) }, TR2.AAD.box);
    const adopted = await call("/v2/link/adopt", { body: { lookup: s.lookup, answer, create: { tid, token: texts.token, label: "Texts" } } });
    assert.strictEqual(adopted._status, 200, JSON.stringify(adopted));
    const keys = await TR2.inboxKeys(K);
    const H = { "x-relay-token": relay.token };

    const wA = await TR2.convId(keys, "c-andy"), wB = await TR2.convId(keys, "c-megan");
    const t0 = Date.now() - 2 * 3600000;
    // A message as the relay phone seals it: bound to its conversation and direction, naming its time and fingerprint.
    const item = async (x) => {
      const conv = x.conv, f = await TR2.fpId(keys, fp(x.n)), dir = x.dir || "in";
      const out = { conv, fp: f, ts: x.ts, dir, src: "notif",
        e: await TR2.sealJson(keys.enc, { text: x.text, sender: x.sender || "", name: x.name || "", group: false, ts: x.ts, fp: f, media: x.media }, TR2.AAD.message(conv, dir)),
        ne: await TR2.sealJson(keys.enc, { name: x.name, group: false }, TR2.AAD.name(conv)),
        mt: await TR2.matchToken(keys, dir, x.text, x.media && x.media.mime) };
      if (x.path) out.media = { path: x.path };
      return out;
    };
    const png = Buffer.from("89504e470d0a1a0a0000000d4948445200000001000000010806000000" + "1f15c4890000000d4944415478da63f8cfc0f01f0005000201" + "e2266e170000000049454e44ae426082", "hex");
    const upload = async (conv, ts, f) => (await call("/v2/media/put", {
      headers: Object.assign({ "x-relay-conv": conv, "x-relay-fp": f, "x-relay-ts": String(ts), "x-relay-kind": "original", "content-type": "application/octet-stream" }, H),
      body: Buffer.from(await TR2.sealBytes(keys.enc, png, TR2.AAD.file(conv, ts, f, "original"))) })).path;
    const picTs = t0 + 30000, pic2Ts = t0 + 40000;
    const picPath = await upload(wA, picTs, await TR2.fpId(keys, fp(3)));
    const pic2Path = await upload(wA, pic2Ts, await TR2.fpId(keys, fp(7)));
    assert.ok(picPath && pic2Path);
    const pngMedia = { mime: "image/png", w: 1, h: 1, bytes: png.length };
    let r = await call("/v2/relay/ingest", { headers: H, body: { items: [
      await item({ conv: wA, n: 1, ts: t0, text: "I owe you $500, will pay Friday", sender: "Andy", name: "Andy" }),
      await item({ conv: wA, n: 2, ts: t0 + 10000, dir: "out", text: "No problem", name: "Andy" }),
      await item({ conv: wA, n: 5, ts: t0 + 20000, text: "Are we still on for Friday?", sender: "Andy", name: "Andy" }),
      await item({ conv: wA, n: 3, ts: picTs, text: "", sender: "Andy", name: "Andy", media: pngMedia, path: picPath }),
      await item({ conv: wA, n: 7, ts: pic2Ts, text: "", sender: "Andy", name: "Andy", media: pngMedia, path: pic2Path }),
      await item({ conv: wB, n: 4, ts: t0 + 60000, text: "See you at 6", sender: "Megan", name: "Megan" })
    ] } });
    assert.strictEqual(r.stored, 6, JSON.stringify(r));
    const msgs = (await call("/_debug/dump"))["v2-messages"];
    const keyOf = async (ts, n) => "m:" + pad13(ts) + ":" + await TR2.fpId(keys, fp(n));
    const kOwe = await keyOf(t0, 1), kHi = await keyOf(t0 + 20000, 5), kPic = await keyOf(picTs, 3), kPic2 = await keyOf(pic2Ts, 7), kMeg = await keyOf(t0 + 60000, 4);
    const owe = msgs[tid + "/" + kOwe], hi = msgs[tid + "/" + kHi], pic = msgs[tid + "/" + kPic], pic2 = msgs[tid + "/" + kPic2], meg = msgs[tid + "/" + kMeg];
    const putMsg = (k, v) => call("/_debug/put", { body: { collection: "v2-messages", key: tid + "/" + k, value: Object.assign({}, v, { k, cv: tid + "/" + v.conv }) } });

    // ------------------------------------------------ what the dishonest server changes
    const now = Date.now();
    // 1. Andy's "I owe you" turned into something the user sent (only `dir` changed)
    await putMsg(kOwe, Object.assign({}, owe, { dir: "out", status: "sent", via: "rcs", sentAt: owe.ts }));
    // 2. "Are we still on" again, as a new message an hour later (same sealed payload, new key and time)
    const kReplay = "m:" + pad13(t0 + 3600000) + ":" + "aaaaaaaaaaaaaaaa";
    await putMsg(kReplay, Object.assign({}, hi, { k: kReplay, ts: t0 + 3600000 }));
    // 2b. the same again, sealed afresh (as the relay phone does when the server asks for a resync), under another key
    const kResealed = "m:" + pad13(t0 + 7200000) + ":" + "abababababababab";
    await putMsg(kResealed, Object.assign({}, hi, { k: kResealed, ts: t0 + 7200000,
      e: (await item({ conv: wA, n: 5, ts: t0 + 20000, text: "Are we still on for Friday?", sender: "Andy", name: "Andy" })).e }));
    // 2c. Megan's message with its seal written another way (the same bytes, other unused bits in its last character)
    const respell = (e) => {
      const tail = e.slice(-1), alpha = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
      for (const c of alpha) {
        const v = e.slice(0, -1) + c;
        if (v === e) continue;
        const a = Buffer.from(v.slice(3).replace(/-/g, "+").replace(/_/g, "/"), "base64"), b = Buffer.from(e.slice(3).replace(/-/g, "+").replace(/_/g, "/"), "base64");
        if (a.equals(b)) return v;
      }
      return null;
    };
    let megSeal = meg.e, megRespelled = respell(megSeal);
    for (let i = 0; !megRespelled && i < 4; i++) {
      megSeal = (await item({ conv: wB, n: 4, ts: t0 + 60000, text: "See you at 6" + " ".repeat(i + 1), sender: "Megan", name: "Megan" })).e;
      megRespelled = respell(megSeal);
    }
    assert.ok(megRespelled, "a seal with unused bits");
    const kSpelled = "m:" + pad13(now - 15000) + ":" + "cdcdcdcdcdcdcdcd";
    await putMsg(kSpelled, Object.assign({}, meg, { k: kSpelled, ts: now - 15000, e: megRespelled }));
    // 3. a message the relay phone never sent: nothing sealed, with Andy's own picture
    const kFab = "m:" + pad13(now - 60000) + ":" + "bbbbbbbbbbbbbbbb";
    await putMsg(kFab, { k: kFab, conv: wA, dir: "in", status: "received", e: "", ts: now - 60000, src: "notif", media: { path: picPath, mime: "image/png" } });
    // 4. a failed "reply" with the server's own words under it
    const kErr = "m:" + pad13(now - 30000) + ":" + "cccccccccccccccc";
    await putMsg(kErr, { k: kErr, conv: wA, dir: "out", status: "failed", e: "", ts: now - 30000, src: "texts", error: "Your inbox key expired. Link again at relay-help.example", code: "Link again at relay-help.example" });
    // 5. a message that doesn't open as Andy's (Megan's), carrying Andy's picture
    const kMix = "m:" + pad13(now - 20000) + ":" + "dddddddddddddddd";
    await putMsg(kMix, Object.assign({}, meg, { k: kMix, conv: wA, ts: now - 20000, media: { path: picPath, mime: "image/png" } }));
    // 6. Andy's picture message with its type and size changed in the clear
    await putMsg(kPic, Object.assign({}, pic, { media: Object.assign({}, pic.media, { mime: "video/mp4", bytes: 999999999 }) }));
    // 7. Andy's second picture message pointed at his first picture (a file of the same conversation)
    await putMsg(kPic2, Object.assign({}, pic2, { media: { path: picPath } }));
    // 8. a text message given a picture it never had
    await putMsg(kHi, Object.assign({}, hi, { media: { path: picPath } }));
    // 9. Megan's message filed under another time
    await putMsg(kMeg, Object.assign({}, meg, { ts: now - 1000 }));
    // controls: a real merge record {X -> Andy} relocated into Megan's summary with X's text filed under Megan; a
    // message under Andy whose file path names another inbox
    const wX = await TR2.convId(keys, "c-x");
    const mrX = await TR2.sealJson(keys.enc, { from: wX, into: wA }, TR2.AAD.merge(wA));
    const megSum = (await call("/_debug/dump"))["v2-convs"][tid + "/" + wB];
    await call("/_debug/put", { body: { collection: "v2-convs", key: tid + "/" + wB, value: Object.assign({}, megSum, { merged: [mrX] }) } });
    const kX = "m:" + pad13(now - 10000) + ":" + "eeeeeeeeeeeeeeee";
    await putMsg(kX, { k: kX, conv: wB, dir: "in", status: "received", ts: now - 10000, src: "notif",
      e: await TR2.sealJson(keys.enc, { text: "Words sealed for X", sender: "X", name: "X", group: false, ts: now - 10000 }, TR2.AAD.message(wX, "in")) });
    const tT = now - 5000, fT = "ffffffffffffffff";
    const kT = "m:" + pad13(tT) + ":" + fT;
    await putMsg(kT, { k: kT, conv: wA, dir: "in", status: "received", ts: tT, src: "notif",
      e: await TR2.sealJson(keys.enc, { text: "", sender: "Andy", name: "Andy", group: false, ts: tT, fp: fT, media: pngMedia }, TR2.AAD.message(wA, "in")),
      media: { path: "/media/v2/" + "f".repeat(32) + "/" + wA + "/" + pad13(tT) + "-" + fT + "-o.enc" } });

    // ------------------------------------------------ the page, linked as the Texts device
    browser = await chromium.launch();
    const ctx = await browser.newContext();
    const page = await ctx.newPage();
    const errors = [];
    page.on("pageerror", e => errors.push(String(e)));
    page.on("console", m => { if (m.type() === "error" && /Content Security Policy/.test(m.text())) errors.push(m.text()); });
    await page.goto(ORIGIN + "/");
    await page.evaluate((l) => localStorage.setItem("relay.link2", l), JSON.stringify({ tid, token: texts.token, key: TR2.b64u(K) }));
    await page.reload();
    await page.waitForSelector('#view-inbox:not([hidden]) #conv-list li:has-text("Andy")', { timeout: 15000 });
    await page.click('#conv-list li:has-text("Andy")');
    await page.waitForSelector("#msg-list li.msg", { timeout: 10000 });
    await page.waitForSelector("#msg-list img.pic[src^='blob:']", { timeout: 10000 });
    await sleep(500);
    const row = (k) => page.evaluate((k) => {
      const li = document.querySelector('#msg-list li[data-k="' + k + '"]');
      return li ? { cls: li.className, text: li.textContent, img: !!li.querySelector("img.pic"), tile: (li.querySelector(".attach-tile") || {}).textContent || "",
        stamp: (li.querySelector(".stamp") || {}).textContent || "" } : null;
    }, k);
    const clock = (ts) => page.evaluate((ts) => new Date(ts).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" }), ts);

    await check("1. a text someone sent can't be shown as one the user sent", async () => {
      const x = await row(kOwe);
      assert.ok(x, "row missing");
      assert.ok(!/\bout\b/.test(x.cls) || x.text.includes("couldn't be checked"), "shown as the user's own: " + JSON.stringify(x));
      assert.ok(!x.text.includes("I owe you"), JSON.stringify(x));
    });
    await check("2. the same sealed message filed again later shows once, at the time it was sealed", async () => {
      const list = await page.textContent("#msg-list");
      assert.strictEqual(list.split("Are we still on for Friday?").length - 1, 1, "shown " + (list.split("Are we still on for Friday?").length - 1) + " times");
      const shown = (await row(kHi)) || (await row(kReplay));
      assert.ok(shown && shown.stamp.startsWith(await clock(t0 + 20000)), "stamped " + JSON.stringify(shown && shown.stamp));
    });
    await check("2b. a copy sealed again and filed under a new key shows once too", async () => {
      const list = await page.textContent("#msg-list");
      assert.strictEqual(list.split("Are we still on for Friday?").length - 1, 1, "shown " + (list.split("Are we still on for Friday?").length - 1) + " times");
    });
    await check("3. a message with nothing sealed is unchecked, without the picture the server put on it", async () => {
      const x = await row(kFab);
      assert.ok(x && x.text.includes("couldn't be checked") && !x.img && !x.tile, JSON.stringify(x));
    });
    await check("4. the server's own words never show inside a conversation", async () => {
      const x = await row(kErr);
      assert.ok(x && !x.text.includes("relay-help.example"), JSON.stringify(x && x.text));
      assert.ok(!(await page.textContent("#msg-list")).includes("relay-help"));
    });
    await check("5. an unchecked message shows no attachment", async () => {
      const x = await row(kMix);
      assert.ok(x && x.text.includes("couldn't be checked") && !x.img && !x.tile, JSON.stringify(x));
    });
    await check("6. the sealed attachment type and size win over the server's", async () => {
      const x = await row(kPic);
      assert.ok(x && x.img && !/Video/.test(x.tile + x.text), JSON.stringify(x));
    });
    await check("7. a picture can't be moved onto another message of the same conversation", async () => {
      const x = await row(kPic2);
      assert.ok(x && !x.img && /couldn't be checked, so it isn't shown/.test(x.text), JSON.stringify(x));
    });
    await check("8. a text message given a picture it never had shows no picture", async () => {
      const x = await row(kHi) || await row(kReplay);
      assert.ok(x && !x.img && !x.tile && !/Picture/.test(x.text), JSON.stringify(x));
    });
    await check("control: a file path naming another inbox is blocked", async () => {
      const x = await row(kT);
      assert.ok(x && !x.img && /couldn't be checked, so it isn't shown/.test(x.text), JSON.stringify(x));
    });
    await page.click("#back-btn");
    await page.click('#conv-list li:has-text("Megan")');
    await page.waitForSelector('#msg-list:has-text("See you at 6")', { timeout: 10000 });
    await check("9. a message filed under another time shows the time it was sealed with", async () => {
      const x = await row(kMeg);
      assert.ok(x && x.stamp.startsWith(await clock(t0 + 60000)), "stamped " + JSON.stringify(x && x.stamp));
    });
    await check("9b. a seal written another way never opens: no second copy of the words", async () => {
      const x = await row(kSpelled);
      const list = await page.textContent("#msg-list");
      assert.ok(x && x.text.includes("couldn't be checked"), JSON.stringify(x));
      assert.strictEqual(list.split("See you at 6").length - 1, 1, "shown " + (list.split("See you at 6").length - 1) + " times");
    });
    await check("control: a merge record moved into another conversation's summary vouches for nothing", async () => {
      const x = await row(kX);
      assert.ok(x && x.text.includes("couldn't be checked") && !x.text.includes("Words sealed for X"), JSON.stringify(x));
    });
    await page.click("#back-btn");

    // ------------------------------------------------ 10. a late thread answer never lands under another conversation
    let delayed = false;
    await page.route(u => u.pathname === "/v2/thread" && u.searchParams.get("conv") === wA && !u.searchParams.get("cursor"), async route => {
      if (delayed) return route.continue();
      delayed = true;
      await sleep(2500);
      route.continue();
    });
    await page.click('#conv-list li:has-text("Andy")');
    await sleep(300);
    await page.click("#back-btn");
    await page.click('#conv-list li:has-text("Megan")');
    await page.waitForSelector('#msg-list:has-text("See you at 6")', { timeout: 10000 });
    await sleep(3000);   // Andy's answer arrives
    const header = await page.textContent("#thread-name");
    const shown = await page.textContent("#msg-list");
    await page.fill("#msg-input", "Sending to whoever this is");
    await page.click("#send-btn");
    let ob = { items: [] };
    for (let i = 0; i < 50 && !ob.items.length; i++) { ob = await call("/v2/relay/outbox", { headers: H }); await sleep(100); }
    await check("10. a thread answer that arrives late never shows under another conversation", async () => {
      assert.strictEqual(header, "Megan");
      assert.ok(shown.includes("See you at 6") && !shown.includes("No problem"), "the list shows Andy's thread under Megan's name");
      assert.ok(ob.items[0] && ob.items[0].conv === wB, "the reply went to " + JSON.stringify(ob.items[0] && ob.items[0].conv));
    });
    await page.unroute(() => true).catch(() => {});
    await check("no Content Security Policy refusals or page errors", async () => assert.deepStrictEqual(errors, []));

    // ------------------------------------------------ 11. a first-version inbox's media address is never used unchecked
    const R1 = { "x-hedy-app-key": "hak_relay.test" };
    const up = await call("/api/relay/media", { headers: Object.assign({ "x-relay-conv": "c3cccccccccccccccccccccccccccccc", "x-relay-fp": fp(9), "x-relay-ts": String(t0), "x-relay-kind": "original", "content-type": "image/png" }, R1), body: png });
    assert.ok(up.path, JSON.stringify(up));
    r = await call("/api/relay/ingest", { headers: R1, body: { items: [{ conv: "c3cccccccccccccccccccccccccccccc", name: "Megan v1", sender: "Megan v1", text: "", ts: t0, fp: fp(9), media: { mime: "image/png", path: up.path, w: 1, h: 1, bytes: png.length } }] } });
    assert.strictEqual(r.stored, 1, JSON.stringify(r));
    const ctx1 = await browser.newContext();
    const v1 = await ctx1.newPage();
    await v1.goto(ORIGIN + "/");
    await v1.evaluate(() => localStorage.setItem("relay.key", "hak_inbox.test"));
    await v1.route(u => u.pathname === "/api/media", route => route.fulfill({ status: 200, contentType: "application/json",
      body: JSON.stringify({ url: "javascript:void(localStorage.setItem('pwned',location.origin+'|'+(typeof TR2)))", expiresIn: 3600 }) }));
    await v1.reload();
    await v1.waitForSelector('#conv-list li:has-text("Megan v1")', { timeout: 15000 });
    await v1.click('#conv-list li:has-text("Megan v1")');
    await v1.waitForSelector('#msg-list:has-text("could not load")', { timeout: 10000 });
    const hrefs = await v1.$$eval("a", as => as.map(a => a.getAttribute("href") || ""));
    const pwned = await v1.evaluate(() => localStorage.getItem("pwned"));
    await check("11. a server-supplied file address that isn't https is never fetched, shown or linked", async () => {
      assert.ok(!hrefs.some(h => /^javascript:/i.test(h)), JSON.stringify(hrefs));
      assert.strictEqual(pwned, null);
    });
    await ctx1.close();
  } finally {
    if (browser) await browser.close();
    server.kill();
  }
  const failed = results.filter(x => x[1] === "FAIL");
  console.log("\n" + (results.length - failed.length) + " of " + results.length + " dishonest-server checks pass");
  process.exit(failed.length ? 1 : 0);
})().catch(e => { console.error(e); process.exit(2); });
