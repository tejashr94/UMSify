package com.umsify.app.ui.exams

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.umsify.app.R
import com.umsify.app.data.local.AppDatabase
import com.umsify.app.data.repository.UserDataRepository
import com.umsify.app.databinding.ActivityExamBinding
import com.umsify.app.ui.ai.AiImportActivity
import com.umsify.app.ui.home.HomeActivity
import com.umsify.app.ui.profile.ProfileActivity
import com.umsify.app.ui.timetable.TimetableActivity
import com.umsify.app.utils.SessionManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ExamActivity : AppCompatActivity() {

    private lateinit var binding: ActivityExamBinding
    private lateinit var adapter: ExamAdapter
    private lateinit var sessionManager: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExamBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionManager = SessionManager.getInstance(this)
        val db = AppDatabase.getInstance(this)
        val repo = UserDataRepository(db, sessionManager)

        setupToolbar()
        setupRecyclerView()
        setupBottomNav()

        val userId = sessionManager.getUserId()
        lifecycleScope.launch {
            repo.getExamsFlow(userId).collectLatest { exams ->
                if (exams.isEmpty()) {
                    binding.tvNoExams.visibility = View.VISIBLE
                    binding.rvExams.visibility = View.GONE
                } else {
                    binding.tvNoExams.visibility = View.GONE
                    binding.rvExams.visibility = View.VISIBLE
                    adapter.submitList(exams)
                }
            }
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupRecyclerView() {
        adapter = ExamAdapter()
        binding.rvExams.layoutManager = LinearLayoutManager(this)
        binding.rvExams.adapter = adapter
    }

    private fun setupBottomNav() {
        binding.bottomNav.selectedItemId = R.id.nav_exams
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> { startActivity(Intent(this, HomeActivity::class.java)); finish(); true }
                R.id.nav_timetable -> { startActivity(Intent(this, TimetableActivity::class.java)); finish(); true }
                R.id.nav_exams -> true
                R.id.nav_ai -> { startActivity(Intent(this, AiImportActivity::class.java)); finish(); true }
                R.id.nav_profile -> { startActivity(Intent(this, ProfileActivity::class.java)); finish(); true }
                else -> false
            }
        }
    }
}
