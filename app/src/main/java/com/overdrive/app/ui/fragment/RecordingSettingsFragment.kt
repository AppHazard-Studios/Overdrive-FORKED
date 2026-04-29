package com.overdrive.app.ui.fragment

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.overdrive.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class RecordingSettingsFragment : Fragment() {

    private lateinit var toggleMode: MaterialButtonToggleGroup
    private lateinit var toggleDashcamQuality: MaterialButtonToggleGroup
    private lateinit var tvQualityHint: TextView
    private lateinit var toggleStorageType: MaterialButtonToggleGroup
    private lateinit var sliderLimit: Slider
    private lateinit var tvLimitValue: TextView
    private lateinit var tvStorageUsed: TextView
    private lateinit var tvStorageLimit: TextView
    private lateinit var storageBarFill: View
    private lateinit var tvStoragePath: TextView
    private lateinit var tvSdCardStatus: TextView
    private lateinit var switchTelemetry: SwitchMaterial
    private lateinit var cardProximityGuard: MaterialCardView
    private lateinit var toggleTriggerLevel: MaterialButtonToggleGroup
    private lateinit var sliderPreRecord: Slider
    private lateinit var tvPreRecordValue: TextView
    private lateinit var sliderPostRecord: Slider
    private lateinit var tvPostRecordValue: TextView
    private lateinit var btnApply: MaterialButton

    private var isInitializing = false
    private var hasUnsavedChanges = false
    private var currentStorageType = "INTERNAL"
    private var sdCardAvailable = false
    private var maxLimitMb = 100000
    private var maxLimitMbSdCard = 100000

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.fragment_recording_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initViews(view)
        setupListeners()
        applyDefaultsFromPrefs()
        loadAllSettings()
    }

    private fun prefs() = requireContext().getSharedPreferences("recording_settings", Context.MODE_PRIVATE)

    private fun applyDefaultsFromPrefs() {
        val p = prefs()
        isInitializing = true

        val mode = p.getString("mode", "NONE") ?: "NONE"
        toggleMode.check(when (mode) {
            "CONTINUOUS" -> R.id.btnModeContinuous
            "ACC_ONLY" -> R.id.btnModeAccOnly
            "PROXIMITY_GUARD" -> R.id.btnModeProximity
            else -> R.id.btnModeNone
        })
        cardProximityGuard.visibility = if (mode == "PROXIMITY_GUARD") View.VISIBLE else View.GONE

        val dashcamQuality = p.getString("dashcam_quality", "BALANCED") ?: "BALANCED"
        applyDashcamQualityToggle(dashcamQuality)

        currentStorageType = p.getString("storage_type", "INTERNAL") ?: "INTERNAL"
        toggleStorageType.check(if (currentStorageType == "SD_CARD") R.id.btnStorageSdCard else R.id.btnStorageInternal)

        val limitMb = p.getInt("limit_mb", 500)
        val sliderVal = limitMb.toFloat().coerceIn(sliderLimit.valueFrom, sliderLimit.valueTo)
        sliderLimit.value = sliderVal
        tvLimitValue.text = if (limitMb >= 1000) "${limitMb / 1000} GB" else "$limitMb MB"
        tvStorageLimit.text = "$limitMb MB limit"

        switchTelemetry.isChecked = p.getBoolean("telemetry", false)

        val triggerLevel = p.getString("trigger_level", "RED") ?: "RED"
        toggleTriggerLevel.check(when (triggerLevel) {
            "YELLOW" -> R.id.btnTriggerYellow
            "ORANGE" -> R.id.btnTriggerOrange
            else -> R.id.btnTriggerRed
        })
        val preRecord = p.getInt("pre_record", 5).toFloat().coerceIn(sliderPreRecord.valueFrom, sliderPreRecord.valueTo)
        val postRecord = p.getInt("post_record", 10).toFloat().coerceIn(sliderPostRecord.valueFrom, sliderPostRecord.valueTo)
        sliderPreRecord.value = preRecord
        tvPreRecordValue.text = "${preRecord.toInt()}s"
        sliderPostRecord.value = postRecord
        tvPostRecordValue.text = "${postRecord.toInt()}s"

        isInitializing = false
        hasUnsavedChanges = false
        btnApply.isEnabled = false
    }

    private fun saveToPrefs(dashcamQuality: String,
                            mode: String, triggerLevel: String, preRecord: Int, postRecord: Int,
                            limitMb: Int, storageType: String, telemetryEnabled: Boolean) {
        prefs().edit()
            .putString("mode", mode)
            .putString("dashcam_quality", dashcamQuality)
            .putString("storage_type", storageType)
            .putInt("limit_mb", limitMb)
            .putBoolean("telemetry", telemetryEnabled)
            .putString("trigger_level", triggerLevel)
            .putInt("pre_record", preRecord)
            .putInt("post_record", postRecord)
            .apply()
    }

    private fun initViews(view: View) {
        toggleMode = view.findViewById(R.id.toggleMode)
        toggleDashcamQuality = view.findViewById(R.id.toggleDashcamQuality)
        tvQualityHint = view.findViewById(R.id.tvQualityHint)
        toggleStorageType = view.findViewById(R.id.toggleStorageType)
        sliderLimit = view.findViewById(R.id.sliderLimit)
        tvLimitValue = view.findViewById(R.id.tvLimitValue)
        tvStorageUsed = view.findViewById(R.id.tvStorageUsed)
        tvStorageLimit = view.findViewById(R.id.tvStorageLimit)
        storageBarFill = view.findViewById(R.id.storageBarFill)
        tvStoragePath = view.findViewById(R.id.tvStoragePath)
        tvSdCardStatus = view.findViewById(R.id.tvSdCardStatus)
        switchTelemetry = view.findViewById(R.id.switchTelemetry)
        cardProximityGuard = view.findViewById(R.id.cardProximityGuard)
        toggleTriggerLevel = view.findViewById(R.id.toggleTriggerLevel)
        sliderPreRecord = view.findViewById(R.id.sliderPreRecord)
        tvPreRecordValue = view.findViewById(R.id.tvPreRecordValue)
        sliderPostRecord = view.findViewById(R.id.sliderPostRecord)
        tvPostRecordValue = view.findViewById(R.id.tvPostRecordValue)
        btnApply = view.findViewById(R.id.btnApply)
    }

    private fun setupListeners() {
        toggleMode.addOnButtonCheckedListener { _, _, isChecked ->
            if (isChecked && !isInitializing) {
                cardProximityGuard.visibility =
                    if (toggleMode.checkedButtonId == R.id.btnModeProximity) View.VISIBLE else View.GONE
                markChanged()
            }
        }

        toggleDashcamQuality.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked && !isInitializing) {
                val hint = when (checkedId) {
                    R.id.btnQualityEfficient -> "Efficient — 15fps · 4 Mbps"
                    R.id.btnQualityQuality   -> "Quality — 25fps · 12 Mbps"
                    else                     -> "Balanced — 20fps · 8 Mbps"
                }
                tvQualityHint.text = hint
                markChanged()
            }
        }
        toggleStorageType.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked && !isInitializing) {
                val newType = if (checkedId == R.id.btnStorageSdCard) "SD_CARD" else "INTERNAL"
                if (newType == "SD_CARD" && !sdCardAvailable) {
                    Toast.makeText(context, "SD Card not available", Toast.LENGTH_SHORT).show()
                    toggleStorageType.check(R.id.btnStorageInternal)
                    return@addOnButtonCheckedListener
                }
                currentStorageType = newType
                updateLimitSliderMax()
                markChanged()
            }
        }

        sliderLimit.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !isInitializing) {
                val mb = value.toInt()
                tvLimitValue.text = if (mb >= 1000) "${mb / 1000} GB" else "$mb MB"
                tvStorageLimit.text = "$mb MB limit"
                markChanged()
            }
        }

        switchTelemetry.setOnCheckedChangeListener { _, _ -> if (!isInitializing) markChanged() }

        toggleTriggerLevel.addOnButtonCheckedListener { _, _, isChecked ->
            if (isChecked && !isInitializing) markChanged()
        }

        sliderPreRecord.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !isInitializing) {
                tvPreRecordValue.text = "${value.toInt()}s"
                markChanged()
            }
        }

        sliderPostRecord.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !isInitializing) {
                tvPostRecordValue.text = "${value.toInt()}s"
                markChanged()
            }
        }

        btnApply.setOnClickListener { applySettings() }
    }

    private fun loadAllSettings() {
        viewLifecycleOwner.lifecycleScope.launch {
            launch(Dispatchers.IO) { fetchAndApplyQualityAndMode() }
            launch(Dispatchers.IO) { fetchAndApplyStorage() }
            launch(Dispatchers.IO) { fetchAndApplyStorageStats() }
            launch(Dispatchers.IO) { fetchAndApplyTelemetry() }
        }
    }

    private suspend fun fetchAndApplyQualityAndMode() {
        // Fetch each endpoint independently — a timeout on one must not blank the whole page.
        val qualityJson = runCatching { getJson("/api/settings/quality") }.getOrNull()
        val modeJson = runCatching { getJson("/api/recording/mode") }.getOrNull()
        val unifiedJson = runCatching { getJson("/api/settings/unified") }.getOrNull()

        if (qualityJson == null && modeJson == null && unifiedJson == null) {
            android.util.Log.w("RecordingSettings", "All quality/mode endpoints unreachable — using prefs")
            return
        }

        withContext(Dispatchers.Main) {
            isInitializing = true

            if (qualityJson != null) {
                val quality = qualityJson.optString("recordingQuality", "BALANCED")
                val dashcamQuality = when (quality.uppercase()) {
                    "EFFICIENT", "LOW", "REDUCED" -> "EFFICIENT"
                    "QUALITY" -> "QUALITY"
                    else -> "BALANCED"
                }
                applyDashcamQualityToggle(dashcamQuality)
                prefs().edit().putString("dashcam_quality", dashcamQuality).apply()
            }

            val daemonMode = modeJson?.optString("mode", "NONE") ?: "NONE"
            val unifiedConfig = unifiedJson?.optJSONObject("config")
            val modeFromUnified = unifiedConfig?.optJSONObject("recording")?.optString("mode")
                ?.takeIf { it.isNotEmpty() }
            val mode = modeFromUnified ?: daemonMode

            toggleMode.check(when (mode) {
                "CONTINUOUS" -> R.id.btnModeContinuous
                "ACC_ONLY" -> R.id.btnModeAccOnly
                "PROXIMITY_GUARD" -> R.id.btnModeProximity
                else -> R.id.btnModeNone
            })
            cardProximityGuard.visibility = if (mode == "PROXIMITY_GUARD") View.VISIBLE else View.GONE

            val proxGuard = unifiedConfig?.optJSONObject("proximityGuard")
            if (proxGuard != null) {
                val triggerLevel = proxGuard.optString("triggerLevel", "RED")
                toggleTriggerLevel.check(when (triggerLevel) {
                    "YELLOW" -> R.id.btnTriggerYellow
                    "ORANGE" -> R.id.btnTriggerOrange
                    else -> R.id.btnTriggerRed
                })
                val preRecord = proxGuard.optInt("preRecordSeconds", 5)
                    .toFloat().coerceIn(sliderPreRecord.valueFrom, sliderPreRecord.valueTo)
                val postRecord = proxGuard.optInt("postRecordSeconds", 10)
                    .toFloat().coerceIn(sliderPostRecord.valueFrom, sliderPostRecord.valueTo)
                sliderPreRecord.value = preRecord
                tvPreRecordValue.text = "${preRecord.toInt()}s"
                sliderPostRecord.value = postRecord
                tvPostRecordValue.text = "${postRecord.toInt()}s"

                prefs().edit()
                    .putString("trigger_level", triggerLevel)
                    .putInt("pre_record", preRecord.toInt())
                    .putInt("post_record", postRecord.toInt())
                    .apply()
            }

            prefs().edit().putString("mode", mode).apply()

            isInitializing = false
            hasUnsavedChanges = false
            btnApply.isEnabled = false

            // If unified config has a saved mode but daemon is at NONE (just started),
            // re-apply the mode so the daemon actually starts in the correct mode.
            if (mode != "NONE" && daemonMode == "NONE" && modeFromUnified != null) {
                launch(Dispatchers.IO) {
                    runCatching {
                        postJson("/api/recording/mode", JSONObject().apply { put("mode", mode) })
                    }
                }
            }
        }
    }

    private suspend fun fetchAndApplyStorage() {
        try {
            val json = getJson("/api/settings/storage")

            withContext(Dispatchers.Main) {
                isInitializing = true

                val limitMb = json.optInt("recordingsLimitMb", 500)
                val storageType = json.optString("recordingsStorageType", "INTERNAL")
                sdCardAvailable = json.optBoolean("sdCardAvailable", false)
                maxLimitMb = json.optInt("maxLimitMb", 100000)
                maxLimitMbSdCard = json.optInt("maxLimitMbSdCard", 100000)
                val recordingsPath = json.optString("recordingsPath", "")
                val sdCardFree = json.optLong("sdCardFreeSpace", 0)
                val sdCardTotal = json.optLong("sdCardTotalSpace", 0)

                currentStorageType = storageType
                view?.findViewById<MaterialButton>(R.id.btnStorageSdCard)?.isEnabled = sdCardAvailable
                toggleStorageType.check(
                    if (storageType == "SD_CARD") R.id.btnStorageSdCard else R.id.btnStorageInternal
                )

                if (sdCardAvailable) {
                    val freeGb = sdCardFree / 1_000_000_000.0
                    val totalGb = sdCardTotal / 1_000_000_000.0
                    tvSdCardStatus.text = "SD: ${String.format("%.1f", freeGb)} GB free / ${String.format("%.1f", totalGb)} GB"
                    tvSdCardStatus.setTextColor(resources.getColor(R.color.status_running, null))
                } else {
                    tvSdCardStatus.text = "SD Card: Not detected"
                    tvSdCardStatus.setTextColor(resources.getColor(R.color.text_muted, null))
                }

                updateLimitSliderMax()
                sliderLimit.value = limitMb.toFloat().coerceIn(sliderLimit.valueFrom, sliderLimit.valueTo)
                tvLimitValue.text = if (limitMb >= 1000) "${limitMb / 1000} GB" else "$limitMb MB"
                tvStorageLimit.text = "$limitMb MB limit"

                if (recordingsPath.isNotEmpty()) {
                    val shortPath = recordingsPath.replace("/storage/emulated/0/", "")
                    tvStoragePath.text = "Saved to $shortPath"
                }

                isInitializing = false

                // Keep prefs in sync with what daemon reported
                prefs().edit()
                    .putInt("limit_mb", limitMb)
                    .putString("storage_type", storageType)
                    .apply()
            }
        } catch (e: Exception) {
            android.util.Log.w("RecordingSettings", "Failed to load storage: ${e.message}")
        }
    }

    private suspend fun fetchAndApplyStorageStats() {
        try {
            val json = getJson("/api/recordings/stats")

            withContext(Dispatchers.Main) {
                val usedFormatted = json.optString("normalSizeFormatted", "0 MB")
                val usedBytes = json.optLong("normalSize", 0)
                tvStorageUsed.text = "$usedFormatted used"

                val limitMb = sliderLimit.value.toLong()
                val limitBytes = limitMb * 1024 * 1024
                val pct = if (limitBytes > 0) (usedBytes.toFloat() / limitBytes * 100).coerceIn(0f, 100f) else 0f
                storageBarFill.post {
                    val parent = storageBarFill.parent as? ViewGroup ?: return@post
                    val params = storageBarFill.layoutParams
                    params.width = (parent.width * pct / 100).toInt()
                    storageBarFill.layoutParams = params
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("RecordingSettings", "Failed to load storage stats: ${e.message}")
        }
    }

    private suspend fun fetchAndApplyTelemetry() {
        try {
            val json = getJson("/api/settings/telemetry-overlay")

            withContext(Dispatchers.Main) {
                isInitializing = true
                val enabled = json.optBoolean("enabled", false)
                switchTelemetry.isChecked = enabled
                isInitializing = false
                prefs().edit().putBoolean("telemetry", enabled).apply()
            }
        } catch (e: Exception) {
            android.util.Log.w("RecordingSettings", "Failed to load telemetry: ${e.message}")
        }
    }

    private fun updateLimitSliderMax() {
        val maxLimit = (if (currentStorageType == "SD_CARD") maxLimitMbSdCard else maxLimitMb)
            .toFloat().coerceAtLeast(1000f)
        if (sliderLimit.valueTo != maxLimit) {
            sliderLimit.valueTo = maxLimit
        }
        if (sliderLimit.value > sliderLimit.valueTo) {
            sliderLimit.value = sliderLimit.valueTo
        }
    }

    private fun markChanged() {
        hasUnsavedChanges = true
        btnApply.isEnabled = true
    }

    private fun applySettings() {
        btnApply.isEnabled = false

        val dashcamQuality = when (toggleDashcamQuality.checkedButtonId) {
            R.id.btnQualityEfficient -> "EFFICIENT"
            R.id.btnQualityQuality   -> "QUALITY"
            else                     -> "BALANCED"
        }
        val mode = when (toggleMode.checkedButtonId) {
            R.id.btnModeContinuous -> "CONTINUOUS"
            R.id.btnModeAccOnly -> "ACC_ONLY"
            R.id.btnModeProximity -> "PROXIMITY_GUARD"
            else -> "NONE"
        }
        val triggerLevel = when (toggleTriggerLevel.checkedButtonId) {
            R.id.btnTriggerYellow -> "YELLOW"
            R.id.btnTriggerOrange -> "ORANGE"
            else -> "RED"
        }
        val preRecord = sliderPreRecord.value.toInt()
        val postRecord = sliderPostRecord.value.toInt()
        val limitMb = sliderLimit.value.toInt()
        val telemetryEnabled = switchTelemetry.isChecked

        saveToPrefs(dashcamQuality, mode, triggerLevel, preRecord, postRecord,
            limitMb, currentStorageType, telemetryEnabled)

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            var daemonReachable = false
            var success = true

            runCatching {
                postJson(
                    "/api/settings/quality", JSONObject().apply {
                        put("recordingQuality", dashcamQuality)
                        put("recordingCodec", "H264")
                    }
                )
                daemonReachable = true
            }.onFailure {
                android.util.Log.w("RecordingSettings", "Failed to save quality: ${it.message}")
                success = false
            }

            runCatching {
                postJson("/api/recording/mode", JSONObject().apply { put("mode", mode) })
            }.onFailure {
                android.util.Log.w("RecordingSettings", "Failed to save mode: ${it.message}")
                success = false
            }

            runCatching {
                postJson(
                    "/api/settings/unified", JSONObject().apply {
                        put("section", "recording")
                        put(
                            "data", JSONObject().apply {
                                put("dashcamQuality", dashcamQuality)
                                put("codec", "H264")
                                put("mode", mode)
                            }
                        )
                    }
                )
            }.onFailure {
                android.util.Log.w("RecordingSettings", "Failed to save unified recording: ${it.message}")
            }

            runCatching {
                postJson(
                    "/api/settings/unified", JSONObject().apply {
                        put("section", "proximityGuard")
                        put(
                            "data", JSONObject().apply {
                                put("triggerLevel", triggerLevel)
                                put("preRecordSeconds", preRecord)
                                put("postRecordSeconds", postRecord)
                            }
                        )
                    }
                )
            }.onFailure {
                android.util.Log.w("RecordingSettings", "Failed to save proximity guard: ${it.message}")
            }

            runCatching {
                postJson(
                    "/api/settings/storage", JSONObject().apply {
                        put("recordingsLimitMb", limitMb)
                        put("recordingsStorageType", currentStorageType)
                    }
                )
            }.onFailure {
                android.util.Log.w("RecordingSettings", "Failed to save storage: ${it.message}")
                success = false
            }

            runCatching {
                postJson(
                    "/api/settings/telemetry-overlay",
                    JSONObject().apply { put("enabled", telemetryEnabled) }
                )
            }.onFailure {
                android.util.Log.w("RecordingSettings", "Failed to save telemetry: ${it.message}")
            }

            withContext(Dispatchers.Main) {
                hasUnsavedChanges = false
                val msg = when {
                    !daemonReachable -> "Settings saved. Will apply when daemon connects."
                    !success -> "Some settings may not have saved"
                    else -> "Settings applied"
                }
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                launch(Dispatchers.IO) { fetchAndApplyStorageStats() }
            }
        }
    }

    private fun applyDashcamQualityToggle(tier: String) {
        val btnId = when (tier.uppercase()) {
            "EFFICIENT" -> R.id.btnQualityEfficient
            "QUALITY"   -> R.id.btnQualityQuality
            else        -> R.id.btnQualityBalanced
        }
        toggleDashcamQuality.check(btnId)
        tvQualityHint.text = when (btnId) {
            R.id.btnQualityEfficient -> "Efficient — 15fps · 4 Mbps"
            R.id.btnQualityQuality   -> "Quality — 25fps · 12 Mbps"
            else                     -> "Balanced — 20fps · 8 Mbps"
        }
    }

    private fun getJson(path: String): JSONObject {
        val conn = URL("http://127.0.0.1:8080$path").openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        return try {
            JSONObject(conn.inputStream.bufferedReader().readText())
        } finally {
            conn.disconnect()
        }
    }

    private fun postJson(path: String, body: JSONObject) {
        val conn = URL("http://127.0.0.1:8080$path").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            (if (code >= 400) conn.errorStream else conn.inputStream)?.close()
        } finally {
            conn.disconnect()
        }
    }
}
