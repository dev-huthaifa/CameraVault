package com.vault.secretcamera.ui.viewer

import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.vault.secretcamera.R
import com.vault.secretcamera.SecretVaultApp
import com.vault.secretcamera.ads.AdManager
import com.vault.secretcamera.data.VaultRepository
import com.vault.secretcamera.databinding.ActivityMediaViewerBinding
import com.vault.secretcamera.model.VaultCategory
import com.vault.secretcamera.model.VaultItem
import com.vault.secretcamera.security.SecurityPreferences
import com.vault.secretcamera.util.SecureShareHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MediaViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMediaViewerBinding
    private lateinit var repository: VaultRepository
    private lateinit var securityPrefs: SecurityPreferences
    private var currentItem: VaultItem? = null
    private var tempVideoFile: File? = null

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
        isSharing = true
        SecureShareHelper.showShareDialog(this, repository, listOf(item))
    }

    private var isSharing = false

    override fun onResume() {
        super.onResume()
        if (!securityPrefs.isUnlocked()) {
            finish()
            return
        }
        isSharing = false
    }

    override fun onStop() {
        super.onStop()
        if (!isSharing && !isChangingConfigurations) {
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
