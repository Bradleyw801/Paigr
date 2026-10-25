// Merge tombstones followed by ingest and send through a chain of merges, and a repeated merge that changes nothing
// (in-memory harness). (Started as an independent reviewer's checks, kept as regressions.)
//   node test/tomb.test.js
const assert = require("assert");
const path = require("path");
const TR2 = require("../web/tr2.js");
const { caller2, col, rings } = require("./harness");
const v2 = caller2(path.join(__dirname, "../functions/inbox2.js"));
const H = (t) => ({ "x-relay-token": t });
const fp = (n) => n.toString(16).padStart(16, "0");

const results = [];
async function check(name, fn) {
  try { await fn(); results.push([name, "ok"]); console.log("ok      " + name); }
  catch (e) { results.push([name, "FAIL"]); console.log("FAIL    " + name + "\n        " + String(e && e.message || e).split("\n")[0]); }
}

async function newInbox() {
  const relay = { token: TR2.newToken() }, texts = { token: TR2.newToken() };
  const s = await TR2.linkSecrets(TR2.newCode());
  await v2("POST", "/v2/link/ask", { lookup: s.lookup, token: relay.token, role: "relay" });
  const K = TR2.newKey(), tid = TR2.newTid();
  await v2("POST", "/v2/link/adopt", { lookup: s.lookup, answer: await TR2.sealJson(s.boxKey, { v: 2, tid, key: TR2.b64u(K) }, "tr2:box"), create: { tid, token: texts.token } });
  return { tid, relay, texts, keys: await TR2.inboxKeys(K) };
}

async function ingestOne(ib, conv, n, ts, text) {
  const k = ib.keys, f = await TR2.fpId(k, fp(n));
  const item = { conv, fp: f, ts, dir: "in", src: "notif",
    e: await TR2.sealJson(k.enc, { text, sender: "x", name: "x", group: false, ts, fp: f }, TR2.AAD.message(conv, "in")),
    ne: await TR2.sealJson(k.enc, { name: "x", group: false }, TR2.AAD.name(conv)), mt: await TR2.matchToken(k, "in", text, "") };
  const r = await v2("POST", "/v2/relay/ingest", { items: [item] }, null, H(ib.relay.token));
  assert.strictEqual(r._status, 200, JSON.stringify(r));
  return "m:" + String(ts).padStart(13, "0") + ":" + f;
}

/** The page's aliasesOf: the conversations whose sealed records a conversation's summary vouches for. */
async function aliases(keys, conv, merged) {
  const set = new Set([conv]), done = new Set();
  for (let grew = true; grew;) {
    grew = false;
    for (const r of merged || []) {
      if (done.has(r)) continue;
      for (const into of [...set]) {
        let v; try { v = await TR2.openJson(keys.enc, r, TR2.AAD.merge(into)); } catch { continue; }
        if (v.into !== into) continue;
        done.add(r);
        if (!set.has(v.from)) { set.add(v.from); grew = true; }
        break;
      }
    }
  }
  return [...set];
}

(async () => {
  // ------------------------------------------------ 1. a text still naming the first of five chained merges
  {
    const ib = await newInbox();
    const c = [];
    const t = Date.now() - 6 * 3600000;
    for (let i = 0; i <= 5; i++) { c.push(await TR2.convId(ib.keys, "c-chain-" + i)); await ingestOne(ib, c[i], 10 + i, t + i * 60000, "text " + i); }
    for (let i = 0; i < 5; i++) {
      const mr = await TR2.sealJson(ib.keys.enc, { from: c[i], into: c[i + 1] }, TR2.AAD.merge(c[i + 1]));
      const m = await v2("POST", "/v2/relay/merge", { from: c[i], into: c[i + 1], mr }, null, H(ib.relay.token));
      assert.strictEqual(m.more, false, JSON.stringify(m));
    }
    const k = await ingestOne(ib, c[0], 99, Date.now(), "in flight, naming the first conversation");
    const v = col("v2-messages").get(ib.tid + "/" + k);
    const where = c.indexOf(v.conv);
    const sum = col("v2-convs").get(ib.tid + "/" + v.conv);
    const al = await aliases(ib.keys, v.conv, sum && sum.merged);
    await check("1. after 5 chained merges a text naming the first lands in the last conversation, where it opens", async () => {
      assert.strictEqual(where, 5, "filed under c" + where + " (merged away, summary " + (sum ? "re-created" : "missing") + "); opens there: " + al.includes(c[0]));
    });
    const sent = await v2("POST", "/v2/send", { conv: c[0], e: await TR2.sealJson(ib.keys.enc, { text: "r", ts: Date.now() }, TR2.AAD.message(c[0], "out")),
      o: await TR2.sealJson(ib.keys.enc, { text: "r", n: "n", ts: Date.now() }, TR2.AAD.out(c[0])) }, null, H(ib.texts.token));
    await check("1b. a reply typed in the first of five chained merges is filed under the last", async () => {
      assert.strictEqual(sent._status, 200, JSON.stringify(sent));
      const m = col("v2-messages").get(ib.tid + "/" + sent.id);
      assert.strictEqual(c.indexOf(m.conv), 5, "filed under c" + c.indexOf(m.conv));
    });
  }

  // ------------------------------------------------ 2. a merge run again (the relay phone repeats each merge ten minutes later)
  {
    const ib = await newInbox();
    const from = await TR2.convId(ib.keys, "c-from"), into = await TR2.convId(ib.keys, "c-into");
    const t = Date.now() - 3600000;
    await ingestOne(ib, into, 1, t, "into");
    await ingestOne(ib, from, 2, t + 1000, "from");
    const mr = await TR2.sealJson(ib.keys.enc, { from, into }, TR2.AAD.merge(into));
    const first = await v2("POST", "/v2/relay/merge", { from, into, mr }, null, H(ib.relay.token));
    const before = rings.filter(x => x[0] === "tr2/" + ib.tid + "/i" && x[1] === "reload").length;
    const again = await v2("POST", "/v2/relay/merge", { from, into, mr }, null, H(ib.relay.token));
    const after = rings.filter(x => x[0] === "tr2/" + ib.tid + "/i" && x[1] === "reload").length;
    await check("2. a repeated merge that moves nothing doesn't make every device reload", async () => {
      assert.ok(again.epoch === undefined || again.epoch === first.epoch, "epoch " + first.epoch + " -> " + again.epoch + " (moved " + again.moved + ", folded " + again.folded + "); reload rings " + before + " -> " + after);
    });
  }

  const failed = results.filter(x => x[1] === "FAIL");
  console.log("\n" + (results.length - failed.length) + " of " + results.length + " tombstone checks pass");
  process.exit(failed.length ? 1 : 0);
})().catch(e => { console.error(e); process.exit(2); });
