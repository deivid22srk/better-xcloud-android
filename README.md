# Better xCloud for Android

An unofficial, open-source Android app that wraps [Xbox Cloud Gaming (xCloud)](https://www.xbox.com/play) in a **native Material 3 UI** with the [Better xCloud](https://github.com/redphx/better-xcloud) userscript auto-injected for streaming.

> **Why?** The official Xbox Game Pass app for Android is closed-source, and using the Better xCloud userscript in a Tampermonkey-compatible browser (Kiwi Browser) means doing everything inside a WebView. This project gives you the best of both worlds: a beautiful **native Material 3 home screen** for browsing your library, and a hidden WebView that only surfaces for streaming.

## Architecture

```
[App Launch]
     │
     ▼
[Splash] → [AuthActivity]
              │  (hidden WebView, loads xbox.com/play, signs in)
              │  (auto-injects Better xCloud userscript at document-start)
              ▼
        [BxApp.sharedWebView retained in process singleton]
              │
              ▼
[MainActivity — Material 3 Home]
   ├── TopAppBar (gamertag, settings, refresh, sign-out)
   ├── SwipeRefreshLayout
   └── RecyclerView grid of Game Pass Cloud titles
              │ (click a game)
              ▼
[StreamActivity — fullscreen WebView]
   Reuses sharedWebView → navigates to xbox.com/play/games/<id>
   Injects userscript again for the stream session
              │ (back)
              ▼
        [Back to MainActivity]
```

The WebView is created once (in `AuthActivity`) and kept alive for the entire app process via `BxApp.sharedWebView`. This means cookies, localStorage, MS auth tokens, and the Better xCloud settings all persist across activity transitions without ever needing to re-login.

## Features

### Native Material 3 UI
- **Home screen** with grid of Game Pass Cloud titles (cover art via Glide)
- **Material 3 theming** (dark baseline, Xbox green seed, surface levels)
- **Splash screen** (AndroidX core-splashscreen)
- **Swipe-to-refresh** library
- **Top app bar** with gamertag, settings, refresh, sign-out
- **Empty/signed-out states** with material buttons
- **Pull-to-refresh** library

### Native Settings screen
- Stream: resolution (auto/720p/1080p/1600p), codec profile, bitrate, combine audio, prevent resolution drops
- Controller: touch mode, touch opacity, gamebar position, MKB enable
- Interface: controller-friendly, hide scrollbar, skip splash, reduce animations, loading screen options
- Audio: mic on play, volume booster
- Region: server region (US/EU/BR/JP/AU/Asia), bypass restriction, IPv6
- Advanced: block tracking, screenshot filters

All settings are written to `localStorage["BetterXcloud"]` so the userscript picks them up natively — no bridge juggling.

### Streaming
- **Desktop Edge User-Agent** (forces full desktop xCloud experience)
- **Better xCloud userscript injected at document-start** with `BX_FLAGS.SafariWorkaround=false` (avoids the "Falha ao executar o Better xCloud" overlay that the original userscript shows on non-Safari browsers when readyState != 'loading')
- **Microphone permission** wired through `WebChromeClient.onPermissionRequest` for in-game voice chat
- **Immersive fullscreen**, keep-screen-on, hardware Back navigates WebView history
- **Bluetooth gamepad** support via system Gamepad API
- **File chooser** wired for screenshot / upload dialogs
- **HTTPS-only** network security config (xbox.com / xcloud.com / microsoft.com / xboxlive.com / msftauth.net)

## Build

### Prerequisites
- JDK 17
- Android SDK 34 + Build Tools 34.x
- Gradle 8.7 (via wrapper)

### Local build
```bash
chmod +x ./gradlew
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

### GitHub Actions
Every push to `main`/`master`, every PR, and every tag `v*` triggers `.github/workflows/build.yml`:
1. Sets up JDK 17 + Gradle 8.7
2. Runs `./gradlew assembleDebug assembleRelease`
3. Uploads both APKs as artifacts (30-day retention)
4. On `v*` tags, attaches both APKs to a GitHub Release

## How the userscript is injected (and why SafariWorkaround is disabled)

The Better xCloud userscript's `src/index.ts` has this guard:

```ts
if (isFullVersion() && BX_FLAGS.SafariWorkaround && document.readyState !== 'loading') {
    // Show "Falha ao executar o Better xCloud" overlay
    // Link to https://better-xcloud.github.io/troubleshooting/
    throw new Error('[Better xCloud] Executing workaround for Safari');
}
```

This workaround exists because Safari/iOS sometimes needs a forced reload after login. On Android, `WebViewClient.onPageStarted` fires *after* the document has begun parsing, so `document.readyState` is already `'loading'` or `'interactive'` — the check fails and the overlay appears.

The fix is to set `window.BX_FLAGS.SafariWorkaround = false` **before** the userscript reads its flags. The userscript does:

```ts
BX_FLAGS = Object.assign(DEFAULT_FLAGS, window.BX_FLAGS || {});
```

So setting `window.BX_FLAGS = { SafariWorkaround: false, ... }` in a preamble before injecting the userscript body disables the workaround cleanly. This is exactly what `AuthActivity.injectBetterXcloud()` and `StreamActivity.injectBetterXcloud()` do.

## How the game library is fetched

When the user signs in, `XcloudBridge.fetchLibraryAndCache()` runs JavaScript inside the page context that:

1. Calls `https://catalog.gamepass.com/sigls/v2?id=<GALLERY_ID>&market=US&language=<lang>` (cookies are sent automatically)
2. Takes the title IDs from the response (skipping index 0 which is metadata)
3. Calls `https://displaycatalog.mp.microsoft.com/v7.0/products?bigId=<id>&...` to fetch title + image URLs
4. Returns the JSON to Kotlin via `BxAndroid.onLibraryResult(...)` JavascriptInterface callback

The gallery IDs come from `redphx/better-xcloud`'s `src/enums/game-pass-gallery.ts` (we use `ALL` for the main library).

## How settings persist

The userscript stores all preferences as a JSON object in `localStorage["BetterXcloud"]`. The native Settings screen reads this via:

```js
BxAndroid.onSettingsRead(localStorage.getItem('BetterXcloud') || '{}');
```

And writes back via:

```js
localStorage.setItem('BetterXcloud', <new JSON>);
```

The userscript detects changes to localStorage and applies them live.

## Updating the bundled userscript

The bundled userscript at `app/src/main/res/raw/better_xcloud_user.js` is the official `dist/better-xcloud.user.js` from [redphx/better-xcloud](https://github.com/redphx/better-xcloud). To update:

```bash
curl -sSL \
  https://raw.githubusercontent.com/redphx/better-xcloud/typescript/dist/better-xcloud.user.js \
  -o app/src/main/res/raw/better_xcloud_user.js
git commit -am "Update bundled Better xCloud userscript"
```

Then bump `versionCode` / `versionName` in `app/build.gradle.kts` and tag a release.

## Permissions

| Permission | Why |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | Streaming |
| `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS` | In-game voice chat |
| `BLUETOOTH`, `BLUETOOTH_CONNECT` | Bluetooth gamepads |
| `VIBRATE` | Controller haptics |
| `WAKE_LOCK` | Keep screen on while streaming |

No location, contacts, SMS, storage, or call permissions.

## Disclaimer

This project is **not affiliated with Microsoft, Xbox, or redphx**. All Xbox / xCloud trademarks belong to Microsoft. The Better xCloud userscript is © its original author under the MIT license. Use only with a valid Xbox Game Pass Ultimate subscription.

## License

MIT — see `LICENSE`.
