package com.umsify.app

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.umsify.app.databinding.ActivityMainBinding
import com.umsify.app.widget.TimetableRefreshWorker

/**
 * MainActivity — Hosts the WebView that displays the LPU UMS website.
 *
 * Responsibilities:
 * - Configure WebView settings (JavaScript, cookies, storage, etc.)
 * - Load https://ums.lpu.in/lpuums/
 * - Show a loading indicator while the page is loading
 * - Handle basic network errors with a user-friendly message and retry option
 * - Handle back-navigation inside the WebView
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // The UMS website URL
    private val UMS_URL = "https://ums.lpu.in/lpuums/"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupWebView()
        setupRetryButton()

        if (isNetworkAvailable()) {
            loadUmsWebsite()
        } else {
            showErrorState()
        }
    }

    /**
     * Configure all WebView settings required for the UMS website to work correctly.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webView = binding.webView
        val settings: WebSettings = webView.settings

        // ── JavaScript ──────────────────────────────────────────────────────
        settings.javaScriptEnabled = true
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(true)

        // ── Mixed Content ───────────────────────────────────────────────────
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        // ── Storage & Databases ─────────────────────────────────────────────
        settings.domStorageEnabled = true
        settings.databaseEnabled = true

        // ── Viewport & Zoom ─────────────────────────────────────────────────
        settings.useWideViewPort = false // Sometimes messes up CSS grids on ASP.NET
        settings.loadWithOverviewMode = false // Same as above
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false   // hide the +/- zoom buttons

        // ── Cache ────────────────────────────────────────────────────────────
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        // ── Media ────────────────────────────────────────────────────────────
        settings.mediaPlaybackRequiresUserGesture = false

        // ── User Agent ───────────────────────────────────────────────────────
        // Use an iPad/Desktop User-Agent to prevent ASP.NET from serving the broken mobile version.
        // It's critical to include the word "Macintosh" or "Windows", so ASP.NET avoids rendering 
        // using its internal outdated Mobile adapters.
        settings.userAgentString = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        // ── Cookies ──────────────────────────────────────────────────────────
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        // ── WebViewClient — keep navigation inside the app ───────────────────
        webView.webViewClient = object : WebViewClient() {

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // Show progress bar, hide error state
                showLoadingState()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Hide progress bar, show WebView
                showWebViewState()
                // Persist cookies
                CookieManager.getInstance().flush()

                // Trigger background worker to fetch and update widget data
                val workRequest = OneTimeWorkRequestBuilder<TimetableRefreshWorker>().build()
                WorkManager.getInstance(applicationContext).enqueue(workRequest)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                android.util.Log.e("WebViewError", "Error: ${error?.errorCode} ${error?.description}")
                
                // Do not show the error layout if the error is just a favicon or analytics request failing
                if (request?.isForMainFrame == true) {
                    showErrorState()
                }
            }

            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(
                view: WebView?,
                handler: android.webkit.SslErrorHandler?,
                error: android.net.http.SslError?
            ) {
                android.util.Log.e("WebViewError", "SSL Error: ${error.toString()}")
                handler?.proceed() // Temporarily proceed for all SSL errors to see if this fixes the UI
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                // Keep all UMS-related URLs inside the WebView
                // External links (e.g. tel:, mailto:) can fall through to the OS
                val url = request?.url?.toString() ?: return false
                return if (url.startsWith("http://") || url.startsWith("https://")) {
                    // Load inside WebView
                    false
                } else {
                    // Let the OS handle non-HTTP schemes
                    true
                }
            }
        }

        // ── WebChromeClient — progress tracking ───────────────────────────────
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                binding.progressBar.progress = newProgress
                if (newProgress == 100) {
                    binding.progressBar.visibility = View.INVISIBLE
                } else {
                    binding.progressBar.visibility = View.VISIBLE
                }
            }

            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                android.util.Log.d("WebViewConsole", consoleMessage?.message() ?: "")
                return super.onConsoleMessage(consoleMessage)
            }
        }
    }

    private fun setupRetryButton() {
        binding.btnRetry.setOnClickListener {
            if (isNetworkAvailable()) {
                loadUmsWebsite()
            } else {
                showErrorState()
            }
        }
    }

    private fun loadUmsWebsite() {
        showLoadingState()
        binding.webView.loadUrl(UMS_URL)
    }

    // ── UI State Helpers ─────────────────────────────────────────────────────

    private fun showLoadingState() {
        binding.layoutError.visibility = View.GONE
        binding.webView.visibility = View.VISIBLE
        binding.progressBar.visibility = View.VISIBLE
    }

    private fun showWebViewState() {
        binding.layoutError.visibility = View.GONE
        binding.webView.visibility = View.VISIBLE
        binding.progressBar.visibility = View.INVISIBLE
    }

    private fun showErrorState() {
        binding.progressBar.visibility = View.INVISIBLE
        binding.webView.visibility = View.GONE
        binding.layoutError.visibility = View.VISIBLE
    }

    // ── Network Utility ──────────────────────────────────────────────────────

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // ── Back Navigation ──────────────────────────────────────────────────────

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
