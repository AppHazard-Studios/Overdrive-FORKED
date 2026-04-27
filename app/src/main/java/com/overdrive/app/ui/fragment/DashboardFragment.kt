package com.overdrive.app.ui.fragment

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.overdrive.app.R
import com.overdrive.app.auth.AuthManager
import com.overdrive.app.ui.model.DaemonStatus
import com.overdrive.app.ui.model.DaemonType
import com.overdrive.app.ui.viewmodel.DaemonsViewModel
import com.overdrive.app.ui.viewmodel.RecordingViewModel
import com.google.android.material.card.MaterialCardView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

class DashboardFragment : Fragment() {

    private val daemonsViewModel: DaemonsViewModel by activityViewModels()
    private val recordingViewModel: RecordingViewModel by activityViewModels()

    // Recording card
    private lateinit var cardRecording: MaterialCardView
    private lateinit var tvRecPill: TextView
    private lateinit var tvRecValueNum: TextView
    private lateinit var tvRecValueUnit: TextView
    private lateinit var frameRecBar: FrameLayout
    private lateinit var viewRecBar: View
    private lateinit var tvRecDetail: TextView

    // Sentry card
    private lateinit var cardSurveillance: MaterialCardView
    private lateinit var viewSentryAccent: View
    private lateinit var tvSurvPill: TextView
    private lateinit var tvSurvEventsNum: TextView
    private lateinit var tvSurvEventsSub: TextView
    private lateinit var tvSurvLastDet: TextView

    // System card
    private lateinit var cardDaemonHealth: MaterialCardView
    private lateinit var tvDaemonCount: TextView
    private lateinit var tvCpuVal: TextView
    private lateinit var frameCpuBar: FrameLayout
    private lateinit var viewCpuBar: View
    private lateinit var tvGpuVal: TextView
    private lateinit var frameGpuBar: FrameLayout
    private lateinit var viewGpuBar: View
    private lateinit var tvMemVal: TextView
    private lateinit var frameMemBar: FrameLayout
    private lateinit var viewMemBar: View
    private lateinit var layoutDaemonDots: LinearLayout

    // Vehicle card
    private lateinit var cardVehicle: MaterialCardView
    private lateinit var viewVehicleAccent: View
    private lateinit var tvThermalBadge: TextView
    private lateinit var tvVehicleVoltNum: TextView
    private lateinit var tvStatSoc: TextView
    private lateinit var tvStatRange: TextView
    private lateinit var layoutTodayTrip: LinearLayout
    private lateinit var tvTodayScore: TextView
    private lateinit var tvTodayDistance: TextView

    private val daemonDotViews = mutableMapOf<DaemonType, View>()

    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            fetchLiveData()
            handler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 60_000L
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_dashboard, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initViews(view)
        setupClickListeners()
        buildDaemonDotRow()
        observeViewModels()
    }

    override fun onResume() {
        super.onResume()
        fetchLiveData()
        handler.postDelayed(refreshRunnable, REFRESH_INTERVAL_MS)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshRunnable)
    }

    private fun initViews(view: View) {
        cardRecording      = view.findViewById(R.id.cardRecording)
        tvRecPill          = view.findViewById(R.id.tvRecPill)
        tvRecValueNum      = view.findViewById(R.id.tvRecValueNum)
        tvRecValueUnit     = view.findViewById(R.id.tvRecValueUnit)
        frameRecBar        = view.findViewById(R.id.frameRecBar)
        viewRecBar         = view.findViewById(R.id.viewRecBar)
        tvRecDetail        = view.findViewById(R.id.tvRecDetail)

        cardSurveillance   = view.findViewById(R.id.cardSurveillance)
        viewSentryAccent   = view.findViewById(R.id.viewSentryAccent)
        tvSurvPill         = view.findViewById(R.id.tvSurvPill)
        tvSurvEventsNum    = view.findViewById(R.id.tvSurvEventsNum)
        tvSurvEventsSub    = view.findViewById(R.id.tvSurvEventsSub)
        tvSurvLastDet      = view.findViewById(R.id.tvSurvLastDet)

        cardDaemonHealth   = view.findViewById(R.id.cardDaemonHealth)
        tvDaemonCount      = view.findViewById(R.id.tvDaemonCount)
        tvCpuVal           = view.findViewById(R.id.tvCpuVal)
        frameCpuBar        = view.findViewById(R.id.frameCpuBar)
        viewCpuBar         = view.findViewById(R.id.viewCpuBar)
        tvGpuVal           = view.findViewById(R.id.tvGpuVal)
        frameGpuBar        = view.findViewById(R.id.frameGpuBar)
        viewGpuBar         = view.findViewById(R.id.viewGpuBar)
        tvMemVal           = view.findViewById(R.id.tvMemVal)
        frameMemBar        = view.findViewById(R.id.frameMemBar)
        viewMemBar         = view.findViewById(R.id.viewMemBar)
        layoutDaemonDots   = view.findViewById(R.id.layoutDaemonDots)

        cardVehicle        = view.findViewById(R.id.cardVehicle)
        viewVehicleAccent  = view.findViewById(R.id.viewVehicleAccent)
        tvThermalBadge     = view.findViewById(R.id.tvThermalBadge)
        tvVehicleVoltNum   = view.findViewById(R.id.tvVehicleVoltNum)
        tvStatSoc          = view.findViewById(R.id.tvStatSoc)
        tvStatRange        = view.findViewById(R.id.tvStatRange)
        layoutTodayTrip    = view.findViewById(R.id.layoutTodayTrip)
        tvTodayScore       = view.findViewById(R.id.tvTodayScore)
        tvTodayDistance    = view.findViewById(R.id.tvTodayDistance)
    }

    private fun setupClickListeners() {
        val navOptions = NavOptions.Builder()
            .setLaunchSingleTop(true)
            .setPopUpTo(R.id.nav_graph, false)
            .build()
        cardRecording.setOnClickListener {
            findNavController().navigate(R.id.recordingFragment, null, navOptions)
        }
        cardSurveillance.setOnClickListener {
            findNavController().navigate(R.id.sentryConfigFragment, null, navOptions)
        }
        cardDaemonHealth.setOnClickListener {
            findNavController().navigate(R.id.daemonsFragment, null, navOptions)
        }
        cardVehicle.setOnClickListener {
            findNavController().navigate(R.id.performanceFragment, null, navOptions)
        }
    }

    // ── Daemon dot row ─────────────────────────────────────────────────────────

    private fun buildDaemonDotRow() {
        layoutDaemonDots.removeAllViews()
        daemonDotViews.clear()
        DaemonType.values().forEach { type ->
            val item = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val dot = View(requireContext()).apply {
                val sizePx = (8 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                    bottomMargin = (3 * resources.displayMetrics.density).toInt()
                }
                setBackgroundResource(R.drawable.status_dot_offline)
            }
            val label = TextView(requireContext()).apply {
                text = type.displayName
                    .replace("Daemon", "").replace("Proxy", "").replace("Bot", "").trim()
                textSize = 9f
                setTextColor(resources.getColor(R.color.text_muted, null))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = android.view.Gravity.CENTER
            }
            item.addView(dot)
            item.addView(label)
            layoutDaemonDots.addView(item)
            daemonDotViews[type] = dot
        }
    }

    // ── ViewModel observations ─────────────────────────────────────────────────

    private fun observeViewModels() {
        recordingViewModel.isRecording.observe(viewLifecycleOwner) { isRec ->
            tvRecPill.text = if (isRec) "● REC" else "IDLE"
            tvRecPill.setTextColor(resources.getColor(
                if (isRec) R.color.status_danger else R.color.text_muted, null
            ))
        }

        recordingViewModel.storageInfo.observe(viewLifecycleOwner) { info ->
            val (num, unit) = formatStorageSplit(info.availableBytes)
            tvRecValueNum.text = num
            tvRecValueUnit.text = unit
            tvRecDetail.text = "${info.usedFormatted} used  ·  ${info.totalFormatted} total"
            val pct = info.usagePercent.coerceIn(0, 100)
            setBarWidth(frameRecBar, viewRecBar, pct)
        }

        daemonsViewModel.daemonStates.observe(viewLifecycleOwner) { states ->
            val running = states.values.count { it.status == DaemonStatus.RUNNING }
            val total   = states.size

            tvDaemonCount.text = "$running/$total"
            tvDaemonCount.setTextColor(resources.getColor(
                when {
                    running == total && total > 0 -> R.color.status_success
                    running > 0                  -> R.color.status_warning
                    else                         -> R.color.status_danger
                }, null
            ))

            states.forEach { (type, state) ->
                daemonDotViews[type]?.setBackgroundResource(
                    when (state.status) {
                        DaemonStatus.RUNNING  -> R.drawable.status_dot_online
                        DaemonStatus.ERROR    -> R.drawable.status_dot_error
                        DaemonStatus.STARTING -> R.drawable.status_dot_starting
                        else                  -> R.drawable.status_dot_offline
                    }
                )
            }
        }
    }

    // ── Live data fetch ────────────────────────────────────────────────────────

    private fun fetchLiveData() {
        val jwt = try { AuthManager.generateJwt() } catch (_: Exception) { null }
        fetchSurveillanceStatus(jwt)
        fetchBatterySnapshot(jwt)
        fetchPerfSnapshot(jwt)
        fetchTodayTrips(jwt)
    }

    private fun fetchSurveillanceStatus(jwt: String?) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = URL("http://127.0.0.1:8080/api/surveillance/status")
                    .openConnection() as HttpURLConnection
                conn.connectTimeout = 3_000
                conn.readTimeout    = 3_000
                if (jwt != null) conn.setRequestProperty("Cookie", "byd_session=$jwt")
                val json = if (conn.responseCode == 200)
                    JSONObject(conn.inputStream.bufferedReader().readText()) else null
                conn.disconnect()
                if (json != null) withContext(Dispatchers.Main) { updateSentryCard(json) }
            } catch (_: Exception) {}
        }
    }

    private fun fetchBatterySnapshot(jwt: String?) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = URL("http://127.0.0.1:8080/api/performance/battery?hours=1&points=5")
                    .openConnection() as HttpURLConnection
                conn.connectTimeout = 3_000
                conn.readTimeout    = 3_000
                if (jwt != null) conn.setRequestProperty("Cookie", "byd_session=$jwt")
                val json = if (conn.responseCode == 200)
                    JSONObject(conn.inputStream.bufferedReader().readText()) else null
                conn.disconnect()
                if (json != null) withContext(Dispatchers.Main) { updateVehicleCard(json) }
            } catch (_: Exception) {}
        }
    }

    private fun fetchPerfSnapshot(jwt: String?) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = URL("http://127.0.0.1:8080/api/performance")
                    .openConnection() as HttpURLConnection
                conn.connectTimeout = 3_000
                conn.readTimeout    = 3_000
                if (jwt != null) conn.setRequestProperty("Cookie", "byd_session=$jwt")
                val json = if (conn.responseCode == 200)
                    JSONObject(conn.inputStream.bufferedReader().readText()) else null
                conn.disconnect()
                if (json != null) withContext(Dispatchers.Main) { updateSystemMetrics(json) }
            } catch (_: Exception) {}
        }
    }

    private fun fetchTodayTrips(jwt: String?) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = URL("http://127.0.0.1:8080/api/trips/summary?days=1")
                    .openConnection() as HttpURLConnection
                conn.connectTimeout = 3_000
                conn.readTimeout    = 3_000
                if (jwt != null) conn.setRequestProperty("Cookie", "byd_session=$jwt")
                val json = if (conn.responseCode == 200)
                    JSONObject(conn.inputStream.bufferedReader().readText()) else null
                conn.disconnect()
                if (json != null) withContext(Dispatchers.Main) { updateTodayTrip(json) }
            } catch (_: Exception) {}
        }
    }

    // ── Card update methods ────────────────────────────────────────────────────

    private fun updateSentryCard(json: JSONObject) {
        val enabled = json.optBoolean("enabled", false)
        val active  = json.optBoolean("active",  false)
        val events  = json.optInt("events_today", -1)
        val lastDet = json.optString("last_detection", null)

        val (pillText, pillColor, accentColor) = when {
            active  -> Triple("DETECTING", R.color.status_warning, R.color.status_warning)
            enabled -> Triple("ARMED",     R.color.status_success, R.color.status_success)
            else    -> Triple("OFF",       R.color.text_muted,     android.R.color.transparent)
        }

        tvSurvPill.text = pillText
        tvSurvPill.setTextColor(resources.getColor(pillColor, null))

        if (accentColor == android.R.color.transparent) {
            viewSentryAccent.setBackgroundResource(R.drawable.gradient_brand)
        } else {
            viewSentryAccent.setBackgroundColor(resources.getColor(accentColor, null))
        }

        tvSurvEventsNum.text = if (events >= 0) "$events" else "--"

        if (events == 0 || lastDet.isNullOrEmpty()) {
            tvSurvLastDet.text = "All clear today"
        } else {
            tvSurvLastDet.text = "Last at ${parseTimeHHMM(lastDet)}"
        }
    }

    private fun updateVehicleCard(json: JSONObject) {
        val current = json.optJSONObject("current") ?: return
        val v12 = current.optDouble("voltage12v", Double.NaN)

        val history = json.optJSONArray("voltageHistory")
        var soc   = Double.NaN
        var range = Double.NaN
        if (history != null && history.length() > 0) {
            val last = history.getJSONObject(history.length() - 1)
            soc   = last.optDouble("soc",   Double.NaN)
            range = last.optDouble("range", Double.NaN)
        }

        if (!v12.isNaN()) {
            val voltColor = resources.getColor(when {
                v12 >= 12.4 -> R.color.status_success
                v12 >= 12.0 -> R.color.status_warning
                else        -> R.color.status_danger
            }, null)
            tvVehicleVoltNum.text = "%.1f".format(v12)
            tvVehicleVoltNum.setTextColor(voltColor)
            viewVehicleAccent.setBackgroundColor(voltColor)

            val (badgeText, badgeColor) = when {
                v12 >= 12.4 -> "GOOD"     to R.color.status_success
                v12 >= 12.0 -> "LOW"      to R.color.status_warning
                else        -> "CRITICAL" to R.color.status_danger
            }
            tvThermalBadge.text = badgeText
            tvThermalBadge.setTextColor(resources.getColor(badgeColor, null))
        }

        if (!soc.isNaN())   tvStatSoc.text   = "%.0f%%".format(soc)
        if (!range.isNaN()) tvStatRange.text  = "%.0f km".format(range)
    }

    private fun updateSystemMetrics(json: JSONObject) {
        json.optJSONObject("cpu")?.let { cpu ->
            val pct = cpu.optDouble("system", Double.NaN)
            if (!pct.isNaN()) {
                tvCpuVal.text = "%.0f%%".format(pct)
                setBarWidth(frameCpuBar, viewCpuBar, pct.toInt())
            }
        }
        json.optJSONObject("gpu")?.let { gpu ->
            val pct = gpu.optDouble("usage", Double.NaN)
            if (!pct.isNaN()) {
                tvGpuVal.text = "%.0f%%".format(pct)
                setBarWidth(frameGpuBar, viewGpuBar, pct.toInt())
            }
        }
        json.optJSONObject("memory")?.let { mem ->
            val pct = mem.optDouble("usagePercent", Double.NaN)
            if (!pct.isNaN()) {
                tvMemVal.text = "%.0f%%".format(pct)
                setBarWidth(frameMemBar, viewMemBar, pct.toInt())
            }
        }
    }

    private fun updateTodayTrip(json: JSONObject) {
        val arr = json.optJSONArray("summary") ?: return
        if (arr.length() == 0) return
        val s = arr.getJSONObject(0)

        val count  = s.optInt("tripCount",       s.optInt("trip_count", 0))
        val distKm = s.optDouble("totalDistanceKm", s.optDouble("total_distance_km", 0.0))

        if (count == 0) return

        val sA  = s.optDouble("avgAnticipationScore",    s.optDouble("avg_anticipation_score",    0.0))
        val sS  = s.optDouble("avgSmoothnessScore",      s.optDouble("avg_smoothness_score",       0.0))
        val sSD = s.optDouble("avgSpeedDisciplineScore", s.optDouble("avg_speed_discipline_score", 0.0))
        val sE  = s.optDouble("avgEfficiencyScore",      s.optDouble("avg_efficiency_score",       0.0))
        val sC  = s.optDouble("avgConsistencyScore",     s.optDouble("avg_consistency_score",      0.0))
        val score = ((sA + sS + sSD + sE + sC) / 5).toInt()

        tvTodayScore.text = "$score"
        tvTodayScore.setTextColor(resources.getColor(when {
            score >= 70 -> R.color.status_success
            score >= 40 -> R.color.status_warning
            else        -> R.color.status_danger
        }, null))
        tvTodayDistance.text = "%.0f km".format(distKm)
        layoutTodayTrip.visibility = View.VISIBLE
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun setBarWidth(frame: FrameLayout, bar: View, percent: Int) {
        val pct = percent.coerceIn(0, 100)
        val barColor = when {
            pct >= 95 -> resources.getColor(R.color.status_danger,  null)
            pct >= 80 -> resources.getColor(R.color.status_warning, null)
            else      -> null
        }
        frame.post {
            val params = bar.layoutParams
            params.width = (frame.width * pct / 100f).toInt().coerceAtLeast(0)
            bar.layoutParams = params
            if (barColor != null) {
                bar.setBackgroundColor(barColor)
            } else {
                bar.setBackgroundResource(R.drawable.gradient_brand)
            }
        }
    }

    private fun formatStorageSplit(bytes: Long): Pair<String, String> = when {
        bytes >= 1_000_000_000L -> "%.1f".format(bytes / 1_000_000_000.0) to " GB"
        bytes >= 1_000_000L     -> "%.1f".format(bytes / 1_000_000.0)     to " MB"
        bytes >= 1_000L         -> "%.1f".format(bytes / 1_000.0)         to " KB"
        else                    -> "$bytes" to " B"
    }

    private fun parseTimeHHMM(raw: String): String {
        val isoFormats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ssZ",
            "yyyy-MM-dd'T'HH:mm:ss"
        )
        for (fmt in isoFormats) {
            try {
                val date = SimpleDateFormat(fmt, Locale.US).parse(raw) ?: continue
                return SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
            } catch (_: Exception) {}
        }
        // Already short — trim to HH:MM if it looks like a time
        if (raw.length >= 5 && raw[2] == ':') return raw.substring(0, 5)
        return raw
    }
}
