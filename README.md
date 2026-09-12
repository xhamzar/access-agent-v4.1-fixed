# Access Agent Android 13 — v4.1

A deterministic, local-first Accessibility automation layer for Android 13/API 33. It exposes a JSON-lines loopback server on `127.0.0.1:8765`.

## v4.1 fixes (code review of v4)

Critical:
- **Manifest**: added `<uses-permission android:name="android.permission.INTERNET"/>`. On Android, creating any TCP socket (even on 127.0.0.1) requires the `inet` group — without the permission `new ServerSocket()` failed with EACCES and the v4 server never actually started. This is why `nc 127.0.0.1 8765` could not connect.
- **GoalEngine**: v4 scored only the flat root node (`observe()` has no `children`), so `action=goal` could never find a target. The planner now walks the full tree via the new `observe_tree` / `publicObserveTree()`.
- **False success**: `goalSatisfied()` returned true for any "buka/open" goal whenever a window existed, even after a failed click. Success now requires the action to succeed AND the foreground package to match a goal token; otherwise the planner honestly reports `inconclusive` / `blocked`.
- **Crash on Android 8–10**: `getStateDescription()` (API 30) was called with `minSdk 26` and threw `NoSuchMethodError` (an `Error`, not caught by `catch Exception`), killing the process. Now guarded by SDK check.

Improvements:
- Server failures are now logged (`logcat -s AccessAgent`) and shown as a toast; accept() failures no longer hot-spin.
- Non-clickable labels are activated via coordinate tap on their bounds.
- Targets already acted on are skipped; history capped at 200 entries.
- `verify:true` without `verify_target` no longer re-clicks 3x.
- Screenshot `HardwareBuffer` closed in `finally` (no leak on error paths).
- `MainActivity` shows live service status + a copy-paste Termux test command (and fixed the literal `\n` bug).
- compileSdk aligned to 34 for AGP 8.5.2; added `gradle.properties`.

## Build

Requires JDK 17. The Gradle 8.7 wrapper is bundled, so no local Gradle install is needed:

```sh
./gradlew assembleDebug     # Linux/macOS/Termuxproot
gradlew.bat assembleDebug   # Windows
# output: app/build/outputs/apk/debug/app-debug.apk
```

## Build on GitHub (no local setup needed)

The repo ships with `.github/workflows/build.yml` (GitHub Actions):

1. Push this project to a GitHub repository (`main` or `master` branch).
2. Every push/PR automatically builds the APK with JDK 17 + Gradle 8.7.
3. Download the result: repo page → **Actions** → latest run → artifact **access-agent-debug-apk**.
4. Optional release: push a tag (`git tag v4.1 && git push origin v4.1`) and the APK is attached to a GitHub Release automatically. `workflow_dispatch` lets you trigger a build manually from the Actions tab.

Android 13 note: sideloaded accessibility apps are a "restricted setting" — first tap the 3-dot menu in App info → *Allow restricted settings*, then enable the service in Accessibility settings. Disable battery optimization for the app so Android does not kill the server.

## Usage in Termux

### 1. One-time setup (on the phone)

1. Install `app-debug.apk` (from GitHub Actions artifact or local build) — allow "install unknown apps".
2. Open **Access Agent** → *Open Accessibility Settings* → enable the service.
   Android 13+: sideloaded apps must first do **App info → ⋮ menu → Allow restricted settings**.
3. A toast "Access Agent listening on 127.0.0.1:8765" means the server is up.
4. Recommended: Settings → Apps → Access Agent → Battery → **Unrestricted** (so Android does not kill the server).

### 2. Termux setup

Use the F-Droid build of Termux (Play Store build is outdated), then:

```sh
pkg update -y && pkg install -y jq netcat-openbsd
```

Install the bundled CLI helper as a global command:

```sh
cd access-agent-v4.1
cp termux/agent $PREFIX/bin/agent && chmod +x $PREFIX/bin/agent
```

### 3. Everyday commands

```sh
agent ping                     # test connection -> {"ok":true,"service":"access-agent-v4.1",...}
agent state                    # foreground app + last event
agent goal "buka Settings"     # deterministic planner opens the app
agent click "Battery"          # click a node by visible text
agent type "Search" "wifi"     # type into a field matched by text
agent tap 540 1200             # coordinate tap
agent swipe 540 1800 540 600   # scroll gesture
agent screenshot ~/ss.jpg      # screenshot decoded from jpg+gzip base64
agent back / agent home        # system navigation
agent batch '[{"action":"home"},{"action":"wait","ms":500}]'
agent raw '{"action":"observe_tree"}'   # any raw protocol command
```

Raw protocol without the helper (every connection = exactly one JSON line):

```sh
printf '%s\n' '{"action":"ping"}' | nc 127.0.0.1 8765
printf '%s\n' '{"action":"goal","goal":"buka Settings","max_steps":8}' | nc 127.0.0.1 8765
```

### Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `connection refused` | service not enabled, killed by battery optimization, or phone rebooted without re-enabling |
| `{"ok":false,"error":"target_not_found"}` | text not on screen — try `agent tree` to inspect, or scroll first |
| gestures do nothing | screen must be ON and unlocked for `dispatchGesture` |
| `goal` returns `inconclusive` | planner clicked something but could not verify (package name does not match the goal token) — check `trace` in the response |
| screenshot empty | screenshot needs Android 11+ (API 30) |

## Security warning

The endpoint has **no authentication**: any app on the device holding the INTERNET permission can connect to 127.0.0.1:8765, drive the UI, and read screenshots. Loopback-only is not a security boundary between apps. For anything beyond a personal lab, add a shared token check in `handle()` (compare against a header/first field) before executing. The service does not grant root or bypass Android security boundaries.
