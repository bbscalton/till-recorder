const app = document.getElementById("app");
const lock = document.getElementById("lock");

function esc(value) {
  return String(value || "").replace(/[&<>"']/g, (ch) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", "\"": "&quot;", "'": "&#39;",
  }[ch]));
}

function money(value) {
  return `$${Number(value || 0).toLocaleString("en-US")}`;
}

function when(ms) {
  const time = Number(ms || 0);
  if (!time) return "—";
  return new Date(time).toLocaleString([], { dateStyle: "medium", timeStyle: "short" });
}

function pill(status) {
  const label = status || "none";
  return `<span class="pill ${label}">${label}</span>`;
}

function readToken() {
  try {
    return localStorage.getItem("till-token") || sessionStorage.getItem("till-token") || "";
  } catch (error) {
    return "";
  }
}

function keepToken(value) {
  try {
    if (value) {
      localStorage.setItem("till-token", value);
      sessionStorage.setItem("till-token", value);
    } else {
      localStorage.removeItem("till-token");
      sessionStorage.removeItem("till-token");
    }
  } catch (error) {}
}

async function api(path, options) {
  const token = readToken();
  const response = await fetch(path, {
    cache: "no-store",
    ...options,
    headers: {
      Authorization: `Bearer ${token}`,
      ...(options && options.headers),
    },
  });
  if (response.status === 401) {
    keepToken("");
    showGate("That token does not open management.");
    throw new Error("auth");
  }
  if (!response.ok) throw new Error("request");
  return response.json();
}

function showGate(message) {
  lock.hidden = true;
  app.innerHTML = `
    <p class="lede">Owner</p>
    <h1>Management</h1>
    <form id="gate" class="panel" style="max-width: 460px;">
      <p class="note">This page uses the existing owner token. Google accounts cannot open it.</p>
      <label for="token">Owner token</label>
      <input id="token" type="password" autocomplete="current-password" required>
      <p id="gateError" class="error">${message || ""}</p>
      <div class="row" style="margin-top: 14px;">
        <button type="submit">Open management</button>
      </div>
    </form>
  `;
  const error = document.getElementById("gateError");
  error.hidden = !message;
  document.getElementById("gate").addEventListener("submit", async (event) => {
    event.preventDefault();
    const field = document.getElementById("token");
    const value = field.value.trim();
    field.value = "";
    keepToken(value);
    try {
      await load();
    } catch (error) {
      if (error && error.message === "auth") return;
      showGate("Management could not be opened.");
    }
  });
}

function storageLabel(user) {
  const sub = user.subscription;
  if (sub && sub.retentionLabel) return sub.retentionLabel;
  if (user.request && user.request.retentionLabel) return `Requested ${user.request.retentionLabel}`;
  return "48 hours";
}

function planLabel(user) {
  const sub = user.subscription;
  if (!sub) return pill("none");
  const ended = sub.expiresAt ? ` · ends ${when(sub.expiresAt)}` : "";
  return `${pill(sub.status)} ${sub.label || ""} · ${money(sub.price)}${ended}`;
}

function render(data) {
  lock.hidden = false;
  const users = data.users || [];
  const pending = users.filter((user) => user.status === "pending");
  const requests = users.filter((user) => user.request && user.request.status === "requested");
  const denied = users.filter((user) => user.status === "denied" || (user.subscription && user.subscription.status === "expired") || (user.request && user.request.status === "denied"));
  const queue = (rows, empty, draw) => rows.length ? rows.map(draw).join("") : `<p class="note">${empty}</p>`;
  app.innerHTML = `
    <div>
      <p class="lede">Owner</p>
      <h1>Management</h1>
    </div>
    <section class="stats">
      <div class="panel stat"><strong>${pending.length}</strong><span>Accounts waiting</span></div>
      <div class="panel stat"><strong>${requests.length}</strong><span>Plan requests</span></div>
      <div class="panel stat"><strong>${users.length}</strong><span>Accounts</span></div>
      <div class="panel stat"><strong>${(data.ownerTills || []).length}</strong><span>Owner tills kept</span></div>
    </section>
    <section class="grid-2">
      <article class="panel">
        <h2>Pending accounts</h2>
        <div class="queue" id="accounts">
          ${queue(pending, "No accounts are waiting.", (user) => `
            <div class="card-row">
              <div>
                <strong>${esc(user.name || user.email)}</strong> ${pill("pending")}
                <p>${esc(user.email)} · registered ${when(user.createdAt)}</p>
              </div>
              <div class="row">
                <button type="button" data-user="${user.id}" data-action="approve">Approve</button>
                <button class="deny" type="button" data-user="${user.id}" data-action="deny">Deny</button>
              </div>
            </div>
          `)}
        </div>
      </article>
      <article class="panel">
        <h2>Plan requests</h2>
        <div class="queue" id="plans">
          ${queue(requests, "No plan requests are waiting.", (user) => `
            <div class="card-row">
              <div>
                <strong>${esc(user.email)}</strong> ${pill("requested")}
                <p>${esc(user.request.label)} · ${money(user.request.price)} · storage ${esc(user.request.retentionLabel)}${user.request.addon ? " · add-on" : ""}</p>
              </div>
              <div class="row">
                <button type="button" data-plan="${user.id}" data-action="approve">Approve</button>
                <button class="deny" type="button" data-plan="${user.id}" data-action="deny">Deny</button>
              </div>
            </div>
          `)}
        </div>
      </article>
    </section>
    <section class="panel">
      <h2>Denied and expired</h2>
      ${denied.length ? `<table>
        <thead><tr><th>Account</th><th>Account status</th><th>Plan</th><th>Request</th></tr></thead>
        <tbody>
          ${denied.map((user) => `<tr>
            <td>${esc(user.email)}</td>
            <td>${pill(user.status)}</td>
            <td>${user.subscription ? pill(user.subscription.status) : pill("none")}</td>
            <td>${user.request ? pill(user.request.status) : "—"}</td>
          </tr>`).join("")}
        </tbody>
      </table>` : `<p class="note">Nothing is denied or expired.</p>`}
    </section>
    <section class="panel">
      <h2>All accounts</h2>
      <table>
        <thead><tr><th>Account</th><th>Status</th><th>Plan</th><th>Storage</th><th>Tills</th><th>Last seen</th></tr></thead>
        <tbody>
          ${users.map((user) => {
            const last = Math.max(0, ...(user.devices || []).map((device) => Number(device.lastSeen || 0)));
            const tills = (user.devices || []).map((device) => esc(device.name)).join(", ") || "—";
            return `<tr>
              <td>${esc(user.name) || "—"}<br>${esc(user.email)}</td>
              <td>${pill(user.status)}</td>
              <td>${planLabel(user)}</td>
              <td>${storageLabel(user)}</td>
              <td>${tills}</td>
              <td>${last ? when(last) : "—"}</td>
            </tr>`;
          }).join("") || `<tr><td colspan="6">No Google accounts yet.</td></tr>`}
        </tbody>
      </table>
    </section>
    <section class="panel">
      <h2>Owner tills</h2>
      <p class="note">These tills were paired before store accounts. Their recordings stay. Cleanup does not delete them.</p>
      <table>
        <thead><tr><th>Till</th><th>Status</th><th>Storage</th><th>Last seen</th></tr></thead>
        <tbody>
          ${(data.ownerTills || []).map((device) => `<tr>
            <td>${esc(device.name)}</td>
            <td>${device.online ? "Online" : "Offline"}${device.recording ? " · recording" : ""}</td>
            <td>${device.storage}</td>
            <td>${when(device.lastSeen)}</td>
          </tr>`).join("") || `<tr><td colspan="4">No owner tills.</td></tr>`}
        </tbody>
      </table>
    </section>
  `;
  app.querySelectorAll("[data-user]").forEach((button) => {
    button.addEventListener("click", () => decideUser(button.dataset.user, button.dataset.action));
  });
  app.querySelectorAll("[data-plan]").forEach((button) => {
    button.addEventListener("click", () => decidePlan(button.dataset.plan, button.dataset.action));
  });
}

async function decideUser(userId, action) {
  await api("/api/admin/user", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ userId, action }),
  });
  await load();
}

async function decidePlan(userId, action) {
  await api("/api/admin/plan", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ userId, action }),
  });
  await load();
}

async function load() {
  render(await api("/api/admin/overview"));
}

document.getElementById("gate").addEventListener("submit", async (event) => {
  event.preventDefault();
  const field = document.getElementById("token");
  const value = field.value.trim();
  field.value = "";
  keepToken(value);
  try {
    await load();
  } catch (error) {
    if (error && error.message === "auth") return;
    showGate("Management could not be opened.");
  }
});

lock.addEventListener("click", () => {
  keepToken("");
  showGate("");
});

if (readToken()) {
  load().catch((error) => {
    if (error && error.message === "auth") return;
    showGate("Management could not be opened.");
  });
}
