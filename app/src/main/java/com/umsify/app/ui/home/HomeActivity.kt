package com.umsify.app.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.umsify.app.data.local.AppDatabase
import com.umsify.app.data.model.TimetableEntry
import com.umsify.app.data.repository.UserDataRepository
import com.umsify.app.databinding.ActivityHomeBinding
import com.umsify.app.ui.ai.AiImportActivity
import com.umsify.app.ui.exams.ExamActivity
import com.umsify.app.ui.profile.ProfileActivity
import com.umsify.app.ui.timetable.TimetableActivity
import com.umsify.app.utils.SessionManager
import com.umsify.app.utils.TimeUtils
import java.text.SimpleDateFormat
import java.util.*

/**
 * HomeActivity — Main dashboard after authentication.
 * Shows greeting, current class, today's schedule, next exam, and quick actions.
 * Uses BottomNavigationView for top-level navigation.
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var viewModel: HomeViewModel
    private lateinit var sessionManager: SessionManager
    private lateinit var todayAdapter: TodayClassAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionManager = SessionManager.getInstance(this)
        setupViewModel()
        setupRecyclerView()
        setupBottomNav()
        setupGreeting()
        setupQuickActions()
        observeViewModel()

        viewModel.loadDashboard()
        viewModel.syncData()
        com.umsify.app.widget.WidgetUpdateManager.updateAllWidgets(this)
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadDashboard()
        viewModel.syncData()
        com.umsify.app.widget.WidgetUpdateManager.updateAllWidgets(this)
    }

    private fun setupViewModel() {
        val db = AppDatabase.getInstance(this)
        val repo = UserDataRepository(db, sessionManager)
        viewModel = ViewModelProvider(
            this,
            HomeViewModelFactory(repo, sessionManager)
        )[HomeViewModel::class.java]
    }

    private fun setupRecyclerView() {
        todayAdapter = TodayClassAdapter()
        binding.rvTodayClasses.apply {
            layoutManager = LinearLayoutManager(this@HomeActivity)
            adapter = todayAdapter
            isNestedScrollingEnabled = false
        }
    }

    private fun setupGreeting() {
        val name = sessionManager.getUserName().split(" ").first()
        val greeting = TimeUtils.getGreeting()
        binding.tvGreeting.text = "$greeting, $name 👋"

        val cal = Calendar.getInstance()
        val dateStr = SimpleDateFormat("EEEE, dd MMMM", Locale.getDefault()).format(cal.time)
        binding.tvDate.text = dateStr
    }

    private fun setupBottomNav() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                com.umsify.app.R.id.nav_home -> true
                com.umsify.app.R.id.nav_timetable -> {
                    startActivity(Intent(this, TimetableActivity::class.java))
                    false
                }
                com.umsify.app.R.id.nav_exams -> {
                    startActivity(Intent(this, ExamActivity::class.java))
                    false
                }
                com.umsify.app.R.id.nav_ai -> {
                    startActivity(Intent(this, AiImportActivity::class.java))
                    false
                }
                com.umsify.app.R.id.nav_profile -> {
                    startActivity(Intent(this, ProfileActivity::class.java))
                    false
                }
                else -> false
            }
        }
    }

    private fun setupQuickActions() {
        binding.btnMyTimetable.setOnClickListener {
            startActivity(Intent(this, TimetableActivity::class.java))
        }
        binding.btnExamSheet.setOnClickListener {
            startActivity(Intent(this, ExamActivity::class.java))
        }
        binding.btnAiImport.setOnClickListener {
            startActivity(Intent(this, AiImportActivity::class.java))
        }
        binding.btnAddWidget.setOnClickListener {
            showAddWidgetDialog()
        }
        binding.btnMyProfile.setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
        }
        binding.btnSyncNow.setOnClickListener {
            viewModel.syncData()
        }
        com.umsify.app.widget.WidgetUpdateManager.schedulePeriodicSync(this)
        com.umsify.app.widget.WidgetUpdateManager.scheduleNextTransition(this)
    }

    private fun showAddWidgetDialog() {
        val options = arrayOf(
            "Timetable Widget (Current & Next Class)",
            "Exam Widget (Upcoming Exam Countdown)"
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Add Widget to Home Screen")
            .setItems(options) { _, which ->
                val appWidgetManager = getSystemService(android.appwidget.AppWidgetManager::class.java)
                val providerComponent = if (which == 0) {
                    android.content.ComponentName(this, com.umsify.app.widget.TimetableWidgetProvider::class.java)
                } else {
                    android.content.ComponentName(this, com.umsify.app.widget.ExamWidgetProvider::class.java)
                }

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    if (appWidgetManager != null && appWidgetManager.isRequestPinAppWidgetSupported) {
                        val pinnedIntent = Intent(this, com.umsify.app.widget.WidgetPinSuccessReceiver::class.java)
                        val successCallback = android.app.PendingIntent.getBroadcast(
                            this, 0, pinnedIntent,
                            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                        )
                        val success = appWidgetManager.requestPinAppWidget(providerComponent, null, successCallback)
                        if (success) {
                            com.umsify.app.utils.ToastManager.widgetAdded(this)
                        } else {
                            com.umsify.app.utils.ToastManager.showInfoToast(this, "Widget request cancelled")
                        }
                    } else {
                        showManualWidgetInstructions()
                    }
                } else {
                    showManualWidgetInstructions()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showManualWidgetInstructions() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("How to Add Widget")
            .setMessage("1. Go to your phone's home screen.\n2. Touch and hold an empty space.\n3. Tap 'Widgets'.\n4. Find 'UMSify' and drag the widget to your home screen.")
            .setPositiveButton("Got It", null)
            .show()
    }

    private fun observeViewModel() {
        viewModel.currentClass.observe(this) { entry ->
            if (entry != null) {
                binding.cardCurrentClass.visibility = View.VISIBLE
                binding.tvCurrentCourseCode.text = entry.courseCode
                binding.tvCurrentCourseName.text = entry.courseName.ifEmpty { entry.courseCode }
                binding.tvCurrentTime.text = "${TimeUtils.formatTo12h(entry.startTime)} – ${TimeUtils.formatTo12h(entry.endTime)}"
                binding.tvCurrentRoom.text = "Room: ${entry.room}"
            } else {
                binding.cardCurrentClass.visibility = View.GONE
            }
        }

        viewModel.nextClass.observe(this) { entry ->
            if (entry != null) {
                binding.cardNextClass.visibility = View.VISIBLE
                binding.tvNextCourseCode.text = entry.courseCode
                binding.tvNextCourseName.text = entry.courseName.ifEmpty { entry.courseCode }
                binding.tvNextTime.text = "Next: ${TimeUtils.formatTo12h(entry.startTime)}"
            } else {
                binding.cardNextClass.visibility = View.GONE
            }
        }

        viewModel.todayClasses.observe(this) { classes ->
            updateTodayState(classes)
        }

        viewModel.nextExam.observe(this) { exam ->
            if (exam != null) {
                binding.cardNextExam.visibility = View.VISIBLE
                binding.tvExamCode.text = exam.courseCode
                binding.tvExamName.text = exam.courseName.ifEmpty { exam.courseCode }
                binding.tvExamDate.text = TimeUtils.formatExamDate(exam.examDate)
                binding.tvExamTime.text = "${TimeUtils.formatTo12h(exam.startTime)} – ${TimeUtils.formatTo12h(exam.endTime)}"
                val daysLeft = TimeUtils.daysUntil(exam.examDate)
                binding.tvExamDaysLeft.text = when {
                    daysLeft == 0 -> "TODAY!"
                    daysLeft > 0 -> "$daysLeft DAYS LEFT"
                    else -> "PAST"
                }
            } else {
                binding.cardNextExam.visibility = View.GONE
            }
        }

        viewModel.dynamicMessage.observe(this) { message ->
            binding.tvDynamicMessage.text = message
        }

        viewModel.lastSyncedText.observe(this) { text ->
            binding.tvLastSynced.text = "Last synced: $text"
        }

        viewModel.isLoading.observe(this) { loading ->
            binding.progressHome.visibility = if (loading) View.VISIBLE else View.GONE
        }

        viewModel.errorMessage.observe(this) { message ->
            if (!message.isNullOrEmpty()) {
                Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
            }
        }

        viewModel.syncCompleteEvent.observe(this) { completed ->
            if (completed == true) {
                com.umsify.app.widget.WidgetUpdateManager.updateAllWidgets(this)
            }
        }
    }

    private fun updateTodayState(classes: List<TimetableEntry>) {
        when {
            classes.isEmpty() -> {
                binding.tvNoClassToday.visibility = View.VISIBLE
                binding.rvTodayClasses.visibility = View.GONE
                binding.tvNoClassToday.text = getString(com.umsify.app.R.string.no_class_today)
            }
            else -> {
                binding.tvNoClassToday.visibility = View.GONE
                binding.rvTodayClasses.visibility = View.VISIBLE
                todayAdapter.submitList(classes)
            }
        }
    }
}
