// The store on its own: what expiring, saving and loading do (the inbox's own tests cover everything else it does,
// through test/harness.js).
//   node server/test/store.test.js
const assert = require("assert");
const { createStore } = require("../store");

(async () => {
  let clock = 1_000_000;
  const saved = [];
  let failNext = false;
  const persist = {
    apply(rows) {
      if (failNext) { failNext = false; throw new Error("disk full"); }
      saved.push(rows.map((r) => Object.assign({}, r)));
    }
  };
  const s = createStore({ persist, expire: true, now: () => clock });
  const { data } = s;

  // ---------------------------------------------------------------- saving comes first
  assert.deepStrictEqual(await data.put("a", "k1", { n: 1 }), { version: 1 });
  assert.deepStrictEqual(saved.pop(), [{ c: "a", k: "k1", value: { n: 1 }, version: 1, expiresAt: null }]);
  assert.deepStrictEqual(await data.put("a", "k1", { n: 2 }), { version: 2 });
  saved.length = 0;
  failNext = true;
  await assert.rejects(data.put("a", "k1", { n: 3 }), /disk full/);
  assert.deepStrictEqual(await data.getWithMeta("a", "k1"), { key: "k1", value: { n: 2 }, version: 2 }, "a write that couldn't be saved didn't happen");
  failNext = true;
  await assert.rejects(data.batchPut("a", [{ key: "b1", value: 1 }, { key: "b2", value: 2 }]));
  assert.strictEqual(await data.get("a", "b1"), null, "nor did any of its batch");
  await data.batchPut("a", [{ key: "b1", value: 1 }, { key: "b1", value: 11 }, { key: "b2", value: 2 }]);
  assert.strictEqual(saved.length, 1, "a batch is saved in one go");
  assert.deepStrictEqual(saved[0].map((r) => [r.k, r.version]), [["b1", 1], ["b1", 2], ["b2", 1]]);
  assert.deepStrictEqual(await data.getWithMeta("a", "b1"), { key: "b1", value: 11, version: 2 });
  failNext = true;
  await assert.rejects(data.delete("a", "b2"));
  assert.strictEqual(await data.get("a", "b2"), 2, "a delete that couldn't be saved didn't happen");
  await data.delete("a", "b2");
  assert.strictEqual(await data.get("a", "b2"), null);
  assert.deepStrictEqual(await data.put("a", "b2", 2), { version: 1 }, "a deleted item starts over");

  // ---------------------------------------------------------------- items whose time is up are gone
  await data.put("v2-offers", "o1", { x: 1 }, { ttlSeconds: 60 });
  assert.strictEqual(saved.pop()[0].expiresAt, clock + 60000);
  clock += 59000;
  assert.deepStrictEqual(await data.get("v2-offers", "o1"), { x: 1 });
  clock += 2000;
  assert.strictEqual(await data.get("v2-offers", "o1"), null);
  assert.deepStrictEqual(saved.pop(), [{ c: "v2-offers", k: "o1", gone: true }], "and gone from the disk too");
  await data.put("v2-offers", "o1", { x: 2 }, { ifNotExists: true, ttlSeconds: 60 });
  assert.deepStrictEqual(await data.getWithMeta("v2-offers", "o1"), { key: "o1", value: { x: 2 }, version: 1 }, "its key is free again");
  // a put without a time keeps the item for good
  await data.put("v2-offers", "o1", { x: 3 });
  clock += 3600000;
  assert.deepStrictEqual(await data.get("v2-offers", "o1"), { x: 3 });

  // lists, queries and batches skip what's gone
  await data.put("v2-state", "t/relay", { seen: "0000000000005" }, { ttlSeconds: 1 });
  await data.put("v2-state", "u/relay", { seen: "0000000000006" });
  assert.strictEqual((await data.query("v2-state", "by-seen", { lt: "9" })).items.length, 2);
  assert.strictEqual((await data.list("v2-state", { keyPrefix: "t/" })).items.length, 1);
  clock += 1000;
  assert.deepStrictEqual((await data.query("v2-state", "by-seen", { lt: "9" })).items.map((i) => i.key), ["u/relay"]);
  await data.put("v2-state", "t/relay", { seen: "0000000000005" }, { ttlSeconds: 1 });
  clock += 1000;
  assert.strictEqual((await data.list("v2-state", { keyPrefix: "t/" })).items.length, 0);
  await data.put("v2-state", "t/relay", { seen: "0000000000005" }, { ttlSeconds: 1 });
  clock += 1000;
  const got = await data.batchGet("v2-state", ["t/relay", "u/relay"]);
  assert.deepStrictEqual(got.items.map((i) => i.found), [false, true]);

  // the sweep drops the rest, a batch at a time
  for (let i = 0; i < 1200; i++) await data.put("v2-feed", "f" + i, { i }, { ttlSeconds: 10 });
  saved.length = 0;
  clock += 11000;
  assert.strictEqual(s.sweep(), 1200);
  assert.deepStrictEqual(saved.map((b) => b.length), [500, 500, 200]);
  assert.strictEqual(s.counts()["v2-feed"], 0);

  // ---------------------------------------------------------------- loading puts back versions and times
  const t = createStore({ expire: true, now: () => clock });
  t.load("v2-devices", "d1", { tid: "x" }, 7, null);
  t.load("v2-offers", "o9", { y: 1 }, 3, clock + 5000);
  assert.deepStrictEqual(await t.data.getWithMeta("v2-devices", "d1"), { key: "d1", value: { tid: "x" }, version: 7 });
  await assert.rejects(t.data.put("v2-devices", "d1", {}, { ifVersion: 6 }), (e) => e.name === "ConflictError" && e.currentVersion === 7);
  assert.deepStrictEqual(await t.data.put("v2-devices", "d1", { tid: "y" }, { ifVersion: 7 }), { version: 8 });
  clock += 5000;
  assert.strictEqual(await t.data.get("v2-offers", "o9"), null);

  // ---------------------------------------------------------------- what's handed out is a copy
  const v = await data.get("a", "k1");
  v.n = 99;
  assert.deepStrictEqual(await data.get("a", "k1"), { n: 2 });
  const page = await data.list("a", {});
  page.items[0].value = "changed";
  assert.notStrictEqual((await data.list("a", {})).items[0].value, "changed");

  console.log("store.test.js: all passed");
})().catch((e) => { console.error(e && e.stack || e); process.exit(1); });
