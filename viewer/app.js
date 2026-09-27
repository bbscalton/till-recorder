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
    if (active && active.id === id) clearPlayer();
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
    await api(`/api/segments?device_id=${encodeURIComponent(selectedId)}&date=${encodeURIComponent(day)}`, { method: "DELETE" });
    clearPlayer();
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

async function refresh() {
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
  const payload = await api(`/api/segments?device_id=${encodeURIComponent(selectedId)}&date=${encodeURIComponent(dayInput.value || todayIso())}`);
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
    block.title = segment.kind === "camera" ? "Front camera" : "Register screen";
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
      this.reset();
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
    if (head.seq - from > 4) from = Math.max(1, head.seq - 1);
    for (let seq = from; seq <= head.seq; seq += 1) {
      const bytes = await this.fetchChunk(seq === 0 ? "init" : String(seq));
      if (!bytes) break;
      await this.append(bytes);
      this.seq = seq;
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
    if (!this.video.buffered.length) return;
    const end = this.video.buffered.end(this.video.buffered.length - 1);
    if (end - this.video.currentTime > 1.5) this.video.currentTime = Math.max(0, end - 0.4);
    if (this.video.paused) this.video.play().catch(() => {});
    const source = this.source;
    if (source && !source.updating && this.video.buffered.length) {
      const start = this.video.buffered.start(0);
      if (end - start > 20) {
        try { source.remove(start, end - 8); } catch (error) {}
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

const screenFeed = new LiveFeed(live, "screen", true);
const cameraFeed = new LiveFeed(camera, "camera", false);

function syncLive(id, cameraOnNow) {
  screenFeed.start(id);
  if (cameraOnNow) {
    camera.hidden = false;
    cameraFeed.start(id);
  } else {
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
