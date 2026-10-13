package com.umsify.app.ui.auth

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.umsify.app.data.local.AppDatabase
import com.umsify.app.data.repository.AuthRepository
import com.umsify.app.databinding.ActivityLoginBinding
import com.umsify.app.ui.home.HomeActivity
import com.umsify.app.utils.SessionManager

/**
 * LoginActivity — Handles both Login and Sign Up in a single screen.
 * Uses a TabLayout to switch between the two modes.
 * No UMS website dependency.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var viewModel: AuthViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupViewModel()
        setupTabs()
        setupLoginForm()
        setupSignupForm()
        observeViewModel()
    }

    private fun setupViewModel() {
        val db = AppDatabase.getInstance(this)
        val sessionManager = SessionManager.getInstance(this)
        val repository = AuthRepository(db, sessionManager)
        viewModel = ViewModelProvider(
            this,
            AuthViewModelFactory(repository)
        )[AuthViewModel::class.java]
    }

    private fun setupTabs() {
        // Default: show Login form
        showLoginForm()

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                hideKeyboard()
                when (tab?.position) {
                    0 -> showLoginForm()
                    1 -> showSignupForm()
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun showLoginForm() {
        binding.layoutLogin.visibility = View.VISIBLE
        binding.layoutSignup.visibility = View.GONE
    }

    private fun showSignupForm() {
        binding.layoutLogin.visibility = View.GONE
        binding.layoutSignup.visibility = View.VISIBLE
    }

    private fun setupLoginForm() {
        binding.btnLogin.setOnClickListener {
            val username = binding.etLoginUsername.text.toString().trim()
            val password = binding.etLoginPassword.text.toString()

            // Client-side validation
            if (username.isEmpty()) {
                binding.tilLoginUsername.error = "Please enter your username"
                return@setOnClickListener
            }
            if (password.isEmpty()) {
                binding.tilLoginPassword.error = "Please enter your password"
                return@setOnClickListener
            }
            clearLoginErrors()
            hideKeyboard()
            viewModel.login(username, password)
        }

        binding.tvForgotPassword.setOnClickListener {
            Snackbar.make(binding.root,
                "Password reset: contact your administrator.",
                Snackbar.LENGTH_LONG).show()
        }

        binding.tvCreateAccount.setOnClickListener {
            binding.tabLayout.selectTab(binding.tabLayout.getTabAt(1))
        }
    }

    private fun setupSignupForm() {
        binding.btnSignup.setOnClickListener {
            val name = binding.etSignupName.text.toString().trim()
            val regNum = binding.etSignupRegNumber.text.toString().trim()
            val username = binding.etSignupUsername.text.toString().trim()
            val password = binding.etSignupPassword.text.toString()
            val confirmPassword = binding.etSignupConfirmPassword.text.toString()
            val gender = when (binding.rgGender.checkedRadioButtonId) {
                binding.rbMale.id -> "Male"
                binding.rbFemale.id -> "Female"
                binding.rbOther.id -> "Other"
                else -> ""
            }
            val email = binding.etSignupEmail.text.toString().trim()

            if (!validateSignup(name, regNum, username, password, confirmPassword, gender)) return@setOnClickListener

            hideKeyboard()
            viewModel.signup(name, regNum, username, password, gender, email)
        }

        binding.tvAlreadyHaveAccount.setOnClickListener {
            binding.tabLayout.selectTab(binding.tabLayout.getTabAt(0))
        }
    }

    private fun validateSignup(
        name: String, regNum: String, username: String,
        password: String, confirmPassword: String, gender: String
    ): Boolean {
        var valid = true
        clearSignupErrors()

        if (name.isEmpty()) { binding.tilSignupName.error = "Full name is required"; valid = false }
        if (regNum.isEmpty()) { binding.tilSignupRegNumber.error = "Registration number is required"; valid = false }
        else if (!regNum.matches(Regex("\\d{7,10}"))) { binding.tilSignupRegNumber.error = "Enter a valid registration number (7-10 digits)"; valid = false }
        if (username.isEmpty()) { binding.tilSignupUsername.error = "Username is required"; valid = false }
        else if (username.length < 3) { binding.tilSignupUsername.error = "Username must be at least 3 characters"; valid = false }
        if (password.isEmpty()) { binding.tilSignupPassword.error = "Password is required"; valid = false }
        else if (password.length < 8) { binding.tilSignupPassword.error = "Password must be at least 8 characters"; valid = false }
        else if (!password.any { it.isDigit() }) { binding.tilSignupPassword.error = "Password must contain at least one number"; valid = false }
        if (confirmPassword != password) { binding.tilSignupConfirmPassword.error = "Passwords do not match"; valid = false }
        if (gender.isEmpty()) { Toast.makeText(this, "Please select your gender", Toast.LENGTH_SHORT).show(); valid = false }

        return valid
    }

    private fun clearLoginErrors() {
        binding.tilLoginUsername.error = null
        binding.tilLoginPassword.error = null
    }

    private fun clearSignupErrors() {
        binding.tilSignupName.error = null
        binding.tilSignupRegNumber.error = null
        binding.tilSignupUsername.error = null
        binding.tilSignupPassword.error = null
        binding.tilSignupConfirmPassword.error = null
    }

    private fun observeViewModel() {
        viewModel.isLoading.observe(this) { loading ->
            binding.progressLogin.visibility = if (loading) View.VISIBLE else View.GONE
            binding.btnLogin.isEnabled = !loading
            binding.btnSignup.isEnabled = !loading
        }

        viewModel.authSuccess.observe(this) { session ->
            session ?: return@observe
            if (viewModel.isSignupFlow.value == true) {
                com.umsify.app.utils.ToastManager.signupSuccess(this)
            } else {
                com.umsify.app.utils.ToastManager.loginSuccess(this)
            }
            startActivity(Intent(this, HomeActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()
        }

        viewModel.errorMessage.observe(this) { message ->
            if (!message.isNullOrEmpty()) {
                if (message.contains("Invalid username or password", ignoreCase = true)) {
                    com.umsify.app.utils.ToastManager.invalidLogin(this)
                } else if (message.contains("internet", ignoreCase = true) || message.contains("connection", ignoreCase = true)) {
                    com.umsify.app.utils.ToastManager.networkUnavailable(this)
                } else {
                    com.umsify.app.utils.ToastManager.showErrorToast(this, message)
                }
                Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
    }
}
