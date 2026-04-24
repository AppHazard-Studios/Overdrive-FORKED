package com.overdrive.app.shell

import com.overdrive.app.logging.LogManager
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Manages permission granting via the privileged shell.
 * 
 * Grants permissions via shell commands. Privileged shell (UID 1000) has been removed;
 * this now falls back to plain Runtime.exec() which works at ADB shell UID (2000).
 */
object ShellPermissionManager {
    
    private const val TAG = "ShellPermissionManager"
    private const val PACKAGE_NAME = "com.overdrive.app"
    
    private val logManager = LogManager.getInstance()
    
    /**
     * Run a shell command and return stdout, or null on failure.
     * Privileged shell (UID 1000) was removed — this falls back to a plain exec.
     * On BYD the app runs with sufficient ADB-granted permissions so pm grant
     * is generally a no-op, but we keep the call for compatibility.
     */
    private fun execSync(command: String): String? {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val out = BufferedReader(InputStreamReader(proc.inputStream)).readText()
            proc.waitFor()
            out
        } catch (e: Exception) {
            logManager.warn(TAG, "execSync failed: ${e.message}")
            null
        }
    }

    /**
     * Grant a single permission to our app via shell.
     */
    fun grantPermission(permission: String): Boolean {
        val result = execSync("pm grant $PACKAGE_NAME $permission 2>&1")
        val success = result?.isEmpty() == true || result?.contains("Success") == true
        
        if (success) {
            logManager.info(TAG, "Granted permission: $permission")
        } else {
            logManager.warn(TAG, "Failed to grant permission: $permission - $result")
        }
        
        return success
    }
    
    /**
     * Grant multiple permissions.
     * @return Map of permission to success status
     */
    fun grantPermissions(permissions: List<String>): Map<String, Boolean> {
        logManager.info(TAG, "Granting ${permissions.size} permissions...")
        return permissions.associateWith { grantPermission(it) }
    }
    
    /**
     * Grant BYD-specific permissions required for camera and radar access.
     */
    fun grantBydPermissions(): PermissionResult {
        val permissions = listOf(
            "android.permission.BYDAUTO_BODYWORK_COMMON",
            "android.permission.BYDAUTO_BODYWORK_GET",
            "android.permission.BYDAUTO_BODYWORK_SET",
            "android.permission.BYDAUTO_RADAR_GET",
            "android.permission.BYDAUTO_RADAR_COMMON"
        )
        
        logManager.info(TAG, "Granting BYD permissions...")
        
        var granted = 0
        var failed = 0
        
        for (perm in permissions) {
            if (grantPermission(perm)) {
                granted++
            } else {
                failed++
            }
        }
        
        logManager.info(TAG, "BYD permissions: $granted granted, $failed failed")
        return PermissionResult(granted, failed)
    }
    
    /**
     * Grant camera-related permissions.
     */
    fun grantCameraPermissions(): PermissionResult {
        val permissions = listOf(
            "android.permission.CAMERA",
            "android.permission.RECORD_AUDIO",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.MANAGE_EXTERNAL_STORAGE"  // SOTA: Required for cross-UID file visibility
        )
        
        logManager.info(TAG, "Granting camera permissions...")
        
        var granted = 0
        var failed = 0
        
        for (perm in permissions) {
            if (grantPermission(perm)) {
                granted++
            } else {
                failed++
            }
        }
        
        logManager.info(TAG, "Camera permissions: $granted granted, $failed failed")
        return PermissionResult(granted, failed)
    }
    
    /**
     * Grant network-related permissions.
     */
    fun grantNetworkPermissions(): PermissionResult {
        val permissions = listOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.ACCESS_WIFI_STATE",
            "android.permission.CHANGE_WIFI_STATE"
        )
        
        logManager.info(TAG, "Granting network permissions...")
        
        var granted = 0
        var failed = 0
        
        for (perm in permissions) {
            if (grantPermission(perm)) {
                granted++
            } else {
                failed++
            }
        }
        
        logManager.info(TAG, "Network permissions: $granted granted, $failed failed")
        return PermissionResult(granted, failed)
    }
    
    /**
     * Grant location permissions for GPS tracking.
     */
    fun grantLocationPermissions(): PermissionResult {
        val permissions = listOf(
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_BACKGROUND_LOCATION"
        )
        
        logManager.info(TAG, "Granting location permissions...")
        
        var granted = 0
        var failed = 0
        
        for (perm in permissions) {
            if (grantPermission(perm)) {
                granted++
            } else {
                failed++
            }
        }
        
        logManager.info(TAG, "Location permissions: $granted granted, $failed failed")
        return PermissionResult(granted, failed)
    }
    
    /**
     * Check if a permission is granted.
     */
    fun isPermissionGranted(permission: String): Boolean {
        val result = execSync("dumpsys package $PACKAGE_NAME | grep $permission")
        return result?.contains("granted=true") == true
    }
    
    /**
     * Result of a permission granting operation.
     */
    data class PermissionResult(
        val granted: Int,
        val failed: Int
    ) {
        val allGranted: Boolean get() = failed == 0
        val total: Int get() = granted + failed
    }
}
