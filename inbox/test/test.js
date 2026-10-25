const assert = require("assert");
const crypto = require("crypto");
const { caller, col, rings, pushes, hooks } = require("./harness");
const api = caller(require("path").join(__dirname, "../functions/api.js"));
const pair = caller(require("path").join(__dirname, "../functions/pair.js"));
const RELAY = "relay-phone", INBOX = "inbox-sidephone";
const fp = (n) => n.toString(16).padStart(16, "0");
const realNow = Date.now.bind(Date);
let offset = 0;
Date.now = () => realNow() + offset;
const sha = (s) => crypto.createHash("sha256").update(s).digest("hex");
(async () => {
  col("settings").set("pushSubs", { ids: ["apppush_test1"] });
  col("settings").set("pairing", { relayKey: "hak_relay.secret", inboxKey: "hak_inbox.secret", codes: [] });
  const t0 = Date.now() - 3600000;
  const conv = "c1aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

  // 1. a text through the notification, then the store's copy of it a few seconds off: one message, one unread, one push
  let r = await api("POST", "/api/relay/ingest", { items: [{ conv, name: "Andy", sender: "Andy", text: "Checking in  on the checks", ts: t0, fp: fp(1) }] }, null, RELAY);
  assert.strictEqual(r.stored, 1);
  r = await api("POST", "/api/relay/ingest", { items: [{ conv, name: "Andy", sender: "+18015550123", text: "Checking in on the checks", ts: t0 + 4000, fp: fp(2), src: "store" }] }, null, RELAY);
  assert.strictEqual(r.stored, 0); assert.strictEqual(r.folded, 1);
  let c = col("conversations").get(conv);
  assert.strictEqual(c.unread, 1);
  assert.strictEqual(pushes.length, 1);
  assert.strictEqual([...col("messages").values()].filter(m => m.conv === conv).length, 1);

  // 2. the store first, then the notification: still one
  r = await api("POST", "/api/relay/ingest", { items: [{ conv, name: "Andy", sender: "+18015550123", text: "Second one", ts: t0 + 60000, fp: fp(3), src: "store" }] }, null, RELAY);
  assert.strictEqual(r.stored, 1);
  r = await api("POST", "/api/relay/ingest", { items: [{ conv, name: "Andy", sender: "Andy", text: "Second one", ts: t0 + 61000, fp: fp(4) }] }, null, RELAY);
  assert.strictEqual(r.folded, 1);
  assert.strictEqual(col("conversations").get(conv).unread, 2);
  assert.strictEqual(pushes.length, 2);

  // 3. a reply from Texts and the store's copy of it, sent much later because the relay device was offline
  const sent = await api("POST", "/api/send", { conv, text: "On it, Andy" }, null, INBOX);
  offset += 2 * 3600000;   // two hours pass before the relay device sends it
  await api("POST", "/api/relay/outbox/result", { id: sent.id, status: "sent", via: "rcs", canReply: true }, null, RELAY);
  r = await api("POST", "/api/relay/ingest", { items: [{ conv, name: "Andy", dir: "out", text: "On it, Andy", ts: Date.now() + 2000, fp: fp(5), src: "store" }] }, null, RELAY);
  assert.strictEqual(r.folded, 1, JSON.stringify(r));
  // a reply typed on the relay device itself: stored as outgoing, sent from the phone, no unread, no push
  r = await api("POST", "/api/relay/ingest", { items: [{ conv, name: "Andy", dir: "out", text: "Typed on the relay phone", ts: Date.now() + 5000, fp: fp(6), src: "store" }] }, null, RELAY);
  assert.strictEqual(r.stored, 1);
  const mine = [...col("messages").values()].find(m => m.text === "Typed on the relay phone");
  assert.strictEqual(mine.dir, "out"); assert.strictEqual(mine.via, "phone"); assert.strictEqual(mine.status, "sent");
  c = col("conversations").get(conv);
  assert.strictEqual(c.lastDir, "out"); assert.strictEqual(c.lastText, "Typed on the relay phone"); assert.strictEqual(c.unread, 2);
  assert.strictEqual(pushes.length, 2);
  // the echo of a Texts reply in Messages' notification folds too
  const sent2 = await api("POST", "/api/send", { conv, text: "Echo test" }, null, INBOX);
  await api("POST", "/api/relay/outbox/result", { id: sent2.id, status: "sent", via: "rcs", canReply: true }, null, RELAY);
  r = await api("POST", "/api/relay/ingest", { items: [{ conv, name: "Andy", dir: "out", text: "Echo test", ts: Date.now() + 1000, fp: fp(7) }] }, null, RELAY);
  assert.strictEqual(r.folded, 1);

  // 4. full history: stored quietly, flagged, no unread, no push, and the summary keeps the newest message
  const hist = Array.from({ length: 30 }, (_, i) => ({ conv, name: "Andy", sender: "+18015550123", dir: i % 3 ? "in" : "out", text: "old " + i, ts: t0 - 86400000 * 30 + i * 60000, fp: fp(100 + i), src: "store", history: true }));
  r = await api("POST", "/api/relay/ingest", { items: hist }, null, RELAY);
  assert.strictEqual(r.stored, 30);
  c = col("conversations").get(conv);
  assert.strictEqual(c.unread, 2); assert.strictEqual(c.lastText, "Echo test");
  assert.strictEqual(pushes.length, 2);
  // logged once for the conversation, not per old message: devices refresh that conversation
  assert.strictEqual([...col("feed").values()].filter(v => v.history).length, 0);
  assert.strictEqual([...col("feed").values()].filter(v => v.touch && v.conv === conv).length, 1);
  const touchSync = await api("GET", "/api/sync", null, { feed: "f:" + String(Date.now() - 60000).padStart(13, "0") }, INBOX);
  assert.deepStrictEqual(touchSync.reload, [conv]);
  assert.ok(touchSync.convs.some(x => x.conv === conv));
  assert.ok(!touchSync.items.some(x => x.touch));
  // a history-only conversation gets created
  const conv2 = "c2bbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
  r = await api("POST", "/api/relay/ingest", { items: [{ conv: conv2, name: "Dad", sender: "+18015550999", text: "From 2024", ts: t0 - 86400000 * 400, fp: fp(300), src: "store", history: true }] }, null, RELAY);
  assert.strictEqual(col("conversations").get(conv2).unread, 0);

  // 5. read on the relay device: read here too
  r = await api("POST", "/api/relay/ingest", { items: [], reads: { [conv]: Date.now() + 10000 } }, null, RELAY);
  assert.strictEqual(col("conversations").get(conv).unread, 0);

  // 6. three pictures from the store, two of which came through the notification: one more picture, not three
  const conv3 = "c3cccccccccccccccccccccccccccccc";
  const pic = (n, src, dt) => ({ conv: conv3, name: "Megan", sender: "Megan", text: "", ts: t0 + dt, fp: fp(n), src, media: { mime: "image/jpeg" } });
  await api("POST", "/api/relay/ingest", { items: [pic(400, "notif", 0), pic(401, "notif", 1000)] }, null, RELAY);
  r = await api("POST", "/api/relay/ingest", { items: [pic(402, "store", 500), pic(403, "store", 1500), pic(404, "store", 2500)] }, null, RELAY);
  assert.strictEqual(r.stored, 1); assert.strictEqual(r.folded, 2);

  // 6b. an attachment is uploaded only when the inbox still needs it, and attaches to the copy already stored
  const want = (n, dt, extra) => Object.assign({ conv: conv3, name: "Megan", sender: "Megan", text: "", ts: t0 + dt, fp: fp(n), src: "store", media: { mime: "image/jpeg" }, wantMedia: true }, extra || {});
  r = await api("POST", "/api/relay/ingest", { items: [want(410, 600000)] }, null, RELAY);
  assert.strictEqual(r.results[0].status, "stored"); assert.strictEqual(r.results[0].needMedia, true);
  const path = "/media/" + conv3 + "/" + String(t0 + 600000) + "-" + fp(410) + "-o.jpg";
  r = await api("POST", "/api/relay/ingest", { items: [want(410, 600000, { media: { mime: "image/jpeg", path, bytes: 10 } })] }, null, RELAY);
  assert.strictEqual(r.results[0].status, "known"); assert.strictEqual(r.results[0].media, "attached"); assert.ok(!r.results[0].needMedia);
  assert.strictEqual(col("messages").get("m:" + String(t0 + 600000) + ":" + fp(410)).media.path, path);
  // the notification already brought this picture with its file: the store's copy needs no upload
  const notifPath = "/media/" + conv3 + "/" + String(t0 + 900000) + "-" + fp(411) + "-o.jpg";
  await api("POST", "/api/relay/ingest", { items: [{ conv: conv3, name: "Megan", sender: "Megan", text: "", ts: t0 + 900000, fp: fp(411), media: { mime: "image/jpeg", path: notifPath } }] }, null, RELAY);
  r = await api("POST", "/api/relay/ingest", { items: [want(412, 902000)] }, null, RELAY);
  assert.strictEqual(r.results[0].status, "folded"); assert.ok(!r.results[0].needMedia);
  assert.strictEqual(r.results[0].k, "m:" + String(t0 + 900000) + ":" + fp(411));
  // sent again later: recognised as the copy that folded, not stored a second time
  r = await api("POST", "/api/relay/ingest", { items: [want(412, 902000)] }, null, RELAY);
  assert.strictEqual(r.results[0].status, "known"); assert.strictEqual(r.stored, 0);

  // 6c. a history copy of a text relayed weeks ago folds into it, however many messages came after
  const conv4 = "c4dddddddddddddddddddddddddddddd";
  const weeksAgo = t0 - 21 * 86400000;
  await api("POST", "/api/relay/ingest", { items: [{ conv: conv4, name: "Sam", sender: "Sam", text: "See you Saturday", ts: weeksAgo, fp: fp(500) }] }, null, RELAY);
  for (let b = 0; b < 6; b++) {
    await api("POST", "/api/relay/ingest", { items: Array.from({ length: 50 }, (_, i) => ({ conv: conv4, name: "Sam", sender: "Sam", text: "later " + b + "-" + i, ts: weeksAgo + 86400000 + (b * 50 + i) * 60000, fp: fp(1000 + b * 50 + i) })) }, null, RELAY);
  }
  r = await api("POST", "/api/relay/ingest", { items: [{ conv: conv4, name: "Sam", sender: "+18015550444", text: "See you Saturday", ts: weeksAgo + 2000, fp: fp(501), src: "store", history: true }] }, null, RELAY);
  assert.strictEqual(r.folded, 1, JSON.stringify(r));

  // 6d. a reply from Texts, its echo in the notification before the relay reports it sent, and the store's copy: one message
  const sent3 = await api("POST", "/api/send", { conv, text: "Three ways" }, null, INBOX);
  r = await api("POST", "/api/relay/ingest", { items: [{ conv, name: "Andy", dir: "out", text: "Three ways", ts: Date.now() + 800, fp: fp(600) }] }, null, RELAY);
  assert.strictEqual(r.results[0].status, "folded"); assert.strictEqual(r.results[0].k, sent3.id);
  await api("POST", "/api/relay/outbox/result", { id: sent3.id, status: "sent", via: "rcs", canReply: true }, null, RELAY);
  r = await api("POST", "/api/relay/ingest", { items: [{ conv, name: "Andy", dir: "out", text: "Three ways", ts: Date.now() + 900, fp: fp(601), src: "store" }] }, null, RELAY);
  assert.strictEqual(r.results[0].status, "folded");
  assert.strictEqual([...col("messages").values()].filter(m => m.text === "Three ways").length, 1);
  assert.deepStrictEqual(col("messages").get(sent3.id).from.sort(), ["notif", "store", "texts"]);
  // two different texts that read the same, both sent: two messages
  r = await api("POST", "/api/relay/ingest", { items: [{ conv, name: "Andy", dir: "out", text: "Three ways", ts: Date.now() + 60000, fp: fp(602), src: "store" }] }, null, RELAY);
  assert.strictEqual(r.results[0].status, "stored");

  // 6e. locate: which conversation already holds these messages
  const loc = await api("POST", "/api/relay/locate", { probes: [{ ts: weeksAgo + 1000, text: "See  you Saturday", dir: "in" }, { ts: t0 + 600000, text: "", mime: "image/jpeg", dir: "in" }, { ts: t0, text: "nothing like it", dir: "in" }] }, null, RELAY);
  assert.deepStrictEqual(loc.convs, [[conv4], [conv3], []]);

  // 6f. merge: a conversation the store brought in turns out to be one the notifications already had
  const conv5 = "c5eeeeeeeeeeeeeeeeeeeeeeeeeeeeee";
  await api("POST", "/api/relay/ingest", { items: [
    { conv: conv5, name: "Sam (store)", sender: "+18015550444", text: "Old thread text", ts: weeksAgo - 86400000, fp: fp(700), src: "store", history: true },
    { conv: conv5, name: "Sam (store)", sender: "+18015550444", text: "later 0-3", ts: weeksAgo + 86400000 + 3 * 60000 + 1500, fp: fp(701), src: "store", history: true }
  ] }, null, RELAY);
  const epochBefore = (await api("GET", "/api/inbox", null, null, INBOX)).epoch;
  const m = await api("POST", "/api/relay/merge", { from: conv5, into: conv4 }, null, RELAY);
  assert.strictEqual(m.moved, 1); assert.strictEqual(m.folded, 1);
  assert.strictEqual(col("conversations").get(conv5), undefined);
  assert.strictEqual([...col("messages").values()].filter(x => x.conv === conv5).length, 0);
  assert.strictEqual(col("messages").get("m:" + String(weeksAgo - 86400000) + ":" + fp(700)).conv, conv4);
  const afterMerge = await api("GET", "/api/sync", null, { feed: "f:" + String(Date.now() - 60000).padStart(13, "0") }, INBOX);
  assert.strictEqual(afterMerge.epoch, epochBefore + 1);
  assert.ok(rings.some(x => x[0] === "inbox" && x[1] === "reload"));

  // 6g. a Texts reply sent long after it was queued: its store copy folds, and is still recognised days later
  const sent4 = await api("POST", "/api/send", { conv: conv4, text: "Sorry, just saw this" }, null, INBOX);
  offset += 15 * 60000;   // the relay phone was offline for a quarter of an hour
  await api("POST", "/api/relay/outbox/result", { id: sent4.id, status: "sent", via: "rcs", canReply: true }, null, RELAY);
  const late = { conv: conv4, name: "Sam", dir: "out", text: "Sorry, just saw this", ts: Date.now() + 1500, fp: fp(800), src: "store" };
  r = await api("POST", "/api/relay/ingest", { items: [late] }, null, RELAY);
  assert.strictEqual(r.results[0].status, "folded");
  offset += 3 * 86400000;   // a resync three days on sends it again
  r = await api("POST", "/api/relay/ingest", { items: [late] }, null, RELAY);
  assert.strictEqual(r.results[0].status, "known", JSON.stringify(r));
  assert.strictEqual(r.results[0].k, sent4.id);
  assert.strictEqual([...col("messages").values()].filter(m => m.text === "Sorry, just saw this").length, 1);

  // 6h. a big merge goes in slices that each fit the time limit, and picks up after a try that stopped halfway
  const sliced = caller(require("path").join(__dirname, "../functions/api.js"), src => src.replace("const MERGE_BUDGET_MS = 4500;", "const MERGE_BUDGET_MS = 1;"));
  const conv6 = "c6ffffffffffffffffffffffffffffff", conv7 = "c7aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
  const base6 = weeksAgo - 30 * 86400000;
  await api("POST", "/api/relay/ingest", { items: [{ conv: conv7, name: "Kim", sender: "Kim", text: "hello there", ts: base6, fp: fp(899) }] }, null, RELAY);
  for (let b = 0; b < 3; b++) {
    await api("POST", "/api/relay/ingest", { items: Array.from({ length: 30 }, (_, i) => ({ conv: conv6, name: "Kim (store)", sender: "+18015550666", text: "batch " + b + " #" + i, ts: base6 + 3600000 + (b * 30 + i) * 600000, fp: fp(900 + b * 30 + i), src: "store", history: true })) }, null, RELAY);
  }
  // an earlier try folded this one into Kim's copy and stopped before removing it
  const half = { conv: conv6, name: "Kim (store)", sender: "+18015550666", text: "hello there", ts: base6 + 1000, fp: fp(990), src: "store", history: true };
  await api("POST", "/api/relay/ingest", { items: [half] }, null, RELAY);
  const halfKey = "m:" + String(base6 + 1000).padStart(13, "0") + ":" + fp(990);
  const kimFirst = col("messages").get("m:" + String(base6).padStart(13, "0") + ":" + fp(899));
  assert.ok(col("messages").has(halfKey));
  kimFirst.twins = [halfKey]; kimFirst.from = ["notif", "store"];
  col("messages").set(kimFirst.k, kimFirst);
  let calls = 0, mr;
  do { mr = await sliced("POST", "/api/relay/merge", { from: conv6, into: conv7 }, null, RELAY); calls++; } while (mr.more && calls < 20);
  assert.ok(calls > 1, "expected more than one slice, got " + calls);
  assert.strictEqual(mr.more, false);
  assert.strictEqual(col("conversations").get(conv6), undefined);
  assert.strictEqual([...col("messages").values()].filter(x => x.conv === conv6).length, 0);
  assert.strictEqual([...col("messages").values()].filter(x => x.conv === conv7).length, 91);
  assert.ok(!col("messages").has(halfKey));
  r = await api("POST", "/api/relay/ingest", { items: [half] }, null, RELAY);   // that copy, sent again, is known
  assert.strictEqual(r.results[0].status, "known");

  // 6i. two requests writing one conversation's summary at once: neither change is lost
  const before7 = col("conversations").get(conv7).unread;
  hooks.raceOnce = { c: "conversations", k: conv7, mutate: v => { v.unread = (v.unread || 0) + 5; } };
  await api("POST", "/api/relay/ingest", { items: [{ conv: conv7, name: "Kim", sender: "Kim", text: "one more", ts: Date.now(), fp: fp(1999) }] }, null, RELAY);
  assert.strictEqual(col("conversations").get(conv7).unread, before7 + 6);

  // 6j. an MMS read on the relay phone: its store time is whole seconds, the notification's copy has milliseconds
  const conv8 = "c8bbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
  const mmsAt = Math.floor(Date.now() / 1000) * 1000;
  await api("POST", "/api/relay/ingest", { items: [{ conv: conv8, name: "Lee", sender: "Lee", text: "", ts: mmsAt + 740, fp: fp(2000), media: { mime: "image/jpeg" } }] }, null, RELAY);
  assert.strictEqual(col("conversations").get(conv8).unread, 1);
  await api("POST", "/api/relay/ingest", { items: [], reads: { [conv8]: mmsAt } }, null, RELAY);
  assert.strictEqual(col("conversations").get(conv8).unread, 0);

  // 7. wake kinds reach the relay
  const w = await api("POST", "/api/relay/wake", { kind: "refresh" }, null, INBOX);
  assert.strictEqual(w.kind, "refresh");
  const ob = await api("GET", "/api/relay/outbox", null, null, RELAY);
  assert.strictEqual(ob.wake, w.at); assert.strictEqual(ob.wakeKind, "refresh");
  assert.ok(rings.some(x => x[0] === "outbox" && x[1] === "reconnect" && x[2].kind === "refresh"));
  const hb = await api("POST", "/api/relay/heartbeat", { listening: true, store: { allowed: true, lastScan: 5, history: { state: "running", done: 10, total: 40, media: false } } }, null, RELAY);
  assert.strictEqual(hb.wakeKind, "refresh");
  assert.strictEqual(col("devices").get("relay").store.history.total, 40);

  // 8. a cursor older than the log keeps resets the device
  const old = await api("GET", "/api/sync", null, { feed: "f:" + String(Date.now() - 4 * 86400000).padStart(13, "0") }, INBOX);
  assert.strictEqual(old.reset, true);
  const fresh = await api("GET", "/api/sync", null, { feed: "f:" + String(Date.now() - 60000).padStart(13, "0") }, INBOX);
  assert.ok(!fresh.reset);

  // 9. only the relay makes link codes; only a linked phone lets a relay in; the newest code of a role replaces the last
  const h1 = sha("aaaabbbbcccc"), h2 = sha("ddddeeeeffff"), h3 = sha("gggghhhhjjjj".replace(/[il]/g, "1"));
  assert.strictEqual((await api("POST", "/api/pair/codes", { hash: h1, role: "inbox" }, null, INBOX))._status, 403);
  assert.strictEqual((await api("POST", "/api/pair/codes", { hash: h1, role: "inbox" }, null, RELAY))._status, 200);
  assert.strictEqual((await api("POST", "/api/pair/codes", { hash: h2, role: "inbox" }, null, RELAY))._status, 200);
  assert.strictEqual(col("settings").get("pairing").codes.filter(x => x.role === "inbox").length, 1);
  assert.strictEqual((await api("POST", "/api/pair/codes", { hash: h3, role: "relay" }, null, RELAY))._status, 403);

  // 10. pairing: the replaced code is dead, the newest works once; a waiting relay is not a wrong guess
  assert.strictEqual((await pair("POST", "/pair", { code: "AAAA-BBBB-CCCC", role: "inbox" }))._status, 403);
  const ok = await pair("POST", "/pair", { code: "dddd-eeee-ffff", role: "inbox" });
  assert.strictEqual(ok.key, "hak_inbox.secret");
  assert.strictEqual((await pair("POST", "/pair", { code: "dddd-eeee-ffff", role: "inbox" }))._status, 403);
  const failsBefore = (col("settings").get("pairlock") || {}).fails;
  for (let i = 0; i < 12; i++) assert.strictEqual((await pair("POST", "/pair", { code: "MMMMNNNNPPPP", role: "relay", wait: true }))._status, 202);
  assert.strictEqual((col("settings").get("pairlock") || {}).fails, failsBefore);
  await api("POST", "/api/pair/codes", { hash: sha("mmmmnnnnpppp"), role: "relay" }, null, INBOX);
  const relayIn = await pair("POST", "/pair", { code: "MMMMNNNNPPPP", role: "relay", wait: true });
  assert.strictEqual(relayIn.key, "hak_relay.secret");
  const wrongRole = await pair("POST", "/pair", { code: "aaaabbbbcccc", role: "relay" });
  assert.strictEqual(wrongRole._status, 403);

  // 11. phones the inbox knows: a reinstalled app gets its key back by its phone's tag, and nothing else does
  col("settings").delete("pairlock");
  const tagR = sha("textrelay:relay:0123456789abcdef"), tagI = sha("textrelay:inbox:fedcba9876543210");
  const other = sha("textrelay:inbox:1111222233334444");
  const unknown = await pair("POST", "/pair", { device: tagI, role: "inbox" });
  assert.strictEqual(unknown._status, 404);
  assert.strictEqual(unknown.known, false);
  assert.strictEqual(col("settings").get("pairlock"), undefined);   // an unknown phone is not a wrong code
  await api("POST", "/api/pair/codes", { hash: sha("qqqqrrrrssss"), role: "inbox" }, null, RELAY);
  assert.strictEqual((await pair("POST", "/pair", { code: "QQQQ-RRRR-SSSS", role: "inbox", device: tagI })).key, "hak_inbox.secret");
  // reinstalled: the same phone is let back in with no code; another phone isn't; an inbox phone's tag is no relay tag
  const again = await pair("POST", "/pair", { device: tagI, role: "inbox" });
  assert.strictEqual(again.key, "hak_inbox.secret");
  assert.strictEqual(again.known, true);
  assert.strictEqual((await pair("POST", "/pair", { device: other, role: "inbox" }))._status, 404);
  assert.strictEqual((await pair("POST", "/pair", { device: tagI, role: "relay" }))._status, 404);
  // the relay phone is remembered from its heartbeat; reinstalled, its first waiting check connects it
  await api("POST", "/api/relay/heartbeat", { listening: true, device: tagR }, null, RELAY);
  assert.strictEqual(col("devices").get("relay").device, tagR);
  assert.strictEqual(col("settings").get("phones").relay[0].tag, tagR);
  assert.strictEqual((await pair("POST", "/pair", { code: "TTTTVVVVWWWW", role: "relay", wait: true, device: tagR })).key, "hak_relay.secret");
  // a heartbeat sent with the inbox's key never makes a relay phone
  await api("POST", "/api/relay/heartbeat", { listening: true, device: other }, null, INBOX);
  assert.strictEqual((await pair("POST", "/pair", { device: other, role: "relay" }))._status, 404);
  // Texts reports its phone once linked, and forgets it when unlinked on purpose
  await api("POST", "/api/device", { device: other }, null, INBOX);
  assert.strictEqual((await pair("POST", "/pair", { device: other, role: "inbox" })).key, "hak_inbox.secret");
  assert.strictEqual((await api("POST", "/api/device/forget", { device: other }, null, INBOX)).forgot, true);
  assert.strictEqual((await pair("POST", "/pair", { device: other, role: "inbox" }))._status, 404);
  assert.strictEqual(col("settings").get("phones").inbox.length, 1);
  // a known phone gets in even while wrong codes have paused linking; codes stay paused
  col("settings").set("pairlock", { fails: 10, until: Date.now() + 600000 });
  assert.strictEqual((await pair("POST", "/pair", { device: tagI, role: "inbox" })).key, "hak_inbox.secret");
  assert.strictEqual((await pair("POST", "/pair", { code: "XXXX-YYYY-ZZZZ", role: "inbox" }))._status, 429);
  col("settings").delete("pairlock");

  // 12. attachments the inbox's storage can't keep are refused for good (a 400 the relay phone drops), never a 500
  const mconv = "c1aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
  for (const mime of ["image/heic", "image/heif", "video/3gpp", "audio/amr", "audio/ogg", "text/x-vcard"]) {
    const t = await api("POST", "/api/relay/media/ticket", { conv: mconv, fp: "0123456789abcdef", ts: 1792000000000, mime, bytes: 5000000 }, null, RELAY);
    assert.strictEqual(t._status, 400, mime);
  }
  const jpg = await api("POST", "/api/relay/media/ticket", { conv: mconv, fp: "0123456789abcdef", ts: 1792000000000, mime: "image/jpg", bytes: 5000000 }, null, RELAY);
  assert.strictEqual(jpg._status, 200);
  assert.ok(jpg.path.endsWith("-o.jpg"), jpg.path);
  console.log("all server tests passed");
})().catch(e => { console.error(e); process.exit(1); });
