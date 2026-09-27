# Till Recorder

Install the Android app on the register tablet. After you arm it once, it watches the screen and saves a clip only while the screen is changing, the same idea as motion detection. A still register screen is not saved. While someone is using it, a live picture is sent too.

Clips are stored in Cloudflare R2. The website is in `viewer/`. Open the home page, then Watch, and sign in with the token.

## Install the app

The APK to install on the tablet is:

`android/app/build/outputs/apk/debug/app-debug.apk`

On the tablet, enter:

- Register name
- Cloudflare address (printed when the recorder is deployed)
- Token

Tap **Test connection**, then **Arm this register**. When Android asks what to share, choose **Entire screen** and allow the microphone. Then open the point-of-sale app.

A notice stays on the tablet while it is watching. Recording starts when the screen changes and stops about 20 seconds after the screen goes still.

## Watch live or play a clip

Open the GitHub Pages site, or the same viewer on the Cloudflare address, and sign in with the token. Pick the register. The top picture is the live screen while it is in use. The bar below is the saved clips from times the screen was active.

The recording includes everything visible on the register. Use a point-of-sale app that hides card numbers.
