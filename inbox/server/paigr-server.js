#!/usr/bin/env node
"use strict";
// Paigr's inbox server, to run yourself.
//
// It runs the same inbox code as the hosted server (functions/inbox2.js, and functions/watch2.js every five minutes),
// on your own machine. The phones seal every message, name and picture before sending it, so this server only ever
// stores what it can't read, in DATA_DIR. It talks to nobody but the phones: no push service, no analytics, no updates.
//
//   node server/paigr-server.js            listens on port 8787 and keeps everything in ./paigr-data
//   TOR=1 node server/paigr-server.js      runs as a Tor onion service instead, and prints the onion address
//
// Settings, all optional (environment variables):
//   PORT         the port it listens on (8787)
//   HOST         the address it listens on (0.0.0.0; with TOR=1, 127.0.0.1 so that only Tor reaches it)
//   DATA_DIR     where it keeps everything (./paigr-data)
//   TOR          1 to run as an onion service (needs tor; the Docker image has it)
//   PUBLIC_URL   the https address phones use, when a proxy in front of this server serves it (https://paigr.example.com)
//   TRUST_PROXY  1 to take the address from the proxy's X-Forwarded-Proto and X-Forwarded-Host instead
//   TLS_CERT, TLS_KEY   certificate and key files, to serve https itself
//   LOG          1 to log each request: method, route, status and time. Never addresses, tokens or contents.
//
// Needs Node 22.5 or later. No packages: only Node's own modules, so there is nothing else to audit.

const fs = require("fs");
const fsp = require("fs/promises");
const path = require("path");
const http = require("http");
const https = require("https");
const crypto = require("crypto");
const { spawn } = require("child_process");
const { createStore } = require("./store");

const VERSION = "1.0.0";
const ROOT = path.join(__dirname, "..");

const LIMITS = {
  body: 5 * 1024 * 1024,         // a call to the inbox; the largest is a picture sent whole (4 MB)
  upload: 25 * 1024 * 1024 + 64, // a picture or video sent with an upload ticket
  frame: 64 * 1024,              // what a phone may send on its socket (it has nothing to say there)
  sockets: 1000,
  tickets: 20000,
  burst: 300,                    // requests one address can make at once,
  refill: 10                     // and then this many a second
};
const TICKET_MS = 2 * 60000;          // a realtime ticket is used at once, or not at all
const PING_MS = 45000;                // the server checks each socket this often,
const SILENT_MS = 3 * 60000;          // and drops one it hasn't heard from in this long
const SOCKET_LIFE_MS = 2 * 3600000;   // a socket lasts two hours at most; the apps open a fresh one sooner
const WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
const FILE_RE = /^\/(?:[A-Za-z0-9_-][A-Za-z0-9._-]{0,127}\/){0,8}[A-Za-z0-9_-][A-Za-z0-9._-]{0,127}$/;
const HOST_RE = /^(?:[A-Za-z0-9-]{1,63}\.)*[A-Za-z0-9-]{1,63}(?::[0-9]{1,5})?$|^\[[0-9A-Fa-f:.]{2,45}\](?::[0-9]{1,5})?$/;
const TYPES = {
  ".html": "text/html; charset=utf-8", ".js": "text/javascript; charset=utf-8", ".css": "text/css; charset=utf-8",
  ".png": "image/png", ".svg": "image/svg+xml", ".webmanifest": "application/manifest+json", ".json": "application/json",
  ".woff2": "font/woff2", ".txt": "text/plain; charset=utf-8", ".ico": "image/x-icon"
};

/** The function files, run as the hosted platform runs them: a script whose `handler(ctx)` answers. */
function loadFunction(file) {
  return new Function(fs.readFileSync(file, "utf8") + "\n;return handler;")();
}

/** Node's SQLite, without its "experimental" notice on every start. */
function sqlite() {
  const emit = process.emitWarning;
  process.emitWarning = function (w, ...rest) {
    if (/SQLite/.test(String(w && w.message || w))) return;
    return emit.call(process, w, ...rest);
  };
  try {
    return require("node:sqlite").DatabaseSync;
  } finally {
    process.emitWarning = emit;
  }
}

const token = (bytes) => crypto.randomBytes(bytes).toString("base64url");

function config(env) {
  const tor = env.TOR === "1" || env.TOR === "true";
  const publicUrl = (env.PUBLIC_URL || "").replace(/\/+$/, "");
  if (publicUrl && !/^https?:\/\/[^/?#]+$/.test(publicUrl)) throw new Error("PUBLIC_URL must be like https://paigr.example.com");
  return {
    port: Number(env.PORT || 8787),
    host: env.HOST || (tor ? "127.0.0.1" : "0.0.0.0"),
    dataDir: path.resolve(env.DATA_DIR || "paigr-data"),
    tor,
    torBin: env.TOR_BIN || "tor",
    publicUrl,
    trustProxy: env.TRUST_PROXY === "1" || env.TRUST_PROXY === "true",
    tlsCert: env.TLS_CERT || "",
    tlsKey: env.TLS_KEY || "",
    log: env.LOG === "1" || env.LOG === "true",
    watchSeconds: Number(env.WATCH_SECONDS || 300)
  };
}

/**
 * Starts the server. Options are config()'s, plus `extraRoutes(req, res, url, server)` (tests answer their own routes
 * there; true when it answered) and `quiet`. Resolves once it's listening, to { port, origin, store, close() }.
 */
async function start(options) {
  const cfg = Object.assign(config({}), options || {});
  const say = cfg.quiet ? () => {} : (...a) => console.log(...a);
  const dirs = {
    files: path.join(cfg.dataDir, "files"),
    uploads: path.join(cfg.dataDir, "uploads")
  };
  for (const d of [cfg.dataDir, dirs.files, dirs.uploads]) fs.mkdirSync(d, { recursive: true, mode: 0o700 });
  for (const f of fs.readdirSync(dirs.uploads)) fs.rmSync(path.join(dirs.uploads, f), { force: true });   // left by a stop mid-upload

  // ---------------------------------------------------------------- the database: every item the inbox keeps

  const DatabaseSync = sqlite();
  const db = new DatabaseSync(path.join(cfg.dataDir, "paigr.db"));
  db.exec(`
    PRAGMA journal_mode = WAL;
    PRAGMA synchronous = FULL;
    PRAGMA busy_timeout = 5000;
    CREATE TABLE IF NOT EXISTS items (c TEXT NOT NULL, k TEXT NOT NULL, v TEXT NOT NULL, ver INTEGER NOT NULL, exp INTEGER,
      PRIMARY KEY (c, k)) WITHOUT ROWID;
    CREATE TABLE IF NOT EXISTS files (p TEXT PRIMARY KEY, type TEXT NOT NULL, bytes INTEGER NOT NULL, at INTEGER NOT NULL) WITHOUT ROWID;
    CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT NOT NULL) WITHOUT ROWID;`);
  const sql = {
    put: db.prepare("INSERT INTO items (c, k, v, ver, exp) VALUES (?, ?, ?, ?, ?) ON CONFLICT (c, k) DO UPDATE SET v = excluded.v, ver = excluded.ver, exp = excluded.exp"),
    del: db.prepare("DELETE FROM items WHERE c = ? AND k = ?"),
    all: db.prepare("SELECT c, k, v, ver, exp FROM items"),
    filePut: db.prepare("INSERT INTO files (p, type, bytes, at) VALUES (?, ?, ?, ?) ON CONFLICT (p) DO UPDATE SET type = excluded.type, bytes = excluded.bytes, at = excluded.at"),
    fileGet: db.prepare("SELECT p, type, bytes FROM files WHERE p = ?"),
    fileDel: db.prepare("DELETE FROM files WHERE p = ?"),
    metaGet: db.prepare("SELECT v FROM meta WHERE k = ?"),
    metaPut: db.prepare("INSERT INTO meta (k, v) VALUES (?, ?) ON CONFLICT (k) DO UPDATE SET v = excluded.v")
  };
  function inTransaction(fn) {
    db.exec("BEGIN IMMEDIATE");
    try {
      fn();
      db.exec("COMMIT");
    } catch (e) {
      try { db.exec("ROLLBACK"); } catch { /* already rolled back */ }
      throw e;
    }
  }
  const persist = {
    apply(rows) {
      const write = () => {
        for (const r of rows) {
          if (r.gone) sql.del.run(r.c, r.k);
          else sql.put.run(r.c, r.k, JSON.stringify(r.value), r.version, r.expiresAt == null ? null : r.expiresAt);
        }
      };
      if (rows.length > 1) inTransaction(write); else write();
    }
  };
  const store = createStore({ persist, expire: true });
  let loaded = 0;
  const expired = [];
  for (const r of sql.all.all()) {
    if (r.exp != null && r.exp <= Date.now()) { expired.push(r); continue; }
    store.load(r.c, r.k, JSON.parse(r.v), r.ver, r.exp);
    loaded++;
  }
  if (expired.length) inTransaction(() => { for (const r of expired) sql.del.run(r.c, r.k); });
  if (sql.metaGet.get("schema") === undefined) sql.metaPut.run("schema", "1");
  let secret = sql.metaGet.get("file-key");
  if (!secret) {
    secret = { v: crypto.randomBytes(32).toString("hex") };
    sql.metaPut.run("file-key", secret.v);
  }
  const fileKey = Buffer.from(secret.v, "hex");

  // ---------------------------------------------------------------- files: sealed pictures and videos, on disk

  const uploads = new Map();   // ticket -> { prefix, type, max, until, file, bytes }

  function diskPath(p) {
    if (typeof p !== "string" || !FILE_RE.test(p) || p.split("/").some(s => s === "." || s === "..")) throw new Error("bad file path");
    return path.join(dirs.files, p);
  }

  function sign(p, exp) {
    return crypto.createHmac("sha256", fileKey).update(p + "\n" + exp).digest("base64url");
  }

  async function keepFile(p, from, type, bytes) {
    const to = diskPath(p);
    await fsp.mkdir(path.dirname(to), { recursive: true, mode: 0o700 });
    await fsp.rename(from, to);
    sql.filePut.run(p, type || "application/octet-stream", bytes, Date.now());
  }

  function filesFor(origin) {
    return {
      async put(p, base64, o) {
        diskPath(p);
        const buf = Buffer.from(String(base64 || ""), "base64");
        const tmp = path.join(dirs.uploads, "put-" + token(12));
        await fsp.writeFile(tmp, buf, { mode: 0o600 });
        await keepFile(p, tmp, o && o.contentType, buf.length);
        return {};
      },
      async get(p) {
        const row = sql.fileGet.get(p);
        if (!row) return null;
        const buf = await fsp.readFile(diskPath(p));
        return { base64: buf.toString("base64"), byteLength: buf.length, contentType: row.type };
      },
      async delete(p) {
        if (!sql.fileGet.get(p)) return;
        sql.fileDel.run(p);
        await fsp.rm(diskPath(p), { force: true });
      },
      async signedUrl(p, o) {
        if (!sql.fileGet.get(p)) return null;
        const exp = Date.now() + Math.max(60, Math.min(86400, (o && o.ttlSeconds) || 3600)) * 1000;
        return { url: origin + "/_files" + p + "?exp=" + exp + "&sig=" + sign(p, exp) };
      },
      async createUploadTicket(o) {
        if (uploads.size >= LIMITS.tickets) throw new Error("too many uploads waiting");
        const ticket = token(24);
        const type = (o && o.contentType) || "application/octet-stream";
        uploads.set(ticket, {
          prefix: String(o && o.pathPrefix || "/"), type,
          max: Math.min(Number(o && o.maxBytes) || LIMITS.upload, LIMITS.upload),
          until: Date.now() + Math.max(60, Math.min(3600, (o && o.ttlSeconds) || 900)) * 1000,
          file: null, bytes: 0
        });
        return { ticket, uploadUrl: origin + "/_upload/" + ticket, headers: { "content-type": type } };
      },
      async completeUploadTicket(ticket, o) {
        const t = uploads.get(ticket);
        if (!t || t.until < Date.now()) throw new Error("no such upload ticket");
        const p = o && o.path;
        diskPath(p);
        if (!p.startsWith(t.prefix)) throw new Error("path outside the ticket's prefix");
        if (!t.file) throw new Error("nothing uploaded");
        uploads.delete(ticket);
        await keepFile(p, t.file, t.type, t.bytes);
        return { byteLength: t.bytes };
      }
    };
  }

  // ---------------------------------------------------------------- realtime: a socket per device, rung by the inbox

  const tickets = new Map();    // ticket -> { channel, until }
  const channels = new Map();   // channel -> Set of sockets
  let socketCount = 0;

  const realtimeFor = (wsOrigin) => ({
    async issueTicket(channel) {
      if (tickets.size >= LIMITS.tickets) throw new Error("too many tickets waiting");
      const ticket = token(24);
      tickets.set(ticket, { channel: String(channel), until: Date.now() + TICKET_MS });
      return { ticket, webSocketUrl: wsOrigin + "/_realtime" };
    },
    async publish(channel, payload, o) {
      const set = channels.get(String(channel));
      if (!set || !set.size) return { delivered: 0 };
      const frame = wsFrame(0x1, Buffer.from(JSON.stringify({ type: (o && o.type) || "", payloadJson: JSON.stringify(payload === undefined ? {} : payload) })));
      for (const s of set) s.send(frame);
      return { delivered: set.size };
    }
  });

  function wsFrame(op, payload) {
    const n = payload.length;
    const head = n < 126 ? Buffer.from([0x80 | op, n])
      : n < 65536 ? Buffer.from([0x80 | op, 126, n >> 8, n & 255])
        : Buffer.concat([Buffer.from([0x80 | op, 127]), (() => { const b = Buffer.alloc(8); b.writeBigUInt64BE(BigInt(n)); return b; })()]);
    return Buffer.concat([head, payload]);
  }

  /** One frame from the start of `b`: null until all of it has arrived, { error } for a frame no client may send. */
  function readFrame(b) {
    if (b.length < 2) return null;
    const fin = (b[0] & 0x80) !== 0;
    const op = b[0] & 0x0f;
    if (b[0] & 0x70) return { error: 1002 };            // no extensions were agreed on
    if (!(b[1] & 0x80)) return { error: 1002 };         // a client masks every frame
    let len = b[1] & 0x7f;
    let at = 2;
    if (len === 126) {
      if (b.length < 4) return null;
      len = b.readUInt16BE(2);
      at = 4;
    } else if (len === 127) {
      if (b.length < 10) return null;
      const big = b.readBigUInt64BE(2);
      if (big > BigInt(LIMITS.frame)) return { error: 1009 };
      len = Number(big);
      at = 10;
    }
    if (len > LIMITS.frame) return { error: 1009 };
    if (op >= 0x8 && (len > 125 || !fin)) return { error: 1002 };
    if (![0x0, 0x1, 0x2, 0x8, 0x9, 0xa].includes(op)) return { error: 1002 };
    if (b.length < at + 4 + len) return null;
    const mask = b.subarray(at, at + 4);
    const payload = Buffer.from(b.subarray(at + 4, at + 4 + len));
    for (let i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
    return { op, payload, size: at + 4 + len };
  }

  class Socket {
    constructor(socket, channel) {
      this.socket = socket;
      this.channel = channel;
      this.buf = Buffer.alloc(0);
      this.heard = Date.now();
      this.until = Date.now() + SOCKET_LIFE_MS;
      this.open = true;
      socketCount++;
      (channels.get(channel) || channels.set(channel, new Set()).get(channel)).add(this);
      socket.setNoDelay(true);
      socket.on("data", (d) => this.feed(d));
      socket.on("close", () => this.gone());
      socket.on("error", () => this.gone());
    }
    feed(d) {
      this.heard = Date.now();
      this.buf = this.buf.length ? Buffer.concat([this.buf, d]) : d;
      while (this.open) {
        const f = readFrame(this.buf);
        if (!f) break;
        if (f.error) return this.close(f.error);
        this.buf = this.buf.subarray(f.size);
        if (f.op === 0x8) return this.close(f.payload.length >= 2 ? f.payload.readUInt16BE(0) : 1000);
        if (f.op === 0x9) this.send(wsFrame(0xa, f.payload));
        // pongs, and anything else a client sends, only show it's still there
      }
      if (this.buf.length > LIMITS.frame + 14) this.close(1009);
    }
    send(frame) {
      if (!this.open) return;
      if (this.socket.writableLength > 1024 * 1024) return this.gone();   // not reading: let it go
      this.socket.write(frame);
    }
    close(code) {
      if (!this.open) return;
      const body = Buffer.alloc(2);
      body.writeUInt16BE(code >= 1000 && code < 5000 && code !== 1005 && code !== 1006 ? code : 1000);
      try { this.socket.write(wsFrame(0x8, body)); } catch { /* gone already */ }
      this.socket.end();
      setTimeout(() => this.socket.destroy(), 2000).unref();
      this.gone(true);
    }
    gone(keepSocket) {
      if (this.open) {
        this.open = false;
        socketCount--;
        const set = channels.get(this.channel);
        if (set) {
          set.delete(this);
          if (!set.size) channels.delete(this.channel);
        }
      }
      if (!keepSocket) this.socket.destroy();
    }
    tick(now) {
      if (now > this.until) return this.close(1001);
      if (now - this.heard > SILENT_MS) return this.gone();
      this.send(wsFrame(0x9, Buffer.alloc(0)));
    }
  }

  function refuseUpgrade(socket, status, text) {
    socket.end("HTTP/1.1 " + status + " " + text + "\r\nConnection: close\r\nContent-Length: 0\r\n\r\n");
  }

  function upgrade(req, socket, head) {
    socket.on("error", () => socket.destroy());
    const u = new URL(req.url, "http://localhost");
    if (u.pathname !== "/_realtime") return refuseUpgrade(socket, 404, "Not Found");
    const ticket = u.searchParams.get("ticket") || "";
    const t = tickets.get(ticket);
    tickets.delete(ticket);
    if (!t || t.until < Date.now()) return refuseUpgrade(socket, 401, "Unauthorized");
    const key = req.headers["sec-websocket-key"];
    if (String(req.headers.upgrade || "").toLowerCase() !== "websocket" || !key || req.headers["sec-websocket-version"] !== "13") {
      return refuseUpgrade(socket, 400, "Bad Request");
    }
    if (socketCount >= LIMITS.sockets) return refuseUpgrade(socket, 503, "Service Unavailable");
    const accept = crypto.createHash("sha1").update(key + WS_GUID).digest("base64");
    socket.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n");
    const s = new Socket(socket, t.channel);
    if (head && head.length) s.feed(head);
  }

  // ---------------------------------------------------------------- the inbox itself

  const inbox2 = loadFunction(path.join(ROOT, "functions", "inbox2.js"));
  const watch2 = loadFunction(path.join(ROOT, "functions", "watch2.js"));
  const cryptoApi = {
    sha256: async (s) => crypto.createHash("sha256").update(String(s)).digest("hex"),
    timingSafeEqual: async (a, b) => {
      const x = Buffer.from(String(a)), y = Buffer.from(String(b));
      return x.length === y.length && crypto.timingSafeEqual(x, y);
    }
  };

  /** The page's own address: from PUBLIC_URL, a trusted proxy, or how this request reached the server. */
  function originOf(req) {
    if (cfg.publicUrl) return cfg.publicUrl;
    const first = (v) => String(v || "").split(",")[0].trim();
    let proto = req.socket.encrypted ? "https" : "http";
    let host = req.headers.host || "";
    if (cfg.trustProxy) {
      if (/^https?$/.test(first(req.headers["x-forwarded-proto"]))) proto = first(req.headers["x-forwarded-proto"]);
      if (first(req.headers["x-forwarded-host"])) host = first(req.headers["x-forwarded-host"]);
    }
    if (!HOST_RE.test(host)) host = "127.0.0.1:" + cfg.port;
    return proto + "://" + host.toLowerCase();
  }

  function ctxFor(origin, request) {
    return {
      data: store.data,
      files: filesFor(origin),
      realtime: realtimeFor(origin.replace(/^http/, "ws")),
      crypto: cryptoApi,
      principal: null,
      request
    };
  }

  async function callInbox(req, res, u) {
    const raw = await readBody(req, LIMITS.body);
    const ctx = ctxFor(originOf(req), {
      method: req.method, path: u.pathname, query: Object.fromEntries(u.searchParams), headers: req.headers,
      body: raw.length ? raw.toString("utf8") : "",   // as on the hosted platform: a file's bytes also come as (lossy) text
      readBytes: async () => raw.length ? { byteLength: raw.length, base64: raw.toString("base64") } : null
    });
    const r = await inbox2(ctx);
    const headers = Object.assign({ "content-type": "application/json; charset=utf-8", "cache-control": "no-store" }, r.headers || {});
    send(res, r.status || 200, headers, JSON.stringify(r.body === undefined ? null : r.body));
  }

  let watching = false;
  async function watch() {
    if (watching) return;
    watching = true;
    try {
      await watch2({ data: store.data, realtime: realtimeFor(""), crypto: cryptoApi, principal: null, request: null });
    } catch (e) {
      console.error("watch failed:", e && e.message);
    } finally {
      watching = false;
    }
  }

  // ---------------------------------------------------------------- HTTP

  const statics = new Map();
  (function walk(dir, prefix) {
    for (const name of fs.readdirSync(dir)) {
      const file = path.join(dir, name);
      if (fs.statSync(file).isDirectory()) { walk(file, prefix + name + "/"); continue; }
      const body = fs.readFileSync(file);
      statics.set(prefix + name, {
        body, type: TYPES[path.extname(name)] || "application/octet-stream",
        etag: '"' + crypto.createHash("sha256").update(body).digest("base64url").slice(0, 27) + '"'
      });
    }
  })(path.join(ROOT, "web"), "/");

  /** The page's policy here: everything from this server, and nothing from anywhere else. */
  function policy(origin) {
    const host = origin.replace(/^https?:\/\//, "");
    return "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; font-src 'self'; img-src 'self' blob: data:; "
      + "media-src 'self' blob:; connect-src 'self' ws://" + host + " wss://" + host + " blob:; worker-src 'self'; manifest-src 'self'; "
      + "object-src 'none'; base-uri 'none'; form-action 'self'";
  }

  function serveStatic(req, res, p) {
    if (req.method !== "GET" && req.method !== "HEAD") return send(res, 405, { allow: "GET, HEAD" }, "");
    const f = statics.get(p === "/" ? "/index.html" : p);
    if (!f) return send(res, 404, { "content-type": "text/plain; charset=utf-8" }, "Not found");
    const headers = { "content-type": f.type, "cache-control": p === "/sw.js" ? "no-store" : "no-cache", etag: f.etag };
    let body = f.body;
    if (f.type.startsWith("text/html")) {
      const csp = policy(originOf(req));
      body = Buffer.from(body.toString("utf8").replace(/<meta http-equiv="Content-Security-Policy" content="[^"]*">/, '<meta http-equiv="Content-Security-Policy" content="' + csp + '">'));
      Object.assign(headers, { "content-security-policy": csp + "; frame-ancestors 'none'", "x-frame-options": "DENY", "cache-control": "no-store" });
      delete headers.etag;
    } else if (req.headers["if-none-match"] === f.etag) {
      return send(res, 304, headers, "");
    }
    send(res, 200, headers, req.method === "HEAD" ? "" : body);
  }

  function serveFile(req, res, u) {
    if (req.method !== "GET" && req.method !== "HEAD") return send(res, 405, { allow: "GET, HEAD" }, "");
    const p = u.pathname.slice("/_files".length);
    const exp = Number(u.searchParams.get("exp"));
    const sig = Buffer.from(u.searchParams.get("sig") || "");
    const want = Buffer.from(Number.isFinite(exp) ? sign(p, exp) : "");
    if (!(exp > Date.now()) || sig.length !== want.length || !crypto.timingSafeEqual(sig, want)) return send(res, 403, { "content-type": "text/plain" }, "This link has expired.");
    const row = sql.fileGet.get(p);
    if (!row) return send(res, 404, { "content-type": "text/plain" }, "Not found");
    res.writeHead(200, Object.assign(baseHeaders(), {
      "content-type": row.type, "content-length": row.bytes, "cache-control": "private, max-age=3600", "cross-origin-resource-policy": "same-origin"
    }));
    if (req.method === "HEAD") return res.end();
    const stream = fs.createReadStream(diskPath(p));
    stream.on("error", () => res.destroy());
    stream.pipe(res);
  }

  function receiveUpload(req, res, ticket) {
    if (req.method !== "PUT") return send(res, 405, { allow: "PUT" }, "");
    const t = uploads.get(ticket);
    if (!t || t.until < Date.now()) return refuse(req, res, 403, "This upload ticket is used up or expired.");
    if (String(req.headers["content-type"] || "").split(";")[0].trim().toLowerCase() !== t.type.toLowerCase()) return refuse(req, res, 403, "Wrong content type for this upload.");
    if (Number(req.headers["content-length"]) > t.max) return refuse(req, res, 413, "Larger than this upload allows.");
    const part = path.join(dirs.uploads, ticket + "." + token(6) + ".part");
    const out = fs.createWriteStream(part, { mode: 0o600 });
    let n = 0;
    let failed = false;
    const fail = (status, text) => {
      if (failed) return;
      failed = true;
      out.destroy();
      fs.rm(part, { force: true }, () => {});
      refuse(req, res, status, text);
    };
    req.on("data", (d) => {
      n += d.length;
      if (n > t.max) return fail(413, "Larger than this upload allows.");
      if (!out.write(d)) { req.pause(); out.once("drain", () => req.resume()); }
    });
    req.on("aborted", () => fail(400, "The upload stopped part way."));
    req.on("error", () => fail(400, "The upload stopped part way."));
    req.on("end", () => {
      if (failed) return;
      out.end(async () => {
        if (failed) return;
        if (!uploads.has(ticket)) { fs.rm(part, { force: true }, () => {}); return send(res, 403, { "content-type": "text/plain" }, "This upload ticket is used up."); }
        const final = path.join(dirs.uploads, ticket);
        try {
          await fsp.rename(part, final);
        } catch {
          return fail(500, "Couldn't keep the upload.");
        }
        t.file = final;
        t.bytes = n;
        send(res, 200, { "content-type": "text/plain" }, "");
      });
    });
  }

  function baseHeaders() {
    return { "x-content-type-options": "nosniff", "referrer-policy": "no-referrer" };
  }

  function send(res, status, headers, body) {
    if (res.headersSent) return res.end();
    res.writeHead(status, Object.assign(baseHeaders(), headers));
    res.end(body);
  }

  function sendJson(res, status, body) {
    send(res, status, { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" }, JSON.stringify(body));
  }

  /** Answers before reading what the client is still sending, then closes the connection so it stops. */
  function refuse(req, res, status, text) {
    if (res.headersSent) return res.destroy();
    res.writeHead(status, Object.assign(baseHeaders(), { "content-type": "text/plain; charset=utf-8", connection: "close" }));
    res.end(text);
    res.on("finish", () => req.destroy());
  }

  function readBody(req, max) {
    return new Promise((resolve, reject) => {
      if (Number(req.headers["content-length"]) > max) return reject(Object.assign(new Error("too large"), { status: 413 }));
      const chunks = [];
      let n = 0;
      req.on("data", (d) => {
        n += d.length;
        if (n > max) { reject(Object.assign(new Error("too large"), { status: 413 })); req.pause(); return; }
        chunks.push(d);
      });
      req.on("end", () => resolve(Buffer.concat(chunks)));
      req.on("error", reject);
      req.on("aborted", () => reject(Object.assign(new Error("aborted"), { status: 400 })));
    });
  }

  // How many requests each address may make: a burst, refilled steadily. (Through Tor, or a proxy, every phone
  // arrives from the same address, which these limits allow for.)
  const buckets = new Map();
  function allowed(req) {
    const at = req.socket.remoteAddress || "?";
    const now = Date.now();
    const b = buckets.get(at) || { n: LIMITS.burst, t: now };
    b.n = Math.min(LIMITS.burst, b.n + (now - b.t) / 1000 * LIMITS.refill);
    b.t = now;
    buckets.set(at, b);
    if (b.n < 1) return false;
    b.n -= 1;
    return true;
  }

  async function handle(req, res) {
    const began = Date.now();
    let u;
    try {
      u = new URL(req.url, "http://localhost");
    } catch {
      return send(res, 400, { "content-type": "text/plain" }, "Bad request");
    }
    const p = u.pathname;
    if (cfg.log) res.on("finish", () => console.log(req.method, p.startsWith("/_files/") ? "/_files/…" : p.startsWith("/_upload/") ? "/_upload/…" : p, res.statusCode, (Date.now() - began) + "ms"));
    try {
      if (p === "/healthz") return sendJson(res, 200, { ok: true, version: VERSION });
      if (!allowed(req)) return send(res, 429, { "content-type": "application/json", "retry-after": "5" }, JSON.stringify({ error: "Too many requests." }));
      if (cfg.extraRoutes && await cfg.extraRoutes(req, res, u, inside)) return;
      if (p.startsWith("/v2/")) return await callInbox(req, res, u);
      if (p.startsWith("/_upload/")) return receiveUpload(req, res, p.slice("/_upload/".length));
      if (p.startsWith("/_files/")) return serveFile(req, res, u);
      if (p === "/__hedy/push/config") return sendJson(res, 200, { enabled: false, allowVisitorSubscriptions: false });
      if (p.startsWith("/__hedy/") || p.startsWith("/api/") || p === "/pair") return sendJson(res, 404, { error: "Not on this server." });
      return serveStatic(req, res, p);
    } catch (e) {
      if (e && e.status) return refuse(req, res, e.status, e.status === 413 ? "Too large." : "Bad request.");
      console.error("request failed:", e && e.stack || e);
      if (!res.headersSent) sendJson(res, 500, { error: "Server error." });
      else res.destroy();
    }
  }
  // What tests reach through extraRoutes: the store, and the database's file records.
  const inside = { store, db, files: () => db.prepare("SELECT p, type, bytes FROM files").all() };

  const server = cfg.tlsCert && cfg.tlsKey
    ? https.createServer({ cert: fs.readFileSync(cfg.tlsCert), key: fs.readFileSync(cfg.tlsKey) }, handle)
    : http.createServer(handle);
  server.on("upgrade", upgrade);
  server.on("clientError", (e, socket) => { if (socket.writable) socket.end("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n"); else socket.destroy(); });
  server.requestTimeout = 15 * 60000;   // a large video through Tor takes a while
  server.headersTimeout = 60000;
  server.keepAliveTimeout = 65000;
  server.maxConnections = 2000;
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(cfg.port, cfg.host, () => { server.off("error", reject); resolve(); });
  });
  const port = server.address().port;

  // ---------------------------------------------------------------- chores

  const timers = [
    setInterval(watch, Math.max(1, cfg.watchSeconds) * 1000),
    setInterval(() => { const now = Date.now(); for (const set of channels.values()) for (const s of [...set]) s.tick(now); }, PING_MS),
    setInterval(() => {
      const now = Date.now();
      try { store.sweep(); } catch (e) { console.error("sweep failed:", e && e.message); }
      for (const [k, t] of tickets) if (t.until < now) tickets.delete(k);
      for (const [k, t] of uploads) if (t.until < now) { uploads.delete(k); if (t.file) fs.rm(t.file, { force: true }, () => {}); }
      for (const [k, b] of buckets) if (now - b.t > 10 * 60000) buckets.delete(k);
    }, 60000)
  ];

  let tor = null;
  if (cfg.tor) tor = startTor(cfg, port, say);

  let closed = false;
  function close() {
    if (closed) return;
    closed = true;
    for (const t of timers) clearInterval(t);
    for (const set of channels.values()) for (const s of [...set]) s.close(1001);
    server.close();
    server.closeAllConnections();
    if (tor) tor.stop();
    db.close();
  }

  const shown = cfg.publicUrl || ((cfg.tlsCert ? "https" : "http") + "://" + (cfg.host === "0.0.0.0" ? "localhost" : cfg.host) + ":" + port);
  say("Paigr server " + VERSION + " is running.");
  say("  Data:     " + cfg.dataDir + " (" + loaded + " items)");
  say("  Address:  " + shown + (cfg.tor ? " (only on this machine; phones use the onion address)" : ""));
  if (!cfg.tor && !cfg.publicUrl && !cfg.tlsCert && !cfg.trustProxy) {
    say("  Phones need https, or Tor: put this behind a proxy with a certificate, or start it with TOR=1. See README.md.");
  }
  return { port, origin: "http://127.0.0.1:" + port, store, db, close, watch };
}

/** Runs tor alongside, as an onion service that reaches this server. Its keys, and so its address, stay in DATA_DIR. */
function startTor(cfg, port, say) {
  const base = path.join(cfg.dataDir, "tor");
  const service = path.join(base, "onion");
  const state = path.join(base, "state");
  for (const d of [base, service, state]) {
    fs.mkdirSync(d, { recursive: true, mode: 0o700 });
    fs.chmodSync(d, 0o700);   // tor refuses a service directory others can read
  }
  const torrc = path.join(base, "torrc");
  fs.writeFileSync(torrc, [
    "# Written by paigr-server each time it starts.",
    "DataDirectory " + state,
    "HiddenServiceDir " + service,
    "HiddenServicePort 80 127.0.0.1:" + port,
    "SocksPort 0",
    "Log notice stdout"
  ].join("\n") + "\n");
  let stopping = false;
  const child = spawn(cfg.torBin, ["-f", torrc], { stdio: ["ignore", "pipe", "pipe"] });
  const relay = (d) => {
    for (const line of String(d).split("\n")) {
      if (/\[(warn|err)\]/.test(line)) console.error("tor: " + line.replace(/^.*?\[(warn|err)\] /, ""));
      else if (/Bootstrapped 100%/.test(line)) say("  Tor:      connected. The onion address answers within a minute or two.");
    }
  };
  child.stdout.on("data", relay);
  child.stderr.on("data", relay);
  child.on("error", (e) => {
    console.error(e.code === "ENOENT" ? "TOR=1, but tor isn't installed here. Install it, or use the Docker image, which has it." : "tor: " + e.message);
    process.exit(1);
  });
  child.on("exit", (code) => {
    if (stopping) return;
    console.error("tor stopped (" + code + "). Stopping too, so nothing runs without it.");
    process.exit(1);
  });
  const hostname = path.join(service, "hostname");
  const until = Date.now() + 120000;
  const look = setInterval(() => {
    let onion = "";
    try { onion = fs.readFileSync(hostname, "utf8").trim(); } catch { /* not yet */ }
    if (onion) {
      clearInterval(look);
      say("  Onion:    http://" + onion);
      say("  On the phone with your SIM, open Paigr Relay > More > Inbox server and enter that address.");
      say("  It's kept in " + hostname + ". Back up " + service + " to keep this address.");
    } else if (Date.now() > until) {
      clearInterval(look);
      console.error("tor hasn't made an onion address yet. Its messages above say why.");
    }
  }, 500);
  return {
    stop() {
      stopping = true;
      clearInterval(look);
      child.kill("SIGTERM");
    }
  };
}

module.exports = { start, config, VERSION };

if (require.main === module) {
  process.umask(0o077);   // what it writes is for its own user alone
  let cfg;
  try {
    cfg = config(process.env);
  } catch (e) {
    console.error(e.message);
    process.exit(2);
  }
  start(cfg).then((s) => {
    const stop = () => { s.close(); process.exit(0); };
    process.on("SIGTERM", stop);
    process.on("SIGINT", stop);
  }, (e) => {
    console.error("Couldn't start: " + (e && e.message || e));
    process.exit(1);
  });
}
