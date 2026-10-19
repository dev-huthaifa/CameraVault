package com.vault.secretcamera.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.vault.secretcamera.R
import com.vault.secretcamera.data.AppVaultRepository
import com.vault.secretcamera.ui.secondphone.FakeCrashActivity
import com.vault.secretcamera.ui.secondphone.SecondPhoneActivity
import com.vault.secretcamera.ui.secondphone.VaultMediaPickerActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

class AppLockService : Service() {

    data class BridgedMediaEntry(
        val uri: Uri,
        val file: File?,
        val expiresAt: Long
    )

    companion object {
        private const val NOTIFICATION_ID = 9021
        private const val CHANNEL_ID = "channel_second_phone_lock"

        const val ACTION_ACTIVATE_VAULT_APP = "com.vault.secretcamera.ACTION_ACTIVATE_VAULT_APP"
        const val EXTRA_TARGET_PKG = "extra_target_pkg"

        private val bridgedMediaEntries = ConcurrentHashMap<String, BridgedMediaEntry>()

        /**
         * Registers a temporary MediaStore URI/File that was bridged into the General Studio
         * so the user can pick it inside X/Instagram/WhatsApp, and schedules automatic deletion
         * so it NEVER stays permanently in the General Studio.
         */
        fun registerTemporaryBridgedMedia(context: Context, uri: Uri, file: File?, durationMs: Long = 120_000L) {
            val key = uri.toString()
            bridgedMediaEntries[key] = BridgedMediaEntry(
                uri = uri,
                file = file,
                expiresAt = System.currentTimeMillis() + durationMs
            )
            val appCtx = context.applicationContext
            Handler(Looper.getMainLooper()).postDelayed({
                cleanupExpiredBridgedMedia(appCtx)
            }, durationMs + 1000L)
        }

        fun cleanupExpiredBridgedMedia(context: Context) {
            val now = System.currentTimeMillis()
            val iterator = bridgedMediaEntries.entries.iterator()
            var removedAny = false
            while (iterator.hasNext()) {
                val entry = iterator.next().value
                if (now >= entry.expiresAt) {
                    deleteBridgedEntry(context, entry)
                    iterator.remove()
                    removedAny = true
                }
            }
            if (removedAny) {
                notifyMediaStoreChanged(context)
            }
        }

        fun cleanupAllBridgedMedia(context: Context) {
            if (bridgedMediaEntries.isEmpty()) return
            val iterator = bridgedMediaEntries.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next().value
                deleteBridgedEntry(context, entry)
                iterator.remove()
            }
            notifyMediaStoreChanged(context)
        }

        private fun deleteBridgedEntry(context: Context, entry: BridgedMediaEntry) {
            try {
                context.contentResolver.delete(entry.uri, null, null)
            } catch (_: Exception) {}
            try {
                entry.file?.let { if (it.exists()) it.delete() }
            } catch (_: Exception) {}
        }

        private fun notifyMediaStoreChanged(context: Context) {
            try {
                context.contentResolver.notifyChange(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, null)
                context.contentResolver.notifyChange(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, null)
            } catch (_: Exception) {}
        }
    }

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var appVaultRepository: AppVaultRepository

    private var lastInterceptedPkg: String? = null
    private var lastInterceptTime: Long = 0L

    // WindowManager for both:
    // 1) Full-screen instant Crash Blocker Overlay (prevents outside launch in 0ms on Android 10-15)
    // 2) Floating "استديو الخزنة 🔒" Quick-Access Pill for Vaulted Apps & System Photo Pickers
    private var windowManager: WindowManager? = null
    private var floatingVaultButton: View? = null
    private var floatingLayoutParams: WindowManager.LayoutParams? = null
    private var fullScreenCrashOverlay: View? = null
    private var currentActiveProtectedPkg: String? = null
    private var launcherPackages: Set<String> = emptySet()

    // Broadcast receiver to immediately lock vaulted apps the millisecond the user presses Home, Recents, or turns off the screen
    private val systemEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    revokeSessionAndLockAll()
                }
                @Suppress("DEPRECATION")
                Intent.ACTION_CLOSE_SYSTEM_DIALOGS -> {
                    val reason = intent.getStringExtra("reason") ?: ""
                    if (reason == "homekey" || reason == "recentapps" || reason == "fs_gesture") {
                        revokeSessionAndLockAll()
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        appVaultRepository = AppVaultRepository(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        refreshLauncherPackages()
        registerSystemEventReceiver()
        startForegroundNotification()
        startMonitoring()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_ACTIVATE_VAULT_APP) {
            val targetPkg = intent.getStringExtra(EXTRA_TARGET_PKG)
            if (!targetPkg.isNullOrEmpty()) {
                dismissCrashBlockerOverlay()
                AppVaultRepository.grantTemporaryAccess(targetPkg, 15 * 60_000L)
                currentActiveProtectedPkg = targetPkg
                showFloatingVaultStudioButton(targetPkg)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try {
            unregisterReceiver(systemEventReceiver)
        } catch (_: Exception) {}
        hideFloatingVaultStudioButton()
        dismissCrashBlockerOverlay()
        cleanupAllBridgedMedia(this)
        super.onDestroy()
        serviceJob.cancel()
    }

    private fun registerSystemEventReceiver() {
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                @Suppress("DEPRECATION")
                addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(systemEventReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(systemEventReceiver, filter)
            }
        } catch (_: Exception) {}
    }

    private fun revokeSessionAndLockAll() {
        AppVaultRepository.revokeAllAccess()
        currentActiveProtectedPkg = null
        hideFloatingVaultStudioButton()
        cleanupAllBridgedMedia(this)
    }

    private fun refreshLauncherPackages() {
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
            }
            val resolves = packageManager.queryIntentActivities(homeIntent, 0)
            val set = mutableSetOf<String>()
            for (r in resolves) {
                r.activityInfo?.packageName?.let { set.add(it) }
            }
            launcherPackages = set
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

    private fun startForegroundNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "خدمة نظام الكاميرا",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "حماية واستقرار النظام"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }

        val openIntent = Intent(this, SecondPhoneActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("خدمة الكاميرا الذكية نشطة 📷")
            .setContentText("معالجة الصور التلقائية قيد التشغيل")
            .setSmallIcon(R.drawable.ic_camera_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startMonitoring() {
        serviceScope.launch {
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager

            while (isActive) {
                try {
                    cleanupExpiredBridgedMedia(this@AppLockService)
                    val currentPackage = getForegroundPackage(usageStatsManager)
                    if (currentPackage != null) {
                        handleForegroundPackage(currentPackage)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                delay(120L)
            }
        }
    }

    private fun getForegroundPackage(usageStatsManager: UsageStatsManager?): String? {
        if (usageStatsManager == null) return null
        val endTime = System.currentTimeMillis()
        val beginTime = endTime - 10_000L
        val usageEvents = usageStatsManager.queryEvents(beginTime, endTime) ?: return null

        var foregroundPackage: String? = null
        val event = UsageEvents.Event()

        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                foregroundPackage = event.packageName
            }
        }
        return foregroundPackage
    }

    private fun handleForegroundPackage(targetPackage: String) {
        if (targetPackage == packageName) {
            // Hide the floating button while inside our own app (e.g. inside VaultMediaPickerActivity or SecondPhoneActivity)
            hideFloatingVaultStudioButton()
            return
        }

        // If user went back to the Home Screen Launcher, IMMEDIATELY revoke all temporary access & lock vaulted apps!
        if (isHomeLauncherPackage(targetPackage)) {
            revokeSessionAndLockAll()
            return
        }

        val isInVault = appVaultRepository.isAppInVault(targetPackage)
        val isProtected = appVaultRepository.isAppProtected(targetPackage)
        val hasAccess = AppVaultRepository.hasTemporaryAccess(targetPackage)

        // 1. STRICT OUTSIDE BLOCK: If app is protected and NOT authorized via Second Phone,
        // IMMEDIATELY cover the screen with a Full-Screen WindowManager Blocker Overlay (0ms delay, bypasses Android 10+ BAL restrictions)
        // AND fire FakeCrashActivity!
        if (isProtected && !hasAccess) {
            hideFloatingVaultStudioButton()
            val now = System.currentTimeMillis()
            if (targetPackage == lastInterceptedPkg && (now - lastInterceptTime) < 600L) {
                return
            }

            lastInterceptedPkg = targetPackage
            lastInterceptTime = now

            showInstantCrashBlockerOverlay(targetPackage)

            try {
                val crashIntent = Intent(this, FakeCrashActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION
                    putExtra(FakeCrashActivity.EXTRA_TARGET_PACKAGE, targetPackage)
                }
                startActivity(crashIntent)
            } catch (_: Exception) {}
            return
        }

        // 2. If user is inside an authorized Vaulted app (e.g., X, Instagram, WhatsApp)
        // OR inside ANY Gallery / PhotoPicker / Sub-Activity opened during that session -> Show the Floating "استديو الخزنة 🔒" button!
        if (isInVault && hasAccess) {
            dismissCrashBlockerOverlay()
            AppVaultRepository.grantTemporaryAccess(targetPackage, 15 * 60_000L)
            currentActiveProtectedPkg = targetPackage
            showFloatingVaultStudioButton(targetPackage)
        } else if (currentActiveProtectedPkg != null &&
            AppVaultRepository.hasTemporaryAccess(currentActiveProtectedPkg!!)
        ) {
            // User opened the General Studio / System Photo Picker / File Chooser from inside X or Instagram!
            // Keep the Floating "استديو الخزنة 🔒" button right on top of the General Studio!
            showFloatingVaultStudioButton(currentActiveProtectedPkg!!)
        } else {
            currentActiveProtectedPkg = null
            hideFloatingVaultStudioButton()
        }
    }

    /**
     * Draws an opaque, full-screen system error overlay directly via WindowManager (TYPE_APPLICATION_OVERLAY).
     * Unlike Service.startActivity() which Android 10+ blocks from the background, WindowManager overlays
     * appear in 0 milliseconds and completely block any interaction with the protected app launched from the Home Screen!
     */
    private fun showInstantCrashBlockerOverlay(targetPkg: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            return
        }

        mainHandler.post {
            dismissCrashBlockerOverlay()

            try {
                val density = resources.displayMetrics.density
                fun dp(v: Int): Int = (v * density).toInt()

                val appLabel = try {
                    val info = packageManager.getApplicationInfo(targetPkg, 0)
                    packageManager.getApplicationLabel(info).toString()
                } catch (_: PackageManager.NameNotFoundException) {
                    "التطبيق"
                }

                // Full-screen opaque dark backdrop so the underlying app is 100% hidden and untouchable
                val root = object : FrameLayout(this) {
                    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
                        if (event?.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                            kickToHomeScreenAndDismiss()
                            return true
                        }
                        return super.dispatchKeyEvent(event)
                    }
                }.apply {
                    setBackgroundColor(Color.parseColor("#FF090A0D"))
                    isClickable = true
                    isFocusable = true
                    isFocusableInTouchMode = true
                }

                // System crash card
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
                    textSize = 16.5f
                    setTypeface(typeface, Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                headerRow.addView(appIconView)
                headerRow.addView(titleView)

                val bodyView = TextView(this).apply {
                    text = "تعذر تشغيل «$appLabel» بسبب خطأ في النظام وتلف في ملفات التشغيل الأساسية (رمز الخطأ: ERR_SYS_0x80040111).\nلا يمكن فتح هذا التطبيق."
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
                    setPadding(dp(8), dp(12), dp(8), dp(12))
                    setOnClickListener {
                        kickToHomeScreenAndDismiss()
                    }
                }

                val infoBtn = Button(this).apply {
                    text = "ⓘ   معلومات التطبيق"
                    setTextColor(Color.parseColor("#E0E2E8"))
                    setBackgroundColor(Color.TRANSPARENT)
                    textSize = 14f
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    setPadding(dp(8), dp(10), dp(8), dp(6))
                    setOnClickListener {
                        kickToHomeScreenAndDismiss()
                    }
                }

                dialogCard.addView(headerRow)
                dialogCard.addView(bodyView)
                dialogCard.addView(divider)
                dialogCard.addView(closeBtn)
                dialogCard.addView(infoBtn)

                val cardParams = FrameLayout.LayoutParams(
                    (resources.displayMetrics.widthPixels * 0.88).toInt(),
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER
                }
                root.addView(dialogCard, cardParams)

                val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_SYSTEM_ERROR
                }

                val overlayParams = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    layoutType,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_FULLSCREEN or
                            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    PixelFormat.OPAQUE
                ).apply {
                    gravity = Gravity.CENTER
                }

                windowManager?.addView(root, overlayParams)
                fullScreenCrashOverlay = root
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun kickToHomeScreenAndDismiss() {
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(homeIntent)
        } catch (_: Exception) {}

        mainHandler.postDelayed({
            dismissCrashBlockerOverlay()
        }, 250L)
    }

    private fun dismissCrashBlockerOverlay() {
        mainHandler.post {
            val overlay = fullScreenCrashOverlay ?: return@post
            try {
                windowManager?.removeView(overlay)
            } catch (_: Exception) {}
            fullScreenCrashOverlay = null
        }
    }

    private fun showFloatingVaultStudioButton(targetPkg: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            return
        }

        mainHandler.post {
            if (floatingVaultButton != null) return@post

            try {
                val density = resources.displayMetrics.density
                fun dp(v: Int): Int = (v * density).toInt()

                val pill = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(24).toFloat()
                        setColor(0xF20D1520.toInt())
                        setStroke(dp(2), ContextCompat.getColor(this@AppLockService, R.color.vault_accent_cyan))
                    }
                    elevation = dp(10).toFloat()
                }

                val icon = ImageView(this).apply {
                    setImageResource(R.drawable.ic_vault_lock)
                    setColorFilter(ContextCompat.getColor(this@AppLockService, R.color.vault_accent_cyan))
                    layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply {
                        marginEnd = dp(6)
                    }
                }

                val label = TextView(this).apply {
                    text = "استديو الخزنة 🔒"
                    setTextColor(ContextCompat.getColor(this@AppLockService, R.color.text_primary))
                    textSize = 13f
                    setTypeface(typeface, Typeface.BOLD)
                }

                pill.addView(icon)
                pill.addView(label)

                val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    layoutType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = dp(16)
                    y = dp(110)
                }

                // Make the pill draggable AND clickable
                var initialX = 0
                var initialY = 0
                var initialTouchX = 0f
                var initialTouchY = 0f
                var isMoved = false

                pill.setOnTouchListener { _, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = params.x
                            initialY = params.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            isMoved = false
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = (event.rawX - initialTouchX).toInt()
                            val dy = (event.rawY - initialTouchY).toInt()
                            if (abs(dx) > dp(6) || abs(dy) > dp(6)) {
                                isMoved = true
                                params.x = initialX + dx
                                params.y = initialY + dy
                                try {
                                    windowManager?.updateViewLayout(pill, params)
                                } catch (_: Exception) {}
                            }
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            if (!isMoved) {
                                val target = currentActiveProtectedPkg ?: targetPkg
                                val pickerIntent = Intent(this@AppLockService, VaultMediaPickerActivity::class.java).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                    putExtra(VaultMediaPickerActivity.EXTRA_DIRECT_SHARE_PACKAGE, target)
                                }
                                startActivity(pickerIntent)
                            }
                            true
                        }
                        else -> false
                    }
                }

                windowManager?.addView(pill, params)
                floatingVaultButton = pill
                floatingLayoutParams = params
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun hideFloatingVaultStudioButton() {
        mainHandler.post {
            val btn = floatingVaultButton ?: return@post
            try {
                windowManager?.removeView(btn)
            } catch (_: Exception) {}
            floatingVaultButton = null
            floatingLayoutParams = null
        }
    }
}
