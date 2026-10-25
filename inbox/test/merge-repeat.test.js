// A merge makes inbox devices reload once for what it changed, and not again for a repeat that changes nothing: the
// relay phone seals its merge record afresh on every call and runs every merge once more later, a reply racing the
// merge can leave the last call with nothing to do, and a call can stop after the work but before devices were told.
// (Started as an independent reviewer's checks, kept as regressions.)
//   node test/merge-repeat.test.js
const assert = require("assert");
const path = require("path");
const TR2 = require("../web/tr2.js");
const { caller2, col, rings, base } = require("./harness");
const v2 = caller2(path.join(__dirname, "../functions/inbox2.js"));
const H = (t) => ({ "x-relay-token": t });
const fp = (n) => n.toString(16).padStart(16, "0");

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
}

const reloads = (tid) => rings.filter(x => x[0] === "tr2/" + tid + "/i" && x[1] === "reload").length;
const sealMerge = (ib, from, into) => TR2.sealJson(ib.keys.enc, { from, into }, TR2.AAD.merge(into));   // afresh per call, as Hedy2.merge does

(async () => {
  let failed = 0;
  const ok = (cond, name, detail) => { console.log((cond ? "ok      " : "FAIL    ") + name + (cond ? "" : "\n        " + detail)); if (!cond) failed++; };

  // ------------------------------------------------ 1. the repeat, sealed afresh, changes nothing and rings nothing
  {
    const ib = await newInbox();
    const from = await TR2.convId(ib.keys, "c-from"), into = await TR2.convId(ib.keys, "c-into");
    const t = Date.now() - 3600000;
    await ingestOne(ib, into, 1, t, "into");
    await ingestOne(ib, from, 2, t + 1000, "from");
    const merge = async () => v2("POST", "/v2/relay/merge", { from, into, mr: await sealMerge(ib, from, into) }, null, H(ib.relay.token));
    const first = await merge();
    const before = reloads(ib.tid);
    const again = await merge();
    const merged = (col("v2-convs").get(ib.tid + "/" + into) || {}).merged || [];
    ok(first.epoch > 0 && before === 1, "1a. the merge itself makes devices reload once", JSON.stringify(first) + " rings " + before);
    ok(again.epoch === first.epoch && reloads(ib.tid) === before, "1b. a repeat sealed afresh moves nothing and rings nothing",
      JSON.stringify(first) + " -> " + JSON.stringify(again));
    ok(merged.length === 1, "1c. one merge keeps one record", "records kept: " + merged.length);
  }

  // ------------------------------------------------ 2. a reply racing the merge leaves the last call nothing to do
  {
    const ib = await newInbox();
    const from = await TR2.convId(ib.keys, "c-from"), into = await TR2.convId(ib.keys, "c-into");
    const t = Date.now() - 3600000;
    await ingestOne(ib, into, 1, t, "into");
    await ingestOne(ib, from, 2, t + 1000, "from");
    const epoch0 = (await v2("GET", "/v2/inbox", null, null, H(ib.texts.token))).epoch;
    const mr = await sealMerge(ib, from, into);
    // A send that worked out where `from` lives before the tombstone files its reply there just as `from` goes.
    const k = "m:" + String(Date.now()).padStart(13, "0") + ":olatereply";
    const realDelete = base.data.delete;
    base.data.delete = async (c, key) => {
      await realDelete(c, key);
      if (c === "v2-convs" && key === ib.tid + "/" + from) {
        await base.data.put("v2-messages", ib.tid + "/" + k, { k, conv: from, cv: ib.tid + "/" + from, qs: ib.tid + "/queued", dir: "out",
          status: "queued", e: "e1." + "A".repeat(60), o: "e1." + "B".repeat(60), ts: Date.now(), createdAt: Date.now(), src: "texts" });
      }
    };
    let first;
    try { first = await v2("POST", "/v2/relay/merge", { from, into, mr }, null, H(ib.relay.token)); } finally { base.data.delete = realDelete; }
    // The send checks again and files its own reply under `into`.
    const cur = col("v2-messages").get(ib.tid + "/" + k);
    await base.data.put("v2-messages", ib.tid + "/" + k, Object.assign({}, cur, { conv: into, cv: ib.tid + "/" + into }));
    // The relay phone calls again (more: true), and once more later (the same record here, so only the race is tested).
    const second = await v2("POST", "/v2/relay/merge", { from, into, mr }, null, H(ib.relay.token));
    const third = await v2("POST", "/v2/relay/merge", { from, into, mr }, null, H(ib.relay.token));
    const epoch = (await v2("GET", "/v2/inbox", null, null, H(ib.texts.token))).epoch;
    ok(first.more === true && epoch !== epoch0 && reloads(ib.tid) === 1, "2a. devices reload for the merge, even when its last call finds nothing",
      JSON.stringify({ first, second, third, epoch0, epoch, rings: reloads(ib.tid) }));
    ok(!col("v2-convs").get(ib.tid + "/" + from), "2b. and the merged conversation is gone", "");
  }

  // ------------------------------------------------ 3. a call that did the work but stopped before devices reloaded
  {
    const ib = await newInbox();
    const from = await TR2.convId(ib.keys, "c-from"), into = await TR2.convId(ib.keys, "c-into");
    const t = Date.now() - 3600000;
    await ingestOne(ib, into, 1, t, "into");
    await ingestOne(ib, from, 2, t + 1000, "from");
    const realPut = base.data.put;
    base.data.put = async function (c, key, v, o) {
      if (c === "v2-state" && key === ib.tid + "/epoch") { base.data.put = realPut; throw new Error("stopped here"); }
      return realPut.apply(this, arguments);
    };
    let first;
    try { first = await v2("POST", "/v2/relay/merge", { from, into, mr: await sealMerge(ib, from, into) }, null, H(ib.relay.token)); } finally { base.data.put = realPut; }
    const before = reloads(ib.tid);
    const again = await v2("POST", "/v2/relay/merge", { from, into, mr: await sealMerge(ib, from, into) }, null, H(ib.relay.token));
    const third = await v2("POST", "/v2/relay/merge", { from, into, mr: await sealMerge(ib, from, into) }, null, H(ib.relay.token));
    ok(first._status === 500 && before === 0 && reloads(ib.tid) === 1 && again.epoch === third.epoch,
      "3. the next call has devices reload for it, once", JSON.stringify({ first, again, third, rings: reloads(ib.tid) }));
  }

  console.log(failed ? failed + " merge checks failed" : "merge-repeat: all checks passed");
  process.exit(failed ? 1 : 0);
})().catch(e => { console.error(e); process.exit(2); });
