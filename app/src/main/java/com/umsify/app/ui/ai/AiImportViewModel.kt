package com.umsify.app.ui.ai

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.lifecycle.*
import com.umsify.app.data.local.parser.LocalDocumentPipeline
import com.umsify.app.data.local.parser.ParseResult
import com.umsify.app.data.model.ExamEntry
import com.umsify.app.data.model.TimetableEntry
import com.umsify.app.data.repository.DataResult
import com.umsify.app.data.repository.UserDataRepository
import com.umsify.app.ui.ai.AiImportActivity.ImportType
import com.umsify.app.utils.SessionManager
import com.umsify.app.utils.ToastManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * AiImportViewModel — 100% Local Rule-Based Document Pipeline.
 * Zero Generative AI / Zero Groq API calls.
 * Performs on-device PDF rendering, ML Kit OCR, and deterministic LPU parsing.
 */
class AiImportViewModel(
    private val context: Context,
    private val repository: UserDataRepository,
    private val sessionManager: SessionManager
) : ViewModel() {

    private val _isAnalyzing = MutableLiveData(false)
    val isAnalyzing: LiveData<Boolean> = _isAnalyzing

    private val _analyzingStep = MutableLiveData<String>()
    val analyzingStep: LiveData<String> = _analyzingStep

    private val _timetableResult = MutableLiveData<List<TimetableEntry>?>()
    val timetableResult: LiveData<List<TimetableEntry>?> = _timetableResult

    private val _examResult = MutableLiveData<List<ExamEntry>?>()
    val examResult: LiveData<List<ExamEntry>?> = _examResult

    private val _isDuplicateDetected = MutableLiveData<ByteArray?>()
    val isDuplicateDetected: LiveData<ByteArray?> = _isDuplicateDetected

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    private val _saveSuccess = MutableLiveData<String?>()
    val saveSuccess: LiveData<String?> = _saveSuccess

    /**
     * Checks if document hash was already processed before starting local analysis.
     */
    fun checkFile(uri: Uri, resolver: ContentResolver, onReadyToProcess: (ByteArray) -> Unit) {
        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    resolver.openInputStream(uri)?.readBytes()
                } ?: throw IOException("Unable to read file")

                val isDup = repository.isDocumentAlreadyProcessed(bytes)
                if (isDup) {
                    _isDuplicateDetected.value = bytes
                } else {
                    onReadyToProcess(bytes)
                }
            } catch (e: Exception) {
                _errorMessage.value = "Failed to open document: ${e.message}"
            }
        }
    }

    /**
     * Executes local rule-based parsing on document bytes.
     * Fully offline and on-device.
     */
    fun processDocument(
        fileBytes: ByteArray,
        mimeType: String,
        fileName: String,
        type: ImportType
    ) {
        viewModelScope.launch {
            _isAnalyzing.value = true
            _errorMessage.value = null
            _timetableResult.value = null
            _examResult.value = null

            try {
                _analyzingStep.value = "Reading document locally…"
                delay(150)

                _analyzingStep.value = "Detecting layout & text with on-device OCR…"
                delay(150)

                _analyzingStep.value = "Parsing academic structure (Rule-Based)…"

                val pipeline = LocalDocumentPipeline(context)
                val userId = sessionManager.getUserId()
                val parseResult = pipeline.processDocumentBytes(fileBytes, mimeType, fileName, userId, type.name)

                when (parseResult) {
                    is ParseResult.TimetableParsed -> {
                        val effectiveUserId = userId.ifBlank {
                            if (parseResult.studentVid.isNotBlank()) "USER_${parseResult.studentVid}" else "USER_12412964"
                        }
                        if (sessionManager.getUserId().isBlank()) {
                            sessionManager.saveSession(
                                token = "GUEST_$effectiveUserId",
                                userId = effectiveUserId,
                                username = parseResult.studentVid.ifBlank { "Student" },
                                name = "Student",
                                registrationNumber = parseResult.studentVid
                            )
                        }

                        repository.recordDocumentProcessed(fileBytes, "timetable")
                        _analyzingStep.value = "Saving timetable locally…"
                        withContext(Dispatchers.IO) {
                            repository.saveTimetableLocally(effectiveUserId, parseResult.entries)
                        }
                        com.umsify.app.widget.WidgetUpdateManager.updateAllWidgets(context)
                        ToastManager.timetableGenerated(context)
                        ToastManager.timetableSaved(context)

                        // Synchronize directly to Google Sheets backend (Sheet 2: Timetable) BEFORE navigating
                        _analyzingStep.value = "Updating Google Sheet (Sheet 2: Timetable)…"
                        try {
                            val syncRes = withContext(Dispatchers.IO) {
                                repository.saveTimetableToBackend(parseResult.entries)
                            }
                            if (syncRes is DataResult.Success) {
                                withContext(Dispatchers.Main) {
                                    ToastManager.timetableSynced(context)
                                }
                            }
                        } catch (e: Exception) {
                            // Local Room cache already safe if network hiccup
                        }

                        com.umsify.app.widget.WidgetUpdateManager.updateAllWidgets(context)
                        _timetableResult.value = parseResult.entries
                    }
                    is ParseResult.ExamParsed -> {
                        val effectiveUserId = userId.ifBlank {
                            if (parseResult.registrationNumber.isNotBlank()) "USER_${parseResult.registrationNumber}" else "USER_12412964"
                        }
                        if (sessionManager.getUserId().isBlank()) {
                            sessionManager.saveSession(
                                token = "GUEST_$effectiveUserId",
                                userId = effectiveUserId,
                                username = parseResult.registrationNumber.ifBlank { "Student" },
                                name = parseResult.studentName.ifBlank { "Student" },
                                registrationNumber = parseResult.registrationNumber
                            )
                        }

                        repository.recordDocumentProcessed(fileBytes, "exam")
                        _analyzingStep.value = "Saving exam schedule locally…"
                        withContext(Dispatchers.IO) {
                            repository.saveExamsLocally(effectiveUserId, parseResult.exams)
                        }
                        com.umsify.app.widget.WidgetUpdateManager.updateAllWidgets(context)
                        ToastManager.examSheetGenerated(context)
                        ToastManager.examSheetSaved(context)

                        // Synchronize directly to Google Sheets backend (Sheet 3: ExamDateSheet) BEFORE navigating
                        _analyzingStep.value = "Updating Google Sheet (Sheet 3: ExamDateSheet)…"
                        try {
                            val syncRes = withContext(Dispatchers.IO) {
                                repository.saveExamsToBackend(parseResult.exams)
                            }
                            if (syncRes is DataResult.Success) {
                                withContext(Dispatchers.Main) {
                                    ToastManager.examSheetSynced(context)
                                }
                            }
                        } catch (e: Exception) {
                            // Local Room cache already safe if network hiccup
                        }

                        com.umsify.app.widget.WidgetUpdateManager.updateAllWidgets(context)
                        _examResult.value = parseResult.exams
                    }
                    is ParseResult.Error -> {
                        _errorMessage.value = parseResult.message
                        ToastManager.showErrorToast(context, parseResult.message)
                    }
                }
            } catch (e: Exception) {
                val err = "Extraction failed: ${e.message ?: "Please upload a clearer file."}"
                _errorMessage.value = err
                ToastManager.showErrorToast(context, err)
            } finally {
                _isAnalyzing.value = false
            }
        }
    }

    fun clearDuplicateFlag() {
        _isDuplicateDetected.value = null
    }

    fun saveTimetable(entries: List<TimetableEntry>) {
        viewModelScope.launch {
            repository.saveTimetableLocally(sessionManager.getUserId(), entries)
            ToastManager.timetableSaved(context)
            when (val result = repository.saveTimetableToBackend(entries)) {
                is DataResult.Success -> {
                    val count = entries.size
                    _saveSuccess.value = "Your timetable is ready. $count classes saved! ✓"
                    ToastManager.timetableSynced(context)
                }
                is DataResult.Error -> {
                    _errorMessage.value = result.message
                }
                DataResult.NetworkError -> {
                    _saveSuccess.value = "Saved locally in cache. Will sync when online."
                    ToastManager.networkUnavailable(context)
                }
            }
        }
    }

    fun saveExams(exams: List<ExamEntry>) {
        viewModelScope.launch {
            repository.saveExamsLocally(sessionManager.getUserId(), exams)
            ToastManager.examSheetSaved(context)
            when (val result = repository.saveExamsToBackend(exams)) {
                is DataResult.Success -> {
                    _saveSuccess.value = "Exam schedule saved! ${exams.size} exams ready. ✓"
                    ToastManager.examSheetSynced(context)
                }
                is DataResult.Error -> {
                    _errorMessage.value = result.message
                }
                DataResult.NetworkError -> {
                    _saveSuccess.value = "Saved locally in cache. Will sync when online."
                    ToastManager.networkUnavailable(context)
                }
            }
        }
    }
}

class AiImportViewModelFactory(
    private val context: Context,
    private val repository: UserDataRepository,
    private val sessionManager: SessionManager
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return AiImportViewModel(context, repository, sessionManager) as T
    }
}
