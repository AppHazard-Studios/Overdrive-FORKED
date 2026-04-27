package com.overdrive.app.ui.daemon

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.overdrive.app.launcher.AdbDaemonLauncher
import com.overdrive.app.logging.LogManager
import com.overdrive.app.ui.model.DaemonType
import com.overdrive.app.ui.viewmodel.DaemonsViewModel

class DaemonStartupManager(
    private val context: Context,
    private val daemonsViewModel: DaemonsViewModel? = null
) {
    private val log = LogManager.getInstance()
    private val handler = Handler(Looper.getMainLooper())
    private val adbLauncher = AdbDaemonLauncher(context)

    companion object {
        private const val TAG = "DaemonStartup"
        private const val HEALTH_CHECK_INTERVAL_MS = 30_000L

        val CORE_DAEMONS: List<DaemonType> = listOf(
            DaemonType.CAMERA_DAEMON,
            DaemonType.SENTRY_DAEMON,
            DaemonType.ACC_SENTRY_DAEMON,
        )

        val OPTIONAL_DAEMONS: List<DaemonType> = emptyList()

        val userStoppedDaemons = mutableSetOf<DaemonType>()

        fun markUserStopped(type: DaemonType) {
            userStoppedDaemons.add(type)
        }

        fun clearUserStopped(type: DaemonType) {
            userStoppedDaemons.remove(type)
        }

        @Volatile
        private var bootManager: DaemonStartupManager? = null

        @Volatile
        private var bootStarted = false

        fun startOnBoot(context: Context) {
            if (bootStarted) return
            bootStarted = true
            userStoppedDaemons.clear()
            val manager = DaemonStartupManager(context, null)
            bootManager = manager
            manager.initializeOnBoot()
        }
    }

    fun initializeOnAppLaunch() {
        log.info(TAG, "=== Initializing daemon startup on app launch ===")
        log.info(TAG, "Waiting 45 seconds before starting daemons (system stabilization)...")

        userStoppedDaemons.clear()

        enableAccessibilityKeepAlive()

        handler.postDelayed({ startCoreDaemons() }, 45000)
        handler.postDelayed({ startDaemonHealthCheck() }, 90000)
    }

    private fun initializeOnBoot() {
        log.info(TAG, "=== Initializing daemon startup on boot ===")
        log.info(TAG, "Waiting 45 seconds before starting daemons (system stabilization)...")

        userStoppedDaemons.clear()

        enableAccessibilityKeepAlive()

        handler.postDelayed({ startCoreDaemonsViaAdb() }, 45000)
        handler.postDelayed({ startDaemonHealthCheck() }, 90000)
    }

    fun checkAllDaemonStatuses() {
        log.info(TAG, "=== Checking all daemon statuses ===")
        daemonsViewModel?.let { vm ->
            DaemonType.values().forEach { type -> vm.refreshDaemonStatus(type, logResult = true) }
            val savedMode = com.overdrive.app.ui.util.PreferencesManager.getAccessMode()
            val streamMode = if (savedMode == com.overdrive.app.ui.model.AccessMode.PUBLIC) "public" else "private"
            log.info(TAG, "Syncing camera daemon stream mode to: $streamMode")
            vm.cameraDaemonController.setStreamMode(streamMode)
        }
    }

    private fun startCoreDaemons() {
        val vm = daemonsViewModel ?: run {
            log.warn(TAG, "ViewModel not available, using ADB launcher")
            startCoreDaemonsViaAdb()
            return
        }
        log.info(TAG, "Starting core daemons (Camera first, then Sentry daemons)...")

        log.info(TAG, "Starting Camera Daemon...")
        vm.startDaemon(DaemonType.CAMERA_DAEMON)

        handler.postDelayed({
            log.info(TAG, "Starting Sentry Daemon...")
            vm.startDaemon(DaemonType.SENTRY_DAEMON)
        }, 5000)

        handler.postDelayed({
            log.info(TAG, "Starting ACC Sentry Daemon...")
            vm.startDaemon(DaemonType.ACC_SENTRY_DAEMON)
        }, 10000)
    }

    private fun startCoreDaemonsViaAdb() {
        log.info(TAG, "Starting core daemons via ADB (Camera first, then Sentry daemons)...")

        adbLauncher.isDaemonRunning("camera_daemon") { running ->
            if (!running) {
                log.info(TAG, "Boot: Starting Camera Daemon...")
                val nativeLibDir = context.applicationInfo.nativeLibraryDir
                val outputDir = context.getExternalFilesDir(null)?.absolutePath ?: context.filesDir.absolutePath
                adbLauncher.launchDaemon(outputDir, nativeLibDir, createLogCallback("CameraDaemon"))
            } else {
                log.info(TAG, "Boot: Camera Daemon already running")
            }
        }

        handler.postDelayed({
            adbLauncher.isSentryDaemonRunning { running ->
                if (!running) {
                    log.info(TAG, "Boot: Starting Sentry Daemon...")
                    adbLauncher.launchSentryDaemon(createLogCallback("SentryDaemon"))
                } else {
                    log.info(TAG, "Boot: Sentry Daemon already running")
                }
            }
        }, 5000)

        handler.postDelayed({
            adbLauncher.isDaemonRunning("acc_sentry_daemon") { running ->
                if (!running) {
                    log.info(TAG, "Boot: Starting ACC Sentry Daemon...")
                    adbLauncher.launchAccSentryDaemon(
                        onSuccess = { log.info(TAG, "Boot: ACC Sentry Daemon started") },
                        onError = { error -> log.error(TAG, "Boot: ACC Sentry error: $error") }
                    )
                } else {
                    log.info(TAG, "Boot: ACC Sentry Daemon already running")
                }
            }
        }, 10000)
    }

    fun onAccessModeChanged(newMode: com.overdrive.app.ui.model.AccessMode) {
        val vm = daemonsViewModel ?: return
        val streamMode = if (newMode == com.overdrive.app.ui.model.AccessMode.PUBLIC) "public" else "private"
        vm.cameraDaemonController.setStreamMode(streamMode) { success ->
            if (success) log.info(TAG, "Camera daemon set to $streamMode mode")
        }
    }

    fun onDaemonToggled(type: DaemonType, enabled: Boolean) {
        // No optional daemons on lite build — no-op
    }

    private fun createLogCallback(name: String): AdbDaemonLauncher.LaunchCallback {
        return object : AdbDaemonLauncher.LaunchCallback {
            override fun onLog(message: String) { log.debug(TAG, "[$name] $message") }
            override fun onLaunched() { log.info(TAG, "[$name] Started successfully") }
            override fun onError(error: String) { log.error(TAG, "[$name] Error: $error") }
        }
    }

    private fun enableAccessibilityKeepAlive() {
        if (com.overdrive.app.services.KeepAliveAccessibilityService.isRunning()) {
            log.info(TAG, "AccessibilityService already running")
            return
        }

        log.info(TAG, "Enabling AccessibilityService keep-alive via ADB...")
        val serviceLauncher = com.overdrive.app.launcher.ServiceLauncher(
            context,
            com.overdrive.app.launcher.AdbShellExecutor(context),
            log
        )
        serviceLauncher.enableAccessibilityKeepAlive(object : com.overdrive.app.launcher.ServiceLauncher.LaunchCallback {
            override fun onLog(message: String) { log.debug(TAG, "[A11y] $message") }
            override fun onLaunched() { log.info(TAG, "AccessibilityService keep-alive enabled") }
            override fun onError(error: String) { log.warn(TAG, "AccessibilityService enable failed: $error (non-fatal)") }
        })
    }

    private var healthCheckRunning = false

    private fun startDaemonHealthCheck() {
        if (healthCheckRunning) return
        healthCheckRunning = true
        log.info(TAG, "Daemon health check started (interval=${HEALTH_CHECK_INTERVAL_MS / 1000}s)")
        scheduleNextHealthCheck()
    }

    private fun scheduleNextHealthCheck() {
        handler.postDelayed({
            if (healthCheckRunning) {
                runHealthCheck()
                scheduleNextHealthCheck()
            }
        }, HEALTH_CHECK_INTERVAL_MS)
    }

    private fun runHealthCheck() {
        for (type in CORE_DAEMONS) {
            if (type in userStoppedDaemons) continue
            checkAndRelaunchDaemon(type)
        }
    }

    private fun checkAndRelaunchDaemon(type: DaemonType) {
        adbLauncher.isDaemonRunning(type.processName) { isRunning ->
            if (!isRunning) {
                log.warn(TAG, "Health check: ${type.displayName} is DEAD — relaunching...")
                relaunchDaemon(type)
            }
        }
    }

    private fun relaunchDaemon(type: DaemonType) {
        val vm = daemonsViewModel
        if (vm != null) {
            handler.post { vm.startDaemon(type) }
        } else {
            when (type) {
                DaemonType.CAMERA_DAEMON -> {
                    val nativeLibDir = context.applicationInfo.nativeLibraryDir
                    val outputDir = context.getExternalFilesDir(null)?.absolutePath ?: context.filesDir.absolutePath
                    adbLauncher.launchDaemon(outputDir, nativeLibDir, createLogCallback("HealthCheck-Camera"))
                }
                DaemonType.SENTRY_DAEMON -> {
                    adbLauncher.launchSentryDaemon(createLogCallback("HealthCheck-Sentry"))
                }
                DaemonType.ACC_SENTRY_DAEMON -> {
                    adbLauncher.launchAccSentryDaemon(
                        onSuccess = { log.info(TAG, "HealthCheck: ACC Sentry restarted") },
                        onError = { e -> log.error(TAG, "HealthCheck: ACC Sentry restart failed: $e") }
                    )
                }
            }
        }
    }

    fun cleanup() {
        healthCheckRunning = false
        handler.removeCallbacksAndMessages(null)
        adbLauncher.closePersistentConnection()
    }
}
