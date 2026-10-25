// Exchanges a one-time code for a device key. POST /pair, public, rate-limited, with a lockout after repeated misses.
// settings:pairing = { relayKey, inboxKey, codes: [{ hash, role, createdAt, expiresAt }] }
//   codes: made on the relay device (role inbox, for linking a phone) or shown by a relay device that is not linked yet
//          and registered by a linked phone that scanned it (role relay). Only their SHA-256 is stored; each works once,
//          for its role, until it expires.
// A relay device showing its code asks with wait: true until a phone has scanned it; that answers 202 and is not a miss.
// A wrong code answers 403, not 401: Java's HttpURLConnection discards the body of a 401, and both apps show this message.
// settings:phones = { relay: [{ tag, at }], inbox: [{ tag, at }] }: phones the inbox has linked. A tag is the SHA-256 of
//   the role and the app's own Android ID on that phone, which stays the same when the app is reinstalled there and is
//   different on every other phone. A phone showing a known tag gets its key back without a code (Relay or Texts was
//   reinstalled on it). A tag the inbox doesn't know is never counted as a wrong code.
const SET = "settings";
const PHONES = "phones";
const TAG_RE = /^[a-f0-9]{64}$/;
const MAX_PHONES = 5;

async function handler(ctx) {
  if ((ctx.request.method || "").toUpperCase() !== "POST") return json(405, { error: "POST only." });
  let body = {};
  try {
    body = JSON.parse(ctx.request.body || "{}");
  } catch {
    return json(400, { error: "Request body must be valid JSON." });
  }
  const role = body.role === "relay" ? "relay" : "inbox";
  const code = normalizeCode(typeof body.code === "string" ? body.code : "");
  const tag = typeof body.device === "string" && TAG_RE.test(body.device) ? body.device : null;
  if (code.length < 6 && !tag) return json(400, { error: "Enter the code." });
  const waiting = body.wait === true && code.length >= 12;

  const now = Date.now();
  if (tag && await knownPhone(ctx, role, tag)) {
    const pairing = await ctx.data.get(SET, "pairing");
    const key = pairing && (role === "relay" ? pairing.relayKey : pairing.inboxKey);
    if (!key) return json(503, { error: "No device key is stored for this role." });
    return json(200, { ok: true, role, key, known: true });
  }
  if (code.length < 6) return json(404, { known: false, error: "This phone hasn't been linked to your inbox before." });

  const lock = (await ctx.data.get(SET, "pairlock")) || { fails: 0 };
  if (lock.until && lock.until > now) {
    return json(429, { error: "Too many wrong codes. Try again in " + Math.ceil((lock.until - now) / 60000) + " min." });
  }
  const pairing = await ctx.data.get(SET, "pairing");
  if (!pairing) return json(503, { error: "Linking is not set up on the server yet." });

  const before = Array.isArray(pairing.codes) ? pairing.codes : [];
  const live = before.filter(c => c && typeof c.hash === "string" && c.expiresAt > now);
  const h = String(await ctx.crypto.sha256(code));
  let match = -1;
  let otherRole = false;
  // Compare against every live code, so the time taken does not depend on where a match sits.
  for (let i = 0; i < live.length; i++) {
    const same = await ctx.crypto.timingSafeEqual(h, String(live[i].hash));
    if (!same) continue;
    if (live[i].role === role) { if (match < 0) match = i; }
    else otherRole = true;
  }

  if (match < 0) {
    if (live.length !== before.length) await save(ctx, pairing, live);
    if (waiting && !otherRole) return json(202, { waiting: true });
    const fails = (lock.fails || 0) + 1;
    const next = { fails, lastFail: now };
    if (fails >= 10) next.until = now + 15 * 60000;
    await ctx.data.put(SET, "pairlock", next, { ttlSeconds: 1800 });
    const why = otherRole
      ? (role === "relay" ? "That code is for linking a phone with Texts, not a relay phone." : "That code is for connecting a relay phone. Scan the code under Link a device in Relay.")
      : "That code is not right, or it has expired. Make a new one in Relay.";
    return json(403, { error: why + (fails >= 10 ? " Linking is paused for 15 minutes." : "") });
  }

  live.splice(match, 1);   // one use only
  await save(ctx, pairing, live);
  if (lock.fails) {
    try { await ctx.data.delete(SET, "pairlock"); } catch { /* already gone */ }
  }
  const key = role === "relay" ? pairing.relayKey : pairing.inboxKey;
  if (!key) return json(503, { error: "No device key is stored for this role." });
  if (tag) await rememberPhone(ctx, role, tag, now);
  return json(200, { ok: true, role, key });
}

async function save(ctx, pairing, codes) {
  pairing.codes = codes;
  await ctx.data.put(SET, "pairing", pairing);
}

async function knownPhone(ctx, role, tag) {
  const phones = (await ctx.data.get(SET, PHONES)) || {};
  const list = Array.isArray(phones[role]) ? phones[role] : [];
  let known = false;
  for (const p of list) {
    if (p && typeof p.tag === "string" && p.tag.length === tag.length && await ctx.crypto.timingSafeEqual(tag, p.tag)) known = true;
  }
  return known;
}

/** Adds a phone to its role's list (newest first, at most five), with a version check so two links never lose one. */
async function rememberPhone(ctx, role, tag, now) {
  for (let attempt = 0; ; attempt++) {
    const cur = await ctx.data.getWithMeta(SET, PHONES);
    const phones = cur && cur.value && typeof cur.value === "object" ? cur.value : {};
    const list = (Array.isArray(phones[role]) ? phones[role] : []).filter(p => p && p.tag !== tag);
    list.unshift({ tag, at: now });
    phones[role] = list.slice(0, MAX_PHONES);
    try {
      await ctx.data.put(SET, PHONES, phones, cur ? { ifVersion: cur.version } : { ifNotExists: true });
      return;
    } catch (e) {
      if (!(e && e.name === "ConflictError") || attempt >= 5) throw e;
    }
  }
}

/** Codes: case, spaces and dashes don't matter, and the look-alikes O, I and L read as 0, 1 and 1. */
function normalizeCode(v) {
  return String(v || "").toLowerCase().replace(/[^a-z0-9]/g, "").replace(/o/g, "0").replace(/[il]/g, "1").slice(0, 64);
}

function json(status, body) {
  return { status, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" }, body };
}
