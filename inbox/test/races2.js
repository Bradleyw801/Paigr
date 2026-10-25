// inbox2.js races and limits on the in-memory harness: merges racing replies and texts, merge records past any cap,
// failure codes instead of server text, the move's queued replies, and the epoch under two bumps at once.
// (Started as an independent reviewer's proof tests, kept as regression tests.)
//   node test/races2.js
const assert = require("assert");
const path = require("path");
const TR2 = require("../web/tr2.js");
const { caller2, col, base } = require("./harness");
const v2 = caller2(path.join(__dirname, "../functions/inbox2.js"));
const H = (t) => ({ "x-relay-token": t });
const pad13 = (n) => String(n).padStart(13, "0");
const fp = (n) => n.toString(16).padStart(16, "0");

const results = [];
async function check(name, fn) {
  try { await fn(); results.push([name, "ok"]); console.log("ok      " + name); }
  catch (e) { results.push([name, "FAIL"]); console.log("FAIL    " + name + "\n        " + String(e && e.message || e).split("\n")[0]); }
}

async function newInbox() {
  const relay = { token: TR2.newToken() }, texts = { token: TR2.newToken() };
  const s = await TR2.linkSecrets(TR2.newCode());
  assert.strictEqual((await v2("POST", "/v2/link/ask", { lookup: s.lookup, token: relay.token, role: "relay" }))._status, 200);
  const K = TR2.newKey(), tid = TR2.newTid();
  const r = await v2("POST", "/v2/link/adopt", { lookup: s.lookup, answer: await TR2.sealJson(s.boxKey, { v: 2, tid, key: TR2.b64u(K) }, "tr2:box"), create: { tid, token: texts.token } });
  assert.strictEqual(r._status, 200, JSON.stringify(r));
  return { tid, relay, texts, keys: await TR2.inboxKeys(K) };
}

async function ingestOne(ib, conv, n, ts, text) {
  const k = ib.keys;
  const item = { conv, fp: await TR2.fpId(k, fp(n)), ts, dir: "in", src: "notif",
    e: await TR2.sealJson(k.enc, { text, sender: "x", name: "x", group: false, ts, fp: await TR2.fpId(k, fp(n)) }, TR2.AAD.message(conv, "in")),
    ne: await TR2.sealJson(k.enc, { name: "x", group: false }, TR2.AAD.name(conv)), mt: await TR2.matchToken(k, "in", text, "") };
  const r = await v2("POST", "/v2/relay/ingest", { items: [item] }, null, H(ib.relay.token));
  assert.strictEqual(r._status, 200, JSON.stringify(r));
  return r;
}

async function sendReply(ib, conv, text) {
  const k = ib.keys;
  const ts = Date.now();
  return v2("POST", "/v2/send", { conv, e: await TR2.sealJson(k.enc, { text, ts }, TR2.AAD.message(conv, "out")),
    o: await TR2.sealJson(k.enc, { text, n: TR2.b64u(TR2.random(16)), ts }, TR2.AAD.out(conv)) }, null, H(ib.texts.token));
}

/** The page's aliasesOf, written out again: the conversations whose sealed records a conversation accepts. */
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

/** Runs `during` once, right after the merge's last "anything left in from?" query has answered "no". */
function afterRestQuery(tid, from, during) {
  const real = base.data.query;
  base.data.query = async function (c, index, opts) {
    const out = await real.call(this, c, index, opts);
    if (c === "v2-messages" && index === "by-conv" && opts && opts.eq === tid + "/" + from && opts.limit === 1 && !out.items.length) {
      base.data.query = real;
      await during();
    }
    return out;
  };
  return () => { base.data.query = real; };
}

(async () => {
  // ------------------------------------------------ 1. a reply sent while its conversation is being merged away
  {
    const ib = await newInbox();
    const from = await TR2.convId(ib.keys, "c-from"), into = await TR2.convId(ib.keys, "c-into");
    const t = Date.now() - 3600000;
    await ingestOne(ib, into, 1, t, "into's text");
    await ingestOne(ib, from, 2, t + 1000, "from's text");
    let sent = null;
    const undo = afterRestQuery(ib.tid, from, async () => { sent = await sendReply(ib, from, "Typed just as the merge finished"); });
    const mr = await TR2.sealJson(ib.keys.enc, { from, into }, TR2.AAD.merge(into));
    const m = await v2("POST", "/v2/relay/merge", { from, into, mr }, null, H(ib.relay.token));
    undo();
    await check("1. merge vs send: a reply sent as the merge finishes ends up in the merged conversation", async () => {
      assert.ok(sent && sent._status === 200, "send " + JSON.stringify(sent));
      assert.strictEqual(m.more, false, JSON.stringify(m));
      const msg = col("v2-messages").get(ib.tid + "/" + sent.id);
      const intoThread = await v2("GET", "/v2/thread", null, { conv: into }, H(ib.texts.token));
      const inboxConvs = (await v2("GET", "/v2/inbox", null, null, H(ib.texts.token))).items.map(c => c.conv);
      const ob = await v2("GET", "/v2/relay/outbox", null, null, H(ib.relay.token));
      assert.ok(intoThread.items.some(x => x.k === sent.id),
        "reply left in conv=" + (msg.conv === from ? "from" : msg.conv) + " (status " + msg.status + ", still in the outbox: " + ob.items.some(i => i.id === sent.id)
        + "); from's summary exists: " + inboxConvs.includes(from) + "; into's thread doesn't have it");
    });
  }

  // ------------------------------------------------ 2. a text ingested as the merge finishes
  {
    const ib = await newInbox();
    const from = await TR2.convId(ib.keys, "c-from"), into = await TR2.convId(ib.keys, "c-into");
    const t = Date.now() - 3600000;
    await ingestOne(ib, into, 1, t, "into's text");
    await ingestOne(ib, from, 2, t + 1000, "from's text");
    const undo = afterRestQuery(ib.tid, from, async () => { await ingestOne(ib, from, 3, Date.now(), "in flight from the relay phone"); });
    const mr = await TR2.sealJson(ib.keys.enc, { from, into }, TR2.AAD.merge(into));
    await v2("POST", "/v2/relay/merge", { from, into, mr }, null, H(ib.relay.token));
    undo();
    await check("2. merge vs ingest: a text ingested as the merge finishes isn't left behind or its unread count lost", async () => {
      const k3 = "m:" + pad13(0).slice(0, 0);
      const left = [...col("v2-messages").values()].filter(v => v.cv === ib.tid + "/" + from);
      const intoSum = col("v2-convs").get(ib.tid + "/" + into);
      const fromSum = col("v2-convs").get(ib.tid + "/" + from);
      assert.ok(!left.length, left.length + " message(s) still filed under from; from's summary " + (fromSum ? "exists (unread " + fromSum.unread + ")" : "deleted") + "; into unread " + (intoSum && intoSum.unread));
    });
  }

  // ------------------------------------------------ 3. many merges into one conversation: the cap of 32 records
  {
    const ib = await newInbox();
    const into = await TR2.convId(ib.keys, "c-hub");
    const t = Date.now() - 86400000;
    await ingestOne(ib, into, 1, t, "hub");
    const froms = [];
    for (let i = 0; i < 33; i++) {
      const from = await TR2.convId(ib.keys, "c-spoke-" + i);
      froms.push(from);
      await ingestOne(ib, from, 100 + i, t + (i + 1) * 3600000, "spoke " + i);
      const mr = await TR2.sealJson(ib.keys.enc, { from, into }, TR2.AAD.merge(into));
      const m = await v2("POST", "/v2/relay/merge", { from, into, mr }, null, H(ib.relay.token));
      assert.strictEqual(m._status, 200, JSON.stringify(m));
    }
    await check("3. every conversation merged in stays vouched for (merge records capped at 32)", async () => {
      const sum = (await v2("GET", "/v2/thread", null, { conv: into }, H(ib.texts.token))).conversation;
      const ok = await aliases(ib.keys, into, sum.merged);
      const lost = froms.filter(f => !ok.includes(f));
      assert.strictEqual(lost.length, 0, lost.length + " of 33 merged conversations no longer vouched for (records kept: " + sum.merged.length + "); their messages show as \"couldn't be checked\"");
    });
  }

  // ------------------------------------------------ 4. a merge whose `from` already carries 32 records drops the new one
  {
    const ib = await newInbox();
    const mid = await TR2.convId(ib.keys, "c-mid"), into = await TR2.convId(ib.keys, "c-top");
    const t = Date.now() - 86400000;
    await ingestOne(ib, mid, 1, t, "mid");
    await ingestOne(ib, into, 2, t + 1000, "top");
    for (let i = 0; i < 32; i++) {
      const from = await TR2.convId(ib.keys, "c-leaf-" + i);
      await ingestOne(ib, from, 200 + i, t + (i + 2) * 60000, "leaf " + i);
      await v2("POST", "/v2/relay/merge", { from, into: mid, mr: await TR2.sealJson(ib.keys.enc, { from, into: mid }, TR2.AAD.merge(mid)) }, null, H(ib.relay.token));
    }
    const mr = await TR2.sealJson(ib.keys.enc, { from: mid, into }, TR2.AAD.merge(into));
    await v2("POST", "/v2/relay/merge", { from: mid, into, mr }, null, H(ib.relay.token));
    await check("4. merging a conversation that carries 32 records keeps the record for that merge", async () => {
      const sum = col("v2-convs").get(ib.tid + "/" + into);
      const ok = await aliases(ib.keys, into, sum.merged);
      assert.ok(sum.merged.includes(mr) && ok.includes(mid), "the record for mid -> top was dropped: top accepts " + (ok.length - 1) + " other conversations, mid's own messages included: " + ok.includes(mid));
    });
  }


  // ------------------------------------------------ 5. a reply that read "not merged" before the merge began, filed after it ended
  {
    const ib = await newInbox();
    const from = await TR2.convId(ib.keys, "c-from"), into = await TR2.convId(ib.keys, "c-into");
    const t = Date.now() - 3600000;
    await ingestOne(ib, into, 1, t, "into's text");
    await ingestOne(ib, from, 2, t + 1000, "from's text");
    let reached, release;
    const atPut = new Promise(r => { reached = r; });
    const gate = new Promise(r => { release = r; });
    const realPut = base.data.put;
    let held = false;
    base.data.put = async function (c, k, v, o) {
      if (!held && c === "v2-messages" && v && v.src === "texts" && v.conv === from) { held = true; reached(); await gate; }
      return realPut.call(this, c, k, v, o);
    };
    const sending = sendReply(ib, from, "Typed while the merge ran");
    await atPut;
    const mr = await TR2.sealJson(ib.keys.enc, { from, into }, TR2.AAD.merge(into));
    const m = await v2("POST", "/v2/relay/merge", { from, into, mr }, null, H(ib.relay.token));
    release();
    const sent = await sending;
    base.data.put = realPut;
    await check("5. a reply filed under a conversation merged away while it was on its way is refiled where that conversation went", async () => {
      assert.strictEqual(m._status, 200, JSON.stringify(m));
      assert.strictEqual(sent._status, 200, JSON.stringify(sent));
      const msg = col("v2-messages").get(ib.tid + "/" + sent.id);
      assert.strictEqual(msg.conv, into, "left under " + (msg.conv === from ? "from" : msg.conv));
      assert.strictEqual(msg.cv, ib.tid + "/" + into);
      assert.ok(!col("v2-convs").get(ib.tid + "/" + from), "from's summary came back");
      assert.strictEqual(col("v2-convs").get(ib.tid + "/" + into).lastE, msg.e);
      const ob = await v2("GET", "/v2/relay/outbox", null, null, H(ib.relay.token));
      assert.ok(ob.items.some(i => i.id === sent.id && i.conv === into), "still queued for the relay phone, under into");
    });
  }

  // ------------------------------------------------ 6. after a merge, a reply or a text naming the old conversation lands in the new one
  {
    const ib = await newInbox();
    const from = await TR2.convId(ib.keys, "c-from"), into = await TR2.convId(ib.keys, "c-into");
    const t = Date.now() - 3600000;
    await ingestOne(ib, into, 1, t, "into's text");
    await ingestOne(ib, from, 2, t + 1000, "from's text");
    const mr = await TR2.sealJson(ib.keys.enc, { from, into }, TR2.AAD.merge(into));
    assert.strictEqual((await v2("POST", "/v2/relay/merge", { from, into, mr }, null, H(ib.relay.token))).more, false);
    const sent = await sendReply(ib, from, "Typed in a window still showing the old conversation");
    const wfp = await TR2.fpId(ib.keys, fp(9));
    const ts = Date.now();
    const path = "/media/v2/" + ib.tid + "/" + from + "/" + pad13(ts) + "-" + wfp + "-o.enc";
    const late = { conv: from, fp: wfp, ts, dir: "in", src: "notif",
      e: await TR2.sealJson(ib.keys.enc, { text: "", sender: "x", name: "x", group: false, ts, fp: wfp, media: { mime: "image/png" } }, TR2.AAD.message(from, "in")),
      media: { path } };
    const r = await v2("POST", "/v2/relay/ingest", { items: [late] }, null, H(ib.relay.token));
    await check("6. a reply or a text naming a conversation merged away goes where it went, with its picture", async () => {
      assert.strictEqual(sent._status, 200, JSON.stringify(sent));
      assert.strictEqual(col("v2-messages").get(ib.tid + "/" + sent.id).conv, into);
      assert.strictEqual(r._status, 200, JSON.stringify(r));
      const stored = col("v2-messages").get(ib.tid + "/m:" + pad13(ts) + ":" + wfp);
      assert.strictEqual(stored.conv, into);
      assert.strictEqual(stored.media && stored.media.path, path, "its picture, stored under the conversation it was sealed for, comes along");
      assert.ok(!col("v2-convs").get(ib.tid + "/" + from), "from's summary isn't made again");
    });
  }

  // ------------------------------------------------ 7. why a reply wasn't sent: a code, never the server's words
  {
    const ib = await newInbox();
    const conv = await TR2.convId(ib.keys, "c-one");
    await ingestOne(ib, conv, 1, Date.now() - 60000, "hi");
    const a = await sendReply(ib, conv, "one"), b = await sendReply(ib, conv, "two");
    await v2("POST", "/v2/relay/outbox/result", { id: a.id, status: "failed", code: "no_number", error: "Your inbox key expired. Link again at relay-help.example" }, null, H(ib.relay.token));
    await v2("POST", "/v2/relay/outbox/result", { id: b.id, status: "failed", code: "Visit relay-help.example", error: "x" }, null, H(ib.relay.token));
    await check("7. a failed reply carries the relay phone's code and no text from the server", async () => {
      const ma = col("v2-messages").get(ib.tid + "/" + a.id), mb = col("v2-messages").get(ib.tid + "/" + b.id);
      assert.strictEqual(ma.code, "no_number");
      assert.strictEqual(ma.error, undefined);
      assert.strictEqual(mb.code, undefined, "only a plain code is kept");
      const th = await v2("GET", "/v2/thread", null, { conv }, H(ib.texts.token));
      assert.ok(!JSON.stringify(th).includes("relay-help"), "nothing of it reaches a device");
      const feed = [...col("v2-feed").values()].filter(v => v.k === a.id);
      assert.ok(feed.length && feed.every(v => v.error === undefined) && feed.some(v => v.status === "failed" && v.code === "no_number"));
    });
  }

  // ------------------------------------------------ 8. a reply still queued in the first version comes over failed, with a code
  {
    const ib = await newInbox();
    const conv = await TR2.convId(ib.keys, "c-v1");
    const ts = Date.now() - 86400000;
    const k = "m:" + pad13(ts) + ":oabcd1234";
    const e = await TR2.sealJson(ib.keys.enc, { text: "never went", ts, moved: true }, TR2.AAD.message(conv, "out"));
    const r = await v2("POST", "/v2/import", { messages: [{ k, conv, dir: "out", status: "queued", ts, src: "texts", e, error: "Visit relay-help.example" },
      { k: "m:" + pad13(ts - 5) + ":oabcd1235", conv, dir: "out", status: "failed", ts: ts - 5, src: "texts", e, error: "Visit relay-help.example" }], convs: [] }, null, H(ib.texts.token));
    await check("8. the move: a queued reply is failed with a code; no failure text comes along", async () => {
      assert.strictEqual(r._status, 200, JSON.stringify(r));
      const v = col("v2-messages").get(ib.tid + "/" + k);
      assert.strictEqual(v.status, "failed");
      assert.strictEqual(v.code, "before_move");
      assert.ok(!JSON.stringify([...col("v2-messages").values()].filter(x => x.conv === conv)).includes("relay-help"));
    });
  }

  // ------------------------------------------------ 9. two epoch bumps at once each get their own number
  {
    const ib = await newInbox();
    const [x, y] = await Promise.all([v2("POST", "/v2/import/done", {}, null, H(ib.texts.token)), v2("POST", "/v2/import/done", {}, null, H(ib.texts.token))]);
    await check("9. two bumps of the epoch at once give two different epochs", async () => {
      assert.strictEqual(x._status, 200); assert.strictEqual(y._status, 200);
      assert.notStrictEqual(x.epoch, y.epoch);
      assert.strictEqual(col("v2-state").get(ib.tid + "/epoch").n, Math.max(x.epoch, y.epoch));
    });
  }

  const failed = results.filter(x => x[1] === "FAIL");
  console.log("\n" + (results.length - failed.length) + " of " + results.length + " race and limit checks pass");
  process.exit(failed.length ? 1 : 0);
})().catch(e => { console.error(e); process.exit(2); });
