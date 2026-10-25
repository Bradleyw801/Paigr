// Scheduled every 5 minutes: notices when the relay device stops checking in and tells the inbox devices once.
// The relay's next heartbeat (api.js) clears the flag and sends the "back" message.
const OFFLINE_AFTER_MS = 10 * 60 * 1000;

async function handler(ctx) {
  const now = Date.now();
  const relay = await ctx.data.get("devices", "relay");
  if (!relay || !relay.lastSeen) return { status: 200, body: { ok: true, state: "never-seen" } };
  const silentFor = now - relay.lastSeen;
  if (silentFor < OFFLINE_AFTER_MS) return { status: 200, body: { ok: true, state: "online", silentFor } };
  if (relay.offlineSince) return { status: 200, body: { ok: true, state: "offline-known", since: relay.offlineSince } };

  relay.offlineSince = now;
  await ctx.data.put("devices", "relay", relay);
  const minutes = Math.round(silentFor / 60000);
  if (ctx.push) {
    try {
      const cur = (await ctx.data.get("settings", "pushSubs")) || { ids: [] };
      if (Array.isArray(cur.ids) && cur.ids.length) {
        await ctx.push.sendToVisitorSubscriptions(cur.ids, {
          title: "Relay phone offline",
          body: "No check-in for " + minutes + " minutes. Open Texts and tap Reconnect, or check its Wi-Fi and power.",
          tag: "relay-status",
          url: "/"
        });
      }
    } catch (e) {
      console.warn("push failed", e && e.message);
    }
  }
  if (ctx.realtime) {
    try {
      await ctx.realtime.publish("inbox", { online: false, silentFor, at: now }, { type: "relay" });
    } catch (e) {
      console.warn("doorbell failed", e && e.message);
    }
  }
  return { status: 200, body: { ok: true, state: "offline-reported", silentFor } };
}
