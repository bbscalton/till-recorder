# Till Recorder / Unruly POS runbook (SOP)

## Add a register (new till)
1. Watch page -> Add register -> type the till name -> Create setup link.
2. Write down the 6-digit PIN shown (it is shown once). Send the link or show the QR to the person at the till.
3. On the till (Android, Unruly POS 2.0.10): open the link / scan the QR. It installs-or-opens the app and pairs by itself; the PIN appears once on the write-it-down screen.
4. Enter that PIN once to activate the device, then do the one approvals step (camera/mic/notifications, Prevent uninstall, battery, Accessibility).
5. The till appears on the Watch wall as Connected. The link works once and expires (default 24 h). Lost PIN: Watch -> Push to POS on that till.

## "Accessibility off" on a tile
Meaning: the till's heartbeat says Accessibility is OFF, so the screen is not being watched (an app update or restart can switch it off).
Fix: at the till open Unruly POS - the blocking "Turn Accessibility back on" prompt opens Accessibility settings - turn Unruly POS on. The tile clears within seconds.
A loud notification is also posted after boot / update while it is off. Tills on older apps (and Windows) show no flag: that means unknown, not on.

## Change safety
Before any Worker change: snapshot with docs/RESTORE.md (cloudflare/tools/r2dump), then deploy, then compare (tools/r2dump/compare.ps1) and delete throwaway test records.