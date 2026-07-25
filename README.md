# Better xCloud for Android

An unofficial, open-source Android wrapper that loads [Xbox Cloud Gaming (xCloud)](https://www.xbox.com/play) inside a hardened WebView and injects the [Better xCloud](https://github.com/redphx/better-xcloud) userscript automatically.

> **Why?** The official Xbox Game Pass app for Android is closed-source. This project gives you an open-source alternative that combines the convenience of a native Android APK with all the enhancements from the Better xCloud userscript (stream stats, controller shortcuts, mouse/keyboard support, screenshot, region picker, and much more).

## Features

- **Loads `https://www.xbox.com/play` in a desktop-class WebView** (Edge on Windows User-Agent) so xCloud serves the full desktop experience.
- **Auto-injects the Better xCloud userscript** at `document-start` on every page load — no Tampermonkey/Kiwi Browser needed.
- **Native microphone permission** wired through `WebChromeClient.onPermissionRequest` so in-game voice chat works.
- **Immersive fullscreen**, keep-screen-on, hardware Back button navigates WebView history.
- **Bluetooth gamepad support** via the system Gamepad API.
- **File chooser** wired for screenshot saving / upload dialogs.
- **External links** (TrueAchievements, etc.) open in the system browser.
- **Cleartext traffic blocked** via `network_security_config.xml` — only HTTPS to xbox.com / xcloud.com / microsoft.com / xboxlive.com / msftauth.net.
- **Min SDK 26 (Android 8.0)**, target SDK 34.

## Build

### Prerequisites

- JDK 17
- Android SDK 34 + Build Tools 34.x
- Gradle 8.7 (via the included wrapper)

### Local build

```bash
chmod +x ./gradlew
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

For an unsigned release APK:

```bash
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release-unsigned.apk
```

### GitHub Actions build

This repository ships with `.github/workflows/build.yml`. Every push to `main`/`master`, every PR, and every tag `v*` triggers a build that:

1. Sets up JDK 17 and Gradle 8.7.
2. Runs `./gradlew assembleDebug assembleRelease`.
3. Uploads both APKs as GitHub Actions artifacts (retained for 30 days).
4. On `v*` tags, attaches both APKs to a GitHub Release with auto-generated release notes.

To trigger a build manually: **Actions → Build APK → Run workflow**.

## Architecture

```
┌────────────────────────────────────────────┐
│                 MainActivity                │
│  ┌──────────────────────────────────────┐  │
│  │      WebView (desktop Edge UA)        │  │
│  │  ┌────────────────────────────────┐  │  │
│  │  │   xbox.com/play (xCloud SPA)   │  │  │
│  │  │   ┌──────────────────────────┐ │  │  │
│  │  │   │  Better xCloud userscript │ │  │  │
│  │  │   │  (injected at doc-start)  │ │  │  │
│  │  │   └──────────────────────────┘ │  │  │
│  │  └────────────────────────────────┘  │  │
│  └──────────────────────────────────────┘  │
└────────────────────────────────────────────┘
```

The userscript is bundled at `app/src/main/res/raw/better_xcloud_user.js` and loaded into a Kotlin string at runtime, then injected via `WebView.evaluateJavascript()` from `WebViewClient.onPageStarted()` — the closest equivalent to Tampermonkey's `@run-at document-start`.

A minimal `window.BxAndroid` JavaScript bridge is exposed for the script to call native helpers (`openExternal`, `toast`, `version`).

## Permissions

| Permission | Why |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | Streaming |
| `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS` | In-game voice chat |
| `BLUETOOTH`, `BLUETOOTH_CONNECT` | Bluetooth gamepads |
| `VIBRATE` | Controller haptics |
| `WAKE_LOCK` | Keep screen on while streaming |
| `FULLSCREEN` | Immersive mode |

No location, contacts, SMS, storage, or call permissions are requested.

## Updating the bundled userscript

The bundled userscript is the official `dist/better-xcloud.user.js` from [redphx/better-xcloud](https://github.com/redphx/better-xcloud). To update it:

```bash
# From the project root
curl -sSL \
  https://raw.githubusercontent.com/redphx/better-xcloud/typescript/dist/better-xcloud.user.js \
  -o app/src/main/res/raw/better_xcloud_user.js
git commit -am "Update bundled Better xCloud userscript"
```

Then bump `versionCode` / `versionName` in `app/build.gradle.kts` and tag a release.

## Disclaimer

- This project is **not affiliated with Microsoft, Xbox, or redphx**. All Xbox / xCloud trademarks belong to Microsoft.
- The Better xCloud userscript is © its original author under the MIT license — see `LICENSE`.
- Use only with a valid Xbox Game Pass Ultimate subscription. The wrapper does not bypass any authentication or payment.

## License

MIT — see `LICENSE`.
