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
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.vault.secretcamera.R
import com.vault.secretcamera.data.AppVaultRepository
import com.vault.secretcamera.ui.secondphone.FakeCrashActivity
import com.vault.secretcamera.ui.secondphone.SecondPhoneActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AppLockService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 9021
        private const val CHANNEL_ID = "channel_second_phone_lock"
    }

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private lateinit var appVaultRepository: AppVaultRepository

    private var lastInterceptedPkg: String? = null
    private var lastInterceptTime: Long = 0L

    override fun onCreate() {
        super.onCreate()
        appVaultRepository = AppVaultRepository(this)
        startForegroundNotification()
        startMonitoring()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
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
                    if (appVaultRepository.isAppLockShieldEnabled()) {
                        val currentPackage = getForegroundPackage(usageStatsManager)
                        if (currentPackage != null && currentPackage != packageName) {
                            checkAndLockIfNeeded(currentPackage)
                        }
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

    /**
     * Strict Isolation Rule:
     * Any app moved into the Vault cannot be opened from the outside Home Launcher!
     * If launched from outside without an active Vault session token, we immediately block it
     * with FakeCrashActivity ("يتعذر تشغيل التطبيق - خطأ في النظام") so it never opens outside the Vault!
     */
    private fun checkAndLockIfNeeded(targetPackage: String) {
        if (appVaultRepository.isAppProtected(targetPackage)) {
            if (!AppVaultRepository.hasTemporaryAccess(targetPackage)) {
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
            }
        }
    }
}
