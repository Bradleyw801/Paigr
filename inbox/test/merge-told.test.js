// A merge's "devices were told" covers every change, not only the first call's: a call that moves a late reply and stops
// before telling devices leaves the next call to. (Started as an independent reviewer's check, kept as a regression.)
//   node test/merge-told.test.js
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
    const r = await v2("POST", "/v2/relay/ingest", { items: [item] }, null, H(relay.token));
    assert.strictEqual(r._status, 200, JSON.stringify(r));
  }
  const reloads = () => rings.filter(x => x[0] === "tr2/" + tid + "/i" && x[1] === "reload").length;
  const mr = () => TR2.sealJson(keys.enc, { from, into }, TR2.AAD.merge(into));

  // Call 1: a reply racing the merge lands under `from` just as `from`'s summary goes.
  const k = "m:" + String(Date.now()).padStart(13, "0") + ":olatereply";
  const realDelete = base.data.delete;
  base.data.delete = async (c, key) => {
    await realDelete(c, key);
    if (c === "v2-convs" && key === tid + "/" + from) {
      await base.data.put("v2-messages", tid + "/" + k, { k, conv: from, cv: tid + "/" + from, qs: tid + "/queued", dir: "out",
        status: "queued", e: "e1." + "A".repeat(60), o: "e1." + "B".repeat(60), ts: Date.now(), createdAt: Date.now(), src: "texts" });
    }
  };
  let first;
  try { first = await v2("POST", "/v2/relay/merge", { from, into, mr: await mr() }, null, H(relay.token)); } finally { base.data.delete = realDelete; }
  const afterFirst = reloads();

  // Call 2: moves the reply, then stops before devices reload.
  const realPut = base.data.put;
  base.data.put = async function (c, key) {
    if (c === "v2-state" && key === tid + "/epoch") { base.data.put = realPut; throw new Error("stopped here"); }
    return realPut.apply(this, arguments);
  };
  let second;
  try { second = await v2("POST", "/v2/relay/merge", { from, into, mr: await mr() }, null, H(relay.token)); } finally { base.data.put = realPut; }
  const movedNow = (col("v2-messages").get(tid + "/" + k) || {}).conv === into;

  // Call 3: the relay phone tries again.
  const third = await v2("POST", "/v2/relay/merge", { from, into, mr: await mr() }, null, H(relay.token));
  const total = reloads();
  console.log(JSON.stringify({ first: { more: first.more }, afterFirst, second: second._status, movedNow, third: { more: third.more }, reloads: total }));
  const ok = first.more === true && afterFirst === 1 && second._status === 500 && movedNow && total === 2;
  console.log(ok ? "ok      devices reloaded for the move that call 2 made" : "FAIL    devices never reloaded for call 2's move (reloads: " + total + ", want 2)");
  process.exit(ok ? 0 : 1);
})().catch(e => { console.error(e); process.exit(2); });
