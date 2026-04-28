package com.overdrive.app.ui.util

import android.content.Context
import android.util.Log
import com.overdrive.app.ui.model.RecordingFile
import java.io.File
import java.util.Calendar

/**
 * Simplified Scanner - Uses Direct File Access (SOTA Architecture).
 * Since App owns the directory, we trust the Disk, not the Database.
 *
 * Delegates path resolution to StorageManager so the scanner always reads from
 * exactly the same directories the daemon writes to — including SD card paths.
 */
object RecordingScanner {
    private const val TAG = "RecordingScanner"

    // Simple cache to prevent IO spam on UI refresh
    private var cachedRecordings: List<RecordingFile>? = null
    private var cacheTimestamp: Long = 0
    private const val CACHE_VALIDITY_MS = 5000L // 5 seconds

    // Cached storage paths — refreshed every 10s so SD card changes are picked up quickly
    private var cachedRecordingsDir: File? = null
    private var cachedSurveillanceDir: File? = null
    private var cachedProximityDir: File? = null
    private var configCacheTimestamp: Long = 0
    private const val CONFIG_CACHE_VALIDITY_MS = 10_000L

    private fun loadStorageConfig(context: Context) {
        val now = System.currentTimeMillis()
        if (cachedRecordingsDir != null && now - configCacheTimestamp < CONFIG_CACHE_VALIDITY_MS) return

        // StorageManager reads the same unified config as the daemon and resolves
        // SD card paths via raw /storage/ enumeration — the same logic the daemon uses
        // when deciding where to write recordings.
        val sm = com.overdrive.app.storage.StorageManager.getInstance()
        cachedRecordingsDir  = sm.recordingsDir
        cachedSurveillanceDir = sm.surveillanceDir
        cachedProximityDir   = sm.proximityDir
        configCacheTimestamp = now

        Log.d(TAG, "Active dirs: recordings=${cachedRecordingsDir?.absolutePath}, " +
            "surveillance=${cachedSurveillanceDir?.absolutePath}")
    }
    
    /**
     * Scan all recordings directly from Disk.
     * SOTA: Scans both internal and SD card locations based on config.
     */
    fun scanRecordings(context: Context): List<RecordingFile> {
        val now = System.currentTimeMillis()
        
        // Return cache if still valid
        cachedRecordings?.let { cached ->
            if (now - cacheTimestamp < CACHE_VALIDITY_MS) {
                return cached
            }
        }
        
        // Load storage config to get active directories
        loadStorageConfig(context)
        
        val internalBase = File("/storage/emulated/0/Overdrive")
        // Scan configured directories
        val normal = scanDirectory(cachedRecordingsDir ?: File(internalBase, com.overdrive.app.storage.StorageManager.RECORDINGS_SUBDIR), RecordingFile.RecordingType.NORMAL)
        val sentry = scanDirectory(cachedSurveillanceDir ?: File(internalBase, com.overdrive.app.storage.StorageManager.SURVEILLANCE_SUBDIR), RecordingFile.RecordingType.SENTRY)
        val proximity = scanDirectory(cachedProximityDir ?: File(internalBase, com.overdrive.app.storage.StorageManager.PROXIMITY_SUBDIR), RecordingFile.RecordingType.PROXIMITY)
        
        val allFiles = (normal + sentry + proximity).sortedByDescending { it.timestamp }
        
        Log.d(TAG, "Direct Scan: Found ${allFiles.size} total videos (normal=${normal.size}, sentry=${sentry.size}, proximity=${proximity.size})")
        
        cachedRecordings = allFiles
        cacheTimestamp = now
        return allFiles
    }
    
    private fun scanDirectory(dir: File, type: RecordingFile.RecordingType): List<RecordingFile> {
        if (!dir.exists()) return emptyList()
        
        // DIRECT FILE ACCESS - The Source of Truth
        val files = dir.listFiles() ?: return emptyList()
        
        return files
            .filter { it.isFile && it.name.endsWith(".mp4") }
            .mapNotNull { file ->
                // Use RecordingFile.fromFile for proper filename parsing
                RecordingFile.fromFile(file, type)
            }
    }
    
    /**
     * Invalidate the cache (call after recording/deletion).
     */
    fun invalidateCache() {
        cachedRecordings = null
        cacheTimestamp = 0
        // Also invalidate config cache to pick up storage changes
        cachedRecordingsDir = null
        cachedSurveillanceDir = null
        cachedProximityDir = null
        configCacheTimestamp = 0
    }
    
    /**
     * Delete a recording file and its JSON sidecar (event timeline) if present.
     */
    fun deleteRecording(recording: RecordingFile): Boolean {
        val deleted = recording.file.delete()
        if (deleted) {
            // Also delete JSON sidecar (event timeline) if it exists
            val jsonFile = File(recording.file.absolutePath.replace(".mp4", ".json"))
            if (jsonFile.exists()) {
                jsonFile.delete()
            }
            invalidateCache()
        }
        return deleted
    }
    
    // ==================== Helper Methods ====================
    
    /**
     * Get the recordings directory (respects configured storage type).
     */
    fun getRecordingsDir(context: Context): File {
        loadStorageConfig(context)
        return cachedRecordingsDir ?: File("/storage/emulated/0/Overdrive", com.overdrive.app.storage.StorageManager.RECORDINGS_SUBDIR)
    }
    
    /**
     * Get the sentry events directory (respects configured storage type).
     */
    fun getSentryEventsDir(context: Context): File {
        loadStorageConfig(context)
        return cachedSurveillanceDir ?: File("/storage/emulated/0/Overdrive", com.overdrive.app.storage.StorageManager.SURVEILLANCE_SUBDIR)
    }
    
    /**
     * Get the proximity events directory (respects configured storage type).
     */
    fun getProximityEventsDir(context: Context): File {
        loadStorageConfig(context)
        return cachedProximityDir ?: File("/storage/emulated/0/Overdrive", com.overdrive.app.storage.StorageManager.PROXIMITY_SUBDIR)
    }
    
    /**
     * Scan only normal recordings.
     */
    fun scanNormalRecordings(context: Context): List<RecordingFile> {
        return scanRecordings(context).filter { it.type == RecordingFile.RecordingType.NORMAL }
    }
    
    /**
     * Scan only sentry event recordings.
     */
    fun scanSentryRecordings(context: Context): List<RecordingFile> {
        return scanRecordings(context).filter { it.type == RecordingFile.RecordingType.SENTRY }
    }
    
    /**
     * Scan only proximity event recordings.
     */
    fun scanProximityRecordings(context: Context): List<RecordingFile> {
        return scanRecordings(context).filter { it.type == RecordingFile.RecordingType.PROXIMITY }
    }
    
    /**
     * Get recordings for a specific date.
     */
    fun getRecordingsForDate(context: Context, year: Int, month: Int, day: Int): List<RecordingFile> {
        val calendar = Calendar.getInstance().apply {
            set(year, month, day, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfDay = calendar.timeInMillis
        calendar.add(Calendar.DAY_OF_MONTH, 1)
        val endOfDay = calendar.timeInMillis
        
        return scanRecordings(context).filter { 
            it.timestamp in startOfDay until endOfDay 
        }
    }
    
    /**
     * Get dates that have recordings (for calendar highlighting).
     */
    fun getDatesWithRecordings(context: Context): Set<Long> {
        val calendar = Calendar.getInstance()
        return scanRecordings(context).map { recording ->
            calendar.timeInMillis = recording.timestamp
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            calendar.timeInMillis
        }.toSet()
    }
    
    /**
     * Get recording count per date for a specific month.
     */
    fun getRecordingCountsByDate(context: Context, year: Int, month: Int): Map<Int, Int> {
        val rangeCalendar = Calendar.getInstance().apply {
            set(year, month, 1, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfMonth = rangeCalendar.timeInMillis
        rangeCalendar.add(Calendar.MONTH, 1)
        val endOfMonth = rangeCalendar.timeInMillis
        
        return scanRecordings(context)
            .filter { it.timestamp in startOfMonth until endOfMonth }
            .groupBy { recording ->
                val dayCalendar = Calendar.getInstance()
                dayCalendar.timeInMillis = recording.timestamp
                dayCalendar.get(Calendar.DAY_OF_MONTH)
            }
            .mapValues { it.value.size }
    }
    
    /**
     * Get total size of all recordings.
     */
    fun getTotalRecordingsSize(context: Context): Long {
        return scanRecordings(context).sumOf { it.sizeBytes }
    }
    
    /**
     * Get total size of normal recordings.
     */
    fun getNormalRecordingsSize(context: Context): Long {
        return scanNormalRecordings(context).sumOf { it.sizeBytes }
    }
    
    /**
     * Get total size of sentry recordings.
     */
    fun getSentryRecordingsSize(context: Context): Long {
        return scanSentryRecordings(context).sumOf { it.sizeBytes }
    }
    
    /**
     * Get total size of proximity recordings.
     */
    fun getProximityRecordingsSize(context: Context): Long {
        return scanProximityRecordings(context).sumOf { it.sizeBytes }
    }
}
