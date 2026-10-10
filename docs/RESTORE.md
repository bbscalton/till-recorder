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
