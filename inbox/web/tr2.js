/* Text Relay v2: the encryption both inbox devices and the web page share (see PROTOCOL.md). WebCrypto only. */
(function (root) {
  "use strict";
  const cryptoApi = root.crypto || globalThis.crypto;
  const subtle = cryptoApi.subtle;
  const encoder = new TextEncoder();
  const decoder = new TextDecoder();
  const utf8 = (s) => encoder.encode(String(s));
  const ITERATIONS = 150000;
  const ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";   // 32 symbols, no I, L, O or U

  // ---------------------------------------------------------------- encodings

  function b64u(bytes) {
    let bin = "";
    for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
    return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  }

  /** Base64url without padding, in its one spelling only, so a sealed value can't be written several ways. */
  function unb64u(s) {
    if (typeof s !== "string" || !/^[A-Za-z0-9_-]*$/.test(s) || s.length % 4 === 1) throw new Error("not base64url");
    const bin = atob(s.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((s.length + 3) % 4));
    const out = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    if (b64u(out) !== s) throw new Error("not base64url");
    return out;
  }

  function hex(bytes) {
    let s = "";
    for (const b of bytes) s += b.toString(16).padStart(2, "0");
    return s;
  }

  function random(n) {
    return cryptoApi.getRandomValues(new Uint8Array(n));
  }

  // ---------------------------------------------------------------- primitives

  async function hkdf(ikm, info) {
    const k = await subtle.importKey("raw", ikm, "HKDF", false, ["deriveBits"]);
    return new Uint8Array(await subtle.deriveBits({ name: "HKDF", hash: "SHA-256", salt: new Uint8Array(0), info: utf8(info) }, k, 256));
  }

  async function pbkdf2(password, salt) {
    const k = await subtle.importKey("raw", utf8(password), "PBKDF2", false, ["deriveBits"]);
    return new Uint8Array(await subtle.deriveBits({ name: "PBKDF2", hash: "SHA-256", salt: utf8(salt), iterations: ITERATIONS }, k, 256));
  }

  const aesKey = (raw) => subtle.importKey("raw", raw, "AES-GCM", false, ["encrypt", "decrypt"]);
  const hmacKey = (raw) => subtle.importKey("raw", raw, { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);

  /** AES-256-GCM: iv ‖ ciphertext ‖ tag. `iv` is for test vectors only. */
  async function sealBytes(key, bytes, aad, iv) {
    iv = iv || random(12);
    const ct = new Uint8Array(await subtle.encrypt({ name: "AES-GCM", iv, additionalData: utf8(aad) }, key, bytes));
    const out = new Uint8Array(12 + ct.length);
    out.set(iv);
    out.set(ct, 12);
    return out;
  }

  async function openBytes(key, bytes, aad) {
    if (!bytes || bytes.length < 28) throw new Error("sealed data too short");
    return new Uint8Array(await subtle.decrypt({ name: "AES-GCM", iv: bytes.subarray(0, 12), additionalData: utf8(aad) }, key, bytes.subarray(12)));
  }

  async function seal(key, bytes, aad, iv) {
    return "e1." + b64u(await sealBytes(key, bytes, aad, iv));
  }

  async function open(key, sealed, aad) {
    if (typeof sealed !== "string" || !sealed.startsWith("e1.")) throw new Error("not sealed");
    return openBytes(key, unb64u(sealed.slice(3)), aad);
  }

  async function sealJson(key, value, aad, iv) {
    return seal(key, utf8(JSON.stringify(value)), aad, iv);
  }

  async function openJson(key, sealed, aad) {
    return JSON.parse(decoder.decode(await open(key, sealed, aad)));
  }

  // ---------------------------------------------------------------- the inbox key

  /** The inbox key K (32 bytes) as the keys it stands for. */
  async function inboxKeys(raw) {
    if (!(raw instanceof Uint8Array) || raw.length !== 32) throw new Error("the inbox key must be 32 bytes");
    return {
      raw,
      enc: await aesKey(await hkdf(raw, "textrelay v2 enc")),
      mac: await hmacKey(await hkdf(raw, "textrelay v2 mac"))
    };
  }

  async function mac(keys, s) {
    return new Uint8Array(await subtle.sign("HMAC", keys.mac, utf8(s)));
  }

  async function convId(keys, local) {
    return hex(await mac(keys, "conv:" + local)).slice(0, 32);
  }

  async function fpId(keys, fp) {
    return hex(await mac(keys, "fp:" + fp)).slice(0, 16);
  }

  /** What two copies of a message share, as the server compares it: "" when there is nothing to compare. */
  async function matchToken(keys, dir, text, mime) {
    const t = matchText(text, mime);
    return t ? hex(await mac(keys, "mt:" + (dir === "out" ? "out" : "in") + ":" + t)).slice(0, 32) : "";
  }

  function normText(t) {
    return String(t || "").normalize("NFKC").replace(/[​-‍﻿]/g, "").replace(/\s+/g, " ").trim();
  }

  const MEDIA_WORD = /^(?:sent an? )?(photo|picture|image|sticker|gif|video|voice message|audio|attachment|file)$/i;

  function mediaLabel(mime) {
    const t = String(mime || "").toLowerCase();
    if (!t) return "";
    if (t.startsWith("image/")) return "Picture";
    if (t.startsWith("video/")) return "Video";
    if (t.startsWith("audio/")) return "Voice message";
    return "Attachment";
  }

  /** v1's rule: the text, or for a bare media word or an attachment without text, the kind of attachment. */
  function matchText(text, mime) {
    const t = normText(text);
    const word = MEDIA_WORD.exec(t.replace(/^[^\p{L}\p{N}]+/u, ""));
    if (word) {
      const w = word[1].toLowerCase();
      return "\u0000" + (w === "video" ? "Video" : w === "voice message" || w === "audio" ? "Voice message"
        : w === "attachment" || w === "file" ? "Attachment" : "Picture");
    }
    if (!t && mime) return "\u0000" + mediaLabel(mime);
    return t;
  }

  // ---------------------------------------------------------------- linking

  /** Case, spaces and dashes don't matter; O, I and L read as 0, 1 and 1. */
  function normCode(v) {
    return String(v || "").toLowerCase().replace(/[^a-z0-9]/g, "").replace(/o/g, "0").replace(/[il]/g, "1").slice(0, 64);
  }

  function newCode() {
    return Array.from(random(12), b => ALPHABET[b & 31]).join("");
  }

  function prettyCode(code) {
    return String(code).toUpperCase().replace(/(.{4})(?=.)/g, "$1-");
  }

  /** What a code stands for: where the server files its offer, and the key only the two devices know. */
  async function linkSecrets(code) {
    const seed = await pbkdf2(normCode(code), "textrelay v2 link");
    return { lookup: hex(await hkdf(seed, "textrelay v2 lookup")), boxKey: await aesKey(await hkdf(seed, "textrelay v2 box")) };
  }

  /** A phone's own secrets, from the Android ID the app has there: its tag on the server, and the key to its wrap. */
  async function phoneSecrets(androidId, role) {
    const seed = await pbkdf2(String(androidId).trim().toLowerCase(), "textrelay v2 phone " + role);
    return { tag: hex(await hkdf(seed, "textrelay v2 tag")), wrapKey: await aesKey(await hkdf(seed, "textrelay v2 wrap")) };
  }

  /**
   * The code in what Relay's QR code holds (TEXTRELAY2:<server host>/<CODE>), in a link to this page
   * (https://<server>/#link=<CODE>), or typed. The QR code isn't a web address on purpose: a camera app that isn't
   * Texts shows it as text instead of opening a page the server could change (PROTOCOL.md, Linking).
   */
  function parseLink(text) {
    const s = String(text || "").trim();
    let server = null, code = s;
    // The page only takes a code for its own origin (see linkWithCode), so either scheme is safe to read here.
    const q = /^TEXTRELAY2:([a-z0-9.-]+(?::[0-9]{1,5})?)\/([0-9a-z-]{12,20})$/i.exec(s);
    const m = q ? null : /^(https?:\/\/[^/#?\s]+)\/?(?:[?][^#]*)?#link=([0-9A-Za-z-]{12,20})$/.exec(s);
    if (q) {
      const host = q[1].toLowerCase();
      // A .onion server is reached through Tor, which encrypts it end to end: it has no https.
      server = (/^(?:127\.0\.0\.1|localhost)(?::|$)/.test(host) || /\.onion(?::|$)/.test(host) ? "http://" : "https://") + host;
      code = q[2];
    } else if (m) {
      server = m[1]; code = m[2];
    }
    const n = normCode(code);
    if (n.length !== 12 || !/^[0-9a-hjkmnp-tv-z]{12}$/.test(n)) return null;
    return { server, code: n.toUpperCase() };
  }

  function linkUrl(server, code) {
    return String(server).replace(/\/+$/, "") + "/#link=" + String(code).toUpperCase();
  }

  /** What Relay's QR code holds for a code: TEXTRELAY2:<server host>/<CODE>. */
  function linkQr(server, code) {
    return "TEXTRELAY2:" + String(server).replace(/^https?:\/\//i, "").replace(/\/+$/, "").toLowerCase() + "/" + String(code).toUpperCase();
  }

  // ---------------------------------------------------------------- reactions and quoted replies, as texts carry them
  // Messages has no door for a third-party app to send a reaction or a quoted reply, so both travel as ordinary text,
  // in the forms phones already understand: a reaction the way an iPhone writes one as a text (Loved “hi”, Reacted 😮
  // to “hi”), which Messages shows as a reaction, and a quoted reply as a line starting "> " above the answer, which
  // reads as a quote anywhere. Every device parses them back out of the text to draw them (Words.java is the same).

  const REACT_WORDS = { Loved: "\u2764\uFE0F", Liked: "\uD83D\uDC4D", Disliked: "\uD83D\uDC4E", "Laughed at": "\uD83D\uDE02", Emphasized: "\u203C\uFE0F", Questioned: "\u2753" };
  const REMOVE_WORDS = { "a heart": REACT_WORDS.Loved, "a like": REACT_WORDS.Liked, "a dislike": REACT_WORDS.Disliked, "a laugh": REACT_WORDS["Laughed at"], "an exclamation": REACT_WORDS.Emphasized, "a question mark": REACT_WORDS.Questioned };
  const KIND_WORDS = { "an image": "image", "a sticker": "image", "a video": "video", "an audio message": "audio", "an attachment": "file", "a location": "file", "a contact": "file", "a link": "link" };
  const KIND_SAID = { image: "a picture", video: "a video", audio: "a voice message", file: "an attachment", link: "a link" };
  const KIND_SENT = { image: "an image", video: "a video", audio: "an audio message", file: "an attachment", link: "a link" };
  const EMOJI = "[^\\p{L}\\p{N}\\s\"\u201C\u201D]{1,12}";
  // The opening and closing quotes phones put round the words (straight, curly, low-9, guillemets, single).
  const OPEN = "[\u201C\"\u201E\u00AB\u2018']", CLOSE = "[\u201D\"\u201C\u00BB\u2019']";
  // What a reaction names: quoted words, or a kind of attachment; as Google writes it, also "your message" and words
  // with no quotes round them.
  const WHAT = "(?:" + OPEN + "([^]+?)" + CLOSE + "|(" + Object.keys(KIND_WORDS).join("|") + "))";
  const WHAT_G = "(?:" + OPEN + "([^]+?)" + CLOSE + "|(" + Object.keys(KIND_WORDS).join("|") + ")|(your message)|([^]+?))";
  const REACTION = [
    new RegExp("^(Loved|Liked|Disliked|Laughed at|Emphasized|Questioned) " + WHAT + "\\.?$", "u"),
    new RegExp("^(?:[^]{1,60}? )?[Rr]eacted (?:with )?(" + EMOJI + ") to " + WHAT_G + "\\.?$", "u"),
    new RegExp("^Removed (a heart|a like|a dislike|a laugh|an exclamation|a question mark|" + EMOJI + ") from " + WHAT + "\\.?$", "u"),
    new RegExp("^(" + EMOJI + ") to " + WHAT_G + "\\.?$", "u")
  ];

  /** Whether there is a symbol (an emoji, not punctuation such as a dash) in what matched as one. */
  const symbol = (emoji) => /[\p{So}\u203C\u2049]/u.test(emoji);

  /**
   * A text that is a reaction: { emoji, to (the quoted text) or kind (what kind of attachment; "own" for "your
   * message", the reader's own newest message), removed }; else null. The forms read: Apple's (Loved “hi”, Reacted 😮
   * to “hi”, Removed a heart from “hi”, Liked an image) and Google's (👍 to “hi”; Reacted 👍 to “hi”, with or without
   * the quotes, a name in front, or "your message" in place of the words). A form that starts with an emoji wants a
   * real symbol in it, so a line like "- to do" isn't a reaction.
   */
  function reaction(text) {
    const s = String(text || "").trim();
    for (let i = 0; i < REACTION.length; i++) {
      const m = REACTION[i].exec(s);
      if (!m) continue;
      const first = m[1];
      const emoji = i === 0 ? REACT_WORDS[first] : i === 2 && REMOVE_WORDS[first] ? REMOVE_WORDS[first] : first;
      if (!emoji || (i !== 0 && !(i === 2 && REMOVE_WORDS[first]) && !symbol(emoji))) continue;
      let to = m[2] !== undefined ? m[2] : null;
      let kind = m[3] !== undefined ? KIND_WORDS[m[3]] : null;
      if (i === 1 || i === 3) {
        if (m[4] !== undefined) kind = "own";
        else if (m[5] !== undefined) to = m[5].trim();
      }
      if (to == null && kind == null) continue;
      return { emoji, to, kind, removed: i === 2 };
    }
    return null;
  }

  /** The text that reacts with `emoji` to a message: its text, or the kind of attachment it is (reaction()'s kinds). */
  function reactionText(emoji, to, kind, removed) {
    const word = Object.keys(REACT_WORDS).find((w) => REACT_WORDS[w] === emoji);
    const what = to != null && String(to).trim() ? "\u201C" + String(to).trim() + "\u201D" : KIND_SENT[kind] || "an attachment";
    if (removed) {
      const undo = Object.keys(REMOVE_WORDS).find((w) => REMOVE_WORDS[w] === emoji);
      return "Removed " + (undo || emoji) + " from " + what;
    }
    return word ? word + " " + what : "Reacted " + emoji + " to " + what;
  }

  /** A text that quotes another and answers it: { quoted, body }; else null. */
  function quote(text) {
    const lines = String(text || "").split("\n");
    const q = [];
    let i = 0;
    while (i < lines.length && /^>( |$)/.test(lines[i])) q.push(lines[i].slice(2).trim()), i++;
    if (!q.length || i >= lines.length) return null;
    const body = lines.slice(i).join("\n").trim();
    if (!body) return null;
    return { quoted: q.join(" ").trim(), body };
  }

  /** What `reply` looks like as an answer to a message that said `original` (or was `kind` of attachment, text-free). */
  function quoteText(original, kind, reply) {
    let line = normText(original);
    if (!line) line = KIND_SAID[kind] ? KIND_SAID[kind].replace(/^an? /, "").replace(/^./, (c) => c.toUpperCase()) : "Attachment";
    if (line.length > 80) line = line.slice(0, 79).trim() + "\u2026";
    return "> " + line + "\n" + String(reply || "").trim();
  }

  /**
   * Whether a reaction's or a quote's words name a message that said `b`: spacing and quotes evened out, the end's
   * punctuation too (a phone quoting "ok." may write "ok"), and a cut allowed (a quote cut short, ending in …).
   */
  function sameWords(a, b) {
    const n = (s) => normText(s).replace(/[\u201C\u201D"]/g, "").toLowerCase();
    const x = n(a), y = n(b);
    if (!x || !y) return false;
    if (x === y) return true;
    // A quote cut short (quoteText does at 80, phones do too) still names its message. (NFKC writes … as three dots.)
    const cut = x.replace(/(\.\.\.|\u2026)$/, "").trim();
    if (cut.length >= 12 && cut !== x && y.startsWith(cut)) return true;
    const trail = (s) => s.replace(/[.!?,;:\s]+$/, "");
    const xs = trail(x), ys = trail(y);
    return !!xs && xs === ys;
  }

  /** A text as a list or a notification shows it: a reaction in a few words, a quoted reply by its answer. */
  function snippet(text) {
    const r = reaction(text);
    if (r) return (r.removed ? "Removed " + r.emoji + " from " : r.emoji + " to ") + (r.to != null ? "\u201C" + r.to + "\u201D" : r.kind === "own" ? "your message" : KIND_SAID[r.kind] || "an attachment");
    const q = quote(text);
    return q ? q.body : String(text || "");
  }

  const words = { reaction, reactionText, quote, quoteText, sameWords, snippet, REACT_WORDS, KIND_SAID };

  // ------------------------------------------------------------ phone numbers, for starting a conversation
  // The relay phone keys a conversation started from the inbox from the number alone ("to:" + number), so every device
  // writes the number the same way: its digits, with a leading + kept (00 at the start of a long number counts as +).
  // A number without a country code goes as typed; the relay phone's SIM dials it as a local number.

  /** The number as it goes to the relay phone, or null when the text isn't a number (3 to 20 digits). */
  function canonicalNumber(typed) {
    const t = String(typed == null ? "" : typed).trim();
    if (!t || /[^0-9 .()\-+]/.test(t)) return null;
    let plus = t.startsWith("+");
    let digits = t.replace(/[^0-9]/g, "");
    if (!plus && digits.startsWith("00") && digits.length > 10) { plus = true; digits = digits.slice(2); }
    if (digits.length < 3 || digits.length > 20) return null;
    return (plus ? "+" : "") + digits;
  }

  /** A number as people write it: (801) 555-0123 for a North American number, otherwise as it is. */
  function prettyNumber(number) {
    let d = String(number || "").replace(/[^0-9]/g, "");
    if (d.length === 11 && d.startsWith("1")) d = d.slice(1);
    return d.length === 10 ? "(" + d.slice(0, 3) + ") " + d.slice(3, 6) + "-" + d.slice(6) : String(number || "");
  }

  /** What identifies a person in a number: its last ten digits for a full-length number, all of them for a short code. */
  function numberKey(number) {
    const d = String(number || "").replace(/[^0-9]/g, "");
    return d.length >= 10 ? d.slice(-10) : d;
  }

  /** Whether a conversation's name is just a number (someone not in the relay phone's contacts). */
  const isNumber = (name) => /^[+()0-9 .\-]{3,}$/.test(String(name || "")) && String(name).replace(/[^0-9]/g, "").length >= 3;

  /** The relay phone's own id for a conversation started with a number, which it keys the conversation from. */
  const startedId = (number) => "to:" + number;

  const numbers = { canonical: canonicalNumber, pretty: prettyNumber, key: numberKey, isNumber, startedId };

  // ---------------------------------------------------------------- what each sealed thing is bound to
  // Everything sealed for a conversation opens only as that conversation's (its keyed id), so the server can't show
  // one conversation's messages, name or pictures as another's, and a reply can't be pointed at the wrong person. A
  // message is also bound to its direction, so a text someone sent can't be shown as one you sent, or the other way.

  const pad13 = (n) => String(Math.max(0, Math.floor(Number(n) || 0))).padStart(13, "0");

  const AAD = {
    message: (conv, dir) => "tr2:m:" + conv + ":" + (dir === "out" ? "out" : "in"),
    name: (conv) => "tr2:n:" + conv,
    file: (conv, ts, fp, kind) => "tr2:f:" + conv + ":" + pad13(ts) + ":" + fp + ":" + (kind === "preview" || kind === "p" ? "p" : "o"),
    merge: (into) => "tr2:merge:" + into,      // the relay phone saying a conversation was folded into this one
    out: (conv) => "tr2:o:" + conv,            // a reply for the relay phone to send
    device: (id) => "tr2:dev:" + id,           // a device's name for itself; `id` as the device list gives it (deviceId)
    box: "tr2:box",
    wrap: "tr2:wrap",
    note: "tr2:note"
  };

  const MEDIA_PATH = /^\/media\/v2\/([a-f0-9]{32})\/([a-z0-9_-]{4,64})\/([0-9]{13})-([a-z0-9]{8,32})-([op])\.enc$/;

  /**
   * What a stored file is, from its path: its inbox, its conversation, the AAD it was sealed with, which message it
   * belongs to (`stem`, the message's time and fingerprint, as that message's sealed payload names them) and whether
   * it's the original ("o") or a preview ("p"); null for anything else.
   */
  function fileOf(path) {
    const m = MEDIA_PATH.exec(String(path || ""));
    return m ? { tid: m[1], conv: m[2], aad: AAD.file(m[2], m[3], m[4], m[5]), stem: m[3] + "-" + m[4], kind: m[5] } : null;
  }

  /** The stem of the files a message's sealed payload names: its time (13 digits) and its keyed fingerprint. */
  const stemOf = (ts, fp) => pad13(ts) + "-" + String(fp || "");

  /** A device's id as the inbox lists it: the first 12 hex digits of the SHA-256 of its token. */
  async function deviceId(token) {
    return hex(new Uint8Array(await subtle.digest("SHA-256", utf8(token)))).slice(0, 12);
  }

  const newToken = () => b64u(random(32));
  const newTid = () => hex(random(16));
  const newKey = () => random(32);

  const TR2 = {
    b64u, unb64u, hex, random, hkdf, pbkdf2, aesKey, hmacKey,
    seal, open, sealBytes, openBytes, sealJson, openJson,
    inboxKeys, mac, convId, fpId, matchToken, matchText, normText, mediaLabel,
    normCode, newCode, prettyCode, linkSecrets, phoneSecrets, parseLink, linkUrl, linkQr, newToken, newTid, newKey,
    AAD, fileOf, stemOf, pad13, deviceId, words, numbers
  };
  if (typeof module !== "undefined" && module.exports) module.exports = TR2;
  else root.TR2 = TR2;
})(typeof window !== "undefined" ? window : globalThis);
