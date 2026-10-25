// The self-hosted server, for the apps' own tests: as test/serve.js stands in for the hosted inbox, this runs the real
// self-hosted one (realtime socket included) on a fresh data folder, with the same two test-only routes:
// /_debug/dump (what it holds) and /_debug/put (a dishonest server rewriting what it holds).
//   node server/test/serve-selfhost.js [port]
const fs = require("fs");
const os = require("os");
const path = require("path");
const { start } = require("../paigr-server");

const port = Number(process.argv[2] || 8788);
const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), "paigr-test-"));
const COLLECTIONS = ["v2-messages", "v2-convs", "v2-devices", "v2-offers", "v2-state", "messages", "conversations"];

function body(req) {
  return new Promise((resolve) => { const c = []; req.on("data", (d) => c.push(d)); req.on("end", () => resolve(Buffer.concat(c))); });
}

async function debugRoutes(req, res, u, inside) {
  if (u.pathname === "/_debug/dump") {
    const dump = {};
    for (const c of COLLECTIONS) dump[c] = Object.fromEntries(inside.store.col(c).entries());
    dump.files = Object.fromEntries(inside.files().map((f) => [f.p, { type: f.type, bytes: f.bytes }]));
    res.writeHead(200, { "content-type": "application/json" });
    res.end(JSON.stringify(dump));
    return true;
  }
  if (u.pathname === "/_debug/put" && req.method === "POST") {
    const b = JSON.parse((await body(req)).toString("utf8"));
    await inside.store.data.put(b.collection, b.key, b.value);
    res.writeHead(200, { "content-type": "application/json" });
    res.end("{}");
    return true;
  }
  return false;
}

start({ port, host: "127.0.0.1", dataDir, extraRoutes: debugRoutes, quiet: true }).then((s) => {
  console.log("self-hosted server on http://127.0.0.1:" + s.port + ", data in " + dataDir);
  const stop = () => { s.close(); fs.rmSync(dataDir, { recursive: true, force: true }); process.exit(0); };
  process.on("SIGTERM", stop);
  process.on("SIGINT", stop);
}, (e) => {
  console.error(e && e.stack || e);
  process.exit(1);
});
