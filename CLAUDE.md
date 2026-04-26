# CLAUDE.md

## What This Project Is

Fork of OverDrive — an Android dashcam/sentry app that runs on BYD DiLink v3 head units (not a phone). Uses the car's built-in cameras, parking radar, and vehicle APIs.

- **Fork (origin):** `https://github.com/AppHazard-Studios/Overdrive-FORKED`
- **Upstream:** `https://github.com/yash-srivastava/Overdrive-release`
- **Test device:** BYD Atto 3 (Global) — already running, already confirmed working
- **Upstream sync:** `main` is confirmed in sync with upstream v10 as of April 2026. Do not suggest pulling upstream updates unless asked.

---

## Repository Setup — Two Remotes

```
origin   → https://github.com/AppHazard-Studios/Overdrive-FORKED  (My fork)
upstream → https://github.com/yash-srivastava/Overdrive-release   (Yash's original)
```

Both remotes are already configured. `upstream-sync` is a local tracking branch for `upstream/main` — used only for diffing, never pushed to origin.

### Branch Structure

| Branch | Purpose |
|---|---|
| `main` | Fork base — includes all upstream v10 changes plus all fork customizations. |
| `upstream-sync` | Tracks `upstream/main`. For comparison only, never pushed. |
| `feature/*` | Fork-specific features |
| `fix/*` | Fork-specific bug fixes |
| `contrib/*` | Upstream contribution candidates — cherry-picked from feature/* |

**Never commit directly to `main`.** Always branch first.

---

## Claude Code Behaviour Rules

- Do not add co-author attribution to any commits. No "Co-authored-by: Claude" or any AI attribution in commit messages.
- Do not create pull requests. He does this manually.
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
# He opens PR: feature/my-feature → main on AppHazard-Studios/Overdrive-FORKED
```

### Contributing something upstream

```bash
# Cherry-pick the specific commit(s) from a feature branch
git checkout -b contrib/my-fix
git cherry-pick <commit-hash>
git push -u origin contrib/my-fix
# He opens PR: contrib/my-fix → main on yash-srivastava/Overdrive-release
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
| `CameraDaemon.java` | GPU pipeline, HTTP server, recording, telemetry — DO NOT MODIFY |
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
       │    ├── H264CircularBuffer (pre-event buffer)
       │    └── TBC smoothing (Dynamic Time-Base Corrector, EMA over ~5.5fps)
       └── GpuDownscaler (320×240 @ 2fps)
            └── GpuSurveillancePipeline
                 ├── MotionPipelineV2 (v10) — 6-stage per-quadrant filter
                 │    └── native_motion + motion_pipeline_v2 (C++/NEON JNI)
                 ├── CrossQuadrantTracker / FoveatedCropper (v10)
                 └── TelegramNotifier
```

### Recording Format — Single Mosaic File
The BYD HAL outputs one 5120×960 panoramic strip. `GpuMosaicRecorder` GPU shader composes this into a **2560×1920 2×2 grid → single MP4 per event**. There is no per-camera file. This cannot be changed without touching DO NOT MODIFY files.

Playback of individual camera angles is done at the UI layer: one `MediaPlayer` → one `SurfaceTexture` → GL_TEXTURE_EXTERNAL_OES texture → four UV-cropped GLES2 quads. See `MultiCameraGLView.kt`.

**Telemetry sidecar:** `GpuMosaicRecorder` writes a `.json` file alongside each `.mp4` at recording close. Contains speed, gear, pedal, and signal data sampled at ~5fps. The overlay bar is pinned to the FRONT camera quadrant (NDC 0.9167→1.0, x: -1.0→0.0) — physically calibrated; do not adjust without on-car verification.

---

## ⚠️ Two Separate UIs — Read This Before Touching Any UI File

### Current focus: Android on-screen UI only
The remote web UI (browser access) is NOT the current work priority. Do not edit `assets/web/` files unless explicitly asked. Remote Web UI changes require the physical car with daemon running — they cannot be verified in the emulator.

---

### Android On-Screen UI — Screen-by-File Mapping

Before editing anything, look up the screen here. Do not assume. Do not guess.

| Screen | Implementation | Files to edit |
|---|---|---|
| **Events / Recordings** | **NATIVE Kotlin** — NOT HTML | `RecordingLibraryFragment.kt`, `RecordingAdapter.kt`, `item_recording.xml`, `fragment_recording_library.xml`, `RecordingFile.kt` |
| **Video Player** | **NATIVE Kotlin** — NOT HTML | `MultiCameraPlayerFragment.kt`, `fragment_multi_camera_player.xml`, `MultiCameraGLView.kt` |
| Dashboard | WebView → `local/index.html` | `assets/web/local/index.html`, `assets/web/shared/*.js` |
| Recording Settings | WebView → `local/recording.html` | `assets/web/local/recording.html`, `assets/web/shared/recording.js` |
| Surveillance Settings | WebView → `local/surveillance.html` | `assets/web/local/surveillance.html`, `assets/web/shared/surveillance.js` |
| Performance | WebView → `local/performance.html` | `assets/web/local/performance.html` |
| ABRP | WebView → `local/abrp.html` | `assets/web/local/abrp.html` |
| Trips | WebView → `local/trips.html` | `assets/web/local/trips.html` |

**The events page is native Kotlin. `assets/web/local/events.html` and `assets/web/shared/events.js` are for the remote browser UI only. Editing those files has zero effect on what the user sees in the Android app.**

#### Why the events page is native
`WebViewFragment.shouldOverrideUrlLoading()` intercepts any navigation to `/events` or `/events.html` and redirects to `RecordingLibraryFragment` via `R.id.eventsFragment`. The HTML page is never loaded on-screen.

#### Mandatory pre-coding check
Before editing any file for an on-screen UI task:
1. Identify the screen from the table above
2. If it's NATIVE: edit the Kotlin/XML files only
3. If it's WebView: confirm the page isn't intercepted in `WebViewFragment.shouldOverrideUrlLoading()`
4. Never edit `assets/web/` files for on-screen Android UI work unless the screen is confirmed WebView

---

### Remote Web UI (browser-based) — NOT current priority

- Accessed via browser on LAN / Cloudflare tunnel / Zrok
- Served by `HttpServer.java` — extracts `assets/web/` to `/data/local/tmp/web/` at daemon start
- `assets/web/shared/events.js`, `assets/web/local/events.html` are the REMOTE events page
- Cannot be verified in emulator — requires physical car with daemon running
- When remote UI changes are needed: update both the native layer AND `assets/web/` files separately

**Rule: A change to `assets/web/` does NOT affect the Android on-screen UI for the events or video player screens. A change to Kotlin/XML does NOT affect the remote browser UI. They are independent.**

**Web asset caching note:** `HttpServer.java` extracts `assets/web/` to `/data/local/tmp/web/` once at daemon start. If that directory exists from a prior install, old files are served. After updating web assets, either `adb shell rm -rf /data/local/tmp/web` or do a full uninstall + reinstall to force re-extraction.

---

## DO NOT MODIFY — Ever

- `CameraDaemon.java` — fragile static god class. Any change here = on-car test required.
- `GpuMosaicRecorder.java`, `GpuDownscaler.java` — GPU pipeline
- `native_motion.cpp` — C++ NEON SIMD via JNI
- `motion_pipeline_v2.cpp`, `texture_tracker.cpp` — v10 C++ surveillance pipeline (on-car test required for any change)
- `BydDataCollector.java` — hardware reflection bridge
- `DaemonBootstrap.java` — daemon launch + privilege setup

---

## Build & Test

| Change type | Test environment |
|---|---|
| Android UI (HTML/JS/Kotlin layout) | Android emulator |
| Remote Web UI | Physical car with daemon running |
| Daemon, camera, radar, hardware | BYD Atto 3 via USB sideload |

Build APK: Android Studio → Build → Build APK (or Generate Signed APK for release)

**OpenCV guard:** `texture_tracker.cpp` uses `#if HAVE_OPENCV` (numerical). CMake passes `-DHAVE_OPENCV=0` when opencv-mobile is absent. Must be `#if`, not `#ifdef` — the symbol is always defined, only the value differs.

---

## Known Limitations — Document Only, Never "Fix"

- `Safe.java` AES key is split into 4 fragments but trivially reassembled by any decompiler. Not real security. NDK/Keystore not feasible in app_process context. Add a comment, move on.
- Mixed Java/Kotlin split is historical. Don't migrate for its own sake.

---

## v10 Port Status — Gaps, Partial Implementations, Action Items

The upstream v10 merge landed in April 2026 (PR #15). The following items from the v10 changelog are either incomplete or need on-car verification. Address these before treating v10 as fully shipped.

---

### 1. Status Pill Overlay — PARTIAL (class files missing from upstream)

**State:** Partially shipped. Layouts and renderers are present but the service class itself was never committed by upstream.

**What's present:**
- `res/layout/overlay_status.xml` — full pill + expanded overlay layout
- `res/drawable/overlay_pill_background.xml`, `overlay_expanded_background.xml`, `overlay_action_button_bg.xml`
- `res/drawable/ic_overlay_rec_active/inactive.xml`, `ic_overlay_trip_active/inactive.xml`
- `OverlayBitmapRenderer.java` — renders trip/recording state onto a Canvas bitmap
- `OverlayDoubleBuffer.java` — double-buffered overlay frame management

**What's missing (never committed by upstream):**
- `StatusOverlayService.kt/.java` — the foreground service that creates the `SYSTEM_ALERT_WINDOW` overlay, binds to the daemon, and drives `OverlayBitmapRenderer`
- `SetupGuideDialog.kt/.java` — dialog that guides user to grant overlay permission

**Stripped from our port (because classes don't exist):**
- `SYSTEM_ALERT_WINDOW` permission in `AndroidManifest.xml`
- `<service android:name="...StatusOverlayService">` manifest entry
- `startStatusOverlay()` method call in `MainActivity.kt`

**To complete:** Implement `StatusOverlayService` as a foreground service using `SYSTEM_ALERT_WINDOW`, `WindowManager.addView()`, and `overlay_status.xml`. Implement `SetupGuideDialog` to direct user to Settings → Apps → Special app access → Display over other apps. Then restore the three stripped references. Requires on-car test.

**Priority:** Medium — visible feature gap, but app is functional without it.

---

### 2. Surveillance Config Persistence — NOT WORKING on daemon restart

**State:** Bug. User-configured surveillance settings (sensitivity, preset, camera selection, cooldown, min motion area) are saved by the UI but silently discarded on every daemon restart.

**Root cause:** `SurveillanceEngineGpu.java` line 281 hardcodes `applyEnvironmentPreset("outdoor")` at init and never calls `SurveillanceConfigManager.loadConfig()`. The config manager (`SurveillanceConfigManager.kt`) correctly persists to/from `UnifiedConfigManager` and is used by `SurveillanceApiHandler` on API calls — but the engine ignores it at startup.

**Fix required:**
- In `SurveillanceEngineGpu.java` `init()` or `initPipelineV2()`, call `SurveillanceConfigManager.loadConfig()` and apply the returned config to `pipelineV2Config` before calling `pipelineV2.init()`.
- If no saved config exists, fall back to `applyEnvironmentPreset("outdoor")` as default.
- The `SurveillanceApiHandler` already has the save path wired correctly; only the load path at startup is missing.

**Files to edit:** `SurveillanceEngineGpu.java` (init section, around line 278–285), `SurveillanceConfigManager.kt` (verify `loadConfig()` returns a usable config object).

**Priority:** High — user settings silently reset to defaults every time the car restarts.

---

### 3. MotionPipelineV2 Init Failure — Silent Surveillance Blackout

**State:** Risk. If `MotionPipelineV2.init()` fails on first on-car deployment (JNI library load failure, native crash, or missing OpenCV), surveillance silently stops processing all frames. No fallback, no UI alert, no log surfacing to the user.

**Current behaviour:** `SurveillanceEngineGpu` sets `pipelineV2 = null` on init failure and logs `"V2 pipeline not initialized — skipping frame"` at warn level on every frame. This is daemon-side only — the user sees no motion alerts and has no indication why.

**What v10 removed:** The old motion detection path (v1) was replaced wholesale by `MotionPipelineV2`. There is no fallback path.

**Recommended fix:**
- Add a daemon→UI status broadcast when `pipelineV2` init fails, surfacing it in the surveillance status indicator in the web UI.
- Or at minimum: log at ERROR level (not warn) and write a sentinel file that the UI can poll to show a "Surveillance inactive" badge.
- Do NOT implement a v1 fallback — that code is gone and the effort is not worth it.

**Files to examine:** `SurveillanceEngineGpu.java` (init failure path), `SurveillanceIpcServer.java` (status broadcast mechanism), `assets/web/shared/surveillance.js` (UI status display).

**Priority:** Medium — only triggers on a hard init failure. Verify first on-car that the pipeline initialises correctly; if it does, this is a latent risk only.

---

### 4. MQTT SSL/TLS — Backend Complete, UI Missing Toggle

**State:** Backend done, UI gap. SSL connections work but users cannot enable them from the settings page.

**What works:** `MqttConnectionManager.java` applies `ProxyHelper.getTrustAllSslFactory()` when `trustAllCerts` is set and the URI scheme is `ssl://` or `tls://`. The trust-all factory is correctly wired at three connection points (lines 117, 126, 142).

**What's missing:** `assets/web/local/mqtt.html` has no toggle for `trustAllCerts` and no certificate upload form. Users with self-signed broker certs (Home Assistant, local Mosquitto) cannot enable SSL without manually editing config via ADB.

**Fix required:** Add to `mqtt.html`:
- A checkbox or toggle for "Trust all certificates (self-signed)" that sets `trustAllCerts` in the MQTT config
- Optionally: an input field for the broker URI with `ssl://` / `mqtts://` prefix hint
- Wire through `MqttApiHandler.java` — the config field already exists in `MqttConnectionConfig.java`

**Files to edit:** `assets/web/local/mqtt.html`, confirm `MqttApiHandler.java` already handles `trustAllCerts` in the config save path (it does).

**Priority:** Medium — SSL works but is inaccessible from the UI. Affects anyone using Mosquitto with TLS or Home Assistant with HTTPS MQTT.

---

### 5. Camera Reconfiguration Flow — Needs On-Car Verification

**State:** Wired but untested on physical hardware.

**What's present:** Drawer menu item `nav_reconfigure_camera`, `onReconfigureCameraClicked()` in `MainActivity.kt` (clears saved camera probe config via `UnifiedConfigManager`, kills daemon via `AdbDaemonLauncher`, triggers auto-restart with full camera probe), `dialog_setup_guide.xml` layout, `ic_camera_probe.xml` drawable.

**What needs verifying on the Atto 3:**
- Does `dialog_setup_guide.xml` render correctly in landscape on the DiLink screen?
- Does clearing the probe config and restarting the daemon correctly trigger the camera identification flow?
- Does the resulting camera assignment actually fix swapped/mismatched feeds for Global Atto 3?

**Priority:** Low for now — the existing camera config is already working on the test device. Only needed if feeds appear swapped after a firmware update or on a different trim/model year.

---

### 6. Multi-Camera UV Calibration — On-Car Only

**State:** Set from code inspection. Not verified on physical hardware.

`MultiCameraGLView.kt` UV coordinates for each camera quadrant in the `CameraView` enum were derived from the mosaic layout, not observed on the car. If any feed appears vertically inverted, swap `v0`/`v1` for that camera entry. Requires the Atto 3 to confirm.

---

## On-Car Test Checklist (Priority Order)

When next deploying to the Atto 3, verify these in order:

1. **MotionPipelineV2 init** — does surveillance start cleanly? Check daemon logs for `"V2 pipeline initialized"` vs `"not initialized"`.
2. **Surveillance detection** — does motion in the camera FOV trigger an event? Check all four quadrants independently.
3. **Surveillance config persistence** — change a setting (e.g. sensitivity), restart the car, confirm the setting survived.
4. **Camera reconfiguration** — trigger from drawer menu; confirm the probe flow runs and camera feeds are assigned correctly.
5. **MQTT SSL** — if using a TLS broker, verify connection works (currently requires manual config edit until UI toggle is added).
6. **Status overlay** — N/A until `StatusOverlayService` is implemented.
7. **Multi-camera UV** — confirm no feeds are vertically inverted.
