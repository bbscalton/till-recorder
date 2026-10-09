const PLANS = {
  week: { label: "1 week", days: 7, price: 4000, addonMonths: 1 },
  month: { label: "1 month", days: 30, price: 6000, addonMonths: 1 },
  quarter: { label: "3 months", days: 90, price: 15000, addonMonths: 3 },
};

const ADDON_PRICE = 5000;
const BASE_RETENTION_HOURS = 48;
const ADDON_RETENTION_HOURS = 24 * 30;
const SESSION_MS = 14 * 24 * 60 * 60 * 1000;

export function catalog() {
  return Object.entries(PLANS).map(([id, plan]) => ({
    id,
    label: plan.label,
    days: plan.days,
    price: plan.price,
    addonPrice: ADDON_PRICE * plan.addonMonths,
    withAddon: plan.price + ADDON_PRICE * plan.addonMonths,
    retentionHours: BASE_RETENTION_HOURS,
    addonRetentionHours: ADDON_RETENTION_HOURS,
    retentionLabel: "48 hours",
    addonRetentionLabel: "30 days",
  }));
}

export function quote(planId, addon) {
  const plan = PLANS[planId];
  if (!plan) return null;
  const extra = addon ? ADDON_PRICE * plan.addonMonths : 0;
  return {
    plan: planId,
    label: plan.label,
    days: plan.days,
    planPrice: plan.price,
    addon: Boolean(addon),
    addonPrice: extra,
    price: plan.price + extra,
    retentionHours: addon ? ADDON_RETENTION_HOURS : BASE_RETENTION_HOURS,
    retentionLabel: addon ? "30 days" : "48 hours",
  };
}

export function googleReady(env) {
  return Boolean(env.GOOGLE_CLIENT_ID && env.GOOGLE_CLIENT_SECRET);
}

export function subscriptionActive(user) {
  const sub = user && user.subscription;
  if (!sub || sub.status !== "approved") return false;
  return Date.now() < Number(sub.expiresAt || 0);
}

export function canWatch(user) {
  return Boolean(user && user.status === "approved" && subscriptionActive(user));
}

function freshen(user) {
  if (!user) return user;
  const sub = user.subscription;
  if (sub && sub.status === "approved" && Date.now() >= Number(sub.expiresAt || 0)) {
    user.subscription = { ...sub, status: "expired" };
    user.expiredChanged = true;
  }
  return user;
}

async function putUser(env, user) {
  const stored = { ...user };
  delete stored.expiredChanged;
  await env.RECORDINGS.put(`users/${user.id}.json`, JSON.stringify(stored), {
    httpMetadata: { contentType: "application/json" },
  });
}

async function loadUser(env, id) {
  if (!id) return null;
  const object = await env.RECORDINGS.get(`users/${id}.json`);
  if (!object) return null;
  const user = freshen(await object.json());
  if (user.expiredChanged) await putUser(env, user);
  return user;
}

export async function sessionUser(request, env) {
  const token = readCookie(request, "till_session");
  if (!token || token.length < 32) return null;
  const object = await env.RECORDINGS.get(`sessions/${token}.json`);
  if (!object) return null;
  const session = await object.json();
  if (Date.now() > Number(session.expires || 0)) return null;
  return loadUser(env, session.userId);
}

function readCookie(request, name) {
  const header = request.headers.get("Cookie") || "";
  for (const part of header.split(";")) {
    const [key, ...rest] = part.trim().split("=");
    if (key === name) return decodeURIComponent(rest.join("="));
  }
  return "";
}

function sessionCookie(token) {
  return `till_session=${token}; HttpOnly; Secure; SameSite=Lax; Path=/; Max-Age=${SESSION_MS / 1000}`;
}

function clearCookie() {
  return "till_session=; HttpOnly; Secure; SameSite=Lax; Path=/; Max-Age=0";
}

function redirect(location, cookie) {
  const headers = { Location: location };
  if (cookie) headers["Set-Cookie"] = cookie;
  return new Response(null, { status: 302, headers });
}

function clean(value, max) {
  return String(value || "").replace(/[^\x20-\x7e]/g, "").trim().slice(0, max);
}

export function startGoogle(request, env) {
  if (!googleReady(env)) return redirect("/login?google=missing");
  const state = [...crypto.getRandomValues(new Uint8Array(16))].map((b) => b.toString(16).padStart(2, "0")).join("");
  const redirectUri = `${new URL(request.url).origin}/auth/google/callback`;
  const params = new URLSearchParams({
    client_id: env.GOOGLE_CLIENT_ID,
    redirect_uri: redirectUri,
    response_type: "code",
    scope: "openid email profile",
    state,
    prompt: "select_account",
  });
  const pending = env.RECORDINGS.put(`oauth/${state}.json`, JSON.stringify({ expires: Date.now() + 10 * 60 * 1000 }), {
    httpMetadata: { contentType: "application/json" },
  });
  return pending.then(() => redirect(`https://accounts.google.com/o/oauth2/v2/auth?${params}`));
}

export async function finishGoogle(request, env) {
  if (!googleReady(env)) return redirect("/login?google=missing");
  const url = new URL(request.url);
  const code = url.searchParams.get("code") || "";
  const state = url.searchParams.get("state") || "";
  const fail = redirect("/login?google=failed");
  if (!code || !/^[a-f0-9]{32}$/.test(state)) return fail;
  const saved = await env.RECORDINGS.get(`oauth/${state}.json`);
  if (!saved) return fail;
  const pending = await saved.json();
  await env.RECORDINGS.delete(`oauth/${state}.json`);
  if (Date.now() > Number(pending.expires || 0)) return fail;
  const redirectUri = `${url.origin}/auth/google/callback`;
  const tokenResponse = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      code,
      client_id: env.GOOGLE_CLIENT_ID,
      client_secret: env.GOOGLE_CLIENT_SECRET,
      redirect_uri: redirectUri,
      grant_type: "authorization_code",
    }),
  });
  if (!tokenResponse.ok) return fail;
  const tokenJson = await tokenResponse.json();
  const access = tokenJson.access_token || "";
  if (!access) return fail;
  const profileResponse = await fetch("https://openidconnect.googleapis.com/v1/userinfo", {
    headers: { Authorization: `Bearer ${access}` },
  });
  if (!profileResponse.ok) return fail;
  const profile = await profileResponse.json();
  const sub = clean(profile.sub, 80);
  const email = clean(profile.email, 120).toLowerCase();
  if (!sub || !email || !profile.email_verified) return fail;
  const user = await upsertGoogleUser(env, {
    sub,
    email,
    name: clean(profile.name || email, 80),
  });
  const session = [...crypto.getRandomValues(new Uint8Array(32))].map((b) => b.toString(16).padStart(2, "0")).join("");
  await env.RECORDINGS.put(`sessions/${session}.json`, JSON.stringify({
    userId: user.id,
    expires: Date.now() + SESSION_MS,
  }), { httpMetadata: { contentType: "application/json" } });
  return redirect("/account", sessionCookie(session));
}

async function upsertGoogleUser(env, profile) {
  const subKey = `user-sub/${profile.sub}.json`;
  const existing = await env.RECORDINGS.get(subKey);
  if (existing) {
    const id = (await existing.text()).trim();
    const user = await loadUser(env, id);
    if (user) {
      user.email = profile.email;
      user.name = profile.name || user.name;
      await putUser(env, user);
      return user;
    }
  }
  const id = crypto.randomUUID().replaceAll("-", "");
  const user = {
    id,
    email: profile.email,
    name: profile.name,
    googleSub: profile.sub,
    status: "pending",
    createdAt: Date.now(),
    subscription: null,
    request: null,
  };
  await putUser(env, user);
  await env.RECORDINGS.put(subKey, id, { httpMetadata: { contentType: "text/plain" } });
  return user;
}

const FIREBASE_API_KEY = "AIzaSyCbr_X8p0_VyqIJd9l61kREnQPAWlRrXCM";
const FIREBASE_PROJECT = "till-recorder-auth";

export async function signInFirebase(request, env) {
  const body = await request.json().catch(() => ({}));
  const idToken = String(body.idToken || "");
  if (idToken.length < 20 || idToken.length > 9000) {
    return Response.json({ error: "Google did not sign in" }, { status: 401 });
  }
  const lookup = await fetch(`https://identitytoolkit.googleapis.com/v1/accounts:lookup?key=${FIREBASE_API_KEY}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ idToken }),
  });
  if (!lookup.ok) return Response.json({ error: "Google did not sign in" }, { status: 401 });
  const data = await lookup.json();
  const account = data.users && data.users[0];
  const providers = account && account.providerUserInfo || [];
  const google = providers.some((item) => item.providerId === "google.com");
  const email = clean(account && account.email, 120).toLowerCase();
  const sub = clean(account && account.localId, 80);
  if (!account || !google || !email || !sub || account.emailVerified === false) {
    return Response.json({ error: "Google did not sign in" }, { status: 401 });
  }
  if (account.aud && account.aud !== FIREBASE_PROJECT) {
    return Response.json({ error: "Google did not sign in" }, { status: 401 });
  }
  const user = await upsertGoogleUser(env, {
    sub,
    email,
    name: clean(account.displayName || email, 80),
  });
  const session = [...crypto.getRandomValues(new Uint8Array(32))].map((b) => b.toString(16).padStart(2, "0")).join("");
  await env.RECORDINGS.put(`sessions/${session}.json`, JSON.stringify({
    userId: user.id,
    expires: Date.now() + SESSION_MS,
  }), { httpMetadata: { contentType: "application/json" } });
  return new Response(JSON.stringify({ ok: true }), {
    headers: {
      "Content-Type": "application/json",
      "Set-Cookie": sessionCookie(session),
    },
  });
}

export function logout() {
  return redirect("/login", clearCookie());
}

function publicAccount(user, devices) {
  const sub = user.subscription || null;
  const request = user.request || null;
  const watching = canWatch(user);
  return {
    signedIn: true,
    email: user.email,
    name: user.name,
    status: user.status,
    canWatch: watching,
    canPair: watching,
    retentionLabel: (sub && sub.retentionLabel) || "48 hours",
    subscription: sub,
    request,
    plans: catalog(),
    devices: watching ? devices : devices.map((device) => ({ ...device, recording: false, paused: true })),
  };
}

async function devicesFor(env, userId) {
  const objects = await listPrefix(env, "status/");
  const devices = [];
  for (const item of objects) {
    const object = await env.RECORDINGS.get(item.key);
    if (!object) continue;
    const device = await object.json();
    if (device.ownerUserId !== userId) continue;
    const seen = Number(device.lastSeen || 0);
    devices.push({
      id: device.id,
      name: device.name || "Register",
      lastSeen: seen,
      online: Date.now() - seen < 90_000,
      recording: Boolean(device.recording) && Date.now() - seen < 90_000,
    });
  }
  devices.sort((a, b) => String(a.name).localeCompare(String(b.name)));
  return devices;
}

export async function readAccount(request, env) {
  const user = await sessionUser(request, env);
  if (!user) return Response.json({ signedIn: false }, { status: 401 });
  const devices = await devicesFor(env, user.id);
  return Response.json(publicAccount(user, devices));
}

export async function requestPlan(request, env) {
  const user = await sessionUser(request, env);
  if (!user) return Response.json({ error: "Sign in" }, { status: 401 });
  if (user.status !== "approved") {
    return Response.json({ error: "Management has to approve the account before a plan can be requested." }, { status: 403 });
  }
  const body = await request.json().catch(() => ({}));
  const priced = quote(body.plan, Boolean(body.addon));
  if (!priced) return Response.json({ error: "Choose a plan" }, { status: 400 });
  user.request = {
    ...priced,
    status: "requested",
    requestedAt: Date.now(),
  };
  await putUser(env, user);
  return Response.json(publicAccount(user, await devicesFor(env, user.id)));
}

export async function userOwnsActiveDevice(env, user, deviceId) {
  if (!user || !canWatch(user)) return false;
  const object = await env.RECORDINGS.get(`status/${deviceId}.json`);
  if (!object) return false;
  const device = await object.json();
  return device.ownerUserId === user.id && device.legacy === false;
}

export async function recordingAllowed(env, deviceId) {
  const object = await env.RECORDINGS.get(`status/${deviceId}.json`);
  if (!object) return true;
  const device = await object.json();
  if (!device.ownerUserId || device.legacy !== false) return true;
  const user = await loadUser(env, device.ownerUserId);
  return canWatch(user);
}

export async function adminOverview(env) {
  const users = [];
  const userObjects = await listPrefix(env, "users/");
  for (const item of userObjects) {
    const object = await env.RECORDINGS.get(item.key);
    if (!object) continue;
    const user = freshen(await object.json());
    if (user.expiredChanged) await putUser(env, user);
    const devices = await devicesFor(env, user.id);
    users.push({
      id: user.id,
      email: user.email,
      name: user.name,
      status: user.status,
      createdAt: user.createdAt,
      subscription: user.subscription || null,
      request: user.request || null,
      devices,
    });
  }
  users.sort((a, b) => Number(b.createdAt || 0) - Number(a.createdAt || 0));
  const ownerTills = [];
  const statusObjects = await listPrefix(env, "status/");
  for (const item of statusObjects) {
    const object = await env.RECORDINGS.get(item.key);
    if (!object) continue;
    const device = await object.json();
    if (device.ownerUserId) continue;
    const seen = Number(device.lastSeen || 0);
    ownerTills.push({
      id: device.id,
      name: device.name || "Register",
      lastSeen: seen,
      online: Date.now() - seen < 90_000,
      recording: Boolean(device.recording) && Date.now() - seen < 90_000,
      storage: "Kept",
    });
  }
  ownerTills.sort((a, b) => String(a.name).localeCompare(String(b.name)));
  return Response.json({ users, ownerTills, plans: catalog() });
}

export async function adminDecideUser(request, env) {
  const body = await request.json().catch(() => ({}));
  const user = await loadUser(env, body.userId);
  if (!user) return Response.json({ error: "That account was not found" }, { status: 404 });
  if (body.action === "approve") user.status = "approved";
  else if (body.action === "deny") user.status = "denied";
  else return Response.json({ error: "Choose approve or deny" }, { status: 400 });
  user.decidedAt = Date.now();
  await putUser(env, user);
  return Response.json({ ok: true, status: user.status });
}

export async function adminDecidePlan(request, env) {
  const body = await request.json().catch(() => ({}));
  const user = await loadUser(env, body.userId);
  if (!user) return Response.json({ error: "That account was not found" }, { status: 404 });
  if (!user.request || user.request.status !== "requested") {
    return Response.json({ error: "There is no plan request to decide" }, { status: 400 });
  }
  if (body.action === "deny") {
    user.request = { ...user.request, status: "denied", decidedAt: Date.now() };
    await putUser(env, user);
    return Response.json({ ok: true, status: "denied" });
  }
  if (body.action !== "approve") return Response.json({ error: "Choose approve or deny" }, { status: 400 });
  if (user.status !== "approved") {
    return Response.json({ error: "Approve the account before the plan" }, { status: 400 });
  }
  const startAt = Date.now();
  const priced = quote(user.request.plan, user.request.addon);
  user.subscription = {
    ...priced,
    status: "approved",
    requestedAt: user.request.requestedAt,
    decidedAt: startAt,
    startAt,
    expiresAt: startAt + priced.days * 24 * 60 * 60 * 1000,
  };
  user.request = null;
  await putUser(env, user);
  return Response.json({ ok: true, status: "approved", expiresAt: user.subscription.expiresAt });
}

export async function cleanupRetention(env) {
  const now = Date.now();
  let removed = 0;
  const objects = await listPrefix(env, "status/");
  for (const item of objects) {
    if (removed >= 200) break;
    const object = await env.RECORDINGS.get(item.key);
    if (!object) continue;
    const device = await object.json();
    if (!device.ownerUserId || device.legacy !== false) continue;
    const user = await loadUser(env, device.ownerUserId);
    const hours = user && user.subscription && user.subscription.retentionHours
      ? Number(user.subscription.retentionHours)
      : BASE_RETENTION_HOURS;
    const cutoff = now - hours * 60 * 60 * 1000;
    const indexes = await listPrefix(env, `index/${device.id}/`);
    for (const index of indexes) {
      if (removed >= 200) break;
      const stored = await env.RECORDINGS.get(index.key);
      if (!stored) continue;
      const segment = await stored.json();
      if (Number(segment.endedAtMs || 0) >= cutoff) continue;
      if (segment.key) await env.RECORDINGS.delete(segment.key);
      if (segment.id) await env.RECORDINGS.delete(`ids/${segment.id}`);
      await env.RECORDINGS.delete(index.key);
      removed += 1;
    }
  }
  return removed;
}

async function listPrefix(env, prefix) {
  const objects = [];
  let cursor;
  do {
    const page = await env.RECORDINGS.list({ prefix, cursor });
    objects.push(...page.objects);
    cursor = page.truncated ? page.cursor : undefined;
  } while (cursor);
  return objects;
}
