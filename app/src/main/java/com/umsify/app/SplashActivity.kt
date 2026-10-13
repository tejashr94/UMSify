package com.umsify.app

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.umsify.app.databinding.ActivitySplashBinding
import com.umsify.app.ui.auth.LoginActivity
import com.umsify.app.ui.home.HomeActivity
import com.umsify.app.utils.SessionManager

/**
 * SplashActivity — Entry point of UMSify v2.
 *
 * Flow:
 * 1. Show animated splash for ~1.8 seconds.
 * 2. Check EncryptedSharedPreferences for a valid session.
 * 3. If session exists → HomeActivity
 *    If not          → LoginActivity
 *
 * The UMS website is NO LONGER opened here.
 */
@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding
    private val SPLASH_DURATION_MS = 1800L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Animate logo in
        binding.ivSplashLogo.alpha = 0f
        binding.tvSplashName.alpha = 0f
        binding.tvSplashTagline.alpha = 0f

        binding.ivSplashLogo.animate().alpha(1f).setDuration(600).start()
        binding.tvSplashName.animate().alpha(1f).setStartDelay(300).setDuration(500).start()
        binding.tvSplashTagline.animate().alpha(1f).setStartDelay(500).setDuration(500).start()

        Handler(Looper.getMainLooper()).postDelayed({
            navigate()
        }, SPLASH_DURATION_MS)
    }

    private fun navigate() {
        val sessionManager = SessionManager.getInstance(this)
        val destination = if (sessionManager.isLoggedIn()) {
            HomeActivity::class.java
        } else {
            LoginActivity::class.java
        }
        startActivity(Intent(this, destination))
        finish()
    }
}
