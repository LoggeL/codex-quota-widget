# Codex Quota Widget

A tiny Android home-screen widget for keeping an eye on Codex / ChatGPT usage limits.

It signs in with the same ChatGPT-managed Codex device-code flow used by the Codex CLI, stores tokens locally on the device, and reads the quota directly from the ChatGPT backend API.

## Features

- Android home-screen widget, optimized for a compact 4x1 layout
- One-time Codex / ChatGPT device-code login
- Shows short-window usage, estimated final usage, weekly usage, and remaining reset time
- Tap the widget to refresh immediately
- In-app widget log for refresh/auth/network debugging
- Aggressive stale-cache fallback so transient DNS/network failures keep showing the last good quota instead of an empty error
- Periodic refresh through Android's normal app-widget update flow
- Local token storage with refresh-token support
- No hosted quota proxy required

## Screens / labels

The widget displays:

- plan badge, e.g. `PRO`, `PLUS`, `TEAM`
- short quota window, e.g. `5h 42→58% · rem 1h 23m` where the second number is estimated usage at reset
- weekly quota window, e.g. `W 23→64% · rem 4d 12h`
- actual-usage bars, with estimated final usage shown in the text label
- pace label, e.g. `on track`, `watch pace`, `over pace`, or `ahead`
- last update timestamp, or cached timestamp if the last refresh fell back to stale data

## Build

Requirements:

- JDK 17
- Android SDK
- Gradle wrapper from this repo

Build a debug APK:

```bash
./gradlew :app:assembleDebug
```

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Install / use

1. Install the APK on Android.
2. Open **Codex Quota**.
3. Tap **Sign in with Codex**.
4. Complete the device-code login in the browser.
5. Add the widget to the home screen.
6. Tap the widget whenever you want an immediate refresh.
7. If refresh looks stuck, open **Codex Quota** and check **Widget log**.

## Security notes

- Access and refresh tokens are stored in Android app-private SharedPreferences.
- The repository does not include personal tokens, APK artifacts, or local build output.
- The OAuth client id is a public client identifier, not a client secret.

## Project status

Small personal utility / experiment. Expect rough edges.

## Release notes

### Next

- Added estimated final usage for the 5h and weekly windows.
- Kept bars as actual usage while showing the forecast in text.
- Added an on-track status label derived from actual usage versus expected pace.
- Added a persistent in-app widget log covering widget updates, tap refreshes, auth refreshes, HTTP status, cache fallback, and render completion.

### 0.3.1

- Cache the last successful quota locally and render it immediately during widget updates.
- Fall back to cached quota for up to 7 days when the ChatGPT usage endpoint has transient DNS/network failures.
- Keep manual refresh forceful, but still avoid blanking the widget if the network request fails.


### 0.3.0

- Switched from a hosted status JSON endpoint to direct Codex / ChatGPT OAuth device login.
- Added refresh-token support.
- Added remaining quota reset time in the widget.
- Normalized plan labels (`PRO`, `PLUS`, `TEAM`, etc.).
- Replaced the placeholder launcher icon.

### 0.2.1

- Fixed widget loading on Android launchers by replacing an unsupported raw `View` with a RemoteViews-safe `TextView` dot.

### 0.2.0

- Fancier 4x1 widget styling.
- Gradient quota bars.
- Animated bar fill on update/tap refresh.
- Better live/fetching/error state labels.
