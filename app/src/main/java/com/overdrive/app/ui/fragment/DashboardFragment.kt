package com.overdrive.app.ui.fragment

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.RotateAnimation
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.overdrive.app.R
import com.overdrive.app.auth.AuthManager
import com.overdrive.app.client.CameraDaemonClient
import com.overdrive.app.ui.model.AccessMode
import com.overdrive.app.ui.model.DaemonStatus
import com.overdrive.app.ui.model.DaemonType
import com.overdrive.app.ui.viewmodel.DaemonsViewModel
import com.overdrive.app.ui.viewmodel.MainViewModel
import com.overdrive.app.ui.viewmodel.RecordingViewModel
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class DashboardFragment : Fragment() {

    private val mainViewModel: MainViewModel by activityViewModels()
    private val daemonsViewModel: DaemonsViewModel by activityViewModels()
    private val recordingViewModel: RecordingViewModel by activityViewModels()

    // Surveillance card
    private lateinit var tvSurvPill: TextView
    private lateinit var tvSurvEvents: TextView
    private lateinit var tvSurvLastDetection: TextView

    // Recording card
    private lateinit var tvRecPill: TextView
    private lateinit var tvRecStorage: TextView
    private lateinit var viewRecStorageBar: View
    private lateinit var tvRecStorageDetail: TextView

    // Daemon health card
    private lateinit var tvDaemonCount: TextView
    private lateinit var tvDaemonError: TextView

    // Vehicle / 12V card
    private lateinit var tv12v: TextView
    private lateinit var tvSoc: TextView
    private lateinit var tvRange: TextView
    private lateinit var tvThermalStatus: TextView

    // Trips card
    private lateinit var cardTrips: MaterialCardView

    // Remote access section
    private lateinit var rowRemoteAccessHeader: LinearLayout
    private lateinit var layoutRemoteAccessBody: LinearLayout
    private lateinit var ivRemoteAccessChevron: ImageView
    private lateinit var switchAccessMode: SwitchMaterial
    private lateinit var tvAccessMode: TextView
    private lateinit var urlStatusDot: View
    private lateinit var tvCurrentUrl: TextView
    private lateinit var btnCopyUrl: ImageButton
    private lateinit var tvDeviceToken: TextView
    private lateinit var btnToggleToken: ImageView
    private lateinit var btnCopyToken: ImageView
    private lateinit var btnRegenerateToken: com.google.android.material.button.MaterialButton

    private var isTokenVisible = false
    private var isRemoteAccessExpanded = false
    private var isUpdatingSwitch = false

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
        setupAccessModeToggle()
        observeViewModels()
        loadAuthState()
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
        tvSurvPill             = view.findViewById(R.id.tvSurvPill)
        tvSurvEvents           = view.findViewById(R.id.tvSurvEvents)
        tvSurvLastDetection    = view.findViewById(R.id.tvSurvLastDetection)

        tvRecPill              = view.findViewById(R.id.tvRecPill)
        tvRecStorage           = view.findViewById(R.id.tvRecStorage)
        viewRecStorageBar      = view.findViewById(R.id.viewRecStorageBar)
        tvRecStorageDetail     = view.findViewById(R.id.tvRecStorageDetail)

        tvDaemonCount          = view.findViewById(R.id.tvDaemonCount)
        tvDaemonError          = view.findViewById(R.id.tvDaemonError)

        tv12v                  = view.findViewById(R.id.tv12v)
        tvSoc                  = view.findViewById(R.id.tvSoc)
        tvRange                = view.findViewById(R.id.tvRange)
        tvThermalStatus        = view.findViewById(R.id.tvThermalStatus)

        cardTrips              = view.findViewById(R.id.cardTrips)

        rowRemoteAccessHeader  = view.findViewById(R.id.rowRemoteAccessHeader)
        layoutRemoteAccessBody = view.findViewById(R.id.layoutRemoteAccessBody)
        ivRemoteAccessChevron  = view.findViewById(R.id.ivRemoteAccessChevron)
        switchAccessMode       = view.findViewById(R.id.switchAccessMode)
        tvAccessMode           = view.findViewById(R.id.tvAccessMode)
        urlStatusDot           = view.findViewById(R.id.urlStatusDot)
        tvCurrentUrl           = view.findViewById(R.id.tvCurrentUrl)
        btnCopyUrl             = view.findViewById(R.id.btnCopyUrl)
        tvDeviceToken          = view.findViewById(R.id.tvDeviceToken)
        btnToggleToken         = view.findViewById(R.id.btnToggleToken)
        btnCopyToken           = view.findViewById(R.id.btnCopyToken)
        btnRegenerateToken     = view.findViewById(R.id.btnRegenerateToken)
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

        view?.findViewById<MaterialCardView>(R.id.cardDaemonHealth)?.setOnClickListener {
            findNavController().navigate(R.id.daemonsFragment, null, navOptions)
        }

        view?.findViewById<MaterialCardView>(R.id.cardVehicle)?.setOnClickListener {
            findNavController().navigate(R.id.performanceFragment, null, navOptions)
        }

        cardTrips.setOnClickListener {
            findNavController().navigate(R.id.tripsFragment, null, navOptions)
        }

        rowRemoteAccessHeader.setOnClickListener { toggleRemoteAccess() }

        btnCopyUrl.setOnClickListener {
            val url = mainViewModel.currentUrl.value
            if (!url.isNullOrEmpty()) {
                val cb = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("Tunnel URL", url))
                Toast.makeText(requireContext(), "URL copied", Toast.LENGTH_SHORT).show()
            }
        }

        btnToggleToken.setOnClickListener { toggleTokenVisibility() }
        btnCopyToken.setOnClickListener   { copyTokenToClipboard() }
        btnRegenerateToken.setOnClickListener { showRegenerateConfirmation() }
    }

    private fun setupAccessModeToggle() {
        switchAccessMode.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingSwitch) return@setOnCheckedChangeListener
            val mode = if (isChecked) AccessMode.PUBLIC else AccessMode.PRIVATE
            mainViewModel.setAccessMode(mode)
            daemonsViewModel.daemonStartupManager?.onAccessModeChanged(mode)
        }
    }

    // ── Remote access collapse/expand ─────────────────────────────────────────

    private fun toggleRemoteAccess() {
        isRemoteAccessExpanded = !isRemoteAccessExpanded
        layoutRemoteAccessBody.visibility = if (isRemoteAccessExpanded) View.VISIBLE else View.GONE

        val toAngle = if (isRemoteAccessExpanded) 270f else 90f
        val fromAngle = if (isRemoteAccessExpanded) 90f else 270f
        val anim = RotateAnimation(fromAngle, toAngle,
            RotateAnimation.RELATIVE_TO_SELF, 0.5f,
            RotateAnimation.RELATIVE_TO_SELF, 0.5f).apply {
            duration = 200
            fillAfter = true
        }
        ivRemoteAccessChevron.startAnimation(anim)
    }

    // ── ViewModel observations ─────────────────────────────────────────────────

    private fun observeViewModels() {
        mainViewModel.currentUrl.observe(viewLifecycleOwner) { url ->
            if (url.isNullOrEmpty()) {
                tvCurrentUrl.text = "No tunnel running"
                urlStatusDot.setBackgroundResource(R.drawable.status_dot_offline)
            } else {
                tvCurrentUrl.text = url
                urlStatusDot.setBackgroundResource(R.drawable.status_dot_online)
            }
        }

        mainViewModel.accessMode.observe(viewLifecycleOwner) { mode ->
            isUpdatingSwitch = true
            switchAccessMode.isChecked = mode == AccessMode.PUBLIC
            isUpdatingSwitch = false
            tvAccessMode.text = mode.name
        }

        daemonsViewModel.daemonStates.observe(viewLifecycleOwner) { states ->
            val running = states.values.count { it.status == DaemonStatus.RUNNING }
            val total   = states.size
            tvDaemonCount.text = "$running/$total"
            tvDaemonCount.setTextColor(
                resources.getColor(
                    if (running == total) R.color.status_success
                    else if (running > 0) R.color.status_warning
                    else R.color.status_danger,
                    null
                )
            )

            val errorMsg = states.entries
                .filter { it.value.status == DaemonStatus.ERROR }
                .joinToString(" · ") { it.key.displayName }
            if (errorMsg.isNotEmpty()) {
                tvDaemonError.text = "Error: $errorMsg"
                tvDaemonError.visibility = View.VISIBLE
            } else {
                tvDaemonError.visibility = View.GONE
            }
        }

        recordingViewModel.isRecording.observe(viewLifecycleOwner) { isRec ->
            tvRecPill.text = if (isRec) "● RECORDING" else "IDLE"
            tvRecPill.setTextColor(
                resources.getColor(
                    if (isRec) R.color.status_danger else R.color.text_muted,
                    null
                )
            )
        }

        recordingViewModel.storageInfo.observe(viewLifecycleOwner) { info ->
            tvRecStorage.text = info.availableFormatted + " free"
            tvRecStorageDetail.text = "${info.usedFormatted} used of ${info.totalFormatted}"

            val percent = info.usagePercent.coerceIn(0, 100)
            viewRecStorageBar.post {
                val parent = viewRecStorageBar.parent as? ViewGroup ?: return@post
                val totalWidth = parent.width
                val params = viewRecStorageBar.layoutParams
                params.width = (totalWidth * percent / 100f).toInt().coerceAtLeast(0)
                viewRecStorageBar.layoutParams = params
            }
        }
    }

    // ── Live data fetch (surveillance + vehicle) ───────────────────────────────

    private fun fetchLiveData() {
        val jwt = try { AuthManager.generateJwt() } catch (e: Exception) { null }
        fetchSurveilanceStatus(jwt)
        fetchPerfSnapshot(jwt)
    }

    private fun fetchSurveilanceStatus(jwt: String?) {
        Thread {
            try {
                val conn = URL("http://127.0.0.1:8080/api/surveillance/status")
                    .openConnection() as HttpURLConnection
                conn.connectTimeout = 3_000
                conn.readTimeout    = 3_000
                if (jwt != null) conn.setRequestProperty("Cookie", "byd_session=$jwt")

                val json = if (conn.responseCode == 200) {
                    JSONObject(conn.inputStream.bufferedReader().readText())
                } else null
                conn.disconnect()

                handler.post { if (json != null) updateSurveillanceCard(json) }
            } catch (_: Exception) { /* daemon offline */ }
        }.start()
    }

    private fun fetchPerfSnapshot(jwt: String?) {
        Thread {
            try {
                val conn = URL("http://127.0.0.1:8080/api/performance/battery?hours=1&points=5")
                    .openConnection() as HttpURLConnection
                conn.connectTimeout = 3_000
                conn.readTimeout    = 3_000
                if (jwt != null) conn.setRequestProperty("Cookie", "byd_session=$jwt")

                val json = if (conn.responseCode == 200) {
                    JSONObject(conn.inputStream.bufferedReader().readText())
                } else null
                conn.disconnect()

                handler.post { if (json != null) updateVehicleCard(json) }
            } catch (_: Exception) { /* daemon offline */ }
        }.start()
    }

    private fun updateSurveillanceCard(json: JSONObject) {
        val enabled = json.optBoolean("enabled", false)
        val active  = json.optBoolean("active", false)
        val events  = json.optInt("events_today", -1)
        val lastDet = json.optString("last_detection", null)

        tvSurvPill.text = when {
            active  -> "DETECTING"
            enabled -> "ARMED"
            else    -> "OFF"
        }
        tvSurvPill.setTextColor(
            resources.getColor(
                when {
                    active  -> R.color.status_warning
                    enabled -> R.color.status_success
                    else    -> R.color.text_muted
                },
                null
            )
        )

        tvSurvEvents.text = if (events >= 0) "$events events today" else "-- events today"
        tvSurvLastDetection.text = if (!lastDet.isNullOrEmpty()) "Last: $lastDet" else "No detections today"
    }

    private fun updateVehicleCard(json: JSONObject) {
        val current = json.optJSONObject("current") ?: return
        val v12 = current.optDouble("voltage12v", Double.NaN)
        val thermalStatus = current.optString("thermalStatus", "--")

        // Try SOC and range from the latest history point
        val history = json.optJSONArray("voltageHistory")
        var soc   = Double.NaN
        var range = Double.NaN
        if (history != null && history.length() > 0) {
            val latest = history.getJSONObject(history.length() - 1)
            soc   = latest.optDouble("soc",   Double.NaN)
            range = latest.optDouble("range", Double.NaN)
        }

        if (!v12.isNaN()) {
            tv12v.text = String.format("%.1f V", v12)
            tv12v.setTextColor(
                resources.getColor(
                    when {
                        v12 >= 12.4 -> R.color.status_success
                        v12 >= 12.0 -> R.color.status_warning
                        else        -> R.color.status_danger
                    },
                    null
                )
            )
        }

        tvThermalStatus.text = thermalStatus
        tvThermalStatus.setTextColor(
            resources.getColor(
                when (thermalStatus.uppercase()) {
                    "CRITICAL" -> R.color.status_danger
                    "WARNING"  -> R.color.status_warning
                    "NORMAL"   -> R.color.status_success
                    else       -> R.color.text_muted
                },
                null
            )
        )

        if (!soc.isNaN())   tvSoc.text   = String.format("%.0f%%", soc)
        if (!range.isNaN()) tvRange.text = String.format("%.0f km", range)
    }

    // ── Auth token UI ──────────────────────────────────────────────────────────

    private fun loadAuthState() {
        try {
            val state = AuthManager.getState()
            if (state != null) updateTokenDisplay(state.secret)
            else { AuthManager.initialize(); loadAuthState() }
        } catch (_: Exception) {
            tvDeviceToken.text = "••••••••"
        }
    }

    private fun updateTokenDisplay(secret: String) {
        tvDeviceToken.text = if (isTokenVisible) secret else "••••••••"
    }

    private fun toggleTokenVisibility() {
        isTokenVisible = !isTokenVisible
        AuthManager.getState()?.let { updateTokenDisplay(it.secret) }
        btnToggleToken.setImageResource(
            if (isTokenVisible) android.R.drawable.ic_menu_close_clear_cancel
            else android.R.drawable.ic_menu_view
        )
    }

    private fun copyTokenToClipboard() {
        val state = AuthManager.getState() ?: return
        val cb = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("Access Code", state.secret))
        Toast.makeText(requireContext(), "Access code copied", Toast.LENGTH_SHORT).show()
    }

    private fun showRegenerateConfirmation() {
        AlertDialog.Builder(requireContext())
            .setTitle("Regenerate Token")
            .setMessage("This will invalidate the current token. All active sessions will be logged out. Continue?")
            .setPositiveButton("Regenerate") { _, _ -> regenerateToken() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun regenerateToken() {
        AuthManager.regenerateToken()
        Thread {
            try {
                val client = CameraDaemonClient()
                if (client.connect()) {
                    val success = client.invalidateAuthCacheSync()
                    client.disconnect()
                    activity?.runOnUiThread {
                        val msg = if (success)
                            "New token generated. All sessions logged out."
                        else
                            "Token regenerated. Daemon may need restart to apply."
                        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                    }
                } else {
                    activity?.runOnUiThread {
                        Toast.makeText(requireContext(), "Token regenerated. Could not notify daemon.", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (_: Exception) {
                activity?.runOnUiThread {
                    Toast.makeText(requireContext(), "Token regenerated", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
        loadAuthState()
    }
}
