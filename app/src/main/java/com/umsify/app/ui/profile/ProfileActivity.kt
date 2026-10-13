package com.umsify.app.ui.profile

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.umsify.app.R
import com.umsify.app.data.local.AppDatabase
import com.umsify.app.data.remote.ApiClient
import com.umsify.app.data.repository.DataResult
import com.umsify.app.data.repository.UserDataRepository
import com.umsify.app.databinding.ActivityProfileBinding
import com.umsify.app.ui.ai.AiImportActivity
import com.umsify.app.ui.auth.LoginActivity
import com.umsify.app.ui.exams.ExamActivity
import com.umsify.app.ui.home.HomeActivity
import com.umsify.app.ui.timetable.TimetableActivity
import com.umsify.app.utils.SessionManager
import com.umsify.app.utils.TimeUtils
import kotlinx.coroutines.launch

class ProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileBinding
    private lateinit var sessionManager: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionManager = SessionManager.getInstance(this)
        setupToolbar()
        loadProfile()
        setupButtons()
        setupBottomNav()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun loadProfile() {
        val session = sessionManager.getSession() ?: return
        val db = AppDatabase.getInstance(this)

        lifecycleScope.launch {
            val user = db.userDao().getUser(session.userId)
            val name = user?.name ?: session.name
            val initials = name.split(" ").take(2).mapNotNull { it.firstOrNull()?.toString() }.joinToString("")

            binding.tvAvatarInitials.text = initials.ifEmpty { "U" }
            binding.tvProfileName.text = name
            binding.tvProfileUsername.text = "@${user?.username ?: session.username}"
            binding.tvRegistrationNumber.text = user?.registrationNumber ?: session.registrationNumber
            binding.tvGender.text = user?.gender ?: "—"
            binding.tvEmail.text = if (!user?.email.isNullOrEmpty()) user?.email else "Not set"
            binding.tvLastSynced.text = "Last synced: ${TimeUtils.relativeTime(session.lastSyncedAt)}"

            // Timetable status
            val timetableCount = db.timetableDao().getTimetable(session.userId).size
            binding.tvTimetableStatus.text = if (timetableCount > 0) "$timetableCount classes loaded" else "No timetable"

            // Exam status
            val examCount = db.examDao().getExams(session.userId).size
            binding.tvExamStatus.text = if (examCount > 0) "$examCount exams loaded" else "No exam schedule"
        }
    }

    private fun setupButtons() {
        binding.btnSync.setOnClickListener {
            val db = AppDatabase.getInstance(this)
            val repo = UserDataRepository(db, sessionManager)
            binding.btnSync.isEnabled = false
            binding.btnSync.text = getString(R.string.syncing)
            lifecycleScope.launch {
                when (val result = repo.syncAll()) {
                    is DataResult.Success -> {
                        Snackbar.make(binding.root, getString(R.string.sync_success), Snackbar.LENGTH_SHORT).show()
                        loadProfile()
                    }
                    is DataResult.Error -> Snackbar.make(binding.root, result.message, Snackbar.LENGTH_LONG).show()
                    DataResult.NetworkError -> Snackbar.make(binding.root, getString(R.string.sync_failed), Snackbar.LENGTH_LONG).show()
                }
                binding.btnSync.isEnabled = true
                binding.btnSync.text = getString(R.string.btn_sync)
            }
        }

        binding.btnLogout.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.logout_confirm_title))
                .setMessage(getString(R.string.logout_confirm_message))
                .setPositiveButton("Logout") { _, _ -> performLogout() }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun performLogout() {
        val token = sessionManager.getSession()?.sessionToken ?: ""
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                if (token.isNotEmpty()) {
                    ApiClient.getService().logout(
                        com.umsify.app.data.model.AuthenticatedActionRequest("LOGOUT", token)
                    )
                }
            } catch (_: Exception) {}
        }
        sessionManager.clearSession()
        com.umsify.app.widget.WidgetUpdateManager.updateAllWidgets(this)
        com.umsify.app.utils.ToastManager.logoutSuccess(this)
        startActivity(Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    private fun setupBottomNav() {
        binding.bottomNav.selectedItemId = R.id.nav_profile
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> { startActivity(Intent(this, HomeActivity::class.java)); finish(); true }
                R.id.nav_timetable -> { startActivity(Intent(this, TimetableActivity::class.java)); finish(); true }
                R.id.nav_exams -> { startActivity(Intent(this, ExamActivity::class.java)); finish(); true }
                R.id.nav_ai -> { startActivity(Intent(this, AiImportActivity::class.java)); finish(); true }
                R.id.nav_profile -> true
                else -> false
            }
        }
    }
}
