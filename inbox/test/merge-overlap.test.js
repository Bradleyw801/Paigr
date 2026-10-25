// Two calls for the same merge overlap: one reads the marker while it says "not told", another finishes and marks the
// merge told, then the first moves a late reply and stops before telling devices. The next call still has devices
// reload. (Started as an independent reviewer's check, kept as a regression.)
//   node test/merge-overlap.test.js
const assert = require("assert");
const path = require("path");
const TR2 = require("../web/tr2.js");
const { caller2, col, rings, base } = require("./harness");
const v2 = caller2(path.join(__dirname, "../functions/inbox2.js"));
const H = (t) => ({ "x-relay-token": t });
const fp = (n) => n.toString(16).padStart(16, "0");

(async () => {
  const relay = { token: TR2.newToken() }, texts = { token: TR2.newToken() };
  const s = await TR2.linkSecrets(TR2.newCode());
  await v2("POST", "/v2/link/ask", { lookup: s.lookup, token: relay.token, role: "relay" });
  const K = TR2.newKey(), tid = TR2.newTid();
  await v2("POST", "/v2/link/adopt", { lookup: s.lookup, answer: await TR2.sealJson(s.boxKey, { v: 2, tid, key: TR2.b64u(K) }, "tr2:box"), create: { tid, token: texts.token } });
  const keys = await TR2.inboxKeys(K);
  const from = await TR2.convId(keys, "c-from"), into = await TR2.convId(keys, "c-into");
  const t = Date.now() - 3600000;
  for (const [conv, n, ts, text] of [[into, 1, t, "into"], [from, 2, t + 1000, "from"]]) {
    const f = await TR2.fpId(keys, fp(n));
    const item = { conv, fp: f, ts, dir: "in", src: "notif",
      e: await TR2.sealJson(keys.enc, { text, sender: "x", name: "x", group: false, ts, fp: f }, TR2.AAD.message(conv, "in")),
      ne: await TR2.sealJson(keys.enc, { name: "x", group: false }, TR2.AAD.name(conv)), mt: await TR2.matchToken(keys, "in", text, "") };
    assert.strictEqual((await v2("POST", "/v2/relay/ingest", { items: [item] }, null, H(relay.token)))._status, 200);
  }
  const reloads = () => rings.filter(x => x[0] === "tr2/" + tid + "/i" && x[1] === "reload").length;
  const mr = () => TR2.sealJson(keys.enc, { from, into }, TR2.AAD.merge(into));
  const merge = async () => v2("POST", "/v2/relay/merge", { from, into, mr: await mr() }, null, H(relay.token));
  const realPut = base.data.put, realGet = base.data.get;
  const stopAtReload = () => {
    base.data.put = async function (c, key) {
      if (c === "v2-state" && key === tid + "/epoch") { base.data.put = realPut; throw new Error("stopped here"); }
      return realPut.apply(this, arguments);
    };
  };

  // Call 0 does the whole merge and stops before devices reload: the marker says "not told".
  stopAtReload();
  const c0 = await merge();
  base.data.put = realPut;
  const marker0 = col("v2-state").get(tid + "/merged:" + from) || {};

  // Call B starts and reads the marker ("not told"), then waits.
  let reached, release;
  const reachedP = new Promise(r => { reached = r; }), gate = new Promise(r => { release = r; });
  let pause = true;
  base.data.get = async function (c, key) {
    const v = await realGet.apply(this, arguments);
    if (pause && c === "v2-state" && key === tid + "/merged:" + from) { pause = false; reached(); await gate; }
    return v;
  };
  const pB = merge();
  await reachedP;
  base.data.get = realGet;

  // Call A runs start to finish: devices reload, the marker says "told".
  const a = await merge();
  const afterA = reloads();
  const markerA = col("v2-state").get(tid + "/merged:" + from) || {};

  // A reply from a send that started before the marker lands under `from` now.
  const k = "m:" + String(Date.now()).padStart(13, "0") + ":olatereply";
  await realPut("v2-messages", tid + "/" + k, { k, conv: from, cv: tid + "/" + from, qs: tid + "/queued", dir: "out",
    status: "queued", e: "e1." + "A".repeat(60), o: "e1." + "B".repeat(60), ts: Date.now(), createdAt: Date.now(), src: "texts" });

  // B carries on: it moves the late reply, then stops before devices reload.
  stopAtReload();
  release();
  const b = await pB;
  base.data.put = realPut;
  const movedByB = (col("v2-messages").get(tid + "/" + k) || {}).conv === into;
  const markerB = col("v2-state").get(tid + "/merged:" + from) || {};

  // The relay phone's next call for this merge (its retry, or the run ten minutes later).
  const c = await merge();
  const total = reloads();
  console.log(JSON.stringify({ c0: c0._status, marker0told: !!marker0.told, a: { status: a._status, more: a.more }, afterA, markerAtold: !!markerA.told,
    b: b._status, movedByB, markerBtold: !!markerB.told, c: { status: c._status, more: c.more }, reloads: total }));
  const ok = c0._status === 500 && !marker0.told && a._status === 200 && afterA === 1 && b._status === 500 && movedByB && total === 2;
  console.log(ok ? "ok      devices reloaded for the move that overlapping call B made"
    : "FAIL    devices never reloaded for overlapping call B's move (reloads: " + total + ", want 2; marker after B says told: " + !!markerB.told + ")");
  process.exit(ok ? 0 : 1);
})().catch(e => { console.error(e); process.exit(2); });
