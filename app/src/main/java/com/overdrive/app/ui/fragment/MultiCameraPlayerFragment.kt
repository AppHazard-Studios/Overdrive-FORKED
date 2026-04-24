package com.overdrive.app.ui.fragment

import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.overdrive.app.R
import com.overdrive.app.ui.view.CameraView
import com.overdrive.app.ui.view.EventTimelineView
import com.overdrive.app.ui.view.MultiCameraGLView
import org.json.JSONObject
import java.io.File

/**
 * Multi-camera playback viewer for mosaic recordings.
 *
 * One MediaPlayer feeds one SurfaceTexture. The GL view crops four quadrants
 * from the 2560×1920 mosaic and renders them as: one large primary + three
 * small previews in a right column. Tapping a small preview swaps it to primary.
 *
 * Controls auto-hide after 3 seconds of playback, same as VideoPlayerFragment.
 */
class MultiCameraPlayerFragment : Fragment() {

    companion object {
        const val ARG_VIDEO_PATH  = "video_path"
        const val ARG_VIDEO_TITLE = "video_title"
        private const val SEEK_UPDATE_MS   = 250L
        private const val OVERLAY_HIDE_MS  = 3000L
    }

    private lateinit var glView:        MultiCameraGLView
    private lateinit var seekBar:       SeekBar
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvDuration:    TextView
    private lateinit var tvTitle:       TextView
    private lateinit var tvMeta:        TextView
    private lateinit var tvEventInfo:   TextView
    private lateinit var tvPrimaryLabel:TextView
    private lateinit var btnPlayPause:  ImageButton
    private lateinit var btnBack:       ImageButton
    private lateinit var eventTimeline: EventTimelineView
    private lateinit var topBar:        View
    private lateinit var bottomControls:View
    private lateinit var smallCam0:     FrameLayout
    private lateinit var smallCam1:     FrameLayout
    private lateinit var smallCam2:     FrameLayout
    private lateinit var tvSmallLabel0: TextView
    private lateinit var tvSmallLabel1: TextView
    private lateinit var tvSmallLabel2: TextView

    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isUserSeeking = false
    private var overlayVisible = true

    private val updateRunnable = object : Runnable {
        override fun run() {
            val mp = mediaPlayer ?: return
            if (!isUserSeeking && mp.isPlaying) {
                val pos = mp.currentPosition
                seekBar.progress = pos
                tvCurrentTime.text = formatTime(pos)
                eventTimeline.setPlayhead(pos.toLong())
            }
            handler.postDelayed(this, SEEK_UPDATE_MS)
        }
    }

    private val hideOverlayRunnable = Runnable {
        if (mediaPlayer?.isPlaying == true) setOverlayVisible(false)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_multi_camera_player, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        glView         = view.findViewById(R.id.glView)
        seekBar        = view.findViewById(R.id.seekBar)
        tvCurrentTime  = view.findViewById(R.id.tvCurrentTime)
        tvDuration     = view.findViewById(R.id.tvDuration)
        tvTitle        = view.findViewById(R.id.tvTitle)
        tvMeta         = view.findViewById(R.id.tvMeta)
        tvEventInfo    = view.findViewById(R.id.tvEventInfo)
        tvPrimaryLabel = view.findViewById(R.id.tvPrimaryLabel)
        btnPlayPause   = view.findViewById(R.id.btnPlayPause)
        btnBack        = view.findViewById(R.id.btnBack)
        eventTimeline  = view.findViewById(R.id.eventTimeline)
        topBar         = view.findViewById(R.id.topBar)
        bottomControls = view.findViewById(R.id.bottomControls)
        smallCam0      = view.findViewById(R.id.smallCam0)
        smallCam1      = view.findViewById(R.id.smallCam1)
        smallCam2      = view.findViewById(R.id.smallCam2)
        tvSmallLabel0  = view.findViewById(R.id.tvSmallLabel0)
        tvSmallLabel1  = view.findViewById(R.id.tvSmallLabel1)
        tvSmallLabel2  = view.findViewById(R.id.tvSmallLabel2)

        val videoPath = arguments?.getString(ARG_VIDEO_PATH) ?: run {
            findNavController().popBackStack(); return
        }
        tvTitle.text = arguments?.getString(ARG_VIDEO_TITLE) ?: File(videoPath).name

        val file = File(videoPath)
        if (file.exists()) tvMeta.text = formatSize(file.length())

        updateCameraLabels()
        setupSmallCamTaps()
        setupControls(videoPath)
        setupOverlayTouch()
        loadEventTimeline(videoPath)

        // Wait for GL surface, then create MediaPlayer
        glView.onSurfaceReady = { surface ->
            activity?.runOnUiThread { startMediaPlayer(videoPath, surface) }
        }
    }

    private fun startMediaPlayer(path: String, surface: android.view.Surface) {
        val mp = MediaPlayer().apply {
            setDataSource(requireContext(), Uri.fromFile(File(path)))
            setSurface(surface)
            setOnPreparedListener { player ->
                val dur = player.duration
                seekBar.max = dur
                tvDuration.text = formatTime(dur)
                eventTimeline.setPlayhead(0)
                player.isLooping = false
                player.start()
                btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
                handler.post(updateRunnable)
                scheduleOverlayHide()
            }
            setOnCompletionListener {
                btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
                handler.removeCallbacks(updateRunnable)
                handler.removeCallbacks(hideOverlayRunnable)
                setOverlayVisible(true)
            }
            setOnErrorListener { _, what, extra ->
                android.util.Log.e("MultiCamPlayer", "Error: what=$what extra=$extra")
                tvEventInfo.text = "Playback error"
                true
            }
            prepareAsync()
        }
        mediaPlayer = mp
    }

    private fun setupSmallCamTaps() {
        val cells = listOf(smallCam0, smallCam1, smallCam2)
        cells.forEachIndexed { index, cell ->
            cell.setOnClickListener {
                val cam = glView.smallCameraOrder().getOrNull(index) ?: return@setOnClickListener
                glView.setPrimaryCamera(cam)
                updateCameraLabels()
            }
        }
    }

    private fun updateCameraLabels() {
        // Primary label shows which camera is large
        tvPrimaryLabel.text = glView.smallCameraOrder()
            .let { CameraView.values().first { c -> !it.contains(c) } }
            .label.uppercase()

        // Small column labels in GL render order
        val small = glView.smallCameraOrder()
        val labelViews = listOf(tvSmallLabel0, tvSmallLabel1, tvSmallLabel2)
        labelViews.forEachIndexed { i, tv ->
            tv.text = small.getOrNull(i)?.label?.uppercase() ?: ""
        }
    }

    private fun setupControls(videoPath: String) {
        btnBack.setOnClickListener { findNavController().popBackStack() }

        btnPlayPause.setOnClickListener {
            val mp = mediaPlayer ?: return@setOnClickListener
            if (mp.isPlaying) {
                mp.pause()
                btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
                handler.removeCallbacks(hideOverlayRunnable)
            } else {
                mp.start()
                btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
                handler.post(updateRunnable)
                scheduleOverlayHide()
            }
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    tvCurrentTime.text = formatTime(progress)
                    eventTimeline.setPlayhead(progress.toLong())
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { isUserSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                isUserSeeking = false
                mediaPlayer?.seekTo(sb?.progress ?: 0)
            }
        })
    }

    private fun setupOverlayTouch() {
        glView.setOnClickListener {
            if (overlayVisible) setOverlayVisible(false)
            else { setOverlayVisible(true); scheduleOverlayHide() }
        }
    }

    private fun setOverlayVisible(visible: Boolean) {
        overlayVisible = visible
        val alpha = if (visible) 1f else 0f
        listOf(topBar, bottomControls).forEach { v ->
            if (visible) {
                v.visibility = View.VISIBLE
                v.alpha = 0f
            }
            v.animate().alpha(alpha).setDuration(250).withEndAction {
                if (!visible) v.visibility = View.GONE
            }.start()
        }
    }

    private fun scheduleOverlayHide() {
        handler.removeCallbacks(hideOverlayRunnable)
        handler.postDelayed(hideOverlayRunnable, OVERLAY_HIDE_MS)
    }

    private fun loadEventTimeline(videoPath: String) {
        Thread {
            try {
                val jsonFile = File(videoPath.replace(".mp4", ".json"))
                if (!jsonFile.exists()) {
                    activity?.runOnUiThread { tvEventInfo.text = "No events" }
                    return@Thread
                }
                val json       = JSONObject(jsonFile.readText())
                val durationMs = json.optLong("durationMs", 0)
                val arr        = json.optJSONArray("events") ?: return@Thread
                val stats      = json.optJSONObject("stats")

                val events = (0 until arr.length()).map { i ->
                    val ev = arr.getJSONObject(i)
                    EventTimelineView.TimelineEvent(
                        startMs    = ev.getLong("start"),
                        endMs      = ev.getLong("end"),
                        type       = ev.optString("type", "motion"),
                        confidence = ev.optDouble("maxConf", 0.0).toFloat()
                    )
                }

                val legend = buildString {
                    if (stats != null) {
                        listOf("person" to "🔴", "car" to "🔵", "bike" to "🟢", "motion" to "⚪")
                            .mapNotNull { (key, icon) ->
                                val n = stats.optInt(key, 0)
                                if (n > 0) "$icon$n $key" else null
                            }
                            .joinToString("  ")
                            .also { append(it) }
                    }
                }

                activity?.runOnUiThread {
                    eventTimeline.setEvents(events, durationMs)
                    if (legend.isNotEmpty()) tvEventInfo.text = legend
                }
            } catch (e: Exception) {
                android.util.Log.e("MultiCamPlayer", "Timeline load failed: ${e.message}")
            }
        }.start()
    }

    private fun formatTime(ms: Int): String {
        val s = ms / 1000
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000     -> "%.1f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000         -> "%.1f KB".format(bytes / 1_000.0)
        else                   -> "$bytes B"
    }

    override fun onPause() {
        super.onPause()
        glView.onPause()
        handler.removeCallbacks(updateRunnable)
        mediaPlayer?.takeIf { it.isPlaying }?.pause()
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
    }

    override fun onDestroyView() {
        handler.removeCallbacks(updateRunnable)
        handler.removeCallbacks(hideOverlayRunnable)
        mediaPlayer?.apply { stop(); release() }
        mediaPlayer = null
        super.onDestroyView()
    }
}
