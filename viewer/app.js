const worker = (window.TILL_WORKER_URL || "").replace(/\/$/, "");
const loginView = document.getElementById("loginView");
const appView = document.getElementById("appView");
const live = document.getElementById("live");
const camera = document.getElementById("camera");
const cameraToggle = document.getElementById("cameraToggle");
const cameraHint = document.getElementById("cameraHint");
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

dayInput.addEventListener("change", () => loadDay());
document.getElementById("playDay").addEventListener("click", () => {
  if (segments[0]) playAt(segments[0].startedAtMs + 50);
});
document.getElementById("backThirty").addEventListener("click", () => nudge(-30000));
document.getElementById("forwardThirty").addEventListener("click", () => nudge(30000));
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
    meta.textContent = device.recording ? "Screen is active" : "Screen is still";
    button.append(name, meta);
    button.addEventListener("click", async () => {
      selectedId = device.id;
      await refresh();
      await loadDay();
    });
    deviceList.appendChild(button);
  }
  const current = devices.find((device) => device.id === selectedId);
  liveState.textContent = current && current.recording ? "Live — someone is using the register" : "Idle — the screen is still, so nothing new is being saved";
  const cameraOn = Boolean(current && current.camera);
  cameraToggle.textContent = cameraOn ? "Turn off" : "Turn on";
  cameraToggle.classList.toggle("on", cameraOn);
  cameraToggle.disabled = !selectedId;
  camera.hidden = !cameraOn;
  cameraHint.hidden = cameraOn;
  if (!cameraOn) cameraHint.textContent = "Off. Turn it on to see the person at the register beside the screen.";
  if (selectedId) {
    live.src = `${worker}/api/live/${selectedId}?access=${encodeURIComponent(token)}&t=${Date.now()}`;
    if (cameraOn) {
      camera.src = `${worker}/api/camera/${selectedId}?access=${encodeURIComponent(token)}&t=${Date.now()}`;
    } else {
      camera.removeAttribute("src");
    }
  }
  if (dayInput.value) await loadDay();
}

async function loadDay() {
  if (!selectedId) return;
  const payload = await api(`/api/segments?device_id=${encodeURIComponent(selectedId)}&date=${encodeURIComponent(dayInput.value || todayIso())}`);
  segments = payload.segments || [];
  daySize.textContent = segments.length ? `${segments.length} clip(s) from when the screen was in use` : "Nothing was recorded on this day.";
  track.replaceChildren();
  if (!segments.length) return;
  const start = segments[0].startedAtMs;
  const end = segments[segments.length - 1].endedAtMs;
  const span = Math.max(1, end - start);
  for (const segment of segments) {
    const block = document.createElement("div");
    block.className = "segment";
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

if (token) {
  showApp();
  dayInput.value = todayIso();
  refresh().catch(() => showLogin());
}
setInterval(() => {
  if (!appView.hidden) refresh().catch(() => {});
}, 4000);
