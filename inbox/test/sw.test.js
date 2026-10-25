// web/sw.js only ever opens the inbox (or one of its conversations) from a notification, and shows only its own words.
// (Started as an independent reviewer's check, kept as a regression.)
// Runs the real sw.js in a vm with a stand-in `self`, feeds it a push the server chose, taps the notification with no
// Texts window open, and resolves what it hands to clients.openWindow the way the browser does (against the SW URL).
//   node test/sw.test.js
const fs = require("fs");
const path = require("path");
const vm = require("vm");

const ORIGIN = "https://relay.example";
const handlers = {};
let shown = null, opened = null;
const self = {
  addEventListener: (t, fn) => { handlers[t] = fn; },
  registration: { showNotification: async (title, options) => { shown = { title, options }; } },
  clients: { matchAll: async () => [], openWindow: async (u) => { opened = u; }, claim: async () => {} },
  skipWaiting: () => {}
};
const ctx = vm.createContext({ self, location: new URL(ORIGIN + "/sw.js"), URL, caches: {}, fetch: async () => ({}), console });
vm.runInContext(fs.readFileSync(path.join(__dirname, "../web/sw.js"), "utf8"), ctx);

(async () => {
  let failed = 0;
  for (const url of ["https://relay.example//evil.example/phish", "/.//evil.example/x", "https://evil.example/x", "/?conv=abcd",
    "https://relay.example//relay.example//evil.example/phish", "/.//relay.example//evil.example/phish"]) {
    shown = null; opened = null;
    const waits = [];
    handlers.push({ data: { json: () => ({ title: "New text", body: "x", url, tag: "conv-1" }) }, waitUntil: (p) => waits.push(p) });
    await Promise.all(waits);
    handlers.notificationclick({ notification: { close() {}, data: shown.options.data }, waitUntil: (p) => waits.push(p) });
    await Promise.all(waits);
    const target = new URL(opened, ORIGIN + "/sw.js");
    const own = target.origin === ORIGIN;
    if (!own) failed++;
    console.log((own ? "ok      " : "FAIL    ") + "push url " + JSON.stringify(url) + " -> openWindow(" + JSON.stringify(opened) + ") = " + target.href);
  }
  // the words are the page's own, whatever the server sends
  for (const [data, title, body] of [
    [{ title: "New text", body: "Your key expired, visit relay-help.example" }, "New text", "Open Paigr to read it."],
    [{ title: "Your inbox was hacked", body: "Call 1-800-555-0100" }, "New text", "Open Paigr to read it."],
    [{ title: "Relay phone offline", body: "No check-in for 12 minutes. Visit relay-help.example" }, "Relay phone offline", "No check-in for 12 minutes. Open Paigr and tap Reconnect, or check its Wi-Fi and power."],
    [{ title: "Relay phone offline", body: "relay-help.example" }, "Relay phone offline", "Open Paigr and tap Reconnect, or check its Wi-Fi and power."]
  ]) {
    shown = null;
    const waits = [];
    handlers.push({ data: { json: () => data }, waitUntil: (p) => waits.push(p) });
    await Promise.all(waits);
    const ok = shown.title === title && shown.options.body === body;
    if (!ok) failed++;
    console.log((ok ? "ok      " : "FAIL    ") + "push " + JSON.stringify(data) + " shows " + JSON.stringify([shown.title, shown.options.body]));
  }
  console.log(failed ? failed + " push check(s) failed" : "sw: notifications open only the inbox and say only the page's own words");
  process.exit(failed ? 1 : 0);
})().catch(e => { console.error(e); process.exit(2); });
