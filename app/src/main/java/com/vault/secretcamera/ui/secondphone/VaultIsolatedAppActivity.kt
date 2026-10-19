package com.vault.secretcamera.ui.secondphone

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.vault.secretcamera.R
import com.vault.secretcamera.SecretVaultApp
import com.vault.secretcamera.ads.AdManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * محرك التطبيقات المعزول داخل الخزنة (Vault Isolated App Container):
 * يتيح للمستخدم حذف التطبيق نهائياً من الشاشة الرئيسية ومن نظام الهاتف الخارجي (إخفاء 100%)
 * مع استمرار تشغيل التطبيق (مثل X، إنستغرام، تيك توك، تيليجرام، سناب شات، فيسبوك، وغيرها)
 * بكامل ميزاته وبجلسة دخول محفوظة ومشفرة داخل الخزنة فقط!
 *
 * وعند الضغط على إرفاق صورة أو فيديو داخل التطبيق المعزول، يفتح تلقائياً ومباشرة:
 * «استديو الخزنة 🔒» مع زر التبديل الفوري لـ «الاستديو العام 📂» بدون أي أزرار عائمة أو تداخل.
 */
class VaultIsolatedAppActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
        const val EXTRA_APP_NAME = "extra_app_name"

        /**
         * Maps known social/platform packages and app names to their full mobile web-app / PWA endpoints
         * so they can run 100% inside the Vault even after the native APK is uninstalled from the phone!
         */
        fun resolveIsolatedAppUrl(packageName: String, appName: String): String? {
            val pkg = packageName.lowercase()
            val name = appName.lowercase()
            return when {
                pkg.contains("twitter") || pkg == "com.x.android" || name == "x" || name.contains("تويتر") || name.contains("twitter") ->
                    "https://x.com"
                pkg.contains("instagram") || name.contains("انستقرام") || name.contains("إنستغرام") || name.contains("instagram") ->
                    "https://www.instagram.com"
                pkg.contains("musically") || pkg.contains("trill") || pkg.contains("tiktok") || name.contains("تيك توك") || name.contains("tiktok") ->
                    "https://www.tiktok.com"
                pkg.contains("telegram") || pkg.contains("challegram") || name.contains("تيليجرام") || name.contains("تيلجرام") ->
                    "https://web.telegram.org/k/"
                pkg.contains("snapchat") || name.contains("سناب") || name.contains("snapchat") ->
                    "https://web.snapchat.com"
                pkg.contains("facebook.katana") || pkg.contains("facebook.lite") || name.contains("فيسبوك") || name.contains("facebook") ->
                    "https://m.facebook.com"
                pkg.contains("facebook.orca") || name.contains("ماسنجر") || name.contains("messenger") ->
                    "https://www.messenger.com"
                pkg.contains("whatsapp") || name.contains("واتساب") || name.contains("whatsapp") ->
                    "https://web.whatsapp.com"
                pkg.contains("youtube") || name.contains("يوتيوب") || name.contains("youtube") ->
                    "https://m.youtube.com"
                pkg.contains("reddit") || name.contains("ريديت") || name.contains("reddit") ->
                    "https://www.reddit.com"
                pkg.contains("discord") || name.contains("ديسكورد") || name.contains("discord") ->
                    "https://discord.com/app"
                pkg.contains("threads") || pkg.contains("barcelona") || name.contains("ثريدز") ->
                    "https://www.threads.net"
                pkg.contains("linkedin") || name.contains("لينكد") ->
                    "https://www.linkedin.com"
                pkg.contains("pinterest") || name.contains("بينتريست") ->
                    "https://www.pinterest.com"
                pkg.contains("chatgpt") || pkg.contains("openai") ->
                    "https://chatgpt.com"
                else -> null
            }
        }
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var isLaunchingPicker = false

    // Launches VaultMediaPickerActivity directly when user clicks "Add Photo/Video" inside X / Instagram / etc.
    private val vaultMediaPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isLaunchingPicker = false
        val callback = fileUploadCallback
        fileUploadCallback = null
        if (callback == null) return@registerForActivityResult

        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val data = result.data!!
            val uris = mutableListOf<Uri>()
            val clip = data.clipData
            if (clip != null) {
                for (i in 0 until clip.itemCount) {
                    clip.getItemAt(i)?.uri?.let { uris.add(it) }
                }
            } else {
                data.data?.let { uris.add(it) }
            }
            if (uris.isNotEmpty()) {
                callback.onReceiveValue(uris.toTypedArray())
                return@registerForActivityResult
            }
        }
        callback.onReceiveValue(null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as SecretVaultApp
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

        val targetPkg = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: ""
        val appName = intent.getStringExtra(EXTRA_APP_NAME) ?: "تطبيق الخزنة المعزول"
        val startUrl = resolveIsolatedAppUrl(targetPkg, appName) ?: "https://www.google.com"

        buildUI(appName)
        setupIsolatedWebView(startUrl)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        val app = application as SecretVaultApp
        if (!app.securityPreferences.isUnlocked()) {
            finish()
        }
    }

    override fun onStop() {
        super.onStop()
        val app = application as SecretVaultApp
        if (!isFinishing && !isLaunchingPicker && !isChangingConfigurations && !AdManager.isAdShowing) {
            CookieManager.getInstance().flush()
            app.securityPreferences.lockVault()
            finish()
        }
    }

    override fun onDestroy() {
        CookieManager.getInstance().flush()
        webView.destroy()
        super.onDestroy()
    }

    private fun buildUI(appName: String) {
        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@VaultIsolatedAppActivity, R.color.vault_bg_dark))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Sleek Top App Bar
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@VaultIsolatedAppActivity, R.color.vault_surface_dark))
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }

        val btnBack = ImageButton(this).apply {
            setImageResource(R.drawable.ic_backspace)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(ContextCompat.getColor(this@VaultIsolatedAppActivity, R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
            setOnClickListener { finish() }
        }

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(10)
            }
        }

        val tvTitle = TextView(this).apply {
            text = "$appName (معزول داخل الخزنة 🔒)"
            setTextColor(ContextCompat.getColor(this@VaultIsolatedAppActivity, R.color.text_primary))
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
        }

        val tvSub = TextView(this).apply {
            text = "متصل مباشرة باستديو الخزنة السري والـمعرض العام"
            setTextColor(ContextCompat.getColor(this@VaultIsolatedAppActivity, R.color.vault_accent_cyan))
            textSize = 10.5f
        }

        titleCol.addView(tvTitle)
        titleCol.addView(tvSub)

        val btnRefresh = ImageButton(this).apply {
            setImageResource(android.R.drawable.stat_notify_sync)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(ContextCompat.getColor(this@VaultIsolatedAppActivity, R.color.vault_accent_cyan))
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            setOnClickListener { webView.reload() }
        }

        topBar.addView(btnBack)
        topBar.addView(titleCol)
        topBar.addView(btnRefresh)
        root.addView(topBar)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(3)
            )
        }
        root.addView(progressBar)

        val contentFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        contentFrame.addView(webView)
        root.addView(contentFrame)

        setContentView(root)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupIsolatedWebView(startUrl: String) {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportMultipleWindows(false)
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = userAgentString.replace("; wv", "")
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                CookieManager.getInstance().flush()
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                val scheme = uri.scheme?.lowercase() ?: ""
                // Keep all http/https navigation strictly inside the isolated Vault container!
                return if (scheme == "http" || scheme == "https") {
                    false
                } else {
                    true // Block external intent:// redirects from escaping the Vault!
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                if (newProgress >= 100) progressBar.visibility = View.GONE
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.grant(request.resources)
            }

            // Intercepts "Add Photo / Video" inside X, Instagram, TikTok, Telegram, etc.,
            // and opens VaultMediaPickerActivity directly (which shows Vault Studio + Normal Gallery switch)!
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback

                val acceptTypes = fileChooserParams?.acceptTypes ?: emptyArray()
                val primaryMime = acceptTypes.firstOrNull { it.isNotBlank() } ?: "*/*"
                val isMulti = fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE

                val pickerIntent = Intent(this@VaultIsolatedAppActivity, VaultMediaPickerActivity::class.java).apply {
                    type = if (primaryMime.contains(",")) "*/*" else primaryMime
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, isMulti)
                }
                isLaunchingPicker = true
                vaultMediaPickerLauncher.launch(pickerIntent)
                return true
            }
        }

        // Direct encrypted download into Vault Studio
        webView.setDownloadListener(DownloadListener { url, _, contentDisposition, mimeType, _ ->
            if (url.isNullOrBlank()) return@DownloadListener
            Toast.makeText(this, "جاري تنزيل وتشفير الملف مباشرة داخل استديو الخزنة... 🔒", Toast.LENGTH_SHORT).show()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    val cookie = CookieManager.getInstance().getCookie(url)
                    if (!cookie.isNullOrEmpty()) {
                        conn.setRequestProperty("Cookie", cookie)
                    }
                    conn.connect()
                    val bytes = conn.inputStream.use { it.readBytes() }
                    val fileName = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)
                    val app = application as SecretVaultApp
                    app.vaultRepository.saveDownloadedFile(
                        bytes = bytes,
                        fileName = fileName,
                        mimeType = mimeType ?: "application/octet-stream"
                    )
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@VaultIsolatedAppActivity,
                            "✅ تم حفظ وتشفير «$fileName» داخل استديو الخزنة!",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@VaultIsolatedAppActivity, "فشل التنزيل: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })

        webView.loadUrl(startUrl)
    }
}
