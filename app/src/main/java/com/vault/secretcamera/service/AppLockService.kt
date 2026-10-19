package com.vault.secretcamera.service

import android.animation.ValueAnimator
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
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.vault.secretcamera.R
import com.vault.secretcamera.data.AppVaultRepository
import com.vault.secretcamera.ui.auth.AuthActivity
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
        const val ACTION_OPEN_VAULT_PICKER_NOW = "com.vault.secretcamera.ACTION_OPEN_VAULT_PICKER_NOW"
        const val ACTION_MEDIA_PICKER_DETECTED = "com.vault.secretcamera.ACTION_MEDIA_PICKER_DETECTED"
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

        fun notifyMediaPickerDetected(context: Context, targetPkg: String?) {
            try {
                val intent = Intent(context, AppLockService::class.java).apply {
                    action = ACTION_MEDIA_PICKER_DETECTED
                    if (!targetPkg.isNullOrEmpty()) {
                        putExtra(EXTRA_TARGET_PKG, targetPkg)
                    }
                }
                context.startService(intent)
            } catch (_: Exception) {}
        }
    }

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var appVaultRepository: AppVaultRepository

    private var lastInterceptedPkg: String? = null
    private var lastInterceptTime: Long = 0L

    private var windowManager: WindowManager? = null
    private var fullScreenCrashOverlay: View? = null

    // Smart Stealth Edge Handle state (Docked in bezel by default, auto-peeks ONLY when Gallery/Composer opens)
    private var edgeHandleContainer: FrameLayout? = null
    private var edgeLineIndicator: View? = null
    private var expandedPillView: LinearLayout? = null
    private var edgeLayoutParams: WindowManager.LayoutParams? = null
    private var isEdgePillExpanded = false
    private val autoCollapseRunnable = Runnable { collapseEdgeHandleToStealth() }

    // Strict Session State Tracking for Vaulted Apps
    @Volatile
    private var currentActiveProtectedPkg: String? = null
    @Volatile
    private var hasTargetAppEnteredForeground: Boolean = false
    @Volatile
    private var launchGraceUntil: Long = 0L
    @Volatile
    private var lastKnownForegroundPkg: String? = null
    @Volatile
    private var lastKnownForegroundClass: String? = null
    @Volatile
    private var lastPeekedActivitySignature: String? = null

    private var launcherPackages: Set<String> = emptySet()

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
        startForegroundNotification(null)
        startMonitoring()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ACTIVATE_VAULT_APP -> {
                val targetPkg = intent.getStringExtra(EXTRA_TARGET_PKG)
                if (!targetPkg.isNullOrEmpty()) {
                    dismissCrashBlockerOverlay()
                    AppVaultRepository.grantTemporaryAccess(targetPkg, 15 * 60_000L)
                    currentActiveProtectedPkg = targetPkg
                    hasTargetAppEnteredForeground = false
                    launchGraceUntil = System.currentTimeMillis() + 4500L
                    lastPeekedActivitySignature = null
                    startForegroundNotification(targetPkg)
                    showStealthEdgeVaultHandle(targetPkg, initialBriefPeek = true)
                }
            }
            ACTION_OPEN_VAULT_PICKER_NOW -> {
                val target = intent.getStringExtra(EXTRA_TARGET_PKG) ?: currentActiveProtectedPkg
                if (!target.isNullOrEmpty()) {
                    openVaultMediaPickerForTarget(target)
                }
            }
            ACTION_MEDIA_PICKER_DETECTED -> {
                val target = currentActiveProtectedPkg ?: intent.getStringExtra(EXTRA_TARGET_PKG)
                if (!target.isNullOrEmpty() && AppVaultRepository.hasTemporaryAccess(target)) {
                    showStealthEdgeVaultHandle(target, initialBriefPeek = false)
                    expandEdgePillTemporarily(4000L)
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try {
            unregisterReceiver(systemEventReceiver)
        } catch (_: Exception) {}
        dismissCrashBlockerOverlay()
        hideStealthEdgeVaultHandle()
        VaultStudioMountManager.unmountFromGeneralStudio(this)
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
        val hadActiveSession = currentActiveProtectedPkg != null
        AppVaultRepository.revokeAllAccess()
        currentActiveProtectedPkg = null
        hasTargetAppEnteredForeground = false
        lastPeekedActivitySignature = null
        hideStealthEdgeVaultHandle()
        VaultStudioMountManager.unmountFromGeneralStudio(this)
        cleanupAllBridgedMedia(this)
        if (hadActiveSession) {
            mainHandler.post { startForegroundNotification(null) }
        }
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
                lower.contains("hihonor.android.launcher") ||
                lower.contains("nothing.launcher") ||
                lower.contains("nexuslauncher") ||
                lower.contains("transsion") ||
                lower.contains("hios") ||
                lower.contains("xos.launcher")
    }

    /**
     * Checks if a package is a system media/file picker, permission dialog, or camera helper
     * launched by the currently active vaulted app (so we don't lock the vaulted app while picking a photo!).
     */
    private fun isTransientHelperPackage(pkg: String): Boolean {
        val lower = pkg.lowercase()
        return lower == "com.android.systemui" ||
                lower.contains("documentsui") ||
                lower.contains("providers.media") ||
                lower.contains("photopicker") ||
                lower.contains("permissioncontroller") ||
                lower.contains("packageinstaller") ||
                lower.contains("intentresolver") ||
                lower == "android" ||
                lower.contains("inputmethod") ||
                lower.contains("keyboard") ||
                lower.contains("gboard") ||
                lower.contains("honeyboard")
    }

    /**
     * Detects whether the user just opened a Gallery / Photo Picker / Post Composer / Attachment screen
     * inside the active app or via a system picker, so the Stealth Edge Handle can automatically peek out!
     */
    private fun isMediaPickerOrComposerWindow(pkg: String, className: String): Boolean {
        val lowerPkg = pkg.lowercase()
        if (lowerPkg.contains("documentsui") ||
            lowerPkg.contains("providers.media") ||
            lowerPkg.contains("photopicker") ||
            lowerPkg.contains("gallery") ||
            lowerPkg.contains("photos")
        ) {
            return true
        }
        val lowerClass = className.lowercase()
        if (lowerClass.isEmpty()) return false
        return lowerClass.contains("gallery") ||
                lowerClass.contains("picker") ||
                lowerClass.contains("media") ||
                lowerClass.contains("photo") ||
                lowerClass.contains("album") ||
                lowerClass.contains("composer") ||
                lowerClass.contains("camera") ||
                lowerClass.contains("attach") ||
                lowerClass.contains("creation") ||
                lowerClass.contains("upload") ||
                lowerClass.contains("post") ||
                lowerClass.contains("story") ||
                lowerClass.contains("reel") ||
                lowerClass.contains("chooser")
    }

    private fun startForegroundNotification(activeTargetPkg: String?) {
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

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_camera_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (!activeTargetPkg.isNullOrEmpty()) {
            val pickerIntent = Intent(this, VaultMediaPickerActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(VaultMediaPickerActivity.EXTRA_DIRECT_SHARE_PACKAGE, activeTargetPkg)
            }
            val pickerPendingIntent = PendingIntent.getActivity(
                this,
                101,
                pickerIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.setContentTitle("استديو الخزنة المباشر جاهز 🔒")
                .setContentText("اسحب شريط الحافة المخفي أو اضغط هنا لإرفاق صور/فيديو الخزنة")
                .setContentIntent(pickerPendingIntent)
                .addAction(
                    R.drawable.ic_vault_lock,
                    "فتح استديو الخزنة 🔒",
                    pickerPendingIntent
                )
        } else {
            val openIntent = Intent(this, CameraActivityLauncherAliasHelper.getSafeTargetClass())
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.setContentTitle("خدمة الكاميرا الذكية نشطة 📷")
                .setContentText("معالجة الصور التلقائية قيد التشغيل")
                .setContentIntent(pendingIntent)
        }

        val notification: Notification = builder.build()

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

    private object CameraActivityLauncherAliasHelper {
        fun getSafeTargetClass(): Class<*> = SecondPhoneActivity::class.java
    }

    private fun startMonitoring() {
        serviceScope.launch {
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager

            while (isActive) {
                try {
                    val isInteractive = powerManager?.isInteractive ?: true
                    if (!isInteractive) {
                        if (currentActiveProtectedPkg != null) {
                            revokeSessionAndLockAll()
                        }
                        delay(1000L)
                        continue
                    }

                    cleanupExpiredBridgedMedia(this@AppLockService)
                    val (currentPkg, currentClass) = getForegroundPackageAndClass(usageStatsManager)
                    if (currentPkg != null) {
                        handleForegroundPackage(currentPkg, currentClass ?: "")
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                // Ultra-fast 38ms check cycle so outside app launches are caught before a single frame renders
                delay(38L)
            }
        }
    }

    private fun getForegroundPackageAndClass(usageStatsManager: UsageStatsManager?): Pair<String?, String?> {
        if (usageStatsManager == null) return Pair(null, null)
        val endTime = System.currentTimeMillis()
        val beginTime = endTime - 3500L
        val usageEvents = usageStatsManager.queryEvents(beginTime, endTime) ?: return Pair(lastKnownForegroundPkg, lastKnownForegroundClass)

        var foregroundPackage: String? = null
        var foregroundClass: String? = null
        val event = UsageEvents.Event()

        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                foregroundPackage = event.packageName
                foregroundClass = event.className
            }
        }

        if (foregroundPackage != null) {
            lastKnownForegroundPkg = foregroundPackage
            lastKnownForegroundClass = foregroundClass
        }
        return Pair(lastKnownForegroundPkg, lastKnownForegroundClass)
    }

    private fun handleForegroundPackage(targetPackage: String, targetClass: String) {
        // 0. If user is inside our own Vault app (e.g. SecondPhoneActivity or VaultMediaPickerActivity), do not interfere
        if (targetPackage == packageName) {
            return
        }

        // 1. If user returned to ANY Home Screen Launcher -> IMMEDIATELY revoke session & lock all vaulted apps in 0ms!
        if (isHomeLauncherPackage(targetPackage)) {
            if (currentActiveProtectedPkg != null || AppVaultRepository.hasAnyTemporaryAccess()) {
                revokeSessionAndLockAll()
            }
            return
        }

        val activeAuthPkg = currentActiveProtectedPkg
        val now = System.currentTimeMillis()

        // 2. If there was an active vaulted app session, check if the user has left that app!
        if (activeAuthPkg != null) {
            if (targetPackage == activeAuthPkg) {
                hasTargetAppEnteredForeground = true
            } else if (!isTransientHelperPackage(targetPackage)) {
                // Is it a gallery app opened as a picker from the active app?
                val isSystemGalleryPicker = isMediaPickerOrComposerWindow(targetPackage, targetClass) &&
                        !appVaultRepository.isAppProtected(targetPackage)
                if (!isSystemGalleryPicker) {
                    // If the target app had already entered foreground (or launch grace expired) and now another app is in foreground -> LOCK IMMEDIATELY!
                    if (hasTargetAppEnteredForeground || now > launchGraceUntil) {
                        revokeSessionAndLockAll()
                    }
                }
            }
        }

        val isInVault = appVaultRepository.isAppInVault(targetPackage)
        val isProtected = appVaultRepository.isAppProtected(targetPackage)
        val hasAccess = AppVaultRepository.hasTemporaryAccess(targetPackage)

        // 3. STRICT OUTSIDE BLOCK: If app is protected and NOT authorized via Second Phone:
        // Kick it to the Home Screen IMMEDIATELY in 0ms AND show the authentic System Crash Dialog on top!
        if (isProtected && !hasAccess) {
            hideStealthEdgeVaultHandle()
            if (targetPackage == lastInterceptedPkg && (now - lastInterceptTime) < 550L) {
                return
            }

            lastInterceptedPkg = targetPackage
            lastInterceptTime = now
            lastKnownForegroundPkg = null

            val canDrawOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
            if (canDrawOverlay) {
                // Draw the opaque system crash overlay in 0ms AND immediately push the protected app to the Home Screen underneath!
                showInstantCrashBlockerOverlay(targetPackage)
                kickToHomeScreenImmediately()
            } else {
                // Fallback if overlay permission is not granted yet
                kickToHomeScreenImmediately()
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
            }
            return
        }

        // 4. User is inside an authorized Vaulted app (e.g., X, Instagram, WhatsApp)
        if (isInVault && hasAccess) {
            dismissCrashBlockerOverlay()
            AppVaultRepository.grantTemporaryAccess(targetPackage, 15 * 60_000L)
            currentActiveProtectedPkg = targetPackage
            hasTargetAppEnteredForeground = true
            showStealthEdgeVaultHandle(targetPackage, initialBriefPeek = false)

            // Smart Auto-Peek: If the user just opened a Gallery / Media Picker / Composer inside the app,
            // automatically slide out the "🔒 استديو الخزنة" pill for 3.8 seconds!
            val sig = "$targetPackage/$targetClass"
            if (sig != lastPeekedActivitySignature && isMediaPickerOrComposerWindow(targetPackage, targetClass)) {
                lastPeekedActivitySignature = sig
                expandEdgePillTemporarily(3800L)
            }
        } else if (currentActiveProtectedPkg != null &&
            AppVaultRepository.hasTemporaryAccess(currentActiveProtectedPkg!!)
        ) {
            // User is inside a system Photo Picker / DocumentsUI / Gallery helper launched by X or Instagram!
            val activePkg = currentActiveProtectedPkg!!
            showStealthEdgeVaultHandle(activePkg, initialBriefPeek = false)
            val sig = "$targetPackage/$targetClass"
            if (sig != lastPeekedActivitySignature && isMediaPickerOrComposerWindow(targetPackage, targetClass)) {
                lastPeekedActivitySignature = sig
                expandEdgePillTemporarily(4200L)
            }
        } else {
            hideStealthEdgeVaultHandle()
        }
    }

    /**
     * Immediately pushes the unauthorized foreground app to the background by launching the Home Screen
     * with NO animation underneath our WindowManager overlay, so the protected app NEVER runs or flashes!
     */
    private fun kickToHomeScreenImmediately() {
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
            }
            startActivity(homeIntent)
        } catch (_: Exception) {}
    }

    /**
     * Draws an authentic, pixel-perfect Android System "App keeps stopping" dialog over an opaque dark scrim.
     * Long-pressing the dialog title secretly opens AuthActivity for the phone owner.
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

                // 100% Opaque dark system backdrop during the first 180ms while Home Screen transitions underneath,
                // then softens to native Android system dialog scrim (#B3000000) so the user sees the dialog over their Home Screen!
                val root = object : FrameLayout(this) {
                    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
                        if (event?.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                            dismissCrashBlockerOverlay()
                            return true
                        }
                        return super.dispatchKeyEvent(event)
                    }
                }.apply {
                    setBackgroundColor(Color.parseColor("#FF000000"))
                    isClickable = true
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setOnClickListener {
                        dismissCrashBlockerOverlay()
                    }
                }

                mainHandler.postDelayed({
                    try {
                        root.setBackgroundColor(Color.parseColor("#B8000000"))
                    } catch (_: Exception) {}
                }, 180L)

                // Authentic Android 12-15 System Crash Dialog Card (AppErrorDialog style)
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
                    // Secret owner shortcut: Long-press title to unlock directly
                    setOnLongClickListener {
                        dismissCrashBlockerOverlay()
                        try {
                            val authIntent = Intent(this@AppLockService, AuthActivity::class.java).apply {
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
                        dismissCrashBlockerOverlay()
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
                        dismissCrashBlockerOverlay()
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
                            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    PixelFormat.TRANSLUCENT
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

    private fun dismissCrashBlockerOverlay() {
        mainHandler.post {
            val overlay = fullScreenCrashOverlay ?: return@post
            try {
                windowManager?.removeView(overlay)
            } catch (_: Exception) {}
            fullScreenCrashOverlay = null
        }
    }

    /**
     * =========================================================================================
     * مقبض الحافة الذكي المخفي (Smart Stealth Edge Handle + Auto-Gallery Pop-Out):
     * - أثناء استخدام التطبيق العادي (تصفح X أو إنستقرام أو المحادثات): يندمج تماماً في أقصى حافة الشاشة
     *   كخط رفيع جداً (5dp فقط وبشفافية هادئة 28%) لا يغطي أي جزء من الشاشة ولا يزعج المستخدم نهائياً.
     * - عند السحب أو اللمس من الحافة: يفتح فوراً «استديو الخزنة المباشر 🔒».
     * - عند فتح المعرض/الاستديو داخل التطبيق: ينبثق تلقائياً لمدة 3.8 ثانية ليسهل ضغطه ثم يعود للاختفاء في الحافة!
     * =========================================================================================
     */
    private fun showStealthEdgeVaultHandle(targetPkg: String, initialBriefPeek: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            return
        }

        mainHandler.post {
            if (edgeHandleContainer != null) {
                if (initialBriefPeek) {
                    expandEdgePillTemporarily(2400L)
                }
                return@post
            }

            try {
                val density = resources.displayMetrics.density
                fun dp(v: Int): Int = (v * density).toInt()

                val rootContainer = FrameLayout(this).apply {
                    clipChildren = false
                    clipToPadding = false
                }

                // 1. Stealth Edge Strip (Visible when collapsed: ultra-thin 5dp bar flush with screen edge)
                val stealthTouchZone = FrameLayout(this).apply {
                    layoutParams = FrameLayout.LayoutParams(dp(18), dp(64), Gravity.END or Gravity.CENTER_VERTICAL)
                }

                val slimEdgeBar = View(this).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadii = floatArrayOf(
                            dp(6).toFloat(), dp(6).toFloat(),
                            0f, 0f,
                            0f, 0f,
                            dp(6).toFloat(), dp(6).toFloat()
                        )
                        // Subtle 28% opacity cyan-white glow so it blends into the glass bezel without annoying the eye
                        setColor(0x4700E5FF)
                    }
                    layoutParams = FrameLayout.LayoutParams(dp(5), dp(52), Gravity.END or Gravity.CENTER_VERTICAL)
                }
                stealthTouchZone.addView(slimEdgeBar)

                // 2. Expanded Slide-Out Pill (Only visible temporarily when Gallery/Picker opens or when peeking)
                val pill = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(7), dp(10), dp(7))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadii = floatArrayOf(
                            dp(22).toFloat(), dp(22).toFloat(),
                            0f, 0f,
                            0f, 0f,
                            dp(22).toFloat(), dp(22).toFloat()
                        )
                        setColor(0xEB0D1520.toInt())
                        setStroke(dp(1), ContextCompat.getColor(this@AppLockService, R.color.vault_accent_cyan))
                    }
                    elevation = dp(8).toFloat()
                    visibility = View.GONE
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.END or Gravity.CENTER_VERTICAL
                    )
                }

                val icon = ImageView(this).apply {
                    setImageResource(R.drawable.ic_vault_lock)
                    setColorFilter(ContextCompat.getColor(this@AppLockService, R.color.vault_accent_cyan))
                    layoutParams = LinearLayout.LayoutParams(dp(17), dp(17)).apply {
                        marginEnd = dp(5)
                    }
                }

                val label = TextView(this).apply {
                    text = "استديو الخزنة"
                    setTextColor(ContextCompat.getColor(this@AppLockService, R.color.text_primary))
                    textSize = 12f
                    setTypeface(typeface, Typeface.BOLD)
                }

                pill.addView(icon)
                pill.addView(label)

                rootContainer.addView(stealthTouchZone)
                rootContainer.addView(pill)

                val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }

                val screenHeight = resources.displayMetrics.heightPixels
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    layoutType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.END
                    x = 0
                    y = (screenHeight * 0.36f).toInt()
                }

                // Gesture Handling on the Edge Handle:
                // - Swipe inward (left) OR single tap -> Immediately opens VaultMediaPickerActivity!
                // - Swipe outward (right) when expanded -> Immediately tucks back into stealth edge!
                // - Drag vertically (up/down) -> Moves the edge handle along the screen edge!
                var initialY = 0
                var initialTouchX = 0f
                var initialTouchY = 0f
                var isDraggingVertically = false
                var isSwipedInward = false

                val touchListener = View.OnTouchListener { _, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialY = params.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            isDraggingVertically = false
                            isSwipedInward = false
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = event.rawX - initialTouchX
                            val dy = event.rawY - initialTouchY

                            if (abs(dx) > dp(10) && abs(dx) > abs(dy) * 1.2f) {
                                if (dx < -dp(10) && !isSwipedInward) {
                                    // User swiped inward from the right edge -> Open Vault Studio immediately!
                                    isSwipedInward = true
                                    val activeTarget = currentActiveProtectedPkg ?: targetPkg
                                    collapseEdgeHandleToStealth()
                                    openVaultMediaPickerForTarget(activeTarget)
                                } else if (dx > dp(10) && isEdgePillExpanded) {
                                    // User swiped toward the right edge -> Tuck pill back into stealth edge!
                                    collapseEdgeHandleToStealth()
                                }
                            } else if (abs(dy) > dp(8) && !isSwipedInward) {
                                isDraggingVertically = true
                                params.y = (initialY + dy.toInt()).coerceIn(dp(60), screenHeight - dp(120))
                                try {
                                    windowManager?.updateViewLayout(rootContainer, params)
                                } catch (_: Exception) {}
                            }
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            if (!isDraggingVertically && !isSwipedInward) {
                                // Single tap on the edge handle or expanded pill -> Open Vault Studio immediately!
                                val activeTarget = currentActiveProtectedPkg ?: targetPkg
                                collapseEdgeHandleToStealth()
                                openVaultMediaPickerForTarget(activeTarget)
                            }
                            true
                        }
                        else -> false
                    }
                }

                rootContainer.setOnTouchListener(touchListener)
                stealthTouchZone.setOnTouchListener(touchListener)
                pill.setOnTouchListener(touchListener)

                windowManager?.addView(rootContainer, params)
                edgeHandleContainer = rootContainer
                edgeLineIndicator = stealthTouchZone
                expandedPillView = pill
                edgeLayoutParams = params
                isEdgePillExpanded = false

                if (initialBriefPeek) {
                    expandEdgePillTemporarily(2200L)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Smoothly slides out the compact "🔒 استديو الخزنة" pill from the screen edge for [durationMs],
     * then automatically retracts it back into the invisible 5dp edge bar so the screen stays 100% clean.
     */
    private fun expandEdgePillTemporarily(durationMs: Long) {
        mainHandler.post {
            val pill = expandedPillView ?: return@post
            val edgeBar = edgeLineIndicator ?: return@post

            mainHandler.removeCallbacks(autoCollapseRunnable)
            if (!isEdgePillExpanded) {
                isEdgePillExpanded = true
                edgeBar.visibility = View.GONE
                pill.visibility = View.VISIBLE
                pill.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
                val width = pill.measuredWidth.coerceAtLeast(120)
                pill.translationX = width.toFloat()
                ValueAnimator.ofFloat(width.toFloat(), 0f).apply {
                    duration = 200L
                    interpolator = DecelerateInterpolator()
                    addUpdateListener { anim ->
                        pill.translationX = anim.animatedValue as Float
                    }
                    start()
                }
            }
            mainHandler.postDelayed(autoCollapseRunnable, durationMs)
        }
    }

    private fun collapseEdgeHandleToStealth() {
        mainHandler.post {
            mainHandler.removeCallbacks(autoCollapseRunnable)
            val pill = expandedPillView ?: return@post
            val edgeBar = edgeLineIndicator ?: return@post
            if (!isEdgePillExpanded) return@post

            isEdgePillExpanded = false
            val width = pill.width.coerceAtLeast(120)
            ValueAnimator.ofFloat(0f, width.toFloat()).apply {
                duration = 180L
                interpolator = DecelerateInterpolator()
                addUpdateListener { anim ->
                    pill.translationX = anim.animatedValue as Float
                    if (anim.animatedFraction >= 1f) {
                        pill.visibility = View.GONE
                        pill.translationX = 0f
                        edgeBar.visibility = View.VISIBLE
                    }
                }
                start()
            }
        }
    }

    private fun hideStealthEdgeVaultHandle() {
        mainHandler.post {
            mainHandler.removeCallbacks(autoCollapseRunnable)
            val container = edgeHandleContainer ?: return@post
            try {
                windowManager?.removeView(container)
            } catch (_: Exception) {}
            edgeHandleContainer = null
            edgeLineIndicator = null
            expandedPillView = null
            edgeLayoutParams = null
            isEdgePillExpanded = false
        }
    }

    private fun openVaultMediaPickerForTarget(targetPkg: String) {
        try {
            val pickerIntent = Intent(this, VaultMediaPickerActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(VaultMediaPickerActivity.EXTRA_DIRECT_SHARE_PACKAGE, targetPkg)
            }
            startActivity(pickerIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
