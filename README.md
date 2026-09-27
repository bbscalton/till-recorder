# Till Recorder

Install the Android app on the register tablet. After you arm it once, it watches the screen and saves a clip only while the screen is changing, the same idea as motion detection. A still register screen is not saved. While someone is using it, a live picture is sent too.

Clips are stored in Cloudflare R2. The website is in `viewer/`. Open the home page, then Watch, and sign in with the token.

## Install the app

The APK to install on the tablet is the download on the website, or:

`android/app/build/outputs/apk/debug/app-debug.apk`

On the watch page, press **Pair a register**. On the tablet, enter the register name and that code, then tap **Connect**.

Turn on **Till Recorder** under Accessibility. You only do that once. If Android blocks it, open App info and allow restricted settings, then turn it on. Tap **Start watching**. Allow the microphone and the camera. Watching stays on after that, including after a restart. A notice stays on the tablet while it is watching.

Recording starts when the screen changes and stops about 20 seconds after the screen goes still.

## Watch live or play a clip

Open the GitHub Pages site, or the same viewer on the Cloudflare address, and sign in with the token. Pick the register. The screen and the front camera sit side by side. The front camera stays off until you press **Turn on**, and **Turn off** closes it on the tablet. The bar below is the saved clips from times the screen was active.

The recording includes everything visible on the register. Use a point-of-sale app that hides card numbers.
