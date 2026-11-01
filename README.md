# Paigr

Paigr is in beta, so expect some rough edges.

Paigr lets you read and answer your texts on a different phone from the one with your SIM. I built it so I could
carry a phone without Google services and still get every text, RCS included, from my real number.

It's two apps and a small server. Paigr Relay runs on the phone with your SIM and Google Messages. It picks up every
text that comes in (SMS, MMS and RCS, groups and pictures too), encrypts it and sends it to your inbox on the server.
Paigr runs on the phone you carry, and the same inbox also works in a browser. Your replies go back to the relay
phone and out through Messages, or as SMS or MMS from the phone itself; a new conversation with any number starts
the same way, as SMS from the relay phone.

Everything is encrypted on the phones before it leaves them, so the server only holds what it can't read. You can
use my server or run your own.

Setting up two phones takes one scan. Paigr Relay shows a code, you scan it with Paigr, and the two phones get their
own private inbox. There's no account, email or password.

## Install the apps

The signed builds are in [Brad White's apps](https://bradleyw801.github.io/apps/). Add that repository to
F-Droid or any F-Droid client to get updates, or download `Paigr-Relay.apk` and `Paigr.apk` straight from that page. If you
build the apps yourself, your builds are signed with your own key, so they can't update the published ones. Uninstall
those first.

## Run your own inbox server

```sh
docker run -d --name paigr --restart unless-stopped -e TOR=1 -v paigr-data:/data ghcr.io/bradleyw801/paigr-server
docker logs paigr        # shows the onion address to enter in Paigr Relay (More > Inbox server)
```

That runs it as a Tor onion service. You don't need a domain, open ports or a certificate, and you can reach it from
anywhere through Tor. Install Orbot on both phones and **Connect through Tor** turns on by itself.
[inbox/README.md](inbox/README.md) covers the https setup, the settings, what the server keeps and how to move phones
that already have an inbox. The phones work the same with any server.

My hosted inbox at `relay--bradwhite.apps.hedyassist.com` runs the same code (`inbox/functions/`) on the Hedy
platform. The apps use it until you enter another server.

## What the server sees

Every message, name, attachment and device name is encrypted on the phones with AES-256-GCM, using a key only the
phones have. Each one is also tied to its conversation, direction and message, so the server can't move it, replay
it or pass it off as someone else's. The server sees times, directions, sizes and keyed hashes, and that's all. With
Tor on, it doesn't even see the phones' IP addresses. [inbox/PROTOCOL.md](inbox/PROTOCOL.md) has the full details,
from the keys to the checks every device makes.

Reactions and quoted replies are sent as the plain texts phones already understand (iPhone-style tapback texts and
`> ` quotes), because Google Messages doesn't let other apps send real ones. Attachments sent from Paigr go through
Messages when it accepts files, and otherwise out of the relay phone as MMS.

## Build

| Part | Where | How |
| --- | --- | --- |
| Inbox server and web page | `inbox/` | `node server/paigr-server.js` (Node 22.5+). Tests: `test/run-all.sh` (the `e2e-*` suites need Playwright with Chromium). |
| Paigr Relay | `paigr-relay-android/` | `./gradlew assembleRelease testReleaseUnitTest` (JDK 17+, Android SDK 35). Without a `keystore.properties` the build is unsigned. See `app/build.gradle`. |
| Paigr | `paigr-android/` | The same. Its tests run against the real inbox server in `inbox/`, so you need Node installed too. |

There are three implementations of the protocol, the web page's `tr2.js` and the two apps' `Crypto` and `Words`, and
all three are checked against the vectors in `inbox/test/vectors.json`. `node inbox/test/make-vectors.js` makes new
ones.

## Licence

Apache License 2.0 (see [LICENSE](LICENSE) and [NOTICE](NOTICE)). The Figtree font is under the SIL Open Font License.
