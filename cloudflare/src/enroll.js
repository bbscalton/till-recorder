// Enrollment keys (additive). A Watch owner creates a one-time key for a new till (name, optional camera, optional PIN).
// 2.0.10 "Add register": the key can carry a generated PIN, sealed at rest (AES-GCM, key derived from the Worker secret);
// the PIN is returned to the owner once at creation and to the paired app once at redemption, then erased.
// The Unruly POS app redeems it once at POST /api/enroll and receives the same credentials /api/pair gives.
// Storage: enroll/<key>.json (separate prefix) and enroll-tries/<ip>.json (rate limit). Nothing existing is read-modified.
const ENROLL_KEY = /^[A-Za-z0-9_-]{22}$/;
const TTL_MIN_H = 1;
const TTL_MAX_H = 168;
const TTL_DEFAULT_H = 24;
const TRIES_PER_WINDOW = 20;

function newKey() {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function cleanTillName(raw) {
  return String(raw || "").replace(/[^\x20-\x7e]/g, "").trim().slice(0, 80);
}

export function trivialPin(pin) {
  if (/^(\d)\1+$/.test(pin)) return true;
  const d = [...pin].map(Number);
  const step = d[1] - d[0];
  return (step === 1 || step === -1) && d.every((x, i) => i === 0 || x - d[i - 1] === step);
}

export function randomPin() {
  for (let i = 0; i < 50; i++) {
    const n = crypto.getRandomValues(new Uint32Array(1))[0] % 1000000;
    const pin = String(n).padStart(6, "0");
    if (!trivialPin(pin)) return pin;
  }
  return "482915";
}

const b64 = (bytes) => { let t = ""; for (const b of bytes) t += String.fromCharCode(b); return btoa(t); };
const unb64 = (text) => Uint8Array.from(atob(text), (c) => c.charCodeAt(0));

async function pinKey(env) {
  const raw = new TextEncoder().encode(`till-enroll-pin-v1:${env.TILL_TOKEN || ""}`);
  const digest = await crypto.subtle.digest("SHA-256", raw);
  return crypto.subtle.importKey("raw", digest, "AES-GCM", false, ["encrypt", "decrypt"]);
}

/** Encrypts a PIN for storage in the enroll record (iv.ciphertext, base64). */
export async function sealPin(env, pin) {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ct = await crypto.subtle.encrypt({ name: "AES-GCM", iv }, await pinKey(env), new TextEncoder().encode(pin));
  return `${b64(iv)}.${b64(new Uint8Array(ct))}`;
}

/** The PIN of an enroll record: sealed (2.0.10) or the plain legacy field. Empty when none or unreadable. */
export async function openPin(env, rec) {
  if (rec && rec.pinEnc) {
    try {
      const [iv, ct] = String(rec.pinEnc).split(".");
      const plain = await crypto.subtle.decrypt({ name: "AES-GCM", iv: unb64(iv) }, await pinKey(env), unb64(ct));
      return new TextDecoder().decode(plain);
    } catch (error) {
      return "";
    }
  }
  return rec && rec.pin ? String(rec.pin) : "";
}

function putJson(env, key, value) {
  return env.RECORDINGS.put(key, JSON.stringify(value), { httpMetadata: { contentType: "application/json" } });
}

export async function createEnrollKey(request, env, who, deps) {
  const { json, canWatch } = deps;
  if (who && who.role === "user" && !canWatch(who.user)) {
    return json({ error: "Approve the account and an active plan before adding a till." }, 403);
  }
  const body = await request.json().catch(() => ({}));
  const name = cleanTillName(body.name);
  if (!name) return json({ error: "Give the till a name." }, 400);
  // 2.0.10: camera is optional ("" = the app keeps its default, front). Explicit values still work.
  const camera = body.camera === "back" ? "back" : body.camera === "front" ? "front" : "";
  if (body.camera && !camera) return json({ error: "Camera must be front or back." }, 400);
  let pin = "";
  if (body.pin !== undefined && body.pin !== null && body.pin !== "") {
    pin = String(body.pin);
    if (!/^\d{6}$/.test(pin) || trivialPin(pin)) {
      return json({ error: "Use a 6-digit PIN that is not a repeat or a simple run." }, 400);
    }
  } else if (body.generate_pin) {
    pin = randomPin();
  }
  const hours = Math.min(TTL_MAX_H, Math.max(TTL_MIN_H, Number(body.expires_hours) || TTL_DEFAULT_H));
  const key = newKey();
  const now = Date.now();
  if (pin && !env.TILL_TOKEN) return json({ error: "Recorder is not ready" }, 500);
  const record = { name, camera, pin: "", createdAt: now, expires: now + hours * 3600 * 1000, used: false };
  if (pin) record.pinEnc = await sealPin(env, pin);
  if (who && who.role === "user") record.ownerUserId = who.user.id;
  await putJson(env, `enroll/${key}.json`, record);
  const origin = new URL(request.url).origin;
  const link = `${origin}/e/${key}`;
  return json({
    ok: true, key, name, camera, expires: record.expires, has_pin: Boolean(pin),
    ...(pin ? { master_pin: pin } : {}),
    link, deep_link: `unrulypos://enroll?key=${key}&server=${encodeURIComponent(origin)}`,
  }, 200, true);
}

export async function readEnrollKey(env, raw, who, deps) {
  const { json } = deps;
  const key = String(raw || "");
  if (!ENROLL_KEY.test(key)) return json({ error: "Not found" }, 404);
  const object = await env.RECORDINGS.get(`enroll/${key}.json`);
  if (!object) return json({ error: "Not found" }, 404);
  const rec = await object.json();
  if (who && who.role !== "admin" && rec.ownerUserId !== who.user.id) return json({ error: "Not found" }, 404);
  const expired = Date.now() > Number(rec.expires || 0);
  return json({ used: Boolean(rec.used), expired: expired && !rec.used, name: rec.name || "", camera: rec.camera || "" }, 200, true);
}

async function allowEnrollTry(env, request) {
  const ip = (request.headers.get("CF-Connecting-IP") || "unknown").replace(/[^A-Za-z0-9.:_-]/g, "").slice(0, 80) || "unknown";
  const key = `enroll-tries/${ip}.json`;
  const now = Date.now();
  const existing = await env.RECORDINGS.get(key);
  let record = existing ? await existing.json().catch(() => null) : null;
  if (!record || now > Number(record.resetAt || 0)) record = { count: 0, resetAt: now + 10 * 60 * 1000 };
  record.count += 1;
  await putJson(env, key, record);
  return record.count <= TRIES_PER_WINDOW;
}

export async function redeemEnrollKey(request, env, deps) {
  const { json, DEVICE_ID, writeStatus } = deps;
  if (!(await allowEnrollTry(env, request))) return json({ error: "Too many tries. Wait a few minutes." }, 429);
  const body = await request.json().catch(() => ({}));
  const key = String(body.key || "");
  const deviceId = body.device_id || "";
  const bad = () => json({ error: "That enrollment link is not valid." }, 400);
  if (!ENROLL_KEY.test(key) || !DEVICE_ID.test(deviceId)) return bad();
  if (!env.TILL_TOKEN) return json({ error: "Recorder is not ready" }, 500);
  const object = await env.RECORDINGS.get(`enroll/${key}.json`);
  if (!object) return bad();
  const rec = await object.json().catch(() => null);
  if (!rec || rec.used || Date.now() > Number(rec.expires || 0)) return bad();
  // Never touch an existing register: the device id must be new (or a previously removed one).
  const removed = await env.RECORDINGS.get(`removed/${deviceId}`);
  if (!removed && (await env.RECORDINGS.head(`status/${deviceId}.json`))) {
    return json({ error: "This device is already registered. Use a pair code." }, 409);
  }
  const name = cleanTillName(body.device_name) && !rec.name ? cleanTillName(body.device_name) : rec.name;
  const pin = await openPin(env, rec);
  if (rec.pinEnc && !pin) return bad(); // sealed PIN unreadable: never hand out a till without its PIN
  const { pin: _plain, pinEnc: _sealed, ...rest } = rec;
  const claimed = await env.RECORDINGS.put(
    `enroll/${key}.json`,
    JSON.stringify({ ...rest, pin: "", used: true, usedAt: Date.now(), deviceId }),
    { httpMetadata: { contentType: "application/json" }, onlyIf: { etagMatches: object.etag } },
  );
  if (!claimed) return bad(); // lost a race: someone else redeemed it
  // Same effects as /api/pair claim.
  await env.RECORDINGS.delete(`removed/${deviceId}`);
  await env.RECORDINGS.delete(`pin-push/${deviceId}.json`);
  const statusPatch = { id: deviceId, name, recording: false, lastSeen: Date.now() };
  if (rec.ownerUserId) {
    statusPatch.ownerUserId = rec.ownerUserId;
    statusPatch.legacy = false;
  }
  await writeStatus(env, statusPatch);
  return json({
    ok: true,
    token: env.TILL_TOKEN,
    device_id: deviceId,
    device_name: name,
    camera: rec.camera,
    master_pin: pin || null,
  }, 200, true);
}

export function enrollLanding(request, key) {
  const origin = new URL(request.url).origin;
  const valid = ENROLL_KEY.test(key);
  const deep = `unrulypos://enroll?key=${valid ? key : ""}&server=${encodeURIComponent(origin)}`;
  const apk = "https://github.com/bbscalton/till-recorder/releases/download/unruly-pos-2.0.10/UnrulyPOS-2.0.10.apk";
  const html = `<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="robots" content="noindex"><title>Add this till</title>
<body style="font-family:system-ui,sans-serif;max-width:30rem;margin:2rem auto;padding:0 1rem">
<h1>Add this till to Unruly POS</h1>
${valid ? `<ol><li><a href="${apk}">Install Unruly POS</a> (skip if already installed).</li>
<li><a href="${deep}" style="font-weight:bold">Open Unruly POS and connect</a></li></ol>
<p>This link works once and expires. It sets the till name, camera and PIN for you.</p>` : "<p>This link is not valid.</p>"}
</body></html>`;
  return new Response(html, { headers: { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store", "Referrer-Policy": "no-referrer" } });
}
