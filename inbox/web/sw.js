/* Paigr service worker: notifications, and a cached shell so the app opens offline. */
const SHELL = "texts-shell-v12";
const SHELL_FILES = ["/", "/index.html", "/app.js", "/tr2.js", "/styles.css", "/fonts/Figtree.woff2", "/manifest.webmanifest"];

self.addEventListener("install", (event) => {
  event.waitUntil(caches.open(SHELL).then((c) => c.addAll(SHELL_FILES)).catch(() => {}));
  self.skipWaiting();
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys().then((keys) => Promise.all(keys.filter((k) => k !== SHELL).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener("fetch", (event) => {
  const url = new URL(event.request.url);
  if (event.request.method !== "GET" || url.origin !== location.origin) return;
  if (url.pathname.startsWith("/api/") || url.pathname.startsWith("/v2/") || url.pathname.startsWith("/__hedy/") || url.pathname.startsWith("/downloads/")) return;
  // Network first for the shell, falling back to the cache when offline.
  event.respondWith(
    fetch(event.request).then((res) => {
      if (res.ok) {
        const copy = res.clone();
        caches.open(SHELL).then((c) => c.put(event.request, copy)).catch(() => {});
      }
      return res;
    }).catch(() => caches.match(event.request).then((hit) => hit || caches.match("/index.html")))
  );
});

// What a push may say. The words are this page's own: a push the server sends with anything else says only "New text"
// (the server can't read texts, so it never has anything more to say; it could try to say something misleading).
const PUSH_WORDS = {
  "New text": "Open Paigr to read it.",
  "Relay phone offline": "",
  "Relay phone is back": "Texts are flowing again.",
  "Text Relay": "Notifications are working on this device."
};

/** A notification opens the inbox, or one conversation in it: "/" or "/?conv=<id>", never any other address. */
function ownUrl(u) {
  try {
    const url = new URL(String(u || "/"), location.origin);
    const conv = url.searchParams.get("conv");
    if (url.origin !== location.origin) return "/";
    return conv && /^[a-z0-9_-]{4,64}$/.test(conv) ? "/?conv=" + conv : "/";
  } catch { return "/"; }
}

self.addEventListener("push", (event) => {
  let data = {};
  try { data = event.data ? event.data.json() : {}; } catch { data = {}; }
  const known = typeof data.title === "string" && Object.prototype.hasOwnProperty.call(PUSH_WORDS, data.title);
  const title = known ? data.title : "New text";
  const said = typeof data.body === "string" ? data.body : "";
  // The offline notice takes only how long from the server; every sentence is the page's own.
  const minutes = /^No check-in for (\d{1,6}) min/.exec(said);
  const body = !known ? PUSH_WORDS["New text"]
    : title === "Relay phone offline" ? (minutes ? "No check-in for " + minutes[1] + " minutes. " : "") + "Open Paigr and tap Reconnect, or check its Wi-Fi and power."
    : PUSH_WORDS[title];
  const options = {
    body,
    tag: typeof data.tag === "string" && /^[a-z0-9:-]{1,80}$/i.test(data.tag) ? data.tag : "texts",
    renotify: true,
    icon: "/icons/texts-192.png",
    badge: "/icons/texts-192.png",
    data: { url: ownUrl(data.url) }
  };
  // The server's own title for a test push still names the app as it was first called.
  event.waitUntil(self.registration.showNotification(title === "Text Relay" ? "Paigr" : title, options));
});

self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  const url = ownUrl(event.notification.data && event.notification.data.url);
  const conv = new URL(url, location.origin).searchParams.get("conv");
  event.waitUntil(
    self.clients.matchAll({ type: "window", includeUncontrolled: true }).then((list) => {
      for (const client of list) {
        if ("focus" in client) {
          client.focus();
          if (conv) client.postMessage({ conv });
          return;
        }
      }
      return self.clients.openWindow(url);
    })
  );
});
