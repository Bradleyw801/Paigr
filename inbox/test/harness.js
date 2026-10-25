// In-memory stand-in for the Hedy runtime's ctx, enough to exercise api.js, pair.js and inbox2.js locally.
const fs = require("fs");
const crypto = require("crypto");
const load = (file) => new Function(fs.readFileSync(file, "utf8") + "\n;return handler;")();
const { createStore } = require("../server/store");
// The same store the self-hosted server runs, kept in memory and never expiring anything (the tests' clocks don't move).
const { data, col, hooks } = createStore();
const rings = [];
const pushes = [];
const files = new Map();
const tickets = [];
const issued = [];
const base = {
  data,
  realtime: {
    publish: async (ch, p, o) => rings.push([ch, o.type, p]),
    issueTicket: async (ch) => { issued.push(ch); return { ticket: "rt-" + issued.length, webSocketUrl: "wss://realtime.example/prod" }; }
  },
  push: { sendToVisitorSubscriptions: async (ids, msg) => { pushes.push(msg); return {}; } },
  files: {
    createUploadTicket: async (o) => {
      tickets.push(o);
      return { ticket: "ticket-" + o.contentType + "-" + tickets.length, uploadUrl: "https://storage.example/upload", headers: { "content-type": o.contentType } };
    },
    completeUploadTicket: async (t, o) => {
      const opened = tickets[Number(String(t).split("-").pop()) - 1];
      if (opened && o && o.path && !o.path.startsWith(opened.pathPrefix)) throw new Error("path outside the ticket's prefix");
      return { byteLength: 1 };
    },
    put: async (p, b64, o) => { if (o && o.contentType === "application/octet-stream") throw new Error("unsupported type"); files.set(p, { b64, type: o && o.contentType }); return {}; },
    get: async (p) => files.has(p) ? { base64: files.get(p).b64, byteLength: Buffer.from(files.get(p).b64, "base64").length } : null,
    delete: async (p) => { files.delete(p); },
    signedUrl: async (p) => ({ url: "https://storage.example" + p })
  },
  crypto: {
    sha256: async (s) => crypto.createHash("sha256").update(s).digest("hex"),
    timingSafeEqual: async (a, b) => a.length === b.length && crypto.timingSafeEqual(Buffer.from(a), Buffer.from(b))
  }
};
function caller(fnFile, patch) {
  const handler = patch ? new Function(patch(fs.readFileSync(fnFile, "utf8")) + "\n;return handler;")() : load(fnFile);
  return async (method, path, body, query, who) => {
    const ctx = Object.assign({}, base, {
      principal: who ? { name: who } : null,
      request: { method, path, query: query || {}, body: body ? JSON.stringify(body) : "" }
    });
    const r = await handler(ctx);
    return Object.assign({ _status: r.status }, r.body);
  };
}
/** For inbox2.js: (method, path, body, query, headers). A Buffer body arrives as bytes (ctx.request.readBytes). */
function caller2(fnFile) {
  const handler = load(fnFile);
  return async (method, path, body, query, headers) => {
    const raw = Buffer.isBuffer(body) ? body : null;
    const ctx = Object.assign({}, base, {
      principal: null,
      request: {
        method, path, query: query || {},
        headers: Object.assign(raw ? {} : body ? { "content-type": "application/json" } : {}, headers || {}),
        // As on the platform: a binary body also arrives as a (lossy) text view.
        body: raw ? raw.toString("utf8") : body ? JSON.stringify(body) : "",
        readBytes: async () => raw ? { byteLength: raw.length, base64: raw.toString("base64") } : null
      }
    });
    const r = await handler(ctx);
    return Object.assign({ _status: r.status }, r.body);
  };
}
module.exports = { caller, caller2, col, rings, pushes, hooks, files, tickets, issued, base, load };
