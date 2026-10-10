const worker = (window.TILL_WORKER_URL || "").replace(/\/$/, "");
const loginView = document.getElementById("loginView");
const appView = document.getElementById("appView");
const live = document.getElementById("live");
const camera = document.getElementById("camera");
const cameraToggle = document.getElementById("cameraToggle");
const recordToggle = document.getElementById("recordToggle");
const recordState = document.getElementById("recordState");
const cameraHint = document.getElementById("cameraHint");
const lockToggle = document.getElementById("lockToggle");
const launcherToggle = document.getElementById("launcherToggle");
const launcherHint = document.getElementById("launcherHint");
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
const wallView = document.getElementById("wallView");
const detailView = document.getElementById("detailView");
const overhead = document.getElementById("overhead");
const overheadHint = document.getElementById("overheadHint");
const playCamera = document.getElementById("playCamera");
const playOverhead = document.getElementById("playOverhead");
const searchInput = document.getElementById("searchInput");
const searchResults = document.getElementById("searchResults");
const downloadClip = document.getElementById("downloadClip");
const cashierInput = document.getElementById("cashierInput");
const overheadNote = document.getElementById("overheadNote");
const quietNote = document.getElementById("quietNote");
const viewTitle = document.getElementById("viewTitle");
const customerWatch = new URLSearchParams(location.search).get("customer") === "1";

function storedToken() {
  if (customerWatch) return "";
  try {
    return localStorage.getItem("till-token") || sessionStorage.getItem("till-token") || "";
  } catch (error) {
    return "";
  }
}

function keepToken(value) {
  token = customerWatch ? "" : (value || "");
  if (customerWatch) return;
  try {
    if (token) {
      localStorage.setItem("till-token", token);
      sessionStorage.setItem("till-token", token);
    } else {
      localStorage.removeItem("till-token");
      sessionStorage.removeItem("till-token");
    }
  } catch (error) {}
}

let token = storedToken();
if (token) keepToken(token);
let devices = [];
let selectedId = "";
let segments = [];
let dayGeneration = 0;
let active = null;
let playheadMs = null;
let typed = [];
let highlightedAt = null;
let searchHits = [];
let searchFocusAt = 0;
let showingPlayback = false;
let pairTimer = 0;
let seekLatest = false;
const wallFeeds = new Map();

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
    headers: {
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(options && options.headers),
    },
  });
  if (response.status === 401) {
    if (!customerWatch) keepToken("");
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
  if (customerWatch) return;
  token = document.getElementById("token").value.trim();
  const response = await fetch(`${worker}/api/check-token`, { headers: { Authorization: `Bearer ${token}` } });
  if (!response.ok) {
    document.getElementById("loginError").hidden = false;
    document.getElementById("loginError").textContent = "That token does not match the recorder.";
    return;
  }
  keepToken(token);
  document.getElementById("token").value = "";
  showApp();
  dayInput.value = todayIso();
  await refresh();
});

if (recordToggle) {
  recordToggle.addEventListener("click", async () => {
    if (!selectedId || recordToggle.disabled) return;
    const current = devices.find((device) => device.id === selectedId);
    const enabled = current ? current.record === false : false;
    recordToggle.disabled = true;
    try {
      await api("/api/recording", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ device_id: selectedId, enabled }),
      });
      await refresh();
    } catch (error) {
      if (error.message !== "auth" && recordState) recordState.textContent = "Could not change recording. Try again.";
    } finally {
      recordToggle.disabled = !selectedId;
    }
  });
}

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

if (launcherToggle) {
  launcherToggle.addEventListener("click", async () => {
    if (!selectedId || launcherToggle.disabled) return;
    const current = devices.find((device) => device.id === selectedId);
    const showing = Number(current && current.launcherUntil || 0) > Date.now();
    launcherToggle.disabled = true;
    try {
      await api("/api/launcher", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ device_id: selectedId, show: !showing }),
      });
      await refresh();
    } catch (error) {
      if (launcherHint) launcherHint.textContent = error.message === "auth" ? "Sign in again." : "Could not update the till icon.";
    } finally {
      launcherToggle.disabled = !selectedId;
    }
  });
}

// ---- Master PIN: generate in the browser, push to the selected register, follow its status.
const pinGenerate = document.getElementById("pinGenerate");
const pinSend = document.getElementById("pinSend");
const pinValue = document.getElementById("pinValue");
const pinHint = document.getElementById("pinHint");
let generatedPin = "";
let generatedFor = "";
let pinTimer = 0;
let pinWatched = "";

function newMasterPin() {
  for (;;) {
    const bytes = new Uint32Array(1);
    do { crypto.getRandomValues(bytes); } while (bytes[0] >= 4294000000);
    const value = String(bytes[0] % 1000000).padStart(6, "0");
    const digits = [...value].map(Number);
    const step = digits[1] - digits[0];
    const run = (step === 1 || step === -1) && digits.every((d, i) => i === 0 || d - digits[i - 1] === step);
    if (!/^(\d)\1+$/.test(value) && !run) return value;
  }
}

function showPinStatus(status) {
  if (!pinHint || !status) return;
  const text = {
    pending: "Waiting for the register to pick it up (it checks in every few seconds while the app is open and online). Expires in 30 minutes.",
    delivered: "Delivered to the register. Waiting for the app to confirm.",
    acked: "Delivered and confirmed by the app.",
    expired: "Expired before the register picked it up. Push again.",
  }[status.state];
  if (text) pinHint.textContent = text;
}

async function pollPinStatus() {
  clearTimeout(pinTimer);
  if (!selectedId || !pinHint) return;
  const id = selectedId;
  try {
    const status = await api(`/api/master-pin?device_id=${encodeURIComponent(id)}`);
    if (id !== selectedId) return;
    showPinStatus(status);
    if (status.state === "pending" || status.state === "delivered") pinTimer = setTimeout(pollPinStatus, 5000);
  } catch (_error) { /* shown on the next action */ }
}

function syncPinDevice() {
  if (!pinSend || pinWatched === selectedId) return;
  pinWatched = selectedId;
  generatedPin = "";
  generatedFor = "";
  pinValue.textContent = "";
  pinSend.disabled = true;
  pollPinStatus();
}

if (pinGenerate && pinSend) {
  pinGenerate.addEventListener("click", () => {
    if (!selectedId) return;
    generatedPin = newMasterPin();
    generatedFor = selectedId;
    pinValue.textContent = generatedPin;
    pinSend.disabled = false;
    pinHint.textContent = "Write this PIN down. It is not saved on this page. Type it into the app's till name/PIN fields for first-time setup. Push to POS is only for an already-installed Unruly device whose PIN was forgotten.";
  });
  pinSend.addEventListener("click", async () => {
    if (!selectedId || !generatedPin || pinSend.disabled) return;
    if (generatedFor !== selectedId) {
      pinHint.textContent = "That PIN was generated for another register. Generate a new one.";
      return;
    }
    pinSend.disabled = true;
    try {
      await api("/api/master-pin", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ device_id: selectedId, pin: generatedPin }),
      });
      pinHint.textContent = "Pushed. Waiting for the register to pick it up.";
      pollPinStatus();
    } catch (error) {
      pinHint.textContent = error.message === "auth" ? "Sign in again." : "Could not push the PIN. Try again in a moment.";
    } finally {
      pinSend.disabled = !selectedId;
    }
  });
}

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
  box.hidden = false;
  status.textContent = "Creating a pair code…";
  code.textContent = "";
  try {
    const created = await api("/api/pair-codes", { method: "POST" });
    if (!created || !created.code) throw new Error("empty");
    code.textContent = created.display || created.code;
    status.textContent = "Enter this code in Till Recorder on the tablet or the Windows PC. It works for 10 minutes.";
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
  } catch (error) {
    code.textContent = "";
    status.textContent = error.message === "auth" ? "Sign in again, then pair." : "Could not create a pair code. Try again.";
  }
});

document.getElementById("enrollButton").addEventListener("click", () => {
  const box = document.getElementById("enrollBox");
  box.hidden = !box.hidden;
});

document.getElementById("enrollForm").addEventListener("submit", async (event) => {
  event.preventDefault();
  const status = document.getElementById("enrollStatus");
  const link = document.getElementById("enrollLink");
  const qr = document.getElementById("enrollQr");
  qr.hidden = true;
  link.textContent = "";
  status.textContent = "Creating…";
  try {
    const generate = document.getElementById("enrollGen").checked;
    const pin = document.getElementById("enrollPin").value.trim();
    const created = await api("/api/enroll-keys", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        name: document.getElementById("enrollName").value,
        camera: document.getElementById("enrollCamera").value,
        pin: generate ? "" : pin,
        generate_pin: generate,
        expires_hours: Number(document.getElementById("enrollHours").value),
      }),
    });
    link.textContent = created.link;
    try {
      const code = window.qrcode(0, "M");
      code.addData(created.link);
      code.make();
      qr.src = code.createDataURL(5);
      qr.hidden = false;
    } catch (ignored) { /* link text is still shown */ }
    status.textContent = `One-time link for ${created.name} (${created.camera} camera). ` +
      (created.master_pin ? `PIN: ${created.master_pin} (shown once, write it down). ` : "No PIN preset. ") +
      "Open it on the tablet; it works once.";
  } catch (error) {
    status.textContent = error.message === "auth" ? "Sign in again, then try." : "Could not create the link. Check the name and PIN.";
  }
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

document.getElementById("removeDevice").addEventListener("click", () => {
  if (selectedId) removeRegister(selectedId);
});
document.getElementById("backWall").addEventListener("click", () => {
  selectedId = "";
  clearPlayer();
  wallView.hidden = false;
  detailView.hidden = true;
  document.getElementById("searchScope").value = "all";
  viewTitle.textContent = "All registers";
  searchResults.hidden = true;
  renderDevices();
});
document.getElementById("liveNow").addEventListener("click", () => clearPlayer());
document.getElementById("searchForm").addEventListener("submit", (event) => {
  event.preventDefault();
  runSearch().catch((error) => {
    if (error.message !== "auth") document.getElementById("status").textContent = "Search did not finish.";
  });
});
document.getElementById("staffForm").addEventListener("submit", async (event) => {
  event.preventDefault();
  if (!selectedId) return;
  await api("/api/staff", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ device_id: selectedId, name: cashierInput.value.trim() }),
  });
  document.getElementById("status").textContent = "Saved the cashier for this register.";
  await refresh();
});
downloadClip.addEventListener("click", () => downloadActive().catch(() => {
  document.getElementById("status").textContent = "Could not download that clip.";
}));

player.addEventListener("timeupdate", () => {
  if (!active || !showingPlayback) return;
  playheadMs = active.startedAtMs + player.currentTime * 1000;
  clock.textContent = formatClock(playheadMs);
  placePlayhead();
  const cam = cover(playheadMs, "camera");
  const over = cover(playheadMs, "overhead");
  if (cam) follow(playCamera, cam, playheadMs);
  if (over) follow(playOverhead, over, playheadMs);
  renderTyped();
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
  renderDevices();
  if (!selectedId || detailView.hidden) return;
  const current = devices.find((device) => device.id === selectedId);
  paintDetail(current);
  if (dayInput.value) await loadDay();
}

function paintDetail(current) {
  const locked = Boolean(current && current.locked);
  const online = deviceOnline(current);
  const quiet = live.dataset.quiet === "1";
  const nextLive = !current
    ? "Select a register"
    : locked
      ? "Locked"
      : !online
        ? `Offline · last seen ${formatSeen(current)}`
        : quiet
          ? "Quiet · showing the last frame"
          : "Live";
  if (liveState.textContent !== nextLive) liveState.textContent = nextLive;
  liveState.className = "status-line" + (!online ? " offline" : quiet ? " quiet" : "");
  quietNote.hidden = !quiet || !online || showingPlayback;
  lockToggle.textContent = locked ? "Unlock" : "Lock";
  lockToggle.classList.toggle("on", locked);
  lockToggle.disabled = !selectedId;
  try { syncPinDevice(); } catch (_error) { /* PIN controls not ready yet */ }
  lockHint.textContent = locked
    ? "Locked. Press Unlock here, or enter the owner PIN on the tablet."
    : "Locks this register until the owner PIN is entered on the tablet.";
  if (launcherToggle) {
    const showing = Number(current && current.launcherUntil || 0) > Date.now();
    launcherToggle.textContent = showing ? "Hide app on till" : "Show app on till";
    launcherToggle.disabled = !selectedId;
    if (launcherHint) {
      launcherHint.textContent = showing
        ? "The icon is visible on that register until the time runs out, or until you hide it. Hiding the icon does not stop uninstall from Settings."
        : "Shows the Till Recorder icon on this register for 15 minutes. Hiding the icon does not stop uninstall from Settings.";
    }
  }
  const recording = Boolean(current && current.record !== false && current.recording);
  const recordLabel = recording ? "Recording" : "Not recording";
  if (recordState && recordState.textContent !== recordLabel) recordState.textContent = recordLabel;
  if (recordState) recordState.classList.toggle("off", !recording);
  if (recordToggle) {
    const armed = !current || current.record !== false;
    recordToggle.textContent = armed ? "Stop recording" : "Start recording";
    recordToggle.classList.toggle("on", armed);
    recordToggle.disabled = !selectedId;
  }
  const cameraOn = Boolean(current && current.camera);
  cameraToggle.textContent = cameraOn ? "Turn off" : "Turn on";
  cameraToggle.classList.toggle("on", cameraOn);
  cameraToggle.disabled = !selectedId;
  if (!showingPlayback) {
    const cameraHintText = "Off. Turn it on to see the person at the register.";
    camera.hidden = !cameraOn;
    cameraHint.hidden = cameraOn;
    if (!cameraOn && cameraHint.textContent !== cameraHintText) cameraHint.textContent = cameraHintText;
    const overheadOn = Boolean(current && current.overhead);
    overhead.hidden = !overheadOn;
    overheadHint.hidden = overheadOn;
    const overheadHintText = "No overhead camera has been added on this register.";
    if (!overheadOn && overheadHint.textContent !== overheadHintText) overheadHint.textContent = overheadHintText;
  }
  if (overheadNote) {
    const attached = Boolean(current && current.overhead);
    const label = current && current.overheadLabel ? current.overheadLabel : "";
    const nextNote = attached
      ? `Overhead camera${label ? `: ${label}` : ""}. The picture is sent from the till.`
      : "Add the camera on the register itself. This page only shows the picture that till sends.";
    if (overheadNote.textContent !== nextNote) overheadNote.textContent = nextNote;
  }
  if (document.activeElement !== cashierInput) cashierInput.value = current && current.cashier ? current.cashier : "";
  if (selectedId && !showingPlayback) syncLive(selectedId, cameraOn, Boolean(current && current.overhead), current ? current.record !== false : true);
  viewTitle.textContent = current ? current.name : "All registers";
  document.getElementById("detailName").textContent = current ? current.name || "Register" : "Register";
}

function formatSeen(device) {
  const seen = Number(device && device.lastSeen || 0);
  if (!seen) return "never";
  const ago = Date.now() - seen;
  if (ago < 90_000) return "just now";
  const mins = Math.round(ago / 60000);
  if (mins < 60) return `${mins} min ago`;
  return formatClock(seen);
}

function renderDevices() {
  if (!devices.length) {
    if (deviceList.dataset.mode === "empty") return;
    deviceList.dataset.mode = "empty";
    deviceList.replaceChildren();
    const empty = document.createElement("p");
    empty.className = "hint";
    empty.textContent = "No register has checked in yet. Pair the tablet or the Windows PC first.";
    deviceList.appendChild(empty);
    return;
  }
  if (deviceList.dataset.mode === "empty") deviceList.replaceChildren();
  deviceList.dataset.mode = "list";
  const seen = new Set();
  for (const device of devices) {
    seen.add(device.id);
    let button = deviceList.querySelector(`[data-id="${device.id}"]`);
    if (!button) {
      button = document.createElement("button");
      button.type = "button";
      button.className = "tile";
      button.dataset.id = device.id;
      const video = document.createElement("video");
      video.muted = true;
      video.autoplay = true;
      video.playsInline = true;
      const meta = document.createElement("div");
      meta.className = "tile-meta";
      const name = document.createElement("strong");
      const chip = document.createElement("span");
      chip.className = "chip";
      const who = document.createElement("small");
      const recordLabel = document.createElement("span");
      recordLabel.className = "record-state";
      meta.append(name, chip, who, recordLabel);
      const remove = document.createElement("span");
      remove.className = "tile-remove";
      remove.setAttribute("role", "button");
      remove.tabIndex = 0;
      remove.textContent = "Remove";
      remove.addEventListener("click", (event) => {
        event.preventDefault();
        event.stopPropagation();
        removeRegister(button.dataset.id);
      });
      remove.addEventListener("keydown", (event) => {
        if (event.key !== "Enter" && event.key !== " ") return;
        event.preventDefault();
        event.stopPropagation();
        removeRegister(button.dataset.id);
      });
      button.append(video, meta, remove);
      button.addEventListener("click", () => openTill(device.id));
      deviceList.appendChild(button);
      wallFeeds.set(device.id, new PushFeed(video, "screen", false));
    }
    const online = deviceOnline(device);
    const video = button.querySelector("video");
    const quiet = video.dataset.quiet === "1";
    const chip = button.querySelector(".chip");
    const chipText = !online ? "Offline" : quiet ? "Quiet" : "Live";
    chip.textContent = chipText;
    chip.className = "chip " + (chipText === "Live" ? "live" : chipText === "Quiet" ? "quiet" : "offline");
    button.querySelector("strong").textContent = device.name || "Register";
    const who = button.querySelector("small");
    const whoText = !online
      ? `Last seen ${formatSeen(device)}`
      : (device.cashier ? device.cashier : "No cashier set");
    if (who.textContent !== whoText) who.textContent = whoText;
    let recordLabel = button.querySelector(".record-state");
    if (!recordLabel) {
      recordLabel = document.createElement("span");
      recordLabel.className = "record-state";
      button.querySelector(".tile-meta").append(recordLabel);
    }
    const recording = device.record !== false && Boolean(device.recording);
    const recordText = recording ? "Recording" : "Not recording";
    if (recordLabel.textContent !== recordText) recordLabel.textContent = recordText;
    recordLabel.classList.toggle("off", !recording);
    const feed = wallFeeds.get(device.id);
    if (feed && device.record === false) feed.holdStill();
    else if (feed && online) {
      feed.releaseStill();
      if (!feed.healthy(device.id)) feed.start(device.id);
    }
  }
  for (const button of [...deviceList.querySelectorAll(".tile")]) {
    if (!seen.has(button.dataset.id)) {
      const feed = wallFeeds.get(button.dataset.id);
      if (feed) feed.stop();
      wallFeeds.delete(button.dataset.id);
      button.remove();
    }
  }
}

async function removeRegister(id) {
  const device = devices.find((item) => item.id === id);
  const name = device && device.name ? device.name : "this register";
  if (!window.confirm(`Remove ${name}? This deletes its recordings and unpairs it. The till will ask for a new pair code.`)) return;
  const button = document.getElementById("removeDevice");
  button.disabled = true;
  try {
    await api(`/api/devices/${encodeURIComponent(id)}`, { method: "DELETE" });
    devices = devices.filter((item) => item.id !== id);
    document.getElementById("status").textContent = `Removed ${name}.`;
    if (selectedId === id) {
      selectedId = "";
      clearPlayer(true);
      wallView.hidden = false;
      detailView.hidden = true;
      document.getElementById("searchScope").value = "all";
      viewTitle.textContent = "All registers";
      searchResults.hidden = true;
    }
    renderDevices();
  } catch (error) {
    if (error.message !== "auth") document.getElementById("status").textContent = "Could not remove that register.";
  } finally {
    button.disabled = false;
  }
}

async function openTill(id) {
  if (selectedId && selectedId !== id) clearPlayer(true);
  selectedId = id;
  wallView.hidden = true;
  detailView.hidden = false;
  document.getElementById("searchScope").value = "one";
  const current = devices.find((device) => device.id === id);
  paintDetail(current);
  seekLatest = true;
  await loadDay();
}

async function latestRecordingDay(id) {
  try {
    const payload = await api(`/api/recording-days?device_id=${encodeURIComponent(id)}`);
    const days = Array.isArray(payload.days) ? payload.days.filter((day) => /^\d{4}-\d{2}-\d{2}$/.test(day)) : [];
    return days.length ? days[days.length - 1] : "";
  } catch (error) {
    if (error.message === "auth") throw error;
    return "";
  }
}

async function loadDay() {
  if (!selectedId) return;
  const generation = ++dayGeneration;
  const payload = await api(`/api/segments?device_id=${encodeURIComponent(selectedId)}&date=${encodeURIComponent(dayInput.value || todayIso())}`);
  if (generation !== dayGeneration) return;
  segments = payload.segments || [];
  const shownDay = dayInput.value || todayIso();
  if (seekLatest && !segments.length && selectedId) {
    seekLatest = false;
    const requested = selectedId;
    const latest = await latestRecordingDay(requested);
    if (selectedId !== requested) return;
    if (latest && latest !== shownDay) {
      dayInput.value = latest;
      await loadDay();
      return;
    }
  } else if (segments.length) {
    seekLatest = false;
  }
  const segmentKey = `${shownDay}:${segments.length}:${segments[0] ? segments[0].id : ""}:${segments.length ? segments[segments.length - 1].id : ""}`;
  const clipLine = segments.length
    ? `${segments.length} clip(s) on ${shownDay}. Screen, camera, and overhead share one timeline.`
    : `Nothing was recorded on ${shownDay}.`;
  daySize.textContent = clipLine;
  document.getElementById("status").textContent = clipLine;
  deleteClip.hidden = !active || !segments.some((segment) => segment.id === active.id);
  downloadClip.hidden = deleteClip.hidden;
  if (!(segmentKey === track.dataset.marker && track.childElementCount === segments.length)) {
    track.dataset.marker = segmentKey;
    track.replaceChildren();
    if (segments.length) {
      const start = segments[0].startedAtMs;
      const end = segments[segments.length - 1].endedAtMs;
      const span = Math.max(1, end - start);
      for (const segment of segments) {
        const kind = kindOf(segment);
        const block = document.createElement("div");
        block.className = "segment" + (kind === "screen" ? "" : " " + kind);
        block.dataset.id = segment.id;
        block.title = kind === "camera" ? "Camera" : kind === "overhead" ? "Overhead" : "Screen";
        block.addEventListener("click", (event) => {
          event.stopPropagation();
          playAt(segment.startedAtMs + 50);
        });
        block.style.left = `${((segment.startedAtMs - start) / span) * 100}%`;
        block.style.width = `${Math.max(0.4, ((segment.endedAtMs - segment.startedAtMs) / span) * 100)}%`;
        track.appendChild(block);
      }
    }
  }
  try {
    await loadTyped(generation);
  } catch (error) {
    if (error.message === "auth") throw error;
    if (generation !== dayGeneration) return;
    typed = [];
    renderTyped();
  }
}

const inputList = document.getElementById("inputList");

async function loadTyped(generation) {
  const payload = await api(`/api/inputs?device_id=${encodeURIComponent(selectedId)}&date=${encodeURIComponent(dayInput.value || todayIso())}`);
  if (generation !== dayGeneration) return;
  typed = payload.entries || [];
  renderTyped();
}

function renderTyped() {
  if (!inputList) return;
  const now = Date.now();
  const query = searchInput.value.trim().toLowerCase();
  let rows = showingPlayback && active
    ? typed.filter((entry) => entry.at >= active.startedAtMs - 1500 && entry.at <= active.endedAtMs + 1500)
    : typed.filter((entry) => now - entry.at < 10 * 60 * 1000).slice(-30);
  if (searchFocusAt && !rows.some((entry) => entry.at === searchFocusAt)) {
    const focus = typed.find((entry) => entry.at === searchFocusAt);
    if (focus) rows = rows.concat(focus).sort((a, b) => a.at - b.at);
  }
  let current = null;
  if (searchFocusAt) current = rows.find((entry) => entry.at === searchFocusAt) || null;
  if (!current && active && playheadMs != null) {
    for (const entry of rows) if (entry.at <= playheadMs + 400) current = entry;
  } else if (!current && !active && rows.length) {
    current = rows[rows.length - 1];
  }
  const key = rows.map((entry) => entry.at + ":" + entry.text + ":" + (entry.cashier || "")).join("|") + "|" + (current ? current.at : "") + "|" + query;
  if (key === inputList.dataset.key) return;
  inputList.dataset.key = key;
  inputList.replaceChildren();
  if (!rows.length) {
    const empty = document.createElement("li");
    empty.className = "empty";
    empty.textContent = active ? "Nothing was typed during this clip." : "No recent typing on this register.";
    inputList.appendChild(empty);
    highlightedAt = null;
    return;
  }
  for (const entry of rows) {
    const item = document.createElement("li");
    if (entry === current) item.className = "current";
    else if (query && entry.text.toLowerCase().includes(query)) item.className = "match";
    const time = document.createElement("time");
    time.textContent = formatClock(entry.at);
    const text = document.createElement("span");
    text.textContent = entry.cashier ? `${entry.text} · ${entry.cashier}` : entry.text;
    item.append(time, text);
    inputList.appendChild(item);
  }
  if (current && current.at !== highlightedAt) {
    highlightedAt = current.at;
    const marked = inputList.querySelector(".current");
    if (marked) marked.scrollIntoView({ block: "nearest" });
  }
}

function kindOf(segment) {
  return segment.kind === "camera" || segment.kind === "overhead" ? segment.kind : "screen";
}

function cover(ms, kind) {
  return segments.find((segment) => kindOf(segment) === kind && ms >= segment.startedAtMs && ms < segment.endedAtMs);
}

function nearestClip(ms, kind) {
  const list = segments.filter((segment) => kindOf(segment) === kind);
  let best = null;
  let bestDist = Infinity;
  for (const segment of list) {
    const dist = ms < segment.startedAtMs ? segment.startedAtMs - ms : ms >= segment.endedAtMs ? ms - segment.endedAtMs : 0;
    if (dist < bestDist) {
      best = segment;
      bestDist = dist;
    }
  }
  return best;
}

function offsetIn(segment, ms) {
  const length = Math.max(0.2, (segment.endedAtMs - segment.startedAtMs) / 1000);
  const raw = (ms - segment.startedAtMs) / 1000;
  return Math.min(Math.max(0, raw), Math.max(0, length - 0.05));
}

function playAt(ms) {
  const screen = cover(ms, "screen") || nearestClip(ms, "screen");
  if (!screen) return;
  showingPlayback = true;
  live.hidden = true;
  player.hidden = false;
  cue(player, screen, offsetIn(screen, ms));
  const cam = cover(ms, "camera");
  const over = cover(ms, "overhead");
  camera.hidden = true;
  cameraHint.hidden = true;
  if (cam) cue(playCamera, cam, offsetIn(cam, ms));
  else hideClip(playCamera);
  overhead.hidden = true;
  if (over) {
    overheadHint.hidden = true;
    cue(playOverhead, over, offsetIn(over, ms));
  } else hideClip(playOverhead);
  active = screen;
  playheadMs = screen.startedAtMs + offsetIn(screen, ms) * 1000;
  clock.textContent = formatClock(playheadMs);
  deleteClip.hidden = false;
  downloadClip.hidden = false;
  placePlayhead();
  renderTyped();
}

// Live feeds start muted (browsers only autoplay muted video); the controls unmute them and the choice is kept.
for (const feed of [live, document.getElementById("camera"), document.getElementById("overhead")]) {
  if (feed) feed.addEventListener("volumechange", () => { feed.dataset.sound = feed.muted ? "" : "1"; });
}

function cue(video, segment, offset) {
  video.hidden = false;
  const go = () => {
    try { video.currentTime = offset; } catch (error) {}
    // Saved clips play with sound; if the browser refuses, fall back to muted (the controls unmute).
    video.play().catch(() => {
      video.muted = true;
      video.play().catch(() => {});
    });
  };
  if (video.dataset.segment !== segment.id) {
    video.dataset.segment = segment.id;
    video.src = `${worker}/api/media/${segment.id}?access=${encodeURIComponent(token)}`;
    video.onloadedmetadata = go;
  } else go();
}

function follow(video, segment, ms) {
  if (video.dataset.segment !== segment.id) cue(video, segment, offsetIn(segment, ms));
  else if (Math.abs(video.currentTime - offsetIn(segment, ms)) > 0.8) video.currentTime = offsetIn(segment, ms);
}

function hideClip(video) {
  video.hidden = true;
  video.pause();
}

function clearPlayer(keepFocus) {
  showingPlayback = false;
  if (!keepFocus) searchFocusAt = 0;
  active = null;
  playheadMs = null;
  for (const video of [player, playCamera, playOverhead]) hideClip(video);
  for (const video of [player, playCamera, playOverhead]) {
    video.removeAttribute("src");
    video.dataset.segment = "";
    video.load();
  }
  live.hidden = false;
  deleteClip.hidden = true;
  downloadClip.hidden = true;
  playhead.hidden = true;
  const current = devices.find((device) => device.id === selectedId);
  if (current) paintDetail(current);
  renderTyped();
}

async function runSearch() {
  const query = searchInput.value.trim();
  searchResults.replaceChildren();
  if (!query) {
    searchResults.hidden = true;
    searchFocusAt = 0;
    renderTyped();
    return;
  }
  const day = dayInput.value || todayIso();
  const scope = document.getElementById("searchScope").value;
  let path = `/api/search?date=${encodeURIComponent(day)}&q=${encodeURIComponent(query)}`;
  if (scope === "one" && selectedId) path += `&device_id=${encodeURIComponent(selectedId)}`;
  const payload = await api(path);
  searchHits = payload.hits || [];
  searchResults.hidden = false;
  if (!searchHits.length) {
    const empty = document.createElement("li");
    empty.className = "empty";
    empty.textContent = "Nothing typed that day matches.";
    searchResults.appendChild(empty);
    return;
  }
  for (const hit of searchHits) {
    const item = document.createElement("li");
    const button = document.createElement("button");
    button.type = "button";
    const time = document.createElement("time");
    time.textContent = formatClock(hit.at);
    const who = document.createElement("span");
    who.className = "who";
    who.textContent = [hit.deviceName, hit.cashier].filter(Boolean).join(" · ") || "Register";
    const text = document.createElement("span");
    text.textContent = hit.text;
    button.append(time, who, text);
    button.addEventListener("click", () => chooseHit(hit, button));
    item.appendChild(button);
    searchResults.appendChild(item);
  }
}

async function chooseHit(hit, button) {
  searchFocusAt = hit.at;
  for (const item of searchResults.querySelectorAll("button")) item.classList.remove("chosen");
  if (button) button.classList.add("chosen");
  if (selectedId !== hit.deviceId || detailView.hidden) await openTill(hit.deviceId);
  playAt(hit.at);
}

async function downloadActive() {
  if (!active) return;
  const response = await fetch(`${worker}/api/media/${active.id}?access=${encodeURIComponent(token)}`);
  if (!response.ok) throw new Error("download");
  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  const name = (devices.find((device) => device.id === selectedId) || {}).name || "register";
  link.href = url;
  link.download = `${name}-${formatClock(active.startedAtMs).replace(/[: ]/g, "-")}.mp4`;
  link.click();
  URL.revokeObjectURL(url);
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
    if (!this.video.dataset.sound) this.video.muted = true;
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
    if (!this.announce || showingPlayback) return;
    feedNote = text;
    if (!document.getElementById("lockToggle").classList.contains("on")) {
      const state = document.getElementById("liveState");
      if (text) state.textContent = "Live · " + text;
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
    this.openedAt = 0;
    this.lastMessageAt = 0;
    this.reviveTimer = 0;
    this.healTimer = setInterval(() => this.heal(), 3000);
  }

  holdStill() {
    if (this.still) return;
    this.still = true;
    if (this.reviveTimer) {
      clearTimeout(this.reviveTimer);
      this.reviveTimer = 0;
    }
    this.closeSocket();
  }

  releaseStill() {
    this.still = false;
  }

  healthy(id) {
    if (this.id !== id) return false;
    if (this.connecting || this.reviveTimer) return true;
    return Boolean(this.socket) && this.socket.readyState <= 1;
  }

  start(id) {
    if (this.healthy(id)) return;
    this.revive(id);
  }

  revive(id) {
    if (this.still) return;
    const nextId = id || this.id;
    if (!nextId) return;
    if (this.socket && this.socket.readyState <= 1 && this.id === nextId) return;
    if (this.reviveTimer) {
      clearTimeout(this.reviveTimer);
      this.reviveTimer = 0;
    }
    const switching = Boolean(this.id) && this.id !== nextId;
    const wait = switching ? 0 : (this.openedAt && Date.now() - this.openedAt < 1500 ? 1500 - (Date.now() - this.openedAt) : 0);
    this.reviveTimer = setTimeout(() => {
      this.reviveTimer = 0;
      if (!nextId) return;
      if (this.socket && this.socket.readyState <= 1 && this.id === nextId) return;
      this.id = nextId;
      this.generation = (this.generation || 0) + 1;
      this.closeSocket();
      this.queue = [];
      this.meta = null;
      this.hold = new Map();
      this.nextSeq = null;
      this.gapSince = 0;
      if (switching) this.resetMedia();
      this.open(nextId, this.generation);
    }, wait);
  }

  heal() {
    if (this.still) return;
    const stale = Boolean(this.source && this.lastMessageAt && Date.now() - this.lastMessageAt > 8000);
    if (stale) this.video.dataset.quiet = "1";
    else if (this.lastMessageAt) delete this.video.dataset.quiet;
    if (this.id && this.socket && this.socket.readyState === 1 && this.lastMessageAt && Date.now() - this.lastMessageAt > 20000) {
      this.lastMessageAt = Date.now();
      this.reopenSocket();
      return;
    }
    if (!this.id || this.healthy(this.id)) return;
    this.revive(this.id);
  }

  reopenSocket() {
    if (!this.id || this.connecting) return;
    this.generation = (this.generation || 0) + 1;
    this.closeSocket();
    this.queue = [];
    this.meta = null;
    this.hold = new Map();
    this.nextSeq = null;
    this.gapSince = 0;
    this.open(this.id, this.generation);
  }

  closeSocket() {
    const socket = this.socket;
    this.socket = null;
    if (!socket) return;
    socket.onclose = null;
    socket.onerror = null;
    socket.onmessage = null;
    try { socket.close(); } catch (error) {}
  }

  async open(id, generation) {
    this.connecting = true;
    this.openedAt = Date.now();
    this.lastMessageAt = 0;
    try {
    try {
      const head = await api(`/api/stream/${encodeURIComponent(id)}/${this.kind}/head`);
      const initRes = await fetch(`${worker}/api/stream/${encodeURIComponent(id)}/${this.kind}/init?access=${encodeURIComponent(token)}`, { cache: "no-store" });
      if (this.generation !== generation || this.id !== id) return;
      if (!this.source && head && initRes.ok) await this.attach(head.codec || "avc1.42E01E", await initRes.arrayBuffer());
    } catch (error) {
      if (this.generation === generation && this.id === id) this.note("Waiting for live video from the register.");
    }
    if (this.generation !== generation || this.id !== id) return;
    const url = `${worker.replace(/^http/, "ws")}/api/live-ws?device_id=${encodeURIComponent(id)}&kind=${this.kind}&access=${encodeURIComponent(token)}`;
    const socket = new WebSocket(url);
    if (this.generation !== generation || this.id !== id) {
      try { socket.close(); } catch (error) {}
      return;
    }
    this.socket = socket;
    this.openedAt = Date.now();
    socket.binaryType = "arraybuffer";
    socket.onmessage = (event) => {
      if (socket !== this.socket) return;
      this.lastMessageAt = Date.now();
      this.queue.push(event.data);
      this.pump();
    };
    socket.onerror = () => {
      if (socket !== this.socket) return;
      try { socket.close(); } catch (error) {}
    };
    socket.onclose = () => {
      if (socket !== this.socket) return;
      this.socket = null;
      if (this.still) return;
      this.note("Live video is reconnecting.");
      this.revive(id);
    };
    } finally {
      if (this.generation === generation) this.connecting = false;
    }
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
    if (this.reviveTimer) {
      clearTimeout(this.reviveTimer);
      this.reviveTimer = 0;
    }
    this.closeSocket();
    this.resetMedia();
  }

  yieldToRtc() {
    this.watching = false;
    if (this.gapTimer) {
      clearTimeout(this.gapTimer);
      this.gapTimer = 0;
    }
    this.closeSocket();
    this.queue = [];
    this.media = null;
    this.source = null;
  }

  resetMedia() {
    this.codec = "";
    this.source = null;
    try { this.video.srcObject = null; } catch (error) {}
    if (this.media) {
      try { URL.revokeObjectURL(this.video.src); } catch (error) {}
      this.media = null;
    }
    this.video.removeAttribute("src");
    this.video.load();
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
          this.codec = meta.codec || this.codec;
          this.initBytes = item;
          if (!this.source) await this.attach(this.codec, item);
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
    if (!this.video.dataset.sound) this.video.muted = true;
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
    if (!video.buffered.length) return;
    const end = video.buffered.end(video.buffered.length - 1);
    const start = video.buffered.start(video.buffered.length - 1);
    const lag = end - video.currentTime;
    const outside = video.currentTime < start || video.currentTime > end;
    if (outside || lag > 1.2) {
      try { video.currentTime = Math.max(start, end - 0.2); } catch (error) {}
    }
    if (video.paused) video.play().catch(() => {});
  }

  trim() {
    const source = this.source;
    const video = this.video;
    if (!source || source.updating || !video.buffered.length) return Promise.resolve();
    const start = video.buffered.start(0);
    const end = video.buffered.end(video.buffered.length - 1);
    if (end - start < 8) return Promise.resolve();
    const behind = video.currentTime - 1;
    const cut = Math.min(end - 4, behind);
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
    if (showingPlayback) return;
    const rates = [screenPush && screenPush.fpsLabel, cameraPush && cameraPush.fpsLabel, screenFeed && screenFeed.fpsLabel, cameraFeed && cameraFeed.fpsLabel].filter(Boolean);
    feedNote = rates.length ? rates.join(" · ") : (this.announce ? text : feedNote);
    if (!document.getElementById("lockToggle").classList.contains("on") && feedNote) {
      document.getElementById("liveState").textContent = "Live · " + feedNote;
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
      if (this.pc.iceConnectionState === "failed" || this.pc.iceConnectionState === "disconnected" || this.pc.iceConnectionState === "closed") {
        if (this.video.srcObject) this.video.srcObject = null;
        this.connected = false;
        this.useFallback("Live video is reconnecting.");
      }
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
    this.connected = true;
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
    if (!this.id) return;
    const push = this.kind === "camera" ? cameraPush : screenPush;
    if (!push || !push.id || push.healthy(push.id)) return;
    this.note(text);
    push.revive();
  }

  note(text) {
    if (showingPlayback) return;
    const rates = [screenFeed && screenFeed.fpsLabel, cameraFeed && cameraFeed.fpsLabel].filter(Boolean);
    feedNote = rates.length ? rates.join(" · ") : (this.announce ? text : feedNote);
    if (!this.announce && !rates.length) return;
    if (!document.getElementById("lockToggle").classList.contains("on")) {
      const state = document.getElementById("liveState");
      if (feedNote) state.textContent = "Live · " + feedNote;
    }
  }
}

const screenFeed = new RtcFeed(live, "screen", true);
const cameraFeed = new RtcFeed(camera, "camera", false);
screenPush = new PushFeed(live, "screen", true);
cameraPush = new PushFeed(camera, "camera", false);
const overheadPush = new PushFeed(overhead, "overhead", false);

function syncLive(id, cameraOnNow, overheadOn, recordOn) {
  if (recordOn === false) {
    screenPush.holdStill();
    cameraPush.holdStill();
    overheadPush.holdStill();
    return;
  }
  screenPush.releaseStill();
  cameraPush.releaseStill();
  overheadPush.releaseStill();
  if (screenFeed.id) screenFeed.stop();
  if (cameraFeed.id) cameraFeed.stop();
  if (screenPush.id !== id) screenPush.start(id);
  else if (!screenPush.healthy(id)) screenPush.revive(id);
  if (cameraOnNow) {
    if (!showingPlayback) camera.hidden = false;
    if (cameraPush.id !== id) cameraPush.start(id);
    else if (!cameraPush.healthy(id)) cameraPush.revive(id);
  } else if (cameraPush.id) {
    cameraPush.stop();
    if (!showingPlayback) camera.hidden = true;
  }
  if (overheadOn) {
    if (!showingPlayback) overhead.hidden = false;
    if (overheadPush.id !== id) overheadPush.start(id);
    else if (!overheadPush.healthy(id)) overheadPush.revive(id);
  } else if (overheadPush.id) {
    overheadPush.stop();
    if (!showingPlayback) overhead.hidden = true;
  }
}

function showCustomerGate(message) {
  const title = document.querySelector("#loginForm h1");
  const lede = document.querySelector("#loginForm .lede");
  if (title) title.textContent = "Your registers";
  if (lede) lede.textContent = message;
  const label = document.querySelector("#loginForm label");
  const field = document.getElementById("token");
  const button = document.querySelector("#loginForm button");
  if (label) label.hidden = true;
  if (field) field.hidden = true;
  if (button) button.hidden = true;
  if (!document.getElementById("customerAccountLink")) {
    const link = document.createElement("a");
    link.id = "customerAccountLink";
    link.href = "/account";
    link.textContent = "Open your account";
    document.getElementById("loginForm").appendChild(link);
  }
  showLogin();
}

if (customerWatch) {
  showCustomerGate("Checking the account…");
  fetch(`${worker}/api/account`, { cache: "no-store" }).then(async (response) => {
    if (!response.ok) {
      showCustomerGate("Sign in with Google on the account page before opening your registers.");
      return;
    }
    const account = await response.json();
    if (!account.canWatch) {
      const waiting = account.status === "pending"
        ? "This account is waiting for management to approve it. Video stays off until then."
        : account.status === "denied"
          ? "Management denied this account."
          : "Video stays hidden until management approves a plan. The register stays paired.";
      showCustomerGate(waiting);
      return;
    }
    showApp();
    dayInput.value = todayIso();
    refresh().catch(() => {});
  }).catch(() => {
    showCustomerGate("The account page could not be reached.");
  });
} else if (token) {
  showApp();
  dayInput.value = todayIso();
  refresh().catch((error) => {
    if (error && error.message === "auth") return;
  });
}
setInterval(() => {
  if (!appView.hidden) refresh().catch(() => {});
}, 4000);

function deviceOnline(device) {
  if (!device) return false;
  if (typeof device.online === "boolean") return device.online;
  const seen = Number(device.lastSeen || 0);
  return seen > 0 && Date.now() - seen < 90_000;
}

function resumeLive() {
  if (!customerWatch && !storedToken()) return;
  if (appView.hidden) showApp();
  refresh().catch((error) => {
    if (error && error.message === "auth") return;
  });
  if (screenPush && screenPush.id && !screenPush.healthy(screenPush.id)) screenPush.revive();
  if (cameraPush && cameraPush.id && !cameraPush.healthy(cameraPush.id)) cameraPush.revive();
  if (overheadPush && overheadPush.id && !overheadPush.healthy(overheadPush.id)) overheadPush.revive();
}

window.addEventListener("online", () => resumeLive());
document.addEventListener("visibilitychange", () => {
  if (!document.hidden) resumeLive();
});
