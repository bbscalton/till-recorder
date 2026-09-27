const worker = (window.TILL_WORKER_URL || "").replace(/\/$/, "");
const loginView = document.getElementById("loginView");
const appView = document.getElementById("appView");
const live = document.getElementById("live");
const camera = document.getElementById("camera");
const cameraToggle = document.getElementById("cameraToggle");
const cameraHint = document.getElementById("cameraHint");
const lockToggle = document.getElementById("lockToggle");
const lockHint = document.getElementById("lockHint");
const deleteClip = document.getElementById("deleteClip");
const deleteDay = document.getElementById("deleteDay");
const player = document.getElementById("player");
const deviceList = document.getElementById("deviceList");
const dayInput = document.getElementById("day");
const daySize = document.getElementById("daySize");
const clock = document.getElementById("clock");
const track = document.getElementById("track");
const playhead = document.getElementById("playhead");
const liveState = document.getElementById("liveState");
let token = sessionStorage.getItem("till-token") || "";
let devices = [];
let selectedId = "";
let segments = [];
let dayGeneration = 0;
let active = null;
let playheadMs = null;
let pairTimer = 0;

function todayIso() {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}-${String(now.getDate()).padStart(2, "0")}`;
}

function formatClock(ms) {
  return new Date(ms).toLocaleTimeString([], { hour: "numeric", minute: "2-digit", second: "2-digit" });
}

  async function api(path, options) {
  const response = await fetch(`${worker}${path}`, {
    cache: "no-store",
    ...options,
    headers: { Authorization: `Bearer ${token}`, ...(options && options.headers) },
  });
  if (response.status === 401) {
    token = "";
    sessionStorage.removeItem("till-token");
    showLogin();
    throw new Error("auth");
  }
  if (!response.ok) throw new Error(await response.text());
  return response.json();
}

function showLogin() {
  loginView.hidden = false;
  appView.hidden = true;
}

function showApp() {
  loginView.hidden = true;
  appView.hidden = false;
}

document.getElementById("loginForm").addEventListener("submit", async (event) => {
  event.preventDefault();
  token = document.getElementById("token").value.trim();
  const response = await fetch(`${worker}/api/check-token`, { headers: { Authorization: `Bearer ${token}` } });
  if (!response.ok) {
    document.getElementById("loginError").hidden = false;
    document.getElementById("loginError").textContent = "That token does not match the recorder.";
    return;
  }
  sessionStorage.setItem("till-token", token);
  document.getElementById("token").value = "";
  showApp();
  dayInput.value = todayIso();
  await refresh();
});

cameraToggle.addEventListener("click", async () => {
  if (!selectedId || cameraToggle.disabled) return;
  const current = devices.find((device) => device.id === selectedId);
  const enabled = !(current && current.camera);
  cameraToggle.disabled = true;
  try {
    await api("/api/camera", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ device_id: selectedId, enabled }),
    });
    await refresh();
  } catch (error) {
    if (error.message !== "auth") cameraHint.textContent = "Could not change the front camera. Try again.";
  } finally {
    cameraToggle.disabled = !selectedId;
  }
});

lockToggle.addEventListener("click", async () => {
  if (!selectedId || lockToggle.disabled) return;
  const current = devices.find((device) => device.id === selectedId);
  const locked = !(current && current.locked);
  lockToggle.disabled = true;
  try {
    await api("/api/lock", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ device_id: selectedId, locked }),
    });
    await refresh();
  } catch (error) {
    if (error.message !== "auth") lockHint.textContent = "Could not change the lock. Try again.";
  } finally {
    lockToggle.disabled = !selectedId;
  }
});

document.getElementById("pairButton").addEventListener("click", async () => {
  const box = document.getElementById("pairBox");
  const code = document.getElementById("pairCode");
  const status = document.getElementById("pairStatus");
  clearInterval(pairTimer);
  const created = await api("/api/pair-codes", { method: "POST" });
  code.textContent = created.display || created.code;
  status.textContent = "Enter this code in Till Recorder on the tablet. It works for 10 minutes.";
  box.hidden = false;
  pairTimer = setInterval(async () => {
    try {
      const state = await api(`/api/pair-codes/${created.code}`);
      if (state.claimed) {
        clearInterval(pairTimer);
        status.textContent = `${state.name || "Register"} is connected.`;
        await refresh();
      } else if (state.expired) {
        clearInterval(pairTimer);
        status.textContent = "That code expired. Pair again.";
      }
    } catch (error) {
      if (error.message === "auth") clearInterval(pairTimer);
    }
  }, 2000);
});

dayInput.addEventListener("change", () => loadDay());
document.getElementById("playDay").addEventListener("click", () => {
  if (segments[0]) playAt(segments[0].startedAtMs + 50);
});
document.getElementById("backThirty").addEventListener("click", () => nudge(-30000));
document.getElementById("forwardThirty").addEventListener("click", () => nudge(30000));
deleteClip.addEventListener("click", async () => {
  if (!active) return;
  const label = active.kind === "camera" ? "front-camera" : "screen";
  if (!window.confirm(`Delete this ${label} recording?`)) return;
  const id = active.id;
  deleteClip.disabled = true;
  try {
    await api(`/api/media/${id}`, { method: "DELETE" });
    segments = segments.filter((segment) => segment.id !== id);
    if (active && active.id === id) clearPlayer();
    document.getElementById("status").textContent = "Deleted that recording.";
    await loadDay();
  } catch (error) {
    if (error.message !== "auth") document.getElementById("status").textContent = "Could not delete that recording.";
  } finally {
    deleteClip.disabled = false;
  }
});
deleteDay.addEventListener("click", async () => {
  if (!selectedId) return;
  const day = dayInput.value || todayIso();
  if (!window.confirm(`Delete all recordings for ${day}?`)) return;
  deleteDay.disabled = true;
  try {
    const result = await api(`/api/segments?device_id=${encodeURIComponent(selectedId)}&date=${encodeURIComponent(day)}`, { method: "DELETE" });
    segments = [];
    clearPlayer();
    document.getElementById("status").textContent = `Deleted ${Number(result.removed || 0)} recording(s) for ${day}.`;
    await loadDay();
  } catch (error) {
    if (error.message !== "auth") document.getElementById("status").textContent = "Could not delete that day.";
  } finally {
    deleteDay.disabled = false;
  }
});
document.getElementById("timeline").addEventListener("click", (event) => {
  if (!segments.length) return;
  const rect = event.currentTarget.getBoundingClientRect();
  const ratio = Math.min(1, Math.max(0, (event.clientX - rect.left) / rect.width));
  const start = segments[0].startedAtMs;
  const end = segments[segments.length - 1].endedAtMs;
  playAt(start + ratio * (end - start));
});

player.addEventListener("timeupdate", () => {
  if (!active) return;
  playheadMs = active.startedAtMs + player.currentTime * 1000;
  clock.textContent = formatClock(playheadMs);
  placePlayhead();
});
player.addEventListener("ended", () => {
  if (!active) return;
  const next = segments.find((segment) => segment.startedAtMs >= active.endedAtMs - 200 && segment.id !== active.id);
  if (next && next.startedAtMs - active.endedAtMs < 3000) playAt(next.startedAtMs + 50);
});

let refreshInFlight = false;

async function refresh() {
  if (refreshInFlight) return;
  refreshInFlight = true;
  try {
    await refreshNow();
  } finally {
    refreshInFlight = false;
  }
}

async function refreshNow() {
  const payload = await api("/api/devices");
  devices = payload.devices;
  if (!selectedId && devices[0]) selectedId = devices[0].id;
  deviceList.replaceChildren();
  if (!devices.length) {
    const empty = document.createElement("p");
    empty.className = "hint";
    empty.textContent = "No register has checked in yet. Arm the tablet first.";
    deviceList.appendChild(empty);
  }
  for (const device of devices) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "device" + (device.id === selectedId ? " selected" : "") + (device.recording ? " live" : "");
    const name = document.createElement("span");
    name.textContent = device.name || "Register";
    const meta = document.createElement("small");
    meta.textContent = device.locked ? "Locked" : device.recording ? "Screen is active" : "Screen is still";
    button.append(name, meta);
    button.addEventListener("click", async () => {
      selectedId = device.id;
      await refresh();
      await loadDay();
    });
    deviceList.appendChild(button);
  }
  const current = devices.find((device) => device.id === selectedId);
  const locked = Boolean(current && current.locked);
  liveState.textContent = locked
    ? "Locked — the register cannot be used until the owner PIN is entered"
    : feedNote || (current && current.recording
      ? "Live video — a clip is being saved"
      : "Live video — a clip starts when the screen or the camera moves");
  lockToggle.textContent = locked ? "Unlock" : "Lock";
  lockToggle.classList.toggle("on", locked);
  lockToggle.disabled = !selectedId;
  lockHint.textContent = locked
    ? "Locked. Press Unlock here, or enter the owner PIN on the tablet."
    : "Locks this register until the owner PIN is entered on the tablet.";
  const cameraOn = Boolean(current && current.camera);
  cameraToggle.textContent = cameraOn ? "Turn off" : "Turn on";
  cameraToggle.classList.toggle("on", cameraOn);
  cameraToggle.disabled = !selectedId;
  camera.hidden = !cameraOn;
  cameraHint.hidden = cameraOn;
  if (!cameraOn) cameraHint.textContent = "Off. Turn it on to see the person at the register beside the screen.";
  if (selectedId) syncLive(selectedId, cameraOn);
  else {
    screenFeed.stop();
    cameraFeed.stop();
  }
  if (dayInput.value) await loadDay();
}

async function loadDay() {
  if (!selectedId) return;
  const generation = ++dayGeneration;
  const payload = await api(`/api/segments?device_id=${encodeURIComponent(selectedId)}&date=${encodeURIComponent(dayInput.value || todayIso())}`);
  if (generation !== dayGeneration) return;
  segments = payload.segments || [];
  daySize.textContent = segments.length
    ? `${segments.length} clip(s). Blue marks are the front camera.`
    : "Nothing was recorded on this day.";
  track.replaceChildren();
  deleteClip.hidden = !active || !segments.some((segment) => segment.id === active.id);
  if (!segments.length) return;
  const start = segments[0].startedAtMs;
  const end = segments[segments.length - 1].endedAtMs;
  const span = Math.max(1, end - start);
  for (const segment of segments) {
    const block = document.createElement("div");
    block.className = "segment" + (segment.kind === "camera" ? " camera" : "");
    block.dataset.id = segment.id;
    block.title = segment.kind === "camera" ? "Front camera" : "Register screen";
    block.addEventListener("click", (event) => {
      event.stopPropagation();
      playAt(segment.startedAtMs + 50);
    });
    block.style.left = `${((segment.startedAtMs - start) / span) * 100}%`;
    block.style.width = `${Math.max(0.4, ((segment.endedAtMs - segment.startedAtMs) / span) * 100)}%`;
    track.appendChild(block);
  }
}

function playAt(ms) {
  const segment = segments.find((item) => ms >= item.startedAtMs && ms < item.endedAtMs);
  if (!segment) return;
  const offset = Math.max(0, (ms - segment.startedAtMs) / 1000);
  if (!active || active.id !== segment.id) {
    active = segment;
    player.src = `${worker}/api/media/${segment.id}?access=${encodeURIComponent(token)}`;
    player.addEventListener("loadedmetadata", function seek() {
      player.currentTime = offset;
      player.removeEventListener("loadedmetadata", seek);
    });
    player.play().catch(() => {});
  } else {
    player.currentTime = offset;
    player.play().catch(() => {});
  }
  deleteClip.hidden = false;
}

function clearPlayer() {
  active = null;
  playheadMs = null;
  player.pause();
  player.removeAttribute("src");
  player.load();
  deleteClip.hidden = true;
  playhead.hidden = true;
}

function showPicture(img, url, onMiss) {
  const probe = new Image();
  probe.onload = () => {
    img.src = url;
    img.hidden = false;
  };
  probe.onerror = () => {
    if (!img.getAttribute("src") && onMiss) onMiss();
  };
  probe.src = url;
}

function nudge(delta) {
  const base = playheadMs == null ? (segments[0] ? segments[0].startedAtMs : 0) : playheadMs;
  playAt(base + delta);
}

function placePlayhead() {
  if (!segments.length || playheadMs == null) {
    playhead.hidden = true;
    return;
  }
  const start = segments[0].startedAtMs;
  const end = segments[segments.length - 1].endedAtMs;
  playhead.hidden = false;
  playhead.style.left = `${Math.min(100, Math.max(0, ((playheadMs - start) / Math.max(1, end - start)) * 100))}%`;
}

let feedNote = "";

class LiveFeed {
  constructor(video, kind, announce) {
    this.video = video;
    this.kind = kind;
    this.announce = announce;
    this.id = "";
    this.seq = 0;
    this.codec = "";
    this.timer = 0;
    this.busy = false;
    this.media = null;
    this.source = null;
    this.open = false;
  }

  start(id) {
    if (this.id === id && this.timer) return;
    this.stop();
    this.id = id;
    this.timer = setInterval(() => this.tick(), 400);
    this.tick();
  }

  stop() {
    clearInterval(this.timer);
    this.timer = 0;
    this.id = "";
    this.reset();
  }

  reset() {
    this.seq = 0;
    this.codec = "";
    this.open = false;
    this.source = null;
    if (this.media) {
      try { URL.revokeObjectURL(this.video.src); } catch (error) {}
      this.media = null;
    }
    this.video.removeAttribute("src");
    this.video.load();
  }

  async tick() {
    if (this.busy || !this.id || !token) return;
    this.busy = true;
    try {
      await this.pull();
    } catch (error) {
      this.note("Waiting for live video from the register.");
      if (!this.open) this.reset();
    } finally {
      this.busy = false;
    }
  }

  async pull() {
    const headResponse = await fetch(`${worker}/api/stream/${this.id}/${this.kind}/head?access=${encodeURIComponent(token)}&t=${Date.now()}`, { cache: "no-store" });
    if (!headResponse.ok) {
      this.note("Waiting for live video from the register.");
      return;
    }
    const head = await headResponse.json();
    if (!head.seq || Date.now() - Number(head.updatedAt || 0) > 8000) {
      this.note("Waiting for the next live video frame.");
      return;
    }
    if (!this.open || head.codec !== this.codec || head.seq < this.seq) {
      await this.attach(head.codec || "avc1.42E01E");
    }
    let from = this.seq + 1;
    if (head.seq - from > 30) from = Math.max(1, head.seq - 12);
    for (let seq = from; seq <= head.seq; seq += 1) {
      const bytes = await this.fetchChunk(seq === 0 ? "init" : String(seq));
      if (!bytes) break;
      try {
        await this.append(bytes);
        this.seq = seq;
      } catch (error) {
        break;
      }
    }
    this.edge();
    this.note("");
  }

  async attach(codec) {
    this.reset();
    this.codec = codec;
    if (typeof MediaSource === "undefined" || !MediaSource.isTypeSupported(`video/mp4; codecs="${codec}"`)) {
      throw new Error("This browser cannot play the live video.");
    }
    this.media = new MediaSource();
    this.video.muted = true;
    await new Promise((resolve, reject) => {
      this.media.addEventListener("sourceopen", () => resolve(), { once: true });
      this.video.src = URL.createObjectURL(this.media);
      setTimeout(() => reject(new Error("Live video did not open")), 4000);
    });
    this.source = this.media.addSourceBuffer(`video/mp4; codecs="${codec}"`);
    this.source.mode = "segments";
    const init = await this.fetchChunk("init");
    if (!init) throw new Error("No init");
    await this.append(init);
    this.open = true;
    this.seq = 0;
  }

  fetchChunk(name) {
    return fetch(`${worker}/api/stream/${this.id}/${this.kind}/${name}?access=${encodeURIComponent(token)}`, { cache: "no-store" })
      .then((response) => (response.ok ? response.arrayBuffer() : null));
  }

  append(bytes) {
    return new Promise((resolve, reject) => {
      const source = this.source;
      if (!source) {
        reject(new Error("No buffer"));
        return;
      }
      const done = () => {
        source.removeEventListener("updateend", done);
        source.removeEventListener("error", fail);
        resolve();
      };
      const fail = () => {
        source.removeEventListener("updateend", done);
        source.removeEventListener("error", fail);
        reject(new Error("Buffer error"));
      };
      source.addEventListener("updateend", done);
      source.addEventListener("error", fail);
      try {
        source.appendBuffer(bytes);
      } catch (error) {
        fail();
      }
    });
  }

  edge() {
    const video = this.video;
    if (!video.buffered.length) return;
    const end = video.buffered.end(video.buffered.length - 1);
    const start = video.buffered.start(video.buffered.length - 1);
    const lag = end - video.currentTime;
    if (video.currentTime < start || video.currentTime > end) {
      video.currentTime = start;
    } else if (lag > 8) {
      video.currentTime = Math.max(start, end - 2);
    }
    if (video.paused) video.play().catch(() => {});
    const source = this.source;
    if (source && !source.updating && video.buffered.length) {
      const oldest = video.buffered.start(0);
      if (end - oldest > 20 && video.currentTime - oldest > 8) {
        try { source.remove(oldest, video.currentTime - 4); } catch (error) {}
      }
    }
  }

  note(text) {
    if (!this.announce) return;
    feedNote = text;
    if (!document.getElementById("lockToggle").classList.contains("on")) {
      const state = document.getElementById("liveState");
      if (text) state.textContent = text;
    }
  }
}

function boxType(view, offset) {
  return String.fromCharCode(
    view.getUint8(offset + 4),
    view.getUint8(offset + 5),
    view.getUint8(offset + 6),
    view.getUint8(offset + 7)
  );
}

function trunDuration(view, offset, size) {
  if (size < 16) return 0;
  const flags = view.getUint32(offset + 8) & 0xffffff;
  const count = view.getUint32(offset + 12);
  let cursor = offset + 16;
  if (flags & 0x000001) cursor += 4;
  if (flags & 0x000004) cursor += 4;
  const hasDuration = flags & 0x000100;
  const hasSize = flags & 0x000200;
  const hasFlags = flags & 0x000400;
  const hasCto = flags & 0x000800;
  let total = 0;
  for (let i = 0; i < count && cursor + 4 <= offset + size; i++) {
    if (hasDuration) {
      total += view.getUint32(cursor);
      cursor += 4;
    }
    if (hasSize) cursor += 4;
    if (hasFlags) cursor += 4;
    if (hasCto) cursor += 4;
  }
  return total;
}

function retimeFragment(buffer, timeline) {
  const bytes = buffer.slice(0);
  const view = new DataView(bytes);
  let duration = 0;
  const walk = (start, end) => {
    let offset = start;
    while (offset + 8 <= end) {
      const size = view.getUint32(offset);
      if (size < 8 || offset + size > end) break;
      const type = boxType(view, offset);
      if (type === "moof" || type === "traf") walk(offset + 8, offset + size);
      else if (type === "tfdt" && view.getUint8(offset + 8) === 1 && size >= 20) {
        const hi = Math.floor(timeline / 4294967296);
        view.setUint32(offset + 12, hi);
        view.setUint32(offset + 16, timeline >>> 0);
      } else if (type === "trun") duration += trunDuration(view, offset, size);
      offset += size;
    }
  };
  walk(0, view.byteLength);
  return { bytes, duration: duration || 3780 };
}

function countSamples(buffer) {
  try {
    const view = new DataView(buffer);
    const walk = (start, end) => {
      let count = 0;
      let offset = start;
      while (offset + 8 <= end) {
        const size = view.getUint32(offset);
        if (size < 8 || offset + size > end) break;
        const type = String.fromCharCode(
          view.getUint8(offset + 4),
          view.getUint8(offset + 5),
          view.getUint8(offset + 6),
          view.getUint8(offset + 7)
        );
        if (type === "moof" || type === "traf") count += walk(offset + 8, offset + size);
        else if (type === "trun" && size >= 16) count += view.getUint32(offset + 12);
        offset += size;
      }
      return count;
    };
    return walk(0, view.byteLength);
  } catch (error) {
    return 0;
  }
}

class PushFeed {
  constructor(video, kind, announce) {
    this.video = video;
    this.kind = kind;
    this.announce = announce;
    this.id = "";
    this.socket = null;
    this.queue = [];
    this.busy = false;
    this.meta = null;
    this.media = null;
    this.source = null;
    this.codec = "";
    this.initBytes = null;
    this.timeline = 0;
    this.hold = new Map();
    this.nextSeq = null;
    this.gapSince = 0;
    this.gapTimer = 0;
    this.badAppends = 0;
    this.fpsLabel = "";
    this.watching = false;
  }

  start(id) {
    if (this.id === id && this.socket && this.socket.readyState <= 1) return;
    this.stop();
    this.id = id;
    this.generation = (this.generation || 0) + 1;
    this.open(id, this.generation);
  }

  async open(id, generation) {
    try {
      const head = await api(`/api/stream/${encodeURIComponent(id)}/${this.kind}/head`);
      const initRes = await fetch(`${worker}/api/stream/${encodeURIComponent(id)}/${this.kind}/init?access=${encodeURIComponent(token)}`, { cache: "no-store" });
      if (this.generation !== generation) return;
      if (head && initRes.ok) await this.attach(head.codec || "avc1.42E01E", await initRes.arrayBuffer());
    } catch (error) {
      if (this.generation === generation) this.note("Waiting for live video from the register.");
    }
    if (this.generation !== generation) return;
    const url = `${worker.replace(/^http/, "ws")}/api/live-ws?device_id=${encodeURIComponent(id)}&kind=${this.kind}&access=${encodeURIComponent(token)}`;
    const socket = new WebSocket(url);
    this.socket = socket;
    socket.binaryType = "arraybuffer";
    socket.onmessage = (event) => {
      if (socket !== this.socket) return;
      this.queue.push(event.data);
      this.pump();
    };
  }

  stop() {
    this.id = "";
    this.queue = [];
    this.meta = null;
    this.hold = new Map();
    this.nextSeq = null;
    this.gapSince = 0;
    this.badAppends = 0;
    this.watching = false;
    this.fpsLabel = "";
    if (this.gapTimer) {
      clearTimeout(this.gapTimer);
      this.gapTimer = 0;
    }
    if (this.socket) {
      try { this.socket.close(); } catch (error) {}
      this.socket = null;
    }
    this.resetMedia();
  }

  yieldToRtc() {
    this.watching = false;
    if (this.gapTimer) {
      clearTimeout(this.gapTimer);
      this.gapTimer = 0;
    }
    if (this.socket) {
      try { this.socket.close(); } catch (error) {}
      this.socket = null;
    }
    this.queue = [];
    this.media = null;
    this.source = null;
  }

  resetMedia() {
    this.codec = "";
    this.source = null;
    if (this.media) {
      try { URL.revokeObjectURL(this.video.src); } catch (error) {}
      this.media = null;
    }
    if (!this.video.srcObject) {
      this.video.removeAttribute("src");
      this.video.load();
    }
  }

  async pump() {
    if (this.busy) return;
    this.busy = true;
    try {
      while (this.queue.length && this.socket) {
        const item = this.queue.shift();
        if (typeof item === "string") {
          this.meta = JSON.parse(item);
          continue;
        }
        const meta = this.meta;
        this.meta = null;
        if (!meta) continue;
        if (meta.type === "init") {
          if (!this.source) await this.attach(meta.codec || "avc1.42E01E", item);
          continue;
        }
        if (!this.source) continue;
        this.hold.set(meta.seq, item);
        await this.drain();
      }
    } catch (error) {
      this.note("Waiting for the next live video frame.");
    } finally {
      this.busy = false;
      if (this.queue.length && this.socket) this.pump();
    }
  }

  async drain() {
    if (this.nextSeq == null) {
      const seqs = [...this.hold.keys()].sort((a, b) => a - b);
      if (!seqs.length) return;
      this.nextSeq = seqs[0];
    }
    while (this.source && this.socket) {
      if (!this.hold.has(this.nextSeq)) {
        const later = [...this.hold.keys()].filter((seq) => seq > this.nextSeq).sort((a, b) => a - b);
        if (!later.length) {
          this.gapSince = 0;
          return;
        }
        if (!this.gapSince) this.gapSince = Date.now();
        if (Date.now() - this.gapSince < 1200) {
          this.scheduleGap();
          return;
        }
        this.nextSeq = later[0];
        this.gapSince = 0;
        continue;
      }
      this.gapSince = 0;
      const bytes = this.hold.get(this.nextSeq);
      this.hold.delete(this.nextSeq);
      this.nextSeq += 1;
      const timed = retimeFragment(bytes, this.timeline);
      const samples = countSamples(timed.bytes);
      try {
        await this.append(timed.bytes);
        this.timeline += timed.duration;
        this.badAppends = 0;
        const video = this.video;
        video.dataset.appends = String((Number(video.dataset.appends) || 0) + 1);
        video.dataset.samples = String((Number(video.dataset.samples) || 0) + samples);
      } catch (error) {
        this.badAppends += 1;
        const open = this.media && this.media.readyState === "open" && this.source;
        if (open) {
          try { this.source.abort(); } catch (abortError) {}
        }
        if (this.badAppends >= 3 && this.initBytes) {
          this.badAppends = 0;
          await this.attach(this.codec, this.initBytes);
        }
      }
      this.edge();
      await this.trim();
    }
  }

  scheduleGap() {
    if (this.gapTimer) return;
    this.gapTimer = setTimeout(() => {
      this.gapTimer = 0;
      this.pump();
    }, 1250);
  }

  async attach(codec, init) {
    this.resetMedia();
    this.codec = codec;
    this.initBytes = init;
    if (typeof MediaSource === "undefined" || !MediaSource.isTypeSupported(`video/mp4; codecs="${codec}"`)) return;
    this.media = new MediaSource();
    this.video.muted = true;
    await new Promise((resolve, reject) => {
      this.media.addEventListener("sourceopen", () => resolve(), { once: true });
      this.video.src = URL.createObjectURL(this.media);
      setTimeout(() => reject(new Error("Live video did not open")), 4000);
    });
    this.source = this.media.addSourceBuffer(`video/mp4; codecs="${codec}"`);
    this.timeline = 0;
    this.source.mode = "segments";
    await this.append(init);
    this.watchFrames();
    this.video.play().catch(() => {});
  }

  append(bytes) {
    return new Promise((resolve, reject) => {
      const source = this.source;
      if (!source || source.updating) {
        reject(new Error("No buffer"));
        return;
      }
      const done = () => {
        source.removeEventListener("updateend", done);
        source.removeEventListener("error", fail);
        resolve();
      };
      const fail = () => {
        source.removeEventListener("updateend", done);
        source.removeEventListener("error", fail);
        reject(new Error("Buffer error"));
      };
      source.addEventListener("updateend", done);
      source.addEventListener("error", fail);
      try {
        source.appendBuffer(bytes);
      } catch (error) {
        fail();
      }
    });
  }

  edge() {
    const video = this.video;
    if (!video.buffered.length || video.seeking) return;
    const end = video.buffered.end(video.buffered.length - 1);
    const start = video.buffered.start(video.buffered.length - 1);
    const lag = end - video.currentTime;
    if (video.currentTime < start || lag > 1.2) video.currentTime = Math.max(start, end - 0.2);
    if (video.paused) video.play().catch(() => {});
  }

  trim() {
    const source = this.source;
    const video = this.video;
    if (!source || source.updating || !video.buffered.length) return Promise.resolve();
    const start = video.buffered.start(0);
    const end = video.buffered.end(video.buffered.length - 1);
    if (end - start < 8) return Promise.resolve();
    const cut = end - 4;
    if (cut <= start + 0.2) return Promise.resolve();
    return new Promise((resolve) => {
      const done = () => {
        source.removeEventListener("updateend", done);
        resolve();
      };
      source.addEventListener("updateend", done);
      try {
        source.remove(start, cut);
      } catch (error) {
        done();
      }
    });
  }

  watchFrames() {
    if (this.watching || !this.video.requestVideoFrameCallback) return;
    this.watching = true;
    let count = 0;
    let windowStart = 0;
    const tick = (now) => {
      if (!this.watching || !this.id) return;
      if (!windowStart) windowStart = now;
      count += 1;
      const elapsed = now - windowStart;
      if (elapsed >= 3000) {
        const fps = (count * 1000) / elapsed;
        const label = this.kind === "camera" ? "Camera" : "Screen";
        this.fpsLabel = `${label} ${fps.toFixed(1)} fps`;
        this.video.dataset.fps = fps.toFixed(1);
        this.note(this.fpsLabel);
        count = 0;
        windowStart = now;
      }
      this.video.requestVideoFrameCallback(tick);
    };
    this.video.requestVideoFrameCallback(tick);
  }

  note(text) {
    const rates = [screenPush && screenPush.fpsLabel, cameraPush && cameraPush.fpsLabel, screenFeed && screenFeed.fpsLabel, cameraFeed && cameraFeed.fpsLabel].filter(Boolean);
    feedNote = rates.length ? rates.join(" · ") : (this.announce ? text : feedNote);
    if (!document.getElementById("lockToggle").classList.contains("on") && feedNote) {
      document.getElementById("liveState").textContent = feedNote;
    }
  }
}

let screenPush;
let cameraPush;

const ICE_SERVERS = [
  { urls: "stun:stun.cloudflare.com:3478" },
  { urls: "stun:stun.l.google.com:19302" },
];

class RtcFeed {
  constructor(video, kind, announce) {
    this.video = video;
    this.kind = kind;
    this.announce = announce;
    this.id = "";
    this.session = "";
    this.pc = null;
    this.timer = 0;
    this.statsTimer = 0;
    this.ice = [];
    this.applied = 0;
    this.remoteSet = false;
    this.pending = [];
    this.pendingStream = null;
    this.confirmTimer = 0;
    this.connected = false;
    this.reoffered = false;
    this.startedAt = 0;
    this.touchedAt = 0;
    this.fallback = null;
    this.fpsLabel = "";
    this.posting = Promise.resolve();
  }

  start(id) {
    const open = this.pc && this.pc.connectionState !== "closed" && this.pc.connectionState !== "failed";
    if (this.id === id && (this.fallback || open)) return;
    this.stop();
    this.id = id;
    this.session = crypto.randomUUID().replace(/-/g, "");
    this.startedAt = Date.now();
    this.reoffered = false;
    this.open().catch(() => this.useFallback("Live video is reconnecting."));
  }

  stop() {
    clearInterval(this.timer);
    clearInterval(this.statsTimer);
    clearInterval(this.confirmTimer);
    this.timer = 0;
    this.statsTimer = 0;
    this.confirmTimer = 0;
    this.pendingStream = null;
    this.id = "";
    this.session = "";
    this.connected = false;
    this.remoteSet = false;
    this.fpsLabel = "";
    this.ice = [];
    this.pending = [];
    this.applied = 0;
    if (this.fallback) {
      this.fallback.stop();
      this.fallback = null;
    }
    if (this.pc) {
      try { this.pc.close(); } catch (error) {}
      this.pc = null;
    }
    if (this.video.srcObject) this.video.srcObject = null;
  }

  async open() {
    const pc = new RTCPeerConnection({ iceServers: ICE_SERVERS });
    this.pc = pc;
    pc.addTransceiver("video", { direction: "recvonly" });
    pc.ontrack = (event) => {
      this.pendingStream = event.streams[0] || new MediaStream([event.track]);
      for (const receiver of pc.getReceivers()) {
        if (receiver.track && receiver.track.kind === "video") receiver.playoutDelayHint = 0;
      }
      this.maybeAdopt();
    };
    pc.onicecandidate = (event) => {
      if (!event.candidate) return;
      this.ice.push({
        candidate: event.candidate.candidate,
        sdpMid: event.candidate.sdpMid,
        sdpMLineIndex: event.candidate.sdpMLineIndex,
      });
      this.send({ ice: this.ice });
    };
    pc.oniceconnectionstatechange = () => {
      if (!this.pc) return;
      if (this.pc.iceConnectionState === "connected" || this.pc.iceConnectionState === "completed") {
        this.maybeAdopt();
      }
      if (this.pc.iceConnectionState === "failed") this.useFallback("Live link did not connect. Showing the uploaded video.");
    };
    const offer = await pc.createOffer();
    await pc.setLocalDescription(offer);
    await this.send({ offer: { type: offer.type, sdp: offer.sdp }, ice: this.ice });
    this.timer = setInterval(() => this.poll().catch(() => {}), 200);
    this.statsTimer = setInterval(() => this.measure().catch(() => {}), 2000);
    this.poll().catch(() => {});
  }

  async poll() {
    if (!this.id || !this.pc || !this.session) return;
    if (Date.now() - this.touchedAt > 3000) this.send({});
    const room = await api(`/api/rtc?device_id=${encodeURIComponent(this.id)}&kind=${this.kind}`);
    if (!room || room.session !== this.session) return;
    if (room.answer && room.answer.sdp && !this.remoteSet && this.pc.signalingState === "have-local-offer") {
      await this.pc.setRemoteDescription(room.answer);
      this.remoteSet = true;
      for (const item of this.pending) await this.addIce(item);
      this.pending = [];
    }
    if (!this.remoteSet && !this.reoffered && Date.now() - this.startedAt > 4000 && this.pc.localDescription) {
      this.reoffered = true;
      this.session = crypto.randomUUID().replace(/-/g, "");
      this.applied = 0;
      this.pending = [];
      const offer = this.pc.localDescription;
      await this.send({ offer: { type: offer.type, sdp: offer.sdp }, ice: this.ice });
    }
    const list = Array.isArray(room.phoneIce) ? room.phoneIce : [];
    while (this.applied < list.length) {
      const item = list[this.applied];
      this.applied += 1;
      if (this.remoteSet) await this.addIce(item);
      else this.pending.push(item);
    }
    if (!this.connected && Date.now() - this.startedAt > 12000) {
      this.useFallback("Live link is slow. Showing the uploaded video until it connects.");
    }
  }

  maybeAdopt() {
    if (this.connected || !this.pendingStream || !this.pc) return;
    const ice = this.pc.iceConnectionState;
    if (ice !== "connected" && ice !== "completed") return;
    if (this.confirmTimer) return;
    this.confirmTimer = setInterval(() => this.confirmFrames().catch(() => {}), 400);
  }

  async confirmFrames() {
    if (!this.pc || this.connected || !this.pendingStream) return;
    const stats = await this.pc.getStats();
    let decoded = 0;
    stats.forEach((report) => {
      if (report.type === "inbound-rtp" && report.kind === "video" && report.framesDecoded) {
        decoded = report.framesDecoded;
      }
    });
    if (decoded < 3) return;
    clearInterval(this.confirmTimer);
    this.confirmTimer = 0;
    const stream = this.pendingStream;
    const push = this.kind === "camera" ? cameraPush : screenPush;
    if (push) push.yieldToRtc();
    this.video.srcObject = stream;
    this.video.muted = true;
    this.connected = true;
    if (this.fallback) {
      this.fallback.stop();
      this.fallback = null;
      this.video.srcObject = stream;
    }
    this.video.play().catch(() => {});
    this.note(this.fpsLabel || "Live video connected.");
    this.measure().catch(() => {});
  }

  addIce(item) {
    if (!this.pc || !item || !item.candidate) return Promise.resolve();
    return this.pc.addIceCandidate({
      candidate: item.candidate,
      sdpMid: item.sdpMid || undefined,
      sdpMLineIndex: item.sdpMLineIndex,
    }).catch(() => {});
  }

  send(extra) {
    const body = {
      device_id: this.id,
      kind: this.kind,
      role: "viewer",
      session: this.session,
      ...extra,
    };
    this.touchedAt = Date.now();
    this.posting = this.posting.then(() => api("/api/rtc", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    })).catch(() => {});
    return this.posting;
  }

  async measure() {
    if (!this.pc || !this.connected) return;
    const stats = await this.pc.getStats();
    let fps = null;
    stats.forEach((report) => {
      if (report.type === "inbound-rtp" && report.kind === "video") {
        if (report.framesPerSecond != null) fps = report.framesPerSecond;
        if (report.framesDecoded != null) this.video.dataset.frames = String(report.framesDecoded);
      }
    });
    if (fps == null) return;
    const label = this.kind === "camera" ? "Camera" : "Screen";
    this.fpsLabel = `${label} ${Number(fps).toFixed(1)} fps`;
    this.video.dataset.fps = Number(fps).toFixed(1);
    this.note(this.fpsLabel);
  }

  useFallback(text) {
    if (this.connected || this.fallback || !this.id) return;
    const push = this.kind === "camera" ? cameraPush : screenPush;
    if (push && push.socket && push.socket.readyState <= 1) return;
    this.note(text);
    this.fallback = new LiveFeed(this.video, this.kind, this.announce);
    this.fallback.start(this.id);
  }

  note(text) {
    const rates = [screenFeed && screenFeed.fpsLabel, cameraFeed && cameraFeed.fpsLabel].filter(Boolean);
    feedNote = rates.length ? rates.join(" · ") : (this.announce ? text : feedNote);
    if (!this.announce && !rates.length) return;
    if (!document.getElementById("lockToggle").classList.contains("on")) {
      const state = document.getElementById("liveState");
      if (feedNote) state.textContent = feedNote;
    }
  }
}

const screenFeed = new RtcFeed(live, "screen", true);
const cameraFeed = new RtcFeed(camera, "camera", false);
screenPush = new PushFeed(live, "screen", true);
cameraPush = new PushFeed(camera, "camera", false);

function syncLive(id, cameraOnNow) {
  screenPush.start(id);
  screenFeed.start(id);
  if (cameraOnNow) {
    camera.hidden = false;
    cameraPush.start(id);
    cameraFeed.start(id);
  } else {
    cameraPush.stop();
    cameraFeed.stop();
    camera.hidden = true;
  }
}

if (token) {
  showApp();
  dayInput.value = todayIso();
  refresh().catch(() => showLogin());
}
setInterval(() => {
  if (!appView.hidden) refresh().catch(() => {});
}, 4000);
