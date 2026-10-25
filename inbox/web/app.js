/* Paigr: the inbox page. Plain JS, no build step, no Google services needed. */
(function () {
  "use strict";

  const VERSION = "0.17.0";
  const KEY = "relay.key";
  /** This device's place in a private inbox (v2), in a browser: { tid, token, key }. The Texts app keeps it itself. */
  const LINK = "relay.link2";
  /** How far moving the first version's inbox into the private one got: { done, convs: { <v1 conv>: true } }. */
  const MOVED = "relay.moved2";
  /** Set when this phone was unlinked on purpose but the inbox couldn't be told: it then waits for a code. */
  const UNLINKED = "relay.unlinked";
  /** The inbox and name this device last sealed its own name for (nameThisDevice). */
  const NAMED = "relay.named2";
  const PUSH_ID = "relay.pushId";
  const SYNC_MS = 5000;            // polling while the doorbell socket is down
  const SYNC_CONNECTED_MS = 20000;  // insurance only, while the doorbell socket is up
  /** No check-in for this long means the relay's connection has actually failed (matches the server's watch). */
  const RELAY_OFFLINE_MS = 10 * 60000;
  const CODE_MINUTES = 15;
  const PULL_AT = 64;     // pull-to-refresh: how far to pull before letting go refreshes
  const PULL_MAX = 96;

  const $ = (id) => document.getElementById(id);
  const views = { connect: $("view-connect"), inbox: $("view-inbox"), thread: $("view-thread"), details: $("view-details"), settings: $("view-settings") };
  const sleep = (ms) => new Promise(r => setTimeout(r, ms));

  // Inside the Texts Android app the page gets a small bridge: the app keeps the device key and shows notifications.
  const native = window.TextsNative && typeof window.TextsNative.key === "function" ? window.TextsNative : null;
  function nativeCall(name, ...args) {
    if (!native || typeof native[name] !== "function") return undefined;
    try { return native[name](...args); } catch { return undefined; }
  }

  const state = {
    key: (native && nativeCall("key")) || storageGet(KEY) || "",   // the first version's device key
    link: null,                // the private inbox (v2): { tid, token, key }
    keys: null,                // its keys, from TR2.inboxKeys
    move: null,                // the move from the first version, while it runs
    convs: new Map(),          // conv -> conversation record
    msgs: new Map(),           // conv -> array of messages (ascending)
    cursors: new Map(),        // conv -> cursor for earlier pages
    complete: new Set(),       // convs whose whole history is loaded
    feed: null,                // arrival-log cursor: where the next sync picks up
    epoch: null,               // bumps when the inbox reorganizes conversations (two found to be one): reload then
    relay: null,
    current: null,             // open conversation id
    pendingOut: new Set(),     // message keys waiting for a sent/failed status
    syncTimer: null,
    syncing: false,
    view: "inbox",
    loadedInbox: false,
    filter: "",                // inbox search text, lowercased
    find: { open: false, query: "", marks: [], index: -1 },
    detailsTab: "photos",
    scanFor: null,             // what the Texts app's scanner is open for: "link" or "approve"
    replyTo: null,             // the message the next reply quotes
    attachment: null,          // the file to go with the next message: { file, mime, name, bytes, url }
    reactTo: null              // the message the emoji panel reacts to (else the panel types into the reply)
  };

  // ------------------------------------------------------------ helpers

  function show(name) {
    if (name !== "thread" && state.find.open) closeFind(false);
    state.view = name;
    for (const [n, el] of Object.entries(views)) el.hidden = n !== name;
  }

  function toast(text, ms) {
    const t = $("toast");
    t.textContent = text;
    t.hidden = false;
    clearTimeout(toast.timer);
    toast.timer = setTimeout(() => { t.hidden = true; }, ms || 2600);
  }

  /** Linked to an inbox: the private one, or the first version's. */
  function linked() {
    return !!state.link || !!state.key;
  }

  /**
   * One call to the inbox. With a private inbox, /api/… goes to /v2/… with this device's token: what goes up is
   * sealed first and what comes back is opened, so the rest of the page sees the same records either way.
   * { v1: true } talks to the first version's inbox with its key (moving it into the private one).
   */
  async function api(path, opts) {
    const o = Object.assign({ method: "GET" }, opts || {});
    if (o.link && o.link !== state.link) {
      // Work started for one inbox (the move) never goes on into another one this device has since joined.
      const err = new Error("This device's inbox changed.");
      err.stale = true;
      throw err;
    }
    const link = o.link || state.link;
    const v2 = !!link && !o.v1;
    const url = v2 ? path.replace(/^\/api\//, "/v2/") : path;
    const headers = Object.assign({ "accept": "application/json" }, o.headers || {});
    if (v2) headers["x-relay-token"] = link.token;
    else headers["x-hedy-app-key"] = state.key;
    let body = o.body;
    if (v2 && body !== undefined && typeof body !== "string") body = await sealRequest(url, body);
    if (body !== undefined && typeof body !== "string") {
      body = JSON.stringify(body);
      headers["content-type"] = "application/json";
    }
    const res = await fetch(url, { method: o.method, headers, body, cache: "no-store" });
    let data = null;
    const text = await res.text();
    try { data = text ? JSON.parse(text) : null; } catch { data = { error: text.slice(0, 200) }; }
    if (res.status === 401 || res.status === 403) {
      // A private inbox says plainly when a device isn't in it; anything else is a refusal, not an unlink.
      if (!v2 || (data && data.unlinked)) {
        const err = new Error(v2 ? "This phone isn't linked to your inbox any more." : "This device key was not accepted.");
        err.auth = true;
        err.v1 = !v2;
        throw err;
      }
    }
    if (!res.ok) throw new Error(refusal(res.status, data));
    return v2 ? await openResponse(url, data) : data;
  }

  /**
   * What the inbox said when it refused something, in words this page knows: anything else the server writes is never
   * shown (it could say anything, like where to "fix" your inbox).
   */
  /** Two of those sentences name the apps as they were first called; this page says them with today's names. */
  const OWN_WORDS = {
    "A new inbox starts from the code in Relay, on the phone with the SIM.": "A new inbox starts from the code in Paigr Relay, on the phone with the SIM.",
    "A relay phone hands over by showing its own code to Texts.": "A relay phone hands over by showing its own code to Paigr."
  };
  const SERVER_SAYS = new Set([
    "A new inbox starts from the code in Relay, on the phone with the SIM.",
    "A relay phone hands over by showing its own code to Texts.",
    "Link this phone first.",
    "No device has turned on notifications yet.",
    "No such device in this inbox.",
    "Push is not enabled for this app.",
    "That code is taken. Make a new one.",
    "This code belongs to another device.",
    "This code has expired or was already used. Make a new one.",
    "This code has expired. Make a new one.",
    "This code was just used. Make a new one.",
    "This device is already linked to another inbox.",
    "This device is already linked.",
    "This device isn't linked to an inbox.",
    "This inbox already has 8 devices. Remove one in Settings first.",
    "Try again."
  ]);
  function refusal(status, data) {
    const said = data && (typeof data.error === "string" ? data.error : data.error && data.error.message);
    if (typeof said === "string" && SERVER_SAYS.has(said)) return OWN_WORDS[said] || said;
    if (status === 429) return "Too many tries just now. Wait a minute and try again.";
    if (status === 404) return "The inbox doesn't have that (any more).";
    if (status >= 500) return "The inbox had a problem (" + status + "). Try again in a moment.";
    return "The inbox refused that (" + status + ").";
  }

  // ------------------------------------------------------------ sealing and opening (the private inbox)

  // Everything sealed for a conversation opens only as that conversation's (see TR2.AAD), so the server can't show one
  // conversation's messages, name or pictures as another's, and a reply can't go to someone other than who's shown.
  // A conversation also accepts what was sealed for the ones the relay phone said it merged into it (its sealed merge
  // records, `merged`): those are its aliases.
  const opened = new Map();   // sealed payload -> { value, aad }, so a sync doesn't open the same thing twice

  /** A sealed value opened with the first of `aads` that fits; `{ unreadable: true }` when none does or it's missing. */
  async function openBound(sealed, aads) {
    if (typeof sealed !== "string" || !sealed) return { unreadable: true };   // nothing sealed: nothing to show as checked
    const hit = opened.get(sealed);
    if (hit && aads.includes(hit.aad)) return hit.value;
    for (const aad of aads) {
      let value;
      try { value = await TR2.openJson(state.keys.enc, sealed, aad); } catch { continue; }
      if (!value || typeof value !== "object" || Array.isArray(value)) continue;
      if (opened.size > 8000) opened.clear();
      opened.set(sealed, { value, aad });
      return value;
    }
    return { unreadable: true };
  }

  /** A conversation and the ones its merge records vouch for (merged into it, or into one of those). */
  const aliasSets = new Map();   // conv -> { key, list }
  async function aliasesOf(conv, merged) {
    const records = Array.isArray(merged) ? merged.filter(r => typeof r === "string") : [];
    const key = records.join(",");
    const hit = aliasSets.get(conv);
    if (hit && hit.key === key) return hit.list;
    const set = new Set([conv]);
    const done = new Set();
    for (let grew = true; grew;) {
      grew = false;
      for (const r of records) {
        if (done.has(r)) continue;
        for (const into of [...set]) {
          const v = await openBound(r, [TR2.AAD.merge(into)]);
          if (v.unreadable || v.into !== into || typeof v.from !== "string") continue;
          done.add(r);
          if (!set.has(v.from)) { set.add(v.from); grew = true; }
          break;
        }
      }
    }
    const list = [...set];
    aliasSets.set(conv, { key, list });
    return list;
  }

  /** What the page already knows a conversation answers to; just itself until its summary is opened. */
  function knownAliases(conv) {
    const c = state.convs.get(conv);
    return c && Array.isArray(c.aliases) ? c.aliases : [conv];
  }

  async function sealRequest(url, body) {
    if (url === "/v2/send") {
      const text = String(body.text || "");
      const ts = Number(body.ts) || Date.now();
      // A file sent with it (already sealed and stored, see sendMessage): every inbox device learns what it is from the
      // payload, the relay phone where it is from the request; the body names only where it's stored.
      const att = body.attachment || null;
      const shown = { text, ts };
      const request = { text, n: TR2.b64u(TR2.random(16)), ts };
      const out = { conv: body.conv };
      if (att) {
        shown.fp = att.fp;
        shown.media = { mime: att.mime, w: att.w || 0, h: att.h || 0, bytes: att.bytes };
        request.media = { path: att.path, mime: att.mime, bytes: att.bytes, name: att.name };
        out.media = { path: att.path };
        if (att.preview) out.media.preview = att.preview;
      }
      // How every inbox device shows it: a message this device sent, at this time.
      out.e = await TR2.sealJson(state.keys.enc, shown, TR2.AAD.message(body.conv, "out"));
      // The request the relay phone acts on: sealed for this conversation alone, with a nonce and the time, so the
      // server can't point it at someone else or have it sent twice (see PROTOCOL.md, Replies).
      out.o = await TR2.sealJson(state.keys.enc, request, TR2.AAD.out(body.conv));
      out.mt = await TR2.matchToken(state.keys, "out", text, att ? att.mime : "");
      return out;
    }
    return body;
  }

  async function openResponse(url, data) {
    const path = url.split("?")[0];
    if (!data) return data;
    if (path === "/v2/inbox") data.items = await Promise.all((data.items || []).map(openConv));
    if (path === "/v2/thread") {
      // Every message is checked against the conversation that was asked for, whatever the server says it is.
      const asked = new URL(url, location.origin).searchParams.get("conv") || "";
      if (data.conversation) data.conversation = await openConv(Object.assign({}, data.conversation, { conv: asked }));
      const aliases = data.conversation ? data.conversation.aliases : knownAliases(asked);
      data.items = await Promise.all((data.items || []).map(m => openMessage(Object.assign({}, m, { conv: asked }), aliases)));
    }
    if (path === "/v2/sync") {
      data.convs = await Promise.all((data.convs || []).map(openConv));
      const fresh = new Map(data.convs.filter(c => c && c.conv).map(c => [c.conv, c.aliases]));
      data.items = await Promise.all((data.items || []).map(m => openMessage(m, (m && fresh.get(m.conv)) || knownAliases(m && m.conv))));
    }
    if (data.relay) data.relay = await openRelay(data.relay);
    return data;
  }

  /** The relay phone's status: what it says about its own problems comes sealed. */
  async function openRelay(r) {
    if (!r || typeof r !== "object") return r;
    const said = typeof r.sn === "string" && r.sn ? await openBound(r.sn, [TR2.AAD.note]) : { unreadable: true };
    const out = Object.assign({}, r);
    delete out.sn;
    // Words come only from the relay phone, sealed: whatever the server has in the open is never shown as words.
    out.note = said.unreadable ? "" : String(said.note || "");
    out.model = said.unreadable ? "" : plainName(said.model);
    for (const f of ["lastSeen", "battery", "queued", "replyable", "lastRelayed", "wakeHandled"]) if (out[f] !== undefined && !Number.isFinite(out[f])) delete out[f];
    if (out.store && typeof out.store === "object") {
      out.store = Object.assign({}, out.store);
      if (out.store.history && typeof out.store.history === "object") {
        out.store.history = Object.assign({}, out.store.history, { note: said.unreadable ? "" : String(said.history || "") });
      }
    }
    return out;
  }

  /**
   * A device's name, as the server keeps it (in the open, where it could be anything): shown only as a short plain
   * name, never as a sentence or an address.
   */
  function plainName(s) {
    return String(s || "").replace(/[^\p{L}\p{N} ()+'_-]/gu, " ").replace(/\s+/g, " ").trim().slice(0, 32);
  }

  /**
   * A message as the page shows it: its text, sender, name, time and attachment details, opened as its conversation's
   * and its direction's. One that doesn't open that way (or carries nothing sealed) is shown as unchecked, never as
   * someone else's words, and without any attachment. Of what the server says about a message's files, only where they
   * are is used, and only for files sealed for this message: its time and fingerprint, as its payload names them.
   */
  async function openMessage(m, aliases) {
    if (!m || typeof m !== "object") return m;
    const al = aliases || [m.conv];
    const dir = m.dir === "out" ? "out" : "in";
    const p = await openBound(m.e, al.map(c => TR2.AAD.message(c, dir)));
    const n = m.ne ? await openBound(m.ne, al.map(TR2.AAD.name)) : { unreadable: true };
    const out = Object.assign({}, m, { dir });
    for (const f of ["e", "ne", "mt", "media", "error", "text", "sender", "name", "group", "moved"]) delete out[f];
    out.code = typeof m.code === "string" ? m.code : "";
    // Which message this is, so each shows once: as its payload names it (its own time and keyed fingerprint) when it
    // opens, the same for a copy sealed again (a resync) or filed again; otherwise its seal, which has one spelling.
    out.seal = typeof m.e === "string" && m.e.length >= 22 ? "e:" + m.e.slice(-22) : "";
    const named = !n.unreadable && typeof n.name === "string" ? n.name : "";
    if (p.unreadable) {
      out.text = "(This message couldn't be checked on this device, so it isn't shown.)";
      out.unchecked = true;
      out.sender = "";
      out.name = "";   // not even its conversation's name
      out.group = false;
      return out;
    }
    out.unchecked = false;
    out.text = typeof p.text === "string" ? p.text : "";
    out.sender = typeof p.sender === "string" ? p.sender : "";
    out.name = named || (typeof p.name === "string" ? p.name : "");
    out.group = !!((!n.unreadable && n.group) || p.group);
    out.moved = !!p.moved;
    if (Number.isFinite(p.ts) && p.ts > 0) out.ts = p.ts;   // the time it was sealed with, not the one it's filed under
    if (typeof p.fp === "string" && /^[a-z0-9]{1,40}$/.test(p.fp) && Number.isFinite(p.ts) && p.ts > 0) out.seal = "m:" + dir + ":" + TR2.stemOf(p.ts, p.fp);
    if (p.media && typeof p.media === "object") {
      const media = {};
      if (typeof p.media.mime === "string") media.mime = p.media.mime;
      for (const f of ["w", "h", "bytes"]) if (Number.isFinite(p.media[f]) && p.media[f] > 0) media[f] = p.media[f];
      const stem = typeof p.fp === "string" && Number.isFinite(p.ts) ? TR2.stemOf(p.ts, p.fp) : null;
      const where = m.media && typeof m.media === "object" ? m.media : {};
      for (const [f, kind] of [["path", "o"], ["preview", "p"]]) {
        if (!where[f]) continue;
        const file = TR2.fileOf(where[f]);
        if (file && stem && file.stem === stem && file.kind === kind && file.tid === state.link.tid && al.includes(file.conv)) media[f] = where[f];
        else media.blocked = true;
      }
      out.media = media;
    }
    return out;
  }

  async function openConv(c) {
    if (!c || typeof c !== "object") return c;
    const aliases = await aliasesOf(c.conv, c.merged);
    const lastDir = c.lastDir === "out" ? "out" : "in";
    const last = await openBound(c.lastE, aliases.map(a => TR2.AAD.message(a, lastDir)));
    const n = c.ne ? await openBound(c.ne, aliases.map(TR2.AAD.name)) : { unreadable: true };
    const fromName = n.unreadable ? "" : String(n.name || "");
    const fromLast = last.unreadable ? "" : String(last.name || last.sender || "");
    return {
      conv: c.conv,
      // A name the relay phone sealed beats one carried over from the first version's inbox (marked moved).
      name: (!n.moved && fromName) || (!last.moved && fromLast) || fromName || fromLast || "Unknown",
      group: !!((!n.unreadable && n.group) || (!last.unreadable && last.group)),
      lastTs: !last.unreadable && Number.isFinite(last.ts) && last.ts > 0 ? last.ts : (Number.isFinite(c.lastTs) ? c.lastTs : 0),
      lastText: last.unreadable ? "" : (last.text || (last.media ? mediaKind(last.media) : "")),
      lastDir,
      lastSender: last.unreadable ? "" : (last.sender || ""),
      canReply: c.canReply !== false,
      unread: Number.isFinite(c.unread) && c.unread > 0 ? Math.floor(c.unread) : 0,
      updatedAt: c.updatedAt,
      aliases,
      aliasKey: aliases.join(",")
    };
  }

  function fmtTime(ts) {
    if (!ts) return "";
    const d = new Date(ts), now = new Date();
    if (d.toDateString() === now.toDateString()) return d.toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
    const days = (now - d) / 86400000;
    if (days < 6) return d.toLocaleDateString([], { weekday: "short" });
    return d.toLocaleDateString([], { month: "short", day: "numeric" });
  }

  function fmtDay(ts) {
    const d = new Date(ts), now = new Date();
    if (d.toDateString() === now.toDateString()) return "Today";
    const y = new Date(now); y.setDate(now.getDate() - 1);
    if (d.toDateString() === y.toDateString()) return "Yesterday";
    return d.toLocaleDateString([], { weekday: "short", month: "short", day: "numeric" });
  }

  function fmtClock(ts) {
    return ts ? new Date(ts).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" }) : "";
  }

  function ago(ts) {
    if (!ts) return "never";
    const s = Math.max(0, Math.round((Date.now() - ts) / 1000));
    if (s < 50) return "just now";
    if (s < 3600) return Math.round(s / 60) + " min ago";
    if (s < 86400) return Math.round(s / 3600) + " h ago";
    return Math.round(s / 86400) + " d ago";
  }

  /** Up to two letters (or the first two digits of a number), as the app shows them; "#" when there are none. */
  function initials(name) {
    const n = String(name || "").trim();
    if (!n) return "#";
    const digits = n.replace(/[^0-9+]/g, "").length >= Math.max(3, n.replace(/\s/g, "").length - 1);
    if (digits) {
      const d = n.replace(/[^0-9]/g, "");
      return d.length >= 2 ? d.slice(0, 2) : (d || "#");
    }
    let out = "";
    for (const part of n.split(/\s+/)) {
      const first = [...part][0];
      if (!first || !/[\p{L}\p{N}]/u.test(first)) continue;
      out += first.toUpperCase();
      if ([...out].length === 2) break;
    }
    return out || "#";
  }

  function el(tag, cls, text) {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text !== undefined) e.textContent = text;
    return e;
  }

  function icon(id, cls) {
    const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
    if (cls) svg.setAttribute("class", cls);
    const use = document.createElementNS("http://www.w3.org/2000/svg", "use");
    use.setAttribute("href", "#" + id);
    svg.appendChild(use);
    return svg;
  }

  /** Sets the title (and optional subtitle) inside a settings row button. */
  function setRow(id, title, sub) {
    const b = $(id);
    if (!b) return;
    const t = b.querySelector(".item-title");
    if (t) t.textContent = title;
    const s = b.querySelector(".item-sub");
    if (s && sub !== undefined) s.textContent = sub;
  }

  /** The app's twelve colours round the wheel: base, partner (the far end of its gradient), and a lighter top. */
  const HUES = [
    ["#c4471a", "#b8325a", "#dc6435"], ["#9a5800", "#b4441a", "#b8740f"], ["#557a12", "#2e7d45", "#6e9420"],
    ["#2c7d3e", "#0e7a75", "#3f9952"], ["#0f7b63", "#0b6f8a", "#1f977b"], ["#0b7c84", "#2764c9", "#1c9aa2"],
    ["#1f6ec0", "#4a4cd6", "#3a88d6"], ["#3a5beb", "#6a3fe0", "#5f7bf5"], ["#5348d6", "#8b3fa8", "#6d63e8"],
    ["#7446de", "#a93cb5", "#8e66ea"], ["#9c38b2", "#c22f6c", "#b356c8"], ["#c22f6c", "#c8392f", "#d65289"]
  ];

  /** A conversation's colour, the same one the app gives it: from its id's first eight hex digits. */
  function hueFor(conv) {
    const s = String(conv || "");
    let h;
    if (/^[0-9a-f]{8}/.test(s)) h = BigInt(parseInt(s.slice(0, 8), 16));
    else {
      h = 0xcbf29ce484222325n;   // FNV-1a, for ids that aren't hex
      for (let i = 0; i < s.length; i++) { h ^= BigInt(s.charCodeAt(i)); h = (h * 0x100000001b3n) & 0xffffffffffffffffn; }
      h &= 0x7fffffffffffffffn;
    }
    return HUES[Number(h % BigInt(HUES.length))];
  }

  /** Gives an element (a row, the thread, a badge) its conversation's colour; everything inside it uses it. */
  function paint(node, conv) {
    const [c, p, h] = hueFor(conv);
    node.style.setProperty("--c", c);
    node.style.setProperty("--p", p);
    node.style.setProperty("--h", h);
    node.style.setProperty("--cg", [1, 3, 5].map(i => parseInt(c.slice(i, i + 2), 16)).join(", "));
    return node;
  }

  /** Fills a badge: the conversation's keycap with its initials, or the group glyph. */
  function fillAvatar(node, c) {
    node.innerHTML = "";
    if (c) paint(node, c.conv);
    const text = c && c.group ? "" : initials(c && c.name);
    node.classList.toggle("one", [...text].length === 1);
    if (c && c.group) node.appendChild(icon("i-group"));
    else node.textContent = text;
    return node;
  }

  // ------------------------------------------------------------ message text: links and search highlights

  const URL_RE = /\bhttps?:\/\/[^\s<>"']+|\bwww\.[^\s<>"']+\.[^\s<>"']+/gi;

  function urlsIn(text) {
    const out = [];
    for (const m of String(text || "").matchAll(URL_RE)) out.push(trimUrl(m[0]));
    return out.filter(Boolean);
  }

  function trimUrl(u) {
    return u.replace(/[.,!?;:'")\]]+$/, "");
  }

  function hrefOf(u) {
    return /^https?:\/\//i.test(u) ? u : "https://" + u;
  }

  /** Appends text to node as text nodes, with every occurrence of query wrapped in <mark>. */
  function appendMarked(node, text, query) {
    if (!query) { node.appendChild(document.createTextNode(text)); return; }
    const lower = text.toLowerCase();
    let i = 0;
    while (i < text.length) {
      const at = lower.indexOf(query, i);
      if (at < 0) { node.appendChild(document.createTextNode(text.slice(i))); break; }
      if (at > i) node.appendChild(document.createTextNode(text.slice(i, at)));
      node.appendChild(el("mark", "", text.slice(at, at + query.length)));
      i = at + query.length;
    }
  }

  /** Message text with tappable links; nothing from a message is ever parsed as HTML. */
  function fillText(node, text, query) {
    const s = String(text || "");
    let last = 0;
    for (const m of s.matchAll(URL_RE)) {
      const raw = m[0], url = trimUrl(raw);
      if (m.index > last) appendMarked(node, s.slice(last, m.index), query);
      const a = el("a");
      a.href = hrefOf(url);
      a.target = "_blank";
      a.rel = "noopener noreferrer";
      appendMarked(a, url, query);
      node.appendChild(a);
      if (raw.length > url.length) appendMarked(node, raw.slice(url.length), query);
      last = m.index + raw.length;
    }
    if (last < s.length) appendMarked(node, s.slice(last), query);
  }

  // ------------------------------------------------------------ relay status

  /**
   * Healthy unless the connection has actually failed: no check-in for RELAY_OFFLINE_MS, or the relay reports it lost
   * Messages. A quiet stretch with no new texts is normal and says nothing about the connection.
   */
  /** How the relay phone is doing: a word for the chip, a line for the banner and Settings, and whether to offer a fix. */
  function relayHealth(r) {
    if (!r || !r.lastSeen) return { level: "none", chip: "Relay", text: "Relay phone hasn't checked in yet" };
    if (Date.now() - r.lastSeen >= RELAY_OFFLINE_MS) return { level: "bad", chip: "Relay offline", text: "Relay phone is offline", fix: true };
    if (r.listening === false) return { level: "warn", chip: "Check Relay", text: "Relay phone isn't reading Messages", fix: true };
    const battery = Number.isFinite(r.battery) && r.battery > 0 ? " " + Math.round(r.battery) + "%" : "";
    return { level: "ok", chip: "Relay" + battery, text: "Relay phone connected" };
  }

  function relayStrip() {
    const strip = $("relay-strip");
    const h = relayHealth(state.relay);
    strip.className = "strip " + h.level;
    strip.innerHTML = "";
    strip.appendChild(el("span", "dot"));
    strip.appendChild(el("span", "label", h.chip));
    strip.title = h.text;
    const banner = $("relay-banner");
    if (!banner) return;   // a page from before the banner (a cached copy, for a few minutes after an update)
    banner.hidden = !h.fix;
    banner.className = "banner " + h.level;
    banner.innerHTML = "";
    if (!h.fix) return;
    banner.appendChild(el("span", "dot"));
    banner.appendChild(el("span", "label", h.text));
    const b = el("button", "", "Reconnect");
    b.type = "button";
    b.addEventListener("click", reconnectRelay);
    banner.appendChild(b);
  }

  let reconnecting = false;

  /** Rings the relay to reopen its connection, re-bind to Messages and check in, then watches for that check-in. */
  async function reconnectRelay() {
    if (reconnecting) return;
    reconnecting = true;
    const btn = $("reconnect-btn");
    if (btn) btn.disabled = true;
    try {
      const before = (state.relay && state.relay.lastSeen) || 0;
      const r = await api("/api/relay/wake", { method: "POST", body: { kind: "reconnect" } });
      toast("Asked the relay phone to reconnect…", 3000);
      const until = Date.now() + 90000;
      while (Date.now() < until) {
        await sleep(5000);
        const s = await api("/api/status");
        state.relay = s.relay;
        relayStrip();
        if (state.view === "settings") renderRelaySettings();
        if (s.relay && s.relay.lastSeen > before && s.relay.lastSeen >= r.at - 2000) {
          toast(s.relay.listening === false ? "The relay phone answered, but it still isn't reading Messages. Open Relay on it." : "The relay phone is connected.", 4000);
          return;
        }
      }
      toast("No answer yet. If the relay phone is off, has no Wi-Fi or is out of power, nothing can reach it until it's back.", 7000);
    } catch (e) {
      if (e.auth) return unlinkedHere(e.message);
      toast(e.message, 4000);
    } finally {
      reconnecting = false;
      if (btn) btn.disabled = false;
    }
  }

  // ------------------------------------------------------------ inbox

  async function loadInbox() {
    const data = await api("/api/inbox");
    state.convs.clear();
    for (const c of data.items) state.convs.set(c.conv, c);
    if (!state.feed) state.feed = data.feed || null;
    if (typeof data.epoch === "number") state.epoch = data.epoch;
    state.relay = data.relay;
    state.loadedInbox = true;
    renderInbox();
    relayStrip();
  }

  function renderInbox() {
    const list = $("conv-list");
    list.innerHTML = "";
    let items = [...state.convs.values()].sort((a, b) => (b.lastTs || 0) - (a.lastTs || 0));
    const q = state.filter;
    if (q) items = items.filter(c => ((c.name || "") + " " + (c.lastText || "")).toLowerCase().includes(q));
    const empty = $("inbox-empty");
    empty.hidden = items.length > 0;
    empty.textContent = q ? "Nothing matches “" + q + "”" : "No texts yet";
    for (const c of items) {
      const li = paint(el("li", "conv" + (c.unread ? " unread" : "")), c.conv);
      li.dataset.conv = c.conv;
      li.appendChild(fillAvatar(el("span", "avatar"), c));
      const main = el("div", "conv-main");
      main.appendChild(el("span", "conv-name", c.name || "Unknown"));
      const snippet = (c.lastDir === "out" ? "You: " : (c.group && c.lastSender ? c.lastSender + ": " : "")) + TR2.words.snippet(c.lastText || "");
      main.appendChild(el("div", "conv-snippet", snippet));
      li.appendChild(main);
      const side = el("div", "conv-side");
      side.appendChild(el("span", "conv-time", fmtTime(c.lastTs)));
      if (c.unread) side.appendChild(el("span", "badge", c.unread > 99 ? "99+" : String(c.unread)));
      li.appendChild(side);
      li.addEventListener("click", () => openThread(c.conv));
      list.appendChild(li);
    }
  }

  function openSearch() {
    $("search-row").hidden = false;
    $("search-input").focus();
  }

  function closeSearch() {
    $("search-input").value = "";
    $("search-row").hidden = true;
    if (state.filter) { state.filter = ""; renderInbox(); }
  }

  function unreadElsewhere() {
    let n = 0;
    for (const c of state.convs.values()) if (c.conv !== state.current) n += c.unread || 0;
    return n;
  }

  function renderBackCount() {
    const b = $("back-count");
    const n = unreadElsewhere();
    b.hidden = n === 0;
    b.textContent = n > 99 ? "99+" : String(n);
  }

  // ------------------------------------------------------------ thread

  async function openThread(conv) {
    if (state.find.open) closeFind(true);
    state.current = conv;
    nativeCall("openedConversation", conv);
    const c = state.convs.get(conv);
    $("thread-name").textContent = c ? c.name : "Conversation";
    paint($("view-thread"), conv);
    paint($("view-details"), conv);
    fillAvatar($("thread-avatar"), c);
    renderBackCount();
    $("msg-list").innerHTML = "";
    $("load-more").hidden = true;
    show("thread");
    try {
      const data = await api("/api/thread?conv=" + encodeURIComponent(conv));
      state.msgs.set(conv, tidy(data.items));
      state.cursors.set(conv, data.cursor);
      if (!data.cursor) state.complete.add(conv); else state.complete.delete(conv);
      if (data.conversation) state.convs.set(conv, data.conversation);
      // Another conversation was opened (or the list) while this one loaded: never draw it under that one's name.
      if (state.current !== conv) return;
      renderThread(conv, true);
      const now = state.convs.get(conv);
      if (now && now.unread) {
        now.unread = 0;
        renderInbox();
        api("/api/read", { method: "POST", body: { conv } }).catch(() => {});
      }
    } catch (e) {
      if (e.auth) return unlinkedHere(e.message);
      if (state.current === conv) toast(e.message);
    }
    if (!native && state.current === conv) setTimeout(() => $("msg-input").focus(), 50);
  }

  function threadNotice(conv) {
    const c = state.convs.get(conv);
    const n = $("thread-notice");
    if (c && c.canReply === false) {
      n.hidden = false;
      n.className = "notice warn";
      n.textContent = "Replies open after their next text reaches the relay phone.";
    } else {
      n.hidden = true;
    }
  }

  /** The message's words as drawn: a quoted reply by its answer. */
  function shownText(m) {
    if (!m || m.unchecked) return m ? m.text : "";
    const q = TR2.words.quote(m.text || "");
    return q ? q.body : (m.text || "");
  }

  /** Whether a message is the kind of attachment (or link) a reaction or a quote names. */
  function kindMatches(m, kind) {
    if (!kind) return false;
    if (kind === "link") return !!m.text && urlsIn(m.text).length > 0;
    if (!m.media) return false;
    const mime = String(m.media.mime || "").toLowerCase();
    return kind === "image" ? mime.startsWith("image/") : kind === "video" ? mime.startsWith("video/") : kind === "audio" ? mime.startsWith("audio/") : true;
  }

  /** The newest message before `from` that `words` quote (or that is `kind` of attachment); -1 for none. */
  function targetOf(msgs, from, words, kind) {
    for (let j = from - 1; j >= 0 && j >= from - 400; j--) {
      const t = msgs[j];
      if (t.unchecked) continue;
      if (words != null ? (TR2.words.sameWords(words, shownText(t)) || TR2.words.sameWords(words, t.text || "")) : kindMatches(t, kind)) return j;
    }
    return -1;
  }

  function kindOfQuoteLine(quoted) {
    switch (String(quoted || "").trim().toLowerCase()) {
      case "picture": return "image";
      case "video": return "video";
      case "voice message": return "audio";
      case "attachment": return "file";
      default: return null;
    }
  }

  /** The reaction kind of a message with this attachment, for reacting to one with no words. */
  function kindOfMedia(media) {
    if (!media) return null;
    const t = String(media.mime || "").toLowerCase();
    return t.startsWith("image/") ? "image" : t.startsWith("video/") ? "video" : t.startsWith("audio/") ? "audio" : "file";
  }

  /**
   * The thread as drawn: reactions ride along as texts, so each is folded under the message it names (unless it's one
   * of this device's that didn't go, which the reader has to see), and a quoted reply knows what it answers.
   */
  function layout(conv, msgs) {
    const c = state.convs.get(conv);
    const convName = c ? c.name : "";
    const hidden = new Array(msgs.length).fill(false);
    const reactions = new Map();
    for (let i = 0; i < msgs.length; i++) {
      const m = msgs[i];
      if (m.unchecked || m.media) continue;
      const r = TR2.words.reaction(m.text || "");
      if (!r) continue;
      const j = targetOf(msgs, i, r.to, r.kind);
      if (j < 0) continue;
      if (!reactions.has(j)) reactions.set(j, []);
      reactions.get(j).push(m);
      hidden[i] = !(m.dir === "out" && m.status === "failed");
    }
    const rows = [];
    for (let i = 0; i < msgs.length; i++) {
      if (hidden[i]) continue;
      const m = msgs[i];
      const row = { m, index: i, quote: null, quoteKey: null, quoteWho: null, chips: null };
      if (!m.unchecked) {
        row.quote = TR2.words.quote(m.text || "");
        if (row.quote) {
          const kind = kindOfQuoteLine(row.quote.quoted);
          const j = targetOf(msgs, i, kind ? null : row.quote.quoted, kind);
          if (j >= 0) {
            row.quoteKey = msgs[j].k;
            row.quoteWho = msgs[j].dir === "out" ? "You" : (msgs[j].sender || convName);
          }
        }
      }
      const rs = reactions.get(i);
      if (rs) {
        const chips = new Map();
        for (const r of rs) {
          const x = TR2.words.reaction(r.text || "");
          if (!x) continue;
          const by = r.dir === "out" ? "You" : (r.sender || convName);
          let chip = chips.get(x.emoji);
          if (x.removed) {
            if (chip) {
              const at = chip.by.indexOf(by);
              if (at >= 0) chip.by.splice(at, 1);
              chip.mine = chip.by.includes("You");
              if (!chip.by.length) chips.delete(x.emoji);
            }
            continue;
          }
          if (!chip) chips.set(x.emoji, chip = { emoji: x.emoji, by: [], mine: false });
          if (chip.by.includes(by)) continue;
          chip.by.push(by);
          if (r.dir === "out") chip.mine = true;
        }
        if (chips.size) row.chips = [...chips.values()];
      }
      rows.push(row);
    }
    return rows;
  }

  function renderThread(conv, scrollToEnd) {
    const list = $("msg-list");
    const scroller = $("msg-scroll");
    const keepBottom = scrollToEnd || (scroller.scrollHeight - scroller.scrollTop - scroller.clientHeight < 80);
    list.innerHTML = "";
    threadNotice(conv);
    $("load-more").hidden = !state.cursors.get(conv);
    const c = state.convs.get(conv);
    const msgs = state.msgs.get(conv) || [];
    const query = state.find.open ? state.find.query : "";
    const rows = layout(conv, msgs);
    let lastOut = -1;
    for (const r of rows) if (r.m.dir === "out") lastOut = r.index;
    let lastDay = "";
    for (let n = 0; n < rows.length; n++) {
      const row = rows[n];
      const m = row.m;
      const prev = n > 0 ? rows[n - 1].m : null;
      const next = n + 1 < rows.length ? rows[n + 1].m : null;
      const day = fmtDay(m.ts);
      if (day !== lastDay) {
        const d = el("li", "day", day + ", ");
        d.appendChild(el("span", "", fmtClock(m.ts)));
        list.appendChild(d);
        lastDay = day;
      }
      const runStart = !sameRun(prev, m) || (prev && day !== fmtDay(prev.ts));
      const runEnd = !sameRun(m, next) || (next && fmtDay(next.ts) !== day);
      const li = el("li", "msg " + (m.dir === "out" ? "out " + m.status : "in") + (runStart ? " run-start" : "") + (runEnd ? " run-end" : ""));
      li.dataset.k = m.k;
      if (m.dir === "in" && c && c.group && m.sender && runStart) li.appendChild(el("div", "sender", m.sender));
      const b = el("div", "bubble" + (m.media && (m.media.preview || m.media.path || m.media.local) ? " has-media" : ""));
      const words = shownText(m);
      if (words) {
        const t = el("div", "text");
        if (row.quote) {
          const q = el("button", "quote");
          q.type = "button";
          if (row.quoteWho) q.appendChild(el("span", "quote-who", row.quoteWho));
          q.appendChild(el("span", "quote-text", row.quote.quoted));
          q.disabled = !row.quoteKey;
          if (row.quoteKey) q.addEventListener("click", e => { e.stopPropagation(); jumpTo(row.quoteKey); });
          t.appendChild(q);
        }
        const body = el("span", "words");
        fillText(body, words, query);
        t.appendChild(body);
        b.appendChild(t);
      }
      if (m.media) renderMedia(b, m.media);
      b.addEventListener("click", e => { if (!e.target.closest("img, button, a")) li.classList.toggle("show-time"); });
      b.addEventListener("contextmenu", e => { e.preventDefault(); openMenu(li, row); });
      pressAndHold(b, () => openMenu(li, row));
      li.appendChild(b);
      if (row.chips) {
        const chips = el("div", "chips");
        for (const chip of row.chips) {
          const t = el("button", "chip" + (chip.mine ? " mine" : ""), chip.emoji + (chip.by.length > 1 ? " " + chip.by.length : ""));
          t.type = "button";
          t.title = chip.emoji + " from " + chip.by.join(", ") + (chip.mine ? ". Tap to take yours back" : ". Tap to react too");
          t.setAttribute("aria-label", t.title);
          t.addEventListener("click", () => react(m, chip.emoji, chip.mine));
          chips.appendChild(t);
        }
        li.appendChild(chips);
      }
      li.appendChild(el("div", "stamp", fmtClock(m.ts) + (m.moved ? " · moved from your first inbox" : "")));
      if (m.dir === "out" && (m.status === "failed" || m.status === "queued" || row.index === lastOut)) {
        const status = m.status === "queued" ? "Sending…"
          : m.status === "failed" ? "Not sent. " + (notSent(m.code) || (!state.link && m.error) || "The relay phone couldn't send it.")
          : m.via === "sms" ? "Sent as SMS" : m.via === "mms" ? "Sent as MMS" : m.via === "phone" ? "Sent from the relay phone" : "Sent";
        li.appendChild(el("div", "stamp status " + (m.status || ""), status));
      }
      list.appendChild(li);
    }
    if (state.find.open) collectMarks(false);
    else if (keepBottom) scroller.scrollTop = scroller.scrollHeight;
  }

  /** A long press (touch or mouse) on a bubble opens its menu, as it does on a phone. */
  function pressAndHold(node, onHold) {
    let timer = null;
    const start = e => {
      if (e.type === "mousedown" && e.button !== 0) return;
      clear();
      timer = setTimeout(() => { timer = null; onHold(); }, 450);
    };
    const clear = () => { if (timer) { clearTimeout(timer); timer = null; } };
    node.addEventListener("touchstart", start, { passive: true });
    node.addEventListener("mousedown", start);
    for (const ev of ["touchend", "touchmove", "touchcancel", "mouseup", "mouseleave"]) node.addEventListener(ev, clear, { passive: true });
  }

  /** Scrolls to a message (one that was quoted) and shows its time, so it stands out. */
  function jumpTo(k) {
    const li = document.querySelector('#msg-list li[data-k="' + CSS.escape(k) + '"]');
    if (!li) return;
    li.classList.add("show-time");
    li.scrollIntoView({ block: "center", behavior: "smooth" });
  }

  // ------------------------------------------------------------ what a long press offers

  const QUICK = ["👍", "❤️", "😂", "😮", "😢", "🙏"];
  const MORE = ("😀 😃 😄 😁 😆 😅 🤣 😂 🙂 😉 😊 😇 🥰 😍 😘 😗 😙 😚 😋 😛 😜 🤪 😝 🤑 🤗 🤭 🤫 🤔 🤐 🤨 😐 😑 😶 😏 😒 🙄 😬 🤥 😌 😔 😪 🤤 😴 😷 🤒 🤕 🤢 🤮 🤧 🥵 🥶 🥴 😵 🤯 🤠 🥳 😎 🤓 🧐 😕 😟 🙁 😮 😯 😲 😳 🥺 😦 😧 😨 😰 😥 😢 😭 😱 😖 😣 😞 😓 😩 😫 🥱 😤 😡 😠 🤬 😈 👿 💀 💩 🤡 👻 👽 🤖 😺 😸 😹 😻 😼 😽 🙀 😿 😾 "
    + "👍 👎 👊 ✊ 🤛 🤜 👏 🙌 👐 🤲 🤝 🙏 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 👇 ☝️ ✋ 🤚 🖐️ 🖖 👋 🤏 💪 "
    + "❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 💟 ‼️ ❓ ❗ 💯 🔥 ⭐ 🌟 ✨ 🎉 🎊 🎈 🎁 🏆 🥇 "
    + "🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🐔 🐧 🐦 🦆 🦉 🐺 🐴 🦄 🐝 🐛 🦋 🐌 🐢 🐍 🐙 🦀 🐠 🐬 🐳 "
    + "🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🍒 🍑 🥭 🍍 🥥 🥝 🍅 🥑 🥦 🌽 🥕 🍞 🥐 🧀 🍗 🍔 🍟 🍕 🌮 🍣 🍜 🍩 🍪 🎂 🍰 ☕ 🍵 🍺 🍷 "
    + "⚽ 🏀 🏈 ⚾ 🎾 🏐 🏉 🎱 🏓 🏸 🥊 ⛳ 🏄 🏊 🚴 🎮 🎲 🎯 🎸 🎹 🥁 🎤 🎧 🚗 🚕 🚌 🚑 🚒 🚲 🛵 ✈️ 🚀 🚢 🏠 🏥 🏫 ⛰️ 🏖️ 🌋 🗽 "
    + "☀️ 🌙 ⭐ ☁️ ⛅ 🌧️ ⛈️ ❄️ ☔ 🌈 💧 🔔 🔑 🔒 💡 🔦 📱 💻 ⌚ 📷 🎥 📺 📻 🔋 📎 ✏️ 📌 📅 📦 ✉️ 💰 💳 🧲 🔧 🔨 🧪 💊 🩹").split(/\s+/).filter(Boolean);

  function closeMenu() {
    const old = $("msg-menu");
    if (old) old.remove();
  }

  /** The reaction bar, Reply and Copy, beside a message. */
  function openMenu(li, row) {
    closeMenu();
    const m = row.m;
    const menu = el("div", "msg-menu");
    menu.id = "msg-menu";
    if (!m.unchecked && !String(m.k).startsWith("tmp:")) {
      const bar = el("div", "react-bar");
      for (const e of QUICK) {
        const mine = !!(row.chips && row.chips.some(ch => ch.emoji === e && ch.mine));
        const b = el("button", "react" + (mine ? " mine" : ""), e);
        b.type = "button";
        b.setAttribute("aria-label", (mine ? "Remove " : "React ") + e);
        b.addEventListener("click", () => { closeMenu(); react(m, e, mine); });
        bar.appendChild(b);
      }
      const more = el("button", "react more", "+");
      more.type = "button";
      more.setAttribute("aria-label", "More emoji");
      more.addEventListener("click", () => { closeMenu(); state.reactTo = m; showEmojis(true); });
      bar.appendChild(more);
      menu.appendChild(bar);
      const reply = el("button", "menu-item", "Reply");
      reply.type = "button";
      reply.addEventListener("click", () => { closeMenu(); replyTo(m); });
      menu.appendChild(reply);
    }
    if (!m.unchecked && m.text) {
      const copy = el("button", "menu-item", "Copy text");
      copy.type = "button";
      copy.addEventListener("click", () => {
        closeMenu();
        navigator.clipboard.writeText(shownText(m)).then(() => toast("Copied.", 1500), () => toast("Couldn't copy.", 2000));
      });
      menu.appendChild(copy);
    }
    if (!menu.childElementCount) return;
    // Above the message, unless that would run off the top of the thread: then beneath it.
    const scroller = $("msg-scroll").getBoundingClientRect();
    if (li.getBoundingClientRect().top - scroller.top < 180) menu.classList.add("below");
    li.appendChild(menu);
    menu.querySelector("button").focus();
    const away = e => { if (!menu.contains(e.target)) { closeMenu(); document.removeEventListener("pointerdown", away, true); } };
    setTimeout(() => document.addEventListener("pointerdown", away, true), 0);
    menu.addEventListener("keydown", e => { if (e.key === "Escape") { closeMenu(); document.removeEventListener("pointerdown", away, true); } });
  }

  /** Reacts to a message, as a text in the form phones show as a reaction; `remove` takes one back. */
  function react(m, emoji, remove) {
    if (!m || m.unchecked || !emoji) return;
    const to = m.text ? m.text : null;
    sendText(m.conv || state.current, TR2.words.reactionText(emoji, to, kindOfMedia(m.media), !!remove), null);
  }

  /** Starts (or cancels) a reply to `m`: the composer shows what it answers, and the message goes out quoting it. */
  function replyTo(m) {
    state.replyTo = m || null;
    const bar = $("reply-bar");
    bar.hidden = !m;
    if (!m) return;
    const c = state.convs.get(state.current);
    $("reply-who").textContent = "Replying to " + (m.dir === "out" ? "you" : (m.sender || (c ? c.name : "them")));
    const what = shownText(m) || (m.media ? mediaKind(m.media) : "");
    $("reply-text").textContent = what.replace(/\n/g, " ");
    $("msg-input").focus();
  }

  // ------------------------------------------------------------ emoji

  function showEmojis(on) {
    const panel = $("emoji-panel");
    if (on === !panel.hidden) return;
    panel.hidden = !on;
    $("emoji-btn").classList.toggle("active", on);
    if (on) {
      if (!panel.childElementCount) {
        for (const e of MORE) {
          const b = el("button", "emoji", e);
          b.type = "button";
          b.addEventListener("click", () => pickedEmoji(e));
          panel.appendChild(b);
        }
      }
      panel.querySelector("button").focus();
    } else {
      state.reactTo = null;
    }
  }

  /** The emoji picked: a reaction to the message the panel was opened for, else into the reply where the cursor is. */
  function pickedEmoji(e) {
    if (state.reactTo) {
      const m = state.reactTo;
      showEmojis(false);
      react(m, e, false);
      return;
    }
    const input = $("msg-input");
    const at = input.selectionStart == null ? input.value.length : input.selectionStart;
    const end = input.selectionEnd == null ? at : input.selectionEnd;
    input.value = input.value.slice(0, at) + e + input.value.slice(end);
    input.selectionStart = input.selectionEnd = at + e.length;
    input.dispatchEvent(new Event("input"));   // the panel stays open for the next one; typing in the reply closes it
  }

  /**
   * Why a reply wasn't sent, in this page's own words: the relay phone reports a code, and nothing the server writes is
   * shown in a conversation. "local" is this device failing to hand it to the inbox at all.
   */
  const NOT_SENT = {
    stopped: "Relay stopped mid-send. Check the relay phone first.",
    old: "Too old to send.",
    future: "Its time was off.",
    again: "Already handled once.",
    unknown_conv: "The relay phone doesn't know this conversation.",
    unchecked: "The relay phone couldn't check it.",
    no_fallback: "No way to reply yet. Turn on SMS fallback in Paigr Relay.",
    group: "Group replies work after their next text.",
    no_number: "No number for SMS fallback.",
    sms_failed: "SMS fallback failed.",
    before_move: "From before the move.",
    local: "Couldn't reach the inbox.",
    too_big_here: "That file is too big to send (25 MB at most).",
    no_attach: "Attachments can't be sent to this conversation from here.",
    attach_perm: "Allow SMS fallback in Paigr Relay to send attachments.",
    too_big: "Too big to send as MMS from the relay phone.",
    mms_failed: "MMS failed on the relay phone.",
    attach_fetch: "The relay phone couldn't fetch the attachment."
  };

  const notSent = (code) => typeof code === "string" && Object.prototype.hasOwnProperty.call(NOT_SENT, code) ? NOT_SENT[code] : "";

  /** In the order they were sealed, and each sealed message once: the server can't show one twice. */
  const byTime = (a, b) => ((a.ts || 0) - (b.ts || 0)) || (a.k < b.k ? -1 : a.k > b.k ? 1 : 0);
  function tidy(arr) {
    const at = new Map();   // seal -> where its copy is in `out`
    const out = [];
    for (const m of arr || []) {
      if (!m) continue;
      if (m.seal && at.has(m.seal)) {
        const i = at.get(m.seal);
        if (out[i].unchecked && !m.unchecked) out[i] = m;   // the copy that opens as this conversation's is the one shown
        continue;
      }
      if (m.seal) at.set(m.seal, out.length);
      out.push(m);
    }
    return out.sort(byTime);
  }

  /** Messages from the same side within a few minutes are drawn as one run. */
  function sameRun(a, b) {
    return !!(a && b && a.dir === b.dir && (a.sender || "") === (b.sender || "") && Math.abs((a.ts || 0) - (b.ts || 0)) < 3 * 60000);
  }

  async function loadEarlier() {
    const conv = state.current;
    const cursor = state.cursors.get(conv);
    if (!conv || !cursor) return;
    const scroller = $("msg-scroll");
    const before = scroller.scrollHeight;
    const data = await api("/api/thread?conv=" + encodeURIComponent(conv) + "&cursor=" + encodeURIComponent(cursor));
    if (state.cursors.get(conv) !== cursor) return;   // reloaded meanwhile: this page no longer follows what's held
    const have = new Set((state.msgs.get(conv) || []).map(m => m.k));
    const older = data.items.filter(m => !have.has(m.k));
    state.msgs.set(conv, tidy(older.concat(state.msgs.get(conv) || [])));
    state.cursors.set(conv, data.cursor);
    if (!data.cursor) state.complete.add(conv);
    if (state.current !== conv) return;
    renderThread(conv, false);
    scroller.scrollTop = scroller.scrollHeight - before;
  }

  /** Pages in the rest of a conversation's history, so search and the media view cover all of it. */
  async function loadWholeThread(conv) {
    for (let page = 0; page < 60 && !state.complete.has(conv); page++) {
      const cursor = state.cursors.get(conv);
      if (!cursor) { state.complete.add(conv); break; }
      const data = await api("/api/thread?conv=" + encodeURIComponent(conv) + "&cursor=" + encodeURIComponent(cursor));
      if (state.cursors.get(conv) !== cursor) break;   // reloaded meanwhile
      const have = new Set((state.msgs.get(conv) || []).map(m => m.k));
      const older = data.items.filter(m => !have.has(m.k));
      state.msgs.set(conv, tidy(older.concat(state.msgs.get(conv) || [])));
      state.cursors.set(conv, data.cursor);
      if (!data.cursor) state.complete.add(conv);
    }
    return state.msgs.get(conv) || [];
  }

  // ------------------------------------------------------------ search inside a conversation

  async function openFind() {
    if (!state.current) return;
    state.find = { open: true, query: "", marks: [], index: -1 };
    $("thread-bar").hidden = true;
    $("find-bar").hidden = false;
    $("find-input").value = "";
    $("find-count").textContent = "";
    $("find-input").focus();
    const conv = state.current;
    if (!state.complete.has(conv)) {
      $("find-count").textContent = "Loading…";
      try {
        await loadWholeThread(conv);
        if (state.current === conv) renderThread(conv, false);
      } catch (e) { toast(e.message); }
      if (state.find.open && !state.find.query) $("find-count").textContent = "";
    }
  }

  function closeFind(quiet) {
    state.find = { open: false, query: "", marks: [], index: -1 };
    $("find-bar").hidden = true;
    $("thread-bar").hidden = false;
    if (!quiet && state.current) renderThread(state.current, true);
  }

  let findTimer = null;

  function runFind() {
    clearTimeout(findTimer);
    findTimer = setTimeout(() => {
      state.find.query = $("find-input").value.trim().toLowerCase();
      if (state.current) renderThread(state.current, false);
      collectMarks(true);
    }, 150);
  }

  /** Gathers the highlighted matches and puts the newest one in view, like stepping up from the bottom. */
  function collectMarks(reset) {
    const marks = [...$("msg-list").querySelectorAll("mark")];
    state.find.marks = marks;
    if (!state.find.query) { $("find-count").textContent = ""; return; }
    if (!marks.length) { state.find.index = -1; $("find-count").textContent = "No results"; return; }
    if (reset || state.find.index < 0 || state.find.index >= marks.length) state.find.index = marks.length - 1;
    focusMark();
  }

  function focusMark() {
    const { marks, index } = state.find;
    marks.forEach((m, i) => m.classList.toggle("current", i === index));
    const m = marks[index];
    if (m) m.scrollIntoView({ block: "center" });
    $("find-count").textContent = (index + 1) + " of " + marks.length;
  }

  function stepFind(delta) {
    const n = state.find.marks.length;
    if (!n) return;
    state.find.index = (state.find.index + delta + n) % n;
    focusMark();
  }

  // ------------------------------------------------------------ pictures and other attachments

  // Opened files stay in memory while there's room: up to MEDIA_KEEP_BYTES, the least recently shown going first (its
  // object URL let go). What the lightbox shows or plays is kept while it's open. Pictures open as they scroll into view.
  const MEDIA_KEEP_BYTES = 48 * 1024 * 1024;
  const mediaUrls = new Map();     // path -> { url, exp, bytes, own }, oldest first
  const mediaLoading = new Map();  // path -> the promise of its URL, so one file is fetched and opened once at a time
  const mediaPinned = new Set();   // URLs the lightbox is using
  let mediaBytes = 0;

  function mediaKind(media) {
    const t = String(media.mime || "").toLowerCase();
    if (t.startsWith("image/")) return "Picture";
    if (t.startsWith("video/")) return "Video";
    if (t.startsWith("audio/")) return "Voice message";
    return "Attachment";
  }

  /**
   * A URL the page can show a file from. A private inbox's files are sealed: fetched from storage, opened here, and
   * shown from memory. `mime` is what the file is (the server doesn't know).
   */
  function mediaUrl(path, mime) {
    const hit = mediaUrls.get(path);
    if (hit && hit.exp > Date.now()) {
      mediaUrls.delete(path);   // most recently used goes last
      mediaUrls.set(path, hit);
      return Promise.resolve(hit.url);
    }
    if (hit) forgetMedia(path);
    let loading = mediaLoading.get(path);
    if (!loading) {
      loading = loadMedia(path, mime).finally(() => mediaLoading.delete(path));
      mediaLoading.set(path, loading);
    }
    return loading;
  }

  /** Where the server says a file is: only an https address (or this page's own server) is ever fetched or opened. */
  function fileUrl(u) {
    try {
      const url = new URL(String(u || ""), location.origin);
      if (url.protocol === "https:" || url.origin === location.origin) return url.href;
    } catch { /* not an address */ }
    throw new Error("That file's address isn't one this page opens.");
  }

  async function loadMedia(path, mime) {
    const r = await api("/api/media?p=" + encodeURIComponent(path));
    const at = fileUrl(r.url);
    if (!state.link || !String(path).startsWith("/media/v2/")) {
      keepMedia(path, { url: at, exp: Date.now() + Math.max(60, (r.expiresIn || 3600) - 120) * 1000, bytes: 0, own: false });
      return at;
    }
    const file = TR2.fileOf(path);
    if (!file) throw new Error("That file isn't one of this inbox's.");
    const res = await fetch(at, { cache: "no-store" });
    if (!res.ok) throw new Error("Could not load it (" + res.status + ").");
    // Sealed for this conversation and this one file: anything else stored in its place doesn't open.
    const plain = await TR2.openBytes(state.keys.enc, new Uint8Array(await res.arrayBuffer()), file.aad);
    const url = URL.createObjectURL(new Blob([plain], { type: mime || "application/octet-stream" }));
    keepMedia(path, { url, exp: Infinity, bytes: plain.byteLength, own: true });
    return url;
  }

  function keepMedia(path, entry) {
    if (mediaUrls.has(path)) forgetMedia(path);
    mediaUrls.set(path, entry);
    mediaBytes += entry.bytes;
    for (const [p, e] of mediaUrls) {
      if (mediaBytes <= MEDIA_KEEP_BYTES) break;
      if (p !== path && !mediaPinned.has(e.url)) forgetMedia(p);
    }
  }

  function forgetMedia(path) {
    const e = mediaUrls.get(path);
    if (!e) return;
    mediaUrls.delete(path);
    mediaBytes -= e.bytes;
    if (e.own) URL.revokeObjectURL(e.url);   // a picture already on screen stays there
  }

  // Pictures are fetched and opened as they come into view, not all at once.
  const mediaWatched = new Set();
  const mediaObserver = "IntersectionObserver" in window ? new IntersectionObserver(entries => {
    for (const en of entries) {
      if (!en.isIntersecting) continue;
      mediaObserver.unobserve(en.target);
      mediaWatched.delete(en.target);
      const load = en.target.textsLoad;
      en.target.textsLoad = null;
      if (load) load();
    }
  }, { rootMargin: "600px 0px" }) : null;

  function whenSeen(node, load) {
    if (!mediaObserver) { load(); return; }
    for (const n of mediaWatched) if (!n.isConnected) { mediaObserver.unobserve(n); mediaWatched.delete(n); }
    node.textsLoad = load;
    mediaWatched.add(node);
    mediaObserver.observe(node);
  }

  /** The file behind a preview: a picture's own size, or the original of anything else. */
  function previewMime(media) {
    return media.preview ? "image/jpeg" : media.mime;
  }

  async function openOriginal(path, media) {
    media = media || {};
    const kind = mediaKind(media);
    try {
      const u = await mediaUrl(path, media.mime);
      if (!state.link) { window.open(u, "_blank", "noopener"); return; }
      if (kind === "Video" || kind === "Voice message") openPlayer(u, kind, media);
      else await saveFile(u, media);
    } catch (e) { toast(e.message); }
  }

  /** Videos and voice messages play right here. */
  function openPlayer(url, kind, media) {
    const box = $("lightbox");
    const video = $("lightbox-video"), audio = $("lightbox-audio"), img = $("lightbox-img");
    img.hidden = true;
    img.removeAttribute("src");
    const player = kind === "Video" ? video : audio;
    (kind === "Video" ? audio : video).hidden = true;
    player.hidden = false;
    mediaPinned.add(url);   // playing and seeking read from it until the lightbox closes
    player.src = url;
    $("lightbox-cap").textContent = kind + (media.bytes ? " · " + fmtBytes(media.bytes) : "");
    wireSave(url, media);
    box.hidden = false;
    player.play().catch(() => {});
  }

  function fileName(media) {
    const t = String(media.mime || "").toLowerCase();
    const ext = { "image/jpeg": "jpg", "image/png": "png", "image/gif": "gif", "image/webp": "webp", "video/mp4": "mp4", "video/webm": "webm",
      "audio/mpeg": "mp3", "audio/mp4": "m4a", "audio/webm": "weba", "application/pdf": "pdf" }[t] || "bin";
    const d = new Date();
    const pad = (n) => String(n).padStart(2, "0");
    return "Paigr-" + d.getFullYear() + pad(d.getMonth() + 1) + pad(d.getDate()) + "-" + pad(d.getHours()) + pad(d.getMinutes()) + pad(d.getSeconds()) + "." + ext;
  }

  /** Saves an opened file: to Downloads through the Texts app, or as a download in a browser. */
  async function saveFile(url, media) {
    const name = fileName(media);
    if (native && typeof native.saveFile === "function") {
      const blob = await (await fetch(url)).blob();
      const b64 = await new Promise((resolve, reject) => {
        const r = new FileReader();
        r.onload = () => resolve(String(r.result).split(",")[1] || "");
        r.onerror = () => reject(new Error("Could not read the file."));
        r.readAsDataURL(blob);
      });
      const said = nativeCall("saveFile", name, media.mime || "application/octet-stream", b64);
      toast(said || "Saved to Downloads.", 3500);
      return;
    }
    const a = document.createElement("a");
    a.href = url;
    a.download = name;
    document.body.appendChild(a);
    a.click();
    a.remove();
  }

  function wireSave(url, media) {
    const link = $("lightbox-open");
    if (!state.link) {
      link.textContent = "Open original";
      link.href = url;
      link.onclick = null;
      return;
    }
    link.textContent = "Save";
    link.href = "#";
    link.onclick = (e) => { e.preventDefault(); e.stopPropagation(); saveFile(url, media).catch(err => toast(err.message)); };
  }

  function renderMedia(bubble, media) {
    const kind = mediaKind(media);
    const original = media.path || null;
    const preview = media.preview || (kind === "Picture" ? media.path : null);
    if (media.local && !original && !preview) {   // a file from this device, on its way out
      if (kind === "Picture") {
        const img = el("img", "pic");
        img.alt = "Picture";
        img.src = media.local;
        bubble.appendChild(img);
      } else {
        const tile = el("div", "attach-tile");
        tile.appendChild(icon(kind === "Video" ? "i-play" : "i-file"));
        tile.appendChild(el("span", "", kind + (media.bytes ? " · " + fmtBytes(media.bytes) : "")));
        bubble.appendChild(tile);
      }
      return;
    }
    if (!original && !preview) {
      bubble.appendChild(el("div", "attach", kind + (media.blocked ? " · couldn't be checked, so it isn't shown" : " · open it on the relay phone")));
      return;
    }
    if (kind === "Picture" && preview) {
      const img = el("img", "pic");
      img.alt = "Picture";
      if (media.w && media.h) img.style.aspectRatio = media.w + " / " + media.h;
      whenSeen(img, () => mediaUrl(preview, preview === media.preview ? "image/jpeg" : media.mime)
        .then(u => { img.src = u; }).catch(() => { img.replaceWith(el("div", "attach", "Picture · could not load")); }));
      img.addEventListener("click", () => openLightbox(original || preview, media));
      bubble.appendChild(img);
      return;
    }
    const tile = el("button", "attach-tile");
    tile.type = "button";
    tile.appendChild(icon(kind === "Video" ? "i-play" : "i-file"));
    tile.appendChild(el("span", "", kind + (media.bytes ? " · " + fmtBytes(media.bytes) : "") + " · tap to open"));
    tile.addEventListener("click", () => openOriginal(original, media));
    bubble.appendChild(tile);
  }

  async function openLightbox(path, media) {
    const box = $("lightbox");
    const img = $("lightbox-img");
    const cap = $("lightbox-cap");
    for (const id of ["lightbox-video", "lightbox-audio"]) { const p = $(id); p.pause(); p.removeAttribute("src"); p.hidden = true; }
    img.hidden = false;
    img.removeAttribute("src");
    cap.textContent = "Loading full size…";
    box.hidden = false;
    try {
      const u = await mediaUrl(path, path === media.preview ? "image/jpeg" : media.mime);
      if (box.hidden) return;   // closed while it was loading
      mediaPinned.add(u);       // Save reads it again
      img.src = u;
      cap.textContent = (media.w && media.h ? media.w + "×" + media.h : "Full size") + (media.bytes ? " · " + fmtBytes(media.bytes) : "");
      wireSave(u, media);
    } catch (e) {
      cap.textContent = "Could not load the picture: " + e.message;
    }
  }

  function closeLightbox() {
    $("lightbox").hidden = true;
    $("lightbox-img").removeAttribute("src");
    for (const id of ["lightbox-video", "lightbox-audio"]) { const p = $(id); p.pause(); p.removeAttribute("src"); p.hidden = true; }
    mediaPinned.clear();
  }

  function fmtBytes(n) {
    if (n < 1024) return n + " B";
    if (n < 1024 * 1024) return Math.round(n / 1024) + " KB";
    return (n / 1048576).toFixed(1) + " MB";
  }

  // ------------------------------------------------------------ details: photos, files and links in a conversation

  async function openDetails() {
    const conv = state.current;
    if (!conv) return;
    const c = state.convs.get(conv);
    fillAvatar($("details-avatar"), c);
    $("details-name").textContent = c ? c.name : "Conversation";
    $("details-sub").textContent = c && c.group ? "Group conversation" : "";
    show("details");
    selectTab(state.detailsTab);
    $("details-status").textContent = state.complete.has(conv) ? "" : "Loading the whole conversation…";
    renderDetails(conv);
    try {
      await loadWholeThread(conv);
    } catch (e) { toast(e.message); }
    if (state.current === conv && state.view === "details") {
      $("details-status").textContent = "";
      renderDetails(conv);
    }
  }

  function renderDetails(conv) {
    const msgs = (state.msgs.get(conv) || []).slice().reverse();   // newest first
    const photos = $("details-photos"), files = $("details-files"), links = $("details-links");
    photos.innerHTML = ""; files.innerHTML = ""; links.innerHTML = "";
    let nPhotos = 0, nFiles = 0, nLinks = 0;
    for (const m of msgs) {
      const media = m.media;
      if (media && (media.path || media.preview)) {
        const kind = mediaKind(media);
        if (kind === "Picture" || kind === "Video") {
          nPhotos++;
          const cell = el("div", "media-cell");
          if (kind === "Picture") {
            const img = el("img");
            img.alt = "Picture";
            whenSeen(img, () => mediaUrl(media.preview || media.path, previewMime(media)).then(u => { img.src = u; }).catch(() => {}));
            cell.appendChild(img);
            cell.addEventListener("click", () => openLightbox(media.path || media.preview, media));
          } else {
            const p = el("div", "play");
            p.appendChild(icon("i-play"));
            cell.appendChild(p);
            cell.addEventListener("click", () => openOriginal(media.path, media));
          }
          cell.appendChild(el("span", "kind", fmtDay(m.ts)));
          photos.appendChild(cell);
        } else {
          nFiles++;
          files.appendChild(itemRow("i-file", kind + (media.bytes ? " · " + fmtBytes(media.bytes) : ""), fmtDay(m.ts) + " · " + fmtClock(m.ts) + (m.dir === "out" ? " · You" : m.sender ? " · " + m.sender : ""), () => openOriginal(media.path, media)));
        }
      }
      for (const u of urlsIn(m.text)) {
        nLinks++;
        links.appendChild(itemRow("i-link", u, fmtDay(m.ts) + (m.dir === "out" ? " · You" : m.sender ? " · " + m.sender : ""), () => window.open(hrefOf(u), "_blank", "noopener")));
      }
    }
    if (!nPhotos) {
      const p = el("p", "empty", "No photos or videos yet.");
      p.style.gridColumn = "1 / -1";
      photos.appendChild(p);
    }
    if (!nFiles) files.appendChild(el("li", "empty", "No files yet."));
    if (!nLinks) links.appendChild(el("li", "empty", "No links yet."));
    const tabs = document.querySelectorAll(".tab");
    tabs[0].textContent = "Photos" + (nPhotos ? " (" + nPhotos + ")" : "");
    tabs[1].textContent = "Files" + (nFiles ? " (" + nFiles + ")" : "");
    tabs[2].textContent = "Links" + (nLinks ? " (" + nLinks + ")" : "");
  }

  function itemRow(iconId, line1, line2, onClick) {
    const li = el("li");
    const lead = el("span", "lead-icon");
    lead.appendChild(icon(iconId));
    li.appendChild(lead);
    const lines = el("div", "lines");
    lines.appendChild(el("div", "l1", line1));
    lines.appendChild(el("div", "l2", line2));
    li.appendChild(lines);
    li.addEventListener("click", onClick);
    return li;
  }

  function selectTab(name) {
    state.detailsTab = name;
    for (const t of document.querySelectorAll(".tab")) t.classList.toggle("active", t.dataset.tab === name);
    $("details-photos").hidden = name !== "photos";
    $("details-files").hidden = name !== "files";
    $("details-links").hidden = name !== "links";
  }

  // ------------------------------------------------------------ sending

  /** The most a file sent from here can be: what the inbox takes by a ticket (inbox2.js TICKET_MAX, less a margin). */
  const ATTACH_MAX = 25 * 1024 * 1024 - 4096;
  const PREVIEW_FROM_BYTES = 300 * 1024, PREVIEW_FROM_SIDE = 1280, PREVIEW_SIDE = 960;

  async function sendMessage() {
    const input = $("msg-input");
    const typed = input.value.trim();
    const conv = state.current;
    const att = state.attachment;
    if ((!typed && !att) || !conv) return;
    let text = typed;
    if (state.replyTo && typed) text = TR2.words.quoteText(state.replyTo.text || "", kindOfMedia(state.replyTo.media), typed);
    input.value = "";
    autosize(input);
    $("composer").classList.add("empty");
    replyTo(null);
    attach(null, true);
    await sendText(conv, text, att);
  }

  /** Sends words, a file, or both, to `conv`; drawn at once as sending, then as what became of it. */
  async function sendText(conv, text, att) {
    const now = Date.now();
    const temp = { k: "tmp:" + now + ":" + Math.random().toString(36).slice(2, 8), conv, dir: "out", status: "queued", text, ts: now };
    if (att) temp.media = { mime: att.mime, bytes: att.bytes, local: att.url, name: att.name };
    (state.msgs.get(conv) || state.msgs.set(conv, []).get(conv)).push(temp);
    renderThread(conv, true);
    try {
      const body = { conv, text, ts: now };
      if (att) {
        if (!state.link) throw Object.assign(new Error("Attachments need a private inbox."), { code: "local" });
        body.attachment = await storeAttachment(conv, now, att);
        temp.media.w = body.attachment.w;
        temp.media.h = body.attachment.h;
        temp.media.path = body.attachment.path;
        if (body.attachment.preview) temp.media.preview = body.attachment.preview;
      }
      const r = await api("/api/send", { method: "POST", body });
      const arr = state.msgs.get(conv) || [];
      const at = arr.indexOf(temp);
      if (arr.some(x => x.k === r.id)) { if (at >= 0) arr.splice(at, 1); }   // a sync already brought it
      else temp.k = r.id;
      state.pendingOut.add(r.id);
      const c = state.convs.get(conv);
      if (c) { c.lastTs = temp.ts; c.lastText = text || (att ? mediaKind(att) : ""); c.lastDir = "out"; renderInbox(); }
      if (r.canReply === false && state.current === conv) toast("Queued. It goes out after their next text reaches the relay phone.", 4000);
      if (state.current === conv) renderThread(conv, true);
    } catch (e) {
      temp.status = "failed";
      temp.code = e && e.code === "too_big" ? "too_big_here" : "local";
      if (state.current === conv) renderThread(conv, true);
      if (e.auth) unlinkedHere(e.message);
    }
  }

  /** Seals the file (and a small preview of a big picture) and stores it in the inbox; returns what the message names. */
  async function storeAttachment(conv, ts, att) {
    const plain = new Uint8Array(await att.file.arrayBuffer());
    if (plain.length > ATTACH_MAX) throw Object.assign(new Error("too big"), { code: "too_big" });
    // Its files are named by a fingerprint this device makes up, as the relay phone names its own.
    const fp = await TR2.fpId(state.keys, TR2.b64u(TR2.random(8)).replace(/[^A-Za-z0-9]/g, "").toLowerCase());
    const size = att.mime.startsWith("image/") ? await imageSize(att.file) : { w: 0, h: 0 };
    const out = { fp, mime: att.mime, bytes: plain.length, name: att.name, w: size.w, h: size.h };
    out.path = await storeFile(conv, fp, ts, "original", plain);
    if (att.mime.startsWith("image/") && att.mime !== "image/gif" && (plain.length > PREVIEW_FROM_BYTES || size.w > PREVIEW_FROM_SIDE || size.h > PREVIEW_FROM_SIDE)) {
      const small = await shrink(att.file, PREVIEW_SIDE, 0.8);
      if (small && small.length < plain.length) out.preview = await storeFile(conv, fp, ts, "preview", small);
    }
    return out;
  }

  /** One sealed file into the inbox: straight in when small, by an upload ticket when not. */
  async function storeFile(conv, fp, ts, kind, plain) {
    const sealed = await TR2.sealBytes(state.keys.enc, plain, TR2.AAD.file(conv, ts, fp, kind));
    if (sealed.length <= 4 * 1024 * 1024) {
      const up = await fetch("/v2/media/put", {
        method: "POST", body: sealed, cache: "no-store",
        headers: { "x-relay-token": state.link.token, "x-relay-conv": conv, "x-relay-fp": fp, "x-relay-ts": String(ts), "x-relay-kind": kind, "content-type": "application/octet-stream" }
      });
      const d = await up.json().catch(() => null);
      if (!up.ok || !d || !d.path) throw new Error("The inbox didn't store the file (" + up.status + ").");
      return d.path;
    }
    const t = await api("/api/media/ticket", { method: "POST", body: { conv, fp, ts, bytes: sealed.length, kind } });
    const headers = {};
    for (const [k, v] of Object.entries(t.headers || {})) if (k.toLowerCase() !== "content-length") headers[k] = v;
    const put = await fetch(fileUrl(t.uploadUrl), { method: "PUT", headers, body: sealed });
    if (!put.ok) throw new Error("The upload failed (" + put.status + ").");
    const done = await api("/api/media/complete", { method: "POST", body: { ticket: t.ticket, path: t.path } });
    return done.path || t.path;
  }

  function imageSize(file) {
    return new Promise(resolve => {
      const url = URL.createObjectURL(file);
      const img = new Image();
      img.onload = () => { URL.revokeObjectURL(url); resolve({ w: img.naturalWidth, h: img.naturalHeight }); };
      img.onerror = () => { URL.revokeObjectURL(url); resolve({ w: 0, h: 0 }); };
      img.src = url;
    });
  }

  /** A picture scaled to fit `maxSide`, as a JPEG; null when the browser can't draw it. */
  async function shrink(file, maxSide, quality) {
    try {
      const bitmap = await createImageBitmap(file);
      const k = Math.min(1, maxSide / Math.max(bitmap.width, bitmap.height));
      const canvas = document.createElement("canvas");
      canvas.width = Math.max(1, Math.round(bitmap.width * k));
      canvas.height = Math.max(1, Math.round(bitmap.height * k));
      canvas.getContext("2d").drawImage(bitmap, 0, 0, canvas.width, canvas.height);
      bitmap.close();
      const blob = await new Promise(resolve => canvas.toBlob(resolve, "image/jpeg", quality));
      return blob ? new Uint8Array(await blob.arrayBuffer()) : null;
    } catch {
      return null;
    }
  }

  const EXT_MIME = { jpg: "image/jpeg", jpeg: "image/jpeg", png: "image/png", gif: "image/gif", webp: "image/webp", heic: "image/heic", mp4: "video/mp4", "3gp": "video/3gpp", mov: "video/quicktime",
    mp3: "audio/mpeg", m4a: "audio/mp4", ogg: "audio/ogg", opus: "audio/ogg", wav: "audio/wav", pdf: "application/pdf", txt: "text/plain", vcf: "text/vcard" };

  /** What a picked file is: what the browser says, else what its name says, else bytes. */
  function mimeOf(file) {
    const t = String(file.type || "").toLowerCase().split(";")[0].trim();
    if (t) return t === "image/jpg" ? "image/jpeg" : t;
    const ext = String(file.name || "").toLowerCase().split(".").pop();
    return EXT_MIME[ext] || "application/octet-stream";
  }

  /** A file from this device to go with the next message (null takes it off again). */
  function attach(file, quiet) {
    if (state.attachment && state.attachment.url) URL.revokeObjectURL(state.attachment.url);
    state.attachment = null;
    const bar = $("attach-bar");
    if (!file) {
      bar.hidden = true;
      $("composer").classList.toggle("empty", !$("msg-input").value.trim());
      return;
    }
    if (file.size > ATTACH_MAX) {
      toast("That file is too big to send (25 MB at most).", 4000);
      return;
    }
    const mime = mimeOf(file);
    const url = URL.createObjectURL(file);
    state.attachment = { file, mime, name: file.name || "file", bytes: file.size, url };
    bar.hidden = false;
    const pic = $("attach-pic");
    pic.hidden = !mime.startsWith("image/");
    if (!pic.hidden) pic.src = url;
    $("attach-icon").hidden = !pic.hidden;
    $("attach-name").textContent = state.attachment.name;
    $("attach-size").textContent = mediaKind({ mime }) + " · " + fmtBytes(file.size);
    $("composer").classList.remove("empty");
    if (!quiet) $("msg-input").focus();
  }

  function autosize(ta) {
    ta.style.height = "auto";
    ta.style.height = Math.min(140, ta.scrollHeight) + "px";
  }

  // ------------------------------------------------------------ sync

  /** Adds or updates one message. Returns "added", "updated" or null. Never counts unread: the server's summaries do. */
  function mergeMessage(m) {
    let changed = null;
    const arr = state.msgs.get(m.conv);
    if (arr) {
      const i = arr.findIndex(x => x.k === m.k);
      if (i < 0) {
        // The same sealed message again, under another key: shown once (the copy that opens, if one of them doesn't).
        const twin = m.seal ? arr.findIndex(x => x.seal === m.seal) : -1;
        if (twin >= 0) {
          if (!(arr[twin].unchecked && !m.unchecked)) return null;
          arr.splice(twin, 1);
        }
        arr.push(m);
        arr.sort(byTime);
        changed = "added";
      } else if (!(arr[i].status !== "queued" && m.status === "queued") && JSON.stringify(arr[i]) !== JSON.stringify(m)) {
        arr[i] = m;   // a sent or failed status never goes back to queued
        // What the server put under this key may be another message's copy: still each message once, in time order.
        const tidied = tidy(arr);
        arr.length = 0;
        for (const x of tidied) arr.push(x);
        changed = "updated";
      }
    }
    if (!state.convs.has(m.conv)) {
      state.convs.set(m.conv, { conv: m.conv, name: m.name || m.sender || "Unknown", group: !!m.group, unread: 0, canReply: true,
        lastTs: m.ts, lastText: m.text || (m.media ? mediaKind(m.media) : ""), lastDir: m.dir, lastSender: m.sender });
      changed = changed || "added";
    }
    if (m.dir === "out" && m.status !== "queued") state.pendingOut.delete(m.k);
    if (changed === "added" && m.dir === "in" && !m.read && state.current === m.conv && state.view === "thread") {
      api("/api/read", { method: "POST", body: { conv: m.conv } }).catch(() => {});
    }
    return changed;
  }

  const SUMMARY_FIELDS = ["name", "group", "lastTs", "lastText", "lastDir", "lastSender", "unread", "canReply", "aliasKey"];

  /** The server's summary of a conversation wins, unread count included. The open conversation reads as read. */
  function mergeConversation(c) {
    if (!c || !c.conv) return false;
    const next = Object.assign({}, c);
    if (state.current === c.conv && state.view === "thread") next.unread = 0;
    const cur = state.convs.get(c.conv);
    if (cur && SUMMARY_FIELDS.every(f => cur[f] === next[f])) return false;
    state.convs.set(c.conv, next);
    return true;
  }

  async function sync() {
    if (state.syncing || !linked() || document.visibilityState !== "visible") return;
    state.syncing = true;
    try {
      if (!state.loadedInbox) await loadInbox();
      if (!state.feed) return;
      // Arrival order, not message time: a text that reaches the server late (the relay device was offline,
      // or sent everything again) still shows up here.
      let touched = false;
      let reopen = null;
      for (let round = 0; round < 10; round++) {
        const data = await api("/api/sync?feed=" + encodeURIComponent(state.feed));
        if (data.reset) {
          // Away longer than the inbox keeps its log: start over rather than miss anything.
          state.relay = data.relay;
          await reloadEverything();
          touched = true;
          break;
        }
        if (typeof data.epoch === "number" && state.epoch !== null && data.epoch !== state.epoch) {
          // Conversations were reorganized on the inbox (the relay phone found two to be one): start over.
          state.relay = data.relay;
          await reloadEverything();
          touched = true;
          break;
        }
        for (const m of data.items || []) if (mergeMessage(m)) touched = true;
        for (const c of data.convs || []) if (mergeConversation(c)) touched = true;
        // Filled in by a full-history sync: what's held here for those conversations is out of date.
        for (const conv of data.reload || []) {
          if (conv === state.current && (state.view === "thread" || state.view === "details")) reopen = conv;
          else { state.msgs.delete(conv); state.cursors.delete(conv); state.complete.delete(conv); }
        }
        if (data.cursor) state.feed = data.cursor;
        state.relay = data.relay;
        if (!data.more) break;
      }
      relayStrip();
      if (state.view === "settings") renderRelaySettings();
      if (touched) {
        renderInbox();
        if (state.current && state.view === "thread" && !state.find.open) renderThread(state.current, false);
      }
      if (reopen) await reloadThread(reopen);
      if (state.pendingOut.size && state.current) await refreshPending();
    } catch (e) {
      if (e.auth) unlinkedHere(e.message);
    } finally {
      state.syncing = false;
    }
  }

  /** Reloads the newest page of a conversation in place, keeping the reader where they are. */
  async function reloadThread(conv) {
    const data = await api("/api/thread?conv=" + encodeURIComponent(conv));
    if (state.current !== conv) return;
    state.msgs.set(conv, tidy(data.items));
    state.cursors.set(conv, data.cursor);
    if (!data.cursor) state.complete.add(conv); else state.complete.delete(conv);
    if (data.conversation) mergeConversation(data.conversation);
    if (state.view === "thread" && !state.find.open) renderThread(conv, false);
  }

  async function refreshPending() {
    const conv = state.current;
    const data = await api("/api/thread?conv=" + encodeURIComponent(conv));
    const have = state.msgs.get(conv) || [];
    for (const m of data.items) {
      const i = have.findIndex(x => x.k === m.k);
      if (i >= 0) have[i] = m;
      if (m.dir === "out" && m.status !== "queued") state.pendingOut.delete(m.k);
    }
    if (state.msgs.has(conv)) state.msgs.set(conv, tidy(have));
    if (data.conversation) state.convs.set(conv, data.conversation);
    if (state.current === conv && state.view === "thread" && !state.find.open) renderThread(conv, false);
  }

  function startSync() {
    stopSync();
    sync();
    state.syncTimer = setInterval(() => { if (!doorbell.connected || Date.now() - doorbell.lastSyncAt > SYNC_CONNECTED_MS) sync(); }, SYNC_MS);
    doorbell.open();
  }

  function stopSync() {
    if (state.syncTimer) clearInterval(state.syncTimer);
    state.syncTimer = null;
    doorbell.close();
  }

  /** Throws away everything held here and reloads every conversation; the open one reloads too. */
  async function reloadEverything() {
    state.convs.clear(); state.msgs.clear(); state.cursors.clear(); state.complete.clear();
    state.feed = null; state.epoch = null; state.loadedInbox = false;
    await loadInbox();
    if (state.current && (state.view === "thread" || state.view === "details")) {
      if (state.convs.has(state.current)) await openThread(state.current);
      else backToInbox();   // it was folded into another conversation
    }
  }

  /** Reloads everything here and has the relay phone send again anything from the last week it still has. */
  async function resyncAll() {
    const btn = $("resync-btn");
    btn.disabled = true;
    toast("Resyncing…", 2000);
    try {
      await reloadEverything();
      let relayAsked = true;
      try { await api("/api/relay/wake", { method: "POST", body: { kind: "resync" } }); } catch { relayAsked = false; }
      toast("All " + state.convs.size + " conversations reloaded." + (relayAsked ? " The relay phone is checking its messages too." : ""), 4500);
      if (state.view === "settings") renderRelaySettings();
    } catch (e) {
      if (e.auth) return unlinkedHere(e.message);
      toast(e.message, 4000);
    } finally {
      btn.disabled = false;
    }
  }

  /** Pull to refresh: reload the list from the inbox now, and have the relay phone check Messages and its message store. */
  async function refreshInbox() {
    api("/api/relay/wake", { method: "POST", body: { kind: "refresh" } }).catch(() => {});
    try {
      await loadInbox();
      await sync();
    } catch (e) {
      if (e.auth) unlinkedHere(e.message);
      else toast(e.message, 3000);
    }
  }

  function wirePull() {
    const list = $("conv-list");
    const pull = $("pull");
    let startY = 0, dist = 0, tracking = false, busy = false;
    const place = (d, spin) => {
      pull.style.top = $("view-inbox").querySelector(".top").offsetHeight + "px";
      pull.style.opacity = String(Math.min(1, d / PULL_AT));
      pull.style.transform = "translateY(" + (d - 44) + "px)" + (spin ? "" : " rotate(" + Math.round(d * 4) + "deg)");
      pull.classList.toggle("spinning", !!spin);
    };
    const reset = () => {
      pull.classList.add("settle");
      place(0, false);
      setTimeout(() => pull.classList.remove("settle"), 220);
    };
    list.addEventListener("touchstart", e => {
      tracking = !busy && list.scrollTop <= 0 && e.touches.length === 1;
      startY = tracking ? e.touches[0].clientY : 0;
      dist = 0;
    }, { passive: true });
    list.addEventListener("touchmove", e => {
      if (!tracking) return;
      dist = e.touches[0].clientY - startY;
      if (dist <= 0 || list.scrollTop > 0) { if (dist <= 0) place(0, false); return; }
      if (e.cancelable) e.preventDefault();   // the list stays put while the circle comes down
      place(Math.min(PULL_MAX, dist * 0.5), false);
    }, { passive: false });
    const end = async () => {
      if (!tracking) return;
      tracking = false;
      if (Math.min(PULL_MAX, dist * 0.5) < PULL_AT) { reset(); return; }
      busy = true;
      pull.classList.add("settle");
      place(PULL_AT, true);
      try { await refreshInbox(); } finally {
        busy = false;
        reset();
      }
    };
    list.addEventListener("touchend", end);
    list.addEventListener("touchcancel", end);
  }

  // ------------------------------------------------------------ doorbell (realtime nudges from the server)

  // The server rings this socket when a message, a status change, or a relay-state change lands. Payloads
  // carry keys only; the data still comes through the key-gated API. Polling continues as insurance.
  const doorbell = {
    socket: null, connected: false, wanted: false, backoff: 2000, lastSyncAt: 0, timer: null,
    async open() {
      this.wanted = true;
      if (this.socket || !("WebSocket" in window)) return;
      try {
        // A private inbox hands this device a ticket for its own channel; the first version has one public channel.
        const t = state.link
          ? await api("/api/ticket")
          : await fetch("/__hedy/realtime/tickets", {
            method: "POST", headers: { "content-type": "application/json", "accept": "application/json" },
            body: JSON.stringify({ channel: "inbox", capabilities: ["Subscribe"] })
          }).then(r => r.ok ? r.json() : Promise.reject(new Error("ticket " + r.status)));
        if (!this.wanted) return;
        const ws = new WebSocket(t.webSocketUrl + "?ticket=" + encodeURIComponent(t.ticket));
        this.socket = ws;
        ws.onopen = () => { this.connected = true; this.backoff = 2000; this.ring("open"); };
        ws.onmessage = (e) => {
          let m = null;
          try { m = JSON.parse(e.data); } catch { return; }
          this.ring(m.type || "", m.payloadJson ? JSON.parse(m.payloadJson) : {});
        };
        ws.onclose = () => { this.socket = null; this.connected = false; this.retry(); };
        ws.onerror = () => { try { ws.close(); } catch { /* already closing */ } };
      } catch {
        this.socket = null;
        this.retry();
      }
    },
    retry() {
      if (!this.wanted || this.timer) return;
      this.timer = setTimeout(() => { this.timer = null; this.open(); }, this.backoff);
      this.backoff = Math.min(this.backoff * 2, 60000);
    },
    close() {
      this.wanted = false;
      clearTimeout(this.timer); this.timer = null;
      const ws = this.socket; this.socket = null; this.connected = false;
      if (ws) try { ws.close(); } catch { /* fine */ }
    },
    ring() {
      this.lastSyncAt = Date.now();
      sync();   // new messages and sent/failed changes all come through the arrival log
    }
  };

  // ------------------------------------------------------------ linking this phone
  // One scan links two phones. Relay shows a code: on a phone that isn't set up it asks to be let in, on one that is
  // (Link a device) it invites. Texts scans it and either joins that inbox, brings the new phone into its own inbox,
  // or, when neither phone has one yet, starts a new private inbox for the two of them. See PROTOCOL.md.

  function canScan() {
    return !!native && typeof native.scanQr === "function";
  }

  /** A Texts app from before private inboxes (it loads this page from the server and can't keep an inbox's link). */
  function oldApp() {
    return !!native && typeof native.setLink !== "function";
  }

  function storageGet(k) { try { return localStorage.getItem(k); } catch { return null; } }
  function storageSet(k, v) { try { if (v === null) localStorage.removeItem(k); else localStorage.setItem(k, v); } catch { /* private mode */ } }

  /** What this device is called in the inbox's device list. */
  function deviceLabel() {
    const n = nativeCall("deviceName");
    if (n) return String(n).slice(0, 60);
    const ua = navigator.userAgent;
    const os = /Android/.test(ua) ? "Android" : /iPhone|iPad/.test(ua) ? "iPhone" : /Mac OS X/.test(ua) ? "Mac" : /Windows/.test(ua) ? "Windows" : /Linux/.test(ua) ? "Linux" : "";
    const br = /Firefox\//.test(ua) ? "Firefox" : /Edg\//.test(ua) ? "Edge" : /Chrome\//.test(ua) ? "Chrome" : /Safari\//.test(ua) ? "Safari" : "Browser";
    return br + (os ? " on " + os : "");
  }

  function readLink(raw) {
    try {
      const l = typeof raw === "string" ? JSON.parse(raw) : raw;
      if (l && /^[0-9a-f]{32}$/.test(l.tid) && typeof l.token === "string" && l.token.length === 43 && typeof l.key === "string") return { tid: l.tid, token: l.token, key: l.key };
    } catch { /* not a link */ }
    return null;
  }

  /** This device's private inbox, if it has one: the Texts app keeps it, a browser keeps it here. */
  function storedLink() {
    if (native && typeof native.link === "function") return readLink(nativeCall("link") || "");
    return readLink(storageGet(LINK) || "");
  }

  async function useLink(l) {
    if (state.link && (!l || l.tid !== state.link.tid)) {
      // Another inbox, or none: nothing opened with the old key stays in memory.
      for (const p of [...mediaUrls.keys()]) forgetMedia(p);
      mediaPinned.clear();
      opened.clear();
    }
    state.link = l;
    state.keys = l ? await TR2.inboxKeys(TR2.unb64u(l.key)) : null;
  }

  /** Keeps the link where it lives and opens the inbox. `how.created`: this device just started that inbox. */
  async function saveLink(l, message, how) {
    await useLink(l);
    await api("/api/ping");
    nameThisDevice().catch(() => {});
    if (native && typeof native.setLink === "function") nativeCall("setLink", JSON.stringify(l));
    else storageSet(LINK, JSON.stringify(l));
    storageSet(UNLINKED, null);
    $("code-input").value = "";
    $("connect-error").hidden = true;
    state.convs.clear(); state.msgs.clear(); state.cursors.clear(); state.complete.clear();
    state.feed = null; state.epoch = null; state.loadedInbox = false;
    show("inbox");
    startSync();
    refreshPushRegistration().catch(() => {});
    toast(message, 4000);
    if (state.key) moveIn(how);   // a first-version inbox comes along (see moveIn)
  }

  /** A link call: the platform's refusals ({ error: { message } }, a rate limit) and the inbox's ({ error }) alike. */
  async function linkCall(path, body, withToken) {
    const headers = { "content-type": "application/json", "accept": "application/json" };
    if (withToken && state.link) headers["x-relay-token"] = state.link.token;
    const res = await fetch(path, { method: "POST", headers, body: JSON.stringify(body), cache: "no-store" });
    let data = null;
    try { data = await res.json(); } catch { data = null; }
    if (!res.ok) throw new Error(refusal(res.status, data));
    return data || {};
  }

  /**
   * A code from Relay, scanned or typed. An invite: join that inbox. A phone asking to be let in: bring it into this
   * phone's inbox (a new relay phone takes over from the old one), or start a new private inbox with it.
   */
  async function linkWithCode(raw) {
    const parsed = TR2.parseLink(raw);
    const say = (text) => {
      if (state.view === "connect") { $("connect-error").textContent = text; $("connect-error").hidden = false; }
      else toast(text, 6000);
    };
    if (!parsed) {
      say(/^TEXTRELAY:/i.test(String(raw || "").trim())
        ? "That code is from an older Paigr Relay. Update it on that phone, then scan the code it shows."
        : "That isn't a code from Paigr Relay. Enter the 12 letters and numbers it shows, or scan its code.");
      return;
    }
    if (parsed.server && parsed.server !== location.origin) {
      say("That code is for a different inbox server (" + parsed.server.replace(/^https?:\/\//, "") + ").");
      return;
    }
    if (oldApp()) {
      say("Update Paigr on this phone first, then scan the code again.");
      return;
    }
    for (const id of ["connect-btn", "scan-btn", "approve-go"]) $(id).disabled = true;
    const note = $("connect-note");
    note.textContent = "Linking…";
    note.hidden = state.view !== "connect";
    try {
      const s = await TR2.linkSecrets(parsed.code);
      const mine = state.link;
      const token = mine ? null : TR2.newToken();
      const claim = await linkCall("/v2/link/claim", mine ? { lookup: s.lookup } : { lookup: s.lookup, token }, !!mine);
      if (claim.kind === "invite") {
        const box = await TR2.openJson(s.boxKey, claim.box, TR2.AAD.box);
        if (box.tid !== claim.tid || typeof box.key !== "string") throw new Error("That code didn't open. Make a new one in Paigr Relay.");
        await saveLink({ tid: box.tid, token, key: box.key }, "Linked. Your messages are loading.");
        return;
      }
      if (mine) {
        // A phone waiting to be let into this inbox: a new relay phone (taking over) or a browser.
        if (claim.role === "relay" && claim.hasRelay) {
          // The page's own words: the server's answer only picks which question this is.
          if (!confirm("Make the phone showing this code your relay phone? Your current relay phone stops relaying for this inbox.")) return;
        }
        const answer = await TR2.sealJson(s.boxKey, { v: 2, tid: mine.tid, key: mine.key }, TR2.AAD.box);
        await linkCall("/v2/link/adopt", { lookup: s.lookup, answer }, true);
        $("approve-panel").hidden = true;
        $("approve-input").value = "";
        toast(claim.role === "relay" ? "Done. That phone is your relay phone now; it connects in a few seconds." : "Done. That device is linked.", 5000);
        if (state.view === "settings") renderDevices();
        return;
      }
      if (claim.role !== "relay") throw new Error("That device isn't linked either. Scan the code Paigr Relay shows on the phone with the SIM card.");
      // Neither phone has an inbox yet: this one starts a private inbox for the two of them.
      const key = TR2.newKey();
      const tid = TR2.newTid();
      const answer = await TR2.sealJson(s.boxKey, { v: 2, tid, key: TR2.b64u(key) }, TR2.AAD.box);
      await linkCall("/v2/link/adopt", { lookup: s.lookup, answer, create: { tid, token } });
      // This device started this inbox: the first version's inbox moves into it by itself (see moveIn).
      if (state.key) writeMoved({ tid, done: false, declined: false, started: 0, convs: {} });
      await saveLink({ tid, token, key: TR2.b64u(key) }, "Linked. Your private inbox is ready.", { created: true });
    } catch (e) {
      say(e.message);
    } finally {
      for (const id of ["connect-btn", "scan-btn", "approve-go"]) $(id).disabled = false;
      note.hidden = true;
    }
  }

  function disconnected(message) {
    stopSync();
    $("connect-error").textContent = message || "";
    $("connect-error").hidden = !message;
    renderConnect(false);
    show("connect");
  }

  /** This device was taken out of its inbox (or the first version's key stopped working). */
  async function unlinkedHere(message) {
    if (state.link) {
      await useLink(null);
      if (native && typeof native.setLink === "function") nativeCall("setLink", "");
      storageSet(LINK, null);
    }
    disconnected(message);
  }

  /** In the Texts app the scanner comes first and typing the code is the fallback; in a browser it's the code. */
  function renderConnect(typing) {
    const scan = canScan();
    $("scan-btn").hidden = !scan;
    $("step-scan").hidden = !scan;
    $("step-type").hidden = scan;
    $("code-entry").hidden = scan && !typing;
    $("enter-code-btn").hidden = !scan || typing;
  }

  function scanCode(forWhat) {
    state.scanFor = forWhat;
    nativeCall("scanQr");
  }

  function connect(given) {
    const code = String(given || $("code-input").value).trim();
    if (TR2.normCode(code).length < 12) {
      $("connect-error").textContent = "Enter the whole code Paigr Relay shows (12 letters and numbers).";
      $("connect-error").hidden = false;
      return;
    }
    return linkWithCode(code);
  }

  // Called by the Texts app's scanner with what it read, or when it closed without a code.
  window.textsScanned = (text) => {
    state.scanFor = null;
    linkWithCode(text);
  };
  window.textsScanClosed = (typeInstead) => {
    const forWhat = state.scanFor;
    state.scanFor = null;
    if (!typeInstead) return;
    if (forWhat === "approve") { $("approve-panel").hidden = false; $("approve-input").focus(); }
    else { renderConnect(true); $("code-input").focus(); }
  };
  // The Texts app found this phone's inbox again after a reinstall (or found nothing).
  window.textsLinked = async () => {
    const l = storedLink();
    if (!l || state.link) { $("connect-note").hidden = true; return; }
    try { await saveLink(l, "Linked again. Your messages are loading."); } catch { $("connect-note").hidden = true; }
  };

  // ------------------------------------------------------------ devices in this inbox

  /**
   * This device's name for itself, sealed with the inbox key for its own id: the device list shows only names that open
   * that way, so every name in it is one a device of the inbox gave itself, never the server's words. Sent once per
   * inbox and name.
   */
  async function nameThisDevice() {
    const link = state.link, keys = state.keys;
    if (!link || !keys) return;
    const name = plainName(deviceLabel());
    const id = await TR2.deviceId(link.token);
    const mark = link.tid + ":" + id + ":" + name;   // a device back in the same inbox has a new id, so names itself again
    if (!name || storageGet(NAMED) === mark) return;
    const sn = await TR2.sealJson(keys.enc, { name }, TR2.AAD.device(id));
    await api("/api/devices/name", { method: "POST", body: { sn }, link });
    storageSet(NAMED, mark);
  }

  /** A listed device's name, as that device sealed it for its own id; "" for anything else. */
  async function deviceName(d) {
    if (!d || typeof d.sn !== "string" || !d.sn || typeof d.id !== "string" || !/^[a-f0-9]{12}$/.test(d.id)) return "";
    const said = await openBound(d.sn, [TR2.AAD.device(d.id)]);
    return said.unreadable ? "" : plainName(said.name);
  }

  async function renderDevices() {
    const head = $("devices-head"), group = $("devices-group"), list = $("device-list");
    head.hidden = group.hidden = !state.link;
    if (!state.link) return;
    const link = state.link;
    let items = [];
    try { items = (await api("/api/devices")).items || []; } catch (e) { list.innerHTML = ""; list.appendChild(el("li", "", e.message)); return; }
    if (!Array.isArray(items)) items = [];
    const names = await Promise.all(items.map(d => deviceName(d).catch(() => "")));
    if (state.link !== link) return;
    list.innerHTML = "";
    items.forEach((d, i) => {
      if (!d || typeof d !== "object") return;
      const li = el("li");
      const chip = el("span", "chip" + (d.role === "relay" ? "" : " ok"));
      chip.appendChild(icon(d.role === "relay" ? "i-beacon" : "i-phone"));
      li.appendChild(chip);
      const lines = el("div", "lines");
      // Each name as its own device sealed it; the relay phone's model as it sealed it in its status.
      const name = names[i];
      const model = d.role === "relay" && state.relay ? state.relay.model : "";
      const what = d.role === "relay" ? "Relay phone" + (model ? " · " + model : "") : (name || "Paigr");
      lines.appendChild(el("div", "l1", what + (d.you === true ? " (this device)" : "")));
      lines.appendChild(el("div", "l2", (d.role === "relay" && name && name !== model ? name + " · " : "") + "linked " + (Number.isFinite(d.at) ? fmtDay(d.at) : "")));
      li.appendChild(lines);
      if (!d.you) {
        const b = el("button", "", "Remove");
        b.type = "button";
        b.addEventListener("click", async () => {
          if (!confirm("Remove this device from your inbox? It stops getting your messages at once.")) return;
          try { await api("/api/devices/remove", { method: "POST", body: { id: d.id } }); toast("Removed."); } catch (e) { toast(e.message, 4000); }
          renderDevices();
        });
        li.appendChild(b);
      }
      list.appendChild(li);
    });
  }

  // ------------------------------------------------------------ moving the first version's inbox in

  function readMoved() {
    try {
      const v = JSON.parse(storageGet(MOVED) || "{}");
      return { tid: typeof v.tid === "string" ? v.tid : "", done: !!v.done, declined: !!v.declined, started: Number(v.started) || 0,
        convs: v.convs && typeof v.convs === "object" ? v.convs : {} };
    } catch { return { tid: "", done: false, declined: false, started: 0, convs: {} }; }
  }

  /**
   * Waits (up to a minute) for the relay phone's first check-in with the private inbox: from then on every text goes
   * there, so the move can't miss one that was still on its way to the first version's inbox.
   */
  async function relaySwitched(mv) {
    const until = Date.now() + 60000;
    state.move.waiting = true;
    renderMove();
    try {
      while (Date.now() < until) {
        try {
          const s = await api("/api/status", { link: mv.link });
          if (s.relay && s.relay.lastSeen) {
            await sleep(5000);   // anything it was already sending lands first
            return true;
          }
        } catch (e) {
          if (e.auth || e.stale) throw e;
        }
        await sleep(3000);
      }
      return false;
    } finally {
      if (state.move) { state.move.waiting = false; renderMove(); }
    }
  }

  function writeMoved(v) {
    storageSet(MOVED, JSON.stringify(v));
  }

  /** The first version's key isn't needed once everything is in the private inbox. */
  function dropV1() {
    state.key = "";
    storageSet(KEY, null);
    nativeCall("setKey", "");
  }

  /** Runs `fn` over `items`, at most `limit` at a time, keeping the order. */
  async function mapLimit(items, limit, fn) {
    const out = new Array(items.length);
    let next = 0;
    const worker = async () => { while (next < items.length) { const i = next++; out[i] = await fn(items[i]); } };
    await Promise.all(Array.from({ length: Math.min(limit, items.length) }, worker));
    return out;
  }

  /** A first-version message key with its fingerprint keyed, as the relay phone now files that message. */
  async function movedKey(mv, k) {
    const m = /^m:([0-9]{13}):([a-z0-9]{8,32})$/.exec(k || "");
    if (!m) return null;
    return "m:" + m[1] + ":" + (/^[a-f0-9]{8,32}$/.test(m[2]) ? await TR2.fpId(mv.keys, m[2]) : m[2]);
  }

  /** Stops the move when this device is in another inbox than the one it started moving into. */
  function stillMoving(mv) {
    if (state.link === mv.link) return;
    const err = new Error("This device's inbox changed.");
    err.stale = true;
    throw err;
  }

  /** One attachment: fetched from the first version's storage, sealed here, stored in the private inbox. */
  async function moveFile(mv, path, wconv, ts, wfp, kind) {
    const r = await api("/api/media?p=" + encodeURIComponent(path), { v1: true, link: mv.link });
    const res = await fetch(fileUrl(r.url), { cache: "no-store" });
    if (!res.ok) throw new Error("file " + res.status);
    const sealed = await TR2.sealBytes(mv.keys.enc, new Uint8Array(await res.arrayBuffer()), TR2.AAD.file(wconv, ts, wfp, kind));
    if (sealed.length <= 4 * 1024 * 1024) {
      stillMoving(mv);
      const up = await fetch("/v2/media/put", {
        method: "POST", body: sealed, cache: "no-store",
        headers: { "x-relay-token": mv.link.token, "x-relay-conv": wconv, "x-relay-fp": wfp, "x-relay-ts": String(ts), "x-relay-kind": kind, "content-type": "application/octet-stream" }
      });
      const d = await up.json().catch(() => null);
      if (!up.ok || !d || !d.path) throw new Error("store " + up.status);
      return d.path;
    }
    const t = await api("/api/media/ticket", { method: "POST", body: { conv: wconv, fp: wfp, ts, bytes: sealed.length, kind }, link: mv.link });
    const headers = {};
    for (const [k, v] of Object.entries(t.headers || {})) if (k.toLowerCase() !== "content-length") headers[k] = v;
    const put = await fetch(fileUrl(t.uploadUrl), { method: "PUT", headers, body: sealed });
    if (!put.ok) throw new Error("upload " + put.status);
    const done = await api("/api/media/complete", { method: "POST", body: { ticket: t.ticket, path: t.path }, link: mv.link });
    return done.path || t.path;
  }

  /**
   * One first-version message, sealed here for the private inbox. Its payload says it was moved: what the first
   * version's server held is all it rests on (that server could read and change it), unlike what the relay phone seals.
   */
  async function moveMessage(mv, m, wconv) {
    const km = /^m:([0-9]{13}):([a-z0-9]{8,32})$/.exec(m && m.k || "");
    if (!km) return null;
    const ts = Number(km[1]);
    const wfp = /^[a-f0-9]{8,32}$/.test(km[2]) ? await TR2.fpId(mv.keys, km[2]) : km[2];
    let media = null;
    if (m.media) {
      media = {};
      for (const [field, kind] of [["path", "original"], ["preview", "preview"]]) {
        if (!m.media[field]) continue;
        try {
          media[field] = await moveFile(mv, m.media[field], wconv, ts, wfp, kind);
          state.move.files++;
        } catch (e) {
          if (e.stale) throw e;
          state.move.missed++;   // the message comes over without it
        }
      }
      renderMove();
    }
    const dir = m.dir === "out" ? "out" : "in";
    const payload = { text: String(m.text || ""), sender: String(m.sender || ""), name: String(m.name || ""), group: !!m.group, ts, fp: wfp, moved: true };
    if (m.media) {
      payload.media = { mime: String(m.media.mime || "") };
      for (const f of ["w", "h", "bytes"]) if (Number.isFinite(m.media[f])) payload.media[f] = m.media[f];
    }
    const rec = { k: "m:" + km[1] + ":" + wfp, conv: wconv, dir, status: m.status, ts, e: await TR2.sealJson(mv.keys.enc, payload, TR2.AAD.message(wconv, dir)) };
    if (m.src) rec.src = m.src;
    if (m.name) rec.ne = await TR2.sealJson(mv.keys.enc, { name: String(m.name), group: !!m.group, moved: true }, TR2.AAD.name(wconv));
    const mt = await TR2.matchToken(mv.keys, dir, m.text, m.media && m.media.mime);
    if (mt) rec.mt = mt;
    if (media) rec.media = media;
    if (Array.isArray(m.from)) rec.from = m.from;
    if (Array.isArray(m.twins)) rec.twins = (await Promise.all(m.twins.map(k => movedKey(mv, k)))).filter(Boolean);
    for (const f of ["history", "read", "via", "sentAt", "receivedAt"]) if (m[f] !== undefined && m[f] !== null) rec[f] = m[f];
    return rec;
  }

  async function moveConv(mv, c) {
    const wconv = await TR2.convId(mv.keys, c.conv);
    const all = [];
    let cursor = null;
    for (let page = 0; page < 2000; page++) {
      const data = await api("/api/thread?conv=" + encodeURIComponent(c.conv) + (cursor ? "&cursor=" + encodeURIComponent(cursor) : ""), { v1: true, link: mv.link });
      for (const m of data.items || []) all.push(m);
      cursor = data.cursor;
      if (!cursor) break;
    }
    state.move.messages += all.length;
    for (let i = 0; i < all.length; i += 25) {
      const recs = await mapLimit(all.slice(i, i + 25), 3, m => moveMessage(mv, m, wconv));
      await api("/api/import", { method: "POST", body: { messages: recs.filter(Boolean), convs: [] }, link: mv.link });
      state.move.moved += recs.filter(Boolean).length;
      renderMove();
    }
    const lastDir = c.lastDir === "out" ? "out" : "in";
    const summary = {
      conv: wconv,
      ne: c.name ? await TR2.sealJson(mv.keys.enc, { name: String(c.name), group: !!c.group, moved: true }, TR2.AAD.name(wconv)) : null,
      lastTs: c.lastTs || 0,
      lastE: await TR2.sealJson(mv.keys.enc, { text: String(c.lastText || ""), sender: String(c.lastSender || ""), name: String(c.name || ""), group: !!c.group,
        ts: Number(c.lastTs) || 0, moved: true }, TR2.AAD.message(wconv, lastDir)),
      lastDir, lastMedia: false, canReply: c.canReply !== false, unread: c.unread || 0
    };
    await api("/api/import", { method: "POST", body: { messages: [], convs: [summary] }, link: mv.link });
  }

  const MOVE_TRIES = 5;   // in a row, while this page is open; it starts over the next time the page opens
  let moveFailures = 0;

  /**
   * The first version's inbox, moved into the private one: every conversation and message, sealed here, and every
   * attachment fetched, sealed and stored again. Runs in the background behind a note at the top of the inbox, and
   * picks up where it stopped if the app closes. Once it's done the first version's key is dropped.
   *
   * Everything moves by itself only into an inbox this device started (by scanning the relay phone's own code, the
   * "Make your inbox private" step); joining someone's inbox by an invite asks first, since everyone in it could read
   * what came along. The move stays with the inbox it started in: if this device joins another one meanwhile, it stops.
   */
  async function moveIn(how) {
    if (state.move || !state.link || !state.key) return;
    const mv = { link: state.link, keys: state.keys };
    let progress = readMoved();
    if (progress.tid !== mv.link.tid) {
      if (!(how && how.created) && !confirm("Bring the texts from this device's first inbox into this one? Every device linked to this inbox will be able to read them.")) {
        writeMoved({ tid: mv.link.tid, done: false, declined: true, started: 0, convs: {} });
        return;
      }
      progress = { tid: mv.link.tid, done: false, declined: false, started: 0, convs: {} };
      writeMoved(progress);
    }
    if (progress.declined) return;
    if (progress.done) { dropV1(); renderMove(); return; }
    state.move = { done: 0, total: 0, messages: 0, moved: 0, files: 0, missed: 0, error: null, final: false, waiting: false };
    renderMove();
    try {
      if (!progress.started) {
        await relaySwitched(mv);
        progress.started = Date.now();
        writeMoved(progress);
      }
      const inbox = await api("/api/inbox", { v1: true, link: mv.link });
      const convs = (inbox.items || []).filter(c => c && c.conv).sort((a, b) => (b.lastTs || 0) - (a.lastTs || 0));
      state.move.total = convs.length;
      state.move.done = convs.filter(c => progress.convs[c.conv]).length;
      renderMove();
      for (const c of convs) {
        if (progress.convs[c.conv]) continue;
        await moveConv(mv, c);
        progress.convs[c.conv] = true;
        writeMoved(progress);
        state.move.done++;
        renderMove();
      }
      await api("/api/import/done", { method: "POST", body: {}, link: mv.link });
      // A text that reached the first version's inbox while the relay phone switched over comes again from that phone
      // (the last week, from its message store and what Messages still shows); copies fold into what moved.
      await api("/api/relay/wake", { method: "POST", body: { kind: "resync" }, link: mv.link }).catch(() => {});
      stillMoving(mv);
      progress.done = true;
      writeMoved(progress);
      const missed = state.move.missed;
      dropV1();
      state.move = null;
      moveFailures = 0;
      renderMove();
      toast("Everything's moved into your private inbox." + (missed ? " " + missed + " attachment" + (missed === 1 ? "" : "s") + " couldn't come along." : ""), 6000);
      try { await reloadEverything(); } catch { /* the next sync loads it */ }
    } catch (e) {
      if (!state.move) return;
      if (e.stale) {   // this device is in another inbox now: the move stays with the one it started in
        state.move = null;
        renderMove();
        return;
      }
      if (e.auth && e.v1) {   // the first version's key no longer works: there's nothing left to move
        dropV1();
        state.move = null;
        renderMove();
        return;
      }
      moveFailures++;
      state.move.error = e.message;
      state.move.final = moveFailures >= MOVE_TRIES;
      renderMove();
      if (!state.move.final) setTimeout(() => { if (state.move && state.move.error) { state.move = null; moveIn(); } }, 30000);
    }
  }

  /** The note at the top of the inbox: the move under way, or (still on the first version) how to make it private. */
  function renderMove() {
    const b = $("move-banner");
    const m = state.move;
    b.innerHTML = "";
    if (m) {
      const n = (x) => Number(x || 0).toLocaleString();
      b.appendChild(document.createTextNode(m.error
        ? "Moving your messages into your private inbox stopped (" + m.error + "). " + (m.final ? "It tries again the next time Paigr opens." : "It tries again in a moment.")
        : m.waiting ? "Waiting for the relay phone to switch to your private inbox, then your messages move in. Keep Paigr open."
        : "Moving your messages into your private inbox: " + n(m.done) + " of " + n(m.total) + " conversations"
          + (m.moved ? ", " + n(m.moved) + " messages" : "") + (m.files ? ", " + n(m.files) + " attachments" : "") + ". Keep Paigr open until it's done."));
      b.hidden = false;
      return;
    }
    if (state.key && !state.link) {
      b.appendChild(document.createTextNode(oldApp()
        ? "Make your inbox private: update Paigr on this phone and Paigr Relay on the phone with the SIM card, then scan the code it shows."
        : "Make your inbox private: update Paigr Relay on the phone with the SIM card, then scan the code it shows."));
      const go = el("button", "", canScan() ? "Scan" : "Enter the code");
      go.type = "button";
      go.addEventListener("click", () => {
        if (canScan()) scanCode("approve");
        else { openSettings(); $("approve-panel").hidden = false; $("approve-input").focus(); }
      });
      b.appendChild(go);
      b.hidden = false;
      return;
    }
    b.hidden = true;
  }

  // ------------------------------------------------------------ push (browser only; the Android app has its own)

  function pushSupported() {
    return "serviceWorker" in navigator && "PushManager" in window && "Notification" in window;
  }

  /** What the server says about notifications, once it has answered: a server run at home sends none. */
  let pushConfig = null;
  async function serverPush() {
    if (pushConfig) return pushConfig;
    const r = await fetch("/__hedy/push/config");
    if (!r.ok) throw new Error("Notifications are not enabled on the server.");
    pushConfig = await r.json();
    return pushConfig;
  }

  async function subscribePush() {
    const config = await serverPush();
    if (!config.enabled) throw new Error("Notifications are not enabled on the server.");
    if (!config.allowVisitorSubscriptions) throw new Error("The server does not allow device subscriptions.");
    const reg = await navigator.serviceWorker.register("/sw.js");
    await navigator.serviceWorker.ready;
    let sub = await reg.pushManager.getSubscription();
    if (!sub) {
      sub = await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: b64ToBytes(config.vapidPublicKey) });
    }
    const keys = sub.toJSON().keys;
    const res = await fetch("/__hedy/push/subscriptions", {
      method: "POST",
      headers: { "content-type": "application/json", "accept": "application/json" },
      body: JSON.stringify({ endpoint: sub.endpoint, p256dh: keys.p256dh, auth: keys.auth, userAgent: navigator.userAgent, client: "texts-pwa" })
    });
    if (!res.ok) throw new Error("Could not register this device for notifications (" + res.status + ").");
    const { id } = await res.json();
    localStorage.setItem(PUSH_ID, id);
    await api("/api/push/register", { method: "POST", body: { subscriptionId: id } });
    return id;
  }

  async function enablePush() {
    if (!pushSupported()) { toast("This browser can't do notifications."); return; }
    const permission = await Notification.requestPermission();
    if (permission !== "granted") { toast("Notifications were not allowed."); renderPushState(); return; }
    try {
      await subscribePush();
      toast("Notifications are on.");
    } catch (e) {
      toast(e.message, 4000);
    }
    renderPushState();
  }

  async function refreshPushRegistration() {
    if (!pushSupported() || Notification.permission !== "granted" || !localStorage.getItem(PUSH_ID)) return;
    await subscribePush();   // re-posting refreshes the server-side expiry
  }

  function renderPushState() {
    const p = $("push-state");
    if (native) {
      const allowed = nativeCall("notificationsAllowed") !== false;
      const exempt = nativeCall("batteryExempt") === true;
      const off = nativeCall("alertMode") === "off";
      p.textContent = !allowed ? "Off. Allow notifications for Paigr to hear about new messages."
        : off ? "Off. Paigr checks for new messages only while it's open."
        : "On. Paigr listens for new messages in the background.";
      setRow("push-btn", allowed ? "System notification settings" : "Allow notifications");
      $("push-btn").disabled = false;
      $("push-test-btn").hidden = true;
      $("battery-btn").hidden = exempt || off;
      renderNativeChoices();
      return;
    }
    $("alert-row").hidden = true;
    $("status-row").hidden = true;
    $("battery-btn").hidden = true;
    const btn = $("push-btn");
    if (!pushSupported()) { p.textContent = "This browser doesn't support notifications."; btn.disabled = true; return; }
    if (pushConfig && !pushConfig.enabled) { p.textContent = "This inbox server doesn't send notifications to browsers."; btn.disabled = true; return; }
    if (!pushConfig) serverPush().then(() => { if (pushConfig && !pushConfig.enabled) renderPushState(); }, () => { /* can't tell yet */ });
    btn.disabled = false;
    if (Notification.permission === "denied") { p.textContent = "Notifications are blocked in the browser settings for this site."; return; }
    if (Notification.permission === "granted" && localStorage.getItem(PUSH_ID)) { p.textContent = "On for this device."; setRow("push-btn", "Re-register this device"); return; }
    p.textContent = "Off. Turn them on to hear about new texts when the app is closed.";
    setRow("push-btn", "Turn on notifications");
  }

  /**
   * The Android app's own controls: how new messages alert, and whether the background notification shows. Android
   * shows that one while an app listens in the background; only its own settings can turn it off, so Hidden opens them.
   */
  function renderNativeChoices() {
    const alert = nativeCall("alertMode");
    const status = nativeCall("statusMode");
    $("alert-row").hidden = alert === undefined;
    $("status-row").hidden = status === undefined || alert === "off";
    for (const b of document.querySelectorAll("[data-alert]")) b.classList.toggle("on", b.dataset.alert === alert);
    for (const b of document.querySelectorAll("[data-status]")) b.classList.toggle("on", b.dataset.status === status);
    $("alert-sub").textContent = alert === "off"
      ? "No notifications at all. Paigr doesn't run in the background; new messages are here when you open it."
      : alert === "quiet"
        ? "New messages appear in the notification shade, with no sound, vibration or pop-up."
        : "New messages make a sound and can pop up on screen.";
    $("status-sub").textContent = status === "hidden"
      ? "Turned off in Android's settings. Paigr still listens for new messages."
      : "Android shows this while Paigr listens for new messages. Hidden opens Android's setting to turn it off; Paigr keeps listening.";
  }

  function b64ToBytes(value) {
    const padded = value + "=".repeat((4 - value.length % 4) % 4);
    const b64 = padded.replace(/-/g, "+").replace(/_/g, "/");
    return Uint8Array.from(atob(b64), c => c.charCodeAt(0));
  }

  // ------------------------------------------------------------ install as app (browser only)

  let installPrompt = null;   // the browser's deferred install prompt, when it offers one

  function isInstalled() {
    return window.matchMedia("(display-mode: standalone)").matches || window.navigator.standalone === true;
  }

  function installHint() {
    const ua = navigator.userAgent;
    if (/iPhone|iPad|iPod/.test(ua)) return "In Safari, tap Share, then “Add to Home Screen”.";
    if (/Firefox|FxiOS/.test(ua)) return "In Firefox, open the ⋮ menu and tap “Install” (or “Add to Home screen”).";
    if (/SamsungBrowser/.test(ua)) return "Open the browser menu and tap “Add page to” → Home screen.";
    return "Open the browser menu and tap “Install app” (or “Add to Home screen”).";
  }

  async function installApp() {
    if (installPrompt) {
      const p = installPrompt;
      installPrompt = null;
      p.prompt();
      const choice = await p.userChoice.catch(() => null);
      if (!choice || choice.outcome !== "accepted") installPrompt = p;
      renderInstall();
      return;
    }
    toast(installHint(), 6000);
  }

  function renderInstall() {
    const installed = isInstalled() || !!native;
    const dl = $("texts-dl-connect");
    if (dl) dl.hidden = !!native;
    for (const id of ["install-btn", "install-row"]) {
      const b = $(id);
      if (b) b.hidden = installed;
    }
    const note = $("install-note");
    if (note) note.textContent = native ? "You're using the Paigr app (" + (nativeCall("version") || "") + ")." : installed ? "Installed. You're using it as an app." : (installPrompt ? "Adds Paigr to your home screen with its own icon." : installHint());
  }

  function wireInstall() {
    window.addEventListener("beforeinstallprompt", e => {
      e.preventDefault();
      installPrompt = e;
      renderInstall();
    });
    window.addEventListener("appinstalled", () => { installPrompt = null; renderInstall(); toast("Installed. Open Paigr from your home screen."); });
    $("install-btn").addEventListener("click", installApp);
    $("install-row").addEventListener("click", installApp);
    renderInstall();
  }

  // ------------------------------------------------------------ settings

  function renderRelaySettings() {
    renderHistory();
    const r = state.relay;
    const h = relayHealth(r);
    if ($("settings-relay-title")) {
      $("settings-relay-title").textContent = reconnecting ? "Reconnecting…" : h.text;
      $("settings-relay-row").querySelector(".chip").className = "chip " + h.level;
    }
    const lines = [];
    if (r && r.lastSeen) {
      lines.push("Checked in " + ago(r.lastSeen) + (Number.isFinite(r.battery) && r.battery > 0 ? ", " + Math.round(r.battery) + "%" + (r.charging ? " charging" : "") : ""));
      if (r.model) lines.push(r.model);
      if (Number.isFinite(r.queued) && r.queued > 0) lines.push(Math.floor(r.queued) + " waiting to upload");
      if (r.smsFallback === false) lines.push("SMS fallback off");
      if (r.note) lines.push(r.note);
    } else {
      lines.push("Open Paigr Relay on it and check it says Relaying.");
    }
    $("settings-relay").textContent = lines.join("\n");
  }

  /** What the relay phone reports about its own message store: allowed or not, and how far the full history is. */
  function renderHistory() {
    const p = $("history-state");
    const st = state.relay && state.relay.store;
    p.hidden = !(state.relay && state.relay.lastSeen);
    if ($("history-row")) $("history-row").hidden = p.hidden;
    if (p.hidden) return;
    if (!st || !st.allowed) {
      p.textContent = "Off. Turn on message access in Paigr Relay to bring in older texts.";
      return;
    }
    const h = st.history || {};
    const n = (x) => Number(x || 0).toLocaleString();
    if (h.state === "running") p.textContent = "Syncing " + n(h.done) + " of " + n(h.total);
    else if (h.state === "paused") p.textContent = "Paused at " + n(h.done) + " of " + n(h.total) + (h.note ? ". " + h.note : "");
    else if (h.state === "done") p.textContent = n(h.total) + " messages, synced " + ago(h.at) + (h.media ? ", with pictures" : ", text only");
    else p.textContent = "On for new texts. Sync the full history in Paigr Relay.";
  }

  function openSettings() {
    show("settings");
    renderRelaySettings();
    renderHistory();
    renderPushState();
    renderInstall();
    $("approve-panel").hidden = true;
    $("approve-sub").textContent = (canScan() ? "Scan" : "Type") + " the code Paigr Relay shows on it"
      + (state.link ? "" : ". Your inbox becomes private, with everything in it.");
    renderDevices();
    $("version-line").textContent = "Paigr " + VERSION + (native ? " · app " + (nativeCall("version") || "") : "");
  }

  // ------------------------------------------------------------ wiring

  function backToInbox() {
    state.current = null;
    show("inbox");
    renderInbox();
  }

  function wire() {
    $("connect-btn").addEventListener("click", () => connect());
    $("code-input").addEventListener("keydown", e => { if (e.key === "Enter") connect(); });
    $("scan-btn").addEventListener("click", () => scanCode("link"));
    $("enter-code-btn").addEventListener("click", () => { renderConnect(true); $("code-input").focus(); });
    $("approve-btn").addEventListener("click", () => {
      if (canScan()) scanCode("approve");
      else { $("approve-panel").hidden = false; $("approve-input").focus(); }
    });
    $("approve-go").addEventListener("click", () => linkWithCode($("approve-input").value));
    $("approve-input").addEventListener("keydown", e => { if (e.key === "Enter") linkWithCode($("approve-input").value); });
    wirePull();
    $("settings-btn").addEventListener("click", openSettings);
    $("settings-back").addEventListener("click", () => show("inbox"));
    $("back-btn").addEventListener("click", backToInbox);
    $("thread-title").addEventListener("click", openDetails);
    $("thread-media-btn").addEventListener("click", openDetails);
    $("thread-search-btn").addEventListener("click", openFind);
    $("details-back").addEventListener("click", () => show("thread"));
    for (const t of document.querySelectorAll(".tab")) t.addEventListener("click", () => selectTab(t.dataset.tab));
    $("find-close").addEventListener("click", () => closeFind(false));
    $("find-input").addEventListener("input", runFind);
    $("find-input").addEventListener("keydown", e => {
      if (e.key === "Enter") { e.preventDefault(); stepFind(e.shiftKey ? 1 : -1); }
      if (e.key === "Escape") { e.preventDefault(); closeFind(false); }
    });
    $("find-prev").addEventListener("click", () => stepFind(-1));
    $("find-next").addEventListener("click", () => stepFind(1));
    $("search-input").addEventListener("input", e => { state.filter = e.target.value.trim().toLowerCase(); renderInbox(); });
    if ($("search-btn")) {
      $("search-btn").addEventListener("click", () => { if ($("search-row").hidden) openSearch(); else closeSearch(); });
      $("search-input").addEventListener("keydown", e => { if (e.key === "Escape") { e.preventDefault(); closeSearch(); } });
      $("search-input").addEventListener("blur", () => { if (!$("search-input").value.trim()) $("search-row").hidden = true; });
    }
    $("load-more").addEventListener("click", () => loadEarlier().catch(e => toast(e.message)));
    $("composer").addEventListener("submit", e => { e.preventDefault(); sendMessage(); });
    const input = $("msg-input");
    input.addEventListener("input", () => { autosize(input); $("composer").classList.toggle("empty", !input.value.trim() && !state.attachment); });
    input.addEventListener("focus", () => showEmojis(false));
    $("composer").classList.add("empty");
    $("reply-close").addEventListener("click", () => replyTo(null));
    $("attach-close").addEventListener("click", () => attach(null));
    $("attach-btn").addEventListener("click", () => $("attach-input").click());
    $("attach-input").addEventListener("change", e => { const f = e.target.files && e.target.files[0]; if (f) attach(f); e.target.value = ""; });
    $("emoji-btn").addEventListener("click", () => { state.reactTo = null; showEmojis($("emoji-panel").hidden); });

    // On a touch screen Enter starts a new line and the send button sends, as on Android; with a keyboard, Enter sends.
    const touch = !!native || window.matchMedia("(pointer: coarse)").matches;
    if (touch) input.setAttribute("enterkeyhint", "enter");
    input.addEventListener("keydown", e => {
      if (e.key === "Enter" && !e.shiftKey && !touch) { e.preventDefault(); sendMessage(); }
    });
    wireInstall();
    document.addEventListener("keydown", e => {
      if (e.key !== "Escape") return;
      if (!$("lightbox").hidden) { closeLightbox(); return; }
      if ($("msg-menu")) { closeMenu(); return; }
      if (!$("emoji-panel").hidden) { showEmojis(false); return; }
      if (state.replyTo) { replyTo(null); return; }
      if (state.find.open) return;
      if (state.view === "details") { show("thread"); return; }
      if (state.view === "thread") backToInbox();
    });
    $("lightbox").addEventListener("click", e => {
      if (e.target.id !== "lightbox-open" && e.target.tagName !== "VIDEO" && e.target.tagName !== "AUDIO") closeLightbox();
    });
    $("push-btn").addEventListener("click", () => { if (native) nativeCall("requestNotifications"); else enablePush(); });
    $("battery-btn").addEventListener("click", () => nativeCall("requestBattery"));
    $("push-test-btn").addEventListener("click", async () => {
      try { await api("/api/push/test", { method: "POST" }); toast("Test sent. It should pop up in a moment."); }
      catch (e) { toast(e.message, 4000); }
    });
    for (const b of document.querySelectorAll("[data-alert]")) b.addEventListener("click", () => { nativeCall("setAlertMode", b.dataset.alert); renderPushState(); });
    for (const b of document.querySelectorAll("[data-status]")) b.addEventListener("click", () => { nativeCall("setStatusMode", b.dataset.status); renderNativeChoices(); });
    $("reconnect-btn").addEventListener("click", reconnectRelay);
    $("resync-btn").addEventListener("click", resyncAll);
    $("signout-btn").addEventListener("click", async () => {
      if (!confirm("Unlink this phone? It stops getting messages until you link it again with a code from Paigr Relay.")) return;
      if (state.link) {
        // The inbox removes this device (and its way back in after a reinstall).
        try { await api("/api/leave", { method: "POST", body: {} }); } catch { /* removed already, or offline: unlinked here either way */ }
      }
      storageSet(KEY, null);
      state.key = "";
      nativeCall("setKey", "");
      await unlinkedHere("");
    });
    document.addEventListener("visibilitychange", () => {
      if (document.visibilityState === "visible" && linked()) startSync(); else stopSync();
      if (document.visibilityState === "visible" && state.view === "settings") renderPushState();   // back from Android's settings
    });
    if ("serviceWorker" in navigator) {
      navigator.serviceWorker.addEventListener("message", e => {
        if (e.data && e.data.conv) openThread(e.data.conv);
      });
    }
  }

  // Called by the Texts Android app: a notification tap, and the phone's back button.
  window.textsOpen = (conv) => { if (conv && linked()) openThread(conv); };
  window.textsBack = () => {
    if (!$("lightbox").hidden) { closeLightbox(); return true; }
    if (state.find.open) { closeFind(false); return true; }
    if (state.view === "details") { show("thread"); return true; }
    if (state.view === "settings") { show("inbox"); return true; }
    if (state.view === "thread") { backToInbox(); return true; }
    return false;
  };

  async function init() {
    wire();
    if (native && state.key && !nativeCall("key")) nativeCall("setKey", state.key);
    if ("serviceWorker" in navigator) {
      // The Texts app carries this page itself; a service worker left from an older version would only get in the way.
      if (native) navigator.serviceWorker.getRegistrations().then(rs => rs.forEach(r => r.unregister())).catch(() => {});
      else navigator.serviceWorker.register("/sw.js").catch(() => {});
    }
    const stored = storedLink();
    if (stored) {
      try { await useLink(stored); } catch { await useLink(null); }
    }
    const asked = /#link=([0-9A-Za-z-]{12,20})/.exec(location.hash);
    if (asked) history.replaceState(null, "", location.pathname + location.search);
    if (!linked()) {
      disconnected("");
      if (asked) {
        // Opened from a link to this page with a code (https://<server>/#link=<CODE>): filled in, linking waits for a tap.
        renderConnect(true);
        $("code-input").value = TR2.prettyCode(TR2.normCode(asked[1]));
      }
      if (nativeCall("recovery") === "pending") {
        $("connect-note").textContent = "Checking whether this phone was linked before…";
        $("connect-note").hidden = false;
      }
      return;
    }
    show("inbox");
    startSync();
    refreshPushRegistration().catch(() => {});
    nameThisDevice().catch(() => {});
    renderMove();
    if (state.link && state.key) moveIn();
    const conv = new URLSearchParams(location.search).get("conv");
    if (conv) {
      history.replaceState(null, "", "/");
      await loadInbox().catch(() => {});
      openThread(conv);
    }
  }

  init();
})();
