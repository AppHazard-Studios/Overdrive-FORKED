package com.overdrive.app.ui.fragment

import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
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

class DashboardFragment : Fragment() {

    private val daemonsViewModel: DaemonsViewModel by activityViewModels()
    private val recordingViewModel: RecordingViewModel by activityViewModels()

    // Quick stats strip
    private lateinit var tileStatVolt: MaterialCardView
    private lateinit var tvStatCpu: TextView
    private lateinit var tvStatGpu: TextView
    private lateinit var tvStatMem: TextView
    private lateinit var tvStatVolt: TextView
    private lateinit var tvStatSoc: TextView

    // Surveillance card
    private lateinit var tvSurvPill: TextView
    private lateinit var tvSurvEvents: TextView
    private lateinit var tvSurvLastDetection: TextView

    // Recording card
    private lateinit var tvRecPill: TextView
    private lateinit var tvRecStorage: TextView
    private lateinit var viewRecStorageBar: View
    private lateinit var tvRecStorageDetail: TextView

    // Trips card
    private lateinit var cardTrips: MaterialCardView
    private lateinit var tvTripsCount: TextView
    private lateinit var tvTripsDistance: TextView
    private lateinit var tvTripsScore: TextView

    // Daemon health card
    private lateinit var cardDaemonHealth: MaterialCardView
    private lateinit var layoutDaemonDots: LinearLayout
    private lateinit var tvDaemonCount: TextView
    private lateinit var tvDaemonError: TextView

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

    // ── View init ──────────────────────────────────────────────────────────────

    private fun initViews(view: View) {
        tileStatVolt        = view.findViewById(R.id.tileStatVolt)
        tvStatCpu           = view.findViewById(R.id.tvStatCpu)
        tvStatGpu           = view.findViewById(R.id.tvStatGpu)
        tvStatMem           = view.findViewById(R.id.tvStatMem)
        tvStatVolt          = view.findViewById(R.id.tvStatVolt)
        tvStatSoc           = view.findViewById(R.id.tvStatSoc)

        tvSurvPill          = view.findViewById(R.id.tvSurvPill)
        tvSurvEvents        = view.findViewById(R.id.tvSurvEvents)
        tvSurvLastDetection = view.findViewById(R.id.tvSurvLastDetection)

        tvRecPill           = view.findViewById(R.id.tvRecPill)
        tvRecStorage        = view.findViewById(R.id.tvRecStorage)
        viewRecStorageBar   = view.findViewById(R.id.viewRecStorageBar)
        tvRecStorageDetail  = view.findViewById(R.id.tvRecStorageDetail)

        cardTrips           = view.findViewById(R.id.cardTrips)
        tvTripsCount        = view.findViewById(R.id.tvTripsCount)
        tvTripsDistance     = view.findViewById(R.id.tvTripsDistance)
        tvTripsScore        = view.findViewById(R.id.tvTripsScore)

        cardDaemonHealth    = view.findViewById(R.id.cardDaemonHealth)
        layoutDaemonDots    = view.findViewById(R.id.layoutDaemonDots)
        tvDaemonCount       = view.findViewById(R.id.tvDaemonCount)
        tvDaemonError       = view.findViewById(R.id.tvDaemonError)
    }

    // ── Daemon dot row (built once; dots updated by observer) ──────────────────

    private val daemonDotViews = mutableMapOf<DaemonType, View>()

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

    // ── Click listeners ────────────────────────────────────────────────────────

    private fun setupClickListeners() {
        val navOptions = NavOptions.Builder()
            .setLaunchSingleTop(true)
            .setPopUpTo(R.id.nav_graph, false)
            .build()

        view?.findViewById<MaterialCardView>(R.id.cardSurveillance)?.setOnClickListener {
            findNavController().navigate(R.id.sentryConfigFragment, null, navOptions)
        }
        view?.findViewById<MaterialCardView>(R.id.cardRecording)?.setOnClickListener {
            findNavController().navigate(R.id.recordingFragment, null, navOptions)
        }
        cardDaemonHealth.setOnClickListener {
            findNavController().navigate(R.id.daemonsFragment, null, navOptions)
        }
        cardTrips.setOnClickListener {
            findNavController().navigate(R.id.tripsFragment, null, navOptions)
        }
    }

    // ── ViewModel observations ─────────────────────────────────────────────────

    private fun observeViewModels() {
        daemonsViewModel.daemonStates.observe(viewLifecycleOwner) { states ->
            val running = states.values.count { it.status == DaemonStatus.RUNNING }
            val total   = states.size

            tvDaemonCount.text = "$running/$total"
            tvDaemonCount.setTextColor(resources.getColor(
                when {
                    running == total && total > 0 -> R.color.status_success
                    running > 0 -> R.color.status_warning
                    else -> R.color.status_danger
                }, null
            ))

            // Update per-daemon status dots
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

            val errorNames = states.entries
                .filter { it.value.status == DaemonStatus.ERROR }
                .joinToString(" · ") { it.key.displayName }
            if (errorNames.isNotEmpty()) {
                tvDaemonError.text = "Error: $errorNames"
                tvDaemonError.visibility = View.VISIBLE
            } else {
                tvDaemonError.visibility = View.GONE
            }
        }

        recordingViewModel.isRecording.observe(viewLifecycleOwner) { isRec ->
            tvRecPill.text = if (isRec) "● REC" else "IDLE"
            tvRecPill.setTextColor(resources.getColor(
                if (isRec) R.color.status_danger else R.color.text_muted, null
            ))
        }

        recordingViewModel.storageInfo.observe(viewLifecycleOwner) { info ->
            tvRecStorage.text = "${info.availableFormatted} free"
            tvRecStorageDetail.text = "${info.usedFormatted} used of ${info.totalFormatted}"
            val percent = info.usagePercent.coerceIn(0, 100)
            viewRecStorageBar.post {
                val parent = viewRecStorageBar.parent as? ViewGroup ?: return@post
                val params = viewRecStorageBar.layoutParams
                params.width = (parent.width * percent / 100f).toInt().coerceAtLeast(0)
                viewRecStorageBar.layoutParams = params
            }
        }
    }

    // ── Live data fetch ────────────────────────────────────────────────────────

    private fun fetchLiveData() {
        val jwt = try { AuthManager.generateJwt() } catch (_: Exception) { null }
        fetchSurveillanceStatus(jwt)
        fetchBatterySnapshot(jwt)
        fetchPerfSnapshot(jwt)
        fetchTripsSnapshot(jwt)
    }

    private fun fetchSurveillanceStatus(jwt: String?) {
        Thread {
            try {
                val conn = URL("http://127.0.0.1:8080/api/surveillance/status")
                    .openConnection() as HttpURLConnection
                conn.connectTimeout = 3_000
                conn.readTimeout    = 3_000
                if (jwt != null) conn.setRequestProperty("Cookie", "byd_session=$jwt")
                val json = if (conn.responseCode == 200)
                    JSONObject(conn.inputStream.bufferedReader().readText()) else null
                conn.disconnect()
                handler.post { if (json != null) updateSurveillanceCard(json) }
            } catch (_: Exception) {}
        }.start()
    }

    private fun fetchBatterySnapshot(jwt: String?) {
        Thread {
            try {
                val conn = URL("http://127.0.0.1:8080/api/performance/battery?hours=1&points=5")
                    .openConnection() as HttpURLConnection
                conn.connectTimeout = 3_000
                conn.readTimeout    = 3_000
                if (jwt != null) conn.setRequestProperty("Cookie", "byd_session=$jwt")
                val json = if (conn.responseCode == 200)
                    JSONObject(conn.inputStream.bufferedReader().readText()) else null
                conn.disconnect()
                handler.post { if (json != null) updateBatteryTiles(json) }
            } catch (_: Exception) {}
        }.start()
    }

    private fun fetchPerfSnapshot(jwt: String?) {
        Thread {
            try {
                val conn = URL("http://127.0.0.1:8080/api/performance")
                    .openConnection() as HttpURLConnection
                conn.connectTimeout = 3_000
                conn.readTimeout    = 3_000
                if (jwt != null) conn.setRequestProperty("Cookie", "byd_session=$jwt")
                val json = if (conn.responseCode == 200)
                    JSONObject(conn.inputStream.bufferedReader().readText()) else null
                conn.disconnect()
                handler.post { if (json != null) updatePerfTiles(json) }
            } catch (_: Exception) {}
        }.start()
    }

    private fun fetchTripsSnapshot(jwt: String?) {
        Thread {
            try {
                val conn = URL("http://127.0.0.1:8080/api/trips/summary?days=7")
                    .openConnection() as HttpURLConnection
                conn.connectTimeout = 3_000
                conn.readTimeout    = 3_000
                if (jwt != null) conn.setRequestProperty("Cookie", "byd_session=$jwt")
                val json = if (conn.responseCode == 200)
                    JSONObject(conn.inputStream.bufferedReader().readText()) else null
                conn.disconnect()
                handler.post { if (json != null) updateTripsCard(json) }
            } catch (_: Exception) {}
        }.start()
    }

    // ── Card update methods ────────────────────────────────────────────────────

    private fun updateSurveillanceCard(json: JSONObject) {
        val enabled = json.optBoolean("enabled", false)
        val active  = json.optBoolean("active",  false)
        val events  = json.optInt("events_today", -1)
        val lastDet = json.optString("last_detection", null)

        tvSurvPill.text = when {
            active  -> "DETECTING"
            enabled -> "ARMED"
            else    -> "OFF"
        }
        tvSurvPill.setTextColor(resources.getColor(when {
            active  -> R.color.status_warning
            enabled -> R.color.status_success
            else    -> R.color.text_muted
        }, null))

        tvSurvEvents.text = if (events >= 0) "$events events today" else "-- events today"
        tvSurvLastDetection.text = if (!lastDet.isNullOrEmpty()) "Last: $lastDet" else "No detections today"
    }

    private fun updateBatteryTiles(json: JSONObject) {
        val current = json.optJSONObject("current") ?: return
        val v12 = current.optDouble("voltage12v", Double.NaN)

        val history = json.optJSONArray("voltageHistory")
        var soc = Double.NaN
        if (history != null && history.length() > 0) {
            soc = history.getJSONObject(history.length() - 1).optDouble("soc", Double.NaN)
        }

        if (!v12.isNaN()) {
            tvStatVolt.text = "%.1fV".format(v12)
            val voltColor = resources.getColor(when {
                v12 >= 12.4 -> R.color.status_success
                v12 >= 12.0 -> R.color.status_warning
                else        -> R.color.status_danger
            }, null)
            tvStatVolt.setTextColor(voltColor)
            tileStatVolt.strokeColor = ColorStateList.valueOf(voltColor)
        }

        if (!soc.isNaN()) tvStatSoc.text = "%.0f%%".format(soc)
    }

    private fun updatePerfTiles(json: JSONObject) {
        json.optJSONObject("cpu")?.let { cpu ->
            val sys = cpu.optDouble("system", Double.NaN)
            if (!sys.isNaN()) tvStatCpu.text = "%.0f%%".format(sys)
        }
        json.optJSONObject("gpu")?.let { gpu ->
            val usage = gpu.optDouble("usage", Double.NaN)
            if (!usage.isNaN()) tvStatGpu.text = "%.0f%%".format(usage)
        }
        json.optJSONObject("memory")?.let { mem ->
            val pct = mem.optDouble("usagePercent", Double.NaN)
            if (!pct.isNaN()) tvStatMem.text = "%.0f%%".format(pct)
        }
    }

    private fun updateTripsCard(json: JSONObject) {
        val summaryArr = json.optJSONArray("summary") ?: return
        if (summaryArr.length() == 0) return
        val s = summaryArr.getJSONObject(0)

        val count = s.optInt("tripCount", s.optInt("trip_count", 0))
        val distKm = s.optDouble("totalDistanceKm", s.optDouble("total_distance_km", 0.0))

        val sA  = s.optDouble("avgAnticipationScore",   s.optDouble("avg_anticipation_score",    0.0))
        val sS  = s.optDouble("avgSmoothnessScore",     s.optDouble("avg_smoothness_score",       0.0))
        val sSD = s.optDouble("avgSpeedDisciplineScore",s.optDouble("avg_speed_discipline_score", 0.0))
        val sE  = s.optDouble("avgEfficiencyScore",     s.optDouble("avg_efficiency_score",       0.0))
        val sC  = s.optDouble("avgConsistencyScore",    s.optDouble("avg_consistency_score",      0.0))
        val score = ((sA + sS + sSD + sE + sC) / 5).toInt()

        tvTripsCount.text    = "$count"
        tvTripsDistance.text = "%.0f".format(distKm)
        tvTripsScore.text    = "$score"
        tvTripsScore.setTextColor(resources.getColor(when {
            score >= 70 -> R.color.status_success
            score >= 40 -> R.color.status_warning
            else        -> R.color.status_danger
        }, null))
    }
}
