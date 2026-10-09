# Till Recorder

Till Recorder records a point-of-sale screen and camera. The Android app runs on the register tablet. The Windows app runs on the POS PC, stays in the tray, and records the desktop and webcam. Both pair with a code from the watch page and show up on the same site.

Clips upload to the owner's Cloudflare account. The website is in `viewer/`.

## Download

- Android APK: https://github.com/bbscalton/till-recorder/releases/download/v1.5.13/till-recorder.apk
- Windows: https://github.com/bbscalton/till-recorder/releases/download/v1.5.13/till-recorder-windows.zip — unzip and run `TillRecorder.exe`
- Unruly POS 1.0.0 (Android APK and Windows setup): https://github.com/bbscalton/till-recorder/releases/tag/unruly-pos-1.0.0

## Pair

On https://till-recorder.neuereatec.workers.dev/watch, press **Pair a register**. Enter the register name and that code. You do not type a long token.

On Android, turn on **Till Recorder** under Accessibility once. It captures the screen itself, with no system screen-share prompt. Allow the microphone and the camera. A notice stays visible, and watching stays on after a restart.

On Windows, the tray app pairs with the code and keeps running.

On an iPhone or iPad, open `ios/TillRecorder.xcodeproj` on a Mac. This Windows PC cannot compile it. Set the same signing team on the Till Recorder app and the TillRecorderBroadcast extension, and keep the App Group `group.com.tillrecorder.ios` on both. Run it on the device, not the simulator. Pair with a code from the watch page. You do not type a web address or a token. Then start the screen broadcast: open Control Center, press and hold Screen Recording, and choose Till Recorder. You can also tap the broadcast button in the app. The broadcast stays up until you stop it, and uploads resume after the network comes back. A restart does not start it again. iOS cannot hide the app icon the way Android does. The front camera runs only while the Till Recorder app is open; the screen broadcast continues in the background. Notes are saved only when they are typed inside Till Recorder. iOS cannot read keystrokes from the point-of-sale app.

Recording starts when the screen changes and stops about 20 seconds after the screen goes still.

## Watch

Open the site and sign in. The screen and the front camera or webcam sit side by side. The camera stays off until you turn it on from the watch page. Stop recording on that register pauses new screen, camera, and overhead uploads and leaves the till paired. Start recording resumes them without a new pair code and without pressing Start on the device. Clips already saved stay. Android live screen is about 3 fps, because of the phone screenshot limit. The Windows desktop is smooth, about 15–30+ fps. The camera is real video. Delete a clip or a whole day from the watch page.

The recording includes everything visible on the register, and the text typed on it is saved with the clip. Card numbers can appear in a clip or in that typed text if the point-of-sale app shows or asks for them.

## Accounts

Store staff sign in with Google at https://till-recorder.neuereatec.workers.dev/login. A new account is pending until the owner approves it at https://till-recorder.neuereatec.workers.dev/management. The management page uses the existing owner token. Google users are not admins.

Approved users request a plan from https://till-recorder.neuereatec.workers.dev/account. Prices are $4,000 for 1 week, $6,000 for 1 month, and $15,000 for 3 months. Recordings are kept for 48 hours. A storage add-on is $5,000 per month on top of the plan and keeps recordings for 30 days. Management approves or denies the request and collects payment. The site does not charge a card.

A till can be paired only while the account is approved and a plan is active. An expired plan pauses new recordings and hides video. The pairing stays.

Until a Google client is connected, Continue with Google on the login page opens the Till Recorder client page in Google Cloud.
