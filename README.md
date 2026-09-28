# Till Recorder

Till Recorder records a point-of-sale screen and camera. The Android app runs on the register tablet. The Windows app runs on the POS PC, stays in the tray, and records the desktop and webcam. Both pair with a code from the watch page and show up on the same site.

Clips upload to the owner's Cloudflare account. The website is in `viewer/`.

## Download

- Android APK: https://github.com/bbscalton/till-recorder/releases/download/v1.5.9/till-recorder.apk
- Windows: https://github.com/bbscalton/till-recorder/releases/download/v1.5.9/till-recorder-windows.zip — unzip and run `TillRecorder.exe`

## Pair

On https://till-recorder.neuereatec.workers.dev/watch, press **Pair a register**. Enter the register name and that code. You do not type a long token.

On Android, turn on **Till Recorder** under Accessibility once. It captures the screen itself, with no system screen-share prompt. Allow the microphone and the camera. A notice stays visible, and watching stays on after a restart.

On Windows, the tray app pairs with the code and keeps running.

Recording starts when the screen changes and stops about 20 seconds after the screen goes still.

## Watch

Open the site and sign in. The screen and the front camera or webcam sit side by side. The camera stays off until you turn it on from the watch page. Android live screen is about 3 fps, because of the phone screenshot limit. The Windows desktop is smooth, about 15–30+ fps. The camera is real video. Delete a clip or a whole day from the watch page.

The recording includes everything visible on the register, and the text typed on it is saved with the clip. Card numbers can appear in a clip or in that typed text if the point-of-sale app shows or asks for them.
