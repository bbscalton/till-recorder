# RESTORE (enrollment change, 2026-10-10)
Before: Worker version 4da11eb8-2ced-47ad-a983-522ce57f10a1, commit 47b04fee6a6dc5d4650b0e1362d56d7b9862e851, git tag pre-enrollment-20261010.
Backups on PC01: C:\Users\Administrator\Till-Recorder\backups\20261010-pre-enrollment (source, viewer, wrangler.jsonc, version lists) and ...\20261010-watch-state (device/control/status/pin-push/users JSON + API snapshots + SUMMARY.txt).
Roll back Worker only (instant, data untouched):
  cd C:\Users\Administrator\Till-Recorder\cloudflare
  npx wrangler rollback 4da11eb8-2ced-47ad-a983-522ce57f10a1 -m "rollback enrollment"
Or redeploy old source: git checkout pre-enrollment-20261010 ; cd cloudflare ; npx wrangler deploy
Re-import data (only if a record was lost; note the change never writes existing keys):
  cd cloudflare ; for each file in backups\20261010-watch-state\r2 named a__b.json:
  npx wrangler r2 object put till-recorder/a/b.json --remote --file <file> --content-type application/json
  (e.g. status__<id>.json -> status/<id>.json, control__ -> control/, pin-push__ -> pin-push/, users__ -> users/)
Remove test/enrollment records: npx wrangler r2 object delete till-recorder/enroll/<key>.json --remote
Not backed up: recordings (clips/stream/index/ids), sessions, jpgs, DO state (not touched by this change).

# RESTORE (Add register + accessibility_enabled change, Worker 2.0.10, 2026-10-10)
Before: Worker version 6fb74443-23a4-404a-bafd-f120ed95efc9 (previous 4e712d62-1a25-4311-a6bb-280201b7781c). After: a1173dfe-d38b-46e6-8cd0-b3f884a4922c.
Snapshot (PC01): C:\Users\Administrator\Till-Recorder\backups\20261010-pre-accessibility
  src\, viewer\, docs\, wrangler.jsonc, deployed-commit.txt (git HEAD + dirty files), deployments-list.txt, versions-list.txt
  r2\            full contents of every non-media R2 key (status, control, pin-push, removed, enroll, enroll-tries, pair, pair-tries, users, user-sub, sessions, rtc); a__b.json = a/b.json
  r2-key-manifest.tsv   size + name of ALL 14,652 R2 objects (recordings clips/stream/index/ids/inputs are listed, not copied: this change never writes them)
  SHA256SUMS.txt, SUMMARY.txt, after-deploy-compare.txt (before/after proof)
  No KV or D1 exists for this Worker. Storage = R2 bucket till-recorder + the ephemeral LiveRelay Durable Object.
How the snapshot was taken without the owner token: wrangler has no "r2 list", so cloudflare\tools\r2dump (read-only Worker run locally with
  `npx wrangler dev` and a remote R2 binding, wrangler login only) lists/reads the bucket on localhost. Run it from a temp copy; use compatibility_date 2026-08-01 locally.
Roll back the Worker only (instant, data untouched):
  cd C:\Users\Administrator\Till-Recorder\cloudflare
  npx wrangler rollback 6fb74443-23a4-404a-bafd-f120ed95efc9 -m "rollback accessibility/add-register"
Or redeploy old source: copy backups\20261010-pre-accessibility\src\* over cloudflare\src and ...\viewer\* over viewer\, then npx wrangler deploy.
Data: this change only ADDS accessibilityEnabled/accessibilityAt to a status/<id>.json when a 2.0.10 app reports it, and writes enroll/<key>.json (PIN sealed).
  Nothing existing is rewritten. Rollback leaves those extra fields harmlessly in place. To undo them, re-put the status file from r2\ (wrangler r2 object put till-recorder/status/<id>.json --remote --file ... --content-type application/json).
Throwaway test records created during verification were deleted (status/throwaway-e2e-20261010.json, enroll/THROWAWAYtest20261010x.json, one enroll-tries counter).