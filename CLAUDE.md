# CLAUDE.md

## What This Project Is

Fork of OverDrive — an Android dashcam/sentry app that runs on BYD DiLink v3 head units (not a phone). Uses the car's built-in cameras, parking radar, and vehicle APIs.

- **Fork (origin):** `https://github.com/AppHazard-Studios/Overdrive-FORKED`
- **Upstream:** `https://github.com/yash-srivastava/Overdrive-release`
- **Test device:** BYD Atto 3 (Global) — already running, already confirmed working
- **Upstream sync:** `main` is confirmed in sync with upstream as of April 2026. Do not suggest pulling upstream updates unless asked.

---

## Repository Setup — Two Remotes

```
origin   → https://github.com/AppHazard-Studios/Overdrive-FORKED  (Mike's fork)
upstream → https://github.com/yash-srivastava/Overdrive-release   (Yash's original)
```

Both remotes are already configured. `upstream-sync` is a local tracking branch for `upstream/main` — used only for diffing, never pushed to origin.

### Branch Structure

| Branch | Purpose |
|---|---|
| `main` | Clean mirror of upstream. Fork-only changes do NOT live here. |
| `upstream-sync` | Tracks `upstream/main`. For comparison only. |
| `feature/*` | Fork-specific features (multi-camera viewer, events redesign, etc.) |
| `fix/*` | Fork-specific bug fixes |
| `contrib/*` | Upstream contribution candidates — cherry-picked from feature/* |

**Never commit directly to `main`.** Always branch first.

---

## Claude Code Behaviour Rules

- Do not add co-author attribution to any commits. No "Co-authored-by: Claude" or any AI attribution in commit messages.
- Do not create pull requests. Mike does this manually.
- Do not push unless explicitly asked.
- Never commit to `main`.
- Always create a new branch before making changes: `feature/name` or `fix/name`.
- Push ONLY to `origin` (AppHazard-Studios/Overdrive-FORKED). Never push to upstream.
- One logical change per commit with a clear message explaining what changed and why.

---

## Day-to-Day Workflow

### Adding a fork feature

```bash
git checkout main
git pull origin main
git checkout -b feature/my-feature
# make changes, commit
git push -u origin feature/my-feature
# Mike opens PR: feature/my-feature → main on AppHazard-Studios/Overdrive-FORKED
```

### Contributing something upstream

```bash
# Cherry-pick the specific commit(s) from a feature branch
git checkout -b contrib/my-fix
git cherry-pick <commit-hash>
git push -u origin contrib/my-fix
# Mike opens PR: contrib/my-fix → main on yash-srivastava/Overdrive-release
```

### Syncing with upstream (when needed)

```bash
git fetch upstream --no-tags
git checkout upstream-sync
git reset --hard upstream/main
# Compare with main if needed, then return to work
git checkout feature/whatever
```

---

## Installing on the BYD Atto 3

The original OverDrive app (from Yash) may already be installed. This fork has the **same applicationId** (`com.overdrive.app`) — see below for why this cannot change.

- **First install:** If original app is present, uninstall it first via Android Settings, then `adb install app-arm64-v8a-release.apk`
- **Subsequent updates:** `adb install -r app-arm64-v8a-release.apk` (the `-r` flag replaces in-place)
- Build the APK via Android Studio → Build → Generate Signed / Unsigned APK → pick `arm64-v8a`

---

## Why `applicationId` Cannot Be Changed

`DaemonBootstrap.java` (DO NOT MODIFY) hardcodes `"com.overdrive.app"`. So do:
- `BydCameraCoordinator.java`
- `GpsMonitor.java`
- `ShellPermissionManager.java`
- `DaemonLauncher.java`
- `ServiceLauncher.java`
- `StatusBroadcaster.java`

These are all in the daemon layer which runs as a separate process under a different UID. Changing `applicationId` would silently break the entire daemon layer — cameras, recording, radar, everything. The applicationId stays as `com.overdrive.app` forever.

---

## Architecture

### Two Planes — Understand Which One You're In Before Writing Any Code

**UI Layer** (Kotlin + Jetpack Navigation + WebView)
- Most screens are HTML/JS served by the embedded HTTP server, rendered in `WebViewFragment`
- Safe to test in Android emulator
- This is where almost all current work happens

**Daemon Layer** (Java, launched via `app_process` over ADB TCP)
- Runs independently of the UI at ports 8080, 19876–19878
- Cannot be tested in emulator — requires the physical BYD Atto 3
- Communicates with UI via TCP sockets only (no Android Binder — different UIDs)

| Daemon | Role |
|---|---|
| `CameraDaemon.java` | GPU pipeline, HTTP server, recording, telemetry — 2100 lines, all static state |
| `AccSentryDaemon.java` | ACC/ignition monitoring |
| `SentryDaemon.java` | UID 1000, ACC lock, network UID whitelisting |
| `TelegramBotDaemon.java` | Telegram alerts |
| `GlobalProxyDaemon.java` | sing-box VLESS proxy for BYD SIM |

### BYD Hardware Access
BYD SDK is not public. App bridges via:
- Compile-time stubs in `android.hardware.bydauto.*`
- Runtime reflection in `BydDataCollector` (Class.forName + Method.invoke)
- `PermissionBypassContext` — ContextWrapper that returns PERMISSION_GRANTED for everything

Do not touch any of these. They are the hardware bridge.

### GPU Recording Pipeline
```
BYD AVMCamera (reflection)
  → SurfaceTexture (GL_TEXTURE_EXTERNAL_OES)
  → OpenGL ES
       ├── GpuMosaicRecorder → MediaCodec H.264 → MP4
       │    └── H264CircularBuffer (pre-event buffer)
       └── GpuDownscaler (320×240 @ 2fps)
            └── SurveillanceEngineGpu (motion detection)
                 └── TelegramNotifier
```

### Recording Format — Single Mosaic File
The BYD HAL outputs one 5120×960 panoramic strip. `GpuMosaicRecorder` GPU shader composes this into a **2560×1920 2×2 grid → single MP4 per event**. There is no per-camera file. This cannot be changed without touching DO NOT MODIFY files.

Playback of individual camera angles is done at the UI layer: one `MediaPlayer` → one `SurfaceTexture` → GL_TEXTURE_EXTERNAL_OES texture → four UV-cropped GLES2 quads. See `MultiCameraGLView.kt`.

---

## ⚠️ Dual UI — Critical to Understand

This app has **two completely separate UIs** that must both be considered for any UI change:

### 1. Android App UI (on-screen)
- What displays on the car's head unit screen
- Kotlin fragments + HTML/JS files loaded into `WebViewFragment`
- Test in Android emulator

### 2. Remote Web UI (browser-based)
- Accessed via browser when connecting remotely (LAN, Cloudflare tunnel, Zrok)
- Served by `HttpServer.java` — separate HTML/JS assets from the Android UI
- Cannot be tested in emulator — requires the car running with a daemon active

**Rule: Before making any UI change, identify which UI(s) it affects. If a feature exists in both, both must be updated. Never assume changing one side also changes the other — they are separate files.**

---

## DO NOT MODIFY — Ever

- `CameraDaemon.java` — fragile static god class, 2100 lines. Any change here = on-car test required.
- `GpuMosaicRecorder`, `GpuDownscaler` — GPU pipeline
- `native_motion.cpp` — C++ NEON SIMD via JNI
- `BydDataCollector` — hardware reflection bridge
- `DaemonBootstrap.java` — daemon launch + privilege setup

---

## Build & Test

| Change type | Test environment |
|---|---|
| Android UI (HTML/JS/Kotlin layout) | Android emulator |
| Remote Web UI | Physical car with daemon running |
| Daemon, camera, radar, hardware | BYD Atto 3 via USB sideload |

Build APK: Android Studio → Build → Build APK (or Generate Signed APK for release)

---

## Known Limitations — Document Only, Never "Fix"

- `Safe.java` AES key is split into 4 fragments but trivially reassembled by any decompiler. Not real security. NDK/Keystore not feasible in app_process context. Add a comment, move on.
- Mixed Java/Kotlin split is historical. Don't migrate for its own sake.

---

## What Is Already Shipped — Do Not Redo

### On `main`: UI Cleanup (merged from `ui/dashboard-logs-improvements`)
- Removed persistent log panel from all screens (Android UI only — remote Web UI is separate)
- Logs moved to dedicated sidebar menu item (filterable by source, clearable, exportable)
- Dashboard redesigned to two-column landscape layout
- URL/access mode moved into Remote Access card; PRIVATE/PUBLIC toggle inline
- Fixed hamburger menu navigation consistency; back button works correctly

### On `main`: Events Page Redesign
- Replaced full-month calendar grid (~200dp) with compact single-row bar (~56dp)
- Row: left/right day-navigation arrows + tappable date pill (opens DatePickerDialog) + iOS-style segmented filter (All / Normal / Sentry / Proximity)
- Drawables added: `bg_pill.xml`, `bg_filter_tab_active.xml`

### On `main`: Logs Page Color Unification
- Controls bar unified to `bg_surface` (was `bg_elevated`) to match toolbar
- 1dp divider added between controls bar and log list

### On `main`: Multi-Camera Video Viewer
- `MultiCameraGLView.kt` — GLSurfaceView renderer, one decoder, four UV-cropped quads
- `MultiCameraPlayerFragment.kt` — hosts the GL view, tap-to-swap, overlay controls
- `fragment_multi_camera_player.xml` — layout with primary (75% width) + right column (3 small feeds)
- Navigation wired: `action_global_videoPlayer` routes to `multiCameraPlayerFragment`
- **On-car UV calibration note:** CameraView enum UV coords were set from code inspection. If any camera feed appears vertically inverted on the physical car, swap `v0`/`v1` in that enum entry in `MultiCameraGLView.kt`. This requires an Atto 3 test to verify.

---

## Current Task

None — all planned features from the last session are implemented and on `main`. See Phase 2 below for what's next.

---

## Phase 2 — Code Quality (Upstream Contribution Candidates)

All safe to contribute upstream as individual PRs via `contrib/*` branches:

1. **Deduplicate `PermissionBypassContext`** — copy-pasted between `CameraDaemon.java` and `DaemonBootstrap.java`. One canonical version in `DaemonBootstrap` only.
2. **Delete dead stubs in `CameraDaemon`** — `startStreaming()`, `stopStreaming()`, `startAllStreaming()` just log "removed". Delete them.
3. **`HttpServer` connection timeouts** — 32-thread pool, no timeout enforcement. Add timeouts before tunnel exposure makes this a real risk.
4. **Unit tests** — `RecordingModeManager`, `ProximityGuardController`, `TripDetector` are pure logic, no hardware deps. Add tests.
5. **Remote Web UI multi-camera viewer** — port the GLSurfaceView playback concept to the browser-based player in `HttpServer.java` HTML assets. Separate task, separate files from the Android UI implementation.
