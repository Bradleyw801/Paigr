// Writes test/vectors.json: fixed inputs and the outputs PROTOCOL.md says they must give, computed with Node's own
// crypto module (not WebCrypto), so tr2.js, Relay and Texts are each checked against an independent implementation.
const crypto = require("crypto");
const fs = require("fs");
const path = require("path");

const hkdf = (ikm, info) => Buffer.from(crypto.hkdfSync("sha256", ikm, Buffer.alloc(0), Buffer.from(info, "utf8"), 32));
const pbkdf2 = (pw, salt) => crypto.pbkdf2Sync(Buffer.from(pw, "utf8"), Buffer.from(salt, "utf8"), 150000, 32, "sha256");
const hmac = (key, s) => crypto.createHmac("sha256", key).update(Buffer.from(s, "utf8")).digest();
const b64u = (b) => Buffer.from(b).toString("base64").replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
function sealBytes(key, bytes, aad, iv) {
  const c = crypto.createCipheriv("aes-256-gcm", key, iv);
  c.setAAD(Buffer.from(aad, "utf8"));
  return Buffer.concat([iv, c.update(bytes), c.final(), c.getAuthTag()]);
}
const seal = (key, bytes, aad, iv) => "e1." + b64u(sealBytes(key, bytes, aad, iv));

// The same text rule as tr2.js and v1's server, written out again here.
function normText(t) {
  return String(t || "").normalize("NFKC").replace(/[​-‍﻿]/g, "").replace(/\s+/g, " ").trim();
}
const MEDIA_WORD = /^(?:sent an? )?(photo|picture|image|sticker|gif|video|voice message|audio|attachment|file)$/i;
function label(mime) {
  const t = String(mime || "").toLowerCase();
  return !t ? "" : t.startsWith("image/") ? "Picture" : t.startsWith("video/") ? "Video" : t.startsWith("audio/") ? "Voice message" : "Attachment";
}
function matchText(text, mime) {
  const t = normText(text);
  const w = MEDIA_WORD.exec(t.replace(/^[^\p{L}\p{N}]+/u, ""));
  if (w) {
    const x = w[1].toLowerCase();
    return "\u0000" + (x === "video" ? "Video" : x === "voice message" || x === "audio" ? "Voice message" : x === "attachment" || x === "file" ? "Attachment" : "Picture");
  }
  if (!t && mime) return "\u0000" + label(mime);
  return t;
}

const K = Buffer.from("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f", "hex");
const enc = hkdf(K, "textrelay v2 enc");
const mac = hkdf(K, "textrelay v2 mac");
const iv = Buffer.from("a0a1a2a3a4a5a6a7a8a9aaab", "hex");

const texts = [
  ["in", "Checking in  on the checks", ""],
  ["in", " Checking in on the checks\n", ""],
  ["out", "📷 Photo", ""],
  ["in", "Sent a picture", ""],
  ["in", "", "image/jpeg"],
  ["in", "", "video/mp4"],
  ["in", "", "audio/mp4"],
  ["in", "", "application/pdf"],
  ["in", "voice message", ""],
  ["in", "Hello world​ ", ""],
  ["in", "ｆｕｌｌ　ｗｉｄｔｈ", ""],
  ["out", "line one line two　end", ""],
  ["in", "tab\tand\u000bvertical\u000cfeed", ""],
  ["in", "\u0085next line kept", ""],
  ["in", "café", ""],
  ["in", "café", ""],
  ["in", "Ⅻ ﬁ ㎏", ""],
  ["out", "", ""]
];

const code = "K7Q4-M2XD-9PRT";
const normCode = code.toLowerCase().replace(/[^a-z0-9]/g, "").replace(/o/g, "0").replace(/[il]/g, "1");
const seed = pbkdf2(normCode, "textrelay v2 link");
const boxKey = hkdf(seed, "textrelay v2 box");
const androidId = "3f2a9c7b1e4d5a60";
const pseed = pbkdf2(androidId, "textrelay v2 phone relay");
const wrapKey = hkdf(pseed, "textrelay v2 wrap");
const tid = "00112233445566778899aabbccddeeff";
// A message's payload names its own time and keyed fingerprint (the stem of its files' paths): see wireFp below.
const payloadOf = (fp) => JSON.stringify({ text: "Hi 👋 there", sender: "Andy", name: "Andy Graham", group: false, ts: 1700000000123, fp,
  media: { mime: "image/jpeg", w: 640, h: 480, bytes: 51234 } });
const file = Buffer.from(Array.from({ length: 300 }, (_, i) => (i * 37 + 11) & 255));

const wireConv = hmac(mac, "conv:c1aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").toString("hex").slice(0, 32);
const wireFp = hmac(mac, "fp:0123456789abcdef").toString("hex").slice(0, 16);
const payload = payloadOf(wireFp);
const otherConv = hmac(mac, "conv:c2bbbbbbbbbbbbbbbbbbbbbbbbbbbbbb").toString("hex").slice(0, 32);
const request = JSON.stringify({ text: "On my way", n: "AAECAwQFBgcICQoLDA0ODw", ts: 1700000000456 });
const mergeRec = JSON.stringify({ from: otherConv, into: wireConv });
const note = JSON.stringify({ note: "Messages' notifications are off", history: "Paused: the phone is low on space" });

const vectors = {
  key: K.toString("hex"),
  enc: enc.toString("hex"),
  mac: mac.toString("hex"),
  iv: iv.toString("hex"),
  convId: { local: "c1aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", wire: hmac(mac, "conv:c1aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").toString("hex").slice(0, 32) },
  fpId: { local: "0123456789abcdef", wire: hmac(mac, "fp:0123456789abcdef").toString("hex").slice(0, 16) },
  match: texts.map(([dir, text, mime]) => {
    const t = matchText(text, mime);
    return { dir, text, mime, matchText: t, mt: t ? hmac(mac, "mt:" + dir + ":" + t).toString("hex").slice(0, 32) : "" };
  }),
  // What each sealed thing is bound to (PROTOCOL.md, "What each seal is bound to"), for a conversation and a file.
  aads: {
    conv: wireConv, ts: 1700000000123, shortTs: 12345, fp: wireFp,
    messageIn: "tr2:m:" + wireConv + ":in",
    messageOut: "tr2:m:" + wireConv + ":out",
    name: "tr2:n:" + wireConv,
    fileOriginal: "tr2:f:" + wireConv + ":1700000000123:" + wireFp + ":o",
    filePreviewShortTs: "tr2:f:" + wireConv + ":0000000012345:" + wireFp + ":p",
    merge: "tr2:merge:" + wireConv,
    out: "tr2:o:" + wireConv,
    path: "/media/v2/" + tid + "/" + wireConv + "/1700000000123-" + wireFp + "-o.enc"
  },
  message: { aad: "tr2:m:" + wireConv + ":in", plain: payload, sealed: seal(enc, Buffer.from(payload, "utf8"), "tr2:m:" + wireConv + ":in", iv) },
  // A reply request, a merge record and the relay phone's status note, each sealed as PROTOCOL.md says.
  request: { aad: "tr2:o:" + wireConv, plain: request, sealed: seal(enc, Buffer.from(request, "utf8"), "tr2:o:" + wireConv, iv) },
  merge: { aad: "tr2:merge:" + wireConv, from: otherConv, into: wireConv, plain: mergeRec,
    sealed: seal(enc, Buffer.from(mergeRec, "utf8"), "tr2:merge:" + wireConv, iv) },
  note: { aad: "tr2:note", plain: note, sealed: seal(enc, Buffer.from(note, "utf8"), "tr2:note", iv) },
  file: { aad: "tr2:f:" + wireConv + ":1700000000123:" + wireFp + ":o", plainHex: file.toString("hex"),
    sealedHex: sealBytes(enc, file, "tr2:f:" + wireConv + ":1700000000123:" + wireFp + ":o", iv).toString("hex") },
  link: {
    code, norm: normCode,
    lookup: hkdf(seed, "textrelay v2 lookup").toString("hex"),
    boxKey: boxKey.toString("hex"),
    box: seal(boxKey, Buffer.from(JSON.stringify({ v: 2, tid, key: b64u(K) }), "utf8"), "tr2:box", iv)
  },
  phone: {
    androidId, role: "relay",
    tag: hkdf(pseed, "textrelay v2 tag").toString("hex"),
    wrapKey: wrapKey.toString("hex"),
    wrap: seal(wrapKey, Buffer.from(JSON.stringify({ v: 2, tid, token: "tok", key: b64u(K) }), "utf8"), "tr2:wrap", iv)
  }
};
// Reactions and quoted replies as texts carry them (PROTOCOL.md, Replies): what tr2.js `words`, Words.java in Paigr and
// Words.java in Paigr Relay must each read and write. Written out by hand, not computed, so all three are checked
// against the same fixed expectations.
vectors.words = {
 "parse": [
  {
   "text": "Loved “Running late, 10 min”",
   "reaction": {
    "emoji": "❤️",
    "to": "Running late, 10 min",
    "kind": null,
    "removed": false
   },
   "quote": null,
   "snippet": "❤️ to “Running late, 10 min”"
  },
  {
   "text": "Liked “hey”",
   "reaction": {
    "emoji": "👍",
    "to": "hey",
    "kind": null,
    "removed": false
   },
   "quote": null,
   "snippet": "👍 to “hey”"
  },
  {
   "text": "Disliked “no”",
   "reaction": {
    "emoji": "👎",
    "to": "no",
    "kind": null,
    "removed": false
   },
   "quote": null,
   "snippet": "👎 to “no”"
  },
  {
   "text": "Laughed at “that joke”",
   "reaction": {
    "emoji": "😂",
    "to": "that joke",
    "kind": null,
    "removed": false
   },
   "quote": null,
   "snippet": "😂 to “that joke”"
  },
  {
   "text": "Emphasized “call me”",
   "reaction": {
    "emoji": "‼️",
    "to": "call me",
    "kind": null,
    "removed": false
   },
   "quote": null,
   "snippet": "‼️ to “call me”"
  },
  {
   "text": "Questioned “tomorrow?”",
   "reaction": {
    "emoji": "❓",
    "to": "tomorrow?",
    "kind": null,
    "removed": false
   },
   "quote": null,
   "snippet": "❓ to “tomorrow?”"
  },
  {
   "text": "Liked \"straight quotes\"",
   "reaction": {
    "emoji": "👍",
    "to": "straight quotes",
    "kind": null,
    "removed": false
   },
   "quote": null,
   "snippet": "👍 to “straight quotes”"
  },
  {
   "text": "Reacted 😮 to “hi there”",
   "reaction": {
    "emoji": "😮",
    "to": "hi there",
    "kind": null,
    "removed": false
   },
   "quote": null,
   "snippet": "😮 to “hi there”"
  },
  {
   "text": "Reacted 🙏 to “thanks”",
   "reaction": {
    "emoji": "🙏",
    "to": "thanks",
    "kind": null,
    "removed": false
   },
   "quote": null,
   "snippet": "🙏 to “thanks”"
  },
  {
   "text": "Reacted 🏳️‍🌈 to “pride”",
   "reaction": {
    "emoji": "🏳️‍🌈",
    "to": "pride",
    "kind": null,
    "removed": false
   },
   "quote": null,
   "snippet": "🏳️‍🌈 to “pride”"
  },
  {
   "text": "👍 to “hey”",
   "reaction": {
    "emoji": "👍",
    "to": "hey",
    "kind": null,
    "removed": false
   },
   "quote": null,
   "snippet": "👍 to “hey”"
  },
  {
   "text": "❤️ to “hey”",
   "reaction": {
    "emoji": "❤️",
    "to": "hey",
    "kind": null,
    "removed": false
   },
   "quote": null,
   "snippet": "❤️ to “hey”"
  },
  {
   "text": "Loved an image",
   "reaction": {
    "emoji": "❤️",
    "to": null,
    "kind": "image",
    "removed": false
   },
   "quote": null,
   "snippet": "❤️ to a picture"
  },
  {
   "text": "Liked a video",
   "reaction": {
    "emoji": "👍",
    "to": null,
    "kind": "video",
    "removed": false
   },
   "quote": null,
   "snippet": "👍 to a video"
  },
  {
   "text": "Laughed at an attachment",
   "reaction": {
    "emoji": "😂",
    "to": null,
    "kind": "file",
    "removed": false
   },
   "quote": null,
   "snippet": "😂 to an attachment"
  },
  {
   "text": "Reacted 😢 to an audio message",
   "reaction": {
    "emoji": "😢",
    "to": null,
    "kind": "audio",
    "removed": false
   },
   "quote": null,
   "snippet": "😢 to a voice message"
  },
  {
   "text": "Loved a sticker",
   "reaction": {
    "emoji": "❤️",
    "to": null,
    "kind": "image",
    "removed": false
   },
   "quote": null,
   "snippet": "❤️ to a picture"
  },
  {
   "text": "Liked a link",
   "reaction": {
    "emoji": "👍",
    "to": null,
    "kind": "link",
    "removed": false
   },
   "quote": null,
   "snippet": "👍 to a link"
  },
  {
   "text": "Removed a heart from “hi”",
   "reaction": {
    "emoji": "❤️",
    "to": "hi",
    "kind": null,
    "removed": true
   },
   "quote": null,
   "snippet": "Removed ❤️ from “hi”"
  },
  {
   "text": "Removed a like from an image",
   "reaction": {
    "emoji": "👍",
    "to": null,
    "kind": "image",
    "removed": true
   },
   "quote": null,
   "snippet": "Removed 👍 from a picture"
  },
  {
   "text": "Removed 😮 from “hi”",
   "reaction": {
    "emoji": "😮",
    "to": "hi",
    "kind": null,
    "removed": true
   },
   "quote": null,
   "snippet": "Removed 😮 from “hi”"
  },
  {
   "text": "Removed a question mark from “why”",
   "reaction": {
    "emoji": "❓",
    "to": "why",
    "kind": null,
    "removed": true
   },
   "quote": null,
   "snippet": "Removed ❓ from “why”"
  },
  {
   "text": "Reacted 👍 to \"On my way\"",
   "reaction": {
    "emoji": "👍",
    "to": "On my way",
    "kind": null,
    "removed": false
   },
   "quote": null,
   "snippet": "👍 to “On my way”"
  },
  {
   "text": "Reacted 👍 to On my way",
   "reaction": {
    "emoji": "👍",
    "to": "On my way",
    "kind": null,
    "removed": false
   },
   "quote": null,
   "snippet": "👍 to “On my way”"
  },
  {
   "text": "Reacted 👍 to On my way.",
   "reaction": {
    "emoji": "👍",
    "to": "On my way",
    "kind": null,
    "removed": false
   },
   "quote": null,
   "snippet": "👍 to “On my way”"
  },
  {
   "text": "Maya Chen reacted ❤️ to “Doors open at 7”",
   "reaction": {
    "emoji": "❤️",
    "to": "Doors open at 7",
    "kind": null,
    "removed": false
   },
   "quote": null,
   "snippet": "❤️ to “Doors open at 7”"
  },
  {
   "text": "Reacted with 😂 to “lol”",
   "reaction": {
    "emoji": "😂",
    "to": "lol",
    "kind": null,
    "removed": false
   },
   "quote": null,
   "snippet": "😂 to “lol”"
  },
  {
   "text": "👍 to hey there",
   "reaction": {
    "emoji": "👍",
    "to": "hey there",
    "kind": null,
    "removed": false
   },
   "quote": null,
   "snippet": "👍 to “hey there”"
  },
  {
   "text": "Reacted 👍 to your message",
   "reaction": {
    "emoji": "👍",
    "to": null,
    "kind": "own",
    "removed": false
   },
   "quote": null,
   "snippet": "👍 to your message"
  },
  {
   "text": "❤️ to your message.",
   "reaction": {
    "emoji": "❤️",
    "to": null,
    "kind": "own",
    "removed": false
   },
   "quote": null,
   "snippet": "❤️ to your message"
  },
  {
   "text": "Loved ‘single quotes’",
   "reaction": {
    "emoji": "❤️",
    "to": "single quotes",
    "kind": null,
    "removed": false
   },
   "quote": null,
   "snippet": "❤️ to “single quotes”"
  },
  {
   "text": "Liked „low quotes“",
   "reaction": {
    "emoji": "👍",
    "to": "low quotes",
    "kind": null,
    "removed": false
   },
   "quote": null,
   "snippet": "👍 to “low quotes”"
  },
  {
   "text": "Reacted 👍 to «guillemets»",
   "reaction": {
    "emoji": "👍",
    "to": "guillemets",
    "kind": null,
    "removed": false
   },
   "quote": null,
   "snippet": "👍 to “guillemets”"
  },
  {
   "text": "Removed 👍 from an image",
   "reaction": {
    "emoji": "👍",
    "to": null,
    "kind": "image",
    "removed": true
   },
   "quote": null,
   "snippet": "Removed 👍 from a picture"
  },
  {
   "text": "- to do list",
   "reaction": null,
   "quote": null,
   "snippet": "- to do list"
  },
  {
   "text": ":) to be honest",
   "reaction": null,
   "quote": null,
   "snippet": ":) to be honest"
  },
  {
   "text": "hello to “you”",
   "reaction": null,
   "quote": null,
   "snippet": "hello to “you”"
  },
  {
   "text": "— to be fair",
   "reaction": null,
   "quote": null,
   "snippet": "— to be fair"
  },
  {
   "text": "I reacted to it",
   "reaction": null,
   "quote": null,
   "snippet": "I reacted to it"
  },
  {
   "text": "I Loved “x”",
   "reaction": null,
   "quote": null,
   "snippet": "I Loved “x”"
  },
  {
   "text": "Loved",
   "reaction": null,
   "quote": null,
   "snippet": "Loved"
  },
  {
   "text": "Loved “”",
   "reaction": null,
   "quote": null,
   "snippet": "Loved “”"
  },
  {
   "text": "loved “lower”",
   "reaction": null,
   "quote": null,
   "snippet": "loved “lower”"
  },
  {
   "text": "Reacted to “x”",
   "reaction": null,
   "quote": null,
   "snippet": "Reacted to “x”"
  },
  {
   "text": "Reacted abc to “x”",
   "reaction": null,
   "quote": null,
   "snippet": "Reacted abc to “x”"
  },
  {
   "text": "👍 to nothing",
   "reaction": {
    "emoji": "👍",
    "to": "nothing",
    "kind": null,
    "removed": false
   },
   "quote": null,
   "snippet": "👍 to “nothing”"
  },
  {
   "text": "Liked “multi\nline”",
   "reaction": {
    "emoji": "👍",
    "to": "multi\nline",
    "kind": null,
    "removed": false
   },
   "quote": null,
   "snippet": "👍 to “multi\nline”"
  },
  {
   "text": "> Running late\nNo rush",
   "reaction": null,
   "quote": {
    "quoted": "Running late",
    "body": "No rush"
   },
   "snippet": "No rush"
  },
  {
   "text": "> a\n> b\n\nok",
   "reaction": null,
   "quote": {
    "quoted": "a b",
    "body": "ok"
   },
   "snippet": "ok"
  },
  {
   "text": "> only",
   "reaction": null,
   "quote": null,
   "snippet": "> only"
  },
  {
   "text": ">no space",
   "reaction": null,
   "quote": null,
   "snippet": ">no space"
  },
  {
   "text": "hello\n> x",
   "reaction": null,
   "quote": null,
   "snippet": "hello\n> x"
  },
  {
   "text": ">\nbare",
   "reaction": null,
   "quote": {
    "quoted": "",
    "body": "bare"
   },
   "snippet": "bare"
  },
  {
   "text": "> quoted\n> more\nanswer\nsecond line",
   "reaction": null,
   "quote": {
    "quoted": "quoted more",
    "body": "answer\nsecond line"
   },
   "snippet": "answer\nsecond line"
  },
  {
   "text": "plain text",
   "reaction": null,
   "quote": null,
   "snippet": "plain text"
  },
  {
   "text": "  Loved “padded”  ",
   "reaction": {
    "emoji": "❤️",
    "to": "padded",
    "kind": null,
    "removed": false
   },
   "quote": null,
   "snippet": "❤️ to “padded”"
  }
 ],
 "reactionText": [
  {
   "emoji": "❤️",
   "to": "hi",
   "kind": null,
   "removed": false,
   "text": "Loved “hi”"
  },
  {
   "emoji": "👍",
   "to": "Running late, 10 min",
   "kind": null,
   "removed": false,
   "text": "Liked “Running late, 10 min”"
  },
  {
   "emoji": "👎",
   "to": "",
   "kind": "image",
   "removed": false,
   "text": "Disliked an image"
  },
  {
   "emoji": "😂",
   "to": null,
   "kind": "video",
   "removed": false,
   "text": "Laughed at a video"
  },
  {
   "emoji": "‼️",
   "to": "x",
   "kind": null,
   "removed": false,
   "text": "Emphasized “x”"
  },
  {
   "emoji": "❓",
   "to": "x",
   "kind": null,
   "removed": false,
   "text": "Questioned “x”"
  },
  {
   "emoji": "😮",
   "to": "hi there",
   "kind": null,
   "removed": false,
   "text": "Reacted 😮 to “hi there”"
  },
  {
   "emoji": "🙏",
   "to": null,
   "kind": "audio",
   "removed": false,
   "text": "Reacted 🙏 to an audio message"
  },
  {
   "emoji": "❤️",
   "to": "hi",
   "kind": null,
   "removed": true,
   "text": "Removed a heart from “hi”"
  },
  {
   "emoji": "👍",
   "to": null,
   "kind": "image",
   "removed": true,
   "text": "Removed a like from an image"
  },
  {
   "emoji": "😮",
   "to": "hi",
   "kind": null,
   "removed": true,
   "text": "Removed 😮 from “hi”"
  },
  {
   "emoji": "😮",
   "to": null,
   "kind": "file",
   "removed": true,
   "text": "Removed 😮 from an attachment"
  },
  {
   "emoji": "😮",
   "to": null,
   "kind": "nonsense",
   "removed": false,
   "text": "Reacted 😮 to an attachment"
  }
 ],
 "quoteText": [
  {
   "original": "Running late,  10 min\nsecond line",
   "kind": null,
   "reply": "No rush",
   "text": "> Running late, 10 min second line\nNo rush"
  },
  {
   "original": "",
   "kind": "image",
   "reply": "nice",
   "text": "> Picture\nnice"
  },
  {
   "original": "",
   "kind": null,
   "reply": "ok",
   "text": "> Attachment\nok"
  },
  {
   "original": "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
   "kind": null,
   "reply": " r ",
   "text": "> xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx…\nr"
  }
 ],
 "sameWords": [
  [
   "“Running late”",
   "Running  late",
   true
  ],
  [
   "Running late, 10 min and then some more wo…",
   "Running late, 10 min and then some more words here",
   true
  ],
  [
   "Running late, 10 min and then some more wo...",
   "Running late, 10 min and then some more words here",
   true
  ],
  [
   "hi…",
   "hi there",
   false
  ],
  [
   "hi",
   "hi there",
   false
  ],
  [
   "",
   "",
   false
  ],
  [
   "A",
   "a",
   true
  ],
  [
   "ＡＢＣ",
   "ABC",
   true
  ],
  [
   "On my way.",
   "On my way",
   true
  ],
  [
   "On my way",
   "On my way!",
   true
  ],
  [
   "Hi, is the bike…",
   "Hi, is the bike still for sale?",
   true
  ],
  [
   "ok…",
   "ok see you",
   false
  ],
  [
   "ok",
   "ok.",
   true
  ]
 ]
};
fs.writeFileSync(path.join(__dirname, "vectors.json"), JSON.stringify(vectors, null, 1) + "\n");
console.log("wrote vectors.json:", vectors.match.length, "match cases,", vectors.words.parse.length, "word cases");
