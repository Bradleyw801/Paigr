# Text Relay v2: private inboxes

Version 2 lets any two phones work together with nothing but a scan. Every pair of phones gets its own inbox on the
shared server (a *tenant*), and everything in it that says who or what is encrypted on the phones before it leaves
them. The server stores and matches scrambled data; it can't read texts, names, numbers or attachments.

The server is one function (`inbox2`) behind `/v2/*`. Relay is the phone with the SIM ("relay" role). Texts, or the
web page in a browser, is an inbox device ("inbox" role).

## Encodings

- `b64u`: base64url, no padding, in its one spelling: a decoder refuses padding and any other bits in the unused part of
  the last character, so the server can't write one sealed value several ways. `hex`: lowercase. Strings are UTF-8.
- `HKDF(ikm, info)`: HKDF-SHA256, empty salt, 32 bytes out.
- `PBKDF2(password, salt)`: PBKDF2-HMAC-SHA256, 150 000 iterations, 32 bytes out.

## Keys

- `K`: the inbox key, 32 random bytes, made by the phone that starts the inbox. It only ever leaves a phone sealed
  (in a link box or a phone wrap).
- `Kenc = HKDF(K, "textrelay v2 enc")`, `Kmac = HKDF(K, "textrelay v2 mac")`.
- `seal(key, bytes, aad)`: AES-256-GCM, 12-byte random IV, AAD = UTF-8 of `aad`; written `"e1." + b64u(iv ‖ ct ‖ tag)`.
  Files use the same cipher with the raw bytes `iv ‖ ct ‖ tag` (no prefix, no base64).
- AADs bind each sealed thing to where it belongs, so the server can't pass it off as something else (see *What each
  seal is bound to*): `tr2:m:<conv>:<in|out>` message payload (its conversation and direction), `tr2:n:<conv>`
  conversation name, `tr2:f:<conv>:<ts13>:<fp>:<o|p>` file, `tr2:o:<conv>` reply request, `tr2:merge:<into>` merge
  record, `tr2:box` link box, `tr2:wrap` phone wrap, `tr2:note` the relay phone's status note, `tr2:dev:<id>` a
  device's name for itself. `<conv>` is the keyed conversation id, `<ts13>` the message time in ms padded to 13
  digits, `<fp>` the keyed fingerprint, `<id>` a device's id (see Devices).
- `mac(s) = HMAC-SHA256(Kmac, s)`.

## What the server sees

| Field | Value |
|---|---|
| conversation id | `hex(mac("conv:" + localConv))[0:32]` |
| message fp | `hex(mac("fp:" + localFp))[0:16]` (Texts' own replies keep a random `o…` id) |
| match token `mt` | `hex(mac("mt:" + dir + ":" + matchText))[0:32]`, omitted when matchText is empty |
| `e` | `seal(Kenc, JSON{text, sender, name, group, ts, fp, media:{mime, w, h, bytes}}, "tr2:m:" + conv + ":" + dir)`: `ts` and `fp` are the message's own time and keyed fingerprint (as filed, see files) |
| `ne` | `seal(Kenc, JSON{name, group}, "tr2:n:" + conv)`, only when the message names its conversation |
| files | `seal(Kenc, bytes, "tr2:f:" + conv + ":" + ts13 + ":" + fp + ":" + (o or p))`, stored as `application/zip` at `/media/v2/<tid>/<conv>/<ts13>-<fp>-<o\|p>.enc` |

Times, directions, read state, delivery status and which messages are copies of each other stay visible: the server
needs them to deduplicate, sync and count unread. What devices show is the sealed version of each: the server's copy
only sorts and counts.

## What each seal is bound to

The server decides which records it hands out under which conversation, so every sealed thing says which conversation
it belongs to, and devices open it only as that conversation's:

- A message's `e` and `ne`, and a conversation's `ne` and `lastE` (copies of its messages'), open only with the AAD of
  the conversation they're shown under, and `e` (and `lastE`, with `lastDir`) only as the direction it's shown as. One
  that doesn't, or a record with nothing sealed, is shown as "couldn't be checked", never with a name or words or an
  attachment; a conversation whose name doesn't open is "Unknown". So the server can't show one person's messages or
  name as another's, a text someone sent as one the user sent (or the reverse), and since a reply is sealed for the
  conversation it's typed in, can't get a reply sent to someone other than who's shown.
- A message shows at the time sealed in it, not the one the server files it under, and each message shows once per
  conversation: devices know a message by the time and keyed fingerprint its payload names, which every copy of it
  shares, sealed again (the relay phone seals each upload afresh, and sends recent texts again whenever asked) or not;
  a record whose payload names none is known by its seal. So the server can't replay an old text as a new one.
- A file opens only with the AAD from its own path; it is a message's attachment only when the path's stem
  (`<ts13>-<fp>`) is the one the message's payload names, its kind is right (`o` original, `p` preview), and it is stored
  in this inbox under the message's conversation (or one merged into it). Of the server's `media`, only `path` and
  `preview` are read: type, size and dimensions come from the payload.
- Nothing in the server's own words is shown as words. A failed reply carries the relay phone's `code` (`stopped`,
  `old`, `future`, `again`, `unknown_conv`, `unchecked`, `no_fallback`, `group`, `no_number`, `sms_failed`, the
  attachment codes `no_attach`, `attach_perm`, `too_big`, `mms_failed`, `attach_fetch`, and the move's
  `before_move`), which each device puts in its own words; the server keeps no free text with it. The relay
  phone's own words about itself, and its model, come only from its sealed note. Its refusals show only when they're
  one of the page's known sentences, and dialogs use only the page's own sentences; web push shows only the page's own
  wording (the server's title picks which, and the offline notice takes only its minutes), and a notification opens
  only the inbox or one of its conversations (`/` or `/?conv=<id>`). A device's name shows only as that device sealed
  it for itself (see Devices); the relay phone's line shows the model from its sealed note. Counts and times from the
  server show only when they're numbers. The page also runs under a content security policy (scripts only from itself), and file addresses
  from the server are used only when they're https (or its own origin).
- **Merges.** When the relay phone finds two conversations to be one, the messages sealed for `from` move to `into`.
  It sends `mr = seal(Kenc, JSON{from, into}, "tr2:merge:" + into)` with `/v2/relay/merge`; the server keeps it in
  `into`'s summary (`merged`, also carried over from `from`'s; up to 200, the oldest dropped first, never the record of
  the merge under way). Devices accept what was sealed for `from` under `into` only with such a record that opens
  (chains work: a record for `from2 → into` vouches for a record `from1 → from2`). The relay phone remembers its own
  merges, so a reply sealed for `from` just before the merge still goes out. The server marks `from` merged before it
  moves anything (90 days): a reply or a text that names `from` after that goes where `from` went (following every
  merge after it), a reply filed under `from` while the merge ran is moved there too, and a merge that finds anything
  left behind says `more` (the relay phone calls again, and runs every merge once more ten minutes later). Devices
  reload for every call that changed something, `more` or not, so a merge whose last call finds nothing left (a reply
  racing it files itself where `from` went) still shows. The marker keeps the merge's record and whether devices were
  told of every change so far (a call clears that before it changes anything): the relay phone seals its record afresh
  on every call, so a later call for the same merge keeps no second copy (and still protects the first from the cap),
  and one that changes nothing makes no device reload, unless a call before it stopped before telling them.

## Replies

A reply from an inbox device goes up as `POST /v2/send {conv, e, o, mt}`:

```
e = seal(Kenc, JSON{text, ts}, "tr2:m:" + conv + ":out")    // how every inbox device shows it
o = seal(Kenc, JSON{text, n, ts}, "tr2:o:" + conv)         // the request the relay phone acts on
```

`n` is 16 random bytes (b64u), `ts` the sending device's clock (ms). The server stores both and hands the relay phone
`{id, conv, o, ts}`; inbox devices never get `o` (nor does the arrival log), and the server drops it once the relay
phone reports a result. The relay phone's result says how it went out: `via` is `rcs` (Messages' own reply action),
`sms` or `mms` (sent from the relay phone itself).

**A reply with a file.** The sending device seals the file as the relay phone seals its uploads (the AAD of the path
it will have, with a fingerprint the device makes up and keys with `fpId`), stores it with `POST /v2/media/put` (or a
ticket), optionally a JPEG preview the same way, and sends `{conv, e, o, mt, media:{path, preview?}}` where

```
e = seal(Kenc, JSON{text, ts, fp, media:{mime, w, h, bytes}}, "tr2:m:" + conv + ":out")
o = seal(Kenc, JSON{text, n, ts, media:{path, mime, bytes, name}}, "tr2:o:" + conv)
```

`text` may be empty when there is a file. Inbox devices find the file by the message's record as for any attachment
(the stem the payload names, under its conversation); the relay phone takes the path from inside `o` only, and only
when it is this inbox's and this conversation's, fetches it (`GET /v2/media?p=`), opens it with the path's AAD, and
sends it on: through Messages' reply action when that takes files, else as MMS from itself, to the number Messages
gave for the conversation or to everyone in the group as its message store lists them, shrunk to the carrier's
limits when it's a picture (`too_big` when it can't be). A request naming a file that isn't this conversation's is
refused (`unchecked`). The server's `media` on the stored message is only ever `{path, preview}` of this inbox and
conversation (`mediaOf`), and `lastMedia` says the conversation's last message had one.

**Reactions and quoted replies** are not protocol: Messages has no door for another app to send either, so they travel
as text in the forms phones already understand, and every device reads them back out of the text to draw them
(web/tr2.js `words`, Words.java in both apps, test/vectors.json for what all three must agree on). A reaction is the
text an iPhone sends for a tapback (`Loved “hi”`, `Liked`, `Disliked`, `Laughed at`, `Emphasized`, `Questioned`;
`Reacted 😮 to “hi”` for any other emoji; `Removed a heart from “hi”` to take one back; `an image` and the other kind
words instead of a quote for a message with no words), which Messages shows as a reaction; Google's own `👍 to “hi”`
form is read too. A quoted reply is `> ` + the first 80 characters of what it answers (or `Picture`, `Attachment`…
for a message with no words), a newline, and the reply. A device attaches each to the newest earlier message whose
words match (spacing and quotes evened out, a cut allowed), or the newest attachment of that kind, and draws a
reaction under that message rather than as a row of its own (unless it is one of its own that failed to send). The relay phone opens `o` only with `"tr2:o:" + conv` for the conversation the item is filed
under (or one it merged into it), so the server can't send an incoming text, or a reply meant for someone else, to
anyone. It then acts on a request once: a nonce it has seen before gets the outcome it had then, reported again, never a
second send. It refuses a request more than 2 days old, more than a day in the future, made before this phone joined
its inbox (2 minutes' grace for the two clocks when it was linked by a code, none when it got back in after a
reinstall), for a conversation it doesn't know, or made no later than the floor (below). A request filed under a
conversation it wasn't sealed for is tried against this phone's other conversations: a real request for one of them
is refused there too. A refused request is spent like any other (its refusal is its outcome), so it can't go out
later, when a clock catches up, the conversation turns up or the server files it where it belongs. One that opens for
none of this phone's conversations (sealed for one it doesn't know yet) is reported failed then; when it opens later,
filed under its own conversation once that turns up, it is refused: the relay phone remembers such requests by their
GCM tag (4 days, at most 2 000), and once that memory has had to forget one early (only a flood of made-up ones does
that) it also refuses, for 4 days, any request made more than 2 minutes before it first knew the request's
conversation. (A server can still hold a reply back, show it as failed and hand it over later within its 2 days; the
relay phone's reports aren't sealed.) A request is
remembered until it could never pass again (made and spent more than 3 days ago, this phone's clock), at most 20 000
(the earliest made forgotten first); forgetting one raises the floor to the time it said it was made (the sending
device's clock), so a forgotten request never passes again however this phone's clock jumps, and a request dated far
ahead is simply remembered until then. A new inbox starts with nothing remembered (another inbox's requests can't open
with its key); what an older build remembered without noting its inbox stays, as the stored link's inbox's. A reply in a conversation this phone folded into another (its own merge, before the inbox has done it)
goes out through the one it went into. A request is recorded as handled ("stopped while sending") before it is sent,
so a relay phone that stops mid-send reports it failed rather than sending it twice. On the server, a reply's result and a copy folding into it
never undo each other: copies are written over the version they read, and on a conflict only their additions are
applied again.

Which conversation a message-store thread belongs to is decided on the relay phone, from the texts its own
notifications showed (direction, time and a local hash of the words). The server is never asked: its answer would
decide which number a reply falls back to as SMS.

## The relay phone's status

`POST /v2/relay/heartbeat` carries numbers and switches in the open (battery, listening, queue length, version) and
the phone's own words sealed: `sn = seal(Kenc, JSON{note, history, model}, "tr2:note")` (its last problem, why the
history copy paused, and its model). The phone's words are its own: a refusal from the server is described by its
status code alone (what the server wrote with it is dropped), and a crash or a failed merge by its kind, so nothing the
server wrote is sealed into the note, or shown on the relay phone's own screens. Inbox devices open `sn` from `relay` in `/v2/inbox`, `/v2/sync` and
`/v2/status`, and show none of those from anywhere else.

`matchText` is v1's rule, run on the phone: NFKC, drop U+200B–U+200D and U+FEFF, collapse JavaScript `\s` runs to one
space, trim; a bare media word ("Photo", "sent a picture", "📷 Video"…) or an empty text with an attachment becomes
`"\u0000"` + Picture / Video / Voice message / Attachment.

## Devices

Each device makes its own token: 32 random bytes, b64u (43 characters), sent as `x-relay-token`. The server keeps
only `sha256(token)`. An unknown or removed token gets `403 {unlinked: true}`. A tenant id `tid` is 16 random bytes,
hex, made by the phone that starts the inbox.

A device's id in the device list is the first 12 hex digits of `sha256(token)`. Devices send no name when they link;
once in an inbox, a device seals its own: `POST /v2/devices/name {sn}`, `sn = seal(Kenc, JSON{name}, "tr2:dev:" + id)`
(the page does, once per inbox and name; the relay phone is listed by its role and the model in its sealed note).
`GET /v2/devices` lists `{id, role, at, you, sn}`, and a device shows a name only when `sn` opens for that device's
id, so a name in the list is always one a device of the inbox gave itself.

## Linking

A code is 12 symbols from `0123456789ABCDEFGHJKMNPQRSTVWXYZ` (60 bits), shown as `XXXX-XXXX-XXXX` and in a QR code
as `TEXTRELAY2:<server host>/<CODE>`. The QR code isn't a web address on purpose: a camera app that isn't Texts shows
it as text, rather than opening a page from the server that could read the code (and, for an invite, the inbox key in
its box). Texts' scanner reads it; a browser types the code (a link `https://<server>/#link=<CODE>` fills it in, and
waits for a tap). Typing is forgiving: case, spaces and dashes don't matter; O, I and L read as 0, 1 and 1 (`norm`).

```
seed    = PBKDF2(norm(code), "textrelay v2 link")
lookup  = hex(HKDF(seed, "textrelay v2 lookup"))     // what the server files the offer under
boxKey  = HKDF(seed, "textrelay v2 box")             // never leaves the two phones
box     = seal(boxKey, JSON{v:2, tid, key:b64u(K)}, "tr2:box")
```

An offer lives 15 minutes and works once. Two kinds:

- **ask**: a device that isn't linked (a new Relay, a browser) shows its code: `POST /v2/link/ask {lookup, token,
  role}` and then `POST /v2/link/wait {lookup, token}` every few seconds until it answers 200 `{tid, role, answer}`.
  `answer` is a box; opened with `boxKey` it holds the inbox key.
- **invite**: a linked device shows a code so another device can join its inbox: `POST /v2/link/invite {lookup, box,
  role}` (with its token). `POST /v2/link/status {lookup}` says when it was used.

The scanning device calls `POST /v2/link/claim {lookup, token?}`:

- an invite answers `{kind:"invite", tid, role, box}` and registers `token` (the scanner's new token) in that inbox;
  only while the device that made the invite is still in it;
- an ask answers `{kind:"ask", role, hasRelay}` and changes nothing. The scanner then `POST /v2/link/adopt {lookup,
  answer}`: with its own token header it brings the asking device into its inbox; with `create:{tid, token}` instead
  (an unlinked Texts scanning an unlinked Relay) it starts a new inbox with both phones in it. Adopting a relay phone
  removes any other relay phone from that inbox, once the new one is in.

Everything that could refuse (a device already in another inbox, an inbox at its 8 devices) is checked before an
offer is used up, so a refused code still works once the problem is fixed.

A phone that isn't in an inbox yet and scans a code for a server other than its own asks first ("Use this inbox
server?", naming it), then links there: that's how a phone joins an inbox on a server you run yourself. A phone in an
inbox turns down a code for another server.

## Servers you run yourself, and Tor

The same inbox code runs on the hosted platform or on your own machine (`server/paigr-server.js`, see README.md), and
everything in this document holds on both. The self-hosted server keeps the records in SQLite and the sealed files on
its disk, signs file links itself (HMAC-SHA256, valid an hour), runs its own realtime socket at `/_realtime` (frames
`{type, payloadJson}`, single-use tickets valid two minutes, a socket lasts two hours at most), runs the watch every five
minutes, and sends no web push. It can run as a Tor onion service.

A server whose host ends in `.onion` is reached over http through Tor, which encrypts the connection end to end and
proves the address: `TEXTRELAY2:<56 letters and digits>.onion/<CODE>` means `http://<…>.onion`. Both apps then connect
through Tor only, and keep "Connect through Tor" on while that's their server; for any other server it's a choice.
Through Tor, an app hands Tor every name to look up (SOCKS5 with the host name, never an address the phone looked up),
logs in to Tor's SOCKS port under a name of its own so Tor keeps its circuits apart from other apps', and fails closed:
when Tor isn't running, nothing goes out.

## Removing a device

`POST /v2/devices/remove {id}` (an inbox device) or `POST /v2/leave` (the device itself) deletes the device's record,
its wrap, the invite it was showing and the push subscriptions it registered. A device's own record is only ever
rewritten with a version check, so a call already under way when the device is removed can't bring it back. Its
token is refused from then on, and a push registration on its way when it was removed is undone.

Limits: the inbox key isn't changed. A removed device keeps what it saved, and it still holds the key: if the server's
operator worked with whoever has it, they could read new texts and queue replies the relay phone would send. To cut a
device off completely, start a new inbox: in Texts, Settings, remove the relay phone (it goes back to showing a code),
then *Unlink this phone*, and scan the relay phone's new code. The new inbox starts empty. A realtime socket a removed device already had open keeps getting content-free nudges until the platform closes
it (at most two hours); reopening it needs a ticket it can no longer get.

## Getting back in after a reinstall

```
pseed   = PBKDF2(androidId, "textrelay v2 phone " + role)
tag     = hex(HKDF(pseed, "textrelay v2 tag"))
wrapKey = HKDF(pseed, "textrelay v2 wrap")
wrap    = seal(wrapKey, JSON{v:2, tid, token, key:b64u(K)}, "tr2:wrap")
```

A linked app files `POST /v2/wrap {tag, wrap}`. Reinstalled on the same phone (Android keeps the app's ID), it asks
`POST /v2/link/recover {tag}` and opens the wrap itself. Unlinking or removing a device deletes its wrap.

Only an install that has never been in an inbox asks (a flag set at the first link survives unlinking): one that left
an inbox, or was told it isn't in one, waits for a code, so a server can't put a phone back into an inbox it left. (A
reinstall clears the flag with the rest of the app's data, so it trusts the wrap the server returns: after starting a
new inbox to cut a lost phone off, a reinstall on this phone could be handed the old inbox's wrap by a server that kept
it. The other devices then see the relay phone go quiet.)
Neither app's data goes to a cloud backup or along to a new phone (`dataExtractionRules`, `allowBackup=false`). A link
is stored with a hash of the Android ID the app had when it was stored; read on a phone where that's different (its
data copied from another phone), it is dropped before anything uses it, and so is a first-version key. A link stored by a build that kept neither the
flag nor the hash counts as linked, on this phone, from when the new version first starts.

## Endpoints

Public (low rate limit): `/v2/link/ask`, `/wait`, `/claim`, `/adopt`, `/recover`.
With a token: `GET /v2/ping`, `GET /v2/ticket` (a realtime ticket for `tr2/<tid>/i` or `tr2/<tid>/o`),
`POST /v2/link/invite`, `/v2/link/status`, `POST /v2/wrap`, `POST /v2/leave`, `GET /v2/devices`,
`POST /v2/devices/name`, `POST /v2/devices/remove` (inbox devices), `GET /v2/media?p=`,
`POST /v2/media/put|ticket|complete`.

Relay: `POST /v2/relay/ingest`, `GET /v2/relay/outbox`, `POST /v2/relay/outbox/result`, `POST /v2/relay/heartbeat`,
`POST /v2/relay/merge`.

Inbox devices: `GET /v2/inbox`, `GET /v2/thread`, `GET /v2/sync`, `POST /v2/send {conv, e, o, mt, media?}`, `POST /v2/read`,
`GET /v2/status`, `POST /v2/push/register`, `POST /v2/push/test`, `POST /v2/relay/wake`,
`POST /v2/import` and `/v2/import/done` (moving a v1 inbox in).

Moving a v1 inbox in: the inbox device waits (up to a minute) for the relay phone's first heartbeat in the new inbox,
so every later text goes there, then seals and imports each conversation, calls `/v2/import/done`, and asks the
relay phone to `resync` (send its last week again; copies fold by key). The v1 records stay on the server, readable
by it, until they are deleted there; the move doesn't delete them. It moves by itself only into an inbox the device
started (scanning the relay phone's own code); joining an inbox by an invite asks first, since everyone in it could
read what came along. It stays with the inbox it started in (if the device joins another one meanwhile, it stops), and
stops retrying after five failures in a row until the page opens again. What it seals is marked `moved: true` in each
payload and name: it rests on what the first version's server held, which that server could read and change, and the
page says "moved from your first inbox" on such a message and prefers a name the relay phone sealed over a moved one.
A reply still queued in the first version comes over failed (`before_move`), never sent by surprise.

Records keep v1's shapes with the readable fields replaced: messages carry `e`, `ne`, `mt` and `media:{path,
preview}` instead of `text`, `sender`, `name`, `group` and the media details; conversations carry `ne`, `lastE`,
`lastDir`, `lastMedia` instead of `name`, `group`, `lastText` and `lastSender`. Web push says only that a text came in.
`v2-state` also keeps `merged:<conv>` (where a merged conversation went and whether the merge's record was kept,
90 days).

## Server data (all keyed `<tid>/…` except devices, offers and wraps)

`v2-tenants`, `v2-devices` (by sha256 of the token; index by-tenant), `v2-offers` (by lookup, expires),
`v2-wraps` (by tag), `v2-messages` (indexes by-conv `cv`, by-status `qs`), `v2-convs`, `v2-feed` (3 days),
`v2-twins`, `v2-state` (relay heartbeat with index by-seen, viewer, epoch, wake, push subscriptions with the device
that registered each, live invite, merged conversations).

## The Texts app

Texts shows the page from files built into the app (`/`, `/index.html` and their scripts), never from the server.
Only that page opens inside the app: any other address, on the inbox server or elsewhere, opens in the phone's
browser, and anything else that tries to load in the app shows an empty page. The bridge that hands the page this
phone's link (and so the inbox key) answers only after the app has just served its own page for the main frame, and
stops answering when anything else starts loading. The WebView keeps its storage in a profile of its own
(`setDataDirectorySuffix("own")`), so nothing an older Texts left (it loaded the page from the server), a service
worker above all, can serve the page or stand in for it; the old profile's folder is deleted. A bundled file that
can't be read is an error, never the server's copy.

Texts' own notifications show a message only as it opens on the phone (conversation, direction, its own time). One
that doesn't open, or carries nothing sealed, goes in a notification of its own ("Texts: a message came in that
couldn't be checked"), never in a conversation's and never with a name. Each message is announced once, whatever key
the server files it under and however often it was sealed: Texts remembers the last 600 keys (only keys of the inbox's
shape, `m:<time>:<id>` in digits and lowercase letters) and the last 600 messages it announced, by the time and keyed
fingerprint their payloads name. It announces nothing sealed at or before its floor: 10 minutes before it linked, raised
to the time of each message it forgets (the earliest sealed go first, and never past its own clock). So a server can't
push a text out of that memory and announce it again, and one text dated far ahead can't silence the rest. (An SMS can
reach the inbox twice, from Messages' notification and from the message store, with different fingerprints; the server
folds the two, and one that didn't could have both announced.)
Nothing the server writes beside the sealed payload is shown.
