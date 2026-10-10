// Run: node --test cloudflare/test/enroll.test.mjs   (Node 20+). In-memory R2 stub; no network, no real Worker state.
import test from "node:test";
import assert from "node:assert/strict";
import { createEnrollKey, redeemEnrollKey, readEnrollKey, sealPin, openPin, trivialPin, randomPin } from "../src/enroll.js";

function fakeR2() {
  const m = new Map(); let n = 0;
  return {
    m,
    async get(k) { const v = m.get(k); return v ? { etag: v.etag, json: async () => JSON.parse(v.body) } : null; },
    async head(k) { return m.has(k) ? {} : null; },
    async delete(k) { m.delete(k); },
    async put(k, body, opts = {}) {
      const cur = m.get(k);
      if (opts.onlyIf && opts.onlyIf.etagMatches && (!cur || cur.etag !== opts.onlyIf.etagMatches)) return null;
      m.set(k, { body: String(body), etag: `e${++n}` }); return {};
    },
  };
}
const json = (b, status = 200) => ({ status, body: b });
const DEVICE_ID = /^[A-Za-z0-9_-]{8,64}$/;
function mk() {
  const env = { RECORDINGS: fakeR2(), TILL_TOKEN: "test-token-not-real" };
  const statuses = [];
  const deps = { json, canWatch: () => true, DEVICE_ID, writeStatus: async (e, p) => { statuses.push(p); } };
  const req = (body) => ({ url: "https://x.test/api", headers: { get: () => "1.2.3.4" }, json: async () => body });
  return { env, deps, statuses, req };
}

test("PIN rules: no repeats or simple runs, 6 digits", () => {
  for (const p of ["111111", "123456", "654321", "000000"]) assert.equal(trivialPin(p), true, p);
  for (let i = 0; i < 300; i++) { const p = randomPin(); assert.match(p, /^\d{6}$/); assert.equal(trivialPin(p), false); }
});

test("seal/open round trip; wrong secret fails; legacy plain pin still read", async () => {
  const { env } = mk();
  const sealed = await sealPin(env, "482915");
  assert.ok(!sealed.includes("482915"));
  assert.equal(await openPin(env, { pinEnc: sealed }), "482915");
  assert.equal(await openPin({ TILL_TOKEN: "other" }, { pinEnc: sealed }), "");
  assert.equal(await openPin(env, { pin: "135790" }), "135790");
  assert.equal(await openPin(env, {}), "");
});

test("Add register: name only -> link with generated PIN, PIN sealed at rest, no camera needed", async () => {
  const { env, deps, req } = mk();
  const res = await createEnrollKey(req({ name: "Front Till", generate_pin: true }), env, null, deps);
  assert.equal(res.status, 200);
  assert.match(res.body.master_pin, /^\d{6}$/);
  assert.equal(res.body.camera, "");
  assert.match(res.body.link, /\/e\/[A-Za-z0-9_-]{22}$/);
  const stored = env.RECORDINGS.m.get(`enroll/${res.body.key}.json`).body;
  assert.ok(!stored.includes(res.body.master_pin), "PIN must not be stored in clear");
  assert.ok(JSON.parse(stored).pinEnc);
  const read = await readEnrollKey(env, res.body.key, null, deps);
  assert.equal(JSON.stringify(read.body).includes(res.body.master_pin), false);
});

test("rejects blank name and invalid camera; explicit camera still works", async () => {
  const { env, deps, req } = mk();
  assert.equal((await createEnrollKey(req({ name: "  " }), env, null, deps)).status, 400);
  assert.equal((await createEnrollKey(req({ name: "A", camera: "side" }), env, null, deps)).status, 400);
  assert.equal((await createEnrollKey(req({ name: "A", camera: "back" }), env, null, deps)).body.camera, "back");
  assert.equal((await createEnrollKey(req({ name: "A", pin: "123456" }), env, null, deps)).status, 400);
});

test("redeem: PIN and name delivered once, record erased, second use refused, existing device untouched", async () => {
  const { env, deps, statuses, req } = mk();
  const c = await createEnrollKey(req({ name: "Back Till", generate_pin: true }), env, null, deps);
  const ok = await redeemEnrollKey(req({ key: c.body.key, device_id: "newdevice12345678" }), env, deps);
  assert.equal(ok.status, 200);
  assert.equal(ok.body.master_pin, c.body.master_pin);
  assert.equal(ok.body.device_name, "Back Till");
  assert.equal(ok.body.token, "test-token-not-real");
  assert.equal(statuses.length, 1);
  assert.deepEqual(Object.keys(statuses[0]).sort(), ["id", "lastSeen", "name", "recording"]);
  const after = env.RECORDINGS.m.get(`enroll/${c.body.key}.json`).body;
  assert.ok(!after.includes("pinEnc") && !after.includes(c.body.master_pin));
  assert.equal(JSON.parse(after).used, true);
  assert.equal((await redeemEnrollKey(req({ key: c.body.key, device_id: "another1234567890" }), env, deps)).status, 400);
  // an already registered device id is never taken over
  env.RECORDINGS.m.set("status/existingdevice123.json", { body: "{}", etag: "x" });
  const c2 = await createEnrollKey(req({ name: "T2", generate_pin: true }), env, null, deps);
  assert.equal((await redeemEnrollKey(req({ key: c2.body.key, device_id: "existingdevice123" }), env, deps)).status, 409);
  assert.equal(env.RECORDINGS.m.get("status/existingdevice123.json").body, "{}");
});

test("expired key refused; legacy plain-PIN record still redeems", async () => {
  const { env, deps, req } = mk();
  const key = "A".repeat(22);
  env.RECORDINGS.m.set(`enroll/${key}.json`, { etag: "l1", body: JSON.stringify({ name: "Old", camera: "front", pin: "135790", expires: Date.now() + 1e6, used: false }) });
  const ok = await redeemEnrollKey(req({ key, device_id: "legacydevice12345" }), env, deps);
  assert.equal(ok.body.master_pin, "135790");
  const key2 = "B".repeat(22);
  env.RECORDINGS.m.set(`enroll/${key2}.json`, { etag: "l2", body: JSON.stringify({ name: "Old", pin: "", expires: Date.now() - 1, used: false }) });
  assert.equal((await redeemEnrollKey(req({ key: key2, device_id: "legacydevice67890" }), env, deps)).status, 400);
});
