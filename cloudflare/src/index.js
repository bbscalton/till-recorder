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
  async fetch(request, env, ctx) {
    if (request.method === "OPTIONS") return new Response(null, { headers: cors });
    const url = new URL(request.url);
    if (url.pathname.startsWith("/api/")) {
      return handleApi(request, env, url, ctx);
    }
    return env.ASSETS.fetch(request);
  },
};

export class LiveRelay {
  constructor(ctx) {
    this.ctx = ctx;
    this.viewers = new Set();
    this.init = null;
    this.codec = "avc1.42E01E";
    this.recent = [];
  }

  async fetch(request) {
    if (!this.loaded) {
      this.loaded = true;
      const saved = await this.ctx.storage.get("init");
      if (saved && saved.init) {
        this.codec = saved.codec || this.codec;
        this.init = saved.init;
      }
    }
    if (request.headers.get("Upgrade") === "websocket") {
      const pair = new WebSocketPair();
      const client = pair[0];
      const server = pair[1];
      server.accept();
      this.viewers.add(server);
      const drop = () => this.viewers.delete(server);
      server.addEventListener("close", drop);
      server.addEventListener("error", drop);
      if (this.init) this.sendPair(server, { type: "init", codec: this.codec, seq: 0 }, this.init.slice(0));
      const backlog = [...this.recent].sort((a, b) => a.meta.seq - b.meta.seq);
      for (const item of backlog) this.sendPair(server, item.meta, item.bytes.slice(0));
      return new Response(null, { status: 101, webSocket: client });
    }
    if (request.method !== "POST") return new Response("Not found", { status: 404 });
    const seq = Number(request.headers.get("X-Stream-Seq"));
    const codec = request.headers.get("X-Stream-Codec") || this.codec;
    const bytes = await request.arrayBuffer();
    if (!bytes.byteLength) return new Response("ok");
    this.codec = codec;
    const meta = { type: seq === 0 ? "init" : "media", codec, seq };
    const stored = bytes.slice(0);
    if (seq === 0) {
      this.init = stored;
      this.recent = [];
      this.ctx.waitUntil(this.ctx.storage.put("init", { codec: this.codec, init: stored }).catch(() => {}));
    } else {
      this.recent.push({ meta, bytes: stored });
      if (this.recent.length > 90) this.recent.shift();
    }
    for (const viewer of this.viewers) this.sendPair(viewer, meta, stored.slice(0));
    return new Response("ok");
  }

  sendPair(socket, meta, bytes) {
    try {
      socket.send(JSON.stringify(meta));
      socket.send(bytes);
    } catch (error) {
      this.viewers.delete(socket);
    }
  }
}

const PAIR_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";

async function handleApi(request, env, url, ctx) {
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
  if (request.method === "POST" && url.pathname === "/api/stream") return saveStream(request, env, ctx);
  if (request.method === "GET" && url.pathname === "/api/live-ws") return openLive(request, env, url);
  if (request.method === "GET" && url.pathname.startsWith("/api/stream/")) return readStream(env, url);
  if (request.method === "GET" && url.pathname.startsWith("/api/live/")) {
    return readLive(env, decodeURIComponent(url.pathname.slice("/api/live/".length)));
  }
  if (request.method === "GET" && url.pathname === "/api/rtc") return readRtc(env, url);
  if (request.method === "POST" && url.pathname === "/api/rtc") return writeRtc(request, env);
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

async function openLive(request, env, url) {
  const deviceId = url.searchParams.get("device_id") || "";
  const kind = url.searchParams.get("kind") || "";
  if (!DEVICE_ID.test(deviceId) || (kind !== "screen" && kind !== "camera")) {
    return json({ error: "Not found" }, 404);
  }
  if (!env.LIVE_RELAY) return json({ error: "Live relay is not ready" }, 503);
  const stub = env.LIVE_RELAY.get(env.LIVE_RELAY.idFromName(`${deviceId}:${kind}`));
  return stub.fetch(request);
}

async function pushLive(env, deviceId, kind, seq, codec, body) {
  if (!env.LIVE_RELAY || !body.byteLength) return;
  const stub = env.LIVE_RELAY.get(env.LIVE_RELAY.idFromName(`${deviceId}:${kind}`));
  const payload = body.slice(0);
  const send = (bytes) => stub.fetch("https://live/push", {
    method: "POST",
    headers: { "X-Stream-Seq": String(seq), "X-Stream-Codec": codec },
    body: bytes,
  });
  let response = await send(payload.slice(0)).catch(() => null);
  if (!response || !response.ok) response = await send(payload.slice(0)).catch(() => null);
}

async function saveStream(request, env, ctx) {
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
  const relayBody = body.slice(0);
  const archiveBody = body.slice(0);
  const forwarded = pushLive(env, deviceId, kind, seq, codec, relayBody);
  const archived = (async () => {
    await env.RECORDINGS.put(key, archiveBody, { httpMetadata: { contentType: "video/mp4" } });
    await env.RECORDINGS.put(`${base}/head.json`, JSON.stringify({
      seq,
      codec,
      updatedAt: Date.now(),
    }), { httpMetadata: { contentType: "application/json" } });
    if (seq > 90) await env.RECORDINGS.delete(`${base}/${seq - 90}`);
  })();
  if (seq === 0) await archived;
  else if (ctx) ctx.waitUntil(archived);
  else await archived;
  await forwarded;
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
  try {
    return await storeSegment(request, env);
  } catch (error) {
    const message = String(error && error.stack ? error.stack : error).split("\n")[0].slice(0, 180);
    return json({ error: message }, 500);
  }
}

async function storeSegment(request, env) {
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
  for (let index = 0; index < objects.length; index += 16) {
    const rows = await Promise.all(objects.slice(index, index + 16).map(async (item) => {
      const object = await env.RECORDINGS.get(item.key);
      return object ? object.json() : null;
    }));
    for (const row of rows) if (row) segments.push(row);
  }
  segments.sort((a, b) => a.startedAtMs - b.startedAtMs);
  const total = segments.reduce((sum, segment) => sum + Number(segment.bytes || 0), 0);
  return json({ deviceId, date, totalBytes: total, segments }, 200, true);
}

async function deleteDay(env, url) {
  const deviceId = url.searchParams.get("device_id") || "";
  const date = url.searchParams.get("date") || "";
  if (!DEVICE_ID.test(deviceId) || !DAY.test(date)) return json({ error: "Not valid" }, 400);
  const [indexes, clips] = await Promise.all([
    listAll(env, `index/${deviceId}/${date}/`),
    listAll(env, `clips/${deviceId}/${date}/`),
  ]);
  const keys = [];
  for (const item of indexes) {
    keys.push(item.key);
    const name = item.key.split("/").pop() || "";
    const id = name.endsWith(".json") ? name.slice(0, -5) : "";
    if (SEGMENT_ID.test(id)) keys.push(`ids/${id}`);
  }
  for (const item of clips) keys.push(item.key);
  await deleteKeys(env, keys);
  return json({ ok: true, removed: indexes.length }, 200, true);
}

async function deleteSegment(env, rawId) {
  const id = decodeURIComponent(rawId);
  if (!SEGMENT_ID.test(id)) return json({ error: "Not found" }, 404);
  const pointer = await env.RECORDINGS.get(`ids/${id}`);
  if (!pointer) return json({ error: "Not found" }, 404);
  const key = (await pointer.text()).trim();
  const match = /^clips\/([^/]+)\/(\d{4}-\d{2}-\d{2})\/([a-f0-9]{32})\.mp4$/.exec(key);
  if (!match) return json({ error: "Not found" }, 404);
  await deleteKeys(env, [key, `index/${match[1]}/${match[2]}/${match[3]}.json`, `ids/${id}`]);
  return json({ ok: true }, 200, true);
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
  headers.set("Cache-Control", "private, no-store");
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

const RTC_KIND = /^(screen|camera)$/;
const RTC_SESSION = /^[a-zA-Z0-9]{16,64}$/;

function rtcJson(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", "Cache-Control": "no-store", ...cors },
  });
}

function emptyRtc() {
  return { session: "", offer: null, answer: null, viewerIce: [], phoneIce: [], updatedAt: 0 };
}

function normalizeDesc(value, expected) {
  if (!value || typeof value !== "object") return null;
  const type = String(value.type || "");
  const sdp = String(value.sdp || "");
  if (type !== expected || sdp.length < 20 || sdp.length > 50000) return null;
  return { type, sdp };
}

function normalizeIce(value) {
  if (!Array.isArray(value)) return [];
  const ice = [];
  for (const item of value.slice(0, 40)) {
    if (!item || typeof item !== "object") continue;
    const candidate = String(item.candidate || "");
    if (!candidate.startsWith("candidate:") || candidate.length > 2000) continue;
    const index = Number(item.sdpMLineIndex);
    ice.push({
      candidate,
      sdpMid: item.sdpMid == null ? null : String(item.sdpMid).slice(0, 32),
      sdpMLineIndex: Number.isInteger(index) && index >= 0 && index < 8 ? index : 0,
    });
  }
  return ice;
}

async function readRtcKind(env, deviceId, kind) {
  const base = `rtc/${deviceId}/${kind}`;
  const [metaObj, offerObj, answerObj, viewerObj, phoneObj] = await Promise.all([
    env.RECORDINGS.get(`${base}/meta.json`),
    env.RECORDINGS.get(`${base}/offer.json`),
    env.RECORDINGS.get(`${base}/answer.json`),
    env.RECORDINGS.get(`${base}/viewer-ice.json`),
    env.RECORDINGS.get(`${base}/phone-ice.json`),
  ]);
  if (!metaObj) return emptyRtc();
  const meta = await metaObj.json();
  const session = RTC_SESSION.test(meta.session || "") ? meta.session : "";
  if (!session) return emptyRtc();
  return {
    session,
    offer: offerObj ? await offerObj.json() : null,
    answer: answerObj ? await answerObj.json() : null,
    viewerIce: viewerObj ? await viewerObj.json() : [],
    phoneIce: phoneObj ? await phoneObj.json() : [],
    updatedAt: Number(meta.updatedAt || 0),
  };
}

async function readRtc(env, url) {
  const deviceId = url.searchParams.get("device_id") || "";
  const kind = url.searchParams.get("kind") || "";
  if (!DEVICE_ID.test(deviceId)) return rtcJson({ error: "Device id is not valid" }, 400);
  if (kind) {
    if (!RTC_KIND.test(kind)) return rtcJson({ error: "Stream is not valid" }, 400);
    return rtcJson(await readRtcKind(env, deviceId, kind));
  }
  const [screen, camera] = await Promise.all([
    readRtcKind(env, deviceId, "screen"),
    readRtcKind(env, deviceId, "camera"),
  ]);
  return rtcJson({ screen, camera });
}

async function writeRtc(request, env) {
  const body = await request.json().catch(() => null);
  if (!body) return rtcJson({ error: "Not valid" }, 400);
  const deviceId = body.device_id || "";
  const kind = body.kind || "";
  const role = body.role || "";
  const session = String(body.session || "");
  if (!DEVICE_ID.test(deviceId) || !RTC_KIND.test(kind) || !RTC_SESSION.test(session)) {
    return rtcJson({ error: "Not valid" }, 400);
  }
  if (role !== "viewer" && role !== "phone") return rtcJson({ error: "Not valid" }, 400);
  const base = `rtc/${deviceId}/${kind}`;
  const metaObj = await env.RECORDINGS.get(`${base}/meta.json`);
  const meta = metaObj ? await metaObj.json() : {};
  const jsonHeaders = { httpMetadata: { contentType: "application/json" } };
  if (role === "viewer") {
    if (meta.session !== session) {
      await env.RECORDINGS.delete(`${base}/answer.json`);
      await env.RECORDINGS.delete(`${base}/phone-ice.json`);
    }
    const offer = body.offer ? normalizeDesc(body.offer, "offer") : null;
    if (body.offer && !offer) return rtcJson({ error: "Offer is not valid" }, 400);
    if (offer) await env.RECORDINGS.put(`${base}/offer.json`, JSON.stringify(offer), jsonHeaders);
    if (body.ice) {
      await env.RECORDINGS.put(`${base}/viewer-ice.json`, JSON.stringify(normalizeIce(body.ice)), jsonHeaders);
    }
    await env.RECORDINGS.put(`${base}/meta.json`, JSON.stringify({ session, updatedAt: Date.now() }), jsonHeaders);
    return rtcJson({ ok: true });
  }
  if (meta.session !== session) return rtcJson({ ok: false, stale: true });
  const answer = body.answer ? normalizeDesc(body.answer, "answer") : null;
  if (body.answer && !answer) return rtcJson({ error: "Answer is not valid" }, 400);
  if (answer) await env.RECORDINGS.put(`${base}/answer.json`, JSON.stringify(answer), jsonHeaders);
  if (body.ice) {
    await env.RECORDINGS.put(`${base}/phone-ice.json`, JSON.stringify(normalizeIce(body.ice)), jsonHeaders);
  }
  return rtcJson({ ok: true });
}

function json(body, status = 200, fresh = false) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "Content-Type": "application/json",
      ...(fresh ? { "Cache-Control": "no-store" } : {}),
      ...cors,
    },
  });
}

async function deleteKeys(env, keys) {
  const unique = [...new Set(keys.filter(Boolean))];
  for (let index = 0; index < unique.length; index += 1000) {
    const batch = unique.slice(index, index + 1000);
    if (batch.length === 1) await env.RECORDINGS.delete(batch[0]);
    else if (batch.length) await env.RECORDINGS.delete(batch);
  }
}
