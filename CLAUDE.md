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

### Android On-Screen UI — Screen-by-File Mapping

Before editing anything, look up the screen here. Do not assume. Do not guess.

| Screen | Implementation | Files to edit |
|---|---|---|
| **Dashboard** | **NATIVE Kotlin** — NOT HTML | `DashboardFragment.kt`, `fragment_dashboard.xml` |
| **Events / Recordings** | **NATIVE Kotlin** — NOT HTML | `RecordingLibraryFragment.kt`, `RecordingAdapter.kt`, `item_recording.xml`, `fragment_recording_library.xml`, `RecordingFile.kt` |
| **Video Player** | **NATIVE Kotlin** — NOT HTML | `MultiCameraPlayerFragment.kt`, `fragment_multi_camera_player.xml`, `MultiCameraGLView.kt` |
| **Remote Access** | **NATIVE Kotlin** — NOT HTML | `RemoteAccessFragment.kt`, `fragment_remote_access.xml` |
| Recording Settings | WebView → `local/recording.html` | `assets/web/local/recording.html`, `assets/web/shared/recording.js` |
| Surveillance Settings | WebView → `local/surveillance.html` | `assets/web/local/surveillance.html`, `assets/web/shared/surveillance.js` |
| Performance | WebView → `local/performance.html` | `assets/web/local/performance.html`, `assets/web/shared/performance.js` |
| MQTT | WebView → `local/mqtt.html` | `assets/web/local/mqtt.html`, `assets/web/shared/mqtt.js` |
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

### Status Pill Overlay — Not Planned

Partially shipped from upstream v10 but the service class was never committed by upstream. Not a current priority — the app is fully functional without it.

**What's present (assets, drawables, and renderers are all in the repo):**
- `res/layout/overlay_status.xml` — full pill + expanded overlay layout
- `res/drawable/overlay_pill_background.xml`, `overlay_expanded_background.xml`, `overlay_action_button_bg.xml`
- `res/drawable/ic_overlay_rec_active/inactive.xml`, `ic_overlay_trip_active/inactive.xml`
- `OverlayBitmapRenderer.java` — renders trip/recording state onto a Canvas bitmap
- `OverlayDoubleBuffer.java` — double-buffered overlay frame management

**What's missing (never committed by upstream, intentionally left unimplemented):**
- `StatusOverlayService.kt/.java` — foreground service using `SYSTEM_ALERT_WINDOW` + `WindowManager.addView()`
- `SetupGuideDialog.kt/.java` — dialog to guide user to grant overlay permission

**Stripped from the port (because the service class doesn't exist):**
- `SYSTEM_ALERT_WINDOW` permission in `AndroidManifest.xml`
- `<service android:name="...StatusOverlayService">` manifest entry
- `startStatusOverlay()` call in `MainActivity.kt`

If this is ever wanted: implement `StatusOverlayService` as a foreground service using `overlay_status.xml`, implement `SetupGuideDialog` to direct user to Settings → Apps → Special app access → Display over other apps, then restore the three stripped references. Requires on-car test.

---

## Current Focus — Performance Optimisation

**Goal:** Reduce CPU and GPU load without sacrificing reliable, accurate, high-quality recording.

The recording pipeline is the primary constraint. Everything else is secondary to it. Any change that risks dropped frames, encoder stalls, or reduced detection accuracy must be rejected regardless of the CPU saving.

### What "performance" means here

- **CPU**: reducing background work, poll frequency, unnecessary wakeups, GC pressure
- **GPU**: reducing per-frame shader work, unnecessary texture sampling, off-screen render passes
- **Thermal**: keeping the head unit cool enough that Android doesn't throttle under sustained load (recording + sentry simultaneously is the worst case)
- **Not**: reducing recording resolution, bitrate, or detection sensitivity — these are product requirements, not variables

### Where the load actually comes from

**GPU pipeline (on-car only, DO NOT MODIFY files):**
- `GpuMosaicRecorder` — composes the 5120×960 BYD strip into a 2560×1920 2×2 grid each frame at ~5.5fps via MediaCodec H.264
- `GpuDownscaler` — downsamples to 320×240 @ 2fps for the surveillance pipeline
- `MotionPipelineV2` — 6-stage C++/NEON filter per quadrant at 2fps via JNI

**CPU (daemon):**
- `AccSentryDaemon` wake polling interval when parked
- HTTP server request handling (mostly negligible)
- Telemetry sampling at ~5fps alongside recording

**CPU (UI layer — legitimate targets):**
- `DashboardFragment` HTTP polling (currently every 60s — already conservative)
- WebView JS polling timers on settings pages (paused when not visible via WebView cache)
- `RecordingViewModel` storage stat polling
- Any background timers in `MainActivity`

### Legitimate performance levers (UI layer, safe to change)

| Area | What to change | Expected gain |
|---|---|---|
| Recording config | H.264 bitrate, GOP size, profile/level in recording settings | Storage + encoder CPU |
| Surveillance zones | Disable quadrants with no useful FOV (e.g. rear-only sentry) | MotionPipelineV2 cost scales with active quadrants |
| Dashboard poll interval | Currently 60s — can increase if metrics feel stale | Minor; already low |
| WebView poll intervals | Each settings page has its own JS polling rate — audit and raise floors | Reduces daemon HTTP wakeups |
| AccSentryDaemon wake interval | Tune the parked-mode polling cadence | CPU when parked overnight |

### Approach for any performance change

1. Read the existing code to understand the current cost before proposing a change
2. Estimate the gain — "reduces from N to M calls per minute" is more useful than "should be faster"
3. Changes to DO NOT MODIFY files are off the table regardless of the gain
4. All daemon-layer changes require on-car verification — they cannot be tested in the emulator
5. After a change, the on-car test must confirm no regression in recording continuity or event detection

---

## v10 Port — Unverified Items (On-Car Only)

These were implemented in code but not yet verified on the physical Atto 3. They are lower priority now that performance is the focus, but should be confirmed when next deploying.

### Camera Reconfiguration Flow

**State:** Wired but untested on physical hardware.

`onReconfigureCameraClicked()` in `MainActivity.kt` clears the saved camera probe config via `UnifiedConfigManager`, kills the daemon via `AdbDaemonLauncher`, and triggers auto-restart with full camera probe. Verify:
- `dialog_setup_guide.xml` renders correctly in landscape on the DiLink screen
- Clearing probe config and restarting correctly triggers the camera identification flow
- Camera quad labels (FRONT/RIGHT/REAR/LEFT) remain accurate — they are hardcoded to the fixed BYD HAL mosaic layout and do not change with reconfiguration

**Priority:** Low — current camera config is working on the test device. Only needed if feeds appear swapped after a firmware update or different trim.

### Multi-Camera UV Calibration

`MultiCameraGLView.kt` UV coordinates were derived from code inspection, not confirmed on-car. If any feed appears vertically inverted, swap `v0`/`v1` for that camera entry in the `CameraView` enum.

---

## On-Car Test Checklist (Priority Order)

When next deploying to the Atto 3, verify these in order:

1. **Dashboard** — all four widgets populate (recording storage, sentry events, CPU/GPU/MEM bars, 12V voltage). Tap each card to confirm navigation works.
2. **Remote Access** — QR code appears when a tunnel URL is active; hamburger icon shows (not back arrow).
3. **MotionPipelineV2 init** — check daemon logs for `"V2 pipeline initialized"` vs `"not initialized"`. If init fails, the surveillance page shows a "Motion detection unavailable" warning.
4. **Surveillance detection** — motion in camera FOV triggers an event across all active quadrants.
5. **Surveillance config persistence** — change a setting (e.g. sensitivity), restart the car, confirm it survived.
6. **Camera reconfiguration** — trigger from drawer; confirm probe flow runs and feeds are assigned correctly.
7. **Multi-camera UV** — confirm no feeds are vertically inverted.
8. **WebView caching** — navigate to Recording Settings, go back to Dashboard, return to Recording Settings — second visit should appear immediately with no loading spinner.
9. **Performance under load** — with recording active and sentry armed simultaneously, check the Performance tab for CPU/GPU/thermal headroom. This is the baseline for the optimisation work.
