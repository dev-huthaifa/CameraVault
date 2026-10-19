package com.vault.secretcamera.ui.secondphone

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.vault.secretcamera.SecretVaultApp
import com.vault.secretcamera.ads.AdManager
import com.vault.secretcamera.data.VaultRepository
import com.vault.secretcamera.databinding.ActivitySecretBrowserBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class SecretBrowserActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySecretBrowserBinding
    private lateinit var repository: VaultRepository

    private val homeUrl = "https://www.google.com"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySecretBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val app = application as SecretVaultApp
        repository = app.vaultRepository

        if (!app.securityPreferences.isUnlocked()) {
            finish()
            return
        }

        if (app.securityPreferences.isAntiScreenshotEnabled) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        }

        setupToolbar()
        setupWebView()
        AdManager.loadBanner(this, binding.adBannerBrowserContainer)
        AdManager.loadInterstitial(this)

        binding.secretWebView.loadUrl(homeUrl)
    }

    override fun onResume() {
        super.onResume()
        val app = application as SecretVaultApp
        if (!app.securityPreferences.isUnlocked()) {
            finish()
            return
        }
        AdManager.loadBanner(this, binding.adBannerBrowserContainer)
    }

    override fun onStop() {
        super.onStop()
        val app = application as SecretVaultApp
        if (!isFinishing && !isChangingConfigurations && !AdManager.isAdShowing) {
            app.securityPreferences.lockVault()
            finish()
        }
    }

    private fun setupToolbar() {
        binding.btnBrowserClose.setOnClickListener {
            if (binding.secretWebView.canGoBack()) {
                binding.secretWebView.goBack()
            } else {
                AdManager.showInterstitial(this) {
                    finish()
                }
            }
        }

        binding.btnBrowserRefresh.setOnClickListener {
            binding.secretWebView.reload()
        }

        binding.etBrowserUrl.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                val input = binding.etBrowserUrl.text.toString().trim()
                if (input.isNotEmpty()) {
                    loadUserInput(input)
                }
                true
            } else {
                false
            }
        }
    }

    private fun loadUserInput(input: String) {
        val targetUrl = if (input.startsWith("http://") || input.startsWith("https://")) {
            input
        } else if (input.contains(".") && !input.contains(" ")) {
            "https://$input"
        } else {
            "https://www.google.com/search?q=" + java.net.URLEncoder.encode(input, "UTF-8")
        }
        binding.secretWebView.loadUrl(targetUrl)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webView = binding.secretWebView
        val settings = webView.settings

        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = false
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false

        // Incognito cookie policy
        CookieManager.getInstance().setAcceptCookie(true)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                view?.loadUrl(url)
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                binding.browserProgressBar.visibility = View.VISIBLE
                if (!url.isNullOrBlank() && !binding.etBrowserUrl.hasFocus()) {
                    binding.etBrowserUrl.setText(url)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.browserProgressBar.visibility = View.GONE
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.browserProgressBar.progress = newProgress
                if (newProgress == 100) {
                    binding.browserProgressBar.visibility = View.GONE
                } else {
                    binding.browserProgressBar.visibility = View.VISIBLE
                }
            }
        }

        // Direct Vault Encrypted Download Listener
        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            downloadAndEncryptDirectly(url, mimetype, contentDisposition)
        }

        // Long press on image to save directly to vault
        webView.setOnLongClickListener {
            val result = webView.hitTestResult
            val type = result.type
            if (type == WebView.HitTestResult.IMAGE_TYPE || type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                val imageUrl = result.extra
                if (!imageUrl.isNullOrBlank()) {
                    showSaveImageDialog(imageUrl)
                    return@setOnLongClickListener true
                }
            }
            false
        }
    }

    private fun showSaveImageDialog(imageUrl: String) {
        AlertDialog.Builder(this)
            .setTitle("حفظ وتشفير الصورة 🔒")
            .setMessage("هل تريد حفظ هذه الصورة مباشرة داخل استديو الخزنة المشفرة؟")
            .setPositiveButton("حفظ في الخزنة") { _, _ ->
                downloadAndEncryptDirectly(imageUrl, "image/jpeg", null)
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun downloadAndEncryptDirectly(urlStr: String, mimeTypeHint: String?, disposition: String?) {
        Toast.makeText(this, "جاري تنزيل وتشفير الملف في الخزنة...", Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL(urlStr)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.connect()

                val bytes = connection.inputStream.use { it.readBytes() }
                val contentType = connection.contentType ?: mimeTypeHint ?: "application/octet-stream"

                var fileName = URLUtil.guessFileName(urlStr, disposition, contentType)
                if (fileName.isNullOrBlank()) {
                    fileName = "download_${System.currentTimeMillis()}"
                }

                val result = repository.saveDownloadedFile(bytes, fileName, contentType)

                withContext(Dispatchers.Main) {
                    if (result.isSuccess) {
                        Toast.makeText(
                            this@SecretBrowserActivity,
                            "تم حفظ وتشفير ($fileName) في الخزنة بأمان! 🔒",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(
                            this@SecretBrowserActivity,
                            "فشل تشفير الملف في الخزنة: ${result.exceptionOrNull()?.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SecretBrowserActivity, "فشل التنزيل: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroy() {
        try {
            binding.secretWebView.clearCache(true)
            binding.secretWebView.clearHistory()
            binding.secretWebView.destroy()
        } catch (_: Exception) {}
        super.onDestroy()
    }
}
