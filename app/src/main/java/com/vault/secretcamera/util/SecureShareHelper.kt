package com.vault.secretcamera.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.vault.secretcamera.R
import com.vault.secretcamera.data.VaultRepository
import com.vault.secretcamera.databinding.DialogQuickShareBinding
import com.vault.secretcamera.model.VaultCategory
import com.vault.secretcamera.model.VaultItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object SecureShareHelper {

    fun showShareDialog(
        activity: AppCompatActivity,
        repository: VaultRepository,
        items: List<VaultItem>,
        onShareLaunched: () -> Unit = {}
    ) {
        if (items.isEmpty()) return

        val dialog = BottomSheetDialog(activity)
        val binding = DialogQuickShareBinding.inflate(activity.layoutInflater)
        dialog.setContentView(binding.root)

        val countText = if (items.size == 1) {
            "مشاركة ملف سري واحد"
        } else {
            "مشاركة ${items.size} ملفات محددة"
        }
        binding.tvQuickShareTitle.text = countText

        // 1. WhatsApp
        binding.optShareWhatsApp.setOnClickListener {
            dialog.dismiss()
            onShareLaunched()
            executeShare(activity, repository, items, TargetApp.WHATSAPP)
        }

        // 2. Instagram
        binding.optShareInstagram.setOnClickListener {
            dialog.dismiss()
            onShareLaunched()
            executeShare(activity, repository, items, TargetApp.INSTAGRAM)
        }

        // 3. Telegram
        binding.optShareTelegram.setOnClickListener {
            dialog.dismiss()
            onShareLaunched()
            executeShare(activity, repository, items, TargetApp.TELEGRAM)
        }

        // 4. All Other Apps (System Chooser)
        binding.optShareAllApps.setOnClickListener {
            dialog.dismiss()
            onShareLaunched()
            executeShare(activity, repository, items, TargetApp.ALL_APPS)
        }

        dialog.show()
    }

    enum class TargetApp {
        WHATSAPP, INSTAGRAM, TELEGRAM, ALL_APPS
    }

    private fun executeShare(
        activity: AppCompatActivity,
        repository: VaultRepository,
        items: List<VaultItem>,
        target: TargetApp
    ) {
        Toast.makeText(activity, "جاري تحضير الملفات للمشاركة...", Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.IO).launch {
            cleanOldShareCache(activity)
            val uris = mutableListOf<Uri>()
            val shareDir = File(activity.cacheDir, "secure_share").apply { mkdirs() }

            for (item in items) {
                val bytes = repository.getDecryptedBytes(item) ?: continue
                val safeName = sanitizeFileName(item.originalName)
                val tempFile = File(shareDir, safeName)
                FileOutputStream(tempFile).use { it.write(bytes) }

                val uri = FileProvider.getUriForFile(
                    activity.applicationContext,
                    "${activity.packageName}.provider",
                    tempFile
                )
                uris.add(uri)
            }

            withContext(Dispatchers.Main) {
                if (uris.isEmpty()) {
                    Toast.makeText(activity, "تعذر فك تشفير الملفات للمشاركة", Toast.LENGTH_SHORT).show()
                    return@withContext
                }

                launchShareIntent(activity, items, uris, target)
            }
        }
    }

    private fun launchShareIntent(
        context: Context,
        items: List<VaultItem>,
        uris: List<Uri>,
        target: TargetApp
    ) {
        val mimeType = resolveCommonMimeType(items)
        val isSingle = uris.size == 1

        val intent = Intent(if (isSingle) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = mimeType
            if (isSingle) {
                putExtra(Intent.EXTRA_STREAM, uris.first())
            } else {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }

            val clipData = ClipData.newUri(context.contentResolver, "shared_media", uris.first())
            for (i in 1 until uris.size) {
                clipData.addItem(ClipData.Item(uris[i]))
            }
            this.clipData = clipData

            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val pkgName = when (target) {
            TargetApp.WHATSAPP -> resolveInstalledPackage(context, listOf("com.whatsapp", "com.whatsapp.w4b"))
            TargetApp.INSTAGRAM -> resolveInstalledPackage(context, listOf("com.instagram.android"))
            TargetApp.TELEGRAM -> resolveInstalledPackage(context, listOf("org.telegram.messenger", "org.thunderdog.challegram"))
            TargetApp.ALL_APPS -> null
        }

        try {
            if (pkgName != null) {
                intent.setPackage(pkgName)
                for (uri in uris) {
                    context.grantUriPermission(pkgName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(intent)
            } else {
                if (target != TargetApp.ALL_APPS) {
                    Toast.makeText(context, "التطبيق المختار غير مثبت، جاري فتح قائمة المشاركة الشاملة", Toast.LENGTH_SHORT).show()
                }
                val chooser = Intent.createChooser(intent, context.getString(R.string.btn_share))
                val resInfoList = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                for (resolveInfo in resInfoList) {
                    val pName = resolveInfo.activityInfo.packageName
                    for (uri in uris) {
                        context.grantUriPermission(pName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }
                context.startActivity(chooser)
            }
        } catch (e: Exception) {
            try {
                intent.setPackage(null)
                val chooser = Intent.createChooser(intent, context.getString(R.string.btn_share))
                val resInfoList = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                for (resolveInfo in resInfoList) {
                    val pName = resolveInfo.activityInfo.packageName
                    for (uri in uris) {
                        context.grantUriPermission(pName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }
                context.startActivity(chooser)
            } catch (ex: Exception) {
                Toast.makeText(context, "تعذر فتح المشاركة: ${ex.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun resolveInstalledPackage(context: Context, packages: List<String>): String? {
        val pm = context.packageManager
        for (pkg in packages) {
            try {
                pm.getPackageInfo(pkg, 0)
                return pkg
            } catch (_: PackageManager.NameNotFoundException) {}
        }
        return null
    }

    private fun resolveCommonMimeType(items: List<VaultItem>): String {
        val allImages = items.all { it.category == VaultCategory.PHOTOS }
        if (allImages) return "image/*"

        val allVideos = items.all { it.category == VaultCategory.VIDEOS }
        if (allVideos) return "video/*"

        val allAudio = items.all { it.category == VaultCategory.AUDIO }
        if (allAudio) return "audio/*"

        if (items.size == 1) return items.first().mimeType

        return "*/*"
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }

    /**
     * Safely cleans cache files older than 15 minutes, preserving currently shared files
     */
    fun cleanOldShareCache(context: Context) {
        try {
            val shareDir = File(context.cacheDir, "secure_share")
            if (shareDir.exists()) {
                val now = System.currentTimeMillis()
                shareDir.listFiles()?.forEach { file ->
                    if (now - file.lastModified() > 15 * 60 * 1000L) {
                        file.delete()
                    }
                }
            }
        } catch (_: Exception) {}
    }
}
