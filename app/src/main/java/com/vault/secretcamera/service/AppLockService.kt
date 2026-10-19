package com.vault.secretcamera.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
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

    // Floating "استديو الخزنة 🔒" Quick-Access Pill for Vaulted Apps & System Photo Pickers
    private var windowManager: WindowManager? = null
    private var floatingVaultButton: View? = null
    private var floatingLayoutParams: WindowManager.LayoutParams? = null
    private var currentActiveProtectedPkg: String? = null
    private var launcherPackages: Set<String> = emptySet()

    override fun onCreate() {
        super.onCreate()
        appVaultRepository = AppVaultRepository(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        refreshLauncherPackages()
        startForegroundNotification()
        startMonitoring()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_ACTIVATE_VAULT_APP) {
            val targetPkg = intent.getStringExtra(EXTRA_TARGET_PKG)
            if (!targetPkg.isNullOrEmpty()) {
                AppVaultRepository.grantTemporaryAccess(targetPkg, 15 * 60_000L)
                currentActiveProtectedPkg = targetPkg
                showFloatingVaultStudioButton(targetPkg)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        hideFloatingVaultStudioButton()
        cleanupAllBridgedMedia(this)
        super.onDestroy()
        serviceJob.cancel()
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
                delay(200L)
            }
        }
    }

    private fun getForegroundPackage(usageStatsManager: UsageStatsManager?): String? {
        if (usageStatsManager == null) return null
        val endTime = System.currentTimeMillis()
        val beginTime = endTime - 15_000L
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

        // If user went back to the Home Screen Launcher, end the active floating session & clean up bridged files
        if (launcherPackages.contains(targetPackage)) {
            currentActiveProtectedPkg = null
            hideFloatingVaultStudioButton()
            cleanupAllBridgedMedia(this)
            return
        }

        val isInVault = appVaultRepository.isAppInVault(targetPackage)
        val isProtected = appVaultRepository.isAppProtected(targetPackage)
        val hasAccess = AppVaultRepository.hasTemporaryAccess(targetPackage)

        // 1. Strict Outside Block: If app is protected and NOT authorized via Second Phone, block with FakeCrashActivity
        if (isProtected && !hasAccess) {
            hideFloatingVaultStudioButton()
            val now = System.currentTimeMillis()
            if (targetPackage == lastInterceptedPkg && (now - lastInterceptTime) < 900L) {
                return
            }

            lastInterceptedPkg = targetPackage
            lastInterceptTime = now

            val crashIntent = Intent(this, FakeCrashActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                putExtra(FakeCrashActivity.EXTRA_TARGET_PACKAGE, targetPackage)
            }
            startActivity(crashIntent)
            return
        }

        // 2. If user is inside an authorized Vaulted app (e.g., X, Instagram, WhatsApp)
        // OR inside ANY Gallery / PhotoPicker / Sub-Activity opened during that session -> Show the Floating "استديو الخزنة 🔒" button!
        if (isInVault && hasAccess) {
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
