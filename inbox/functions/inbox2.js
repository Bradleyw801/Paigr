// Text Relay v2: a private, end-to-end encrypted inbox for every pair of phones (see PROTOCOL.md).
// One function behind /v2/*. Phones seal everything readable before it leaves them; this stores, matches and syncs
// what it can't read. Every record lives under its inbox's id (tid); a device proves which inbox it belongs to with
// its own token (x-relay-token), of which only the SHA-256 is kept.

const TEN = "v2-tenants";
const DEV = "v2-devices";     // sha256(token) -> { tid, role, at, label, wrap }
const OFF = "v2-offers";      // lookup -> a linking offer (ask or invite)
const WRAP = "v2-wraps";      // phone tag -> { tid, dev, role, wrap }
const MSG = "v2-messages";
const CONV = "v2-convs";
const FEED = "v2-feed";
const TWINS = "v2-twins";
const ST = "v2-state";        // <tid>/relay, /viewer, /epoch, /wake, /push, /invite

const FEED_TTL_SECONDS = 3 * 86400;
const FEED_SKEW_MS = 15000;
const READ_SLACK_MS = 2000;
const MERGE_BUDGET_MS = 4500;
const OFFER_MS = 15 * 60000;
const OFFER_TTL_SECONDS = 3600;
const MAX_DEVICES = 8;
const DIRECT_MAX = 4 * 1024 * 1024 + 64;
const TICKET_MAX = 25 * 1024 * 1024 + 64;
const FILE_TYPE = "application/zip";   // sealed files are opaque bytes; storage takes them under this type
const TOMB_TTL_SECONDS = 90 * 86400;   // where a merged conversation went, for requests still naming it
const MERGED_MAX = 200;                // merge records a conversation keeps (see mergedList)
const CODE_RE = /^[a-z_]{1,24}$/;      // why the relay phone didn't send a reply: a code devices put in their own words

const TOKEN_RE = /^[A-Za-z0-9_-]{43}$/;
const HEX64_RE = /^[a-f0-9]{64}$/;
const TID_RE = /^[a-f0-9]{32}$/;
const SEALED_RE = /^e1\.[A-Za-z0-9_-]{38,48000}$/;
const MT_RE = /^[a-f0-9]{32}$/;
const FP_RE = /^[a-f0-9]{8,32}$/;
const KEY_RE = /^m:[0-9]{13}:[a-z0-9]{8,32}$/;
const MEDIA_RE = /^\/media\/v2\/[a-f0-9]{32}\/[a-z0-9_-]{4,64}\/[0-9]{13}-[a-z0-9]{8,32}-[op]\.enc$/;

async function handler(ctx) {
  const method = (ctx.request.method || "GET").toUpperCase();
  const path = (ctx.request.path || "/").replace(/\/+$/, "") || "/";
  const route = method + " " + path;
  try {
    const open = PUBLIC[route];
    if (open) return await open(ctx, await bodyOf(ctx));
    const entry = AUTHED[route];
    if (!entry) return json(404, { error: "No such endpoint: " + route });
    const me = await deviceOf(ctx);
    if (!me) return json(403, { unlinked: true, error: "This device isn't linked to an inbox." });
    const [roles, fn] = entry;
    if (roles !== "any" && roles !== me.role) return json(403, { error: "Not for a " + me.role + " device." });
    return await fn(ctx, me, method === "GET" ? {} : await bodyOf(ctx), queryOf(ctx));
  } catch (e) {
    if (e && e.badRequest) return json(400, { error: e.message });
    console.error("inbox2 failed", route, String(e && e.stack || e));
    return json(500, { error: "Server error: " + String(e && e.message || e) });
  }
}

const PUBLIC = {
  "POST /v2/link/ask": linkAsk,
  "POST /v2/link/wait": linkWait,
  "POST /v2/link/claim": linkClaim,
  "POST /v2/link/adopt": linkAdopt,
  "POST /v2/link/recover": linkRecover
};

const AUTHED = {
  "GET /v2/ping": ["any", ping],
  "GET /v2/ticket": ["any", ticket],
  "POST /v2/link/invite": ["any", linkInvite],
  "POST /v2/link/status": ["any", linkStatus],
  "POST /v2/wrap": ["any", wrapPut],
  "POST /v2/leave": ["any", leave],
  "GET /v2/devices": ["any", devices],
  "POST /v2/devices/name": ["any", deviceName],
  "POST /v2/devices/remove": ["inbox", deviceRemove],
  "GET /v2/media": ["any", mediaGet],
  "POST /v2/media/put": ["any", mediaPut],
  "POST /v2/media/ticket": ["any", mediaTicket],
  "POST /v2/media/complete": ["any", mediaComplete],

  "POST /v2/relay/ingest": ["relay", ingest],
  "GET /v2/relay/outbox": ["relay", outbox],
  "POST /v2/relay/outbox/result": ["relay", outboxResult],
  "POST /v2/relay/heartbeat": ["relay", heartbeat],
  "POST /v2/relay/merge": ["relay", merge],

  "GET /v2/inbox": ["inbox", inbox],
  "GET /v2/thread": ["inbox", thread],
  "GET /v2/sync": ["inbox", sync],
  "POST /v2/send": ["inbox", send],
  "POST /v2/read": ["inbox", markRead],
  "GET /v2/status": ["inbox", status],
  "POST /v2/push/register": ["inbox", pushRegister],
  "POST /v2/push/test": ["inbox", pushTest],
  "POST /v2/relay/wake": ["inbox", relayWake],
  "POST /v2/import": ["inbox", importItems],
  "POST /v2/import/done": ["inbox", importDone]
};

/** Storage key of a tenant's record. */
const P = (tid, k) => tid + "/" + k;
const unP = (tid, key) => key.slice(tid.length + 1);

async function deviceOf(ctx) {
  const token = header(ctx, "x-relay-token");
  if (!TOKEN_RE.test(token)) return null;
  const id = String(await ctx.crypto.sha256(token));
  const cur = await ctx.data.getWithMeta(DEV, id);
  const rec = cur && cur.value;
  return rec && TID_RE.test(rec.tid) ? { id, tid: rec.tid, role: rec.role === "relay" ? "relay" : "inbox", rec, version: cur.version } : null;
}

/**
 * Changes a device's own record, but only while it is still in its inbox: a removal that lands meanwhile wins, and the
 * device is out. Returns false when it was removed.
 */
async function updateDevice(ctx, me, change) {
  let rec = me.rec, version = me.version;
  for (let attempt = 0; attempt < 4; attempt++) {
    try {
      await ctx.data.put(DEV, me.id, change(Object.assign({}, rec)), { ifVersion: version });
      return true;
    } catch (e) {
      if (!isConflict(e)) throw e;
      const cur = await ctx.data.getWithMeta(DEV, me.id);
      if (!cur || !cur.value || cur.value.tid !== me.tid) return false;
      rec = cur.value;
      version = cur.version;
    }
  }
  throw new Error("the device record kept changing");
}

async function ping(ctx, me) {
  return json(200, { ok: true, tid: me.tid, role: me.role, serverTime: Date.now() });
}

/** A single-use realtime ticket for this device's own channel: tr2/<tid>/o for the relay phone, /i for the others. */
async function ticket(ctx, me) {
  if (!ctx.realtime || typeof ctx.realtime.issueTicket !== "function") return json(503, { error: "Realtime is off." });
  const channel = "tr2/" + me.tid + "/" + (me.role === "relay" ? "o" : "i");
  const t = await ctx.realtime.issueTicket(channel);
  return json(200, Object.assign({ channel }, t));
}

// ------------------------------------------------------------------ linking
// An "ask" is a device that isn't linked showing its code: whoever scans it brings it into their inbox, or starts a
// new inbox with it. An "invite" is a linked device showing a code that lets another device into its own inbox.
// Offers work once, for 15 minutes, and are filed under a lookup only the two devices can work out from the code.

async function linkAsk(ctx, body) {
  const lookup = hex64(body.lookup);
  const token = TOKEN_RE.test(body.token) ? body.token : null;
  const role = roleOf(body.role);
  if (!lookup || !token || !role) throw bad("lookup, token and role are required");
  const dev = String(await ctx.crypto.sha256(token));
  if (await ctx.data.get(DEV, dev)) return json(409, { error: "This device is already linked." });
  const now = Date.now();
  try {
    await ctx.data.put(OFF, lookup, { kind: "ask", role, dev, label: str(body.label, 60), state: "open", at: now, expiresAt: now + OFFER_MS },
      { ifNotExists: true, ttlSeconds: OFFER_TTL_SECONDS });
  } catch (e) {
    if (isConflict(e)) return json(409, { error: "That code is taken. Make a new one." });
    throw e;
  }
  return json(200, { ok: true, expiresAt: now + OFFER_MS });
}

async function linkWait(ctx, body) {
  const lookup = hex64(body.lookup);
  const token = TOKEN_RE.test(body.token) ? body.token : null;
  if (!lookup || !token) throw bad("lookup and token are required");
  const o = await ctx.data.get(OFF, lookup);
  if (!o || o.kind !== "ask") return json(410, { expired: true, error: "This code has expired. Make a new one." });
  const dev = String(await ctx.crypto.sha256(token));
  if (!(await same(ctx, dev, o.dev))) return json(403, { error: "This code belongs to another device." });
  // Kept until it expires, so an answer lost on the way can be fetched again.
  if (o.state === "done") return json(200, { ok: true, tid: o.tid, role: o.role, answer: o.answer });
  if (o.expiresAt <= Date.now()) return json(410, { expired: true, error: "This code has expired. Make a new one." });
  return json(202, { waiting: true, expiresAt: o.expiresAt });
}

async function linkClaim(ctx, body) {
  const lookup = hex64(body.lookup);
  if (!lookup) throw bad("lookup is required");
  const cur = await ctx.data.getWithMeta(OFF, lookup);
  const now = Date.now();
  if (!cur || !cur.value || cur.value.state !== "open" || cur.value.expiresAt <= now) {
    return json(404, { error: "This code has expired or was already used. Make a new one." });
  }
  const o = cur.value;
  const me = await deviceOf(ctx);
  if (o.kind === "ask") {
    const out = { ok: true, kind: "ask", role: o.role, label: o.label || "", linked: !!me };
    if (me && o.role === "relay") {
      const relay = await relayOf(ctx, me.tid);
      out.hasRelay = !!relay;
      if (relay) out.relay = { model: relay.model || "", lastSeen: relay.lastSeen || 0 };
    }
    return json(200, out);
  }
  if (me) {
    return json(409, {
      error: me.tid === o.tid ? "This phone is already linked to that inbox."
        : "This phone is linked to another inbox. Unlink it first (Settings, then Unlink this phone)."
    });
  }
  const token = TOKEN_RE.test(body.token) ? body.token : null;
  if (!token) throw bad("token is required");
  // An invite is only as good as the device that made it: one removed since can't let anyone in.
  const by = typeof o.by === "string" ? await ctx.data.get(DEV, o.by) : null;
  if (!by || by.tid !== o.tid) return json(404, { error: "This code has expired or was already used. Make a new one." });
  const dev = String(await ctx.crypto.sha256(token));
  const refused = await checkDevice(ctx, o.tid, o.role, dev, 0);
  if (refused) return refused;
  if (!(await closeOffer(ctx, lookup, cur, {}))) return json(404, { error: "This code was just used. Make a new one." });
  const failed = await putDevice(ctx, o.tid, o.role, dev, str(body.label, 60), now);
  if (failed) return failed;
  await doorbell(ctx, o.tid, "i", "linked", { role: o.role });
  if (o.role === "relay") await doorbell(ctx, o.tid, "o", "linked", { role: o.role });
  return json(200, { ok: true, kind: "invite", tid: o.tid, role: o.role, box: o.box });
}

async function linkAdopt(ctx, body) {
  const lookup = hex64(body.lookup);
  const answer = sealed(body.answer);
  if (!lookup || !answer) throw bad("lookup and answer are required");
  const cur = await ctx.data.getWithMeta(OFF, lookup);
  const now = Date.now();
  if (!cur || !cur.value || cur.value.kind !== "ask" || cur.value.state !== "open" || cur.value.expiresAt <= now) {
    return json(404, { error: "This code has expired or was already used. Make a new one." });
  }
  const o = cur.value;
  const me = await deviceOf(ctx);
  let tid, own = null;
  if (me) {
    tid = me.tid;
  } else {
    // Neither device is linked: the scanning one starts a new inbox, with both in it.
    const c = body.create && typeof body.create === "object" ? body.create : {};
    if (!TID_RE.test(c.tid) || !TOKEN_RE.test(c.token)) return json(403, { unlinked: true, error: "Link this phone first." });
    if (o.role !== "relay") return json(409, { error: "A new inbox starts from the code in Relay, on the phone with the SIM." });
    tid = c.tid;
    own = { dev: String(await ctx.crypto.sha256(c.token)), label: str(c.label, 60) };
  }
  // Everything that could still refuse is checked before the code is used up.
  const refused = (own && await checkDevice(ctx, tid, "inbox", own.dev, 0)) || await checkDevice(ctx, tid, o.role, o.dev, own ? 1 : 0);
  if (refused) return refused;
  if (own) {
    try {
      await ctx.data.put(TEN, tid, { createdAt: now }, { ifNotExists: true });
    } catch (e) {
      if (isConflict(e)) return json(409, { error: "Try again." });
      throw e;
    }
  }
  if (!(await closeOffer(ctx, lookup, cur, { tid, answer }))) return json(404, { error: "This code was just used. Make a new one." });
  if (own) {
    const failed = await putDevice(ctx, tid, "inbox", own.dev, own.label, now);
    if (failed) return failed;
  }
  const failed = await putDevice(ctx, tid, o.role, o.dev, o.label, now);
  if (failed) return failed;
  await doorbell(ctx, tid, "i", "linked", { role: o.role });
  return json(200, { ok: true, tid, role: o.role, created: !!own });
}

/** Marks an open offer used, so it works once even when two devices race for it. False when one already did. */
async function closeOffer(ctx, lookup, cur, extra) {
  try {
    await ctx.data.put(OFF, lookup, Object.assign({}, cur.value, extra, { state: "done", usedAt: Date.now() }),
      { ifVersion: cur.version, ttlSeconds: OFFER_TTL_SECONDS });
    return true;
  } catch (e) {
    if (isConflict(e)) return false;
    throw e;
  }
}

/**
 * Whether a device can join an inbox: not linked to another one, and room for it (a relay phone replaces the one there,
 * and `adding` counts devices joining in the same step). A refusal as a response, or null.
 */
async function checkDevice(ctx, tid, role, dev, adding) {
  const existing = await ctx.data.get(DEV, dev);
  if (existing && existing.tid !== tid) return json(409, { error: "This device is already linked to another inbox." });
  const others = await tenantDevices(ctx, tid);
  const count = others.filter(d => d.key !== dev && !(role === "relay" && d.value.role === "relay")).length + (adding || 0);
  if (count >= MAX_DEVICES) return json(409, { error: "This inbox already has " + MAX_DEVICES + " devices. Remove one in Settings first." });
  return null;
}

/**
 * Puts a device in an inbox. A new relay phone takes over from any other, once its own record is in: one inbox has one
 * SIM behind it.
 */
async function putDevice(ctx, tid, role, dev, label, now) {
  const cur = await ctx.data.getWithMeta(DEV, dev);
  const existing = cur && cur.value;
  if (existing && existing.tid !== tid) return json(409, { error: "This device is already linked to another inbox." });
  try {
    await ctx.data.put(DEV, dev, Object.assign({}, existing || {}, { tid, role, at: now, label: label || (existing && existing.label) || "" }),
      existing ? { ifVersion: cur.version } : { ifNotExists: true });
  } catch (e) {
    if (isConflict(e)) return json(409, { error: "Try again." });
    throw e;
  }
  if (role === "relay") {
    for (const d of await tenantDevices(ctx, tid)) if (d.value.role === "relay" && d.key !== dev) await removeDevice(ctx, d.key, d.value, tid);
  }
  return null;
}

async function tenantDevices(ctx, tid) {
  const r = await ctx.data.query(DEV, "by-tenant", { eq: tid, limit: 50 });
  return r.items.filter(i => i.value && i.value.tid === tid);
}

/** Takes a device out of its inbox, with what it left behind: its wrap, the invite it is showing, its push subscriptions. */
async function removeDevice(ctx, id, rec, tid) {
  await ctx.data.delete(DEV, id);
  if (rec && rec.wrap) {
    const w = await ctx.data.get(WRAP, rec.wrap);
    if (w && w.dev === id) await ctx.data.delete(WRAP, rec.wrap);
  }
  tid = tid || (rec && rec.tid);
  if (!TID_RE.test(tid || "")) return;
  const inv = await ctx.data.get(ST, P(tid, "invite"));
  if (inv && inv.lookup) {
    const o = await ctx.data.get(OFF, inv.lookup);
    if (o && o.kind === "invite" && o.by === id) await ctx.data.delete(OFF, inv.lookup);
  }
  // Its own subscriptions go, and any whose device isn't known (each device registers its own again on its next start).
  await editPush(ctx, tid, (ids, owners) => ids.filter(x => typeof owners[x] === "string" && owners[x] !== id));
}

async function linkInvite(ctx, me, body) {
  const lookup = hex64(body.lookup);
  const box = sealed(body.box);
  const role = roleOf(body.role) || "inbox";
  if (!lookup || !box) throw bad("lookup and box are required");
  if (role === "relay" && me.role === "relay") return json(403, { error: "A relay phone hands over by showing its own code to Texts." });
  const now = Date.now();
  try {
    await ctx.data.put(OFF, lookup, { kind: "invite", tid: me.tid, role, box, by: me.id, state: "open", at: now, expiresAt: now + OFFER_MS },
      { ifNotExists: true, ttlSeconds: OFFER_TTL_SECONDS });
  } catch (e) {
    if (isConflict(e)) return json(409, { error: "That code is taken. Make a new one." });
    throw e;
  }
  // Only an inbox's newest invite works.
  const prev = await ctx.data.get(ST, P(me.tid, "invite"));
  if (prev && prev.lookup && prev.lookup !== lookup) {
    const po = await ctx.data.get(OFF, prev.lookup);
    if (po && po.kind === "invite" && po.tid === me.tid && po.state === "open") await ctx.data.delete(OFF, prev.lookup);
  }
  await ctx.data.put(ST, P(me.tid, "invite"), { lookup, at: now }, { ttlSeconds: OFFER_TTL_SECONDS });
  return json(200, { ok: true, expiresAt: now + OFFER_MS });
}

async function linkStatus(ctx, me, body) {
  const lookup = hex64(body.lookup);
  if (!lookup) throw bad("lookup is required");
  const o = await ctx.data.get(OFF, lookup);
  if (!o || o.tid !== me.tid) return json(200, { state: "expired" });
  return json(200, { state: o.state === "done" ? "done" : o.expiresAt <= Date.now() ? "expired" : "open" });
}

/** A reinstalled app on a phone that was linked: its wrap, which only that phone can open. */
async function linkRecover(ctx, body) {
  const tag = hex64(body.tag);
  if (!tag) throw bad("tag is required");
  const w = await ctx.data.get(WRAP, tag);
  if (!w) return json(404, { known: false });
  const d = await ctx.data.get(DEV, w.dev);
  if (!d || d.tid !== w.tid) {
    await ctx.data.delete(WRAP, tag);
    return json(404, { known: false });
  }
  return json(200, { ok: true, role: w.role, wrap: w.wrap });
}

async function wrapPut(ctx, me, body) {
  const tag = hex64(body.tag);
  const wrap = sealed(body.wrap);
  if (!tag || !wrap) throw bad("tag and wrap are required");
  const before = me.rec.wrap;
  if (before !== tag && !(await updateDevice(ctx, me, rec => Object.assign(rec, { wrap: tag })))) {
    return json(403, { unlinked: true, error: "This device isn't linked to an inbox." });
  }
  await ctx.data.put(WRAP, tag, { tid: me.tid, dev: me.id, role: me.role, wrap, at: Date.now() });
  if (before && before !== tag) {
    const old = await ctx.data.get(WRAP, before);
    if (old && old.dev === me.id) await ctx.data.delete(WRAP, before);
  }
  // Removed meanwhile: its wrap goes too (recover also refuses a wrap whose device is gone).
  if (!(await ctx.data.get(DEV, me.id))) {
    const w = await ctx.data.get(WRAP, tag);
    if (w && w.dev === me.id) await ctx.data.delete(WRAP, tag);
    return json(403, { unlinked: true, error: "This device isn't linked to an inbox." });
  }
  return json(200, { ok: true });
}

async function leave(ctx, me) {
  await removeDevice(ctx, me.id, me.rec, me.tid);
  await doorbell(ctx, me.tid, "i", "unlinked", { role: me.role });
  return json(200, { ok: true });
}

async function devices(ctx, me) {
  const list = await tenantDevices(ctx, me.tid);
  const items = list.map(d => ({
    id: d.key.slice(0, 12), role: d.value.role, at: d.value.at || 0, you: d.key === me.id, sn: sealed(d.value.sn) || ""
  })).sort((a, b) => (a.role === b.role ? a.at - b.at : a.role === "relay" ? -1 : 1));
  return json(200, { items });
}

/**
 * A device's name for itself, sealed with the inbox key for its own id (`seal({name}, "tr2:dev:" + id)`): devices show
 * only names that open that way, so a name in the list is always one a device of the inbox gave itself.
 */
async function deviceName(ctx, me, body) {
  const sn = sealed(body.sn);
  if (!sn || sn.length > 400) throw bad("sn is required");
  const kept = await updateDevice(ctx, me, rec => Object.assign(rec, { sn }));
  if (!kept) return json(403, { unlinked: true, error: "This device isn't linked to an inbox." });
  return json(200, { ok: true });
}

async function deviceRemove(ctx, me, body) {
  const id = typeof body.id === "string" && /^[a-f0-9]{12}$/.test(body.id) ? body.id : null;
  if (!id) throw bad("id is required");
  const target = (await tenantDevices(ctx, me.tid)).find(d => d.key.startsWith(id));
  if (!target) return json(404, { error: "No such device in this inbox." });
  await removeDevice(ctx, target.key, target.value, me.tid);
  await doorbell(ctx, me.tid, "i", "unlinked", { role: target.value.role });
  if (target.value.role === "relay") await doorbell(ctx, me.tid, "o", "unlinked", {});
  return json(200, { ok: true, you: target.key === me.id });
}

// ------------------------------------------------------------------ relay phone

async function ingest(ctx, me, body) {
  const tid = me.tid;
  const items = Array.isArray(body.items) ? body.items.slice(0, 50) : [];
  const replyable = body.replyable && typeof body.replyable === "object" ? body.replyable : {};
  const reads = body.reads && typeof body.reads === "object" ? body.reads : {};
  const now = Date.now();

  // A conversation the relay phone merged away (a text on its way while that happened) lands in the one it went into.
  const home = await homesOf(ctx, tid, items.map(it => it && typeof it === "object" ? convId(it.conv) : null));
  const entries = [];
  const byKey = new Map();
  for (const it of items) {
    if (!it || typeof it !== "object") continue;
    const named = convId(it.conv);
    if (!named) continue;
    const conv = home.get(named) || named;
    const ts = Number.isFinite(it.ts) && it.ts > 0 ? Math.floor(it.ts) : now;
    const fp = typeof it.fp === "string" && FP_RE.test(it.fp) ? it.fp : rid();
    const k = "m:" + pad13(ts) + ":" + fp;
    const out = it.dir === "out";
    const value = {
      k, conv, cv: P(tid, conv), dir: out ? "out" : "in",
      status: out ? (it.status === "failed" ? "failed" : "sent") : "received",
      e: sealed(it.e) || "",
      ts,
      media: mediaOf(tid, conv, it.media, named),
      src: it.src === "store" ? "store" : "notif",
      receivedAt: now
    };
    const ne = sealed(it.ne);
    if (ne) value.ne = ne;
    if (typeof it.mt === "string" && MT_RE.test(it.mt)) value.mt = it.mt;
    if (out) { value.via = "phone"; value.sentAt = ts; }
    if (it.history) value.history = true;
    if (!out && (it.history || it.read)) value.read = true;
    const entry = { v: value, want: !!it.wantMedia && !!value.media, result: { fp, k } };
    const first = byKey.get(k);
    if (first) entry.same = first;
    else byKey.set(k, entry);
    entries.push(entry);
  }

  // A message the server already holds is not news: no second unread count, no second notification.
  const keys = [...byKey.keys()];
  const held = new Map();
  const near = nearby(ctx, tid, now);
  for (let i = 0; i < keys.length; i += 25) {
    const r = await ctx.data.batchGet(MSG, keys.slice(i, i + 25).map(k => P(tid, k)));
    for (const x of r.items) if (x.found && x.value) held.set(x.value.k, near.add(x.value, x.version));
  }
  // A copy that folded into another message before is known by its own key too, however long ago that was.
  const aliasTo = new Map();
  const missing = keys.filter(k => !held.has(k));
  for (let i = 0; i < missing.length; i += 25) {
    const r = await ctx.data.batchGet(TWINS, missing.slice(i, i + 25).map(k => P(tid, k)));
    for (const x of r.items) if (x.found && x.value && typeof x.value.to === "string") aliasTo.set(unP(tid, x.key), x.value.to);
  }
  const targetKeys = [...new Set(aliasTo.values())].filter(k => !held.has(k));
  for (let i = 0; i < targetKeys.length; i += 25) {
    const r = await ctx.data.batchGet(MSG, targetKeys.slice(i, i + 25).map(k => P(tid, k)));
    for (const x of r.items) if (x.found && x.value) near.add(x.value, x.version);
  }

  // The same message can also arrive twice under different keys (Messages' notification, the message store, a reply
  // sent from Texts). Each later copy folds into the first, by match token, direction and time. A copy only ever adds
  // to the message it folds into (where else it was seen, a late attachment): what changes are kept as edits, so they
  // can be applied again to a newer version (a reply's result reported meanwhile is never undone).
  const fresh = [];
  const edits = new Map();   // key of a stored message -> what this request adds to it
  const visible = new Set();
  const aliases = [];
  const edit = (target, fn) => {
    fn(target);
    if (!edits.has(target.k)) edits.set(target.k, []);
    edits.get(target.k).push(fn);
  };
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
          edit(target, t => {
            t.from = [...new Set((t.from || [srcOf(t)]).concat(v.src))];
            if (!(t.twins || []).includes(v.k)) t.twins = (t.twins || []).concat(v.k).slice(-8);
          });
          aliases.push({ key: P(tid, v.k), value: { to: target.k, conv: target.conv } });
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
      // The attachment arrived late; its details (type, size) come sealed with this copy.
      edit(target, t => {
        if (t.media && t.media.path) return;
        t.media = v.media;
        if (v.e) t.e = v.e;
      });
      visible.add(target.k);
      res.media = "attached";
    }
    if (entry.want && !(target.media && target.media.path)) res.needMedia = true;
  }
  for (let i = 0; i < fresh.length; i += 10) {
    await ctx.data.batchPut(MSG, fresh.slice(i, i + 10).map(v => ({ key: P(tid, v.k), value: v })));
  }
  const freshKeys = new Set(fresh.map(v => v.k));
  const written = new Map();
  for (const [k, fns] of edits) {
    if (freshKeys.has(k)) continue;   // stored just now, with its edits
    const v = await rewriteMessage(ctx, tid, near.get(k), near.version(k), fns);
    if (v) written.set(k, v);
  }
  for (let i = 0; i < aliases.length; i += 25) await ctx.data.batchPut(TWINS, aliases.slice(i, i + 25));

  const latestByConv = new Map();
  const unreadByConv = new Map();
  for (const v of fresh) {
    if (v.dir === "in" && !v.read) unreadByConv.set(v.conv, (unreadByConv.get(v.conv) || 0) + 1);
    const prev = latestByConv.get(v.conv);
    if (!prev || v.ts >= prev.ts) latestByConv.set(v.conv, v);
  }
  // A name a conversation got from any of these messages, newest first.
  const namesByConv = new Map();
  for (const v of [...fresh].sort((a, b) => b.ts - a.ts)) if (v.ne && !namesByConv.has(v.conv)) namesByConv.set(v.conv, v.ne);
  for (const [conv, last] of latestByConv) {
    await updateConv(ctx, tid, conv, existing => {
      const newer = !existing || last.ts >= (existing.lastTs || 0);
      const named = namesByConv.get(conv);
      return Object.assign({}, existing || {}, {   // keeps what else it holds (the relay phone's merge records)
        conv,
        ne: newer ? (last.ne || named || (existing && existing.ne) || null) : ((existing && existing.ne) || named || null),
        lastTs: newer ? last.ts : existing.lastTs,
        lastE: newer ? last.e : existing.lastE,
        lastDir: newer ? last.dir : existing.lastDir,
        lastMedia: newer ? !!last.media : !!existing.lastMedia,
        canReply: conv in replyable ? !!replyable[conv] : (existing ? existing.canReply !== false : true),
        unread: ((existing && existing.unread) || 0) + (unreadByConv.get(conv) || 0),
        imported: !!(existing && existing.imported),
        updatedAt: now
      });
    });
  }

  // Reply handles can appear or vanish without a new message, and a conversation read on the relay phone is read here.
  const touched = new Set(Object.keys(replyable).concat(Object.keys(reads)));
  for (const raw of touched) {
    const conv = convId(raw);
    if (!conv) continue;
    const wrote = await updateConv(ctx, tid, conv, existing => {
      if (!existing) return null;
      let change = false;
      if (raw in replyable && !latestByConv.has(conv) && (existing.canReply !== false) !== !!replyable[raw]) {
        existing.canReply = !!replyable[raw];
        change = true;
      }
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

  const logged = fresh.concat([...visible].filter(k => !freshKeys.has(k) && written.has(k)).map(k => written.get(k)));
  const live = logged.filter(v => !v.history);
  const historyConvs = [...new Set(logged.filter(v => v.history).map(v => v.conv))];
  await feedAdd(ctx, tid, live.concat(historyConvs.map(conv => ({ k: "t:" + conv, conv, touch: true }))));
  await notify(ctx, tid, fresh.filter(v => v.dir === "in" && !v.read));
  if (logged.length || latestByConv.size) {
    const lastKey = live.length ? live[live.length - 1].k : null;
    await doorbell(ctx, tid, "i", "messages", { k: lastKey, convs: [...new Set([...latestByConv.keys()].concat(logged.map(v => v.conv)))] });
  }
  return json(200, {
    ok: true, stored: fresh.length, folded, known, serverTime: now,
    results: entries.map(e => e.same ? Object.assign({}, e.same.result) : e.result)
  });
}

async function updateConv(ctx, tid, conv, change) {
  for (let attempt = 0; ; attempt++) {
    const cur = await ctx.data.getWithMeta(CONV, P(tid, conv));
    const next = change(cur ? cur.value : null);
    if (!next) return null;
    try {
      await ctx.data.put(CONV, P(tid, conv), next, cur ? { ifVersion: cur.version } : { ifNotExists: true });
      return next;
    } catch (e) {
      if (!isConflict(e) || attempt >= 5) throw e;
    }
  }
}

/**
 * Stored messages close in time to the ones arriving (any conversation, so a history copy finds its twin), each with
 * the version it was read at.
 */
function nearby(ctx, tid, now) {
  const RANGE = 10 * 60000;
  const ranges = [];
  const all = new Map();
  const versions = new Map();
  const recent = new Map();
  const own = (v, version) => {
    const have = all.get(v.k);
    if (have) return have;
    all.set(v.k, v);
    if (version !== undefined) versions.set(v.k, version);
    return v;
  };
  async function read(lo, hi) {
    let cursor = null;
    let covered = hi;
    for (let page = 0; page < 3; page++) {
      const r = await ctx.data.list(MSG, {
        keyPrefix: P(tid, "m:"), startAfter: P(tid, "m:" + pad13(lo)), endBefore: P(tid, "m:" + pad13(hi + 1)), order: "asc", limit: 100, cursor
      });
      for (const i of r.items) if (i.value && i.value.k) own(i.value, i.version);
      cursor = r.cursor;
      if (!cursor) break;
      if (page === 2) covered = r.items.length ? r.items[r.items.length - 1].value.ts - 1 : lo;
    }
    ranges.push({ lo, hi: Math.max(lo, covered) });
  }
  return {
    add(v, version) { return own(v, version); },
    /** A message this request wrote: its new form, its version no longer known. */
    replace(v) { all.set(v.k, v); versions.delete(v.k); },
    get(k) { return all.get(k) || null; },
    version(k) { return versions.get(k); },
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
            const r = await ctx.data.query(MSG, "by-conv", { eq: P(tid, v.conv), order: "desc", limit: 100, cursor });
            for (const i of r.items) if (i.value && i.value.k) list.push(own(i.value, i.version));
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
 * Writes a stored message with what this request adds to it (`fns`), only over the version it was read at; when it
 * changed meanwhile (a reply's result came in), the additions go onto the newer version instead. Returns what was
 * written, or null when the message is gone (folded or merged away meanwhile).
 */
async function rewriteMessage(ctx, tid, value, version, fns) {
  let cur = value, ver = version;
  for (let attempt = 0; attempt < 6; attempt++) {
    if (cur && ver !== undefined) {
      try {
        await ctx.data.put(MSG, P(tid, cur.k), cur, { ifVersion: ver });
        return cur;
      } catch (e) {
        if (!isConflict(e)) throw e;
      }
    }
    const fresh = await ctx.data.getWithMeta(MSG, P(tid, value.k));
    if (!fresh || !fresh.value) return null;
    cur = fresh.value;
    ver = fresh.version;
    for (const fn of fns) fn(cur);
  }
  throw new Error("a message kept changing while it was being updated");
}

/** v1's twin rule with the phones' match tokens in place of the text: same direction and token, close in time. */
function findTwin(v, candidates) {
  const token = v.mt;
  if (!token) return null;
  let best = null;
  let bestGap = Infinity;
  for (const y of candidates) {
    if (!y || y.k === v.k || y.dir !== v.dir || y.touch) continue;
    if ((y.from || [srcOf(y)]).includes(v.src)) continue;
    if (y.mt !== token) continue;
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

/**
 * Two conversations turned out to be one (the relay phone found a thread and a notification conversation to be the
 * same people): `from`'s messages move into `into`, folding into copies already there. `mr` is the relay phone's sealed
 * word for it (`seal({from, into}, "tr2:merge:" + into)`), kept with `into`: inbox devices accept what was sealed for
 * `from` under `into` only with it. A reply still waiting to go out is moved, never folded away, so it still goes out
 * and is reported. Done in slices that fit the time limit; `more` says call again.
 */
async function merge(ctx, me, body) {
  const tid = me.tid;
  const from = convId(body.from);
  const into = convId(body.into);
  if (!from || !into || from === into) throw bad("from and into required");
  const target = await ctx.data.get(CONV, P(tid, into));
  if (!target) return json(404, { error: "unknown conversation" });
  // The relay phone seals its record afresh on every call (and runs every merge once more later): once the tombstone
  // says `from` went into `into` and holds the record an earlier call kept, the one sent now is that same merge's, and
  // not news to any device.
  const tomb = await ctx.data.get(ST, P(tid, "merged:" + from));
  const same = !!(tomb && tomb.into === into);
  const kept = same ? sealed(tomb.rec) : null;
  const record = kept ? null : sealed(body.mr);
  const recorded = record ? await addMerged(ctx, tid, into, [record]) : false;
  const tombstone = (told) => ctx.data.put(ST, P(tid, "merged:" + from), Object.assign({ into, at: Date.now(), rec: kept || record || "" }, told ? { told: true } : {}),
    { ttlSeconds: TOMB_TTL_SECONDS });
  // From here on a reply or a text filed under `from` goes to `into` (send, ingest): nothing new is left behind there.
  if (!kept) await tombstone(false);
  // Before this call changes anything, the tombstone stops saying devices were told, so a call that stops before
  // telling them leaves the next one to (`told` covers every change made so far, not just the first call's).
  let untold = !kept || !tomb.told;
  let cleared = false;   // by this call: another call may have marked the merge told since this one read the marker
  const working = async () => {
    if (cleared) return;
    cleared = untold = true;
    await tombstone(false);
  };
  const started = Date.now();
  const near = nearby(ctx, tid, started);
  let moved = 0, folded = 0;
  while (Date.now() - started < MERGE_BUDGET_MS) {
    const r = await ctx.data.query(MSG, "by-conv", { eq: P(tid, from), order: "asc", limit: 25 });
    if (!r.items.length) break;
    let progress = false;
    for (const i of r.items) {
      const v = i.value;
      if (!v || !v.k) continue;
      const as = Object.assign({}, v, { conv: into, src: srcOf(v) });
      const around = v.status === "queued" ? [] : await near.of(as);
      const owner = around.find(y => Array.isArray(y.twins) && y.twins.includes(v.k)) || null;
      const twin = owner || findTwin(as, around);
      await working();
      if (twin) {
        if (!owner) {
          const fns = [t => {
            t.from = [...new Set((t.from || [srcOf(t)]).concat(v.from || [srcOf(v)]))];
            t.twins = [...new Set((t.twins || []).concat(v.k, v.twins || []))].slice(-8);
            if (v.media && v.media.path && !(t.media && t.media.path)) { t.media = v.media; if (v.e) t.e = v.e; }
          }];
          for (const fn of fns) fn(twin);
          await rewriteMessage(ctx, tid, twin, near.version(twin.k), fns);
        }
        const copies = [...new Set([v.k].concat(v.twins || []))].slice(0, 25);
        await ctx.data.batchPut(TWINS, copies.map(k => ({ key: P(tid, k), value: { to: twin.k, conv: into } })));
        await ctx.data.delete(MSG, P(tid, v.k));
        folded++;
        progress = true;
      } else {
        const next = Object.assign({}, v, { conv: into, cv: P(tid, into) });
        if (target.ne) next.ne = target.ne;
        try {
          await ctx.data.put(MSG, P(tid, v.k), next, { ifVersion: i.version });
        } catch (e) {
          if (!isConflict(e)) throw e;
          continue;   // it changed meanwhile (a reply's result came in): moved on a later pass
        }
        near.replace(next);
        moved++;
        progress = true;
      }
    }
    if (!progress) break;
  }
  const rest = await ctx.data.query(MSG, "by-conv", { eq: P(tid, from), limit: 1 });
  if (rest.items.length) return json(200, { ok: true, moved, folded, more: true });

  const source = await ctx.data.get(CONV, P(tid, from));
  if (source) {
    await working();
    await updateConv(ctx, tid, into, t => {
      if (!t) return null;
      if ((source.lastTs || 0) > (t.lastTs || 0)) {
        t.lastTs = source.lastTs;
        t.lastE = source.lastE;
        t.lastDir = source.lastDir;
        t.lastMedia = !!source.lastMedia;
      }
      if (!t.ne && source.ne) t.ne = source.ne;
      t.unread = (t.unread || 0) + (source.unread || 0);
      // what vouched for conversations merged into `from` vouches for them here too
      t.merged = mergedList(t.merged, source.merged, [kept || record].filter(Boolean));
      t.updatedAt = Date.now();
      return t;
    });
    await ctx.data.delete(CONV, P(tid, from));
  }
  const changed = !!(moved || folded || source || recorded);
  // Anything filed under `from` by a request that started before the tombstone was written: moved by another call.
  // Devices reload for what this call changed now: that other call may well be the late request's own (send and ingest
  // file it under `into` themselves), and then the next merge call finds nothing left to do.
  const late = await ctx.data.query(MSG, "by-conv", { eq: P(tid, from), limit: 1 });
  if (late.items.length) {
    if (changed) {
      await reloadDevices(ctx, tid);
      await tombstone(true);
    }
    return json(200, { ok: true, moved, folded, more: true });
  }
  // The relay phone runs every merge once more later; when that finds nothing to do, devices don't reload for it, unless
  // no call has had them reload yet (one that did the work stopped before it could).
  if (!changed && !untold) return json(200, { ok: true, moved, folded, more: false, epoch: await epochOf(ctx, tid) });
  const epoch = await reloadDevices(ctx, tid);
  await tombstone(true);
  return json(200, { ok: true, moved, folded, more: false, epoch });
}

/** A new epoch, and the doorbell that has inbox devices reload for it. */
async function reloadDevices(ctx, tid) {
  const epoch = await bumpEpoch(ctx, tid);
  await doorbell(ctx, tid, "i", "reload", { epoch });
  return epoch;
}

/**
 * A conversation's merge records: what it had, what came with a conversation merged into it, and `keep` (the record
 * of the merge under way), which is never the one left out. Over the cap the oldest go first; a conversation merged
 * into this many others is far beyond anything the relay phone does.
 */
function mergedList(a, b, keep) {
  const all = [...new Set((Array.isArray(a) ? a : []).concat(Array.isArray(b) ? b : []).filter(x => sealed(x)))];
  const must = new Set((Array.isArray(keep) ? keep : []).filter(x => sealed(x)));
  for (const x of must) if (!all.includes(x)) all.push(x);
  while (all.length > MERGED_MAX) {
    const i = all.findIndex(x => !must.has(x));
    if (i < 0) break;
    all.splice(i, 1);
  }
  return all;
}

/** Where a conversation the relay phone merged away went (following every merge after it), or itself. */
async function homeOf(ctx, tid, conv) {
  let at = conv;
  const seen = new Set([conv]);
  for (let hop = 0; hop < 32; hop++) {
    const t = await ctx.data.get(ST, P(tid, "merged:" + at));
    const next = t && convId(t.into);
    if (!next || seen.has(next)) break;
    seen.add(next);
    at = next;
  }
  return at;
}

/** homeOf for several conversations at once: a map of those that were merged away to where they went. */
async function homesOf(ctx, tid, convs) {
  const list = [...new Set(convs.filter(Boolean))];
  const out = new Map();
  for (let i = 0; i < list.length; i += 25) {
    const r = await ctx.data.batchGet(ST, list.slice(i, i + 25).map(c => P(tid, "merged:" + c)));
    for (const x of r.items) {
      if (!x.found || !x.value) continue;
      const conv = unP(tid, x.key).slice("merged:".length);
      out.set(conv, await homeOf(ctx, tid, conv));
    }
  }
  return out;
}

/** Bumps the inbox's epoch (devices reload when it changes); two at once each get their own number. */
async function bumpEpoch(ctx, tid) {
  for (let attempt = 0; ; attempt++) {
    const cur = await ctx.data.getWithMeta(ST, P(tid, "epoch"));
    const n = (cur && cur.value && Number.isFinite(cur.value.n) ? cur.value.n : 0) + 1;
    try {
      await ctx.data.put(ST, P(tid, "epoch"), { n, at: Date.now() }, cur ? { ifVersion: cur.version } : { ifNotExists: true });
      return n;
    } catch (e) {
      if (!isConflict(e) || attempt >= 5) throw e;
    }
  }
}

/** Adds merge records to a conversation's summary; true when that changed it. */
async function addMerged(ctx, tid, conv, records) {
  const wrote = await updateConv(ctx, tid, conv, c => {
    if (!c) return null;
    const list = mergedList(c.merged, records, records);
    if (Array.isArray(c.merged) && list.length === c.merged.length && list.every((x, i) => x === c.merged[i])) return null;
    c.merged = list;
    return c;
  });
  return !!wrote;
}

async function epochOf(ctx, tid) {
  const e = await ctx.data.get(ST, P(tid, "epoch"));
  return e && Number.isFinite(e.n) ? e.n : 0;
}

async function outbox(ctx, me) {
  const tid = me.tid;
  const page = await ctx.data.query(MSG, "by-status", { eq: P(tid, "queued"), order: "asc", limit: 20 });
  const items = page.items.filter(i => i.value && i.value.status === "queued")
    .map(i => ({ id: i.value.k, conv: i.value.conv, o: i.value.o || "", ts: i.value.ts }));
  const viewerActive = await isViewerActive(ctx, tid);
  return json(200, Object.assign({ items, serverTime: Date.now(), pollSeconds: viewerActive || items.length ? 5 : 20 }, await wakeOf(ctx, tid)));
}

async function wakeOf(ctx, tid) {
  const w = await ctx.data.get(ST, P(tid, "wake"));
  return w && Number.isFinite(w.at) ? { wake: w.at, wakeKind: w.kind || "reconnect" } : { wake: 0 };
}

async function relayWake(ctx, me, body) {
  const tid = me.tid;
  const now = Date.now();
  const kind = body && (body.kind === "refresh" || body.kind === "resync") ? body.kind : "reconnect";
  await ctx.data.put(ST, P(tid, "wake"), { at: now, kind });
  await doorbell(ctx, tid, "o", "reconnect", { at: now, kind });
  return json(200, { ok: true, at: now, kind, relay: await relayOf(ctx, tid) });
}

async function outboxResult(ctx, me, body) {
  const tid = me.tid;
  const id = typeof body.id === "string" && KEY_RE.test(body.id) ? body.id : null;
  if (!id) throw bad("id required");
  const status = body.status === "sent" ? "sent" : "failed";
  const sentAt = Date.now();
  const apply = (v) => {
    v.status = status;
    delete v.qs;
    delete v.o;   // acted on: the request itself isn't kept
    v.sentAt = sentAt;
    v.via = body.via === "sms" || body.via === "mms" ? body.via : "rcs";
    // Why it wasn't sent travels as a code; devices put it in their own words (the server's are never shown).
    delete v.error;
    if (status === "failed" && typeof body.code === "string" && CODE_RE.test(body.code)) v.code = body.code;
    else delete v.code;
  };
  let v = null;
  for (let attempt = 0; ; attempt++) {
    const cur = await ctx.data.getWithMeta(MSG, P(tid, id));
    if (!cur || !cur.value) return json(404, { error: "unknown message" });
    v = cur.value;
    apply(v);
    try {
      await ctx.data.put(MSG, P(tid, id), v, { ifVersion: cur.version });
      break;
    } catch (e) {
      if (!isConflict(e) || attempt >= 5) throw e;   // changed meanwhile (a copy folded in): read it again
    }
  }
  if (typeof body.canReply === "boolean") {
    await updateConv(ctx, tid, v.conv, c => {
      if (!c || (c.canReply !== false) === body.canReply) return null;
      c.canReply = body.canReply;
      c.updatedAt = Date.now();
      return c;
    });
  }
  await feedAdd(ctx, tid, [v]);
  await doorbell(ctx, tid, "i", "status", { k: id, status, via: v.via });
  return json(200, { ok: true });
}

async function heartbeat(ctx, me, body) {
  const tid = me.tid;
  const now = Date.now();
  const rec = {
    lastSeen: now,
    seen: pad13(now),   // the watch finds relay phones that stopped checking in by this
    listening: !!body.listening,
    replyable: num(body.replyable),
    lastRelayed: num(body.lastRelayed),
    battery: num(body.battery),
    charging: !!body.charging,
    version: str(body.version, 40),
    model: str(body.model, 80),
    sn: sealed(body.sn) || "",   // what the relay phone has to say about itself (its problems), sealed: "tr2:note"
    queued: num(body.queued),
    listenerConnected: body.listenerConnected !== false
  };
  const prev = await relayOf(ctx, tid);
  if (prev && prev.offlineSince) {
    await pushAll(ctx, tid, { title: "Relay phone is back", body: "Texts are flowing again.", tag: "relay-status", url: "/" });
    await doorbell(ctx, tid, "i", "relay", { online: true });
  }
  if (Number.isFinite(body.wakeHandled) && body.wakeHandled > 0) rec.wakeHandled = Math.floor(body.wakeHandled);
  // Whether the relay phone may send SMS itself: devices then know a reply needn't wait for an open notification.
  if (typeof body.smsFallback === "boolean") rec.smsFallback = body.smsFallback;
  const store = storeOf(body.store);
  if (store) rec.store = store;
  await ctx.data.put(ST, P(tid, "relay"), rec);
  const viewerActive = await isViewerActive(ctx, tid);
  const queued = await ctx.data.query(MSG, "by-status", { eq: P(tid, "queued"), limit: 1 });
  return json(200, Object.assign({ ok: true, serverTime: now, pollSeconds: viewerActive || queued.items.length ? 5 : 20 }, await wakeOf(ctx, tid)));
}

async function relayOf(ctx, tid) {
  const r = await ctx.data.get(ST, P(tid, "relay"));
  if (!r) return null;
  delete r.seen;
  return r;
}

function storeOf(s) {
  if (!s || typeof s !== "object") return null;
  const h = s.history && typeof s.history === "object" ? s.history : {};
  const state = ["idle", "running", "done", "paused"].includes(h.state) ? h.state : "idle";
  return {
    allowed: !!s.allowed,
    lastScan: num(s.lastScan),
    history: { state, done: num(h.done), total: num(h.total), at: num(h.at), media: !!h.media }
  };
}

// ------------------------------------------------------------------ inbox devices

async function inbox(ctx, me) {
  const tid = me.tid;
  const feed = "f:" + pad13(Date.now());
  const all = [];
  let cursor = null;
  for (let page = 0; page < 20; page++) {
    const r = await ctx.data.list(CONV, { keyPrefix: P(tid, ""), limit: 100, cursor });
    for (const i of r.items) if (i.value) all.push(i.value);
    cursor = r.cursor;
    if (!cursor) break;
  }
  const items = all.sort((a, b) => (b.lastTs || 0) - (a.lastTs || 0));
  const latest = await ctx.data.list(MSG, { keyPrefix: P(tid, "m:"), order: "desc", limit: 1 });
  const relay = await relayOf(ctx, tid);
  await touchViewer(ctx, tid);
  return json(200, {
    items, feed, epoch: await epochOf(ctx, tid), latestKey: latest.items.length ? unP(tid, latest.items[0].key) : null,
    relay, serverTime: Date.now()
  });
}

async function thread(ctx, me, body, q) {
  const tid = me.tid;
  const conv = convId(q.conv);
  if (!conv) throw bad("conv required");
  const page = await ctx.data.query(MSG, "by-conv", { eq: P(tid, conv), order: "desc", limit: 50, cursor: q.cursor || null });
  const items = page.items.map(i => pub(i.value)).reverse();
  const conversation = await ctx.data.get(CONV, P(tid, conv));
  return json(200, { items, cursor: page.cursor, conversation });
}

async function sync(ctx, me, body, q) {
  const tid = me.tid;
  const relay = await relayOf(ctx, tid);
  await touchViewer(ctx, tid);
  const cursor = typeof q.feed === "string" && /^f:[0-9]{13}/.test(q.feed) && q.feed.length <= 200 ? q.feed : "f:" + pad13(Date.now() - 60000);
  return json(200, Object.assign(await feedSince(ctx, tid, cursor), { relay, epoch: await epochOf(ctx, tid), serverTime: Date.now() }));
}

async function feedSince(ctx, tid, cursor) {
  if (Date.now() - Number(cursor.slice(2, 15)) > FEED_TTL_SECONDS * 1000 - 6 * 3600000) {
    return { items: [], convs: [], cursor: "f:" + pad13(Date.now()), more: false, reset: true };
  }
  const latest = new Map();
  const late = await ctx.data.list(FEED, {
    keyPrefix: P(tid, "f:"), startAfter: P(tid, "f:" + pad13(Number(cursor.slice(2, 15)) - FEED_SKEW_MS)), endBefore: P(tid, cursor), order: "desc", limit: 50
  });
  for (const i of late.items.reverse()) if (i.value && i.value.k) latest.set(i.value.k, i.value);
  let next = cursor;
  let more = false;
  let page = { cursor: null };
  for (let n = 0; n < 3; n++) {
    page = await ctx.data.list(FEED, { keyPrefix: P(tid, "f:"), startAfter: P(tid, cursor), order: "asc", limit: 100, cursor: page.cursor });
    for (const i of page.items) {
      if (i.value && i.value.k) { latest.delete(i.value.k); latest.set(i.value.k, i.value); }
      next = unP(tid, i.key);
    }
    more = !!page.cursor;
    if (!more) break;
  }
  const reload = [...new Set([...latest.values()].filter(v => v.touch).map(v => v.conv))];
  const items = [...latest.values()].filter(v => !v.touch).map(pub);
  const convIds = [...new Set(items.map(m => m.conv).concat(reload).filter(Boolean))];
  const convs = [];
  for (let i = 0; i < convIds.length; i += 25) {
    const r = await ctx.data.batchGet(CONV, convIds.slice(i, i + 25).map(c => P(tid, c)));
    for (const x of r.items) if (x.found && x.value) convs.push(x.value);
  }
  return { items, convs, reload, cursor: next, more };
}

/**
 * A reply to send. `e` is how the inbox shows it; `o` is the request the relay phone acts on, sealed for this
 * conversation alone and carrying a one-time nonce, so it can't be pointed at anyone else or sent twice. A
 * conversation the inbox doesn't have yet is one the device is starting (PROTOCOL.md, New conversations): it comes
 * with its sealed name (`ne`), and is made here with it; without that, an unknown conversation is refused.
 */
async function send(ctx, me, body) {
  const tid = me.tid;
  let conv = convId(body.conv);
  const e = sealed(body.e);
  const o = sealed(body.o);
  if (!conv || !e || !o) throw bad("conv, e and o required");
  // A conversation the relay phone merged away: the reply goes to the one it went into (the relay phone opens a
  // request sealed for a conversation it merged, see PROTOCOL.md, Merges).
  conv = await homeOf(ctx, tid, conv);
  const now = Date.now();
  let c = await ctx.data.get(CONV, P(tid, conv));
  if (!c) {
    const ne = sealed(body.ne);
    if (!ne) return json(404, { error: "unknown conversation" });
    c = await updateConv(ctx, tid, conv, cur => cur || {
      conv, ne, lastTs: 0, lastE: "", lastDir: "out", lastMedia: false, canReply: true, unread: 0, started: true, createdAt: now, updatedAt: now
    });
  }
  const k = "m:" + pad13(now) + ":o" + rid();
  const value = { k, conv, cv: P(tid, conv), qs: P(tid, "queued"), dir: "out", status: "queued", e, o, ts: now, createdAt: now, src: "texts" };
  if (typeof body.mt === "string" && MT_RE.test(body.mt)) value.mt = body.mt;
  if (c.ne) value.ne = c.ne;
  // An attachment this device stored first (sealed, under this inbox and conversation): the relay phone fetches it
  // from the path inside the sealed request; every inbox device from the message's record.
  const media = mediaOf(tid, conv, body.media);
  const withMedia = !!(media && media.path);
  if (withMedia) value.media = media;
  await ctx.data.put(MSG, P(tid, k), value);
  await updateConv(ctx, tid, conv, cur => {
    if (!cur) return null;
    cur.lastTs = now; cur.lastE = e; cur.lastDir = "out"; cur.lastMedia = withMedia; cur.updatedAt = now;
    return cur;
  });
  // Merged away while this was being filed: it goes where the rest of that conversation went.
  const later = await homeOf(ctx, tid, conv);
  if (later !== conv) {
    await refile(ctx, tid, k, later);
    value.conv = later;
    value.cv = P(tid, later);
    await updateConv(ctx, tid, later, cur => {
      if (!cur) return null;
      cur.lastTs = now; cur.lastE = e; cur.lastDir = "out"; cur.lastMedia = withMedia; cur.updatedAt = now;
      return cur;
    });
  }
  await feedAdd(ctx, tid, [value]);
  await touchViewer(ctx, tid, true);
  await doorbell(ctx, tid, "o", "queued", { id: k, conv: value.conv });
  return json(200, { id: k, canReply: c.canReply !== false });
}

/** Files a stored message under another conversation (one its own was merged into). */
async function refile(ctx, tid, k, into) {
  for (let attempt = 0; attempt < 6; attempt++) {
    const cur = await ctx.data.getWithMeta(MSG, P(tid, k));
    if (!cur || !cur.value || cur.value.conv === into) return;
    try {
      await ctx.data.put(MSG, P(tid, k), Object.assign({}, cur.value, { conv: into, cv: P(tid, into) }), { ifVersion: cur.version });
      return;
    } catch (e) {
      if (!isConflict(e)) throw e;
    }
  }
}

async function markRead(ctx, me, body) {
  const conv = convId(body.conv);
  if (!conv) throw bad("conv required");
  await updateConv(ctx, me.tid, conv, c => {
    if (!c || !c.unread) return null;
    c.unread = 0;
    return c;
  });
  return json(200, { ok: true });
}

async function status(ctx, me) {
  const relay = await relayOf(ctx, me.tid);
  const queued = await ctx.data.query(MSG, "by-status", { eq: P(me.tid, "queued"), limit: 20 });
  return json(200, { relay, queued: queued.items.length, serverTime: Date.now() });
}

async function pushRegister(ctx, me, body) {
  const id = typeof body.subscriptionId === "string" && /^apppush_[A-Za-z0-9_-]{4,80}$/.test(body.subscriptionId) ? body.subscriptionId : null;
  if (!id) throw bad("subscriptionId required");
  const ids = await editPush(ctx, me.tid, (list, owners) => {
    owners[id] = me.id;
    return [id].concat(list.filter(x => x !== id)).slice(0, 10);
  });
  // Removed while this was on its way: its subscription goes again.
  if (!(await ctx.data.get(DEV, me.id))) {
    await editPush(ctx, me.tid, (list, owners) => list.filter(x => owners[x] !== me.id));
    return json(403, { unlinked: true, error: "This device isn't linked to an inbox." });
  }
  return json(200, { ok: true, count: ids.length });
}

/**
 * Changes an inbox's push subscriptions: `change(ids, owners)` returns the ids to keep (and may note owners). Each
 * subscription remembers the device that registered it, so removing a device stops its notifications too.
 */
async function editPush(ctx, tid, change) {
  for (let attempt = 0; ; attempt++) {
    const cur = await ctx.data.getWithMeta(ST, P(tid, "push"));
    const value = cur && cur.value ? cur.value : {};
    const before = Array.isArray(value.ids) ? value.ids : [];
    const owners = Object.assign({}, value.owners && typeof value.owners === "object" ? value.owners : {});
    const ids = change(before.slice(), owners);
    const kept = {};
    for (const x of ids) if (typeof owners[x] === "string") kept[x] = owners[x];
    if (cur && ids.length === before.length && ids.every((x, i) => x === before[i]) && JSON.stringify(kept) === JSON.stringify(value.owners || {})) return ids;
    if (!cur && !ids.length) return ids;
    try {
      await ctx.data.put(ST, P(tid, "push"), { ids, owners: kept, updatedAt: Date.now() }, cur ? { ifVersion: cur.version } : { ifNotExists: true });
      return ids;
    } catch (e) {
      if (!isConflict(e) || attempt >= 5) throw e;
    }
  }
}

async function pushTest(ctx, me) {
  const cur = (await ctx.data.get(ST, P(me.tid, "push"))) || { ids: [] };
  if (!cur.ids || !cur.ids.length) return json(409, { error: "No device has turned on notifications yet." });
  if (!ctx.push) return json(503, { error: "Push is not enabled for this app." });
  const r = await ctx.push.sendToVisitorSubscriptions(cur.ids, { title: "Text Relay", body: "Notifications are working on this device.", url: "/", tag: "relay-test" });
  return json(200, { ok: true, result: r });
}

// ------------------------------------------------------------------ moving a v1 inbox in
// The inbox device that held the v1 inbox seals each message itself and sends it here with the same keys the relay
// phone would give it, so later copies from the phone fold into what was moved. Safe to send again.

async function importItems(ctx, me, body) {
  const tid = me.tid;
  const now = Date.now();
  const list = Array.isArray(body.messages) ? body.messages.slice(0, 50) : [];
  const msgs = [];
  const aliases = [];
  for (const m of list) {
    if (!m || typeof m !== "object" || typeof m.k !== "string" || !KEY_RE.test(m.k)) continue;
    const conv = convId(m.conv);
    const e = sealed(m.e);
    if (!conv || !e) continue;
    const out = m.dir === "out";
    let status = ["received", "sent", "failed"].includes(m.status) ? m.status : out ? "sent" : "received";
    const v = {
      k: m.k, conv, cv: P(tid, conv), dir: out ? "out" : "in", status, e,
      ts: Number(m.k.slice(2, 15)), src: ["notif", "store", "texts"].includes(m.src) ? m.src : out ? "texts" : "notif",
      receivedAt: Number.isFinite(m.receivedAt) ? m.receivedAt : now, imported: true
    };
    if (m.status === "queued") { v.status = "failed"; v.code = "before_move"; }   // never sent by surprise
    const ne = sealed(m.ne);
    if (ne) v.ne = ne;
    if (typeof m.mt === "string" && MT_RE.test(m.mt)) v.mt = m.mt;
    const media = mediaOf(tid, conv, m.media);
    if (media) v.media = media;
    if (Array.isArray(m.from)) v.from = [...new Set(m.from.filter(s => ["notif", "store", "texts"].includes(s)))];
    if (Array.isArray(m.twins)) v.twins = m.twins.filter(k => typeof k === "string" && KEY_RE.test(k)).slice(-8);
    if (m.history) v.history = true;
    if (m.read) v.read = true;
    if (out) {
      v.via = ["sms", "rcs", "phone"].includes(m.via) ? m.via : "phone";
      v.sentAt = Number.isFinite(m.sentAt) ? m.sentAt : v.ts;
    }
    msgs.push(v);
    for (const t of v.twins || []) aliases.push({ key: P(tid, t), value: { to: v.k, conv } });
  }
  for (let i = 0; i < msgs.length; i += 10) await ctx.data.batchPut(MSG, msgs.slice(i, i + 10).map(v => ({ key: P(tid, v.k), value: v })));
  for (let i = 0; i < aliases.length; i += 25) await ctx.data.batchPut(TWINS, aliases.slice(i, i + 25));

  let convs = 0;
  for (const c of (Array.isArray(body.convs) ? body.convs.slice(0, 25) : [])) {
    const conv = c && convId(c.conv);
    if (!conv) continue;
    const lastE = sealed(c.lastE);
    const incoming = {
      ne: sealed(c.ne) || null, lastTs: num(c.lastTs), lastE: lastE || "", lastDir: c.lastDir === "out" ? "out" : "in",
      lastMedia: !!c.lastMedia, canReply: c.canReply !== false, unread: Math.max(0, Math.floor(num(c.unread)))
    };
    await updateConv(ctx, tid, conv, existing => {
      if (existing && existing.imported) return null;   // moved already: a second try changes nothing
      const base = existing || { conv, canReply: incoming.canReply, unread: 0, lastTs: 0 };
      const newer = incoming.lastTs > (base.lastTs || 0);
      return Object.assign({}, base, {
        conv,
        ne: base.ne || incoming.ne,
        lastTs: newer ? incoming.lastTs : base.lastTs,
        lastE: newer ? incoming.lastE : base.lastE,
        lastDir: newer ? incoming.lastDir : base.lastDir,
        lastMedia: newer ? incoming.lastMedia : !!base.lastMedia,
        unread: (base.unread || 0) + incoming.unread,
        imported: true,
        updatedAt: now
      });
    });
    convs++;
  }
  return json(200, { ok: true, messages: msgs.length, convs });
}

/** Everything moved: every inbox device reloads. */
async function importDone(ctx, me) {
  const epoch = await bumpEpoch(ctx, me.tid);
  await doorbell(ctx, me.tid, "i", "reload", { epoch });
  return json(200, { ok: true, epoch });
}

// ------------------------------------------------------------------ media
// Sealed files, stored as opaque bytes under /media/v2/<tid>/<conv>/. The phones know what each one is.

function mediaPath(tid, conv, ts, fp, kind) {
  return "/media/v2/" + tid + "/" + conv + "/" + pad13(ts) + "-" + fp + "-" + (kind === "preview" ? "p" : "o") + ".enc";
}

function mediaOwned(tid, p) {
  return typeof p === "string" && MEDIA_RE.test(p) && p.startsWith("/media/v2/" + tid + "/") ? p : null;
}

async function mediaPut(ctx, me) {
  const conv = convId(header(ctx, "x-relay-conv"));
  const fp = header(ctx, "x-relay-fp");
  const ts = Number(header(ctx, "x-relay-ts"));
  const kind = header(ctx, "x-relay-kind") === "preview" ? "preview" : "original";
  if (!conv || !/^[a-z0-9]{8,32}$/.test(fp) || !Number.isFinite(ts) || ts <= 0) throw bad("x-relay-conv, x-relay-fp and x-relay-ts headers are required");
  const bytes = await ctx.request.readBytes();
  if (!bytes || !bytes.byteLength) throw bad("empty body");
  if (bytes.byteLength > DIRECT_MAX) return json(413, { error: "Too large for a direct upload; use a ticket.", max: DIRECT_MAX });
  const path = mediaPath(me.tid, conv, Math.floor(ts), fp, kind);
  await ctx.files.put(path, bytes.base64, { contentType: FILE_TYPE });
  return json(200, { ok: true, path, bytes: bytes.byteLength, kind });
}

async function mediaTicket(ctx, me, body) {
  const conv = convId(body.conv);
  const fp = typeof body.fp === "string" && /^[a-z0-9]{8,32}$/.test(body.fp) ? body.fp : null;
  const ts = Number.isFinite(body.ts) && body.ts > 0 ? Math.floor(body.ts) : null;
  const size = Number.isFinite(body.bytes) ? body.bytes : 0;
  if (!conv || !fp || !ts) throw bad("conv, fp and ts are required");
  if (size > TICKET_MAX) return json(413, { error: "Larger than " + TICKET_MAX + " bytes.", max: TICKET_MAX });
  if (size <= 0) throw bad("bytes (the exact file size) is required");
  const path = mediaPath(me.tid, conv, ts, fp, body.kind === "preview" ? "preview" : "original");
  const t = await ctx.files.createUploadTicket({ pathPrefix: "/media/v2/" + me.tid + "/", contentType: FILE_TYPE, maxBytes: size, ttlSeconds: 900 });
  return json(200, { ok: true, path, ticket: t.ticket, uploadUrl: t.uploadUrl, headers: t.headers || {} });
}

async function mediaComplete(ctx, me, body) {
  const path = mediaOwned(me.tid, body.path);
  const tk = typeof body.ticket === "string" && body.ticket.length < 4096 ? body.ticket : null;
  if (!path || !tk) throw bad("ticket and path are required");
  const stored = await ctx.files.completeUploadTicket(tk, { path });
  return json(200, { ok: true, path, bytes: stored && stored.byteLength });
}

async function mediaGet(ctx, me, body, q) {
  const p = mediaOwned(me.tid, q.p);
  if (!p) throw bad("bad media path");
  const signed = await ctx.files.signedUrl(p, { ttlSeconds: 3600 });
  if (!signed || !signed.url) return json(404, { error: "no such file" });
  return json(200, { url: signed.url, expiresIn: 3600 });
}

/** Where a message's sealed files are: only paths of this inbox and conversation. An empty object: one is coming. */
function mediaOf(tid, conv, m, sealedFor) {
  if (!m || typeof m !== "object") return null;
  const out = {};
  const own = (p) => {
    const ok = mediaOwned(tid, p);
    return ok && [conv, sealedFor || conv].some(c => ok.startsWith("/media/v2/" + tid + "/" + c + "/")) ? ok : null;
  };
  const path = own(m.path);
  const preview = own(m.preview);
  if (path) out.path = path;
  if (preview) out.preview = preview;
  return out;
}

// ------------------------------------------------------------------ helpers

async function feedAdd(ctx, tid, values) {
  if (!values.length) return;
  const at = pad13(Date.now());
  // The log keeps what inbox devices get: not a reply's request (`o`) or the index fields.
  const entries = values.map(v => ({ key: P(tid, "f:" + at + ":" + v.k), value: pub(v), ttlSeconds: FEED_TTL_SECONDS }));
  for (let i = 0; i < entries.length; i += 25) await ctx.data.batchPut(FEED, entries.slice(i, i + 25));
}

/** A content-free nudge on the inbox's own realtime channel. Never fatal. */
async function doorbell(ctx, tid, who, type, payload) {
  if (!ctx.realtime || typeof ctx.realtime.publish !== "function") return;
  try {
    await ctx.realtime.publish("tr2/" + tid + "/" + who, Object.assign({ at: Date.now() }, payload || {}), { type });
  } catch (e) {
    console.warn("doorbell failed", who, type, e && e.message);
  }
}

async function pushIds(ctx, tid) {
  const cur = await ctx.data.get(ST, P(tid, "push"));
  return cur && Array.isArray(cur.ids) ? cur.ids : [];
}

async function pushAll(ctx, tid, msg) {
  if (!ctx.push) return;
  try {
    const ids = await pushIds(ctx, tid);
    if (ids.length) await ctx.push.sendToVisitorSubscriptions(ids, msg);
  } catch (e) {
    console.warn("push failed", e && e.message);
  }
}

/** Web push can't carry what the server can't read: it only says that a text came in. */
async function notify(ctx, tid, msgs) {
  if (!msgs.length || !ctx.push) return;
  const ids = await pushIds(ctx, tid);
  if (!ids.length) return;
  const rejected = new Set();
  const convs = [...new Set(msgs.map(m => m.conv))].slice(0, 5);
  for (const conv of convs) {
    const last = msgs.filter(m => m.conv === conv).pop();
    try {
      const r = await ctx.push.sendToVisitorSubscriptions(ids, {
        title: "New text", body: "Open Texts to read it.", url: "/?conv=" + encodeURIComponent(conv), tag: "conv-" + conv,
        idempotencyKey: "msg-" + tid + "-" + last.k
      });
      if (r && Array.isArray(r.rejectedRecipients)) for (const x of r.rejectedRecipients) rejected.add(typeof x === "string" ? x : x && x.id);
    } catch (e) {
      console.warn("push failed", String(e && e.message || e));
    }
  }
  if (rejected.size) await editPush(ctx, tid, list => list.filter(id => !rejected.has(id)));
}

async function touchViewer(ctx, tid, force) {
  const now = Date.now();
  const v = await ctx.data.get(ST, P(tid, "viewer"));
  if (force || !v || now - (v.lastActive || 0) > 60000) await ctx.data.put(ST, P(tid, "viewer"), { lastActive: now });
}

async function isViewerActive(ctx, tid) {
  const v = await ctx.data.get(ST, P(tid, "viewer"));
  return !!(v && Date.now() - (v.lastActive || 0) < 180000);
}

/** A record as devices get it: without the index fields, which only say what the record is filed under. */
function pub(v) {
  if (!v || typeof v !== "object") return v;
  const out = Object.assign({}, v);
  delete out.cv;
  delete out.qs;
  delete out.o;
  delete out.error;   // nothing in the server's own words reaches a device
  return out;
}

function header(ctx, name) {
  const h = ctx.request.headers || {};
  const want = name.toLowerCase();
  for (const k of Object.keys(h)) if (k.toLowerCase() === want) return typeof h[k] === "string" ? h[k].trim() : "";
  return "";
}

function queryOf(ctx) {
  const q = ctx.request.query;
  if (q && typeof q === "object") return q;
  const out = {};
  if (typeof q === "string") {
    for (const part of q.replace(/^\?/, "").split("&")) {
      if (!part) continue;
      const i = part.indexOf("=");
      out[decodeURIComponent(i < 0 ? part : part.slice(0, i))] = i < 0 ? "" : decodeURIComponent(part.slice(i + 1).replace(/\+/g, " "));
    }
  }
  return out;
}

/** The JSON body. A file upload (any other content type) has none: its bytes come from ctx.request.readBytes(). */
async function bodyOf(ctx) {
  const type = header(ctx, "content-type").toLowerCase();
  if (type && !type.includes("json")) return {};
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

function isConflict(e) {
  return !!(e && e.name === "ConflictError");
}

async function same(ctx, a, b) {
  return typeof a === "string" && typeof b === "string" && a.length === b.length && !!(await ctx.crypto.timingSafeEqual(a, b));
}

function roleOf(v) {
  return v === "relay" ? "relay" : v === "inbox" ? "inbox" : null;
}

function hex64(v) {
  return typeof v === "string" && HEX64_RE.test(v) ? v : null;
}

function sealed(v) {
  return typeof v === "string" && SEALED_RE.test(v) ? v : null;
}

function convId(v) {
  return typeof v === "string" && /^[a-z0-9_-]{4,64}$/i.test(v) ? v : null;
}

function pad13(n) {
  return String(Math.max(0, Math.floor(n))).padStart(13, "0");
}

function rid() {
  return Math.random().toString(36).slice(2, 10).padEnd(8, "0");
}

function str(v, max) {
  return typeof v === "string" ? v.slice(0, max) : (v == null ? "" : String(v).slice(0, max));
}

function num(v) {
  return Number.isFinite(v) ? v : 0;
}
