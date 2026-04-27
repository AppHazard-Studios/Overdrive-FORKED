package com.overdrive.app.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.overdrive.app.ui.model.LogEntry
import com.overdrive.app.ui.model.LogLevel

class LogsViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val MAX_LOGS = 500
        private const val PREFS_NAME = "logs_prefs"
        private const val KEY_ENABLED = "logging_enabled"
    }

    private val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _loggingEnabled = MutableLiveData(prefs.getBoolean(KEY_ENABLED, true))
    val loggingEnabled: LiveData<Boolean> = _loggingEnabled

    private val _logs = MutableLiveData<List<LogEntry>>(emptyList())
    val logs: LiveData<List<LogEntry>> = _logs

    private val _filter = MutableLiveData<String?>(null)
    val filter: LiveData<String?> = _filter

    private val _filteredLogs = MutableLiveData<List<LogEntry>>(emptyList())
    val filteredLogs: LiveData<List<LogEntry>> = _filteredLogs

    private val allLogs = mutableListOf<LogEntry>()

    fun setLoggingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        _loggingEnabled.value = enabled
    }

    fun addLog(tag: String, message: String, level: LogLevel = LogLevel.INFO) {
        if (_loggingEnabled.value == false) return
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            tag = tag,
            message = message,
            level = level
        )
        
        synchronized(allLogs) {
            allLogs.add(entry)
            // Trim if too many logs
            while (allLogs.size > MAX_LOGS) {
                allLogs.removeAt(0)
            }
        }
        
        updateFilteredLogs()
    }
    
    fun debug(tag: String, message: String) = addLog(tag, message, LogLevel.DEBUG)
    fun info(tag: String, message: String) = addLog(tag, message, LogLevel.INFO)
    fun warn(tag: String, message: String) = addLog(tag, message, LogLevel.WARN)
    fun error(tag: String, message: String) = addLog(tag, message, LogLevel.ERROR)
    
    fun clearLogs() {
        synchronized(allLogs) {
            allLogs.clear()
        }
        _logs.postValue(emptyList())
        _filteredLogs.postValue(emptyList())
    }
    
    fun setFilter(component: String?) {
        _filter.value = component
        updateFilteredLogs()
    }
    
    private fun updateFilteredLogs() {
        val currentFilter = _filter.value
        val filtered = synchronized(allLogs) {
            if (currentFilter.isNullOrBlank()) {
                allLogs.toList()
            } else {
                allLogs.filter { it.tag.contains(currentFilter, ignoreCase = true) }
            }
        }
        _logs.postValue(synchronized(allLogs) { allLogs.toList() })
        _filteredLogs.postValue(filtered)
    }
    
    /**
     * Get all unique tags for filter dropdown.
     */
    fun getAvailableTags(): List<String> {
        return synchronized(allLogs) {
            allLogs.map { it.tag }.distinct().sorted()
        }
    }
}
