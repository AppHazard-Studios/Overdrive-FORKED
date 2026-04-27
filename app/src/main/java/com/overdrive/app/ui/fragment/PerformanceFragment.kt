package com.overdrive.app.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.overdrive.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PerformanceFragment : Fragment() {

    private lateinit var monitoringDot: View
    private lateinit var tvMonitoringStatus: TextView
    private lateinit var tvLastUpdate: TextView
    private lateinit var tvCpuSystem: TextView
    private lateinit var tvCpuApp: TextView
    private lateinit var tvCpuFreq: TextView
    private lateinit var tvCpuTemp: TextView
    private lateinit var cpuBar: View
    private lateinit var tvGpuUsage: TextView
    private lateinit var tvGpuFreq: TextView
    private lateinit var tvGpuTemp: TextView
    private lateinit var tvGpuHealth: TextView
    private lateinit var gpuBar: View
    private lateinit var tvMemUsage: TextView
    private lateinit var tvMemUsed: TextView
    private lateinit var tvMemApp: TextView
    private lateinit var memBar: View
    private lateinit var tvSoc: TextView
    private lateinit var tvSocKwh: TextView
    private lateinit var tvSocRange: TextView
    private lateinit var tvChargingStatus: TextView
    private lateinit var socBar: View
    private lateinit var tvAppThreads: TextView
    private lateinit var tvAppFds: TextView
    private lateinit var tvAppGc: TextView

    private var clientId: String? = null
    private var pollJob: Job? = null
    private var heartbeatJob: Job? = null
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.fragment_performance, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        monitoringDot = view.findViewById(R.id.monitoringDot)
        tvMonitoringStatus = view.findViewById(R.id.tvMonitoringStatus)
        tvLastUpdate = view.findViewById(R.id.tvLastUpdate)
        tvCpuSystem = view.findViewById(R.id.tvCpuSystem)
        tvCpuApp = view.findViewById(R.id.tvCpuApp)
        tvCpuFreq = view.findViewById(R.id.tvCpuFreq)
        tvCpuTemp = view.findViewById(R.id.tvCpuTemp)
        cpuBar = view.findViewById(R.id.cpuBar)
        tvGpuUsage = view.findViewById(R.id.tvGpuUsage)
        tvGpuFreq = view.findViewById(R.id.tvGpuFreq)
        tvGpuTemp = view.findViewById(R.id.tvGpuTemp)
        tvGpuHealth = view.findViewById(R.id.tvGpuHealth)
        gpuBar = view.findViewById(R.id.gpuBar)
        tvMemUsage = view.findViewById(R.id.tvMemUsage)
        tvMemUsed = view.findViewById(R.id.tvMemUsed)
        tvMemApp = view.findViewById(R.id.tvMemApp)
        memBar = view.findViewById(R.id.memBar)
        tvSoc = view.findViewById(R.id.tvSoc)
        tvSocKwh = view.findViewById(R.id.tvSocKwh)
        tvSocRange = view.findViewById(R.id.tvSocRange)
        tvChargingStatus = view.findViewById(R.id.tvChargingStatus)
        socBar = view.findViewById(R.id.socBar)
        tvAppThreads = view.findViewById(R.id.tvAppThreads)
        tvAppFds = view.findViewById(R.id.tvAppFds)
        tvAppGc = view.findViewById(R.id.tvAppGc)
    }

    override fun onResume() {
        super.onResume()
        startMonitoring()
    }

    override fun onPause() {
        super.onPause()
        stopMonitoring()
    }

    private fun startMonitoring() {
        pollJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            // Register with the daemon so it starts CPU/GPU polling
            connect()

            // Fetch SOC once on start (changes slowly)
            fetchSoc()

            // Poll metrics every second
            while (isActive) {
                fetchPerformance()
                delay(1000)
            }
        }

        heartbeatJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            delay(5000)
            while (isActive) {
                sendHeartbeat()
                delay(5000)
            }
        }
    }

    private fun stopMonitoring() {
        pollJob?.cancel()
        heartbeatJob?.cancel()
        pollJob = null
        heartbeatJob = null
        // Fire-and-forget disconnect on a fresh scope so it survives fragment pause
        lifecycleScope.launch(Dispatchers.IO) { disconnect() }
    }

    private fun connect() {
        try {
            val body = JSONObject().apply {
                clientId?.let { put("clientId", it) }
            }
            val conn = URL("http://127.0.0.1:8080/api/performance/connect").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            try {
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                val response = JSONObject(conn.inputStream.bufferedReader().readText())
                clientId = response.optString("clientId").takeIf { it.isNotEmpty() }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            android.util.Log.w("Performance", "Connect failed: ${e.message}")
        }
    }

    private fun disconnect() {
        val id = clientId ?: return
        try {
            val body = JSONObject().apply { put("clientId", id) }
            val conn = URL("http://127.0.0.1:8080/api/performance/disconnect").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            try {
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                conn.inputStream.close()
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            android.util.Log.w("Performance", "Disconnect failed: ${e.message}")
        }
        clientId = null
    }

    private fun sendHeartbeat() {
        val id = clientId ?: return
        try {
            val body = JSONObject().apply { put("clientId", id) }
            val conn = URL("http://127.0.0.1:8080/api/performance/heartbeat").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            try {
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                conn.inputStream.close()
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            android.util.Log.w("Performance", "Heartbeat failed: ${e.message}")
        }
    }

    private suspend fun fetchPerformance() {
        try {
            val conn = URL("http://127.0.0.1:8080/api/performance").openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            val json = try {
                JSONObject(conn.inputStream.bufferedReader().readText())
            } finally {
                conn.disconnect()
            }

            withContext(Dispatchers.Main) {
                applyPerformanceData(json)
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { setMonitoringActive(false) }
        }
    }

    private suspend fun fetchSoc() {
        try {
            val conn = URL("http://127.0.0.1:8080/api/performance/soc/full?hours=72&points=50")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val json = try {
                JSONObject(conn.inputStream.bufferedReader().readText())
            } finally {
                conn.disconnect()
            }

            withContext(Dispatchers.Main) { applySocData(json) }
        } catch (e: Exception) {
            android.util.Log.w("Performance", "SOC fetch failed: ${e.message}")
        }
    }

    private fun applyPerformanceData(data: JSONObject) {
        setMonitoringActive(true)
        tvLastUpdate.text = timeFmt.format(Date())

        val cpu = data.optJSONObject("cpu")
        if (cpu != null) {
            val system = cpu.optDouble("system", 0.0)
            val app = cpu.optDouble("app", 0.0)
            val freqMhz = cpu.optDouble("freqMhz", 0.0)
            val tempC = cpu.optDouble("tempC", 0.0)
            tvCpuSystem.text = system.toInt().toString()
            tvCpuApp.text = "${app.toInt()}%"
            tvCpuFreq.text = if (freqMhz > 0) "${freqMhz.toInt()} MHz" else "--"
            tvCpuTemp.text = if (tempC > 0) "${tempC.toInt()}°C" else "--°C"
            setBarWidth(cpuBar, system.toFloat())
        }

        val gpu = data.optJSONObject("gpu")
        if (gpu != null) {
            val usage = gpu.optDouble("usage", 0.0)
            val freqMhz = gpu.optDouble("freqMhz", 0.0)
            val tempC = gpu.optDouble("tempC", 0.0)
            tvGpuUsage.text = usage.toInt().toString()
            tvGpuFreq.text = if (freqMhz > 0) "${freqMhz.toInt()} MHz" else "--"
            tvGpuTemp.text = if (tempC > 0) "${tempC.toInt()}°C" else "--°C"
            tvGpuHealth.text = gpuHealthLabel(usage, freqMhz)
            setBarWidth(gpuBar, usage.toFloat())
        }

        val mem = data.optJSONObject("memory")
        if (mem != null) {
            val usagePct = mem.optDouble("usagePercent", 0.0)
            val usedMb = mem.optDouble("usedMb", 0.0)
            val appMb = mem.optDouble("appTotalMb", 0.0)
            tvMemUsage.text = usagePct.toInt().toString()
            tvMemUsed.text = "${usedMb.toInt()} MB"
            tvMemApp.text = if (appMb > 0) "${appMb.toInt()} MB" else "--"
            setBarWidth(memBar, usagePct.toFloat())
        }

        val app = data.optJSONObject("app")
        if (app != null) {
            tvAppThreads.text = app.optInt("threads", 0).let { if (it > 0) it.toString() else "--" }
            tvAppFds.text = app.optInt("openFds", 0).let { if (it > 0) it.toString() else "--" }
            tvAppGc.text = app.optInt("gcCount", 0).toString()
        }
    }

    private fun applySocData(data: JSONObject) {
        val history = data.optJSONArray("history")
        val stats = data.optJSONObject("stats")

        var currentSoc = stats?.optDouble("currentSoc", Double.NaN) ?: Double.NaN
        var currentKwh = Double.NaN
        var currentRange = Double.NaN
        var isCharging = false

        if (history != null && history.length() > 0) {
            val latest = history.getJSONObject(history.length() - 1)
            if (currentSoc.isNaN()) currentSoc = latest.optDouble("soc", Double.NaN)
            currentKwh = latest.optDouble("kwh", Double.NaN)
            currentRange = latest.optDouble("range", Double.NaN)
            isCharging = latest.optBoolean("charging", false)
        }

        if (!currentSoc.isNaN()) {
            tvSoc.text = currentSoc.toInt().toString()
            setBarWidth(socBar, currentSoc.toFloat())
        }
        tvSocKwh.text = if (!currentKwh.isNaN() && currentKwh > 0) String.format("%.1f kWh", currentKwh) else "--"
        tvSocRange.text = if (!currentRange.isNaN() && currentRange > 0) "${currentRange.toInt()} km" else "--"
        tvChargingStatus.text = if (isCharging) "Charging" else "Idle"
        tvChargingStatus.setTextColor(
            resources.getColor(if (isCharging) R.color.brand_primary else R.color.text_muted, null)
        )
    }

    private fun setMonitoringActive(active: Boolean) {
        monitoringDot.setBackgroundResource(
            if (active) R.drawable.status_dot_online else R.drawable.status_dot_offline
        )
        tvMonitoringStatus.text = if (active) "LIVE" else "OFFLINE"
        tvMonitoringStatus.setTextColor(
            resources.getColor(if (active) R.color.status_running else R.color.text_muted, null)
        )
    }

    private fun setBarWidth(bar: View, percent: Float) {
        bar.post {
            val parent = bar.parent as? ViewGroup ?: return@post
            val params = bar.layoutParams
            params.width = (parent.width * percent.coerceIn(0f, 100f) / 100).toInt()
            bar.layoutParams = params
        }
    }

    private fun gpuHealthLabel(usage: Double, freqMhz: Double): String {
        if (freqMhz <= 0) return "--"
        return when {
            usage > 90 && freqMhz > 600 -> "High"
            usage > 70 -> "Busy"
            usage > 40 -> "Normal"
            else -> "Low"
        }
    }
}
