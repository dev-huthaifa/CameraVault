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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.vault.secretcamera.data.AppVaultRepository

/**
 * محرك الحظر الفوري على مستوى النظام (Zero-Latency Accessibility App Blocker):
 * يعمل في 0 ملي ثانية فور محاولة فتح أي تطبيق محفوظ بالخزنة من الشاشة الرئيسية:
 * 1. يلغي فتح التطبيق فوراً على مستوى النظام عبر GLOBAL_ACTION_HOME قبل ظهور أي إطار من واجهة التطبيق.
 * 2. يعرض نافذة خطأ نظام أصلية عبر TYPE_ACCESSIBILITY_OVERLAY (بدون الحاجة لأي صلاحيات إضافية).
 * 3. يمسح ألبوم «استديو الخزنة» المؤقت ويلغي تصريح الجلسة فور العودة للشاشة الرئيسية أو قفل الشاشة.
 */
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
                    VaultStudioMountManager.unmountFromGeneralStudio(this@VaultAccessibilityService)
                }
                @Suppress("DEPRECATION")
                Intent.ACTION_CLOSE_SYSTEM_DIALOGS -> {
                    val reason = intent.getStringExtra("reason") ?: ""
                    if (reason == "homekey" || reason == "recentapps" || reason == "fs_gesture") {
                        AppVaultRepository.revokeAllAccess()
                        VaultStudioMountManager.unmountFromGeneralStudio(this@VaultAccessibilityService)
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
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return

        // Ignore our own app, SystemUI, and soft keyboards
        if (pkg == packageName || pkg == "com.android.systemui" || inputMethodPackages.contains(pkg)) {
            return
        }

        // If user returned to Home Screen Launcher -> Immediately lock all apps & unmount temporary studio album!
        if (isHomeLauncherPackage(pkg)) {
            AppVaultRepository.revokeAllAccess()
            VaultStudioMountManager.unmountFromGeneralStudio(this)
            return
        }

        val isProtected = appVaultRepository.isAppProtected(pkg)
        val hasAccess = AppVaultRepository.hasTemporaryAccess(pkg)

        if (isProtected && !hasAccess) {
            val now = System.currentTimeMillis()
            // 1. Immediately abort the app launch back to the Home Screen in 0ms!
            performGlobalAction(GLOBAL_ACTION_HOME)
            VaultStudioMountManager.unmountFromGeneralStudio(this)

            if (pkg == lastBlockedPkg && (now - lastBlockedTime) < 800L) {
                return
            }
            lastBlockedPkg = pkg
            lastBlockedTime = now

            // 2. Show the native system crash dialog overlay
            showAccessibilityCrashDialog(pkg)
        } else if (appVaultRepository.isAppInVault(pkg) && hasAccess) {
            dismissOverlay()
            AppVaultRepository.grantTemporaryAccess(pkg, 15 * 60_000L)
            VaultStudioMountManager.mountForActiveSession(this)
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
                    setBackgroundColor(Color.parseColor("#D9000000"))
                    isClickable = true
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setOnClickListener { dismissOverlay() }
                }

                val dialogCard = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(24), dp(22), dp(24), dp(18))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(20).toFloat()
                        setColor(Color.parseColor("#2B2D31"))
                        setStroke(dp(1), Color.parseColor("#3F4248"))
                    }
                    elevation = dp(16).toFloat()
                    isClickable = true
                }

                val headerRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }

                val appIconView = ImageView(this).apply {
                    try {
                        setImageDrawable(packageManager.getApplicationIcon(targetPkg))
                    } catch (_: Exception) {
                        setImageResource(android.R.drawable.sym_def_app_icon)
                    }
                    layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply {
                        marginEnd = dp(14)
                    }
                }

                val titleView = TextView(this).apply {
                    text = "توقف تطبيق «$appLabel» بشكل متكرر"
                    setTextColor(Color.WHITE)
                    textSize = 16f
                    setTypeface(typeface, Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                headerRow.addView(appIconView)
                headerRow.addView(titleView)

                val bodyView = TextView(this).apply {
                    text = "تعذر تشغيل «$appLabel» بسبب خطأ في النظام وتلف في حزمة التشغيل (ERR_SYS_0x80040111). لا يمكن فتح هذا التطبيق."
                    setTextColor(Color.parseColor("#B8BCC4"))
                    textSize = 13.5f
                    setPadding(0, dp(14), 0, dp(18))
                }

                val divider = View(this).apply {
                    setBackgroundColor(Color.parseColor("#3E4046"))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(1)
                    )
                }

                val closeBtn = Button(this).apply {
                    text = "✕   إغلاق التطبيق"
                    setTextColor(Color.parseColor("#FF6B6B"))
                    setBackgroundColor(Color.TRANSPARENT)
                    textSize = 14.5f
                    setTypeface(typeface, Typeface.BOLD)
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    setPadding(dp(8), dp(12), dp(8), dp(10))
                    setOnClickListener {
                        performGlobalAction(GLOBAL_ACTION_HOME)
                        dismissOverlay()
                    }
                }

                dialogCard.addView(headerRow)
                dialogCard.addView(bodyView)
                dialogCard.addView(divider)
                dialogCard.addView(closeBtn)

                val cardParams = FrameLayout.LayoutParams(
                    (resources.displayMetrics.widthPixels * 0.88).toInt(),
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
            val v = crashOverlayView ?: return@post
            try {
                windowManager?.removeView(v)
            } catch (_: Exception) {}
            crashOverlayView = null
        }
    }
}
