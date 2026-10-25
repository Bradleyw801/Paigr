"use strict";
// The inbox's data store, behaving as the hosted platform's does: collections of keyed JSON items, each with a version,
// conditional writes (ifVersion, ifNotExists), batches of up to 25, key listings, the four indexes inbox2.js and
// watch2.js query, and items that expire (ttlSeconds).
//
// The tests run on it in memory (test/harness.js). The self-hosted server (paigr-server.js) runs this same code with
// `persist` writing every change to disk and `expire` on, so what the tests check is what people run.

const INDEXES = {
  messages: { "by-conv": ["conv", "k"], "by-status": ["status", "k"] },
  "v2-messages": { "by-conv": ["cv", "k"], "by-status": ["qs", "k"] },
  "v2-devices": { "by-tenant": ["tid", "at"] },
  "v2-state": { "by-seen": ["seen", "seen"] }
};

const clone = (v) => JSON.parse(JSON.stringify(v));

/**
 * opts.persist: { apply(rows) } saves changes before they're made in memory, all of one call's rows together or none.
 *   A row is { c, k, value, version, expiresAt } (expiresAt null when it doesn't expire), or { c, k, gone: true }.
 * opts.expire: true to treat an item past its ttlSeconds as gone (the tests keep everything).
 * opts.now: the clock.
 */
function createStore(opts) {
  opts = opts || {};
  const persist = opts.persist || null;
  const expire = !!opts.expire;
  const now = opts.now || Date.now;
  const store = new Map();
  const versions = new Map();
  const expiry = new Map();
  const id = (c, k) => c + "\u0000" + k;
  const col = (c) => store.get(c) || store.set(c, new Map()).get(c);
  const ver = (c, k) => versions.get(id(c, k)) || 0;
  const conflict = (n) => Object.assign(new Error("version conflict"), { name: "ConflictError", currentVersion: n });
  /** Tests set raceOnce = { c, k, mutate } to have "another request" change an item just before a conditional put. */
  const hooks = { raceOnce: null };

  /** Saves the rows, then makes them so here. When saving fails nothing changes, and the caller hears why. */
  function commit(rows) {
    if (persist && rows.length) persist.apply(rows);
    for (const r of rows) {
      if (r.gone) {
        col(r.c).delete(r.k);
        versions.delete(id(r.c, r.k));
        expiry.delete(id(r.c, r.k));
      } else {
        col(r.c).set(r.k, r.value);
        versions.set(id(r.c, r.k), r.version);
        if (r.expiresAt == null) expiry.delete(id(r.c, r.k)); else expiry.set(id(r.c, r.k), r.expiresAt);
      }
    }
  }

  /** Whether the item is there. One whose time is up is gone: dropped here now, and from the disk when it can be. */
  function alive(c, k) {
    if (!col(c).has(k)) return false;
    if (!expire) return true;
    const at = expiry.get(id(c, k));
    if (at === undefined || at > now()) return true;
    try { commit([{ c, k, gone: true }]); } catch {
      col(c).delete(k); versions.delete(id(c, k)); expiry.delete(id(c, k));   // the disk catches up at the next start
    }
    return false;
  }

  /** The row that writes v under c/k, one version on from `after`. */
  function row(c, k, v, ttlSeconds, after) {
    return { c, k, value: clone(v), version: after + 1, expiresAt: ttlSeconds > 0 ? now() + ttlSeconds * 1000 : null };
  }

  /** Clones only what the page hands out. */
  function page(entries, o) {
    const start = o.cursor ? Number(o.cursor) : 0;
    const lim = Math.max(1, Math.min(100, o.limit || 50));
    const items = entries.slice(start, start + lim).map(([k, v, version]) => ({ key: k, value: clone(v), version }));
    return { items, cursor: start + lim < entries.length ? String(start + lim) : null };
  }

  const data = {
    async get(c, k) { return alive(c, k) ? clone(col(c).get(k)) : null; },
    async getWithMeta(c, k) { return alive(c, k) ? { key: k, value: clone(col(c).get(k)), version: ver(c, k) } : null; },
    async put(c, k, v, o) {
      o = o || {};
      for (const name of Object.keys(o)) if (!["ifVersion", "ifNotExists", "ttlSeconds"].includes(name)) throw new Error("put: unknown option " + name);
      const race = hooks.raceOnce;
      if (race && race.c === c && race.k === k && (o.ifVersion !== undefined || o.ifNotExists)) {
        hooks.raceOnce = null;
        const cur = col(c).get(k);
        if (cur !== undefined) {
          const changed = clone(cur);
          race.mutate(changed);
          commit([{ c, k, value: changed, version: ver(c, k) + 1, expiresAt: expiry.has(id(c, k)) ? expiry.get(id(c, k)) : null }]);
        }
      }
      const exists = alive(c, k);
      if (o.ifNotExists && exists) throw conflict(ver(c, k));
      if (o.ifVersion !== undefined && (exists ? ver(c, k) : 0) !== o.ifVersion) throw conflict(ver(c, k));
      const r = row(c, k, v, o.ttlSeconds, ver(c, k));
      commit([r]);
      return { version: r.version };
    },
    async delete(c, k) { if (col(c).has(k)) commit([{ c, k, gone: true }]); },
    async batchGet(c, keys) {
      if (keys.length > 25) throw new Error("batchGet > 25");
      return { items: [...new Set(keys)].map(k => { const found = alive(c, k); return { key: k, value: found ? clone(col(c).get(k)) : null, found, version: ver(c, k) }; }) };
    },
    async batchPut(c, items) {
      if (items.length > 25) throw new Error("batchPut > 25");
      const at = new Map();   // a key named twice in one batch goes up twice, as two puts would
      const rows = items.map(i => {
        const r = row(c, i.key, i.value, i.ttlSeconds, at.has(i.key) ? at.get(i.key) : ver(c, i.key));
        at.set(i.key, r.version);
        return r;
      });
      commit(rows);
      return { items: items.map(i => ({ key: i.key, version: ver(c, i.key) })) };
    },
    async batchDelete(c, keys) {
      if (keys.length > 25) throw new Error("batchDelete > 25");
      const there = [...new Set(keys)].filter(k => alive(c, k));
      commit(there.map(k => ({ c, k, gone: true })));
      return { deleted: there.length };
    },
    async list(c, o) {
      for (const name of Object.keys(o)) if (!["keyPrefix", "startAfter", "endBefore", "order", "limit", "cursor"].includes(name)) throw new Error("list: unknown option " + name);
      let keys = [...col(c).keys()].sort();
      if (o.keyPrefix) keys = keys.filter(k => k.startsWith(o.keyPrefix));
      if (o.startAfter) keys = keys.filter(k => k > o.startAfter);
      if (o.endBefore) keys = keys.filter(k => k < o.endBefore);
      keys = keys.filter(k => alive(c, k));
      if (o.order === "desc") keys.reverse();
      return page(keys.map(k => [k, col(c).get(k), ver(c, k)]), o);
    },
    async query(c, index, o) {
      for (const name of Object.keys(o)) if (!["eq", "prefix", "lt", "lte", "gt", "gte", "order", "limit", "cursor"].includes(name)) throw new Error("query: unknown option " + name);
      const [field, sort] = INDEXES[c][index];
      const keep = (x) => {
        if (x == null || (typeof x !== "string" && typeof x !== "number" && typeof x !== "boolean")) return false;
        const s = String(x);
        if (o.eq !== undefined && s !== String(o.eq)) return false;
        if (o.prefix !== undefined && !s.startsWith(o.prefix)) return false;
        if (o.lt !== undefined && !(s < String(o.lt))) return false;
        if (o.lte !== undefined && !(s <= String(o.lte))) return false;
        if (o.gt !== undefined && !(s > String(o.gt))) return false;
        if (o.gte !== undefined && !(s >= String(o.gte))) return false;
        return true;
      };
      const rows = [...col(c).entries()].filter(([k, v]) => v && keep(v[field]) && alive(c, k))
        .sort((a, b) => String(a[1][sort]) < String(b[1][sort]) ? -1 : String(a[1][sort]) > String(b[1][sort]) ? 1 : 0);
      if (o.order === "desc") rows.reverse();
      return page(rows.map(([k, v]) => [k, v, ver(c, k)]), o);
    }
  };

  /** Puts back what was saved, at start. */
  function load(c, k, value, version, expiresAt) {
    col(c).set(k, value);
    versions.set(id(c, k), version);
    if (expiresAt) expiry.set(id(c, k), expiresAt);
  }

  /** Drops every item whose time is up, a few hundred to a save; returns how many went. */
  function sweep() {
    if (!expire) return 0;
    const t = now();
    const gone = [];
    for (const [c, m] of store) {
      for (const k of m.keys()) {
        const at = expiry.get(id(c, k));
        if (at !== undefined && at <= t) gone.push({ c, k, gone: true });
      }
    }
    for (let i = 0; i < gone.length; i += 500) commit(gone.slice(i, i + 500));
    return gone.length;
  }

  /** How many items each collection holds. */
  function counts() {
    const out = {};
    for (const [c, m] of store) out[c] = m.size;
    return out;
  }

  return { data, col, hooks, load, sweep, counts };
}

module.exports = { createStore, INDEXES };
