# Read — Android WebView App

Native Android wrapper for https://read.rohitks.com.np

- Kotlin, Material 3, minSdk 26 (Android 8.0+)
- Full-screen WebView, no browser chrome
- JavaScript, DOM storage, cookies, third-party cookies, service workers
- File uploads, downloads (via DownloadManager)
- Splash screen + offline page with Retry
- In-app navigation for read.rohitks.com.np; external links open in default browser
- Persistent login (cookies + storage retained)

## Build the APK in the cloud (no Android Studio needed)

1. Create a new empty GitHub repo.
2. Upload the contents of this folder to it (drag & drop in GitHub web UI works).
3. Go to the **Actions** tab → the "Build APK" workflow runs automatically.
4. When it finishes (~5 min), open the run and download the **Read-release-apk** artifact.
5. Unzip → install `app-release.apk` on your phone (enable "Install unknown apps").

The workflow is signed with the debug key so it installs directly. For Play Store,
add a real signing config.

## Alternative: Codemagic / Appcircle
Any Android cloud CI works. Point it at the repo, use JDK 17, run
`./gradlew assembleRelease`, download the APK from `app/build/outputs/apk/release/`.
