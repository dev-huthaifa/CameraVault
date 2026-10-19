package com.vault.secretcamera.service

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.vault.secretcamera.SecretVaultApp
import com.vault.secretcamera.model.VaultCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * مدير دمج «استديو الخزنة» المباشر داخل الاستديو العام للهاتف (Native Album Bridge):
 * بدلاً من إزعاج المستخدم بزر عائم فوق الشاشة:
 * 1. فور دخول المستخدم لتطبيق محمي داخل الجوال الثاني (مثل X أو إنستغرام أو واتساب)،
 *    يقوم هذا المدير بتركيب ألبوم أصلي باسم «استديو الخزنة» داخل الاستديو العام (MediaStore)
 *    بحيث يظهر مباشرة داخل معرض التطبيق نفسه كألبوم مستقل وفي أعلى الصور الحديثة بدون أي أزرار عائمة.
 * 2. فور خروج المستخدم للشاشة الرئيسية أو قفل الشاشة أو إغلاق الخزنة، يتم مسح وإخفاء ألبوم «استديو الخزنة»
 *    بالكامل من الاستديو العام في أجزاء من الثانية، مع بقاء الملفات الأصلية مشفرة بأمان داخل الخزنة.
 */
object VaultStudioMountManager {

    private const val PREFS_NAME = "vault_studio_mount_journal"
    private const val KEY_MOUNTED_URIS = "key_mounted_uris"
    private const val KEY_MOUNTED_PATHS = "key_mounted_paths"
    private const val ALBUM_NAME = "استديو الخزنة"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isMounting = AtomicBoolean(false)
    @Volatile
    private var isCurrentlyMounted = false

    /**
     * Mounts all Vault photos and videos into the native General Studio under the album "استديو الخزنة"
     * for the duration of the active Second Phone app session.
     */
    fun mountForActiveSession(context: Context) {
        val appCtx = context.applicationContext as? SecretVaultApp ?: return
        if (isCurrentlyMounted || !isMounting.compareAndSet(false, true)) return

        scope.launch {
            try {
                // Ensure master key is unlocked
                if (!appCtx.securityPreferences.isUnlocked()) {
                    appCtx.securityPreferences.unlockWithBiometric()
                }

                // Clean any stale leftovers before fresh mount
                unmountSynchronously(appCtx)

                val repo = appCtx.vaultRepository
                val isDecoy = appCtx.securityPreferences.isDecoySession
                val photos = repo.getAllItems(VaultCategory.PHOTOS, isDecoy)
                val videos = repo.getAllItems(VaultCategory.VIDEOS, isDecoy)
                val allMedia = (photos + videos).sortedBy { it.dateAdded }

                if (allMedia.isEmpty()) {
                    isCurrentlyMounted = true
                    return@launch
                }

                val mountedUris = mutableSetOf<String>()
                val mountedPaths = mutableSetOf<String>()
                val baseNowMillis = System.currentTimeMillis()

                for ((index, item) in allMedia.withIndex()) {
                    val bytes = repo.getDecryptedBytes(item) ?: continue
                    val isVideo = item.category == VaultCategory.VIDEOS || item.mimeType.startsWith("video/")
                    val ext = item.originalName.substringAfterLast(
                        '.',
                        if (isVideo) "mp4" else "jpg"
                    )
                    val safeTitle = item.originalName.substringBeforeLast('.').replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    val displayName = "Vault_${index}_${safeTitle}.$ext"
                    val mime = item.mimeType.ifBlank {
                        if (isVideo) "video/mp4" else "image/jpeg"
                    }

                    // Stagger timestamps slightly so ordering is preserved and all sit at the very top of Recent
                    val itemTimeMillis = baseNowMillis + (index * 1000L)
                    val itemTimeSeconds = itemTimeMillis / 1000L

                    val collection = if (isVideo) {
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    } else {
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    }

                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val values = ContentValues().apply {
                                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                                put(MediaStore.MediaColumns.DATE_ADDED, itemTimeSeconds)
                                put(MediaStore.MediaColumns.DATE_MODIFIED, itemTimeSeconds)
                                put(MediaStore.MediaColumns.DATE_TAKEN, itemTimeMillis)
                                put(
                                    MediaStore.MediaColumns.RELATIVE_PATH,
                                    Environment.DIRECTORY_DCIM + "/" + ALBUM_NAME
                                )
                                put(MediaStore.MediaColumns.IS_PENDING, 1)
                            }

                            val uri = appCtx.contentResolver.insert(collection, values)
                            if (uri != null) {
                                // Save URI to crash-safe journal immediately before writing bytes
                                mountedUris.add(uri.toString())
                                saveJournal(appCtx, mountedUris, mountedPaths)

                                appCtx.contentResolver.openOutputStream(uri)?.use { out ->
                                    out.write(bytes)
                                    out.flush()
                                }

                                val publishValues = ContentValues().apply {
                                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                                    put(MediaStore.MediaColumns.DATE_ADDED, itemTimeSeconds)
                                    put(MediaStore.MediaColumns.DATE_MODIFIED, itemTimeSeconds)
                                }
                                appCtx.contentResolver.update(uri, publishValues, null, null)
                            }
                        } else {
                            @Suppress("DEPRECATION")
                            val albumDir = File(
                                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                                ALBUM_NAME
                            ).apply { mkdirs() }
                            // Ensure no .nomedia blocks visibility while mounted
                            File(albumDir, ".nomedia").let { if (it.exists()) it.delete() }

                            val targetFile = File(albumDir, displayName)
                            mountedPaths.add(targetFile.absolutePath)
                            saveJournal(appCtx, mountedUris, mountedPaths)

                            FileOutputStream(targetFile).use { out ->
                                out.write(bytes)
                                out.flush()
                            }

                            @Suppress("DEPRECATION")
                            val values = ContentValues().apply {
                                put(MediaStore.MediaColumns.DATA, targetFile.absolutePath)
                                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                                put(MediaStore.MediaColumns.DATE_ADDED, itemTimeSeconds)
                                put(MediaStore.MediaColumns.DATE_MODIFIED, itemTimeSeconds)
                                put(MediaStore.Images.Media.DATE_TAKEN, itemTimeMillis)
                            }
                            val uri = appCtx.contentResolver.insert(collection, values)
                            if (uri != null) {
                                mountedUris.add(uri.toString())
                                saveJournal(appCtx, mountedUris, mountedPaths)
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                if (mountedPaths.isNotEmpty()) {
                    MediaScannerConnection.scanFile(
                        appCtx,
                        mountedPaths.toTypedArray(),
                        null,
                        null
                    )
                }

                notifyMediaStoreChanged(appCtx)
                isCurrentlyMounted = true
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isMounting.set(false)
            }
        }
    }

    /**
     * Immediately unmounts and erases the temporary "استديو الخزنة" album from MediaStore & disk
     * when the user exits the vaulted app to the Home Screen, locks the screen, or closes the Vault.
     */
    fun unmountFromGeneralStudio(context: Context) {
        val appCtx = context.applicationContext
        isCurrentlyMounted = false
        scope.launch {
            unmountSynchronously(appCtx)
        }
    }

    @Synchronized
    fun unmountSynchronously(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val uris = prefs.getStringSet(KEY_MOUNTED_URIS, emptySet()) ?: emptySet()
            val paths = prefs.getStringSet(KEY_MOUNTED_PATHS, emptySet()) ?: emptySet()

            var deletedAny = false

            for (uriStr in uris) {
                try {
                    val uri = Uri.parse(uriStr)
                    context.contentResolver.delete(uri, null, null)
                    deletedAny = true
                } catch (_: Exception) {}
            }

            for (path in paths) {
                try {
                    val f = File(path)
                    if (f.exists()) {
                        f.delete()
                        deletedAny = true
                    }
                } catch (_: Exception) {}
            }

            // Also sweep any MediaStore entries owned by our app inside the "استديو الخزنة" bucket
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val where = "${MediaStore.MediaColumns.BUCKET_DISPLAY_NAME} = ?"
                    val args = arrayOf(ALBUM_NAME)
                    val d1 = context.contentResolver.delete(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, where, args)
                    val d2 = context.contentResolver.delete(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, where, args)
                    if (d1 > 0 || d2 > 0) deletedAny = true
                } else {
                    @Suppress("DEPRECATION")
                    val albumDir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                        ALBUM_NAME
                    )
                    if (albumDir.exists()) {
                        albumDir.listFiles()?.forEach { it.delete() }
                        albumDir.delete()
                        deletedAny = true
                    }
                }
            } catch (_: Exception) {}

            prefs.edit().clear().apply()

            if (deletedAny) {
                notifyMediaStoreChanged(context)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveJournal(context: Context, uris: Set<String>, paths: Set<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_MOUNTED_URIS, HashSet(uris))
            .putStringSet(KEY_MOUNTED_PATHS, HashSet(paths))
            .commit()
    }

    private fun notifyMediaStoreChanged(context: Context) {
        try {
            context.contentResolver.notifyChange(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, null)
            context.contentResolver.notifyChange(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, null)
        } catch (_: Exception) {}
    }
}
