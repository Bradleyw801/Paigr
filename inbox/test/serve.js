// A local stand-in for the hosted app: serves web/ and runs api.js (v1), pair.js and inbox2.js (v2) on the in-memory
// harness, with storage and upload tickets on this same server (CORS open, like the real storage). For browser tests.
//   node test/serve.js [port]
const http = require("http");
const fs = require("fs");
const path = require("path");
const { base, load, files } = require("./harness");
const root = path.join(__dirname, "..");
const api1 = load(path.join(root, "functions/api.js"));
const pair = load(path.join(root, "functions/pair.js"));
const api2 = load(path.join(root, "functions/inbox2.js"));
const port = Number(process.argv[2] || 8787);
const origin = "http://127.0.0.1:" + port;
const KEYS = { "hak_relay.test": "relay-phone", "hak_inbox.test": "inbox-sidephone" };
const uploads = new Map();   // ticket -> { pathPrefix, contentType, maxBytes, bytes }
let n = 0;
base.files.createUploadTicket = async (o) => {
  const t = "tk" + (++n);
  uploads.set(t, Object.assign({}, o));
  return { ticket: t, uploadUrl: origin + "/_upload/" + t, headers: { "content-type": o.contentType } };
};
base.files.completeUploadTicket = async (t, o) => {
  const u = uploads.get(t);
  if (!u || !u.bytes) throw new Error("nothing uploaded");
  if (!o.path.startsWith(u.pathPrefix)) throw new Error("path outside the ticket");
  files.set(o.path, { b64: u.bytes.toString("base64"), type: u.contentType });
  uploads.delete(t);
  return { byteLength: u.bytes.length };
};
base.files.signedUrl = async (p) => files.has(p) ? { url: origin + "/_files" + p } : null;
base.realtime.issueTicket = async () => ({ ticket: "none", webSocketUrl: "wss://127.0.0.1:9" });
const TYPES = { ".html": "text/html", ".js": "text/javascript", ".css": "text/css", ".png": "image/png", ".webmanifest": "application/manifest+json", ".woff2": "font/woff2", ".txt": "text/plain" };

function body(req) {
  return new Promise(resolve => { const c = []; req.on("data", d => c.push(d)); req.on("end", () => resolve(Buffer.concat(c))); });
}

http.createServer(async (req, res) => {
  const u = new URL(req.url, origin);
  const raw = await body(req);
  const cors = { "access-control-allow-origin": "*", "access-control-allow-methods": "GET, HEAD, PUT", "access-control-allow-headers": "content-type" };
  try {
    if (u.pathname.startsWith("/_files/")) {
      const f = files.get(u.pathname.slice(7));
      if (!f) { res.writeHead(404, cors); return res.end(); }
      res.writeHead(200, Object.assign({ "content-type": f.type || "application/octet-stream" }, cors));
      return res.end(Buffer.from(f.b64, "base64"));
    }
    if (u.pathname === "/_debug/dump") {   // tests read what the server holds
      const { col } = require("./harness");
      const dump = {};
      for (const c of ["v2-messages", "v2-convs", "v2-devices", "v2-offers", "messages", "conversations"]) dump[c] = Object.fromEntries(col(c).entries());
      dump.files = Object.fromEntries([...files.entries()].map(([k, v]) => [k, { type: v.type, bytes: Buffer.from(v.b64, "base64").length }]));
      res.writeHead(200, { "content-type": "application/json" });
      return res.end(JSON.stringify(dump));
    }
    if (u.pathname === "/_debug/put" && req.method === "POST") {   // tests play a dishonest server: they rewrite what it holds
      const { col } = require("./harness");
      const b = JSON.parse(raw.toString("utf8"));
      col(b.collection).set(b.key, b.value);
      res.writeHead(200, { "content-type": "application/json" });
      return res.end("{}");
    }
    if (u.pathname.startsWith("/_upload/")) {
      if (req.method === "OPTIONS") { res.writeHead(200, cors); return res.end(); }
      const t = uploads.get(u.pathname.slice(9));
      if (!t || raw.length > t.maxBytes) { res.writeHead(403, cors); return res.end(); }
      t.bytes = raw;
      res.writeHead(200, cors);
      return res.end();
    }
    let fn = null, principal = null;
    if (u.pathname.startsWith("/v2/")) fn = api2;
    else if (u.pathname === "/pair") fn = pair;
    else if (u.pathname.startsWith("/api/")) {
      principal = KEYS[req.headers["x-hedy-app-key"]];
      if (!principal) { res.writeHead(401, { "content-type": "application/json" }); return res.end(JSON.stringify({ error: { message: "Authentication required." } })); }
      fn = api1;
    }
    if (fn) {
      const query = Object.fromEntries(u.searchParams.entries());
      const ctx = Object.assign({}, base, {
        principal: principal ? { name: principal } : null,
        request: {
          method: req.method, path: u.pathname, query, headers: req.headers,
          body: raw.length && /json/.test(req.headers["content-type"] || "") ? raw.toString("utf8") : "",
          readBytes: async () => ({ byteLength: raw.length, base64: raw.toString("base64") })
        }
      });
      const r = await fn(ctx);
      res.writeHead(r.status, Object.assign({ "content-type": "application/json" }, r.headers || {}));
      return res.end(JSON.stringify(r.body));
    }
    if (u.pathname.startsWith("/__hedy/")) { res.writeHead(404); return res.end("{}"); }
    const file = path.join(root, "web", u.pathname === "/" ? "index.html" : u.pathname);
    if (!file.startsWith(path.join(root, "web")) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) { res.writeHead(404); return res.end("not found"); }
    res.writeHead(200, { "content-type": TYPES[path.extname(file)] || "application/octet-stream", "cache-control": "no-store" });
    res.end(fs.readFileSync(file));
  } catch (e) {
    res.writeHead(500, { "content-type": "application/json" });
    res.end(JSON.stringify({ error: String(e && e.stack || e) }));
  }
}).listen(port, "127.0.0.1", () => console.log("serving on " + origin));
