# OpenCode for Android

The OpenCode app for Android, in Kotlin with Jetpack Compose and Material 3
Expressive. The iOS app is the spec: same features, flows, copy and server contract, with
Android-native visuals.

## Try it on Android

**Sideload instead:** every push to `dev` builds a debug APK and publishes it as the rolling
[`dev-latest` pre-release](https://github.com/kuuzeeai/AI-Open/releases/tag/dev-latest).
It runs on Android 13 or newer.

1. On your phone, open
   [opencode-android.apk](https://github.com/kuuzeeai/AI-Open/releases/download/dev-latest/opencode-android.apk).
2. Open the downloaded file. Android asks to allow installing apps from your browser: tap
   **Settings**, turn on **Allow from this source**, then go back and tap **Install**.
3. Open **OpenCode** and pair with your computer: run `opencode pair --remote` there (works from
   any network) or type `/pair` in OpenCode, then scan the code. **Enter a Server Manually**
   works too.

The version (Settings, at the bottom) ends with the commit it was built from (for example `0.1.0-6be3053`).
Builds are debug-signed for now, so if Android says the app isn't installed when updating,
uninstall the previous build first.

To install from a computer instead: `adb install -r opencode-android.apk`.

## Build

```sh
./gradlew :app:assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
```

Needs JDK 17 and the Android SDK with platform 37.