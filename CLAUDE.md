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
origin   → https://github.com/AppHazard-Studios/Overdrive-FORKED  (My fork)
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
`WebViewFragment.shouldOverrideUrlLoading()` intercepts any navigation to `/events` or `/events.html` and redirects to `RecordingLibraryFragment` via `R.id.eventsFragment`. The HTML page is never loaded on-screen. Check `WebViewFragment.kt` lines 323–343 if this ever needs revisiting.

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

All work is on branch `main`.

## Current Task: Port Overdrive Upstream v10 into Our Fork

### Background
The upstream repo we forked from has released **v10** with major changes. Our fork has been synced and the upstream changes are available on the upstream-sync branch (confirm exact branch name before starting). The bulk of the diff lives in `app/src/main/`.

### Upstream v10 Changelog (per maintainer)
- **Camera reconfiguration** — new setup flow to identify and assign correct camera/video feeds across BYD models; resolves mismatched or swapped inputs across trims and model years.
- **Status pill overlay** — persistent floating indicator for real-time recording and trip status; auto-hides when ACC is off, reappears when car starts.
- **MQTT SSL/TLS support** — secure connections to MQTT brokers (Home Assistant, Mosquitto with TLS/SSL) now work properly.
- **Surveillance detection overhaul** — major rework of the motion detection pipeline. Multi-camera trigger selection, improved algorithm with fewer false positives, new filters (sensitivity cooldown, minimum motion area), preset configs (parking, outdoor, etc.).
- **BYD camera no-signal fix** — resolves native camera signal loss when Overdrive runs alongside BYD dashcam.
- **CPU performance** — ~10–15% lower CPU usage across recording/surveillance pipeline.
- **Event deletion fix** — automated event deletion now properly removes files from storage.
- **SOH and energy display fixes** — corrected SOH estimation; fixed incorrect kWh consumption on trip details; charging power now displays correctly.

### Workflow — Stop and Wait Between Phases

#### Phase 1 — Investigation (read-only, no code edits)
1. Diff the upstream-sync branch against our working branch, focused on `app/src/main/`.
2. Map each v10 changelog item to the actual files / commits / modules touched.
3. Identify any of **our** customizations that overlap with files or systems being changed upstream.
4. Surface conflict zones, risky areas, and any breaking API changes.
5. **Deliverable:** a written summary — files changed, scope per feature, overlap with our work. No code edits.

#### Phase 2 — Strategy Analysis
Critically evaluate both integration strategies and recommend one:

- **Option A — Merge upstream into our branch.** Port v10 changes into the existing fork branch, resolving conflicts in place. Preserves our git history and customizations where they sit.
- **Option B — Rebase our work onto upstream v10.** Treat upstream v10 as the new base and re-apply our customizations on top. Cleaner result, but requires re-doing and re-validating our changes.

For each option, lay out: effort, risk, conflict surface, ease of pulling future upstream releases, history cleanliness. End with a clear recommendation and reasoning. Wait for my confirmation before moving on.

#### Phase 3 — Implementation Plan
Once a strategy is confirmed, produce a step-by-step plan: branch setup, order of operations, areas needing manual conflict resolution, testing checkpoints, rollback plan. Wait for approval.

#### Phase 4 — Implementation
Execute only after the Phase 3 plan is approved. One consolidated change per response per my standing rules.

### Constraints
- **Plan before code.** No edits during Phases 1 or 2.
- **Pause for confirmation** at the end of every phase.
- Keep changes scoped to `app/src/main/` unless investigation shows otherwise — flag if it spreads.
- If a problem recurs twice, stop and propose a different approach.
