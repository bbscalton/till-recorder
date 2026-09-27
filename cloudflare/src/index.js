const DEVICE_ID = /^[A-Za-z0-9_-]{8,64}$/;
const DAY = /^\d{4}-\d{2}-\d{2}$/;
const SEGMENT_ID = /^[a-f0-9]{32}$/;
const MAX_BYTES = 80 * 1024 * 1024;

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "Authorization, Content-Type, X-Device-Id, X-Device-Name, X-Stream-Kind, X-Stream-Seq, X-Stream-Codec",
  "Access-Control-Allow-Methods": "GET, POST, DELETE, OPTIONS",
};

export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") return new Response(null, { headers: cors });
    const url = new URL(request.url);
    if (url.pathname.startsWith("/api/")) {
      return handleApi(request, env, url);
    }
    return env.ASSETS.fetch(request);
  },
};

const PAIR_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";

async function handleApi(request, env, url) {
  if (request.method === "POST" && url.pathname === "/api/pair") return claimPair(request, env);
  if (!authorized(request, env)) {
    return json({ error: "Sign in" }, 401);
  }
  if (request.method === "POST" && url.pathname === "/api/pair-codes") return createPairCode(env);
  if (request.method === "GET" && url.pathname.startsWith("/api/pair-codes/")) {
    return readPairCode(env, decodeURIComponent(url.pathname.slice("/api/pair-codes/".length)));
  }
  if (request.method === "GET" && url.pathname === "/api/check-token") return json({ ok: true });
  if (request.method === "POST" && url.pathname === "/api/login") return json({ ok: true });
  if (request.method === "GET" && url.pathname === "/api/devices") return listDevices(env);
  if (request.method === "GET" && url.pathname === "/api/segments") return listSegments(env, url);
  if (request.method === "POST" && url.pathname === "/api/heartbeat") return heartbeat(request, env);
  if (request.method === "POST" && url.pathname === "/api/live") return saveLive(request, env);
  if (request.method === "POST" && url.pathname === "/api/stream") return saveStream(request, env);
  if (request.method === "GET" && url.pathname.startsWith("/api/stream/")) return readStream(env, url);
  if (request.method === "GET" && url.pathname.startsWith("/api/live/")) {
    return readLive(env, decodeURIComponent(url.pathname.slice("/api/live/".length)));
  }
  if (request.method === "POST" && url.pathname === "/api/camera") return setCamera(request, env);
  if (request.method === "POST" && url.pathname === "/api/lock") return setLock(request, env);
  if (request.method === "POST" && url.pathname === "/api/camera-frame") return saveCamera(request, env);
  if (request.method === "GET" && url.pathname.startsWith("/api/camera/")) {
    return readCamera(env, decodeURIComponent(url.pathname.slice("/api/camera/".length)));
  }
  if (request.method === "POST" && url.pathname === "/api/segments") return saveSegment(request, env);
  if (request.method === "DELETE" && url.pathname === "/api/segments") return deleteDay(env, url);
  if (request.method === "GET" && url.pathname.startsWith("/api/media/")) {
    return readMedia(request, env, url.pathname.slice("/api/media/".length));
  }
  if (request.method === "DELETE" && url.pathname.startsWith("/api/media/")) {
    return deleteSegment(env, url.pathname.slice("/api/media/".length));
  }
  return json({ error: "Not found" }, 404);
}

function authorized(request, env) {
  const header = request.headers.get("Authorization") || "";
  const bearer = header.toLowerCase().startsWith("bearer ") ? header.slice(7).trim() : "";
  const query = new URL(request.url).searchParams.get("access") || "";
  return safeEqual(bearer || query, env.TILL_TOKEN || "");
}

function safeEqual(got, expected) {
  const a = new TextEncoder().encode(got);
  const b = new TextEncoder().encode(expected);
  if (a.length !== b.length || a.length === 0) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
  return diff === 0;
}

function normalizePairCode(raw) {
  const code = String(raw || "").toUpperCase().replace(/[^A-Z0-9]/g, "");
  return /^[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{6}$/.test(code) ? code : "";
}

function formatPairCode(code) {
  return `${code.slice(0, 3)} ${code.slice(3)}`;
}

async function allowPairTry(env, request) {
  const ip = (request.headers.get("CF-Connecting-IP") || "unknown").replace(/[^A-Za-z0-9.:_-]/g, "").slice(0, 80) || "unknown";
  const key = `pair-tries/${ip}.json`;
  const now = Date.now();
  const existing = await env.RECORDINGS.get(key);
  let record = existing ? await existing.json() : { count: 0, resetAt: now + 10 * 60 * 1000 };
  if (now > Number(record.resetAt || 0)) record = { count: 0, resetAt: now + 10 * 60 * 1000 };
  record.count += 1;
  await env.RECORDINGS.put(key, JSON.stringify(record), {
    httpMetadata: { contentType: "application/json" },
  });
  return record.count <= 20;
}

async function createPairCode(env) {
  for (let attempt = 0; attempt < 5; attempt++) {
    const bytes = crypto.getRandomValues(new Uint8Array(6));
    const code = [...bytes].map((value) => PAIR_ALPHABET[value % PAIR_ALPHABET.length]).join("");
    const existing = await env.RECORDINGS.head(`pair/${code}.json`);
    if (existing) continue;
    const record = { expires: Date.now() + 10 * 60 * 1000, claimed: false };
    await env.RECORDINGS.put(`pair/${code}.json`, JSON.stringify(record), {
      httpMetadata: { contentType: "application/json" },
    });
    return json({ code, display: formatPairCode(code), expires: record.expires });
  }
  return json({ error: "Could not create a pair code" }, 500);
}

async function readPairCode(env, raw) {
  const code = normalizePairCode(raw);
  if (!code) return json({ error: "Not found" }, 404);
  const object = await env.RECORDINGS.get(`pair/${code}.json`);
  if (!object) return json({ error: "Not found" }, 404);
  const pair = await object.json();
  const expired = Date.now() > Number(pair.expires || 0);
  return json({
    claimed: Boolean(pair.claimed) && !expired,
    expired,
    name: pair.name || "",
  });
}

async function claimPair(request, env) {
  if (!(await allowPairTry(env, request))) {
    return json({ error: "Too many tries. Wait a few minutes." }, 429);
  }
  const body = await request.json().catch(() => ({}));
  const code = normalizePairCode(body.code || "");
  const deviceId = body.device_id || "";
  const name = String(body.device_name || "").replace(/[^\x20-\x7e]/g, "").trim().slice(0, 80);
  if (!code || !DEVICE_ID.test(deviceId) || !name) {
    return json({ error: "That pair code is not valid." }, 400);
  }
  if (!env.TILL_TOKEN) return json({ error: "Recorder is not ready" }, 500);
  const object = await env.RECORDINGS.get(`pair/${code}.json`);
  if (!object) return json({ error: "That pair code is not valid." }, 400);
  const pair = await object.json();
  if (pair.claimed || Date.now() > Number(pair.expires || 0)) {
    return json({ error: "That pair code is not valid." }, 400);
  }
  pair.claimed = true;
  pair.deviceId = deviceId;
  pair.name = name;
  await env.RECORDINGS.put(`pair/${code}.json`, JSON.stringify(pair), {
    httpMetadata: { contentType: "application/json" },
  });
  await writeStatus(env, { id: deviceId, name, recording: false, lastSeen: Date.now() });
  return json({ ok: true, token: env.TILL_TOKEN });
}

async function heartbeat(request, env) {
  const body = await request.json();
  if (!DEVICE_ID.test(body.device_id || "")) return json({ error: "Device id is not valid" }, 400);
  const name = cleanName(body.device_name);
  await writeStatus(env, {
    id: body.device_id,
    name,
    recording: Boolean(body.recording),
    lastSeen: Date.now(),
  });
  const control = await readControl(env, body.device_id);
  return json({
    ok: true,
    camera: control.camera,
    locked: control.locked,
    lockSeq: control.lockSeq,
  });
}

async function saveStream(request, env) {
  const deviceId = request.headers.get("X-Device-Id") || "";
  if (!DEVICE_ID.test(deviceId)) return json({ error: "Device id is not valid" }, 400);
  const kind = request.headers.get("X-Stream-Kind") || "";
  if (kind !== "screen" && kind !== "camera") return json({ error: "Stream is not valid" }, 400);
  const seq = Number(request.headers.get("X-Stream-Seq"));
  if (!Number.isInteger(seq) || seq < 0 || seq > 1_000_000_000) return json({ error: "Stream is not valid" }, 400);
  const codec = String(request.headers.get("X-Stream-Codec") || "avc1.42E01E").replace(/[^a-zA-Z0-9.]/g, "").slice(0, 32) || "avc1.42E01E";
  const body = await request.arrayBuffer();
  if (body.byteLength < 8 || body.byteLength > 4_000_000) return json({ error: "Stream is not valid" }, 400);
  const base = `stream/${deviceId}/${kind}`;
  const key = seq === 0 ? `${base}/init` : `${base}/${seq}`;
  await env.RECORDINGS.put(key, body, { httpMetadata: { contentType: "video/mp4" } });
  await env.RECORDINGS.put(`${base}/head.json`, JSON.stringify({
    seq,
    codec,
    updatedAt: Date.now(),
  }), { httpMetadata: { contentType: "application/json" } });
  if (seq > 24) await env.RECORDINGS.delete(`${base}/${seq - 24}`);
  return json({ ok: true });
}

async function readStream(env, url) {
  const parts = url.pathname.split("/").filter(Boolean);
  if (parts.length !== 5) return json({ error: "Not found" }, 404);
  const deviceId = decodeURIComponent(parts[2]);
  const kind = parts[3];
  const name = parts[4];
  if (!DEVICE_ID.test(deviceId) || (kind !== "screen" && kind !== "camera")) return json({ error: "Not found" }, 404);
  if (name === "head") {
    const object = await env.RECORDINGS.get(`stream/${deviceId}/${kind}/head.json`);
    if (!object) return json({ error: "No live video" }, 404);
    return new Response(await object.text(), {
      headers: { "Content-Type": "application/json", "Cache-Control": "no-store", ...cors },
    });
  }
  if (name !== "init" && !/^[0-9]+$/.test(name)) return json({ error: "Not found" }, 404);
  const key = name === "init" ? `stream/${deviceId}/${kind}/init` : `stream/${deviceId}/${kind}/${name}`;
  const object = await env.RECORDINGS.get(key);
  if (!object) return json({ error: "No live video" }, 404);
  const bytes = await object.arrayBuffer();
  return new Response(bytes, {
    headers: {
      "Content-Type": "video/mp4",
      "Cache-Control": "no-store",
      "Content-Length": String(bytes.byteLength),
      ...cors,
    },
  });
}

async function saveLive(request, env) {
  const deviceId = request.headers.get("X-Device-Id") || "";
  if (!DEVICE_ID.test(deviceId)) return json({ error: "Device id is not valid" }, 400);
  const name = cleanName(request.headers.get("X-Device-Name") || "Register");
  const jpeg = await readJpeg(request);
  if (!jpeg) return json({ error: "Picture is not valid" }, 400);
  await env.RECORDINGS.put(`live/${deviceId}.jpg`, jpeg, {
    httpMetadata: { contentType: "image/jpeg" },
  });
  await writeStatus(env, { id: deviceId, name, lastSeen: Date.now() });
  return json({ ok: true });
}

async function setCamera(request, env) {
  const body = await request.json();
  const deviceId = body.device_id || "";
  if (!DEVICE_ID.test(deviceId)) return json({ error: "Device id is not valid" }, 400);
  const camera = Boolean(body.enabled);
  const control = await readControl(env, deviceId);
  control.camera = camera;
  await writeControl(env, deviceId, control);
  if (!camera) await env.RECORDINGS.delete(`camera/${deviceId}.jpg`);
  return json({ ok: true, camera });
}

async function setLock(request, env) {
  const body = await request.json();
  const deviceId = body.device_id || "";
  if (!DEVICE_ID.test(deviceId)) return json({ error: "Device id is not valid" }, 400);
  const control = await readControl(env, deviceId);
  const locked = Boolean(body.locked);
  const seen = Number(body.lock_seq);
  if (!locked && Number.isFinite(seen) && seen !== Number(control.lockSeq || 0)) {
    return json({ ok: true, locked: control.locked, lockSeq: control.lockSeq, stale: true });
  }
  control.locked = locked;
  control.lockSeq = Number(control.lockSeq || 0) + 1;
  await writeControl(env, deviceId, control);
  return json({ ok: true, locked: control.locked, lockSeq: control.lockSeq });
}

async function saveCamera(request, env) {
  const deviceId = request.headers.get("X-Device-Id") || "";
  if (!DEVICE_ID.test(deviceId)) return json({ error: "Device id is not valid" }, 400);
  const control = await readControl(env, deviceId);
  if (!control.camera) return json({ ok: true, ignored: true });
  const jpeg = await readJpeg(request);
  if (!jpeg) return json({ error: "Picture is not valid" }, 400);
  await env.RECORDINGS.put(`camera/${deviceId}.jpg`, jpeg, {
    httpMetadata: { contentType: "image/jpeg" },
  });
  return json({ ok: true });
}

async function readJpeg(request) {
  const bytes = await request.arrayBuffer();
  if (bytes.byteLength < 500 || bytes.byteLength > 2 * 1024 * 1024) return null;
  return bytes;
}

function imageResponse(object, missing) {
  if (!object || !object.body || object.size < 500) return json({ error: missing }, 404);
  const headers = new Headers(cors);
  headers.set("Content-Type", "image/jpeg");
  headers.set("Cache-Control", "no-store");
  headers.set("Content-Length", String(object.size));
  return new Response(object.body, { headers });
}

async function readCamera(env, deviceId) {
  if (!DEVICE_ID.test(deviceId)) return json({ error: "Not found" }, 404);
  const object = await env.RECORDINGS.get(`camera/${deviceId}.jpg`);
  return imageResponse(object, "Front camera is off");
}

async function readControl(env, deviceId) {
  const object = await env.RECORDINGS.get(`control/${deviceId}.json`);
  if (!object) return { camera: false, locked: false, lockSeq: 0 };
  const parsed = await object.json();
  return {
    camera: Boolean(parsed.camera),
    locked: Boolean(parsed.locked),
    lockSeq: Number(parsed.lockSeq || 0),
  };
}

async function writeControl(env, deviceId, control) {
  await env.RECORDINGS.put(`control/${deviceId}.json`, JSON.stringify({
    camera: Boolean(control.camera),
    locked: Boolean(control.locked),
    lockSeq: Number(control.lockSeq || 0),
  }), {
    httpMetadata: { contentType: "application/json" },
  });
}

async function readLive(env, deviceId) {
  if (!DEVICE_ID.test(deviceId)) return json({ error: "Not found" }, 404);
  const object = await env.RECORDINGS.get(`live/${deviceId}.jpg`);
  return imageResponse(object, "No live picture yet");
}

async function saveSegment(request, env) {
  const form = await request.formData();
  const deviceId = String(form.get("device_id") || "");
  const deviceName = cleanName(String(form.get("device_name") || ""));
  const startedAtMs = Number(form.get("started_at_ms"));
  const endedAtMs = Number(form.get("ended_at_ms"));
  const localDay = String(form.get("local_day") || "");
  const file = form.get("file");
  if (!DEVICE_ID.test(deviceId)) return json({ error: "Device id is not valid" }, 400);
  if (!DAY.test(localDay)) return json({ error: "Day is not valid" }, 400);
  if (!Number.isFinite(startedAtMs) || !Number.isFinite(endedAtMs)) return json({ error: "Clip time is not valid" }, 400);
  const duration = endedAtMs - startedAtMs;
  if (duration < 1000 || duration > 30 * 60 * 1000) return json({ error: "Clip length is not valid" }, 400);
  if (!file || typeof file.arrayBuffer !== "function") return json({ error: "Clip file is missing" }, 400);
  if (file.size > MAX_BYTES) return json({ error: "Clip is too large" }, 413);
  if (file.size < 1024) return json({ error: "Clip is empty" }, 400);
  const kind = String(form.get("kind") || "screen") === "camera" ? "camera" : "screen";
  const id = crypto.randomUUID().replaceAll("-", "");
  const key = `clips/${deviceId}/${localDay}/${id}.mp4`;
  await env.RECORDINGS.put(key, await file.arrayBuffer(), {
    httpMetadata: { contentType: "video/mp4" },
  });
  const segment = { id, deviceId, deviceName, startedAtMs, endedAtMs, bytes: file.size, key, kind };
  await env.RECORDINGS.put(`index/${deviceId}/${localDay}/${id}.json`, JSON.stringify(segment), {
    httpMetadata: { contentType: "application/json" },
  });
  await env.RECORDINGS.put(`ids/${id}`, key, { httpMetadata: { contentType: "text/plain" } });
  await writeStatus(env, { id: deviceId, name: deviceName, recording: false, lastSeen: Date.now() });
  await rememberInFirebase(env, segment);
  return json({ ok: true, id });
}

async function listDevices(env) {
  const objects = await listAll(env, "status/");
  const devices = [];
  for (const item of objects) {
    const object = await env.RECORDINGS.get(item.key);
    if (!object) continue;
    const device = await object.json();
    device.recording = Boolean(device.recording) && Date.now() - Number(device.lastSeen || 0) < 90_000;
    if (DEVICE_ID.test(device.id || "")) {
      const control = await readControl(env, device.id);
      device.camera = Boolean(control.camera);
      device.locked = Boolean(control.locked);
    } else {
      device.camera = false;
      device.locked = false;
    }
    devices.push(device);
  }
  devices.sort((a, b) => String(a.name).localeCompare(String(b.name)));
  return json({ devices });
}

async function listSegments(env, url) {
  const deviceId = url.searchParams.get("device_id") || "";
  const date = url.searchParams.get("date") || "";
  if (!DEVICE_ID.test(deviceId) || !DAY.test(date)) return json({ error: "Not valid" }, 400);
  const objects = await listAll(env, `index/${deviceId}/${date}/`);
  const segments = [];
  for (const item of objects) {
    const object = await env.RECORDINGS.get(item.key);
    if (object) segments.push(await object.json());
  }
  segments.sort((a, b) => a.startedAtMs - b.startedAtMs);
  const total = segments.reduce((sum, segment) => sum + Number(segment.bytes || 0), 0);
  return json({ deviceId, date, totalBytes: total, segments });
}

async function deleteDay(env, url) {
  const deviceId = url.searchParams.get("device_id") || "";
  const date = url.searchParams.get("date") || "";
  if (!DEVICE_ID.test(deviceId) || !DAY.test(date)) return json({ error: "Not valid" }, 400);
  const objects = await listAll(env, `index/${deviceId}/${date}/`);
  let removed = 0;
  for (const item of objects) {
    const object = await env.RECORDINGS.get(item.key);
    if (object) {
      const segment = await object.json();
      if (String(segment.key || "").startsWith("clips/")) await env.RECORDINGS.delete(segment.key);
      if (SEGMENT_ID.test(segment.id || "")) await env.RECORDINGS.delete(`ids/${segment.id}`);
    }
    await env.RECORDINGS.delete(item.key);
    removed += 1;
  }
  return json({ ok: true, removed });
}

async function deleteSegment(env, rawId) {
  const id = decodeURIComponent(rawId);
  if (!SEGMENT_ID.test(id)) return json({ error: "Not found" }, 404);
  const pointer = await env.RECORDINGS.get(`ids/${id}`);
  if (!pointer) return json({ error: "Not found" }, 404);
  const key = (await pointer.text()).trim();
  const match = /^clips\/([^/]+)\/(\d{4}-\d{2}-\d{2})\/([a-f0-9]{32})\.mp4$/.exec(key);
  if (!match) return json({ error: "Not found" }, 404);
  await env.RECORDINGS.delete(key);
  await env.RECORDINGS.delete(`index/${match[1]}/${match[2]}/${match[3]}.json`);
  await env.RECORDINGS.delete(`ids/${id}`);
  return json({ ok: true });
}

async function readMedia(request, env, id) {
  if (!SEGMENT_ID.test(id)) return json({ error: "Not found" }, 404);
  const pointer = await env.RECORDINGS.get(`ids/${id}`);
  if (!pointer) return json({ error: "Not found" }, 404);
  const key = (await pointer.text()).trim();
  if (!key.startsWith("clips/")) return json({ error: "Not found" }, 404);
  return rangedObject(request, env, key, "video/mp4");
}

async function rangedObject(request, env, key, contentType) {
  const head = await env.RECORDINGS.head(key);
  if (!head) return json({ error: "Not found" }, 404);
  const size = head.size;
  const range = request.headers.get("Range");
  const headers = new Headers(cors);
  headers.set("Content-Type", contentType);
  headers.set("Accept-Ranges", "bytes");
  headers.set("Cache-Control", "private, max-age=86400");
  if (range) {
    const match = /bytes=(\d+)-(\d*)/.exec(range);
    if (!match) return new Response(null, { status: 416, headers });
    const start = Number(match[1]);
    const end = match[2] ? Number(match[2]) : size - 1;
    if (start > end || start >= size) return new Response(null, { status: 416, headers });
    const object = await env.RECORDINGS.get(key, { range: { offset: start, length: end - start + 1 } });
    headers.set("Content-Range", `bytes ${start}-${end}/${size}`);
    headers.set("Content-Length", String(end - start + 1));
    return new Response(object.body, { status: 206, headers });
  }
  const object = await env.RECORDINGS.get(key);
  return new Response(object.body, { headers });
}

async function writeStatus(env, device) {
  const existing = await env.RECORDINGS.get(`status/${device.id}.json`);
  const previous = existing ? await existing.json() : {};
  const next = { ...previous, ...device };
  await env.RECORDINGS.put(`status/${device.id}.json`, JSON.stringify(next), {
    httpMetadata: { contentType: "application/json" },
  });
}

async function rememberInFirebase(env, segment) {
  if (!env.FIREBASE_PROJECT_ID || !env.FIREBASE_TOKEN) return;
  const url = `https://firestore.googleapis.com/v1/projects/${env.FIREBASE_PROJECT_ID}/databases/(default)/documents/segments?documentId=${segment.id}`;
  await fetch(url, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${env.FIREBASE_TOKEN}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      fields: {
        deviceId: { stringValue: segment.deviceId },
        deviceName: { stringValue: segment.deviceName },
        startedAtMs: { integerValue: String(segment.startedAtMs) },
        endedAtMs: { integerValue: String(segment.endedAtMs) },
        bytes: { integerValue: String(segment.bytes) },
        key: { stringValue: segment.key },
      },
    }),
  });
}

async function listAll(env, prefix) {
  const objects = [];
  let cursor;
  do {
    const page = await env.RECORDINGS.list({ prefix, cursor });
    objects.push(...page.objects);
    cursor = page.truncated ? page.cursor : undefined;
  } while (cursor);
  return objects;
}

function cleanName(name) {
  const cleaned = String(name || "").replace(/[^\x20-\x7e]/g, "").trim().slice(0, 80);
  return cleaned || "Register";
}

function json(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", ...cors },
  });
}
