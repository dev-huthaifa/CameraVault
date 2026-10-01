package com.vault.secretcamera.ui.viewer

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.vault.secretcamera.R
import com.vault.secretcamera.SecretVaultApp
import com.vault.secretcamera.ads.AdManager
import com.vault.secretcamera.data.VaultRepository
import com.vault.secretcamera.databinding.ActivityMediaViewerBinding
import com.vault.secretcamera.model.VaultCategory
import com.vault.secretcamera.model.VaultItem
import com.vault.secretcamera.security.SecurityPreferences
import com.vault.secretcamera.util.SecureShareHelper
import com.vault.secretcamera.util.SecurityAuditDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MediaViewerActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityMediaViewerBinding
    private lateinit var repository: VaultRepository
    private lateinit var securityPrefs: SecurityPreferences
    private var currentItem: VaultItem? = null
    private var tempVideoFile: File? = null
    private var isSharing = false

    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var proximitySensor: Sensor? = null
    private var isProximityNear = false
    private var lastFlipTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMediaViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val app = application as SecretVaultApp
        repository = app.vaultRepository
        securityPrefs = app.securityPreferences

        if (!securityPrefs.isUnlocked()) {
            finish()
            return
        }

        if (securityPrefs.isAntiScreenshotEnabled) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        }

        val itemId = intent.getStringExtra(EXTRA_ITEM_ID)
        currentItem = repository.getItems(VaultCategory.ALL).firstOrNull { it.id == itemId }

        if (currentItem == null) {
            Toast.makeText(this, "الملف غير موجود", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        proximitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)

        setupToolbar()
        setupActions()
        displayMedia()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.viewerToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = currentItem?.originalName ?: "معاينة آمنة"
        binding.viewerToolbar.setNavigationOnClickListener {
            AdManager.showInterstitial(this) {
                finish()
            }
        }
    }

    override fun onBackPressed() {
        AdManager.showInterstitial(this) {
            super.onBackPressed()
        }
    }

    private fun setupActions() {
        // Restore to Gallery / File Manager
        binding.btnRestoreViewer.setOnClickListener {
            val item = currentItem ?: return@setOnClickListener
            AlertDialog.Builder(this)
                .setTitle(R.string.restore_confirm_title)
                .setMessage(R.string.restore_confirm_message)
                .setPositiveButton(R.string.btn_restore) { _, _ ->
                    restoreCurrentItem(item)
                }
                .setNegativeButton(R.string.btn_cancel, null)
                .show()
        }

        // Live Cryptographic Audit for Current File
        binding.btnAuditViewer.setOnClickListener {
            val item = currentItem ?: return@setOnClickListener
            SecurityAuditDialog.show(this, repository, securityPrefs, item)
        }

        // Delete Permanently
        binding.btnDeleteViewer.setOnClickListener {
            val item = currentItem ?: return@setOnClickListener
            AlertDialog.Builder(this)
                .setTitle(R.string.delete_confirm_title)
                .setMessage(R.string.delete_confirm_message)
                .setPositiveButton(R.string.btn_delete) { _, _ ->
                    deleteCurrentItem(item)
                }
                .setNegativeButton(R.string.btn_cancel, null)
                .show()
        }

        // Secure Share via FileProvider
        binding.btnShareViewer.setOnClickListener {
            shareCurrentItem()
        }
    }

    private fun displayMedia() {
        val item = currentItem ?: return
        binding.progressBarViewer.visibility = View.VISIBLE

        CoroutineScope(Dispatchers.IO).launch {
            when (item.category) {
                VaultCategory.PHOTOS -> {
                    val bytes = repository.getDecryptedBytes(item)
                    withContext(Dispatchers.Main) {
                        binding.progressBarViewer.visibility = View.GONE
                        if (bytes != null) {
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            binding.ivFullImage.setImageBitmap(bitmap)
                            binding.ivFullImage.visibility = View.VISIBLE
                        } else {
                            Toast.makeText(this@MediaViewerActivity, "تعذر فك تشفير الصورة", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                VaultCategory.VIDEOS -> {
                    tempVideoFile = repository.getTempDecryptedVideo(item)
                    withContext(Dispatchers.Main) {
                        binding.progressBarViewer.visibility = View.GONE
                        if (tempVideoFile != null) {
                            binding.videoContainer.visibility = View.VISIBLE
                            binding.videoView.setVideoPath(tempVideoFile!!.absolutePath)
                            binding.btnPlayPause.setOnClickListener {
                                if (binding.videoView.isPlaying) {
                                    binding.videoView.pause()
                                    binding.btnPlayPause.setImageResource(R.drawable.ic_video)
                                } else {
                                    binding.videoView.start()
                                    binding.btnPlayPause.setImageResource(R.drawable.ic_photo)
                                }
                            }
                            binding.videoView.start()
                        }
                    }
                }

                else -> {
                    val bytes = repository.getDecryptedBytes(item)
                    withContext(Dispatchers.Main) {
                        binding.progressBarViewer.visibility = View.GONE
                        binding.documentContainer.visibility = View.VISIBLE
                        if (bytes != null) {
                            try {
                                val textPreview = String(bytes.take(4096).toByteArray(), Charsets.UTF_8)
                                binding.tvDocumentContent.text = "محتوى الملف المشفر:\n\n$textPreview"
                            } catch (_: Exception) {
                                binding.tvDocumentContent.text = "ملف مستند مشفر (${item.originalName})\nالحجم: ${item.fileSize} بايت"
                            }
                        }
                    }
                }
            }
        }
    }

    private fun restoreCurrentItem(item: VaultItem) {
        CoroutineScope(Dispatchers.IO).launch {
            val result = repository.restoreFileToStorage(item)
            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    Toast.makeText(this@MediaViewerActivity, R.string.file_restored_success, Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    Toast.makeText(this@MediaViewerActivity, "فشل الاستعادة: ${result.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun deleteCurrentItem(item: VaultItem) {
        CoroutineScope(Dispatchers.IO).launch {
            repository.deletePermanently(item)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MediaViewerActivity, "تم الحذف النهائي", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun shareCurrentItem() {
        val item = currentItem ?: return
        SecureShareHelper.showShareDialog(this, repository, listOf(item)) {
            isSharing = true
        }
    }

    // Flip to Lock (Dual Sensor: Accelerometer + Proximity)
    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || !securityPrefs.isFlipToLockEnabled) return

        if (event.sensor.type == Sensor.TYPE_PROXIMITY) {
            val maxRange = event.sensor.maximumRange
            val distance = event.values[0]
            isProximityNear = distance < 4.0f && distance < maxRange
        } else if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val z = event.values[2]
            val isFlipped = (z < -5.0f && isProximityNear) || (z < -8.5f)
            if (isFlipped) {
                val now = System.currentTimeMillis()
                if (now - lastFlipTime > 1500L) {
                    lastFlipTime = now
                    triggerEmergencyLock()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun triggerEmergencyLock() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(70)
            }
        } catch (_: Exception) {}

        securityPrefs.lockVault()
        finish()
    }

    override fun onResume() {
        super.onResume()
        if (!securityPrefs.isUnlocked()) {
            finish()
            return
        }
        isSharing = false
        if (securityPrefs.isFlipToLockEnabled) {
            accelerometer?.let {
                sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            }
            proximitySensor?.let {
                sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(this)
    }

    override fun onStop() {
        super.onStop()
        if (!isSharing && !isChangingConfigurations && !AdManager.isAdShowing) {
            securityPrefs.lockVault()
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tempVideoFile?.delete()
    }

    companion object {
        const val EXTRA_ITEM_ID = "extra_item_id"
    }
}
