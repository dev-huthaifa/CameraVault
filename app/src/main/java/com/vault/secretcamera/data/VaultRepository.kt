package com.vault.secretcamera.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.LruCache
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.crypto.SecretKey

class VaultRepository(
    private val context: Context,
    private val securityPrefs: SecurityPreferences
) {

    private val vaultDir = File(context.filesDir, "vault_data").apply { mkdirs() }
    private val itemsDir = File(vaultDir, "items").apply { mkdirs() }
    private val thumbsDir = File(vaultDir, "thumbnails").apply { mkdirs() }
    private val intrudersDir = File(vaultDir, "intruders").apply { mkdirs() }
    private val indexFile = File(vaultDir, "vault_index.json")
    private val intruderIndexFile = File(vaultDir, "intruders_index.json")

    private val itemsList = mutableListOf<VaultItem>()
    private val intrudersList = mutableListOf<IntruderLog>()

    // High performance in-memory thumbnail cache (35MB limit) to guarantee 60fps scrolling
    private val maxCacheMemory = (Runtime.getRuntime().maxMemory() / 8).toInt().coerceAtMost(35 * 1024 * 1024)
    private val thumbnailCache = object : LruCache<String, Bitmap>(maxCacheMemory) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount
        }
    }

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

    fun getItems(category: VaultCategory): List<VaultItem> {
        val isDecoy = securityPrefs.isDecoySession
        return itemsList.filter { item ->
            item.isDecoy == isDecoy && (category == VaultCategory.ALL || item.category == category)
        }.sortedByDescending { it.dateAdded }
    }

    /**
     * Instantaneous in-memory thumbnail check (0 milliseconds)
     */
    fun getCachedThumbnail(itemId: String): Bitmap? {
        return thumbnailCache.get(itemId)
    }

    /**
     * Efficiently retrieves or decrypts lightweight 15KB thumbnail bitmap
     */
    suspend fun getThumbnailBitmap(item: VaultItem): Bitmap? = withContext(Dispatchers.IO) {
        val masterKey = securityPrefs.currentMasterKey ?: return@withContext null

        // 1. In-memory check
        thumbnailCache.get(item.id)?.let { return@withContext it }

        // 2. Check pre-encrypted 15KB thumbnail file
        val thumbFile = File(thumbsDir, "thumb_${item.id}.enc")
        if (thumbFile.exists()) {
            try {
                val bytes = CryptoManager.decryptFileToBytes(thumbFile, masterKey)
                if (bytes != null) {
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null) {
                        thumbnailCache.put(item.id, bmp)
                        return@withContext bmp
                    }
                }
            } catch (_: Exception) {}
        }

        // 3. Fallback: generate and save thumbnail on demand for legacy items
        if (item.category == VaultCategory.PHOTOS) {
            val fullBytes = getDecryptedBytes(item) ?: return@withContext null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(fullBytes, 0, fullBytes.size, bounds)
            val sample = calculateInSampleSize(bounds, 280, 280)
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeByteArray(fullBytes, 0, fullBytes.size, opts)
            if (bmp != null) {
                thumbnailCache.put(item.id, bmp)
                saveEncryptedThumbnail(item.id, bmp, masterKey)
                return@withContext bmp
            }
        }
        null
    }

    private fun saveEncryptedThumbnail(itemId: String, bitmap: Bitmap, key: SecretKey) {
        try {
            val thumbFile = File(thumbsDir, "thumb_${itemId}.enc")
            ByteArrayOutputStream().use { baos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 75, baos)
                val bytes = baos.toByteArray()
                ByteArrayInputStream(bytes).use { bais ->
                    CryptoManager.encryptStreamToFile(bais, thumbFile, key)
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * Batch import with real-time progress and single JSON save
     */
    suspend fun importBatch(
        uris: List<Uri>,
        onProgress: suspend (current: Int, total: Int) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        var count = 0
        for ((index, uri) in uris.withIndex()) {
            val res = importSingleFileInternal(uri, saveIndexAtEnd = false)
            if (res.isSuccess) {
                count++
            }
            onProgress(index + 1, uris.size)
        }
        saveIndex()
        count
    }

    suspend fun importAndHideFile(uri: Uri): Result<VaultItem> = withContext(Dispatchers.IO) {
        importSingleFileInternal(uri, saveIndexAtEnd = true)
    }

    private fun importSingleFileInternal(uri: Uri, saveIndexAtEnd: Boolean): Result<VaultItem> {
        val masterKey = securityPrefs.currentMasterKey
            ?: return Result.failure(IllegalStateException("الخزنة مقفلة"))

        try {
            val contentResolver = context.contentResolver
            val originalName = queryFileName(contentResolver, uri) ?: "file_${System.currentTimeMillis()}"
            val mimeType = contentResolver.getType(uri) ?: queryMimeType(originalName)
            val category = resolveCategory(mimeType, originalName)

            val itemId = UUID.randomUUID().toString()
            val encFileName = "$itemId.enc"
            val destEncFile = File(itemsDir, encFileName)

            // 1. Read & Encrypt full file
            contentResolver.openInputStream(uri)?.use { inputStream ->
                CryptoManager.encryptStreamToFile(inputStream, destEncFile, masterKey)
            } ?: return Result.failure(IllegalStateException("تعذر قراءة الملف الأصلي"))

            val fileSize = destEncFile.length()

            // 2. Generate and encrypt lightweight thumbnail (approx 15KB)
            if (category == VaultCategory.PHOTOS) {
                val thumbBmp = createSampledBitmap(contentResolver, uri, 280, 280)
                if (thumbBmp != null) {
                    thumbnailCache.put(itemId, thumbBmp)
                    saveEncryptedThumbnail(itemId, thumbBmp, masterKey)
                }
            } else if (category == VaultCategory.VIDEOS) {
                val videoThumb = createVideoThumbnail(context, uri)
                if (videoThumb != null) {
                    thumbnailCache.put(itemId, videoThumb)
                    saveEncryptedThumbnail(itemId, videoThumb, masterKey)
                }
            }

            // 3. UNCOMPROMISING PERMANENT DELETION (From Gallery, Disk & Recycle Bin)
            purgeOriginalFile(uri, originalName)

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
            if (saveIndexAtEnd) {
                saveIndex()
            }

            return Result.success(vaultItem)
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }

    /**
     * Deep deletion ensuring original is wiped from Gallery, Disk, SAF, and Trash/Recycle Bin
     */
    private fun purgeOriginalFile(uri: Uri, originalName: String) {
        val contentResolver = context.contentResolver
        var realPath: String? = null

        // A. Resolve real disk path via MediaColumns.DATA
        try {
            val projection = arrayOf(MediaStore.MediaColumns.DATA)
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    if (idx != -1) {
                        realPath = cursor.getString(idx)
                    }
                }
            }
        } catch (_: Exception) {}

        // B. Resolve path from SAF tree if applicable
        if (realPath.isNullOrEmpty() && DocumentsContract.isDocumentUri(context, uri)) {
            try {
                val docId = DocumentsContract.getDocumentId(uri)
                if (docId.startsWith("primary:")) {
                    val relative = docId.removePrefix("primary:")
                    realPath = File(Environment.getExternalStorageDirectory(), relative).absolutePath
                }
            } catch (_: Exception) {}
        }

        // C. Direct SAF Document deletion
        try {
            if (DocumentsContract.isDocumentUri(context, uri)) {
                DocumentsContract.deleteDocument(contentResolver, uri)
            }
        } catch (_: Exception) {}

        // D. ContentResolver MediaStore deletion
        try {
            contentResolver.delete(uri, null, null)
        } catch (_: Exception) {}

        // E. Direct File System deletion (Destroys the physical file)
        if (!realPath.isNullOrEmpty()) {
            try {
                val file = File(realPath!!)
                if (file.exists()) {
                    file.delete()
                }
                MediaScannerConnection.scanFile(context, arrayOf(realPath!!), null, null)
            } catch (_: Exception) {}
        }

        // F. Direct file deletion if URI has file:// scheme
        if (uri.scheme == ContentResolver.SCHEME_FILE && uri.path != null) {
            try {
                val file = File(uri.path!!)
                if (file.exists()) file.delete()
                MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
            } catch (_: Exception) {}
        }

        // G. Purge from Gallery Trash / Recycle Bin folders
        purgeTrashFiles(originalName, realPath)
    }

    /**
     * Purges files from standard OEM & MediaStore Trash / Recycle Bin
     */
    private fun purgeTrashFiles(originalName: String, realPath: String?) {
        try {
            val baseName = if (!realPath.isNullOrEmpty()) File(realPath!!).name else originalName
            val searchDirs = listOfNotNull(
                Environment.getExternalStorageDirectory(),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            )

            for (dir in searchDirs) {
                // Check .trash directory
                val trashDir = File(dir, ".trash")
                if (trashDir.exists() && trashDir.isDirectory) {
                    trashDir.listFiles()?.forEach { tf ->
                        if (tf.name.contains(baseName) || tf.name.contains(originalName)) {
                            tf.delete()
                            MediaScannerConnection.scanFile(context, arrayOf(tf.absolutePath), null, null)
                        }
                    }
                }
                // Check MIUI/Samsung cloud trash bins
                val miuiTrash = File(dir, "cloud/trashBin")
                if (miuiTrash.exists() && miuiTrash.isDirectory) {
                    miuiTrash.listFiles()?.forEach { tf ->
                        if (tf.name.contains(baseName)) {
                            tf.delete()
                        }
                    }
                }
                // Check .trashed-* files directly in media folder
                dir.listFiles()?.forEach { f ->
                    if (f.name.startsWith(".trashed") && f.name.contains(baseName)) {
                        f.delete()
                        MediaScannerConnection.scanFile(context, arrayOf(f.absolutePath), null, null)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun createSampledBitmap(resolver: ContentResolver, uri: Uri, reqW: Int, reqH: Int): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            options.inSampleSize = calculateInSampleSize(options, reqW, reqH)
            options.inJustDecodeBounds = false
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        } catch (_: Exception) {
            null
        }
    }

    private fun createVideoThumbnail(context: Context, uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val frame = retriever.getFrameAtTime(1000000) // Frame at 1 sec
            if (frame != null) {
                val scale = 280f / frame.width.coerceAtLeast(1)
                val targetH = (frame.height * scale).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(frame, 280, targetH, true)
            } else null
        } catch (_: Exception) {
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize.coerceAtLeast(1)
    }

    // Direct Secret Camera Capture
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

            // Create thumbnail
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, bounds)
            val sample = calculateInSampleSize(bounds, 280, 280)
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val thumbBmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, opts)
            if (thumbBmp != null) {
                thumbnailCache.put(itemId, thumbBmp)
                saveEncryptedThumbnail(itemId, thumbBmp, masterKey)
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

    // Decrypt and Restore file back to Gallery / File Manager
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
                    ?: return@withContext Result.failure(IllegalStateException("تعذر إنشاء ملف جديد"))

                resolver.openOutputStream(newUri)?.use { fos ->
                    CryptoManager.decryptFileToStream(encFile, fos, masterKey)
                }

                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(newUri, values, null, null)

                restoredUri = newUri
            } else {
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

            // Remove from vault
            encFile.delete()
            File(thumbsDir, "thumb_${item.id}.enc").delete()
            thumbnailCache.remove(item.id)
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
        File(thumbsDir, "thumb_${item.id}.enc").delete()
        thumbnailCache.remove(item.id)
        itemsList.remove(item)
        saveIndex()
        true
    }

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

    // Intruder selfie management
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
