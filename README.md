# Codex Quota Widget

Tiny Android home-screen widget for Logge's Codex usage quota.

- Fetches `https://codex-quota.logge.top/status.json`
- Shows short-window (`5h`) and weekly (`W`) usage
- Tap the widget to refresh immediately
- Android refreshes it periodically as a normal app widget

## Build

```bash
./gradlew assembleDebug
```

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Release notes

### 0.2.1

- Fix widget loading on Android launchers by replacing an unsupported raw `View` with a RemoteViews-safe `TextView` dot.

### 0.2.0

- Fancier 4x1 widget styling
- Gradient quota bars
- Animated bar fill on update/tap refresh
- Better live/fetching/error state labels
