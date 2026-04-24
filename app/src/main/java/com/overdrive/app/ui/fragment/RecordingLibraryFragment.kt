package com.overdrive.app.ui.fragment

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.overdrive.app.R
import com.overdrive.app.ui.adapter.RecordingAdapter
import com.overdrive.app.ui.model.RecordingFile
import com.overdrive.app.ui.util.RecordingScanner
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors

class RecordingLibraryFragment : Fragment() {

    companion object {
        private const val TAG = "RecordingLibrary"
    }

    private lateinit var btnPrevDay:       ImageButton
    private lateinit var btnNextDay:       ImageButton
    private lateinit var tvSelectedDate:   TextView
    private lateinit var btnFilterAll:     TextView
    private lateinit var btnFilterNormal:  TextView
    private lateinit var btnFilterSentry:  TextView
    private lateinit var btnFilterProx:    TextView
    private lateinit var recyclerRecordings: RecyclerView
    private lateinit var tvEmptyState:     TextView
    private var emptyStateContainer: LinearLayout? = null

    private lateinit var recordingAdapter: RecordingAdapter

    private val calendar = Calendar.getInstance()
    private val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

    private var currentFilter = RecordingFilter.ALL
    private var scanExecutor = Executors.newSingleThreadExecutor()

    enum class RecordingFilter { ALL, NORMAL, SENTRY, PROXIMITY }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (scanExecutor.isShutdown) scanExecutor = Executors.newSingleThreadExecutor()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_recording_library, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (scanExecutor.isShutdown || scanExecutor.isTerminated) {
            scanExecutor = Executors.newSingleThreadExecutor()
        }

        initViews(view)
        setupRecordingsList()
        setupDateNavigation()
        setupFilterButtons()
        ensureDirectories()
        RecordingScanner.invalidateCache()
        updateDateDisplay()
        loadRecordingsForSelectedDate()
    }

    private fun initViews(view: View) {
        btnPrevDay        = view.findViewById(R.id.btnPrevDay)
        btnNextDay        = view.findViewById(R.id.btnNextDay)
        tvSelectedDate    = view.findViewById(R.id.tvSelectedDate)
        btnFilterAll      = view.findViewById(R.id.btnFilterAll)
        btnFilterNormal   = view.findViewById(R.id.btnFilterNormal)
        btnFilterSentry   = view.findViewById(R.id.btnFilterSentry)
        btnFilterProx     = view.findViewById(R.id.btnFilterProximity)
        recyclerRecordings = view.findViewById(R.id.recyclerRecordings)
        tvEmptyState      = view.findViewById(R.id.tvEmptyState)
        emptyStateContainer = view.findViewById(R.id.emptyStateContainer)
    }

    private fun setupDateNavigation() {
        btnPrevDay.setOnClickListener {
            calendar.add(Calendar.DAY_OF_MONTH, -1)
            updateDateDisplay()
            loadRecordingsForSelectedDate()
        }
        btnNextDay.setOnClickListener {
            // Don't allow navigating into the future
            val today = Calendar.getInstance()
            if (!isSameDay(calendar, today)) {
                calendar.add(Calendar.DAY_OF_MONTH, 1)
                updateDateDisplay()
                loadRecordingsForSelectedDate()
            }
        }
        tvSelectedDate.setOnClickListener { showDatePicker() }
    }

    private fun showDatePicker() {
        DatePickerDialog(
            requireContext(),
            { _, year, month, day ->
                calendar.set(year, month, day)
                updateDateDisplay()
                loadRecordingsForSelectedDate()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).also { picker ->
            // Prevent selecting future dates
            picker.datePicker.maxDate = System.currentTimeMillis()
        }.show()
    }

    private fun updateDateDisplay() {
        tvSelectedDate.text = dateFormat.format(calendar.time)
        // Dim the next-day arrow when we're already on today
        val isToday = isSameDay(calendar, Calendar.getInstance())
        btnNextDay.alpha = if (isToday) 0.3f else 1f
    }

    private fun isSameDay(a: Calendar, b: Calendar): Boolean =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
        a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    private fun setupFilterButtons() {
        btnFilterAll.setOnClickListener     { setFilter(RecordingFilter.ALL) }
        btnFilterNormal.setOnClickListener  { setFilter(RecordingFilter.NORMAL) }
        btnFilterSentry.setOnClickListener  { setFilter(RecordingFilter.SENTRY) }
        btnFilterProx.setOnClickListener    { setFilter(RecordingFilter.PROXIMITY) }
        updateFilterButtons()
    }

    private fun setFilter(filter: RecordingFilter) {
        currentFilter = filter
        updateFilterButtons()
        loadRecordingsForSelectedDate()
    }

    private fun updateFilterButtons() {
        val buttons = listOf(
            btnFilterAll     to RecordingFilter.ALL,
            btnFilterNormal  to RecordingFilter.NORMAL,
            btnFilterSentry  to RecordingFilter.SENTRY,
            btnFilterProx    to RecordingFilter.PROXIMITY
        )
        buttons.forEach { (btn, filter) ->
            val active = filter == currentFilter
            btn.setBackgroundResource(
                if (active) R.drawable.bg_filter_tab_active
                else android.R.color.transparent
            )
            btn.setTextColor(
                requireContext().getColor(
                    if (active) R.color.bg_base else R.color.text_secondary
                )
            )
        }
    }

    private fun setupRecordingsList() {
        recordingAdapter = RecordingAdapter(
            onPlay   = { playRecording(it) },
            onDelete = { confirmDelete(it) }
        )
        recyclerRecordings.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = recordingAdapter
        }
    }

    private fun ensureDirectories() {
        try {
            val dirs = listOf(
                RecordingScanner.getRecordingsDir(requireContext()),
                RecordingScanner.getSentryEventsDir(requireContext()),
                RecordingScanner.getProximityEventsDir(requireContext())
            )
            dirs.first().parentFile?.mkdirs()
            dirs.forEach { it.mkdirs() }
            Log.d(TAG, "Directories ready: ${dirs.map { it.absolutePath }}")
        } catch (e: Exception) {
            Log.e(TAG, "Directory setup failed: ${e.message}")
        }
    }

    private fun loadRecordingsForSelectedDate() {
        val year  = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH)
        val day   = calendar.get(Calendar.DAY_OF_MONTH)

        if (scanExecutor.isShutdown) return
        scanExecutor.submit {
            try {
                val all = RecordingScanner.getRecordingsForDate(requireContext(), year, month, day)
                val filtered = when (currentFilter) {
                    RecordingFilter.ALL       -> all
                    RecordingFilter.NORMAL    -> all.filter { it.type == RecordingFile.RecordingType.NORMAL }
                    RecordingFilter.SENTRY    -> all.filter { it.type == RecordingFile.RecordingType.SENTRY }
                    RecordingFilter.PROXIMITY -> all.filter { it.type == RecordingFile.RecordingType.PROXIMITY }
                }
                Log.d(TAG, "Loaded ${filtered.size} recordings ($currentFilter) for $year-${month+1}-$day")

                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    if (filtered.isEmpty()) {
                        recyclerRecordings.visibility   = View.GONE
                        emptyStateContainer?.visibility = View.VISIBLE
                        tvEmptyState.visibility         = View.VISIBLE
                        tvEmptyState.text = when (currentFilter) {
                            RecordingFilter.ALL       -> "No recordings for this date"
                            RecordingFilter.NORMAL    -> "No normal recordings"
                            RecordingFilter.SENTRY    -> "No sentry events"
                            RecordingFilter.PROXIMITY -> "No proximity events"
                        }
                    } else {
                        recyclerRecordings.visibility   = View.VISIBLE
                        emptyStateContainer?.visibility = View.GONE
                        tvEmptyState.visibility         = View.GONE
                        recordingAdapter.submitList(filtered)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading recordings", e)
            }
        }
    }

    private fun playRecording(recording: RecordingFile) {
        try {
            val bundle = Bundle().apply {
                putString(MultiCameraPlayerFragment.ARG_VIDEO_PATH, recording.path)
                putString(MultiCameraPlayerFragment.ARG_VIDEO_TITLE, recording.name)
            }
            androidx.navigation.fragment.NavHostFragment
                .findNavController(this)
                .navigate(R.id.action_global_videoPlayer, bundle)
        } catch (e: Exception) {
            try {
                val uri = recording.contentUri ?: FileProvider.getUriForFile(
                    requireContext(),
                    "${requireContext().packageName}.fileprovider",
                    recording.file
                )
                startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "video/mp4")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        },
                        "Play with"
                    )
                )
            } catch (e2: Exception) {
                Toast.makeText(context, "Cannot play video: ${e2.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmDelete(recording: RecordingFile) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete Recording")
            .setMessage("Delete ${recording.name}?\nThis cannot be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ -> deleteRecording(recording) }
            .show()
    }

    private fun deleteRecording(recording: RecordingFile) {
        if (RecordingScanner.deleteRecording(recording)) {
            Toast.makeText(context, "Recording deleted", Toast.LENGTH_SHORT).show()
            loadRecordingsForSelectedDate()
        } else {
            Toast.makeText(context, "Failed to delete recording", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        RecordingScanner.invalidateCache()
        updateDateDisplay()
        loadRecordingsForSelectedDate()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scanExecutor.shutdown()
    }
}
