# Paigr's inbox server

Paigr Relay runs on the phone with your SIM card and passes your texts on. Paigr is where you read and answer them,
on the phone you carry. An inbox server sits between the two phones. This folder is that server: the same code the
hosted inbox runs, ready to run on your own machine, plus the web page it serves (`web/`) and the inbox code itself
(`functions/inbox2.js`, `functions/watch2.js`).

The phones seal every message, name and picture before it leaves them, so any inbox server, hosted or yours, only
holds what it can't read ([PROTOCOL.md](PROTOCOL.md) says exactly what it sees). Running your own adds the rest: it's
your disk and your network, and you don't depend on anyone else's server.

## Run it as an onion service (no domain, no open ports)

You need Docker on a machine that stays on: a home server, a Raspberry Pi, a VPS. Either the ready-made image:

```sh
docker run -d --name paigr --restart unless-stopped -e TOR=1 -v paigr-data:/data ghcr.io/bradleyw801/paigr-server
docker logs paigr
```

or, from this folder, a build of your own:

```sh
docker compose up -d
docker compose logs paigr
```

The log shows the server's onion address, such as `http://abcd…wxyz.onion`. Tor reaches it from anywhere, so it can
sit behind your home router, with no port forwarding and no certificate.

Then, on the phones:

1. Install [Orbot](https://orbot.app) on both phones and start it.
2. On the phone with your SIM, open Paigr Relay, go to **More > Inbox server**, and enter the onion address.
   **Connect through Tor** turns on by itself.
3. On the phone you carry, open Paigr and scan the code Paigr Relay shows. Paigr asks before it uses a server
   other than its usual one: tap **Use it**.

Everything between the phones now goes through Tor. Your server never sees either phone's IP address, and neither
phone ever looks up its address outside Tor. If Orbot stops, the apps stop too: texts wait on the relay phone, and
nothing goes out any other way.

### Moving phones that already have an inbox

An inbox stays on the server it started on, so the phones start a new one on yours:

1. In Paigr, open **Settings**, remove the relay phone, then tap **Unlink this phone**.
2. In Paigr Relay, enter your server under **More > Inbox server**.
3. Scan Paigr Relay's code with Paigr, as above. The new inbox starts empty. In Paigr Relay, **More > Sync full
   history** brings your texts back in.

## Run it at an https address instead

If you have a domain that points at the machine, put a web server that gets certificates in front of it. In
`docker-compose.yml`, uncomment the `caddy` service, put in your domain, and add `HOST: "0.0.0.0"` and
`TRUST_PROXY: "1"` to the `paigr` service. Then enter `https://your.domain` in Paigr Relay. Orbot is optional
here: **Connect through Tor** works with any server, if you want it.

## Without Docker

You need Node 22.5 or later (and `tor`, for the onion service). There's nothing to install: the server uses only
Node's own modules.

```sh
TOR=1 node server/paigr-server.js
```

## Settings

All of them are optional environment variables.

| Setting | What it does |
| --- | --- |
| `TOR=1` | Runs as an onion service and prints the address. The server then listens on 127.0.0.1 only. |
| `DATA_DIR` | Where everything is kept. `/data` in the Docker image, `./paigr-data` otherwise. |
| `PORT` | The port it listens on, 8787 unless you set another. |
| `HOST` | The address it listens on. 0.0.0.0 by default; 127.0.0.1 with `TOR=1`. |
| `PUBLIC_URL` | Its https address, when a proxy in front serves it under one, for example `https://paigr.example.com`. |
| `TRUST_PROXY=1` | Takes the address from the proxy's `X-Forwarded-Proto` and `X-Forwarded-Host` instead. |
| `TLS_CERT`, `TLS_KEY` | A certificate and key, to serve https itself. |
| `LOG=1` | Logs each request: method, route, status and time. It never logs IP addresses, tokens or contents. |

## What it keeps

Everything is in the data folder (the `paigr-data` volume with Docker):

- `paigr.db`: the inbox records (SQLite), every one sealed by the phones.
- `files/`: pictures and videos, sealed the same way.
- `tor/onion/`: the keys behind the onion address. Lose them and the address changes, and both phones have to be
  linked again. Back them up.

To back up, stop the server, copy the folder, and start it again. To update, `docker pull ghcr.io/bradleyw801/paigr-server`
and start it again (or `docker compose up -d --build` for a build of your own).

## What it doesn't do

- **Browser notifications.** Web push goes through Google's or Mozilla's servers, so this server doesn't send any. The
  Paigr app doesn't need them: it gets its own through the server's realtime socket.
- **The web page outside Tor Browser, on an onion address.** Other browsers don't treat an http onion page as secure,
  so the page's encryption can't run there. The apps aren't affected.

## Checking it

```sh
node server/test/store.test.js       # the store: saving, expiring, loading
node server/test/selfhost.test.js    # the server, run as people run it: linking, texts, pictures, the socket, a restart
node server/test/e2e-selfhost.js     # the page in a browser against it (needs Playwright)
```

The inbox code in `functions/` has its own tests in `test/`; `test/run-all.sh` runs every suite in this folder (Node 22,
and Playwright for the `e2e-*` ones, which drive the page in Chromium).

## The first version

`functions/api.js`, `pair.js` and `relay-watch.js` are the first version's inbox, which the server could read. They're
kept so a phone still on it can move into a private inbox (the page does the move); the self-hosted server doesn't
run them, and new inboxes never use them.
