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
| `main` | Fork base — full feature set, all upstream v10 changes plus fork customizations. |
| `lite/main` | **Stripped-back product variant** — native UI only, no daemons/features we don't use. This is the active development target. See "Current Focus" section. |
| `upstream-sync` | Tracks `upstream/main`. For comparison only, never pushed. |
| `feature/*` | Features targeting `main` |
| `lite/feature-*` | Features targeting `lite/main` — use prefix `lite/` to distinguish |
| `fix/*` | Bug fixes (apply to whichever branch is relevant) |
| `contrib/*` | Upstream contribution candidates — cherry-picked from feature/* |

**Never commit directly to `main` or `lite/main`.** Always branch first.

`lite/main` was branched from `main` and diverges intentionally — do not merge `main` into `lite/main` without reviewing what you're pulling in. Cherry-pick specific commits instead.

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
- On `main`: most settings screens are HTML/JS WebViews
- On `lite/main`: **all screens are native Kotlin** — no WebViews. See "Native-First Mandate" below.
- Safe to test in Android emulator

**Daemon Layer** (Java, launched via `app_process` over ADB TCP)
- Runs independently of the UI at ports 8080, 19876–19878
- Cannot be tested in emulator — requires the physical BYD Atto 3
- Communicates with UI via TCP sockets only (no Android Binder — different UIDs)

| Daemon | Role | `lite/main` |
|---|---|---|
| `CameraDaemon.java` | GPU pipeline, HTTP server, recording, telemetry — DO NOT MODIFY | ✅ Keep |
| `AccSentryDaemon.java` | ACC/ignition monitoring | ✅ Keep |
| `SentryDaemon.java` | UID 1000, ACC lock, network UID whitelisting | ✅ Keep |
| `TelegramBotDaemon.java` | Telegram alerts | ❌ Remove |
| `GlobalProxyDaemon.java` | sing-box VLESS proxy for BYD SIM | ❌ Remove |

**Cloudflare tunnel and Zrok tunnel** are also removed from `lite/main` — these are managed as sub-processes by `CloudflaredController.kt` and `ZrokController.kt`, not Java daemons. Both the daemon management code and their UI entries are stripped.

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

## ⚠️ Screen Map — Read Before Touching Any UI File

### Current state (`main` branch)

| Screen | Implementation | Files |
|---|---|---|
| **Dashboard** | Native Kotlin | `DashboardFragment.kt`, `fragment_dashboard.xml` |
| **Events / Recordings** | Native Kotlin | `RecordingLibraryFragment.kt`, `RecordingAdapter.kt`, `item_recording.xml`, `fragment_recording_library.xml` |
| **Video Player** | Native Kotlin | `MultiCameraPlayerFragment.kt`, `fragment_multi_camera_player.xml`, `MultiCameraGLView.kt` |
| **Remote Access** | Native Kotlin | `RemoteAccessFragment.kt`, `fragment_remote_access.xml` |
| Recording Settings | WebView | `assets/web/local/recording.html`, `assets/web/shared/recording.js` |
| Surveillance Settings | WebView | `assets/web/local/surveillance.html`, `assets/web/shared/surveillance.js` |
| Performance | WebView | `assets/web/local/performance.html`, `assets/web/shared/performance.js` |
| MQTT | WebView | `assets/web/local/mqtt.html`, `assets/web/shared/mqtt.js` |
| ABRP | WebView | `assets/web/local/abrp.html` |
| Trips | WebView | `assets/web/local/trips.html` |

### Target state (`lite/main` branch)

All screens native Kotlin. WebViews completely eliminated. Remote web UI stripped. No `assets/web/` files served.

| Screen | Status | Notes |
|---|---|---|
| Dashboard | ✅ Native — fix bugs | See bug list in Current Focus |
| Events / Recordings | ✅ Native — keep | Fix slow load if present |
| Video Player | ✅ Native — keep | No changes needed |
| Recording Settings | 🔨 Migrate to native | Replace WebView with native fragment |
| Surveillance Settings | 🔨 Migrate to native | Replace WebView with native fragment |
| Performance | 🔨 Migrate to native | Replace WebView with native fragment |
| ABRP | 🔨 Migrate to native | Replace WebView with native fragment — **keep feature** |
| Logs | 🔨 Migrate to native | Add enable/disable logging toggle |
| Remote Access | ❌ Remove | Entire screen + tunnels gone |
| MQTT | ❌ Remove | Entire screen + daemon support gone |
| Trips | ❌ Remove | Entire screen gone |
| Telegram Settings | ❌ Remove | Entire screen + daemon gone |
| Traffic Monitor | ❌ Remove from nav | Remove sidebar entry; underlying data can stay |

**The events/video player pages have always been native. `assets/web/local/events.html` and related files are remote browser UI only — never loaded on-screen.**

`WebViewFragment.shouldOverrideUrlLoading()` intercepts `/events` and `/events.html` and redirects to `RecordingLibraryFragment`. This remains on `lite/main`.

---

### Remote Web UI — Stripped on `lite/main`

The entire remote web UI (`assets/web/`) is being stripped on `lite/main`:
- No Cloudflare/Zrok tunnels → no remote access
- `HttpServer.java` still runs (it serves the daemon API that the native UI polls) — do not remove it
- The web asset extraction from `HttpServer.java` can be disabled or left inert once no assets are bundled
- Do NOT remove `HttpServer.java` itself — the native UI depends on its API endpoints

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

## Current Focus — Native Lite Rebuild (`lite/main`)

**Goal:** Strip the app down to exactly what's used. All screens go native Kotlin. Remove every unused daemon, page, and dependency. Apply a consistent modern design system throughout.

The recording pipeline is sacred — nothing changes there. UI and feature decisions are the target.

---

### What We're Keeping

| Feature | Notes |
|---|---|
| Recording (camera, storage, events, playback) | Core feature — no changes to pipeline |
| Surveillance / Sentry | Core feature — keep full settings |
| ABRP (Better Route Planner) | Keep — migrate from WebView to native |
| Performance monitoring | Keep — migrate from WebView to native |
| Logs | Keep — migrate to native, add enable/disable toggle |
| Recording Settings | Keep — migrate from WebView to native |
| Surveillance Settings | Keep — migrate from WebView to native |
| Dashboard | Keep — fix existing bugs first |

### What We're Removing

| Feature | What to delete |
|---|---|
| Telegram bot | `TelegramBotDaemon.java`, `TelegramDaemonController.kt`, Telegram settings screen, all nav entries |
| Singbox / GlobalProxy | `GlobalProxyDaemon.java`, `SingboxController.kt`, `SingboxLauncher.kt`, nav entries |
| Cloudflare tunnel | `CloudflaredController.kt`, nav entries, Remote Access screen tunnels section |
| Zrok tunnel | `ZrokController.kt`, nav entries, Remote Access screen tunnels section |
| Remote Access screen | `RemoteAccessFragment.kt`, `fragment_remote_access.xml`, nav entry |
| MQTT | `MqttConnectionManager.java`, `MqttPublisherService.java`, MQTT settings screen, nav entry |
| Trip analysis | Trips screen, nav entry — underlying `TripDatabase` may stay if other features use it |
| Traffic monitor | Remove from sidebar nav only — the underlying monitor can stay dormant |
| All WebView screens | Replace each with native Kotlin before shipping `lite/main` |
| Remote web UI assets | `assets/web/` — strip once all native screens are complete |

---

### Dashboard Bugs to Fix First

These are broken on the current `main` dashboard and must be fixed as the first commit on `lite/main` (or as a fix on `main` and cherry-picked):

1. **Sentry card shows "OFF" even when sentry is enabled.** `DashboardFragment.kt` → `updateSentryCard()` — investigate what `/api/surveillance/status` actually returns vs what the parser expects. The `enabled`/`active` fields may be keyed differently than assumed.

2. **Sentry events today count not shown.** Same method — `events_today` field may be missing or at a different JSON path.

3. **Vehicle card missing SOC% and km range.** `DashboardFragment.kt` → `updateVehicleCard()` — currently reads from `/api/performance/battery` but SOC and range may not be in `voltageHistory`. Check what the daemon actually returns and fix the field path or switch to the `/status` endpoint which includes `soc` and `range`.

Fix these before migrating anything else — they're quick and will be validated on the next car deploy.

---

### Native-First Mandate

On `lite/main`, **no screen is a WebView**. Every settings page, status page, and tool is a native Kotlin Fragment with XML or Compose layout.

Rules:
- When migrating a WebView screen, read the existing HTML/JS to understand what data it fetches and displays, then build a native equivalent that hits the same daemon API endpoints
- Do not carry forward any HTML/JS/CSS — build fresh in Kotlin
- `WebViewFragment.kt` can be deleted once all WebViews are migrated
- `assets/web/` can be deleted once migration is complete and no WebViews remain

---

### Design System — Liquid Glass

All native screens on `lite/main` follow this design language. Do not deviate from it.

**Concept:** iOS 26 / macOS Sequoia liquid glass — dark, deep, frosted surfaces. Clean hierarchy. Content first.

**Background:** Deep near-black — `#08080F` or equivalent. Not pure black. A very slight blue-grey tint.

**Cards / surfaces:**
- Semi-transparent dark glass: `#FFFFFF0F` (6% white alpha) over background
- Corner radius: `20dp` for primary cards, `14dp` for inner elements
- Stroke border: `1dp` at `#FFFFFF18` (10% white alpha) — gives the glass edge
- No shadows. Elevation is implied by the stroke and translucency, not by drop shadow.

**Typography:**
- System font (Roboto on Android, but use thin/light weights to mimic SF Pro)
- Primary labels: `14sp`, weight 500, `#FFFFFF` full white
- Secondary / subtext: `12sp`, weight 400, `#FFFFFF80` (50% white)
- Numeric/metric values: `24–32sp`, weight 300 (light), full white — let numbers breathe
- Section headers: `11sp`, weight 600, `#FFFFFF50` (30% white), all caps, tracked wide

**Accent color:** Single accent — `#00D4AA` (teal/cyan). Used for active states, progress fill, and primary action buttons only. Not overused.

**Status colors (sparingly):**
- Active / good: `#22C55E`
- Warning: `#F59E0B`
- Danger / error: `#EF4444`
- Muted / off: `#FFFFFF40`

**Icons:** Material Symbols, `outlined` style, weight 300. Size `20dp` for inline, `24dp` for action icons.

**Interactive elements:**
- Buttons: glass card style with accent stroke, `14sp` medium weight, `48dp` min touch target
- Toggles: use `SwitchMaterial` styled with accent track color
- Sliders: accent track, transparent thumb with white stroke
- No flat colored buttons except destructive actions (red fill)

**Spacing:** 8dp base unit. Card padding `16dp`. Inter-card gap `12dp`. Section gap `24dp`.

**DiLink-specific:** The screen is landscape 1280×480dp (approximately). Design for landscape-first. Cards should be wide and short, not tall. Avoid vertical scrolling where possible — prefer tab-style or horizontal layout within the screen.

---

### Migration Order

Work in this order. Each screen is one branch + one PR into `lite/main`.

1. **Dashboard bug fixes** — `fix/dashboard-status` → `lite/main`
2. **Strip removed features** — `lite/feature-strip-daemons` — remove Telegram, Singbox, Cloudflare, Zrok, MQTT, Trips, Remote Access, Traffic Monitor nav entry. Remove their controllers/launchers. One large PR.
3. **Recording Settings native** — `lite/feature-recording-settings-native`
4. **Surveillance Settings native** — `lite/feature-surveillance-settings-native`
5. **Performance native** — `lite/feature-performance-native`
6. **ABRP native** — `lite/feature-abrp-native`
7. **Logs native** (with enable/disable toggle) — `lite/feature-logs-native`
8. **Strip `assets/web/`** — once all screens are native and verified on-car

---

### Unverified Items (On-Car Only)

These are from `main` and carry over to `lite/main` as known unknowns:

- **MotionPipelineV2 init** — check daemon logs for `"V2 pipeline initialized"` on first `lite/main` deploy
- **Multi-camera UV** — if any feed appears vertically inverted, swap `v0`/`v1` in `MultiCameraGLView.kt`
- **Camera reconfiguration** — low priority; only needed if feeds are swapped

---

## On-Car Test Checklist (`lite/main`)

When deploying to the Atto 3, verify in order:

1. **Dashboard** — sentry status is accurate (not showing OFF when armed), events today count is shown, SOC% and km range appear on vehicle card.
2. **Recording** — events list loads quickly (native, no WebView delay), video player works.
3. **Sentry settings** — all config persists across restart.
4. **Recording settings** — all config persists across restart.
5. **ABRP** — status and telemetry display correctly (once native screen is built).
6. **Performance** — CPU/GPU/thermal bars show real-time data.
7. **Logs** — toggle disables logging; toggle re-enables it.
8. **MotionPipelineV2 init** — daemon logs show `"V2 pipeline initialized"`.
9. **Surveillance detection** — motion triggers events in all active quadrants.
10. **Performance under load** — recording active + sentry armed simultaneously; check thermal headroom.
