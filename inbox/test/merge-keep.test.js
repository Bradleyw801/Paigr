// A merge's own record is never the one dropped: when `from` already vouches for 200 conversations merged into it, the
// record of `from` -> `into` still survives the call that folds `from`'s records in, even when that call is a repeat
// that brought no record of its own (the tombstone keeps the one an earlier call kept). (Started as an independent
// reviewer's check, kept as a regression.)
//   node test/merge-keep.test.js
const assert = require("assert");
const path = require("path");
const TR2 = require("../web/tr2.js");
const { caller2, col, hooks, base } = require("./harness");
const v2 = caller2(path.join(__dirname, "../functions/inbox2.js"));
const H = (t) => ({ "x-relay-token": t });
const fp = (n) => n.toString(16).padStart(16, "0");

(async () => {
  const relay = TR2.newToken(), texts = TR2.newToken();
  const s = await TR2.linkSecrets(TR2.newCode());
  await v2("POST", "/v2/link/ask", { lookup: s.lookup, token: relay, role: "relay" });
  const K = TR2.newKey(), tid = TR2.newTid();
  await v2("POST", "/v2/link/adopt", { lookup: s.lookup, answer: await TR2.sealJson(s.boxKey, { v: 2, tid, key: TR2.b64u(K) }, "tr2:box"), create: { tid, token: texts } });
  const keys = await TR2.inboxKeys(K);
  const from = await TR2.convId(keys, "c-from"), into = await TR2.convId(keys, "c-into");
  const t = Date.now() - 3600000;
  for (const [conv, n, text] of [[into, 1, "into"], [from, 2, "from"]]) {
    const f = await TR2.fpId(keys, fp(n));
    const r = await v2("POST", "/v2/relay/ingest", { items: [{ conv, fp: f, ts: t + n, dir: "in", src: "notif",
      e: await TR2.sealJson(keys.enc, { text, sender: "x", name: "x", group: false, ts: t + n, fp: f }, TR2.AAD.message(conv, "in")) }] }, null, H(relay));
    assert.strictEqual(r._status, 200, JSON.stringify(r));
  }
  // `from` already vouches for 200 conversations merged into it earlier
  const fromSum = col("v2-convs").get(tid + "/" + from);
  fromSum.merged = [];
  for (let i = 0; i < 200; i++) fromSum.merged.push(await TR2.sealJson(keys.enc, { from: "leaf" + i, into: from }, TR2.AAD.merge(from)));
  await base.data.put("v2-convs", tid + "/" + from, fromSum);
  const msgKey = [...col("v2-messages").keys()].find(k => col("v2-messages").get(k).conv === from);

  // first call: the one message under `from` changes as it is moved (a result came in), so the call says `more`
  hooks.raceOnce = { c: "v2-messages", k: msgKey, mutate: (v) => { v.read = true; } };
  const mr1 = await TR2.sealJson(keys.enc, { from, into }, TR2.AAD.merge(into));
  const r1 = await v2("POST", "/v2/relay/merge", { from, into, mr: mr1 }, null, H(relay));
  // the relay phone calls again, sealing its record afresh (Hedy2.merge)
  const mr2 = await TR2.sealJson(keys.enc, { from, into }, TR2.AAD.merge(into));
  const r2 = await v2("POST", "/v2/relay/merge", { from, into, mr: mr2 }, null, H(relay));
  const merged = (col("v2-convs").get(tid + "/" + into) || {}).merged || [];
  let vouched = false;
  for (const r of merged) {
    try { const v = await TR2.openJson(keys.enc, r, TR2.AAD.merge(into)); if (v.from === from && v.into === into) vouched = true; } catch { /* not this one */ }
  }
  const okk = r1.more === true && r2.more === false && vouched;
  console.log((okk ? "ok      " : "FAIL    ") + "C. the record for from -> into survives the call that folds from's 200 records in"
    + (okk ? "" : "\n        first " + JSON.stringify(r1) + ", second " + JSON.stringify(r2) + ", records kept " + merged.length + ", from -> into vouched for: " + vouched));
  process.exit(okk ? 0 : 1);
})().catch(e => { console.error(e); process.exit(2); });
