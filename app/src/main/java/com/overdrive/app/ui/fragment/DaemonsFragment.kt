package com.overdrive.app.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.overdrive.app.ui.adapter.DaemonAdapter
import com.overdrive.app.ui.viewmodel.DaemonsViewModel
import com.overdrive.app.ui.model.DaemonType
import com.overdrive.app.ui.model.DaemonState
import com.overdrive.app.R

/**
 * Fragment for managing background daemons.
 */
class DaemonsFragment : Fragment() {
    
    private val daemonsViewModel: DaemonsViewModel by activityViewModels()
    
    private lateinit var recyclerDaemons: RecyclerView
    private lateinit var daemonAdapter: DaemonAdapter
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_daemons, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        initViews(view)
        setupRecyclerView()
        observeViewModel()
    }
    
    private fun initViews(view: View) {
        recyclerDaemons = view.findViewById(R.id.recyclerDaemons)
    }
    
    private fun setupRecyclerView() {
        daemonAdapter = DaemonAdapter(
            onToggle = { type, enabled -> onDaemonToggled(type, enabled) },
            onConfigureClick = { type -> onDaemonConfigureClicked(type) },
            onDownloadLog = if (com.overdrive.app.BuildConfig.DEBUG) {
                { type -> onDownloadLogClicked(type) }
            } else null
        )
        
        recyclerDaemons.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = daemonAdapter
        }
    }
    
    private fun observeViewModel() {
        daemonsViewModel.daemonStates.observe(viewLifecycleOwner) { states ->
            // Convert map to list sorted by daemon type ordinal
            val sortedList = states.values.sortedBy { it.type.ordinal }
            daemonAdapter.submitList(sortedList)
        }
    }
    
    private fun onDaemonToggled(type: DaemonType, enabled: Boolean) {
        // Save preference for optional daemons (so they auto-start on next app launch if enabled)
        daemonsViewModel.daemonStartupManager?.onDaemonToggled(type, enabled)
        
        if (enabled) {
            daemonsViewModel.startDaemon(type)
        } else {
            daemonsViewModel.stopDaemon(type)
        }
    }
    
    private fun onDaemonConfigureClicked(type: DaemonType) {
        Toast.makeText(context, "No configuration needed for ${type.displayName}", Toast.LENGTH_SHORT).show()
    }
    
    // ==================== Log Download (Debug Only) ====================
    
    /**
     * Download a daemon's log file from /data/local/tmp/ and share it.
     * Uses tail to limit output size and avoid OOM on large log files.
     */
    private fun onDownloadLogClicked(type: DaemonType) {
        val logPath = DaemonAdapter.getLogFilePath(type) ?: return
        val ctx = context ?: return
        val daemonName = type.displayName.replace(" ", "_").lowercase()
        
        Toast.makeText(ctx, "Fetching ${type.displayName} log...", Toast.LENGTH_SHORT).show()
        
        // Use tail to limit output — 10000 lines is ~1-2MB which is safe for ADB + String
        val adb = com.overdrive.app.launcher.AdbDaemonLauncher(ctx)
        adb.executeShellCommand(
            "wc -l < $logPath 2>/dev/null; echo '---SEPARATOR---'; tail -10000 $logPath 2>/dev/null",
            object : com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback {
                override fun onLog(message: String) {
                    activity?.runOnUiThread {
                        if (message.isBlank()) {
                            Toast.makeText(ctx, "Log file is empty or not found", Toast.LENGTH_SHORT).show()
                            return@runOnUiThread
                        }
                        
                        try {
                            // Parse: first part is line count, after separator is the log content
                            val parts = message.split("---SEPARATOR---", limit = 2)
                            val totalLines = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 0
                            val logContent = parts.getOrNull(1)?.trimStart('\n') ?: message
                            
                            if (logContent.isBlank()) {
                                Toast.makeText(ctx, "Log file is empty", Toast.LENGTH_SHORT).show()
                                return@runOnUiThread
                            }
                            
                            // Write to a shareable file in cache dir
                            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
                            val fileName = "${daemonName}_${timestamp}.log"
                            val cacheDir = java.io.File(ctx.cacheDir, "logs")
                            cacheDir.mkdirs()
                            val logFile = java.io.File(cacheDir, fileName)
                            
                            // Add header with metadata
                            val header = buildString {
                                appendLine("=== ${type.displayName} Log ===")
                                appendLine("Source: $logPath")
                                appendLine("Exported: ${java.util.Date()}")
                                if (totalLines > 10000) {
                                    appendLine("NOTE: Log truncated to last 10000 lines (total: $totalLines lines)")
                                }
                                appendLine("===")
                                appendLine()
                            }
                            logFile.writeText(header + logContent)
                            
                            // Share via intent
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                ctx,
                                "${ctx.packageName}.fileprovider",
                                logFile
                            )
                            
                            val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                this.type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                putExtra(android.content.Intent.EXTRA_SUBJECT, "${type.displayName} Log - $timestamp")
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            startActivity(android.content.Intent.createChooser(shareIntent, "Share ${type.displayName} Log"))
                        } catch (e: Exception) {
                            Toast.makeText(ctx, "❌ Failed to save log: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                
                override fun onLaunched() {}
                
                override fun onError(error: String) {
                    activity?.runOnUiThread {
                        Toast.makeText(ctx, "❌ Log file not found or unreadable", Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }
}
