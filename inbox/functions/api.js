// Text Relay API. One function behind ANY /api/* (role gate: device).
// Two callers: the relay phone (ingest, outbox, heartbeat) and the inbox PWA (inbox, thread, send, sync, push).

const MSG = "messages";
const CONV = "conversations";
const DEV = "devices";
const SET = "settings";
/** Arrival log: every message as it is stored or changes, keyed by when it reached the server. Inbox devices sync from it. */
const FEED = "feed";
/** A copy of a message that folded into another, by the copy's own key: { to: the message it folded into }. */
const TWINS = "twins";
const FEED_TTL_SECONDS = 3 * 86400;
/** Each sync also re-reads this much of the log just before its cursor, for a write that became visible a moment late. */
const FEED_SKEW_MS = 15000;

async function handler(ctx) {
  const method = (ctx.request.method || "GET").toUpperCase();
  const path = (ctx.request.path || "/").replace(/\/+$/, "") || "/";
  const q = queryOf(ctx);
  try {
    if (method === "GET" && path === "/api/ping") return json(200, { ok: true, serverTime: Date.now(), caller: callerOf(ctx) });

    // ---- relay phone ----
    if (method === "POST" && path === "/api/relay/ingest") return await ingest(ctx, await bodyOf(ctx));
    if (method === "GET" && path === "/api/relay/outbox") return await outbox(ctx);
    if (method === "POST" && path === "/api/relay/outbox/result") return await outboxResult(ctx, await bodyOf(ctx));
    if (method === "POST" && path === "/api/relay/heartbeat") return await heartbeat(ctx, await bodyOf(ctx));
    if (method === "POST" && path === "/api/relay/media") return await mediaUpload(ctx);
    if (method === "POST" && path === "/api/relay/media/ticket") return await mediaTicket(ctx, await bodyOf(ctx));
    if (method === "POST" && path === "/api/relay/media/complete") return await mediaComplete(ctx, await bodyOf(ctx));
    if (method === "POST" && path === "/api/relay/locate") return await locate(ctx, await bodyOf(ctx));
    if (method === "POST" && path === "/api/relay/merge") return await merge(ctx, await bodyOf(ctx));
    if (method === "GET" && path === "/api/media") return await mediaGet(ctx, q);

    // ---- inbox PWA ----
    if (method === "GET" && path === "/api/inbox") return await inbox(ctx);
    if (method === "GET" && path === "/api/thread") return await thread(ctx, q);
    if (method === "GET" && path === "/api/sync") return await sync(ctx, q);
    if (method === "POST" && path === "/api/send") return await send(ctx, await bodyOf(ctx));
    if (method === "POST" && path === "/api/read") return await markRead(ctx, await bodyOf(ctx));
    if (method === "GET" && path === "/api/status") return await status(ctx);
    if (method === "POST" && path === "/api/push/register") return await pushRegister(ctx, await bodyOf(ctx));
    if (method === "POST" && path === "/api/push/test") return await pushTest(ctx);
    if (method === "POST" && path === "/api/relay/wake") return await relayWake(ctx, await bodyOf(ctx));

    // ---- linking a device ----
    if (method === "POST" && path === "/api/pair/codes") return await pairCodeAdd(ctx, await bodyOf(ctx));
    if (method === "POST" && path === "/api/device") return await deviceRemember(ctx, await bodyOf(ctx));
    if (method === "POST" && path === "/api/device/forget") return await deviceForget(ctx, await bodyOf(ctx));

    return json(404, { error: "No such endpoint: " + method + " " + path });
  } catch (e) {
    if (e && e.badRequest) return json(400, { error: e.message });
    console.error("api failed", method, path, String(e && e.stack || e));
    return json(500, { error: "Server error: " + String(e && e.message || e) });
  }
}

// ------------------------------------------------------------------ relay phone

async function ingest(ctx, body) {
  const items = Array.isArray(body.items) ? body.items.slice(0, 50) : [];
  const replyable = body.replyable && typeof body.replyable === "object" ? body.replyable : {};
  const reads = body.reads && typeof body.reads === "object" ? body.reads : {};
  const now = Date.now();

  // Items come from Messages' notifications ("notif") or from the phone's message store ("store"). Either can be
  // outgoing (sent from the relay phone itself), already read there, or part of a full-history sync. A store item can
  // ask whether its attachment is still needed (wantMedia) before the relay phone uploads it.
  const entries = [];
  const byKey = new Map();
  for (const it of items) {
    if (!it || typeof it !== "object") continue;
    const conv = convId(it.conv);
    if (!conv) continue;
    const ts = Number.isFinite(it.ts) && it.ts > 0 ? Math.floor(it.ts) : now;
    const fp = typeof it.fp === "string" && /^[a-f0-9]{8,32}$/i.test(it.fp) ? it.fp.toLowerCase() : rid();
    const k = "m:" + pad13(ts) + ":" + fp;
    const out = it.dir === "out";
    const value = {
      k, conv, dir: out ? "out" : "in",
      status: out ? (it.status === "failed" ? "failed" : "sent") : "received",
      sender: out ? "" : str(it.sender, 120),
      name: str(it.name, 120),
      group: !!it.group,
      text: str(it.text, 4000),
      ts,
      media: mediaOf(conv, it.media),
      src: it.src === "store" ? "store" : "notif",
      receivedAt: now
    };
    if (out) { value.via = "phone"; value.sentAt = ts; }
    if (it.history) value.history = true;
    if (!out && (it.history || it.read)) value.read = true;
    const entry = { v: value, want: !!it.wantMedia && !!value.media, result: { fp, k } };
    const first = byKey.get(k);
    if (first) entry.same = first;
    else byKey.set(k, entry);
    entries.push(entry);
  }

  // A message the server already holds (a retry after a lost reply, or the relay phone sending everything again) is
  // not news: no second unread count, no second notification. Deterministic keys make the check exact.
  const keys = [...byKey.keys()];
  const held = new Map();
  for (let i = 0; i < keys.length; i += 25) {
    const r = await ctx.data.batchGet(MSG, keys.slice(i, i + 25));
    for (const x of r.items) if (x.found && x.value) held.set(x.key, x.value);
  }
  // A copy that folded into another message before is known by its own key too, however long ago that was.
  const aliasTo = new Map();
  const missing = keys.filter(k => !held.has(k));
  for (let i = 0; i < missing.length; i += 25) {
    const r = await ctx.data.batchGet(TWINS, missing.slice(i, i + 25));
    for (const x of r.items) if (x.found && x.value && typeof x.value.to === "string") aliasTo.set(x.key, x.value.to);
  }
  const targetKeys = [...new Set(aliasTo.values())].filter(k => !held.has(k));
  const targets = new Map();
  for (let i = 0; i < targetKeys.length; i += 25) {
    const r = await ctx.data.batchGet(MSG, targetKeys.slice(i, i + 25));
    for (const x of r.items) if (x.found && x.value) targets.set(x.key, x.value);
  }

  // The same message can also arrive twice under different keys: from Messages' notification and from the message
  // store, or as a reply sent from Texts plus the copies the notification and the store show of it. Each later copy
  // folds into the first; the first remembers which routes it has been seen through, and the keys of the copies.
  const near = nearby(ctx, now);
  for (const v of held.values()) near.add(v);
  for (const v of targets.values()) near.add(v);
  const fresh = [];
  const changed = new Map();   // stored messages that absorbed a copy or gained an attachment
  const visible = new Map();   // of those, the ones whose attachment changed, for the arrival log
  const aliases = [];
  let folded = 0, known = 0;
  for (const entry of byKey.values()) {
    const v = entry.v, res = entry.result;
    let target = held.get(v.k) || (aliasTo.has(v.k) ? near.get(aliasTo.get(v.k)) : null);
    if (target) {
      res.status = "known";
    } else {
      const around = await near.of(v);
      target = around.find(y => Array.isArray(y.twins) && y.twins.includes(v.k)) || null;
      if (target) {
        res.status = "known";
      } else {
        target = findTwin(v, around);
        if (target) {
          target.from = [...new Set((target.from || [srcOf(target)]).concat(v.src))];
          target.twins = (target.twins || []).concat(v.k).slice(-8);
          changed.set(target.k, target);
          aliases.push({ key: v.k, value: { to: target.k, conv: target.conv } });
          res.status = "folded";
        }
      }
    }
    if (!target) {
      fresh.push(v);
      near.add(v);
      res.status = "stored";
      if (entry.want && !(v.media && v.media.path)) res.needMedia = true;
      continue;
    }
    if (res.status === "known") known++; else folded++;
    res.k = target.k;
    if (v.media && v.media.path && !(target.media && target.media.path)) {
      target.media = v.media;
      changed.set(target.k, target);
      visible.set(target.k, target);
      res.media = "attached";
    }
    if (entry.want && !(target.media && target.media.path)) res.needMedia = true;
  }
  const writes = new Map();
  for (const v of fresh) writes.set(v.k, v);
  for (const v of changed.values()) writes.set(v.k, v);
  const toWrite = [...writes.values()];
  for (let i = 0; i < toWrite.length; i += 10) {
    await ctx.data.batchPut(MSG, toWrite.slice(i, i + 10).map(v => ({ key: v.k, value: v })));
  }
  for (let i = 0; i < aliases.length; i += 25) await ctx.data.batchPut(TWINS, aliases.slice(i, i + 25));

  const latestByConv = new Map();
  const unreadByConv = new Map();
  for (const v of fresh) {
    if (v.dir === "in" && !v.read) unreadByConv.set(v.conv, (unreadByConv.get(v.conv) || 0) + 1);
    const prev = latestByConv.get(v.conv);
    if (!prev || v.ts >= prev.ts) latestByConv.set(v.conv, v);
  }
  for (const [conv, last] of latestByConv) {
    await updateConv(ctx, conv, existing => {
      const newer = !existing || last.ts >= (existing.lastTs || 0);
      return {
        conv,
        name: (newer || !existing) ? (last.name || (existing && existing.name) || last.sender || "Unknown") : (existing.name || last.name || "Unknown"),
        group: last.group || !!(existing && existing.group),
        lastTs: newer ? last.ts : existing.lastTs,
        lastText: newer ? (last.text || mediaLabel(last.media)) : existing.lastText,
        lastDir: newer ? last.dir : existing.lastDir,
        lastSender: newer ? last.sender : existing.lastSender,
        canReply: conv in replyable ? !!replyable[conv] : (existing ? existing.canReply !== false : true),
        unread: ((existing && existing.unread) || 0) + (unreadByConv.get(conv) || 0),
        updatedAt: now
      };
    });
  }

  // Reply handles can appear or vanish without a new message (phone restart, Messages closing a notification), and a
  // conversation read on the relay phone itself is read here too.
  const touched = new Set(Object.keys(replyable).concat(Object.keys(reads)));
  for (const raw of touched) {
    const conv = convId(raw);
    if (!conv) continue;
    const wrote = await updateConv(ctx, conv, existing => {
      if (!existing) return null;
      let change = false;
      if (raw in replyable && !latestByConv.has(conv) && (existing.canReply !== false) !== !!replyable[raw]) {
        existing.canReply = !!replyable[raw];
        change = true;
      }
      // Read on the relay phone up to its newest message. MMS times there are whole seconds, so allow a little.
      const readTs = Number(reads[raw]);
      if (Number.isFinite(readTs) && existing.unread && (existing.lastTs || 0) <= readTs + READ_SLACK_MS) {
        existing.unread = 0;
        change = true;
      }
      if (!change) return null;
      existing.updatedAt = now;
      return existing;
    });
    if (wrote && !latestByConv.has(conv)) latestByConv.set(conv, null);
  }

  // The arrival log gets every new message and every attachment that arrived late. Full-history messages are logged
  // once per conversation instead: an inbox device refreshes that conversation rather than receiving each old message.
  const logged = fresh.concat([...visible.values()].filter(v => !fresh.includes(v)));
  const live = logged.filter(v => !v.history);
  const historyConvs = [...new Set(logged.filter(v => v.history).map(v => v.conv))];
  await feedAdd(ctx, live.concat(historyConvs.map(conv => ({ k: "t:" + conv, conv, touch: true }))));
  await notify(ctx, fresh.filter(v => v.dir === "in" && !v.read));
  if (logged.length || latestByConv.size) {
    const lastKey = live.length ? live[live.length - 1].k : null;
    await doorbell(ctx, "inbox", "messages", { k: lastKey, convs: [...new Set([...latestByConv.keys()].concat(logged.map(v => v.conv)))] });
  }
  return json(200, {
    ok: true, stored: fresh.length, folded, known, serverTime: now,
    results: entries.map(e => e.same ? Object.assign({}, e.same.result) : e.result)
  });
}

/** Read, change and write one conversation's summary; if another request wrote it meanwhile, again from a fresh read. */
async function updateConv(ctx, conv, change) {
  for (let attempt = 0; ; attempt++) {
    const cur = await ctx.data.getWithMeta(CONV, conv);
    const next = change(cur ? cur.value : null);
    if (!next) return null;
    try {
      await ctx.data.put(CONV, conv, next, cur ? { ifVersion: cur.version } : { ifNotExists: true });
      return next;
    } catch (e) {
      if (!(e && e.name === "ConflictError") || attempt >= 5) throw e;
    }
  }
}

const READ_SLACK_MS = 2000;

/**
 * Stored messages close in time to the ones arriving, found by message time rather than by conversation, so a copy
 * from a full-history sync finds its twin however old it is. One request reads each stretch of time once, and every
 * message it holds is one object, so changes made while matching are never lost.
 */
function nearby(ctx, now) {
  const RANGE = 10 * 60000;   // the widest twin window
  const ranges = [];          // { lo, hi } already read
  const all = new Map();      // key -> value
  const recent = new Map();   // conv -> the newest messages, read once, for replies queued long before they went out
  const own = (v) => {
    const have = all.get(v.k);
    if (have) return have;
    all.set(v.k, v);
    return v;
  };
  async function read(lo, hi) {
    let cursor = null;
    let covered = hi;
    for (let page = 0; page < 3; page++) {
      const r = await ctx.data.list(MSG, {
        keyPrefix: "m:", startAfter: "m:" + pad13(lo), endBefore: "m:" + pad13(hi + 1), order: "asc", limit: 100, cursor
      });
      for (const i of r.items) if (i.value && i.value.k) own(i.value);
      cursor = r.cursor;
      if (!cursor) break;
      if (page === 2) covered = r.items.length ? r.items[r.items.length - 1].value.ts - 1 : lo;
    }
    ranges.push({ lo, hi: Math.max(lo, covered) });
  }
  return {
    add(v) { own(v); },
    get(k) { return all.get(k) || null; },
    async of(v) {
      const lo = v.ts - RANGE, hi = v.ts + RANGE;
      if (!ranges.some(r => r.lo <= lo && r.hi >= hi)) await read(lo, hi + 30 * 60000);
      const out = [];
      for (const y of all.values()) if (y.conv === v.conv && y.k !== v.k && y.ts >= lo && y.ts <= hi) out.push(y);
      if (v.dir === "out" && now - v.ts < 2 * 86400000) {
        if (!recent.has(v.conv)) {
          const list = [];
          let cursor = null;
          for (let page = 0; page < 2; page++) {
            const r = await ctx.data.query(MSG, "by-conv", { eq: v.conv, order: "desc", limit: 100, cursor });
            for (const i of r.items) if (i.value && i.value.k) list.push(own(i.value));
            cursor = r.cursor;
            if (!cursor) break;
          }
          recent.set(v.conv, list);
        }
        for (const y of recent.get(v.conv)) if (y.k !== v.k && y.dir === "out" && !out.includes(y)) out.push(y);
      }
      return out;
    }
  };
}

/**
 * The stored message that is the same as v, arrived by another route: same direction and text (a picture without
 * text matches a picture), close in time, and not yet seen through v's route. A reply queued in Texts matches any time
 * after it was queued, since the relay phone may have been offline; one already sent matches on when it went out.
 */
function findTwin(v, candidates) {
  const text = matchText(v);
  if (!text) return null;
  let best = null;
  let bestGap = Infinity;
  for (const y of candidates) {
    if (!y || y.k === v.k || y.dir !== v.dir || y.touch) continue;
    if ((y.from || [srcOf(y)]).includes(v.src)) continue;
    if (matchText(y) !== text) continue;
    let gap;
    if (y.dir === "out" && srcOf(y) === "texts" && y.status === "queued") {
      if (v.ts < y.ts - 60000 || v.ts - y.ts > 2 * 86400000) continue;
      gap = v.ts - y.ts;
    } else {
      const at = y.dir === "out" ? (y.sentAt || y.ts) : y.ts;
      gap = Math.abs(at - v.ts);
      if (gap > (v.dir === "out" ? 10 * 60000 : 3 * 60000)) continue;
    }
    if (gap < bestGap) {
      best = y;
      bestGap = gap;
    }
  }
  return best;
}

function srcOf(m) {
  return m.src || (m.dir === "out" ? "texts" : "notif");
}

function normText(t) {
  return String(t || "").normalize("NFKC").replace(/[​-‍﻿]/g, "").replace(/\s+/g, " ").trim();
}

const MEDIA_WORD = /^(?:sent an? )?(photo|picture|image|sticker|gif|video|voice message|audio|attachment|file)$/i;

/** What two copies of a message must share: the text, or for an attachment without text, the kind of attachment. */
function matchText(m) {
  const t = normText(m.text);
  const word = MEDIA_WORD.exec(t.replace(/^[^\p{L}\p{N}]+/u, ""));
  if (word) {
    const w = word[1].toLowerCase();
    return "\u0000" + (w === "video" ? "Video" : w === "voice message" || w === "audio" ? "Voice message"
      : w === "attachment" || w === "file" ? "Attachment" : "Picture");
  }
  if (!t && m.media) return "\u0000" + mediaLabel(m.media);
  return t;
}

/**
 * Which conversations already hold these messages (by time, direction and text), for a relay phone deciding where a
 * thread from its message store belongs. One list of conversations per probe.
 */
async function locate(ctx, body) {
  const probes = Array.isArray(body.probes) ? body.probes.slice(0, 10) : [];
  const out = [];
  for (const p of probes) {
    const ts = p && Number.isFinite(p.ts) ? Math.floor(p.ts) : 0;
    const dir = p && p.dir === "out" ? "out" : "in";
    const text = ts ? matchText({ text: str(p.text, 4000), media: p.mime ? { mime: str(p.mime, 60) } : null }) : "";
    if (!text) { out.push([]); continue; }
    const win = dir === "out" ? 10 * 60000 : 3 * 60000;
    const convs = new Set();
    let cursor = null;
    for (let page = 0; page < 2; page++) {
      const r = await ctx.data.list(MSG, {
        keyPrefix: "m:", startAfter: "m:" + pad13(ts - win), endBefore: "m:" + pad13(ts + win + 1), order: "asc", limit: 100, cursor
      });
      for (const i of r.items) {
        const y = i.value;
        if (y && y.dir === dir && matchText(y) === text) convs.add(y.conv);
      }
      cursor = r.cursor;
      if (!cursor) break;
    }
    out.push([...convs]);
  }
  return json(200, { convs: out });
}

/**
 * The relay phone found that two conversations are one (a thread its message store brought in before any of its
 * notifications showed whose it was): every message of `from` moves to `into`, copies of the same message fold, and
 * inbox devices reload. Done in slices that fit the function's time limit: `more` means call again. Safe to repeat.
 */
async function merge(ctx, body) {
  const from = convId(body.from);
  const into = convId(body.into);
  if (!from || !into || from === into) throw bad("from and into required");
  const target = await ctx.data.get(CONV, into);
  if (!target) return json(404, { error: "unknown conversation" });
  const started = Date.now();
  const near = nearby(ctx, started);
  let moved = 0, folded = 0;
  // Each pass takes the first page again: what was handled has left `from`.
  while (Date.now() - started < MERGE_BUDGET_MS) {
    const r = await ctx.data.query(MSG, "by-conv", { eq: from, order: "asc", limit: 25 });
    if (!r.items.length) break;
    for (const i of r.items) {
      const v = i.value;
      if (!v || !v.k) continue;
      const as = Object.assign({}, v, { conv: into, src: srcOf(v) });
      const around = await near.of(as);
      // Already folded in by an earlier try that stopped before removing it: just remove it now.
      const owner = around.find(y => Array.isArray(y.twins) && y.twins.includes(v.k)) || null;
      const twin = owner || findTwin(as, around);
      if (twin) {
        if (!owner) {
          twin.from = [...new Set((twin.from || [srcOf(twin)]).concat(v.from || [srcOf(v)]))];
          twin.twins = (twin.twins || []).concat(v.k, v.twins || []).slice(-8);
          if (v.media && v.media.path && !(twin.media && twin.media.path)) twin.media = v.media;
          await ctx.data.put(MSG, twin.k, twin);
        }
        const copies = [...new Set([v.k].concat(v.twins || []))].slice(0, 25);
        await ctx.data.batchPut(TWINS, copies.map(k => ({ key: k, value: { to: twin.k, conv: into } })));
        await ctx.data.delete(MSG, v.k);
        folded++;
      } else {
        v.conv = into;
        v.name = target.name;
        v.group = !!target.group;
        await ctx.data.put(MSG, v.k, v);
        near.add(v);
        moved++;
      }
    }
  }
  const rest = await ctx.data.query(MSG, "by-conv", { eq: from, limit: 1 });
  if (rest.items.length) return json(200, { ok: true, moved, folded, more: true });

  const source = await ctx.data.get(CONV, from);
  if (source) {
    await updateConv(ctx, into, t => {
      if (!t) return null;
      if ((source.lastTs || 0) > (t.lastTs || 0)) {
        t.lastTs = source.lastTs;
        t.lastText = source.lastText;
        t.lastDir = source.lastDir;
        t.lastSender = source.lastSender;
      }
      t.unread = (t.unread || 0) + (source.unread || 0);
      t.updatedAt = Date.now();
      return t;
    });
    await ctx.data.delete(CONV, from);
  }
  const epoch = (await epochOf(ctx)) + 1;
  await ctx.data.put(SET, "epoch", { n: epoch, at: Date.now() });
  await doorbell(ctx, "inbox", "reload", { epoch });
  return json(200, { ok: true, moved, folded, more: false, epoch });
}

/** How long one merge call works before handing back with `more`, well inside the function's 10-second limit. */
const MERGE_BUDGET_MS = 4500;

async function epochOf(ctx) {
  const e = await ctx.data.get(SET, "epoch");
  return e && Number.isFinite(e.n) ? e.n : 0;
}

async function outbox(ctx) {
  const page = await ctx.data.query(MSG, "by-status", { eq: "queued", order: "asc", limit: 20 });
  const items = page.items.map(i => ({ id: i.key, conv: i.value.conv, text: i.value.text, ts: i.value.ts }));
  const viewerActive = await isViewerActive(ctx);
  return json(200, Object.assign({ items, serverTime: Date.now(), pollSeconds: viewerActive || items.length ? 5 : 20 }, await wakeOf(ctx)));
}

/**
 * The latest request from an inbox device for the relay to check again: "refresh" (re-read Messages and the last two
 * days of the message store), "resync" (the last week), or "reconnect" (also reopen its connections). The relay acts
 * once on anything newer than it last handled.
 */
async function wakeOf(ctx) {
  const w = await ctx.data.get(SET, "relayWake");
  return w && Number.isFinite(w.at) ? { wake: w.at, wakeKind: w.kind || "reconnect" } : { wake: 0 };
}

async function relayWake(ctx, body) {
  const now = Date.now();
  const kind = body && (body.kind === "refresh" || body.kind === "resync") ? body.kind : "reconnect";
  await ctx.data.put(SET, "relayWake", { at: now, kind });
  // Instant when the relay's socket is up; otherwise its next poll or heartbeat carries the request.
  await doorbell(ctx, "outbox", "reconnect", { at: now, kind });
  const relay = await ctx.data.get(DEV, "relay");
  return json(200, { ok: true, at: now, kind, relay });
}

async function outboxResult(ctx, body) {
  const id = typeof body.id === "string" && body.id.startsWith("m:") ? body.id : null;
  if (!id) throw bad("id required");
  const cur = await ctx.data.getWithMeta(MSG, id);
  if (!cur) return json(404, { error: "unknown message" });
  const v = cur.value;
  const status = body.status === "sent" ? "sent" : "failed";
  v.status = status;
  v.sentAt = Date.now();
  v.via = body.via === "sms" ? "sms" : "rcs";   // how the relay device sent it: through Messages' reply, or as plain SMS
  if (status === "failed") v.error = str(body.error, 300) || "Could not send";
  else delete v.error;
  try {
    await ctx.data.put(MSG, id, v, { ifVersion: cur.version });
  } catch (e) {
    if (e && e.name === "ConflictError") await ctx.data.put(MSG, id, v);
    else throw e;
  }
  // The phone knows whether it still holds a reply handle for this conversation.
  if (typeof body.canReply === "boolean") {
    await updateConv(ctx, v.conv, c => {
      if (!c || (c.canReply !== false) === body.canReply) return null;
      c.canReply = body.canReply;
      c.updatedAt = Date.now();
      return c;
    });
  }
  await feedAdd(ctx, [v]);
  await doorbell(ctx, "inbox", "status", { k: id, status, via: v.via });
  return json(200, { ok: true });
}

async function heartbeat(ctx, body) {
  const now = Date.now();
  const rec = {
    lastSeen: now,
    listening: !!body.listening,
    replyable: num(body.replyable),
    lastRelayed: num(body.lastRelayed),
    battery: num(body.battery),
    charging: !!body.charging,
    version: str(body.version, 40),
    model: str(body.model, 80),
    note: str(body.note, 300),
    queued: num(body.queued),
    listenerConnected: body.listenerConnected !== false
  };
  const prev = await ctx.data.get(DEV, "relay");
  if (prev && prev.offlineSince) {
    // Back after an outage the watch reported: say so once.
    await pushAll(ctx, { title: "Relay phone is back", body: "Texts are flowing again.", tag: "relay-status", url: "/" });
    await doorbell(ctx, "inbox", "relay", { online: true });
  }
  if (Number.isFinite(body.wakeHandled) && body.wakeHandled > 0) rec.wakeHandled = Math.floor(body.wakeHandled);
  const store = storeOf(body.store);
  if (store) rec.store = store;
  const tag = tagOf(body.device);
  if (tag && roleOf(ctx) === "relay") {
    rec.device = tag;
    if (!prev || prev.device !== tag) await rememberPhone(ctx, "relay", tag, now);   // this phone, for after a reinstall
  }
  await ctx.data.put(DEV, "relay", rec);
  const viewerActive = await isViewerActive(ctx);
  const queued = await ctx.data.query(MSG, "by-status", { eq: "queued", limit: 1 });
  return json(200, Object.assign({ ok: true, serverTime: now, pollSeconds: viewerActive || queued.items.length ? 5 : 20 }, await wakeOf(ctx)));
}

/** What the relay device reports about reading its message store: allowed or not, last check, full-history progress. */
function storeOf(s) {
  if (!s || typeof s !== "object") return null;
  const h = s.history && typeof s.history === "object" ? s.history : {};
  const state = ["idle", "running", "done", "paused"].includes(h.state) ? h.state : "idle";
  return {
    allowed: !!s.allowed,
    lastScan: num(s.lastScan),
    history: { state, done: num(h.done), total: num(h.total), at: num(h.at), media: !!h.media, note: str(h.note, 200) }
  };
}

// ------------------------------------------------------------------ inbox PWA

async function inbox(ctx) {
  // Taken before anything is read: whatever arrives from here on reaches this device through /api/sync?feed=.
  const feed = "f:" + pad13(Date.now());
  // Every conversation, not just the first page: the list order is by key, so one page could miss recent ones.
  const all = [];
  let cursor = null;
  for (let page = 0; page < 20; page++) {
    const r = await ctx.data.list(CONV, { limit: 100, cursor });
    for (const i of r.items) all.push(i.value);
    cursor = r.cursor;
    if (!cursor) break;
  }
  const items = all.sort((a, b) => (b.lastTs || 0) - (a.lastTs || 0));
  const latest = await ctx.data.list(MSG, { keyPrefix: "m:", order: "desc", limit: 1 });
  const relay = await ctx.data.get(DEV, "relay");
  await touchViewer(ctx);
  return json(200, { items, feed, epoch: await epochOf(ctx), latestKey: latest.items.length ? latest.items[0].key : null, relay, serverTime: Date.now() });
}

async function thread(ctx, q) {
  const conv = convId(q.conv);
  if (!conv) throw bad("conv required");
  const page = await ctx.data.query(MSG, "by-conv", { eq: conv, order: "desc", limit: 50, cursor: q.cursor || null });
  const items = page.items.map(i => i.value).reverse();
  const conversation = await ctx.data.get(CONV, conv);
  return json(200, { items, cursor: page.cursor, conversation });
}

async function sync(ctx, q) {
  const relay = await ctx.data.get(DEV, "relay");
  await touchViewer(ctx);
  if (typeof q.feed === "string" && /^f:[0-9]{13}/.test(q.feed) && q.feed.length <= 200) {
    return json(200, Object.assign(await feedSince(ctx, q.feed), { relay, epoch: await epochOf(ctx), serverTime: Date.now() }));
  }
  // The older form, by message time, for a page or app version from before the arrival log. It misses a text
  // that reaches the server after a newer one (the relay device was offline), which the arrival log does not.
  const after = typeof q.after === "string" && q.after.startsWith("m:") ? q.after : null;
  const page = await ctx.data.list(MSG, { keyPrefix: "m:", startAfter: after, order: "asc", limit: 100 });
  return json(200, { items: page.items.map(i => i.value), cursor: page.cursor, relay, serverTime: Date.now() });
}

/**
 * Everything logged after the cursor, oldest first, plus a re-read of the few seconds just before it. Each message
 * appears once, in its latest form, with the current summary of every conversation it touches (the server's unread
 * counts, so a device never has to count for itself). `more` means call again with the returned cursor.
 */
async function feedSince(ctx, cursor) {
  // Older than the log keeps: what happened in between is gone from it, so the device reloads everything instead.
  if (Date.now() - Number(cursor.slice(2, 15)) > FEED_TTL_SECONDS * 1000 - 6 * 3600000) {
    return { items: [], convs: [], cursor: "f:" + pad13(Date.now()), more: false, reset: true };
  }
  const latest = new Map();
  const late = await ctx.data.list(FEED, {
    keyPrefix: "f:", startAfter: "f:" + pad13(Number(cursor.slice(2, 15)) - FEED_SKEW_MS), endBefore: cursor, order: "desc", limit: 50
  });
  for (const i of late.items.reverse()) if (i.value && i.value.k) latest.set(i.value.k, i.value);
  let next = cursor;
  let more = false;
  let page = { cursor: null };
  for (let n = 0; n < 3; n++) {
    page = await ctx.data.list(FEED, { keyPrefix: "f:", startAfter: cursor, order: "asc", limit: 100, cursor: page.cursor });
    for (const i of page.items) {
      if (i.value && i.value.k) { latest.delete(i.value.k); latest.set(i.value.k, i.value); }
      next = i.key;
    }
    more = !!page.cursor;
    if (!more) break;
  }
  // A conversation that a full-history sync filled in is logged once, not per message: devices refresh it.
  const reload = [...new Set([...latest.values()].filter(v => v.touch).map(v => v.conv))];
  const items = [...latest.values()].filter(v => !v.touch);
  const convIds = [...new Set(items.map(m => m.conv).concat(reload).filter(Boolean))];
  const convs = [];
  for (let i = 0; i < convIds.length; i += 25) {
    const r = await ctx.data.batchGet(CONV, convIds.slice(i, i + 25));
    for (const x of r.items) if (x.found && x.value) convs.push(x.value);
  }
  return { items, convs, reload, cursor: next, more };
}

async function send(ctx, body) {
  const conv = convId(body.conv);
  const text = (typeof body.text === "string" ? body.text : "").trim().slice(0, 2000);
  if (!conv || !text) throw bad("conv and text required");
  const c = await ctx.data.get(CONV, conv);
  if (!c) return json(404, { error: "unknown conversation" });
  const now = Date.now();
  const k = "m:" + pad13(now) + ":o" + rid();
  const value = { k, conv, dir: "out", status: "queued", text, ts: now, name: c.name, group: !!c.group, createdAt: now, src: "texts" };
  await ctx.data.put(MSG, k, value);
  await updateConv(ctx, conv, cur => {
    if (!cur) return null;
    cur.lastTs = now; cur.lastText = text; cur.lastDir = "out"; cur.updatedAt = now;
    return cur;
  });
  await feedAdd(ctx, [value]);
  await touchViewer(ctx, true);
  await doorbell(ctx, "outbox", "queued", { id: k, conv });
  return json(200, { id: k, canReply: c.canReply !== false });
}

async function markRead(ctx, body) {
  const conv = convId(body.conv);
  if (!conv) throw bad("conv required");
  await updateConv(ctx, conv, c => {
    if (!c || !c.unread) return null;
    c.unread = 0;
    return c;
  });
  return json(200, { ok: true });
}

async function status(ctx) {
  const relay = await ctx.data.get(DEV, "relay");
  const queued = await ctx.data.query(MSG, "by-status", { eq: "queued", limit: 20 });
  return json(200, { relay, queued: queued.items.length, serverTime: Date.now() });
}

async function pushRegister(ctx, body) {
  const id = typeof body.subscriptionId === "string" && /^apppush_[A-Za-z0-9_-]{4,80}$/.test(body.subscriptionId) ? body.subscriptionId : null;
  if (!id) throw bad("subscriptionId required");
  const cur = (await ctx.data.get(SET, "pushSubs")) || { ids: [] };
  const ids = [id].concat((cur.ids || []).filter(x => x !== id)).slice(0, 10);
  await ctx.data.put(SET, "pushSubs", { ids, updatedAt: Date.now() });
  return json(200, { ok: true, count: ids.length });
}

async function pushTest(ctx) {
  const cur = (await ctx.data.get(SET, "pushSubs")) || { ids: [] };
  if (!cur.ids || !cur.ids.length) return json(409, { error: "No device has turned on notifications yet." });
  if (!ctx.push) return json(503, { error: "Push is not enabled for this app." });
  const r = await ctx.push.sendToVisitorSubscriptions(cur.ids, {
    title: "Text Relay", body: "Notifications are working on this device.", url: "/", tag: "relay-test"
  });
  return json(200, { ok: true, result: r });
}

// ------------------------------------------------------------------ linking a device
// The relay device is the only one that makes codes. "Link a device" on the relay registers a code for an inbox
// device (role inbox) and shows it as a QR code, which Texts scans. A relay device that is not linked yet shows its own
// code; a linked inbox device scans it and registers it here (role relay), and the relay, waiting at POST /pair, is
// let in. Only the SHA-256 of a code is stored, it works once, for its role, and the newest code of a role replaces the
// one before it. See pair.js.

const RELAY_KEY_NAME = "relay-phone";

async function pairCodeAdd(ctx, body) {
  const hash = typeof body.hash === "string" && /^[a-f0-9]{64}$/.test(body.hash) ? body.hash : null;
  const role = body.role === "relay" ? "relay" : body.role === "inbox" ? "inbox" : null;
  if (!hash || !role) throw bad("hash (64 hex) and role (relay or inbox) required");
  const fromRelay = !!(ctx.principal && ctx.principal.name === RELAY_KEY_NAME);
  if (role === "inbox" && !fromRelay) return json(403, { error: "Only the relay phone makes codes for linking a phone." });
  if (role === "relay" && fromRelay) return json(403, { error: "A relay phone can't let in another relay phone." });
  const minutes = Math.max(5, Math.min(60, Number.isFinite(body.ttlMinutes) ? Math.floor(body.ttlMinutes) : 15));
  const pairing = await ctx.data.get(SET, "pairing");
  if (!pairing) return json(503, { error: "Linking is not set up on the server yet." });
  const now = Date.now();
  const codes = (Array.isArray(pairing.codes) ? pairing.codes : []).filter(c => c && c.expiresAt > now && c.role !== role);
  codes.push({ hash, role, createdAt: now, expiresAt: now + minutes * 60000 });
  pairing.codes = codes;
  await ctx.data.put(SET, "pairing", pairing);
  return json(200, { ok: true, role, expiresAt: now + minutes * 60000 });
}

// Phones the inbox has linked, for letting Relay or Texts back in after a reinstall without a code (see pair.js):
// settings:phones = { relay: [{ tag, at }], inbox: [{ tag, at }] }. A linked app reports its phone's tag (Relay in its
// heartbeat, Texts once each time it opens), and Texts drops its own when it is unlinked on purpose.

const PHONES = "phones";
const MAX_PHONES = 5;

function tagOf(v) {
  return typeof v === "string" && /^[a-f0-9]{64}$/.test(v) ? v : null;
}

function roleOf(ctx) {
  return ctx.principal && ctx.principal.name === RELAY_KEY_NAME ? "relay" : "inbox";
}

async function deviceRemember(ctx, body) {
  const tag = tagOf(body.device);
  if (!tag) throw bad("device (64 hex) required");
  await rememberPhone(ctx, roleOf(ctx), tag, Date.now());
  return json(200, { ok: true });
}

async function deviceForget(ctx, body) {
  const tag = tagOf(body.device);
  if (!tag) throw bad("device (64 hex) required");
  const role = roleOf(ctx);
  let forgot = false;
  await changePhones(ctx, phones => {
    const list = Array.isArray(phones[role]) ? phones[role] : [];
    const kept = list.filter(p => p && p.tag !== tag);
    forgot = kept.length !== list.length;
    phones[role] = kept;
    return forgot;
  });
  return json(200, { ok: true, forgot });
}

async function rememberPhone(ctx, role, tag, now) {
  await changePhones(ctx, phones => {
    const list = Array.isArray(phones[role]) ? phones[role] : [];
    const old = list.find(p => p && p.tag === tag);
    if (old && now - (old.at || 0) < 86400000 && list[0] === old) return false;   // already the newest, seen today
    phones[role] = [{ tag, at: now }].concat(list.filter(p => p && p.tag !== tag)).slice(0, MAX_PHONES);
    return true;
  });
}

/** Read, change and write settings:phones with a version check; `change` returns false when there is nothing to write. */
async function changePhones(ctx, change) {
  for (let attempt = 0; ; attempt++) {
    const cur = await ctx.data.getWithMeta(SET, PHONES);
    const phones = cur && cur.value && typeof cur.value === "object" ? cur.value : {};
    if (!change(phones)) return;
    try {
      await ctx.data.put(SET, PHONES, phones, cur ? { ifVersion: cur.version } : { ifNotExists: true });
      return;
    } catch (e) {
      if (!(e && e.name === "ConflictError") || attempt >= 5) throw e;
    }
  }
}

// ------------------------------------------------------------------ media
// Originals are stored untouched under /media/<conv>/ (runtime blob storage). Small uploads come in as a raw
// POST body; anything over the inline limit goes through an upload ticket straight to storage. The web app
// gets short-lived signed URLs from GET /api/media and loads pictures directly from them.

const MEDIA_RE = /^\/media\/[a-z0-9_-]{4,64}\/[0-9]{13}-[a-f0-9]{8,32}-[op]\.[a-z0-9]{2,5}$/i;
const DIRECT_MAX = 4 * 1024 * 1024;
const TICKET_MAX = 25 * 1024 * 1024;

/**
 * The attachment types the inbox can store: Hedy's file storage takes only these, so anything else (HEIC pictures,
 * 3GPP video, AMR voice messages) is refused up front as a 400, which the relay phone takes as final. The message
 * still arrives, with its attachment named for what it was. Relay turns HEIC and HEIF pictures into JPEG first.
 */
function mediaExt(mime) {
  let m = String(mime || "").toLowerCase().split(";")[0].trim();
  if (m === "image/jpg") m = "image/jpeg";
  const map = {
    "image/jpeg": "jpg", "image/png": "png", "image/gif": "gif", "image/webp": "webp",
    "video/mp4": "mp4", "video/webm": "webm", "audio/mpeg": "mp3", "audio/mp4": "m4a", "audio/webm": "weba",
    "application/pdf": "pdf"
  };
  return map[m] ? { mime: m, ext: map[m] } : null;
}

function mediaPath(conv, ts, fp, kind, ext) {
  return "/media/" + conv + "/" + pad13(ts) + "-" + fp + "-" + (kind === "preview" ? "p" : "o") + "." + ext;
}

function mediaMeta(ctx) {
  const h = ctx.request.headers || {};
  const get = (n) => str(h[n] != null ? h[n] : h[n.toLowerCase()] != null ? h[n.toLowerCase()] : h[n.toUpperCase()], 200);
  const conv = convId(get("x-relay-conv"));
  const fpRaw = get("x-relay-fp");
  const fp = /^[a-f0-9]{8,32}$/i.test(fpRaw) ? fpRaw.toLowerCase() : null;
  const ts = Number(get("x-relay-ts"));
  const kind = get("x-relay-kind") === "preview" ? "preview" : "original";
  const type = mediaExt(get("content-type"));
  if (!conv || !fp || !Number.isFinite(ts) || ts <= 0) throw bad("x-relay-conv, x-relay-fp and x-relay-ts headers are required");
  if (!type) throw bad("unsupported media type");
  return { conv, fp, ts: Math.floor(ts), kind, mime: type.mime, path: mediaPath(conv, Math.floor(ts), fp, kind, type.ext) };
}

async function mediaUpload(ctx) {
  const meta = mediaMeta(ctx);
  const bytes = await ctx.request.readBytes();
  if (!bytes || !bytes.byteLength) throw bad("empty body");
  if (bytes.byteLength > DIRECT_MAX) return json(413, { error: "Too large for a direct upload; use a ticket.", max: DIRECT_MAX });
  await ctx.files.put(meta.path, bytes.base64, { contentType: meta.mime });
  return json(200, { ok: true, path: meta.path, bytes: bytes.byteLength, kind: meta.kind });
}

async function mediaTicket(ctx, body) {
  const conv = convId(body.conv);
  const fp = typeof body.fp === "string" && /^[a-f0-9]{8,32}$/i.test(body.fp) ? body.fp.toLowerCase() : null;
  const ts = Number.isFinite(body.ts) && body.ts > 0 ? Math.floor(body.ts) : null;
  const type = mediaExt(body.mime);
  const size = Number.isFinite(body.bytes) ? body.bytes : 0;
  if (!conv || !fp || !ts || !type) throw bad("conv, fp, ts and a supported mime are required");
  if (size > TICKET_MAX) return json(413, { error: "Larger than " + TICKET_MAX + " bytes.", max: TICKET_MAX });
  if (size <= 0) throw bad("bytes (the exact file size) is required");
  const path = mediaPath(conv, ts, fp, body.kind === "preview" ? "preview" : "original", type.ext);
  // The ticket's signed URL fixes content-length at maxBytes, so maxBytes must be the exact size of the upload.
  const ticket = await ctx.files.createUploadTicket({ pathPrefix: "/media/", contentType: type.mime, maxBytes: size, ttlSeconds: 900 });
  return json(200, { ok: true, path, ticket: ticket.ticket, uploadUrl: ticket.uploadUrl, headers: ticket.headers || {} });
}

async function mediaComplete(ctx, body) {
  const path = typeof body.path === "string" && MEDIA_RE.test(body.path) ? body.path : null;
  const ticket = typeof body.ticket === "string" && body.ticket.length < 4096 ? body.ticket : null;
  if (!path || !ticket) throw bad("ticket and path are required");
  const stored = await ctx.files.completeUploadTicket(ticket, { path });
  return json(200, { ok: true, path, bytes: stored && stored.byteLength });
}

async function mediaGet(ctx, q) {
  const p = typeof q.p === "string" ? q.p : "";
  if (!MEDIA_RE.test(p)) throw bad("bad media path");
  const signed = await ctx.files.signedUrl(p, { ttlSeconds: 3600 });
  if (!signed || !signed.url) return json(404, { error: "no such file" });
  return json(200, { url: signed.url, expiresIn: 3600 });
}

/** Accepts what the relay says about an attachment, keeping only paths that belong to this conversation. */
function mediaOf(conv, m) {
  if (!m || typeof m !== "object" || !m.mime) return null;
  const out = { mime: str(m.mime, 60) };
  const own = (p) => typeof p === "string" && MEDIA_RE.test(p) && p.startsWith("/media/" + conv + "/") ? p : null;
  const path = own(m.path);
  const preview = own(m.preview);
  if (path) out.path = path;
  if (preview) out.preview = preview;
  if (Number.isFinite(m.w) && Number.isFinite(m.h) && m.w > 0 && m.h > 0) { out.w = Math.floor(m.w); out.h = Math.floor(m.h); }
  if (Number.isFinite(m.bytes) && m.bytes > 0) out.bytes = Math.floor(m.bytes);
  return out;
}

function mediaLabel(m) {
  if (!m || !m.mime) return "";
  const t = String(m.mime).toLowerCase();
  if (t.startsWith("image/")) return "Picture";
  if (t.startsWith("video/")) return "Video";
  if (t.startsWith("audio/")) return "Voice message";
  return "Attachment";
}

// ------------------------------------------------------------------ helpers

/** Logs messages that were just stored or changed. Stamped as it is written, so the log reads in arrival order. */
async function feedAdd(ctx, values) {
  if (!values.length) return;
  const at = pad13(Date.now());
  const entries = values.map(v => ({ key: "f:" + at + ":" + v.k, value: v, ttlSeconds: FEED_TTL_SECONDS }));
  for (let i = 0; i < entries.length; i += 25) await ctx.data.batchPut(FEED, entries.slice(i, i + 25));
}

/** A content-free nudge on a realtime channel: subscribers fetch the real data through the key-gated routes. Never fatal. */
async function doorbell(ctx, channel, type, payload) {
  if (!ctx.realtime || typeof ctx.realtime.publish !== "function") return;
  try {
    await ctx.realtime.publish(channel, Object.assign({ at: Date.now() }, payload || {}), { type });
  } catch (e) {
    console.warn("doorbell failed", channel, type, e && e.message);
  }
}

/** One web push to every registered inbox subscription. Never fatal. */
async function pushAll(ctx, msg) {
  if (!ctx.push) return;
  try {
    const cur = (await ctx.data.get(SET, "pushSubs")) || { ids: [] };
    const ids = Array.isArray(cur.ids) ? cur.ids : [];
    if (!ids.length) return;
    await ctx.push.sendToVisitorSubscriptions(ids, msg);
  } catch (e) {
    console.warn("push failed", e && e.message);
  }
}

async function notify(ctx, msgs) {
  if (!msgs.length || !ctx.push) return;
  const cur = (await ctx.data.get(SET, "pushSubs")) || { ids: [] };
  const ids = Array.isArray(cur.ids) ? cur.ids : [];
  if (!ids.length) return;
  let rejected = new Set();
  for (const m of msgs.slice(0, 5)) {
    const title = m.group ? (m.name + (m.sender ? " · " + m.sender : "")) : (m.name || m.sender || "New message");
    const body = (m.text || (m.media ? "Sent a " + mediaLabel(m.media).toLowerCase() : "")).slice(0, 200);
    try {
      const r = await ctx.push.sendToVisitorSubscriptions(ids, {
        title, body, url: "/?conv=" + encodeURIComponent(m.conv), tag: "conv-" + m.conv, idempotencyKey: "msg-" + m.k
      });
      if (r && Array.isArray(r.rejectedRecipients)) for (const x of r.rejectedRecipients) rejected.add(typeof x === "string" ? x : x && x.id);
    } catch (e) {
      console.warn("push failed", String(e && e.message || e));
    }
  }
  if (rejected.size) {
    const keep = ids.filter(id => !rejected.has(id));
    if (keep.length !== ids.length) await ctx.data.put(SET, "pushSubs", { ids: keep, updatedAt: Date.now() });
  }
}

async function touchViewer(ctx, force) {
  const now = Date.now();
  const v = await ctx.data.get(DEV, "viewer");
  if (force || !v || now - (v.lastActive || 0) > 60000) {
    await ctx.data.put(DEV, "viewer", { lastActive: now });
  }
}

async function isViewerActive(ctx) {
  const v = await ctx.data.get(DEV, "viewer");
  return !!(v && Date.now() - (v.lastActive || 0) < 180000);
}

function callerOf(ctx) {
  if (ctx.principal) return { kind: "key", name: ctx.principal.name };
  if (ctx.user) return { kind: "user", email: ctx.user.email };
  return { kind: "anonymous" };
}

function queryOf(ctx) {
  const q = ctx.request.query;
  if (q && typeof q === "object") return q;
  const out = {};
  if (typeof q === "string") {
    for (const part of q.replace(/^\?/, "").split("&")) {
      if (!part) continue;
      const i = part.indexOf("=");
      const k = decodeURIComponent(i < 0 ? part : part.slice(0, i));
      const v = i < 0 ? "" : decodeURIComponent(part.slice(i + 1).replace(/\+/g, " "));
      out[k] = v;
    }
  }
  return out;
}

async function bodyOf(ctx) {
  const raw = ctx.request.body;
  if (raw == null || raw === "") return {};
  try {
    const parsed = typeof raw === "string" ? JSON.parse(raw) : raw;
    return parsed && typeof parsed === "object" ? parsed : {};
  } catch {
    throw bad("Request body must be valid JSON.");
  }
}

function json(status, body) {
  return { status, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" }, body };
}

function bad(message) {
  const e = new Error(message);
  e.badRequest = true;
  return e;
}

function convId(v) {
  return typeof v === "string" && /^[a-z0-9_-]{4,64}$/i.test(v) ? v : null;
}

function pad13(n) {
  return String(Math.max(0, Math.floor(n))).padStart(13, "0");
}

function rid() {
  return Math.random().toString(36).slice(2, 10);
}

function str(v, max) {
  return typeof v === "string" ? v.slice(0, max) : (v == null ? "" : String(v).slice(0, max));
}

function num(v) {
  return Number.isFinite(v) ? v : 0;
}
