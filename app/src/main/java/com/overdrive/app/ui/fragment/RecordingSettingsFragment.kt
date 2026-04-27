package com.overdrive.app.ui.fragment

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
    private lateinit var toggleQuality: MaterialButtonToggleGroup
    private lateinit var toggleBitrate: MaterialButtonToggleGroup
    private lateinit var toggleCodec: MaterialButtonToggleGroup
    private lateinit var toggleStreaming: MaterialButtonToggleGroup
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
        loadAllSettings()
    }

    private fun initViews(view: View) {
        toggleMode = view.findViewById(R.id.toggleMode)
        toggleQuality = view.findViewById(R.id.toggleQuality)
        toggleBitrate = view.findViewById(R.id.toggleBitrate)
        toggleCodec = view.findViewById(R.id.toggleCodec)
        toggleStreaming = view.findViewById(R.id.toggleStreaming)
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

        listOf(toggleQuality, toggleBitrate, toggleCodec, toggleStreaming).forEach { group ->
            group.addOnButtonCheckedListener { _, _, isChecked -> if (isChecked && !isInitializing) markChanged() }
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
        try {
            val qualityJson = getJson("/api/settings/quality")
            val modeJson = getJson("/api/recording/mode")
            val unifiedJson = getJson("/api/settings/unified")

            withContext(Dispatchers.Main) {
                isInitializing = true

                val quality = qualityJson.optString("recordingQuality", "NORMAL")
                val bitrate = qualityJson.optString("recordingBitrate", "MEDIUM")
                val codec = qualityJson.optString("recordingCodec", "H264")
                val streaming = qualityJson.optString("streamingQuality", "LQ")

                toggleQuality.check(if (quality == "HIGH") R.id.btnQualityHigh else R.id.btnQualityNormal)
                toggleBitrate.check(
                    when (bitrate) {
                        "LOW" -> R.id.btnBitrateLow
                        "HIGH" -> R.id.btnBitrateHigh
                        else -> R.id.btnBitrateMedium
                    }
                )
                toggleCodec.check(if (codec == "H265") R.id.btnCodecH265 else R.id.btnCodecH264)
                toggleStreaming.check(
                    when (streaming) {
                        "MQ" -> R.id.btnStreamingMq
                        "HQ" -> R.id.btnStreamingHq
                        else -> R.id.btnStreamingLq
                    }
                )

                val unifiedConfig = unifiedJson.optJSONObject("config")
                val modeFromUnified = unifiedConfig?.optJSONObject("recording")?.optString("mode")
                val mode = modeFromUnified?.takeIf { it.isNotEmpty() }
                    ?: modeJson.optString("mode", "NONE")

                toggleMode.check(
                    when (mode) {
                        "CONTINUOUS" -> R.id.btnModeContinuous
                        "ACC_ONLY" -> R.id.btnModeAccOnly
                        "PROXIMITY_GUARD" -> R.id.btnModeProximity
                        else -> R.id.btnModeNone
                    }
                )
                cardProximityGuard.visibility =
                    if (mode == "PROXIMITY_GUARD") View.VISIBLE else View.GONE

                val proxGuard = unifiedConfig?.optJSONObject("proximityGuard")
                if (proxGuard != null) {
                    val triggerLevel = proxGuard.optString("triggerLevel", "RED")
                    toggleTriggerLevel.check(
                        when (triggerLevel) {
                            "YELLOW" -> R.id.btnTriggerYellow
                            "ORANGE" -> R.id.btnTriggerOrange
                            else -> R.id.btnTriggerRed
                        }
                    )
                    val preRecord = proxGuard.optInt("preRecordSeconds", 5)
                        .toFloat().coerceIn(sliderPreRecord.valueFrom, sliderPreRecord.valueTo)
                    val postRecord = proxGuard.optInt("postRecordSeconds", 10)
                        .toFloat().coerceIn(sliderPostRecord.valueFrom, sliderPostRecord.valueTo)
                    sliderPreRecord.value = preRecord
                    tvPreRecordValue.text = "${preRecord.toInt()}s"
                    sliderPostRecord.value = postRecord
                    tvPostRecordValue.text = "${postRecord.toInt()}s"
                }

                isInitializing = false
                hasUnsavedChanges = false
                btnApply.isEnabled = false
            }
        } catch (e: Exception) {
            android.util.Log.w("RecordingSettings", "Failed to load quality/mode: ${e.message}")
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
                switchTelemetry.isChecked = json.optBoolean("enabled", false)
                isInitializing = false
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

        val quality = if (toggleQuality.checkedButtonId == R.id.btnQualityHigh) "HIGH" else "NORMAL"
        val bitrate = when (toggleBitrate.checkedButtonId) {
            R.id.btnBitrateLow -> "LOW"
            R.id.btnBitrateHigh -> "HIGH"
            else -> "MEDIUM"
        }
        val codec = if (toggleCodec.checkedButtonId == R.id.btnCodecH265) "H265" else "H264"
        val streaming = when (toggleStreaming.checkedButtonId) {
            R.id.btnStreamingMq -> "MQ"
            R.id.btnStreamingHq -> "HQ"
            else -> "LQ"
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

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            var success = true

            runCatching {
                postJson(
                    "/api/settings/quality", JSONObject().apply {
                        put("recordingQuality", quality)
                        put("streamingQuality", streaming)
                        put("recordingBitrate", bitrate)
                        put("recordingCodec", codec)
                    }
                )
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
                                put("bitrate", bitrate)
                                put("codec", codec)
                                put("quality", quality)
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
                    !success -> "Some settings may not have saved"
                    codec == "H265" -> "Applied — H.265 takes effect on next recording"
                    else -> "Settings applied"
                }
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                launch(Dispatchers.IO) { fetchAndApplyStorageStats() }
            }
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
            conn.inputStream.close()
        } finally {
            conn.disconnect()
        }
    }
}
