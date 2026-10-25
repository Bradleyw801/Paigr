# Paigr

Keep your number, carry any phone.

Paigr turns one Android phone with your SIM into a relay, and lets you read and answer every text it gets (SMS, MMS
and RCS, groups and pictures included) on any other phone, or in a browser. The other phone can be anything, including
one with no Google services at all. Replies go out from your real number. Texts, names and pictures are encrypted on
the phones before they leave them, so the server in between only holds what it can't read, and you can run that server
yourself.

- **Paigr Relay** goes on the phone that keeps the SIM and Google Messages. It reads Messages' notifications (and, with
  message access, the phone's message store), seals everything and sends it to the inbox; replies come back down and
  go out through Messages, or as SMS/MMS from the phone itself.
- **Paigr** goes on the phone you carry: a native app that opens at once, works by touch or with a keypad, and needs no
  Google services. Reactions, replies that quote, an emoji keyboard, and pictures, GIFs and files from the phone.
- **The inbox** is a small server (Node, no packages) plus a web page that is the same inbox in a browser.

Setting up two phones takes one scan: Paigr Relay shows a code, Paigr scans it, and the two phones get their own
private inbox. No account, email or password.

## Install the apps

The signed builds are on the [app pack](https://bradleyw801.github.io/sidephone-apps/): add it to F-Droid, Droid-ify
or Sidephone's App Library for updates, or download `relay.apk` and `texts.apk` from that page directly. Builds from
this repository install over them only if signed with the same key, which isn't published; a build of your own is a
separate install.

## Run your own inbox server

```sh
docker run -d --name paigr --restart unless-stopped -e TOR=1 -v paigr-data:/data ghcr.io/bradleyw801/paigr-server
docker logs paigr        # shows the onion address to enter in Paigr Relay (More > Inbox server)
```

That runs it as a Tor onion service: no domain, no open ports, no certificate, reachable from anywhere through Tor
(install Orbot on both phones; **Connect through Tor** turns on by itself). [inbox/README.md](inbox/README.md) has
the https setup, the settings, what it keeps and how to move phones that already have an inbox. Nothing on the server
side is tied to the hosted inbox: the phones work the same against any server.

The hosted inbox at `relay--bradwhite.apps.hedyassist.com` runs the same code (`inbox/functions/`) on the Hedy
platform; the apps point there until you enter another server.

## What the server sees

Every message, name, attachment and device name is sealed on the phones with AES-256-GCM under a key only the phones
have, and each seal is bound to its conversation, direction and message, so the server can't move, replay or
reattribute anything. The server sees times, directions, sizes and keyed hashes, and nothing else; with Tor on, not
even the phones' addresses. [inbox/PROTOCOL.md](inbox/PROTOCOL.md) is the full spec: keys, what each seal is bound
to, replies, linking, attachments, and the checks every device makes.

Reactions and quoted replies travel as the texts phones already understand (an iPhone's tapback texts, `> ` quotes),
since Google Messages gives no other app a way to send them; attachments sent from Paigr go through Messages' reply
when it takes files and otherwise out of the relay phone as MMS.

## Build

| Part | Where | How |
| --- | --- | --- |
| Inbox server and web page | `inbox/` | `node server/paigr-server.js` (Node 22.5+). Tests: `test/run-all.sh` (the `e2e-*` suites need Playwright with Chromium). |
| Paigr Relay | `paigr-relay-android/` | `./gradlew assembleRelease testReleaseUnitTest` (JDK 17+, Android SDK 35). Unsigned without a `keystore.properties`; see `app/build.gradle`. |
| Paigr | `paigr-android/` | The same. Its app tests run against the real inbox server in `inbox/`, so Node must be installed. |

The vectors in `inbox/test/vectors.json` are what all three implementations of the protocol (the page's `tr2.js`, and
the two apps' `Crypto`/`Words`) are checked against; `node inbox/test/make-vectors.js` regenerates them.

## Licence

Apache License 2.0 (see [LICENSE](LICENSE) and [NOTICE](NOTICE)). The Figtree font is under the SIL Open Font License.
