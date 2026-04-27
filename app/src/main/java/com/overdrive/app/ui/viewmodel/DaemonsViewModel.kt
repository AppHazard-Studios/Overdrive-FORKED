package com.overdrive.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.overdrive.app.launcher.AdbDaemonLauncher
import com.overdrive.app.logging.LogManager
import com.overdrive.app.ui.daemon.*
import com.overdrive.app.ui.model.DaemonState
import com.overdrive.app.ui.model.DaemonStatus
import com.overdrive.app.ui.model.DaemonType
import com.overdrive.app.ui.model.SubprocessInfo
import com.overdrive.app.ui.model.parseUptimeToMillis

/**
 * ViewModel for managing daemon states.
 */
class DaemonsViewModel(app: Application) : AndroidViewModel(app) {

    private val adbLauncher = AdbDaemonLauncher(app)

    private val controllers: Map<DaemonType, DaemonController>

    private val _daemonStates = MutableLiveData<Map<DaemonType, DaemonState>>()
    val daemonStates: LiveData<Map<DaemonType, DaemonState>> = _daemonStates

    // Expose camera daemon controller for startup manager
    val cameraDaemonController: CameraDaemonController

    // Reference to startup manager (set by Activity after creation)
    private var startupManager: DaemonStartupManager? = null

    // Expose startup manager for preference saving
    val daemonStartupManager: DaemonStartupManager?
        get() = startupManager

    fun setStartupManager(manager: DaemonStartupManager) {
        startupManager = manager
    }

    init {
        cameraDaemonController = CameraDaemonController(app, adbLauncher)

        controllers = mapOf(
            DaemonType.CAMERA_DAEMON to cameraDaemonController,
            DaemonType.SENTRY_DAEMON to SentryDaemonController(adbLauncher),
            DaemonType.ACC_SENTRY_DAEMON to AccSentryDaemonController(adbLauncher)
        )

        // Initialize all states as stopped
        val initialStates = DaemonType.values().associateWith { DaemonState.stopped(it) }
        _daemonStates.value = initialStates

        // Refresh all statuses after a short delay to ensure ADB connection is ready
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            LogManager.getInstance().info("Daemons", "Initial daemon status refresh...")
            refreshAllStatuses(logResults = true)
        }, 1500)
    }

    fun startDaemon(type: DaemonType) {
        val controller = controllers[type] ?: return

        // Clear user-stopped flag so health check can manage this daemon
        DaemonStartupManager.clearUserStopped(type)

        updateState(type, DaemonStatus.STARTING, "Starting...")

        controller.start(object : DaemonCallback {
            override fun onStatusChanged(status: DaemonStatus, message: String) {
                updateState(type, status, message)
            }

            override fun onError(error: String) {
                updateState(type, DaemonStatus.ERROR, error)
            }
        })
    }

    fun stopDaemon(type: DaemonType) {
        val controller = controllers[type] ?: return

        // Mark as user-stopped so health check doesn't auto-restart
        DaemonStartupManager.markUserStopped(type)

        updateState(type, DaemonStatus.STOPPING, "Stopping daemon and related processes...")

        controller.stop(object : DaemonCallback {
            override fun onStatusChanged(status: DaemonStatus, message: String) {
                updateState(type, status, message)
            }

            override fun onError(error: String) {
                updateState(type, DaemonStatus.ERROR, "Stop failed: $error")
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    refreshDaemonStatus(type)
                }, 1000)
            }
        })
    }

    fun refreshDaemonStatus(type: DaemonType, logResult: Boolean = false) {
        val controller = controllers[type] ?: return
        doRefreshDaemonStatus(type, controller, logResult)
    }

    private fun doRefreshDaemonStatus(type: DaemonType, controller: DaemonController, logResult: Boolean) {
        controller.isRunning { isRunning ->
            if (logResult) {
                LogManager.getInstance().debug("Daemons", "refreshDaemonStatus: ${type.name} isRunning=$isRunning")
            }

            if (isRunning) {
                val processName = getProcessName(type)
                val subprocessPatterns = getSubprocessPatterns(type)

                adbLauncher.getProcessUptime(processName) { uptime ->
                    adbLauncher.getSubprocesses(subprocessPatterns) { processes ->
                        val subprocesses = processes.map { p ->
                            SubprocessInfo(p.name, p.pid, p.uptime)
                        }
                        updateStateWithSubprocesses(type, DaemonStatus.RUNNING, "Running", uptime, subprocesses)
                        if (logResult) {
                            val uptimeStr = uptime?.let { " (uptime: $it)" } ?: ""
                            LogManager.getInstance().info("Daemons", "${type.name}: Running$uptimeStr")
                            subprocesses.forEach { sp ->
                                LogManager.getInstance().debug("Daemons", "  └─ ${sp.name} (PID: ${sp.pid}, uptime: ${sp.uptime})")
                            }
                        }
                    }
                }
            } else {
                updateState(type, DaemonStatus.STOPPED, "Not running")
                if (logResult) {
                    LogManager.getInstance().debug("Daemons", "${type.name}: Not running")
                }
            }
        }
    }

    private fun getProcessName(type: DaemonType): String {
        return when (type) {
            DaemonType.CAMERA_DAEMON -> "byd_cam_daemon"
            DaemonType.SENTRY_DAEMON -> "sentry_daemon"
            DaemonType.ACC_SENTRY_DAEMON -> "acc_sentry_daemon"
        }
    }

    private fun getSubprocessPatterns(type: DaemonType): List<String> {
        return when (type) {
            DaemonType.CAMERA_DAEMON -> listOf("byd_cam_daemon", "ffmpeg", "mediamtx")
            DaemonType.SENTRY_DAEMON -> listOf("sentry_daemon")
            DaemonType.ACC_SENTRY_DAEMON -> listOf("acc_sentry_daemon")
        }
    }

    private fun updateStateWithSubprocesses(
        type: DaemonType,
        status: DaemonStatus,
        message: String,
        uptime: String?,
        subprocesses: List<SubprocessInfo>
    ) {
        val currentStates = _daemonStates.value?.toMutableMap() ?: mutableMapOf()
        val startTime = uptime?.let { System.currentTimeMillis() - parseUptimeToMillis(it) }
        currentStates[type] = DaemonState(type, status, message, uptime, startTime, subprocesses)
        _daemonStates.postValue(currentStates)
    }

    fun refreshAllStatuses(logResults: Boolean = false) {
        if (logResults) {
            LogManager.getInstance().info("Daemons", "Checking daemon statuses...")
        }
        DaemonType.values().forEach { type ->
            refreshDaemonStatus(type, logResults)
        }
    }

    fun cleanupAll() {
        controllers.values.forEach { it.cleanup() }

        val stoppedStates = DaemonType.values().associateWith { DaemonState.stopped(it) }
        _daemonStates.postValue(stoppedStates)
    }

    private fun updateState(type: DaemonType, status: DaemonStatus, message: String) {
        val currentStates = _daemonStates.value?.toMutableMap() ?: mutableMapOf()
        currentStates[type] = DaemonState(type, status, message)
        _daemonStates.postValue(currentStates)
    }

    fun getState(type: DaemonType): DaemonState? = _daemonStates.value?.get(type)

    /**
     * Start Location Sidecar service via ADB (grants permissions first).
     */
    fun startLocationSidecarService(callback: AdbDaemonLauncher.LaunchCallback) {
        adbLauncher.startLocationSidecarService(callback)
    }

    override fun onCleared() {
        super.onCleared()
        adbLauncher.closePersistentConnection()
    }
}
