// Scheduled every 5 minutes: finds v2 relay phones that stopped checking in and tells their inbox once.
// A relay phone's heartbeat (inbox2.js) files `seen`; this drops it when reporting, and the next heartbeat brings it
// back with a "Relay phone is back" notice.
const ST = "v2-state";
const OFFLINE_AFTER_MS = 10 * 60 * 1000;

async function handler(ctx) {
  const now = Date.now();
  const r = await ctx.data.query(ST, "by-seen", { lt: pad13(now - OFFLINE_AFTER_MS), limit: 50 });
  let reported = 0;
  for (const i of r.items) {
    const tid = String(i.key).split("/")[0];
    if (!/^[a-f0-9]{32}$/.test(tid) || !i.key.endsWith("/relay") || !i.value || i.value.offlineSince) continue;
    const rec = Object.assign({}, i.value, { offlineSince: now });
    delete rec.seen;
    try {
      await ctx.data.put(ST, i.key, rec, { ifVersion: i.version });
    } catch (e) {
      if (e && e.name === "ConflictError") continue;   // it checked in just now
      throw e;
    }
    reported++;
    const minutes = Math.round((now - (i.value.lastSeen || now)) / 60000);
    if (ctx.push) {
      try {
        const subs = await ctx.data.get(ST, tid + "/push");
        if (subs && Array.isArray(subs.ids) && subs.ids.length) {
          await ctx.push.sendToVisitorSubscriptions(subs.ids, {
            title: "Relay phone offline",
            body: "No check-in for " + minutes + " minutes. Open Texts and tap Reconnect, or check its Wi-Fi and power.",
            tag: "relay-status", url: "/"
          });
        }
      } catch (e) {
        console.warn("push failed", e && e.message);
      }
    }
    if (ctx.realtime) {
      try {
        await ctx.realtime.publish("tr2/" + tid + "/i", { online: false, silentFor: now - (i.value.lastSeen || now), at: now }, { type: "relay" });
      } catch (e) {
        console.warn("doorbell failed", e && e.message);
      }
    }
  }
  return { status: 200, body: { ok: true, checked: r.items.length, reported } };
}

function pad13(n) {
  return String(Math.max(0, Math.floor(n))).padStart(13, "0");
}
