package com.overdrive.app.manager

import android.content.Context
import com.overdrive.app.byd.BydEventClient
import com.overdrive.app.byd.SentryEventHandler
import com.overdrive.app.client.CameraDaemonClient
import com.overdrive.app.daemon.management.DaemonManager
import com.overdrive.app.logging.LogManager

/**
 * Manages BYD event system initialization and lifecycle.
 */
class BydSystemManager(
    private val context: Context,
    private val daemonManager: DaemonManager,
    private val logManager: LogManager
) {
    
    companion object {
        private const val TAG = "BydSystemManager"
    }
    
    /**
     * Initialize the BYD system (event daemon only).
     *
     * Privileged shell (UID 1000) is intentionally disabled — it caused BYD's default
     * dashcam to lose signal by elevating camera priority via accmodemanager. All
     * daemons now run via ADB shell (UID 2000) which is sufficient.
     */
    fun initialize(callback: InitCallback) {
        logManager.info(TAG, "Initializing BYD system...")
        Thread {
            callback.onProgress("Starting event system...")
            startEventSystem(callback)
        }.start()
    }
    
    /**
     * Grant BYD-specific permissions.
     *
     * Privileged shell removed — permissions are granted externally via ADB before
     * the app is used. This method is a no-op kept for call-site compatibility.
     */
    fun grantBydPermissions(callback: PermissionCallback) {
        Thread {
            logManager.info(TAG, "Permission granting via privileged shell is disabled; " +
                    "ensure BYD permissions are pre-granted via ADB.")
            callback.onGranted(0)
        }.start()
    }
    
    /**
     * Start the BYD event system.
     */
    private fun startEventSystem(callback: InitCallback) {
        // Setup DaemonClient for camera control
        val camDaemonClient = CameraDaemonClient()
        if (camDaemonClient.connect()) {
            logManager.info(TAG, "Connected to CamDaemon")
            SentryEventHandler.daemonClient = camDaemonClient
        }
        
        callback.onSuccess()
    }
    
    /**
     * Connect event client.
     */
    fun connectEventClient() {
        BydEventClient.connect()
        logManager.info(TAG, "BydEventClient connecting...")
    }
    
    /**
     * Disconnect event client.
     */
    fun disconnectEventClient() {
        BydEventClient.disconnect()
        logManager.info(TAG, "BydEventClient disconnected")
    }
    
    /**
     * Check if event client is connected.
     */
    fun isEventClientConnected(): Boolean {
        return BydEventClient.isConnected()
    }
    
    /**
     * Enable sentry mode.
     */
    fun enableSentryMode() {
        SentryEventHandler.enableSentryMode()
        logManager.info(TAG, "Sentry mode enabled")
    }
    
    /**
     * Disable sentry mode.
     */
    fun disableSentryMode() {
        SentryEventHandler.disableSentryMode()
        logManager.info(TAG, "Sentry mode disabled")
    }
    
    /**
     * Check if sentry mode is enabled.
     */
    fun isSentryModeEnabled(): Boolean {
        return SentryEventHandler.isSentryModeEnabled()
    }
    
    /**
     * Set event callbacks.
     */
    fun setEventCallbacks(callbacks: BydEventCallbacks) {
        BydEventClient.addListener(object : BydEventClient.EventListener {
            override fun onConnected() = callbacks.onConnected()
            override fun onDisconnected() = callbacks.onDisconnected()
            override fun onPowerLevelChanged(level: Int, levelName: String) = 
                callbacks.onPowerLevelChanged(level, levelName)
            override fun onRadarEvent(area: Int, areaName: String, state: Int, stateName: String) = 
                callbacks.onRadarEvent(area, areaName, state, stateName)
            override fun onBatteryInfo(voltageLevel: Int, voltageLevelName: String, voltage: Double) = 
                callbacks.onBatteryInfo(voltageLevel, voltageLevelName, voltage)
            override fun onBatteryVoltageLevelChanged(level: Int, levelName: String) {}
        })
    }
    
    /**
     * Shutdown BYD system.
     */
    fun shutdown() {
        disconnectEventClient()
        logManager.info(TAG, "BYD system shutdown")
    }
}

/**
 * Initialization callback interface.
 */
interface InitCallback {
    fun onSuccess()
    fun onFailure(reason: String)
    fun onProgress(message: String)
}

/**
 * Permission callback interface.
 */
interface PermissionCallback {
    fun onGranted(count: Int)
    fun onFailed(count: Int)
}

/**
 * BYD event callbacks interface.
 */
interface BydEventCallbacks {
    fun onConnected()
    fun onDisconnected()
    fun onPowerLevelChanged(level: Int, levelName: String)
    fun onRadarEvent(area: Int, areaName: String, state: Int, stateName: String)
    fun onBatteryInfo(voltageLevel: Int, voltageLevelName: String, voltage: Double)
}
