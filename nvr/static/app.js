const loginView = document.getElementById("loginView");
const appView = document.getElementById("appView");
const loginForm = document.getElementById("loginForm");
const loginError = document.getElementById("loginError");
const deviceList = document.getElementById("deviceList");
const dayInput = document.getElementById("day");
const daySize = document.getElementById("daySize");
const player = document.getElementById("player");
const gap = document.getElementById("gap");
const gapText = document.getElementById("gapText");
const gapNext = document.getElementById("gapNext");
const track = document.getElementById("track");
const playhead = document.getElementById("playhead");
const nowMarker = document.getElementById("nowMarker");
const hours = document.getElementById("hours");
const clock = document.getElementById("clock");
const status = document.getElementById("status");

let devices = [];
let selectedId = null;
let segments = [];
let dayStart = 0;
let dayEnd = 0;
let activeSegment = null;
let playheadMs = null;
let rate = 1;

function todayIso() {
  const now = new Date();
  const month = String(now.getMonth() + 1).padStart(2, "0");
  const day = String(now.getDate()).padStart(2, "0");
  return `${now.getFullYear()}-${month}-${day}`;
}

function formatClock(ms) {
  return new Date(ms).toLocaleTimeString([], { hour: "numeric", minute: "2-digit", second: "2-digit" });
}

function formatBytes(bytes) {
  if (bytes < 1024 * 1024) return `${Math.max(1, Math.round(bytes / 1024))} KB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`;
}

function showLogin() {
  loginView.hidden = false;
  appView.hidden = true;
}

function showApp() {
  loginView.hidden = true;
  appView.hidden = false;
}

async function boot() {
  const response = await fetch("/api/me");
  if (response.ok) {
    showApp();
    dayInput.value = todayIso();
    await refreshDevices();
    await loadDay();
  } else {
    showLogin();
  }
}

loginForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  loginError.hidden = true;
  const response = await fetch("/api/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ token: document.getElementById("token").value }),
  });
  if (!response.ok) {
    loginError.hidden = false;
    loginError.textContent = "That token does not match this shop computer.";
    return;
  }
  document.getElementById("token").value = "";
  showApp();
  dayInput.value = dayInput.value || todayIso();
  await refreshDevices();
  await loadDay();
});

document.getElementById("logout").addEventListener("click", async () => {
  await fetch("/api/logout", { method: "POST" });
  pausePlayer();
  showLogin();
});

dayInput.addEventListener("change", () => loadDay());

document.getElementById("deleteDay").addEventListener("click", async () => {
  if (!selectedId) return;
  const label = dayInput.value;
  if (!confirm(`Delete every recording for this register on ${label}?`)) return;
  const response = await fetch(`/api/segments?device_id=${encodeURIComponent(selectedId)}&date=${encodeURIComponent(label)}`, {
    method: "DELETE",
  });
  if (response.status === 401) return showLogin();
  pausePlayer();
  await loadDay();
});

document.getElementById("playDay").addEventListener("click", () => {
  if (segments.length) playAbsolute(segments[0].started_at_ms + 50);
});
document.getElementById("backFive").addEventListener("click", () => nudge(-5 * 60 * 1000));
document.getElementById("backThirty").addEventListener("click", () => nudge(-30 * 1000));
document.getElementById("forwardThirty").addEventListener("click", () => nudge(30 * 1000));
document.getElementById("forwardFive").addEventListener("click", () => nudge(5 * 60 * 1000));

document.querySelectorAll("[data-rate]").forEach((button) => {
  button.addEventListener("click", () => setRate(Number(button.dataset.rate)));
});
setRate(1);

document.getElementById("jumpBtn").addEventListener("click", () => {
  const parts = (document.getElementById("jumpTime").value || "00:00").split(":").map(Number);
  const hoursPart = parts[0] || 0;
  const minutes = parts[1] || 0;
  const seconds = parts[2] || 0;
  playAbsolute(dayStart + ((hoursPart * 3600) + (minutes * 60) + seconds) * 1000);
});

track.parentElement.addEventListener("click", (event) => {
  const rect = track.parentElement.getBoundingClientRect();
  const ratio = Math.min(1, Math.max(0, (event.clientX - rect.left) / rect.width));
  playAbsolute(dayStart + ratio * (dayEnd - dayStart));
});

document.addEventListener("keydown", (event) => {
  if (event.target.closest("input, textarea")) return;
  if (appView.hidden) return;
  if (event.key === "ArrowLeft") {
    event.preventDefault();
    nudge(-30 * 1000);
  } else if (event.key === "ArrowRight") {
    event.preventDefault();
    nudge(30 * 1000);
  } else if (event.key === " ") {
    event.preventDefault();
    if (player.paused) player.play().catch(() => {});
    else player.pause();
  }
});

player.addEventListener("loadedmetadata", () => {
  player.playbackRate = rate;
  player.muted = rate !== 1;
  if (player.dataset.offset) {
    const offset = Number(player.dataset.offset);
    player.currentTime = Math.min(offset, Math.max(0, player.duration - 0.05));
    player.dataset.offset = "";
  }
});

player.addEventListener("timeupdate", () => {
  if (!activeSegment) return;
  playheadMs = activeSegment.started_at_ms + player.currentTime * 1000;
  updatePlayhead();
  clock.textContent = formatClock(playheadMs);
});

player.addEventListener("ended", () => {
  if (!activeSegment) return;
  const next = segments.find((segment) => segment.started_at_ms >= activeSegment.ended_at_ms - 200 && segment.id !== activeSegment.id);
  if (next && next.started_at_ms - activeSegment.ended_at_ms < 3000) {
    playAbsolute(next.started_at_ms + 50);
  } else {
    showGap(activeSegment.ended_at_ms);
  }
});

function setRate(next) {
  rate = next;
  player.playbackRate = next;
  player.muted = next !== 1;
  document.querySelectorAll("[data-rate]").forEach((button) => {
    button.classList.toggle("selected", Number(button.dataset.rate) === next);
  });
}

function pausePlayer() {
  player.pause();
  player.removeAttribute("src");
  player.load();
  activeSegment = null;
  playheadMs = null;
  gap.hidden = true;
  updatePlayhead();
  clock.textContent = "—";
}

async function refreshDevices() {
  const response = await fetch("/api/devices");
  if (response.status === 401) return showLogin();
  const payload = await response.json();
  devices = payload.devices;
  if (!selectedId && devices.length) selectedId = devices[0].id;
  if (selectedId && !devices.some((device) => device.id === selectedId)) {
    selectedId = devices.length ? devices[0].id : null;
  }
  deviceList.replaceChildren();
  if (!devices.length) {
    const empty = document.createElement("p");
    empty.className = "hint";
    empty.textContent = "No register has sent a recording yet.";
    deviceList.appendChild(empty);
    return;
  }
  for (const device of devices) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "device" + (device.id === selectedId ? " selected" : "") + (device.recording ? " live" : "");
    const name = document.createElement("span");
    name.textContent = device.name;
    const meta = document.createElement("small");
    meta.textContent = device.recording ? "Recording now" : "Not recording";
    button.append(name, meta);
    button.addEventListener("click", () => {
      selectedId = device.id;
      pausePlayer();
      refreshDevices();
      loadDay();
    });
    deviceList.appendChild(button);
  }
}

async function loadDay() {
  if (!selectedId) {
    segments = [];
    renderTimeline();
    daySize.textContent = "No clips yet.";
    return;
  }
  const date = dayInput.value || todayIso();
  const response = await fetch(`/api/segments?device_id=${encodeURIComponent(selectedId)}&date=${encodeURIComponent(date)}`);
  if (response.status === 401) return showLogin();
  const payload = await response.json();
  segments = payload.segments;
  dayStart = payload.day_start_ms;
  dayEnd = payload.day_end_ms;
  daySize.textContent = segments.length
    ? `${segments.length} clip${segments.length === 1 ? "" : "s"} · ${formatBytes(payload.total_bytes)}`
    : "Nothing was recorded on this day.";
  status.textContent = segments.length ? "" : "The bar stays empty where the register was not recording.";
  renderTimeline();
  updateNow();
}

function renderTimeline() {
  track.replaceChildren();
  hours.replaceChildren();
  const span = dayEnd - dayStart;
  if (span <= 0) return;
  for (const segment of segments) {
    const block = document.createElement("div");
    block.className = "segment";
    const left = ((segment.started_at_ms - dayStart) / span) * 100;
    const width = (Math.max(1000, segment.ended_at_ms - segment.started_at_ms) / span) * 100;
    block.style.left = `${left}%`;
    block.style.width = `${Math.max(width, 0.2)}%`;
    track.appendChild(block);
  }
  for (let hour = 0; hour < 24; hour += 3) {
    const label = document.createElement("span");
    label.textContent = new Date(dayStart + hour * 3600000).toLocaleTimeString([], { hour: "numeric" });
    hours.appendChild(label);
  }
  updatePlayhead();
}

function segmentAt(ms) {
  return segments.find((segment) => ms >= segment.started_at_ms && ms < segment.ended_at_ms) || null;
}

function playAbsolute(ms) {
  playheadMs = ms;
  const segment = segmentAt(ms);
  updatePlayhead();
  clock.textContent = formatClock(ms);
  if (!segment) {
    player.pause();
    showGap(ms);
    return;
  }
  gap.hidden = true;
  const offset = Math.max(0, (ms - segment.started_at_ms) / 1000);
  if (!activeSegment || activeSegment.id !== segment.id) {
    activeSegment = segment;
    player.dataset.offset = String(offset);
    player.src = `/api/media/${segment.id}`;
    player.play().catch(() => {});
  } else {
    player.currentTime = offset;
    player.play().catch(() => {});
  }
}

function showGap(ms) {
  const next = segments.find((segment) => segment.started_at_ms > ms);
  gap.hidden = false;
  gapText.textContent = `No recording at ${formatClock(ms)}.`;
  if (next) {
    gapNext.hidden = false;
    gapNext.textContent = `Play next clip at ${formatClock(next.started_at_ms)}`;
    gapNext.onclick = () => playAbsolute(next.started_at_ms + 50);
  } else {
    gapNext.hidden = true;
  }
}

function nudge(delta) {
  const base = playheadMs == null ? (segments[0] ? segments[0].started_at_ms : dayStart) : playheadMs;
  playAbsolute(Math.min(dayEnd - 1, Math.max(dayStart, base + delta)));
}

function updatePlayhead() {
  const span = dayEnd - dayStart;
  if (playheadMs == null || span <= 0) {
    playhead.hidden = true;
    return;
  }
  playhead.hidden = false;
  playhead.style.left = `${Math.min(100, Math.max(0, ((playheadMs - dayStart) / span) * 100))}%`;
}

function updateNow() {
  const viewingToday = dayInput.value === todayIso();
  const span = dayEnd - dayStart;
  const ratio = (Date.now() - dayStart) / span;
  if (!viewingToday || span <= 0 || ratio < 0 || ratio > 1) {
    nowMarker.hidden = true;
    return;
  }
  nowMarker.hidden = false;
  nowMarker.style.left = `${ratio * 100}%`;
}

setInterval(() => {
  if (!appView.hidden) {
    updateNow();
    refreshDevices().catch(() => {});
  }
}, 15000);

boot().catch(() => showLogin());
