package com.umsify.app.ui.ai

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.snackbar.Snackbar
import com.umsify.app.R
import com.umsify.app.data.local.AppDatabase
import com.umsify.app.data.model.ExamEntry
import com.umsify.app.data.model.TimetableEntry
import com.umsify.app.data.repository.UserDataRepository
import com.umsify.app.databinding.ActivityAiImportBinding
import com.umsify.app.ui.exams.ExamActivity
import com.umsify.app.ui.home.HomeActivity
import com.umsify.app.ui.profile.ProfileActivity
import com.umsify.app.ui.timetable.TimetableActivity
import com.umsify.app.utils.SessionManager
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * AiImportActivity — Document selection, validation, and Groq AI OCR extraction.
 * Supports PDF, PNG, JPG, JPEG, DOC, DOCX.
 * Sends document to Google Apps Script backend -> Groq qwen/qwen3.8-27b -> JSON.
 * On success, routes directly to ReviewExtractedDataActivity.
 */
class AiImportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAiImportBinding
    private lateinit var viewModel: AiImportViewModel
    private var importType: ImportType = ImportType.TIMETABLE
    private var selectedUri: Uri? = null
    private var selectedBytes: ByteArray? = null

    enum class ImportType { TIMETABLE, EXAM }

    private val docPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleFileSelected(it) }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAiImportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupViewModel()
        setupToolbar()
        setupButtons()
        setupBottomNav()
        observeViewModel()
    }

    private fun setupViewModel() {
        val db = AppDatabase.getInstance(this)
        val sessionManager = SessionManager.getInstance(this)
        val repo = UserDataRepository(db, sessionManager)
        viewModel = ViewModelProvider(
            this,
            AiImportViewModelFactory(applicationContext, repo, sessionManager)
        )[AiImportViewModel::class.java]
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupButtons() {
        binding.btnUploadTimetable.setOnClickListener {
            importType = ImportType.TIMETABLE
            showFilePicker()
        }

        binding.btnUploadExam.setOnClickListener {
            importType = ImportType.EXAM
            showFilePicker()
        }


        binding.btnAnalyze.setOnClickListener {
            val bytes = selectedBytes
            val uri = selectedUri
            if (bytes != null && uri != null) {
                val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
                val fileName = getFileName(uri)
                viewModel.processDocument(bytes, mimeType, fileName, importType)
            }
        }
    }

    private fun showFilePicker() {
        // Allow PDF, PNG, JPG, JPEG, DOC, DOCX
        docPicker.launch("*/*")
    }

    private fun handleFileSelected(uri: Uri) {
        selectedUri = uri
        val fileName = getFileName(uri)
        val fileSize = getFileSize(uri)

        com.umsify.app.utils.ToastManager.documentSelected(this)

        binding.layoutFileInfo.visibility = View.VISIBLE
        binding.tvFileName.text = fileName
        binding.tvFileSize.text = fileSize
        binding.btnAnalyze.visibility = View.VISIBLE
        binding.layoutResult.visibility = View.GONE

        // Read bytes and check for duplicate (Section 30)
        viewModel.checkFile(uri, contentResolver) { bytes ->
            selectedBytes = bytes
        }
    }

    private fun observeViewModel() {
        viewModel.isAnalyzing.observe(this) { analyzing ->
            if (analyzing) {
                binding.layoutAnalyzing.visibility = View.VISIBLE
                binding.layoutResult.visibility = View.GONE
                binding.btnAnalyze.isEnabled = false
            } else {
                binding.layoutAnalyzing.visibility = View.GONE
                binding.btnAnalyze.isEnabled = true
            }
        }

        viewModel.analyzingStep.observe(this) { step ->
            binding.tvAnalyzingStep.text = step
        }

        // Section 30: Document duplicate check
        viewModel.isDuplicateDetected.observe(this) { duplicateBytes ->
            if (duplicateBytes != null) {
                selectedBytes = duplicateBytes
                AlertDialog.Builder(this)
                    .setTitle("Document Already Processed")
                    .setMessage("This document was already analyzed previously. Would you like to reprocess it anyway?")
                    .setPositiveButton("Reprocess") { _, _ ->
                        viewModel.clearDuplicateFlag()
                    }
                    .setNegativeButton("Cancel") { _, _ ->
                        viewModel.clearDuplicateFlag()
                        binding.layoutFileInfo.visibility = View.GONE
                        binding.btnAnalyze.visibility = View.GONE
                        selectedUri = null
                        selectedBytes = null
                    }
                    .show()
            }
        }

        // Auto-navigate straight to Timetable screen without asking to sync!
        viewModel.timetableResult.observe(this) { entries ->
            if (!entries.isNullOrEmpty()) {
                val intent = Intent(this, TimetableActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                startActivity(intent)
                finish()
            }
        }

        // Auto-navigate straight to Exam Date Sheet screen without asking to sync!
        viewModel.examResult.observe(this) { exams ->
            if (!exams.isNullOrEmpty()) {
                val intent = Intent(this, ExamActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                startActivity(intent)
                finish()
            }
        }

        viewModel.errorMessage.observe(this) { message ->
            if (!message.isNullOrEmpty()) {
                binding.layoutAnalyzing.visibility = View.GONE
                Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
                    .setAction(getString(R.string.btn_try_again)) {
                        binding.btnAnalyze.performClick()
                    }
                    .show()
            }
        }
    }

    private fun getFileName(uri: Uri): String {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                cursor.moveToFirst()
                cursor.getString(idx)
            } ?: "Selected file"
        } catch (_: Exception) {
            "Selected file"
        }
    }

    private fun getFileSize(uri: Uri): String {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                cursor.moveToFirst()
                val bytes = cursor.getLong(idx)
                when {
                    bytes < 1024 -> "${bytes} B"
                    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
                    else -> "${bytes / (1024 * 1024)} MB"
                }
            } ?: "Unknown size"
        } catch (_: Exception) {
            "Unknown size"
        }
    }

    private fun setupBottomNav() {
        binding.bottomNav.selectedItemId = R.id.nav_ai
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> { startActivity(Intent(this, HomeActivity::class.java)); finish(); true }
                R.id.nav_timetable -> { startActivity(Intent(this, TimetableActivity::class.java)); finish(); true }
                R.id.nav_exams -> { startActivity(Intent(this, ExamActivity::class.java)); finish(); true }
                R.id.nav_ai -> true
                R.id.nav_profile -> { startActivity(Intent(this, ProfileActivity::class.java)); finish(); true }
                else -> false
            }
        }
    }
}
