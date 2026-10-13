package com.umsify.app.ui.home

import androidx.lifecycle.*
import com.umsify.app.data.model.ExamEntry
import com.umsify.app.data.model.TimetableEntry
import com.umsify.app.data.repository.DataResult
import com.umsify.app.data.repository.UserDataRepository
import com.umsify.app.utils.SessionManager
import com.umsify.app.utils.TimeUtils
import kotlinx.coroutines.launch

class HomeViewModel(
    private val repository: UserDataRepository,
    private val sessionManager: SessionManager
) : ViewModel() {

    private val _currentClass = MutableLiveData<TimetableEntry?>()
    val currentClass: LiveData<TimetableEntry?> = _currentClass

    private val _nextClass = MutableLiveData<TimetableEntry?>()
    val nextClass: LiveData<TimetableEntry?> = _nextClass

    private val _todayClasses = MutableLiveData<List<TimetableEntry>>()
    val todayClasses: LiveData<List<TimetableEntry>> = _todayClasses

    private val _nextExam = MutableLiveData<ExamEntry?>()
    val nextExam: LiveData<ExamEntry?> = _nextExam

    private val _dynamicMessage = MutableLiveData<String>()
    val dynamicMessage: LiveData<String> = _dynamicMessage

    private val _lastSyncedText = MutableLiveData<String>()
    val lastSyncedText: LiveData<String> = _lastSyncedText

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    private val _syncCompleteEvent = MutableLiveData<Boolean>()
    val syncCompleteEvent: LiveData<Boolean> = _syncCompleteEvent

    fun loadDashboard() {
        viewModelScope.launch {
            val userId = sessionManager.getUserId()
            if (userId.isEmpty()) return@launch

            val today = TimeUtils.getTodayName()
            val classes = repository.getTimetableForDay(userId, today)
            _todayClasses.value = classes

            val now = TimeUtils.getCurrentTimeMinutes()
            val current = classes.firstOrNull { entry ->
                val start = TimeUtils.parseTimeToMinutes(entry.startTime)
                val end = TimeUtils.parseTimeToMinutes(entry.endTime)
                now in start until end
            }
            _currentClass.value = current

            val next = classes.firstOrNull { entry ->
                TimeUtils.parseTimeToMinutes(entry.startTime) > now
            }
            _nextClass.value = next

            // Next exam
            val nextExam = repository.getNextExam(userId, TimeUtils.getTodayIso())
            _nextExam.value = nextExam

            // Dynamic message
            _dynamicMessage.value = buildDynamicMessage(classes, current, next, nextExam)

            // Last synced
            val session = sessionManager.getSession()
            _lastSyncedText.value = TimeUtils.relativeTime(session?.lastSyncedAt ?: 0L)
        }
    }

    fun syncData() {
        viewModelScope.launch {
            _isLoading.value = true
            when (val result = repository.syncAll()) {
                is DataResult.Success -> {
                    loadDashboard()
                    _errorMessage.value = null
                    _syncCompleteEvent.value = true
                }
                is DataResult.Error -> _errorMessage.value = result.message
                DataResult.NetworkError -> _errorMessage.value = "No internet connection."
            }
            _isLoading.value = false
        }
    }

    private fun buildDynamicMessage(
        classes: List<TimetableEntry>,
        current: TimetableEntry?,
        next: TimetableEntry?,
        nextExam: ExamEntry?
    ): String {
        return when {
            classes.isEmpty() -> {
                if (nextExam != null) {
                    val days = TimeUtils.daysUntil(nextExam.examDate)
                    "No classes today. Next exam: ${nextExam.courseCode} in $days days! 🌟"
                } else {
                    "No classes today. Have a great day! 🌟"
                }
            }
            current != null -> "You are currently in ${current.courseCode}."
            next != null -> {
                val mins = TimeUtils.parseTimeToMinutes(next.startTime) - TimeUtils.getCurrentTimeMinutes()
                when {
                    mins <= 15 -> "Your next class starts in $mins minutes. ⏰"
                    else -> "You have ${classes.size} classes today."
                }
            }
            else -> {
                if (nextExam != null) {
                    val days = TimeUtils.daysUntil(nextExam.examDate)
                    "All classes finished for today 🎉 Next exam: ${nextExam.courseCode} ($days days left)."
                } else {
                    "All classes finished for today 🎉"
                }
            }
        }
    }
}

class HomeViewModelFactory(
    private val repository: UserDataRepository,
    private val sessionManager: SessionManager
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return HomeViewModel(repository, sessionManager) as T
    }
}
