package com.vault.secretcamera.data

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.vault.secretcamera.model.IntruderLog
import com.vault.secretcamera.model.VaultCategory
import com.vault.secretcamera.model.VaultItem
import com.vault.secretcamera.security.CryptoManager
import com.vault.secretcamera.security.SecurityPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class VaultRepository(
    private val context: Context,
    private val securityPrefs: SecurityPreferences
) {

    private val vaultDir = File(context.filesDir, "vault_data").apply { mkdirs() }
    private val itemsDir = File(vaultDir, "items").apply { mkdirs() }
    private val intrudersDir = File(vaultDir, "intruders").apply { mkdirs() }
    private val indexFile = File(vaultDir, "vault_index.json")
    private val intruderIndexFile = File(vaultDir, "intruders_index.json")

    private val itemsList = mutableListOf<VaultItem>()
    private val intrudersList = mutableListOf<IntruderLog>()

    init {
        loadIndex()
        loadIntrudersIndex()
    }

    private fun loadIndex() {
        itemsList.clear()
        if (indexFile.exists()) {
            try {
                val jsonString = indexFile.readText(Charsets.UTF_8)
                val jsonArray = JSONArray(jsonString)
                for (i in 0 until jsonArray.length()) {
                    val item = VaultItem.fromJson(jsonArray.getJSONObject(i))
                    itemsList.add(item)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    @Synchronized
    private fun saveIndex() {
        try {
            val jsonArray = JSONArray()
            for (item in itemsList) {
                jsonArray.put(item.toJson())
            }
            indexFile.writeText(jsonArray.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadIntrudersIndex() {
        intrudersList.clear()
        if (intruderIndexFile.exists()) {
            try {
                val jsonString = intruderIndexFile.readText(Charsets.UTF_8)
                val jsonArray = JSONArray(jsonString)
                for (i in 0 until jsonArray.length()) {
                    val log = IntruderLog.fromJson(jsonArray.getJSONObject(i))
                    intrudersList.add(log)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    @Synchronized
    private fun saveIntrudersIndex() {
        try {
            val jsonArray = JSONArray()
            for (log in intrudersList) {
                jsonArray.put(log.toJson())
            }
            intruderIndexFile.writeText(jsonArray.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Get items filtered by category and decoy state
    fun getItems(category: VaultCategory): List<VaultItem> {
        val isDecoy = securityPrefs.isDecoySession
        return itemsList.filter { item ->
            item.isDecoy == isDecoy && (category == VaultCategory.ALL || item.category == category)
        }.sortedByDescending { it.dateAdded }
    }

    // Import a file from public storage / gallery into encrypted vault and DELETE original
    suspend fun importAndHideFile(uri: Uri): Result<VaultItem> = withContext(Dispatchers.IO) {
        val masterKey = securityPrefs.currentMasterKey
            ?: return@withContext Result.failure(IllegalStateException("الخزنة مقفلة"))

        try {
            val contentResolver = context.contentResolver
            val originalName = queryFileName(contentResolver, uri) ?: "file_${System.currentTimeMillis()}"
            val mimeType = contentResolver.getType(uri) ?: queryMimeType(originalName)
            val category = resolveCategory(mimeType, originalName)

            val itemId = UUID.randomUUID().toString()
            val encFileName = "$itemId.enc"
            val destEncFile = File(itemsDir, encFileName)

            // 1. Read and Encrypt directly to vault storage
            contentResolver.openInputStream(uri)?.use { inputStream ->
                CryptoManager.encryptStreamToFile(inputStream, destEncFile, masterKey)
            } ?: return@withContext Result.failure(IllegalStateException("تعذر قراءة الملف الأصلي"))

            val fileSize = destEncFile.length()

            // 2. Truly DELETE original file from Gallery / File Manager
            var deleted = false

            // Try Storage Access Framework (SAF) document deletion
            try {
                if (DocumentsContract.isDocumentUri(context, uri)) {
                    deleted = DocumentsContract.deleteDocument(contentResolver, uri)
                }
            } catch (_: Exception) {}

            // Try ContentResolver delete (MediaStore)
            if (!deleted) {
                try {
                    val deletedRows = contentResolver.delete(uri, null, null)
                    if (deletedRows > 0) {
                        deleted = true
                    }
                } catch (_: Exception) {}
            }

            // Also attempt direct file deletion by querying DATA column from MediaStore
            try {
                val projection = arrayOf(MediaStore.Images.Media.DATA)
                contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val colIdx = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
                        if (colIdx != -1) {
                            val filePath = cursor.getString(colIdx)
                            if (!filePath.isNullOrEmpty()) {
                                val rawFile = File(filePath)
                                if (rawFile.exists()) {
                                    rawFile.delete()
                                    MediaScannerConnection.scanFile(context, arrayOf(filePath), null, null)
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {}

            // Direct file deletion if uri has file scheme or real path
            if (uri.scheme == ContentResolver.SCHEME_FILE || uri.path != null) {
                try {
                    val rawFile = File(uri.path ?: "")
                    if (rawFile.exists()) {
                        rawFile.delete()
                        MediaScannerConnection.scanFile(context, arrayOf(rawFile.absolutePath), null, null)
                    }
                } catch (_: Exception) {}
            }

            val vaultItem = VaultItem(
                id = itemId,
                originalName = originalName,
                encryptedFileName = encFileName,
                category = category,
                mimeType = mimeType,
                fileSize = fileSize,
                dateAdded = System.currentTimeMillis(),
                isDecoy = securityPrefs.isDecoySession
            )

            itemsList.add(0, vaultItem)
            saveIndex()

            Result.success(vaultItem)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Direct Secret Camera Capture (Never touches public storage!)
    suspend fun saveSecretCapture(jpegBytes: ByteArray): Result<VaultItem> = withContext(Dispatchers.IO) {
        val masterKey = securityPrefs.currentMasterKey
            ?: return@withContext Result.failure(IllegalStateException("الخزنة مقفلة"))

        try {
            val itemId = UUID.randomUUID().toString()
            val encFileName = "$itemId.enc"
            val destEncFile = File(itemsDir, encFileName)

            ByteArrayInputStream(jpegBytes).use { bais ->
                CryptoManager.encryptStreamToFile(bais, destEncFile, masterKey)
            }

            val vaultItem = VaultItem(
                id = itemId,
                originalName = "IMG_SECRET_${System.currentTimeMillis()}.jpg",
                encryptedFileName = encFileName,
                category = VaultCategory.PHOTOS,
                mimeType = "image/jpeg",
                fileSize = destEncFile.length(),
                dateAdded = System.currentTimeMillis(),
                isDecoy = securityPrefs.isDecoySession
            )

            itemsList.add(0, vaultItem)
            saveIndex()

            Result.success(vaultItem)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Decrypt and Restore file back to Gallery / File Manager (Unhide)
    suspend fun restoreFileToStorage(item: VaultItem): Result<Uri> = withContext(Dispatchers.IO) {
        val masterKey = securityPrefs.currentMasterKey
            ?: return@withContext Result.failure(IllegalStateException("الخزنة مقفلة"))

        val encFile = File(itemsDir, item.encryptedFileName)
        if (!encFile.exists()) {
            return@withContext Result.failure(IllegalStateException("الملف المشفر غير موجود"))
        }

        try {
            val resolver = context.contentResolver
            val restoredUri: Uri

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val collection = when (item.category) {
                    VaultCategory.PHOTOS -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    VaultCategory.VIDEOS -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    VaultCategory.AUDIO -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    else -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                }

                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, item.originalName)
                    put(MediaStore.MediaColumns.MIME_TYPE, item.mimeType)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }

                val newUri = resolver.insert(collection, values)
                    ?: return@withContext Result.failure(IllegalStateException("تعذر إنشاء مدخل في المعرض"))

                resolver.openOutputStream(newUri)?.use { os ->
                    CryptoManager.decryptFileToStream(encFile, os, masterKey)
                }

                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(newUri, values, null, null)

                restoredUri = newUri
            } else {
                // Legacy Android
                val targetDir = when (item.category) {
                    VaultCategory.PHOTOS -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                    VaultCategory.VIDEOS -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                    VaultCategory.AUDIO -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                    else -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                }
                targetDir.mkdirs()
                val targetFile = File(targetDir, item.originalName)
                FileOutputStream(targetFile).use { fos ->
                    CryptoManager.decryptFileToStream(encFile, fos, masterKey)
                }

                MediaScannerConnection.scanFile(context, arrayOf(targetFile.absolutePath), null, null)
                restoredUri = Uri.fromFile(targetFile)
            }

            // Remove from vault after successful restoration
            encFile.delete()
            itemsList.remove(item)
            saveIndex()

            Result.success(restoredUri)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Permanently delete file from vault
    suspend fun deletePermanently(item: VaultItem): Boolean = withContext(Dispatchers.IO) {
        val encFile = File(itemsDir, item.encryptedFileName)
        if (encFile.exists()) {
            encFile.delete()
        }
        itemsList.remove(item)
        saveIndex()
        true
    }

    // Decrypt in-memory bytes for viewing photos / documents without writing plaintext to disk
    suspend fun getDecryptedBytes(item: VaultItem): ByteArray? = withContext(Dispatchers.IO) {
        val masterKey = securityPrefs.currentMasterKey ?: return@withContext null
        val encFile = File(itemsDir, item.encryptedFileName)
        if (!encFile.exists()) return@withContext null

        try {
            CryptoManager.decryptFileToBytes(encFile, masterKey)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // Decrypt to temporary secure cache for video player
    suspend fun getTempDecryptedVideo(item: VaultItem): File? = withContext(Dispatchers.IO) {
        val masterKey = securityPrefs.currentMasterKey ?: return@withContext null
        val encFile = File(itemsDir, item.encryptedFileName)
        if (!encFile.exists()) return@withContext null

        try {
            val tempDir = File(context.cacheDir, "vault_temp").apply { mkdirs() }
            val tempFile = File(tempDir, "temp_play_${item.id}.mp4")
            tempFile.deleteOnExit()

            FileOutputStream(tempFile).use { fos ->
                CryptoManager.decryptFileToStream(encFile, fos, masterKey)
            }
            tempFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // Save Intruder Photo
    suspend fun saveIntruderPhoto(jpegBytes: ByteArray): IntruderLog = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val photoFile = File(intrudersDir, "intruder_$id.jpg")
        FileOutputStream(photoFile).use { fos ->
            fos.write(jpegBytes)
        }

        val log = IntruderLog(
            id = id,
            photoFileName = photoFile.name,
            timestamp = System.currentTimeMillis()
        )
        intrudersList.add(0, log)
        saveIntrudersIndex()
        log
    }

    fun getIntruders(): List<IntruderLog> = intrudersList.toList()

    fun getIntruderFile(log: IntruderLog): File = File(intrudersDir, log.photoFileName)

    fun deleteIntruder(log: IntruderLog) {
        val file = File(intrudersDir, log.photoFileName)
        if (file.exists()) file.delete()
        intrudersList.remove(log)
        saveIntrudersIndex()
    }

    // Helpers
    private fun queryFileName(resolver: ContentResolver, uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            try {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val colIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (colIdx != -1) {
                            name = cursor.getString(colIdx)
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        if (name == null) {
            name = uri.lastPathSegment
        }
        return name
    }

    private fun queryMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "")
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase())
            ?: "application/octet-stream"
    }

    private fun resolveCategory(mimeType: String, fileName: String): VaultCategory {
        val lowerMime = mimeType.lowercase()
        val ext = fileName.substringAfterLast('.', "").lowercase()

        return when {
            lowerMime.startsWith("image/") || ext in listOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic") -> VaultCategory.PHOTOS
            lowerMime.startsWith("video/") || ext in listOf("mp4", "mkv", "mov", "avi", "3gp", "webm") -> VaultCategory.VIDEOS
            lowerMime.startsWith("audio/") || ext in listOf("mp3", "m4a", "wav", "aac", "ogg") -> VaultCategory.AUDIO
            lowerMime.startsWith("application/pdf") || ext in listOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt") -> VaultCategory.DOCUMENTS
            else -> VaultCategory.OTHER
        }
    }
}
