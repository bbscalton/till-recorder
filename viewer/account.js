const app = document.getElementById("app");
const signOut = document.getElementById("signOut");

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

function renderSignedOut(google) {
  signOut.hidden = true;
  const note = google
    ? "Sign in with Google to request a plan and see your tills."
    : "Continue with Google opens Google so this store can be approved.";
  const href = google
    ? "/auth/google"
    : "/login";
  app.innerHTML = `
    <p class="lede">Account</p>
    <h1>Sign in to see your tills.</h1>
    <p class="lede">${note}</p>
    <div class="row">
      <a class="button" href="${href}">Continue with Google</a>
    </div>
  `;
}

function renderAccount(account) {
  signOut.hidden = false;
  const sub = account.subscription;
  const request = account.request;
  const plans = (account.plans || []).map((plan) => `
    <label class="plan">
      <span>
        <input type="radio" name="plan" value="${plan.id}">
        <strong>${plan.label}</strong> ${money(plan.price)}
        <p>Keeps recordings for 48 hours. With the storage add-on, ${money(plan.withAddon)} and recordings are kept for 30 days.</p>
      </span>
      <span>${plan.days} days</span>
    </label>
  `).join("");
  const tills = (account.devices || []).map((device) => `
    <tr>
      <td>${esc(device.name)}</td>
      <td>${device.paused ? "Paused" : (device.online ? "Online" : "Offline")}</td>
      <td>${when(device.lastSeen)}</td>
    </tr>
  `).join("");
  const subLine = sub
    ? `${pill(sub.status)} ${sub.label || ""} · ${money(sub.price)} · storage ${sub.retentionLabel || account.retentionLabel}${sub.expiresAt ? ` · ends ${when(sub.expiresAt)}` : ""}`
    : `${pill("none")} No approved plan yet. Base storage is 48 hours until a plan with the add-on is approved.`;
  const requestLine = request
    ? `${pill(request.status)} ${request.label || ""} · ${money(request.price)} · storage ${request.retentionLabel || "48 hours"} · requested ${when(request.requestedAt)}`
    : "No plan request waiting.";
  app.innerHTML = `
    <div>
      <p class="lede">${esc(account.email)}</p>
      <h1>${esc(account.name) || "Your account"}</h1>
    </div>
    <section class="stats">
      <div class="panel stat"><strong>${account.status}</strong><span>Account</span></div>
      <div class="panel stat"><strong>${sub ? sub.status : "none"}</strong><span>Plan</span></div>
      <div class="panel stat"><strong>${account.retentionLabel}</strong><span>Storage window</span></div>
      <div class="panel stat"><strong>${(account.devices || []).length}</strong><span>Tills</span></div>
    </section>
    <section class="grid-2">
      <article class="panel">
        <h2>Plan</h2>
        <p>${subLine}</p>
        <p>${requestLine}</p>
        <p class="note">Management approves the request and collects payment. A denied or expired plan does not open video. Pairing stays.</p>
        ${account.status === "approved" ? `
          <form id="planForm">
            <div class="plans">${plans}</div>
            <label class="addon">
              <input id="addon" type="checkbox">
              <span>Storage add-on, $5,000 per month on top of the plan. Recordings are kept for 30 days instead of 48 hours. A 3-month plan charges the add-on for 3 months.</span>
            </label>
            <p id="quote" class="note"></p>
            <button type="submit">Request this plan</button>
            <p id="planError" class="error" hidden></p>
          </form>
        ` : `<p class="note">Management has to approve the account before you can request a plan.</p>`}
      </article>
      <article class="panel">
        <h2>Your tills</h2>
        ${account.canWatch
          ? `<p class="note">Open only your registers. Pair a new one from that page.</p><p><a class="button" href="watch.html?customer=1">Open your registers</a></p>`
          : `<p class="note">Pairing and video stay off until the account is approved and a plan is active.</p>`}
        <table>
          <thead><tr><th>Till</th><th>Status</th><th>Last seen</th></tr></thead>
          <tbody>${tills || `<tr><td colspan="3">No tills paired to this account.</td></tr>`}</tbody>
        </table>
      </article>
    </section>
  `;
  const form = document.getElementById("planForm");
  if (!form) return;
  const quote = document.getElementById("quote");
  const refreshQuote = () => {
    const selected = form.querySelector("input[name=plan]:checked");
    const plan = (account.plans || []).find((item) => item.id === (selected && selected.value));
    if (!plan) {
      quote.textContent = "Choose 1 week, 1 month, or 3 months.";
      return;
    }
    const addon = document.getElementById("addon").checked;
    const price = addon ? plan.withAddon : plan.price;
    const storage = addon ? "30 days" : "48 hours";
    quote.textContent = `${plan.label}: ${money(plan.price)}${addon ? ` + ${money(plan.addonPrice)} storage add-on` : ""}. Total ${money(price)}. Recordings kept for ${storage}.`;
  };
  form.addEventListener("change", refreshQuote);
  refreshQuote();
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    const error = document.getElementById("planError");
    const selected = form.querySelector("input[name=plan]:checked");
    error.hidden = true;
    if (!selected) {
      error.hidden = false;
      error.textContent = "Choose a plan.";
      return;
    }
    const response = await fetch("/api/account/plan", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ plan: selected.value, addon: document.getElementById("addon").checked }),
    });
    if (!response.ok) {
      const body = await response.json().catch(() => ({}));
      error.hidden = false;
      error.textContent = body.error || "The request was not saved.";
      return;
    }
    renderAccount(await response.json());
  });
}

Promise.all([
  fetch("/api/auth/config", { cache: "no-store" }).then((response) => response.json()).catch(() => ({ google: false })),
  fetch("/api/account", { cache: "no-store" }),
]).then(async ([config, response]) => {
  if (response.status === 401) {
    renderSignedOut(Boolean(config.google));
    return;
  }
  if (!response.ok) {
    app.innerHTML = `<p class="error">The account could not be loaded.</p>`;
    return;
  }
  renderAccount(await response.json());
}).catch(() => {
  app.innerHTML = `<p class="error">The account could not be loaded.</p>`;
});
