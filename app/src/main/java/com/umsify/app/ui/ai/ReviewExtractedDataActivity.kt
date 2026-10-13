package com.umsify.app.ui.ai

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.umsify.app.R
import com.umsify.app.data.local.AppDatabase
import com.umsify.app.data.model.ExamEntry
import com.umsify.app.data.model.SyncResultData
import com.umsify.app.data.model.TimetableEntry
import com.umsify.app.data.repository.DataResult
import com.umsify.app.data.repository.UserDataRepository
import com.umsify.app.databinding.ActivityReviewExtractedDataBinding
import com.umsify.app.databinding.DialogEditExamRowBinding
import com.umsify.app.databinding.DialogEditTimetableRowBinding
import com.umsify.app.databinding.ItemReviewRowBinding
import com.umsify.app.ui.home.HomeActivity
import com.umsify.app.utils.SessionManager
import com.umsify.app.widget.WidgetUpdateManager
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * ReviewExtractedDataActivity — Enforces Section 8:
 * "NEVER automatically save AI-extracted information without user confirmation.
 * After extraction, open a: 'Review Extracted Data' screen.
 * Allow user to: [Edit], [Delete], [Add], [Save & Sync]."
 */
class ReviewExtractedDataActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReviewExtractedDataBinding
    private lateinit var repository: UserDataRepository
    private lateinit var sessionManager: SessionManager

    private var isTimetable = true
    private val timetableList = mutableListOf<TimetableEntry>()
    private val examList = mutableListOf<ExamEntry>()
    private lateinit var adapter: ReviewAdapter

    companion object {
        const val EXTRA_IS_TIMETABLE = "extra_is_timetable"
        const val EXTRA_TIMETABLE_ITEMS = "extra_timetable_items"
        const val EXTRA_EXAM_ITEMS = "extra_exam_items"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReviewExtractedDataBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionManager = SessionManager.getInstance(this)
        val db = AppDatabase.getInstance(this)
        repository = UserDataRepository(db, sessionManager)

        isTimetable = intent.getBooleanExtra(EXTRA_IS_TIMETABLE, true)
        loadIntentData()
        setupToolbar()
        setupRecyclerView()
        setupButtons()
        updateUI()
    }

    @Suppress("UNCHECKED_CAST", "DEPRECATION")
    private fun loadIntentData() {
        if (isTimetable) {
            val list = intent.getSerializableExtra(EXTRA_TIMETABLE_ITEMS) as? ArrayList<TimetableEntry>
            if (list != null) {
                timetableList.addAll(com.umsify.app.utils.TimeUtils.mergeAndDeduplicateTimetable(list))
            }
        } else {
            val list = intent.getSerializableExtra(EXTRA_EXAM_ITEMS) as? ArrayList<ExamEntry>
            if (list != null) {
                val uniqueExams = list.distinctBy { "${it.courseCode.uppercase()}|${it.examDate}|${it.startTime}" }
                examList.addAll(uniqueExams)
            }
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.title = if (isTimetable) "Review Timetable" else "Review Exam Schedule"
    }

    private fun setupRecyclerView() {
        adapter = ReviewAdapter()
        binding.rvReviewList.layoutManager = LinearLayoutManager(this)
        binding.rvReviewList.adapter = adapter
    }

    private fun setupButtons() {
        binding.btnAddRow.setOnClickListener {
            if (isTimetable) showAddTimetableDialog() else showAddExamDialog()
        }

        binding.btnDiscard.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Discard Extracted Data?")
                .setMessage("Are you sure you want to discard this extracted data without saving?")
                .setPositiveButton("Discard") { _, _ -> finish() }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.btnSaveSync.setOnClickListener {
            performSaveAndSync()
        }
    }

    private fun updateUI() {
        val count = if (isTimetable) timetableList.size else examList.size
        binding.tvCountSummary.text = if (isTimetable) {
            "Detected Classes: $count"
        } else {
            "Detected Exams: $count"
        }

        if (count == 0) {
            binding.tvEmptyState.visibility = View.VISIBLE
            binding.rvReviewList.visibility = View.GONE
            binding.btnSaveSync.isEnabled = false
        } else {
            binding.tvEmptyState.visibility = View.GONE
            binding.rvReviewList.visibility = View.VISIBLE
            binding.btnSaveSync.isEnabled = true
        }
        adapter.notifyDataSetChanged()
    }

    // ── CRUD DIALOGS: TIMETABLE ──────────────────────────────────────────

    private fun showAddTimetableDialog() {
        val dialogBinding = DialogEditTimetableRowBinding.inflate(layoutInflater)
        dialogBinding.tvDialogTitle.text = "Add Class Entry"
        dialogBinding.etDay.setText("Monday")
        dialogBinding.etType.setText("LEC")
        dialogBinding.etStartTime.setText("09:00")
        dialogBinding.etEndTime.setText("10:00")

        AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setPositiveButton("Add") { _, _ ->
                val code = dialogBinding.etCourseCode.text.toString().trim()
                if (code.isBlank()) {
                    Toast.makeText(this, "Course code is required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val newEntry = TimetableEntry(
                    id = UUID.randomUUID().toString(),
                    userId = sessionManager.getUserId(),
                    courseCode = code.uppercase(),
                    courseName = dialogBinding.etCourseName.text.toString().trim(),
                    day = dialogBinding.etDay.text.toString().trim(),
                    date = dialogBinding.etDate.text.toString().trim().ifBlank { null },
                    startTime = dialogBinding.etStartTime.text.toString().trim(),
                    endTime = dialogBinding.etEndTime.text.toString().trim(),
                    type = dialogBinding.etType.text.toString().trim().uppercase().ifBlank { "LEC" },
                    room = dialogBinding.etRoom.text.toString().trim(),
                    section = dialogBinding.etSection.text.toString().trim(),
                    faculty = dialogBinding.etFaculty.text.toString().trim()
                ).normalized()
                timetableList.add(newEntry)
                updateUI()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditTimetableDialog(position: Int) {
        val entry = timetableList[position]
        val dialogBinding = DialogEditTimetableRowBinding.inflate(layoutInflater)
        dialogBinding.tvDialogTitle.text = "Edit Class Entry"
        dialogBinding.etCourseCode.setText(entry.courseCode)
        dialogBinding.etCourseName.setText(entry.courseName)
        dialogBinding.etDay.setText(entry.day)
        dialogBinding.etDate.setText(entry.date ?: "")
        dialogBinding.etStartTime.setText(entry.startTime)
        dialogBinding.etEndTime.setText(entry.endTime)
        dialogBinding.etType.setText(entry.type)
        dialogBinding.etRoom.setText(entry.room)
        dialogBinding.etSection.setText(entry.section)
        dialogBinding.etFaculty.setText(entry.faculty)

        AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setPositiveButton("Save Changes") { _, _ ->
                val code = dialogBinding.etCourseCode.text.toString().trim()
                if (code.isNotBlank()) {
                    val updated = entry.copy(
                        courseCode = code.uppercase(),
                        courseName = dialogBinding.etCourseName.text.toString().trim(),
                        day = dialogBinding.etDay.text.toString().trim(),
                        date = dialogBinding.etDate.text.toString().trim().ifBlank { null },
                        startTime = dialogBinding.etStartTime.text.toString().trim(),
                        endTime = dialogBinding.etEndTime.text.toString().trim(),
                        type = dialogBinding.etType.text.toString().trim().uppercase(),
                        room = dialogBinding.etRoom.text.toString().trim(),
                        section = dialogBinding.etSection.text.toString().trim(),
                        faculty = dialogBinding.etFaculty.text.toString().trim()
                    ).normalized()
                    timetableList[position] = updated
                    updateUI()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── CRUD DIALOGS: EXAMS ──────────────────────────────────────────────

    private fun showAddExamDialog() {
        val dialogBinding = DialogEditExamRowBinding.inflate(layoutInflater)
        dialogBinding.tvDialogTitle.text = "Add Exam Entry"
        dialogBinding.etExamType.setText("Theory End Term")
        dialogBinding.etExamStartTime.setText("09:30")
        dialogBinding.etExamEndTime.setText("12:30")

        AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setPositiveButton("Add") { _, _ ->
                val code = dialogBinding.etExamCourseCode.text.toString().trim()
                val date = dialogBinding.etExamDate.text.toString().trim()
                if (code.isBlank() || date.isBlank()) {
                    Toast.makeText(this, "Course code and date are required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val newExam = ExamEntry(
                    id = UUID.randomUUID().toString(),
                    userId = sessionManager.getUserId(),
                    courseCode = code.uppercase(),
                    courseName = dialogBinding.etExamCourseName.text.toString().trim(),
                    examDate = date,
                    startTime = dialogBinding.etExamStartTime.text.toString().trim(),
                    endTime = dialogBinding.etExamEndTime.text.toString().trim(),
                    room = dialogBinding.etExamRoom.text.toString().trim(),
                    examType = dialogBinding.etExamType.text.toString().trim().ifBlank { "Theory End Term" }
                ).normalized()
                examList.add(newExam)
                updateUI()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditExamDialog(position: Int) {
        val exam = examList[position]
        val dialogBinding = DialogEditExamRowBinding.inflate(layoutInflater)
        dialogBinding.tvDialogTitle.text = "Edit Exam Entry"
        dialogBinding.etExamCourseCode.setText(exam.courseCode)
        dialogBinding.etExamCourseName.setText(exam.courseName)
        dialogBinding.etExamDate.setText(exam.examDate)
        dialogBinding.etExamStartTime.setText(exam.startTime)
        dialogBinding.etExamEndTime.setText(exam.endTime)
        dialogBinding.etExamRoom.setText(exam.room)
        dialogBinding.etExamType.setText(exam.examType)

        AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setPositiveButton("Save Changes") { _, _ ->
                val code = dialogBinding.etExamCourseCode.text.toString().trim()
                val date = dialogBinding.etExamDate.text.toString().trim()
                if (code.isNotBlank() && date.isNotBlank()) {
                    val updated = exam.copy(
                        courseCode = code.uppercase(),
                        courseName = dialogBinding.etExamCourseName.text.toString().trim(),
                        examDate = date,
                        startTime = dialogBinding.etExamStartTime.text.toString().trim(),
                        endTime = dialogBinding.etExamEndTime.text.toString().trim(),
                        room = dialogBinding.etExamRoom.text.toString().trim(),
                        examType = dialogBinding.etExamType.text.toString().trim()
                    ).normalized()
                    examList[position] = updated
                    updateUI()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── SAVE & SYNC (PERMANENT SAVING AFTER CONFIRMATION) ────────────────

    private fun performSaveAndSync() {
        binding.btnSaveSync.isEnabled = false
        binding.btnSaveSync.text = "Syncing…"

        val userId = sessionManager.getUserId().ifBlank {
            if (isTimetable) {
                timetableList.firstOrNull()?.userId?.takeIf { it.isNotBlank() } ?: "USER_12412964"
            } else {
                examList.firstOrNull()?.userId?.takeIf { it.isNotBlank() } ?: "USER_12412964"
            }
        }

        lifecycleScope.launch {
            if (isTimetable) {
                // 1. Immediately save to local Room cache
                repository.saveTimetableLocally(userId, timetableList)
                com.umsify.app.utils.ToastManager.timetableSaved(this@ReviewExtractedDataActivity)
                WidgetUpdateManager.updateAllWidgets(this@ReviewExtractedDataActivity)

                // 2. Synchronize to Google Sheets via Apps Script
                when (val result = repository.saveTimetableToBackend(timetableList)) {
                    is DataResult.Success -> {
                        com.umsify.app.utils.ToastManager.timetableSynced(this@ReviewExtractedDataActivity)
                        showSyncSuccessDialog("Timetable", result.stats)
                    }
                    is DataResult.Error -> {
                        binding.btnSaveSync.isEnabled = true
                        binding.btnSaveSync.text = "Save & Sync"
                        Snackbar.make(binding.root, result.message, Snackbar.LENGTH_LONG).show()
                    }
                    DataResult.NetworkError -> {
                        com.umsify.app.utils.ToastManager.networkUnavailable(this@ReviewExtractedDataActivity)
                        finishWithSuccess()
                    }
                }
            } else {
                // 1. Immediately save to local Room cache
                repository.saveExamsLocally(userId, examList)
                com.umsify.app.utils.ToastManager.examSheetSaved(this@ReviewExtractedDataActivity)
                WidgetUpdateManager.updateAllWidgets(this@ReviewExtractedDataActivity)

                // 2. Synchronize to Google Sheets via Apps Script
                when (val result = repository.saveExamsToBackend(examList)) {
                    is DataResult.Success -> {
                        com.umsify.app.utils.ToastManager.examSheetSynced(this@ReviewExtractedDataActivity)
                        showSyncSuccessDialog("Exam Schedule", result.stats)
                    }
                    is DataResult.Error -> {
                        binding.btnSaveSync.isEnabled = true
                        binding.btnSaveSync.text = "Save & Sync"
                        Snackbar.make(binding.root, result.message, Snackbar.LENGTH_LONG).show()
                    }
                    DataResult.NetworkError -> {
                        com.umsify.app.utils.ToastManager.networkUnavailable(this@ReviewExtractedDataActivity)
                        finishWithSuccess()
                    }
                }
            }
        }
    }

    private fun showSyncSuccessDialog(typeTitle: String, stats: SyncResultData?) {
        WidgetUpdateManager.updateAllWidgets(this)

        val statsMsg = if (stats != null) {
            "Added: ${stats.inserted}\n" +
            "Updated: ${stats.updated}\n" +
            "Removed: ${stats.deleted}\n" +
            "Unchanged: ${stats.unchanged}"
        } else {
            "All records saved and synchronized."
        }

        AlertDialog.Builder(this)
            .setTitle("$typeTitle Synced Successfully ✓")
            .setMessage(statsMsg)
            .setPositiveButton("Done") { _, _ ->
                finishWithSuccess()
            }
            .setCancelable(false)
            .show()
    }

    private fun finishWithSuccess() {
        startActivity(Intent(this, HomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        })
        finish()
    }

    // ── RECYCLERVIEW ADAPTER ─────────────────────────────────────────────

    private inner class ReviewAdapter : RecyclerView.Adapter<ReviewAdapter.ViewHolder>() {

        inner class ViewHolder(val b: ItemReviewRowBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val b = ItemReviewRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(b)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val b = holder.b
            if (isTimetable) {
                val item = timetableList[position]
                b.tvCourseCode.text = item.courseCode
                b.tvCourseName.text = item.courseName.ifBlank { item.courseCode }
                b.tvBadge.text = item.type
                b.tvDayOrDate.text = if (!item.date.isNullOrBlank()) "${item.day} (${item.date})" else item.day
                b.tvTime.text = "${item.startTime} – ${item.endTime}"
                b.tvRoom.text = if (item.room.isNotBlank()) "Room: ${item.room}" else "Room: —"
                b.tvSection.text = if (item.section.isNotBlank()) "Sec: ${item.section}" else ""
                b.tvFaculty.text = item.faculty

                b.btnEdit.setOnClickListener { showEditTimetableDialog(position) }
                b.btnDelete.setOnClickListener {
                    timetableList.removeAt(position)
                    updateUI()
                }
            } else {
                val item = examList[position]
                b.tvCourseCode.text = item.courseCode
                b.tvCourseName.text = item.courseName.ifBlank { item.courseCode }
                b.tvBadge.text = item.examType
                b.tvDayOrDate.text = item.examDate
                b.tvTime.text = "${item.startTime} – ${item.endTime}"
                b.tvRoom.text = if (item.room.isNotBlank()) "Room: ${item.room}" else "Room: —"
                b.tvSection.text = ""
                b.tvFaculty.text = ""

                b.btnEdit.setOnClickListener { showEditExamDialog(position) }
                b.btnDelete.setOnClickListener {
                    examList.removeAt(position)
                    updateUI()
                }
            }
        }

        override fun getItemCount(): Int = if (isTimetable) timetableList.size else examList.size
    }
}
