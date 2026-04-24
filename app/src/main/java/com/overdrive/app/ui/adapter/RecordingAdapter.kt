package com.overdrive.app.ui.adapter

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.overdrive.app.R
import com.overdrive.app.ui.model.RecordingFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class RecordingAdapter(
    private val onPlay: (RecordingFile, Int) -> Unit,
    private val onDelete: (RecordingFile) -> Unit
) : ListAdapter<RecordingFile, RecordingAdapter.RecordingViewHolder>(RecordingDiffCallback()) {

    private val thumbnailCache    = mutableMapOf<String, Bitmap?>()
    private val eventSummaryCache = mutableMapOf<String, String>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecordingViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recording, parent, false)
        return RecordingViewHolder(view)
    }

    override fun onBindViewHolder(holder: RecordingViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class RecordingViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivThumbnail:    ImageView   = itemView.findViewById(R.id.ivThumbnail)
        private val tvTypeBadge:    TextView    = itemView.findViewById(R.id.tvTypeBadge)
        private val tvRecordingTime: TextView   = itemView.findViewById(R.id.tvRecordingTime)
        private val tvDuration:     TextView    = itemView.findViewById(R.id.tvDuration)
        private val tvSize:         TextView    = itemView.findViewById(R.id.tvSize)
        private val tvEvents:       TextView    = itemView.findViewById(R.id.tvEvents)
        private val btnDelete:      ImageButton = itemView.findViewById(R.id.btnDelete)

        fun bind(recording: RecordingFile) {
            // Type badge label + colour
            val (label, colorRes) = when (recording.type) {
                RecordingFile.RecordingType.SENTRY    -> "SENTRY" to R.color.brand_primary
                RecordingFile.RecordingType.PROXIMITY -> "PROX"   to R.color.status_warning
                RecordingFile.RecordingType.NORMAL    -> "REC"    to R.color.accent_purple
            }
            tvTypeBadge.text = label
            tvTypeBadge.backgroundTintList = ColorStateList.valueOf(
                itemView.context.getColor(colorRes)
            )

            tvRecordingTime.text = recording.formattedTime
            tvDuration.text = if (recording.durationMs > 0) recording.formattedDuration else "--:--"
            tvSize.text = recording.formattedSize

            // Show cached event summary immediately; load async if not cached yet
            applyEventSummary(eventSummaryCache[recording.path])

            // Show cached thumbnail immediately; kick off async load if not cached
            if (thumbnailCache.containsKey(recording.path)) {
                val bmp = thumbnailCache[recording.path]
                if (bmp != null) ivThumbnail.setImageBitmap(bmp)
                else             ivThumbnail.setImageResource(R.color.surface_variant)
            } else {
                ivThumbnail.setImageResource(R.color.surface_variant)
                loadAsync(recording)
            }

            // Whole card is tappable to play; delete button handles itself
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onPlay(getItem(pos), pos)
            }
            btnDelete.setOnClickListener { onDelete(recording) }
        }

        private fun applyEventSummary(summary: String?) {
            if (!summary.isNullOrEmpty()) {
                tvEvents.text = summary
                tvEvents.visibility = View.VISIBLE
            } else {
                tvEvents.visibility = View.GONE
            }
        }

        private fun loadAsync(recording: RecordingFile) {
            CoroutineScope(Dispatchers.IO).launch {
                // 1. Parse JSON sidecar (fast, small) to get first event timestamp + stats
                var firstEventUs = 1_000_000L  // default: 1 s in microseconds
                var summary = ""

                val jsonFile = File(recording.path.replace(".mp4", ".json"))
                if (jsonFile.exists()) {
                    try {
                        val json   = JSONObject(jsonFile.readText())
                        val events = json.optJSONArray("events")
                        if (events != null && events.length() > 0) {
                            val firstMs = events.getJSONObject(0).optLong("start", 1000)
                            firstEventUs = (firstMs * 1000L).coerceAtLeast(0L)
                        }
                        val stats = json.optJSONObject("stats")
                        if (stats != null) {
                            val parts   = mutableListOf<String>()
                            val persons = stats.optInt("person", 0)
                            val cars    = stats.optInt("car",    0)
                            val bikes   = stats.optInt("bike",   0)
                            val motion  = stats.optInt("motion", 0)
                            if (persons > 0) parts.add("$persons person${if (persons > 1) "s" else ""}")
                            if (cars    > 0) parts.add("$cars car${if (cars    > 1) "s" else ""}")
                            if (bikes   > 0) parts.add("$bikes bike${if (bikes  > 1) "s" else ""}")
                            // Only show motion if nothing more specific was detected
                            if (parts.isEmpty() && motion > 0) parts.add("motion")
                            summary = parts.joinToString("  ·  ")
                        }
                    } catch (_: Exception) { }
                }

                // 2. Extract thumbnail at the first event's timestamp
                val thumbnail = try {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(recording.path)
                    val frame = retriever.getFrameAtTime(
                        firstEventUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                    )
                    retriever.release()
                    frame
                } catch (_: Exception) { null }

                thumbnailCache[recording.path]    = thumbnail
                eventSummaryCache[recording.path] = summary

                withContext(Dispatchers.Main) {
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION && getItem(pos).path == recording.path) {
                        if (thumbnail != null) ivThumbnail.setImageBitmap(thumbnail)
                        applyEventSummary(summary)
                    }
                }
            }
        }
    }

    fun clearCache() {
        thumbnailCache.clear()
        eventSummaryCache.clear()
    }

    private class RecordingDiffCallback : DiffUtil.ItemCallback<RecordingFile>() {
        override fun areItemsTheSame(oldItem: RecordingFile, newItem: RecordingFile) =
            oldItem.path == newItem.path
        override fun areContentsTheSame(oldItem: RecordingFile, newItem: RecordingFile) =
            oldItem == newItem
    }
}
