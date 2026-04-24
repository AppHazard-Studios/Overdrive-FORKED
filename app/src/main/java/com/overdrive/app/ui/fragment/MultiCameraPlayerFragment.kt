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
import androidx.appcompat.app.AppCompatActivity
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
 * Layout: large primary view (left ~74%) + right column with all four camera
 * thumbnails (right ~26%). Tapping any thumbnail promotes it to the large view.
 * The active camera thumbnail shows a brand-colour left border.
 *
 * Bottom bar: thin EventTimelineView (8dp) directly above the SeekBar — reads as
 * a single scrub control with event markers. Timeline duration is sourced from
 * mp.duration (not the JSON) so playhead position is always in perfect sync with
 * the scrubber.
 *
 * Controls (top/bottom bars) start hidden. Tap the large area to reveal them;
 * they auto-hide after 3 seconds of playback.
 *
 * Video loops continuously.
 */
class MultiCameraPlayerFragment : Fragment() {

    companion object {
        const val ARG_VIDEO_PATH  = "video_path"
        const val ARG_VIDEO_TITLE = "video_title"
        private const val SEEK_UPDATE_MS   = 250L
        private const val CONTROLS_HIDE_MS = 3000L
    }

    // Views
    private lateinit var glView:           MultiCameraGLView
    private lateinit var primaryTapTarget: View
    private lateinit var topBar:           View
    private lateinit var bottomControls:   View
    private lateinit var seekBar:          SeekBar
    private lateinit var eventTimeline:    EventTimelineView
    private lateinit var tvCurrentTime:    TextView
    private lateinit var tvDuration:       TextView
    private lateinit var tvTitle:          TextView
    private lateinit var tvMeta:           TextView
    private lateinit var btnPlayPause:     ImageButton
    private lateinit var btnBack:          ImageButton

    // Camera column cells and their active indicators
    private val camCells      = mutableMapOf<CameraView, FrameLayout>()
    private val camIndicators = mutableMapOf<CameraView, View>()

    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isUserSeeking = false
    private var controlsVisible = false

    private val updateRunnable = object : Runnable {
        override fun run() {
            val mp = mediaPlayer ?: return
            if (!isUserSeeking && mp.isPlaying) {
                val pos = mp.currentPosition
                seekBar.progress = pos
                tvCurrentTime.text = formatTime(pos)
                // Timeline uses same position value — guaranteed sync with seekbar
                eventTimeline.setPlayhead(pos.toLong())
            }
            handler.postDelayed(this, SEEK_UPDATE_MS)
        }
    }

    private val hideControlsRunnable = Runnable {
        if (mediaPlayer?.isPlaying == true) setControlsVisible(false)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_multi_camera_player, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        glView           = view.findViewById(R.id.glView)
        primaryTapTarget = view.findViewById(R.id.primaryTapTarget)
        topBar           = view.findViewById(R.id.topBar)
        bottomControls   = view.findViewById(R.id.bottomControls)
        seekBar          = view.findViewById(R.id.seekBar)
        eventTimeline    = view.findViewById(R.id.eventTimeline)
        tvCurrentTime    = view.findViewById(R.id.tvCurrentTime)
        tvDuration       = view.findViewById(R.id.tvDuration)
        tvTitle          = view.findViewById(R.id.tvTitle)
        tvMeta           = view.findViewById(R.id.tvMeta)
        btnPlayPause     = view.findViewById(R.id.btnPlayPause)
        btnBack          = view.findViewById(R.id.btnBack)

        camCells[CameraView.FRONT] = view.findViewById(R.id.camCellFront)
        camCells[CameraView.RIGHT] = view.findViewById(R.id.camCellRight)
        camCells[CameraView.REAR]  = view.findViewById(R.id.camCellRear)
        camCells[CameraView.LEFT]  = view.findViewById(R.id.camCellLeft)

        camIndicators[CameraView.FRONT] = view.findViewById(R.id.camActiveFront)
        camIndicators[CameraView.RIGHT] = view.findViewById(R.id.camActiveRight)
        camIndicators[CameraView.REAR]  = view.findViewById(R.id.camActiveRear)
        camIndicators[CameraView.LEFT]  = view.findViewById(R.id.camActiveLeft)

        val videoPath = arguments?.getString(ARG_VIDEO_PATH) ?: run {
            findNavController().popBackStack(); return
        }
        tvTitle.text = arguments?.getString(ARG_VIDEO_TITLE) ?: File(videoPath).name
        File(videoPath).takeIf { it.exists() }?.let { tvMeta.text = formatSize(it.length()) }

        (activity as? AppCompatActivity)?.supportActionBar?.hide()

        setupCameraColumn()
        setupControls()
        updateActiveIndicator()

        primaryTapTarget.setOnClickListener {
            if (controlsVisible) setControlsVisible(false)
            else { setControlsVisible(true); scheduleControlsHide() }
        }

        glView.onSurfaceReady = { surface ->
            activity?.runOnUiThread { startMediaPlayer(videoPath, surface) }
        }
    }

    // region Camera column

    private fun setupCameraColumn() {
        camCells.forEach { (cam, cell) ->
            cell.setOnClickListener {
                glView.setPrimaryCamera(cam)
                updateActiveIndicator()
            }
        }
    }

    private fun updateActiveIndicator() {
        val primary = glView.primaryCamera
        camIndicators.forEach { (cam, indicator) ->
            indicator.visibility = if (cam == primary) View.VISIBLE else View.GONE
        }
    }

    // endregion

    // region MediaPlayer

    private fun startMediaPlayer(path: String, surface: android.view.Surface) {
        val mp = MediaPlayer().apply {
            setDataSource(requireContext(), Uri.fromFile(File(path)))
            setSurface(surface)
            setOnPreparedListener { player ->
                val dur = player.duration
                seekBar.max = dur
                tvDuration.text = formatTime(dur)
                // Set timeline duration from the actual video, not the JSON sidecar,
                // so the playhead is always in sync with the seekbar position.
                eventTimeline.setDuration(dur.toLong())
                player.isLooping = true
                player.start()
                btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
                handler.post(updateRunnable)
                // Load event markers after we know the real duration
                loadEventMarkers(path)
            }
            setOnErrorListener { _, what, extra ->
                android.util.Log.e("MultiCamPlayer", "MediaPlayer error: what=$what extra=$extra")
                true
            }
            prepareAsync()
        }
        mediaPlayer = mp
    }

    /** Loads event detection markers from the .json sidecar into the timeline strip. */
    private fun loadEventMarkers(videoPath: String) {
        Thread {
            try {
                val jsonFile = File(videoPath.replace(".mp4", ".json"))
                if (!jsonFile.exists()) return@Thread
                val json = JSONObject(jsonFile.readText())
                val arr  = json.optJSONArray("events") ?: return@Thread

                val events = (0 until arr.length()).map { i ->
                    val ev = arr.getJSONObject(i)
                    EventTimelineView.TimelineEvent(
                        startMs    = ev.getLong("start"),
                        endMs      = ev.getLong("end"),
                        type       = ev.optString("type", "motion"),
                        confidence = ev.optDouble("maxConf", 0.0).toFloat()
                    )
                }

                activity?.runOnUiThread {
                    // setEvents() no longer sets the duration — duration was already set
                    // from mp.duration in onPrepared, so the playhead stays in sync.
                    eventTimeline.setEvents(events)
                }
            } catch (e: Exception) {
                android.util.Log.e("MultiCamPlayer", "Event markers load failed: ${e.message}")
            }
        }.start()
    }

    // endregion

    // region Controls

    private fun setupControls() {
        btnBack.setOnClickListener { findNavController().popBackStack() }

        btnPlayPause.setOnClickListener {
            val mp = mediaPlayer ?: return@setOnClickListener
            if (mp.isPlaying) {
                mp.pause()
                btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
                handler.removeCallbacks(hideControlsRunnable)
            } else {
                mp.start()
                btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
                scheduleControlsHide()
            }
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    tvCurrentTime.text = formatTime(progress)
                    // Keep timeline in sync while the user drags the seekbar
                    eventTimeline.setPlayhead(progress.toLong())
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {
                isUserSeeking = true
                handler.removeCallbacks(hideControlsRunnable)
            }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                isUserSeeking = false
                mediaPlayer?.seekTo(sb?.progress ?: 0)
                scheduleControlsHide()
            }
        })
    }

    private fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        val targetAlpha = if (visible) 1f else 0f
        listOf(topBar, bottomControls).forEach { v ->
            if (visible && v.visibility != View.VISIBLE) {
                v.alpha = 0f
                v.visibility = View.VISIBLE
            }
            v.animate().alpha(targetAlpha).setDuration(200).withEndAction {
                if (!visible) v.visibility = View.GONE
            }.start()
        }
    }

    private fun scheduleControlsHide() {
        handler.removeCallbacks(hideControlsRunnable)
        handler.postDelayed(hideControlsRunnable, CONTROLS_HIDE_MS)
    }

    // endregion

    // region Formatting

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

    // endregion

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
        (activity as? AppCompatActivity)?.supportActionBar?.show()
        handler.removeCallbacks(updateRunnable)
        handler.removeCallbacks(hideControlsRunnable)
        mediaPlayer?.apply { stop(); release() }
        mediaPlayer = null
        super.onDestroyView()
    }
}
