package com.vault.secretcamera.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.vault.secretcamera.data.AppVaultRepository
import com.vault.secretcamera.ui.auth.AuthActivity

class VaultAccessibilityService : AccessibilityService() {

    private lateinit var appVaultRepository: AppVaultRepository
    private var windowManager: WindowManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var crashOverlayView: View? = null
    private var launcherPackages: Set<String> = emptySet()
    private var inputMethodPackages: Set<String> = emptySet()
    private var lastBlockedPkg: String? = null
    private var lastBlockedTime: Long = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    AppVaultRepository.revokeAllAccess()
                }
                @Suppress("DEPRECATION")
                Intent.ACTION_CLOSE_SYSTEM_DIALOGS -> {
                    val reason = intent.getStringExtra("reason") ?: ""
                    if (reason == "homekey" || reason == "recentapps" || reason == "fs_gesture") {
                        AppVaultRepository.revokeAllAccess()
                    }
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        appVaultRepository = AppVaultRepository(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        refreshSystemPackages()
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                @Suppress("DEPRECATION")
                addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(screenReceiver, filter)
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {}
        dismissOverlay()
        super.onDestroy()
    }

    override fun onInterrupt() {
        dismissOverlay()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            if (AppVaultRepository.hasTemporaryAccess(pkg)) {
                val clickedText = buildString {
                    event.contentDescription?.let { append(it).append(' ') }
                    event.text.forEach { append(it).append(' ') }
                }.lowercase()
                if (clickedText.contains("صور") ||
                    clickedText.contains("معرض") ||
                    clickedText.contains("استوديو") ||
                    clickedText.contains("ألبوم") ||
                    clickedText.contains("إرفاق") ||
                    clickedText.contains("فيديو") ||
                    clickedText.contains("photo") ||
                    clickedText.contains("gallery") ||
                    clickedText.contains("media") ||
                    clickedText.contains("image") ||
                    clickedText.contains("attach") ||
                    clickedText.contains("album") ||
                    clickedText.contains("camera")
                ) {
                    AppLockService.notifyMediaPickerDetected(this, pkg)
                }
            }
            return
        }

        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        // Ignore our own app, SystemUI, and soft keyboards
        if (pkg == packageName || pkg == "com.android.systemui" || inputMethodPackages.contains(pkg)) {
            return
        }

        // If user returned to Home Screen Launcher -> Immediately lock all apps!
        if (isHomeLauncherPackage(pkg)) {
            AppVaultRepository.revokeAllAccess()
            return
        }

        val isProtected = appVaultRepository.isAppProtected(pkg)
        val hasAccess = AppVaultRepository.hasTemporaryAccess(pkg)

        if (isProtected && !hasAccess) {
            val now = System.currentTimeMillis()
            performGlobalAction(GLOBAL_ACTION_HOME)

            if (pkg == lastBlockedPkg && (now - lastBlockedTime) < 800L) {
                return
            }
            lastBlockedPkg = pkg
            lastBlockedTime = now

            showAccessibilityCrashDialog(pkg)
        } else if (appVaultRepository.isAppInVault(pkg) && hasAccess) {
            dismissOverlay()
            AppVaultRepository.grantTemporaryAccess(pkg, 15 * 60_000L)
            val cls = event.className?.toString()?.lowercase() ?: ""
            if (cls.contains("gallery") || cls.contains("picker") || cls.contains("media") ||
                cls.contains("photo") || cls.contains("composer") || cls.contains("album")
            ) {
                AppLockService.notifyMediaPickerDetected(this, pkg)
            }
        }
    }

    private fun refreshSystemPackages() {
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
            }
            val resolves = packageManager.queryIntentActivities(homeIntent, 0)
            launcherPackages = resolves.mapNotNull { it.activityInfo?.packageName }.toSet()
        } catch (_: Exception) {}

        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            inputMethodPackages = imm?.inputMethodList?.map { it.packageName }?.toSet() ?: emptySet()
        } catch (_: Exception) {}
    }

    private fun isHomeLauncherPackage(pkg: String): Boolean {
        if (launcherPackages.contains(pkg)) return true
        val lower = pkg.lowercase()
        return lower.contains("launcher") ||
                lower.contains("miui.home") ||
                lower.contains("sec.android.app.launcher") ||
                lower.contains("trebuchet") ||
                lower.contains("lawnchair") ||
                lower.contains("globallauncher") ||
                lower.contains("oppo.launcher") ||
                lower.contains("huawei.android.launcher") ||
                lower.contains("hihonor.android.launcher") ||
                lower.contains("nothing.launcher") ||
                lower.contains("nexuslauncher") ||
                lower.contains("transsion") ||
                lower.contains("hios") ||
                lower.contains("xos.launcher")
    }

    private fun showAccessibilityCrashDialog(targetPkg: String) {
        mainHandler.post {
            dismissOverlay()

            try {
                val density = resources.displayMetrics.density
                fun dp(v: Int): Int = (v * density).toInt()

                val appLabel = try {
                    val info = packageManager.getApplicationInfo(targetPkg, 0)
                    packageManager.getApplicationLabel(info).toString()
                } catch (_: PackageManager.NameNotFoundException) {
                    "التطبيق"
                }

                val root = object : FrameLayout(this) {
                    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
                        if (event?.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                            dismissOverlay()
                            return true
                        }
                        return super.dispatchKeyEvent(event)
                    }
                }.apply {
                    setBackgroundColor(Color.parseColor("#B8000000"))
                    isClickable = true
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setOnClickListener { dismissOverlay() }
                }

                val dialogCard = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(24), dp(22), dp(24), dp(14))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(28).toFloat()
                        setColor(Color.parseColor("#2C2C2E"))
                    }
                    elevation = dp(20).toFloat()
                    isClickable = true
                }

                val titleView = TextView(this).apply {
                    text = "توقف تطبيق «$appLabel» بشكل متكرر"
                    setTextColor(Color.parseColor("#F2F2F7"))
                    textSize = 17.5f
                    setTypeface(typeface, Typeface.BOLD)
                    setLineSpacing(dp(3).toFloat(), 1f)
                    setPadding(0, 0, 0, dp(16))
                    setOnLongClickListener {
                        dismissOverlay()
                        try {
                            val authIntent = Intent(this@VaultAccessibilityService, AuthActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                putExtra(AuthActivity.EXTRA_LOCKED_PACKAGE, targetPkg)
                            }
                            startActivity(authIntent)
                        } catch (_: Exception) {}
                        true
                    }
                }

                val infoBtn = Button(this).apply {
                    text = "ⓘ    معلومات التطبيق"
                    setTextColor(Color.parseColor("#E5E5EA"))
                    setBackgroundColor(Color.TRANSPARENT)
                    textSize = 14.5f
                    isAllCaps = false
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    setPadding(dp(4), dp(11), dp(4), dp(11))
                    setOnClickListener {
                        performGlobalAction(GLOBAL_ACTION_HOME)
                        dismissOverlay()
                    }
                }

                val closeBtn = Button(this).apply {
                    text = "✕    إغلاق التطبيق"
                    setTextColor(Color.parseColor("#E5E5EA"))
                    setBackgroundColor(Color.TRANSPARENT)
                    textSize = 14.5f
                    isAllCaps = false
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    setPadding(dp(4), dp(11), dp(4), dp(11))
                    setOnClickListener {
                        performGlobalAction(GLOBAL_ACTION_HOME)
                        dismissOverlay()
                    }
                }

                dialogCard.addView(titleView)
                dialogCard.addView(infoBtn)
                dialogCard.addView(closeBtn)

                val cardParams = FrameLayout.LayoutParams(
                    (resources.displayMetrics.widthPixels * 0.86).toInt(),
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER
                }
                root.addView(dialogCard, cardParams)

                val overlayParams = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.CENTER
                }

                windowManager?.addView(root, overlayParams)
                crashOverlayView = root
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun dismissOverlay() {
        mainHandler.post {
            val view = crashOverlayView ?: return@post
            try {
                windowManager?.removeView(view)
            } catch (_: Exception) {}
            crashOverlayView = null
        }
    }
}
