package com.umsify.app.ui.timetable

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import com.umsify.app.R
import com.umsify.app.data.local.AppDatabase
import com.umsify.app.data.model.TimetableEntry
import com.umsify.app.data.repository.UserDataRepository
import com.umsify.app.databinding.ActivityTimetableBinding
import com.umsify.app.ui.exams.ExamActivity
import com.umsify.app.ui.ai.AiImportActivity
import com.umsify.app.ui.home.HomeActivity
import com.umsify.app.ui.profile.ProfileActivity
import com.umsify.app.utils.SessionManager
import com.umsify.app.utils.TimeUtils
import kotlinx.coroutines.launch

/**
 * TimetableActivity — Shows the user's full weekly timetable.
 * Day selector tabs at the top, class cards below.
 */
class TimetableActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTimetableBinding
    private lateinit var adapter: TimetableAdapter
    private lateinit var sessionManager: SessionManager
    private lateinit var db: AppDatabase
    private lateinit var repo: UserDataRepository

    private val days = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    private val fullDays = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTimetableBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionManager = SessionManager.getInstance(this)
        db = AppDatabase.getInstance(this)
        repo = UserDataRepository(db, sessionManager)

        setupToolbar()
        setupDayTabs()
        setupRecyclerView()
        setupBottomNav()

        // Default to today
        val todayIdx = when (TimeUtils.getTodayName()) {
            "Monday" -> 0; "Tuesday" -> 1; "Wednesday" -> 2; "Thursday" -> 3
            "Friday" -> 4; "Saturday" -> 5; "Sunday" -> 6; else -> 0
        }
        binding.tabDays.selectTab(binding.tabDays.getTabAt(todayIdx))
        loadForDay(fullDays[todayIdx])
    }

    override fun onResume() {
        super.onResume()
        val pos = binding.tabDays.selectedTabPosition
        if (pos in fullDays.indices) {
            loadForDay(fullDays[pos])
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupDayTabs() {
        days.forEach { day ->
            binding.tabDays.addTab(binding.tabDays.newTab().setText(day))
        }

        binding.tabDays.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                tab?.position?.let { loadForDay(fullDays[it]) }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun setupRecyclerView() {
        adapter = TimetableAdapter()
        binding.rvClasses.layoutManager = LinearLayoutManager(this)
        binding.rvClasses.adapter = adapter
    }

    private fun loadForDay(day: String) {
        val userId = sessionManager.getUserId()
        lifecycleScope.launch {
            binding.progressTimetable.visibility = View.VISIBLE
            val classes = repo.getTimetableForDay(userId, day)
            binding.progressTimetable.visibility = View.GONE

            if (classes.isEmpty()) {
                binding.tvNoClass.visibility = View.VISIBLE
                binding.rvClasses.visibility = View.GONE
                binding.tvNoClass.text = getString(R.string.no_class_today)
            } else {
                binding.tvNoClass.visibility = View.GONE
                binding.rvClasses.visibility = View.VISIBLE
                adapter.submitList(classes)
            }
        }
    }

    private fun setupBottomNav() {
        binding.bottomNav.selectedItemId = R.id.nav_timetable
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> { startActivity(Intent(this, HomeActivity::class.java)); finish(); true }
                R.id.nav_timetable -> true
                R.id.nav_exams -> { startActivity(Intent(this, ExamActivity::class.java)); finish(); true }
                R.id.nav_ai -> { startActivity(Intent(this, AiImportActivity::class.java)); finish(); true }
                R.id.nav_profile -> { startActivity(Intent(this, ProfileActivity::class.java)); finish(); true }
                else -> false
            }
        }
    }
}
