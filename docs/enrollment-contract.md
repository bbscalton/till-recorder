# Unruly POS enrollment contract (server side implemented in branch `enrollment`, NOT yet deployed)

Mechanism chosen: per-till one-time enrollment LINK/QR made on the Watch page (APK is not modified; signature untouched).

## Link / QR
Watch "Create till" -> `https://till-recorder.neuereatec.workers.dev/e/<key>` (key = 22 chars base64url, 128-bit).
The QR encodes exactly this link. Opening it (GET, does not consume the key) shows a page with: (1) Install Unruly POS (APK), (2) "Open Unruly POS and connect" =
deep link `unrulypos://enroll?key=<key>&server=<urlencoded origin>`.
Client work needed: register scheme `unrulypos` host `enroll` (and optionally App Link https://.../e/* with assetlinks.json); in-app "Scan enrollment QR" / paste link as fallback
when the link was opened before install (sideloaded APKs get no install referrer). Parse key from either form.

## Redeem (public, no auth, rate-limited 20 tries/10 min/IP)
POST /api/enroll
{ "key": "<22 chars>", "device_id": "<8-64 chars [A-Za-z0-9_-]>", "device_name": "optional, ignored when the till name was preset" }
200: { "ok": true, "token": "<same device credential /api/pair returns>", "device_id": "...", "device_name": "<till name>",
       "camera": "front"|"back", "master_pin": "123456" | null }
400 {"error":"That enrollment link is not valid."} unknown/expired/used key or bad device_id
409 device_id already registered (existing registers are never touched; use the pair code)
429 too many tries.
Effects identical to /api/pair claim (status/<id>.json, removed/ and pin-push/ cleared for the NEW id). Key becomes single-use; stored PIN is erased from the record at redemption. master_pin is returned only in this one response: if the response is lost the key is spent; owner recovers with Watch "Push to POS" / a new link.

## App behavior on first launch with a key
1. POST /api/enroll with generated device_id. 2. Save token + device_id + name. 3. camera preset -> front/back. 4. If master_pin != null: it becomes the till's single admin PIN, shown once on the write-it-down screen; if null, normal set-PIN flow. 5. Start recording setup, no typing. Pairing-code flow unchanged.

## Owner endpoints (Watch auth: owner token or approved Google user)
POST /api/enroll-keys {name, camera:"front"|"back", pin?:"6 digits" | generate_pin:true, expires_hours:1..168 (default 24)}
 -> {ok,key,link,deep_link,name,camera,expires,has_pin,master_pin?}
GET /api/enroll-keys/<key> -> {used,expired,name,camera}
Storage: R2 `enroll/<key>.json`, `enroll-tries/<ip>.json` (new prefixes only).
