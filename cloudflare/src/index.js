const DEVICE_ID = /^[A-Za-z0-9_-]{8,64}$/;
const DAY = /^\d{4}-\d{2}-\d{2}$/;
const SEGMENT_ID = /^[a-f0-9]{32}$/;
const MAX_BYTES = 80 * 1024 * 1024;

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "Authorization, Content-Type, X-Device-Id, X-Device-Name",
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

async function handleApi(request, env, url) {
  if (!authorized(request, env)) {
    return json({ error: "Sign in" }, 401);
  }
  if (request.method === "GET" && url.pathname === "/api/check-token") return json({ ok: true });
  if (request.method === "POST" && url.pathname === "/api/login") return json({ ok: true });
  if (request.method === "GET" && url.pathname === "/api/devices") return listDevices(env);
  if (request.method === "GET" && url.pathname === "/api/segments") return listSegments(env, url);
  if (request.method === "POST" && url.pathname === "/api/heartbeat") return heartbeat(request, env);
  if (request.method === "POST" && url.pathname === "/api/live") return saveLive(request, env);
  if (request.method === "GET" && url.pathname.startsWith("/api/live/")) {
    return readLive(env, decodeURIComponent(url.pathname.slice("/api/live/".length)));
  }
  if (request.method === "POST" && url.pathname === "/api/camera") return setCamera(request, env);
  if (request.method === "POST" && url.pathname === "/api/camera-frame") return saveCamera(request, env);
  if (request.method === "GET" && url.pathname.startsWith("/api/camera/")) {
    return readCamera(env, decodeURIComponent(url.pathname.slice("/api/camera/".length)));
  }
  if (request.method === "POST" && url.pathname === "/api/segments") return saveSegment(request, env);
  if (request.method === "GET" && url.pathname.startsWith("/api/media/")) {
    return readMedia(request, env, url.pathname.slice("/api/media/".length));
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
  return json({ ok: true, camera: Boolean(control.camera) });
}

async function saveLive(request, env) {
  const deviceId = request.headers.get("X-Device-Id") || "";
  if (!DEVICE_ID.test(deviceId)) return json({ error: "Device id is not valid" }, 400);
  const name = cleanName(request.headers.get("X-Device-Name") || "Register");
  await env.RECORDINGS.put(`live/${deviceId}.jpg`, request.body, {
    httpMetadata: { contentType: "image/jpeg" },
  });
  await writeStatus(env, { id: deviceId, name, recording: true, lastSeen: Date.now() });
  return json({ ok: true });
}

async function setCamera(request, env) {
  const body = await request.json();
  const deviceId = body.device_id || "";
  if (!DEVICE_ID.test(deviceId)) return json({ error: "Device id is not valid" }, 400);
  const camera = Boolean(body.enabled);
  await env.RECORDINGS.put(`control/${deviceId}.json`, JSON.stringify({ camera }), {
    httpMetadata: { contentType: "application/json" },
  });
  if (!camera) await env.RECORDINGS.delete(`camera/${deviceId}.jpg`);
  return json({ ok: true, camera });
}

async function saveCamera(request, env) {
  const deviceId = request.headers.get("X-Device-Id") || "";
  if (!DEVICE_ID.test(deviceId)) return json({ error: "Device id is not valid" }, 400);
  const control = await readControl(env, deviceId);
  if (!control.camera) return json({ ok: true, ignored: true });
  const length = Number(request.headers.get("Content-Length") || "0");
  if (length < 500 || length > 2 * 1024 * 1024) return json({ error: "Picture is not valid" }, 400);
  await env.RECORDINGS.put(`camera/${deviceId}.jpg`, request.body, {
    httpMetadata: { contentType: "image/jpeg" },
  });
  return json({ ok: true });
}

async function readCamera(env, deviceId) {
  if (!DEVICE_ID.test(deviceId)) return json({ error: "Not found" }, 404);
  const object = await env.RECORDINGS.get(`camera/${deviceId}.jpg`);
  if (!object) return json({ error: "Front camera is off" }, 404);
  const headers = new Headers(cors);
  headers.set("Content-Type", "image/jpeg");
  headers.set("Cache-Control", "no-store");
  return new Response(object.body, { headers });
}

async function readControl(env, deviceId) {
  const object = await env.RECORDINGS.get(`control/${deviceId}.json`);
  if (!object) return { camera: false };
  return object.json();
}

async function readLive(env, deviceId) {
  if (!DEVICE_ID.test(deviceId)) return json({ error: "Not found" }, 404);
  const object = await env.RECORDINGS.get(`live/${deviceId}.jpg`);
  if (!object) return json({ error: "No live picture yet" }, 404);
  const headers = new Headers(cors);
  headers.set("Content-Type", "image/jpeg");
  headers.set("Cache-Control", "no-store");
  return new Response(object.body, { headers });
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
  const id = crypto.randomUUID().replaceAll("-", "");
  const key = `clips/${deviceId}/${localDay}/${id}.mp4`;
  await env.RECORDINGS.put(key, await file.arrayBuffer(), {
    httpMetadata: { contentType: "video/mp4" },
  });
  const segment = { id, deviceId, deviceName, startedAtMs, endedAtMs, bytes: file.size, key };
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
    } else {
      device.camera = false;
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
