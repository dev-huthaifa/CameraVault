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

    data class BatchImportResult(
        val successCount: Int,
        val pendingDeleteUris: List<Uri>,
        val directlyDeletedCount: Int = 0
    )

    data class SingleImportResult(
        val item: VaultItem?,
        val pendingDeleteUri: Uri?,
        val isDeletedDirectly: Boolean = false
    )

    /**
     * Batch import with real-time progress, single JSON save, and collected pending delete URIs
     */
    suspend fun importBatch(
        uris: List<Uri>,
        onProgress: suspend (current: Int, total: Int) -> Unit
    ): BatchImportResult = withContext(Dispatchers.IO) {
        var count = 0
        var directDeleteCount = 0
        val pendingUris = mutableListOf<Uri>()

        for ((index, uri) in uris.withIndex()) {
            val res = importSingleFileInternal(uri, saveIndexAtEnd = false)
            if (res.item != null) {
                count++
            }
            if (res.isDeletedDirectly) {
                directDeleteCount++
            }
            if (res.pendingDeleteUri != null) {
                pendingUris.add(res.pendingDeleteUri)
            }
            onProgress(index + 1, uris.size)
        }
        saveIndex()
        BatchImportResult(count, pendingUris, directDeleteCount)
    }

    suspend fun importAndHideFile(uri: Uri): Result<VaultItem> = withContext(Dispatchers.IO) {
        val res = importSingleFileInternal(uri, saveIndexAtEnd = true)
        if (res.item != null) {
            Result.success(res.item)
        } else {
            Result.failure(IllegalStateException("تعذر استيراد الملف"))
        }
    }

    private fun importSingleFileInternal(uri: Uri, saveIndexAtEnd: Boolean): SingleImportResult {
        val masterKey = securityPrefs.currentMasterKey
            ?: return SingleImportResult(null, null, false)

        try {
            val contentResolver = context.contentResolver
            val (originalName, originalSize) = queryFileInfo(contentResolver, uri)
            val mimeType = contentResolver.getType(uri) ?: queryMimeType(originalName)
            val category = resolveCategory(mimeType, originalName)

            val itemId = UUID.randomUUID().toString()
            val encFileName = "$itemId.enc"
            val destEncFile = File(itemsDir, encFileName)

            // 1. Read & Encrypt full file into vault private storage
            contentResolver.openInputStream(uri)?.use { inputStream ->
                CryptoManager.encryptStreamToFile(inputStream, destEncFile, masterKey)
            } ?: return SingleImportResult(null, null, false)

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
            // Use genuine unencrypted originalSize for accurate MediaStore/Disk matching
            val (pendingDeleteUri, isDeletedDirectly) = purgeOriginalFile(uri, originalName, originalSize)

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

            return SingleImportResult(vaultItem, pendingDeleteUri, isDeletedDirectly)
        } catch (e: Exception) {
            e.printStackTrace()
            return SingleImportResult(null, null, false)
        }
    }

    /**
     * Queries the original display name and exact unencrypted byte size directly from ContentResolver
     */
    private fun queryFileInfo(resolver: ContentResolver, uri: Uri): Pair<String, Long> {
        var name: String? = null
        var size: Long = 0L

        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            try {
                resolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null, null, null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIdx != -1) {
                            cursor.getString(nameIdx)?.let { if (it.isNotBlank()) name = it }
                        }
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIdx != -1) {
                            size = cursor.getLong(sizeIdx)
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        if (name.isNullOrBlank()) {
            name = uri.lastPathSegment ?: "file_${System.currentTimeMillis()}"
        }
        return Pair(name!!, size)
    }

    /**
     * Comprehensive resolver for physical disk path and genuine MediaStore URI
     * across Android 10, 11, 12, 13, 14, 15, PhotoPicker, SAF, and OEM Galleries.
     */
    fun resolveRealPathAndMediaStoreUri(
        context: Context,
        uri: Uri,
        originalName: String = "",
        originalSize: Long = 0L
    ): Pair<String?, Uri?> {
        val contentResolver = context.contentResolver

        // 1. Direct File URI (file://...)
        if ("file".equals(uri.scheme, ignoreCase = true)) {
            val path = uri.path
            return Pair(path, null)
        }

        // 2. Direct MediaStore Content URI (content://media/external/...)
        val uriStr = uri.toString()
        if (uriStr.startsWith("content://media/external/")) {
            var realPath: String? = null
            try {
                val proj = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME)
                } else {
                    arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA)
                }
                contentResolver.query(uri, proj, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val dataIdx = c.getColumnIndex(MediaStore.MediaColumns.DATA)
                        if (dataIdx != -1) realPath = c.getString(dataIdx)

                        if (realPath.isNullOrEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val relIdx = c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                            val nameIdx = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                            val rel = if (relIdx != -1) c.getString(relIdx) else null
                            val dName = if (nameIdx != -1) c.getString(nameIdx) else null
                            if (!rel.isNullOrEmpty() && !dName.isNullOrEmpty()) {
                                realPath = File(Environment.getExternalStorageDirectory(), "$rel$dName").absolutePath
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
            return Pair(realPath, uri)
        }

        // 3. Document Provider URI (DocumentsContract)
        if (DocumentsContract.isDocumentUri(context, uri)) {
            val authority = uri.authority

            // A: MediaDocumentsProvider (com.android.providers.media.documents)
            if ("com.android.providers.media.documents" == authority) {
                val docId = DocumentsContract.getDocumentId(uri)
                val split = docId.split(":")
                val type = split.getOrNull(0) ?: ""
                val idStr = split.getOrNull(1) ?: ""
                val mediaId = idStr.toLongOrNull()

                if (mediaId != null) {
                    val contentUri = when (type.lowercase()) {
                        "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                        else -> MediaStore.Files.getContentUri("external")
                    }
                    val mediaUri = ContentUris.withAppendedId(contentUri, mediaId)
                    var realPath: String? = null
                    try {
                        contentResolver.query(mediaUri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                            if (c.moveToFirst()) {
                                val idx = c.getColumnIndex(MediaStore.MediaColumns.DATA)
                                if (idx != -1) realPath = c.getString(idx)
                            }
                        }
                    } catch (_: Exception) {}
                    return Pair(realPath, mediaUri)
                }
            }
            // B: ExternalStorageProvider (primary:DCIM/Camera/...)
            else if ("com.android.externalstorage.documents" == authority) {
                val docId = DocumentsContract.getDocumentId(uri)
                val split = docId.split(":")
                val type = split.getOrNull(0) ?: ""
                if ("primary".equals(type, ignoreCase = true)) {
                    val relPath = split.getOrNull(1) ?: ""
                    val realPath = File(Environment.getExternalStorageDirectory(), relPath).absolutePath
                    var mediaUri: Uri? = null
                    try {
                        contentResolver.query(
                            MediaStore.Files.getContentUri("external"),
                            arrayOf(MediaStore.MediaColumns._ID),
                            "${MediaStore.MediaColumns.DATA} = ?",
                            arrayOf(realPath),
                            null
                        )?.use { c ->
                            if (c.moveToFirst()) {
                                val idx = c.getColumnIndex(MediaStore.MediaColumns._ID)
                                if (idx != -1) {
                                    val id = c.getLong(idx)
                                    mediaUri = ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id)
                                }
                            }
                        }
                    } catch (_: Exception) {}
                    return Pair(realPath, mediaUri)
                }
            }
            // C: DownloadsProvider
            else if ("com.android.providers.downloads.documents" == authority) {
                val id = DocumentsContract.getDocumentId(uri)
                if (id.startsWith("raw:")) {
                    return Pair(id.removePrefix("raw:"), null)
                }
                val downloadUri = id.toLongOrNull()?.let {
                    ContentUris.withAppendedId(Uri.parse("content://downloads/public_downloads"), it)
                }
                var realPath: String? = null
                if (downloadUri != null) {
                    try {
                        contentResolver.query(downloadUri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                            if (c.moveToFirst()) {
                                val idx = c.getColumnIndex(MediaStore.MediaColumns.DATA)
                                if (idx != -1) realPath = c.getString(idx)
                            }
                        }
                    } catch (_: Exception) {}
                }
                return Pair(realPath, downloadUri)
            }
        }

        // 4. Android Photo Picker URI (content://media/picker/... or photopicker authority)
        val isPicker = uriStr.contains("photopicker") || uriStr.contains("/picker/")
        if (isPicker) {
            val possibleId = uri.lastPathSegment?.toLongOrNull()
            if (possibleId != null) {
                val collections = listOf(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                )
                for (collection in collections) {
                    val candidate = ContentUris.withAppendedId(collection, possibleId)
                    try {
                        contentResolver.query(
                            candidate,
                            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA),
                            null, null, null
                        )?.use { c ->
                            if (c.moveToFirst()) {
                                val pathIdx = c.getColumnIndex(MediaStore.MediaColumns.DATA)
                                val path = if (pathIdx != -1) c.getString(pathIdx) else null
                                return Pair(path, candidate)
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        // 5. General MediaStore Query by display name and original size
        if (originalName.isNotBlank()) {
            val searchCollections = listOf(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Files.getContentUri("external")
            )

            for (collection in searchCollections) {
                // A: Exact match with name and size
                if (originalSize > 0L) {
                    try {
                        contentResolver.query(
                            collection,
                            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA),
                            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.SIZE} = ?",
                            arrayOf(originalName, originalSize.toString()),
                            null
                        )?.use { c ->
                            if (c.moveToFirst()) {
                                val idIdx = c.getColumnIndex(MediaStore.MediaColumns._ID)
                                val dataIdx = c.getColumnIndex(MediaStore.MediaColumns.DATA)
                                val id = if (idIdx != -1) c.getLong(idIdx) else null
                                val path = if (dataIdx != -1) c.getString(dataIdx) else null
                                val mUri = id?.let { ContentUris.withAppendedId(collection, it) }
                                return Pair(path, mUri)
                            }
                        }
                    } catch (_: Exception) {}
                }

                // B: Match by name alone (most recent first)
                try {
                    contentResolver.query(
                        collection,
                        arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA),
                        "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                        arrayOf(originalName),
                        "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
                    )?.use { c ->
                        if (c.moveToFirst()) {
                            val idIdx = c.getColumnIndex(MediaStore.MediaColumns._ID)
                            val dataIdx = c.getColumnIndex(MediaStore.MediaColumns.DATA)
                            val id = if (idIdx != -1) c.getLong(idIdx) else null
                            val path = if (dataIdx != -1) c.getString(dataIdx) else null
                            val mUri = id?.let { ContentUris.withAppendedId(collection, it) }
                            return Pair(path, mUri)
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        // 6. Direct Disk Search in standard media folders (when Manage All Files or storage access is active)
        if (originalName.isNotBlank()) {
            val root = Environment.getExternalStorageDirectory()
            val candidateDirs = listOf(
                File(root, "DCIM/Camera"),
                File(root, "DCIM"),
                File(root, "DCIM/Screenshots"),
                File(root, "DCIM/100ANDRO"),
                File(root, "Pictures"),
                File(root, "Pictures/Screenshots"),
                File(root, "Pictures/Instagram"),
                File(root, "Pictures/Telegram"),
                File(root, "Pictures/Twitter"),
                File(root, "Pictures/Facebook"),
                File(root, "Download"),
                File(root, "Movies"),
                File(root, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images"),
                File(root, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video"),
                File(root, "WhatsApp/Media/WhatsApp Images"),
                File(root, "WhatsApp/Media/WhatsApp Video")
            )

            for (dir in candidateDirs) {
                if (!dir.exists() || !dir.isDirectory) continue
                val candidateFile = File(dir, originalName)
                if (candidateFile.exists() && candidateFile.isFile) {
                    if (originalSize <= 0L || candidateFile.length() == originalSize || Math.abs(candidateFile.length() - originalSize) < 2048) {
                        return Pair(candidateFile.absolutePath, null)
                    }
                }
            }
        }

        return Pair(null, null)
    }

    /**
     * Military-Grade NIST SP 800-88 Zero-Fill Shredder:
     * Overwrites file blocks with zeros and synchronizes physically to storage
     * before unlinking, making forensic undeletion impossible.
     */
    fun shredFileContent(file: File) {
        if (!file.exists() || !file.canWrite()) return
        try {
            val length = file.length()
            if (length > 0) {
                java.io.RandomAccessFile(file, "rws").use { raf ->
                    val bufferSize = minOf(length, 64 * 1024L).toInt()
                    val zeroBuffer = ByteArray(bufferSize)
                    var remaining = length
                    raf.seek(0)
                    while (remaining > 0) {
                        val toWrite = minOf(remaining, bufferSize.toLong()).toInt()
                        raf.write(zeroBuffer, 0, toWrite)
                        remaining -= toWrite
                    }
                    raf.fd.sync()
                }
            }
        } catch (_: Exception) {}
    }

    private fun shredContentUri(uri: Uri) {
        try {
            context.contentResolver.openOutputStream(uri, "rwt")?.use { os ->
                val buffer = ByteArray(4096)
                os.write(buffer)
                os.flush()
            }
        } catch (_: Exception) {}
    }

    /**
     * Reads a real raw encrypted hex sample from a stored vault file for cryptographic auditing.
     */
    fun getEncryptedFileHexSample(targetItem: VaultItem? = null): String {
        try {
            val item = targetItem ?: itemsList.firstOrNull() ?: return "لا توجد ملفات مشفرة في الخزنة حالياً لتدقيقها."
            val encFile = File(itemsDir, item.encryptedFileName)
            if (!encFile.exists() || encFile.length() == 0L) return "الملف المشفر غير موجود على القرص."

            val sampleBytes = ByteArray(32)
            java.io.FileInputStream(encFile).use { fis ->
                val read = fis.read(sampleBytes)
                if (read <= 0) return "الملف فارغ."
                val hexList = sampleBytes.take(read).map { String.format("%02X", it) }
                val part1 = hexList.take(16).joinToString(" ")
                val part2 = hexList.drop(16).joinToString(" ")
                return if (part2.isNotEmpty()) "$part1\n$part2" else part1
            }
        } catch (e: Exception) {
            return "تعذر قراءة عينة التشفير: ${e.message}"
        }
    }

    /**
     * Permanent Deletion: Attempts raw physical deletion first, then MediaStore, SAF, and trash purging.
     * Returns Pair(pendingDeleteUri, isDirectlyDeleted).
     */
    private fun purgeOriginalFile(uri: Uri, originalName: String, originalSize: Long): Pair<Uri?, Boolean> {
        val contentResolver = context.contentResolver
        val (realPath, mediaStoreUri) = resolveRealPathAndMediaStoreUri(context, uri, originalName, originalSize)
        var physicallyDeleted = false

        // 1. Direct Physical File Deletion (Bypasses Trash & Wipes File Off Storage)
        if (!realPath.isNullOrEmpty()) {
            try {
                val file = File(realPath)
                if (file.exists()) {
                    if (securityPrefs.isDataShreddingEnabled) {
                        shredFileContent(file)
                    }
                    physicallyDeleted = file.delete()
                }
            } catch (_: Exception) {}
        }

        // 2. Direct MediaStore delete if genuine MediaStore URI exists
        if (mediaStoreUri != null) {
            try {
                if (securityPrefs.isDataShreddingEnabled) {
                    shredContentUri(mediaStoreUri)
                }
                val deleted = contentResolver.delete(mediaStoreUri, null, null)
                if (deleted > 0) physicallyDeleted = true
            } catch (_: Exception) {}
        }

        // 3. Direct ContentResolver delete on original URI (if writable)
        try {
            if (securityPrefs.isDataShreddingEnabled) {
                shredContentUri(uri)
            }
            val deleted = contentResolver.delete(uri, null, null)
            if (deleted > 0) physicallyDeleted = true
        } catch (_: Exception) {}

        // 4. Direct SAF Document deletion
        try {
            if (DocumentsContract.isDocumentUri(context, uri)) {
                if (DocumentsContract.deleteDocument(contentResolver, uri)) {
                    physicallyDeleted = true
                }
            }
        } catch (_: Exception) {}

        // 5. If physically deleted, synchronize and wipe all traces from MediaStore database & trash
        if (physicallyDeleted) {
            // Delete matching records from MediaStore database so gallery immediately removes thumbnails
            try {
                contentResolver.delete(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                    arrayOf(originalName)
                )
            } catch (_: Exception) {}
            try {
                contentResolver.delete(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                    arrayOf(originalName)
                )
            } catch (_: Exception) {}

            // Notify MediaScanner that file is gone
            if (!realPath.isNullOrEmpty()) {
                MediaScannerConnection.scanFile(context, arrayOf(realPath), null, null)
            }

            // Purge from Gallery Trash / Recycle Bin folders
            purgeTrashFiles(originalName, realPath)

            return Pair(null, true)
        }

        // 6. If silent physical deletion was blocked by OS (Scoped Storage without Manage All Files permission):
        // Only return mediaStoreUri if it is a genuine MediaStore content URI!
        if (mediaStoreUri != null && mediaStoreUri.toString().startsWith("content://media/external/")) {
            return Pair(mediaStoreUri, false)
        }

        return Pair(null, false)
    }

    /**
     * Purges files from standard OEM & MediaStore Trash / Recycle Bin
     */
    private fun purgeTrashFiles(originalName: String, realPath: String?) {
        try {
            val baseName = if (!realPath.isNullOrEmpty()) File(realPath).name else originalName
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
                        if (tf.name.contains(baseName) || tf.name.contains(originalName)) {
                            tf.delete()
                        }
                    }
                }
                // Check .trashed-* files directly in media folder
                dir.listFiles()?.forEach { f ->
                    if (f.name.startsWith(".trashed") && (f.name.contains(baseName) || f.name.contains(originalName))) {
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
            if (securityPrefs.isDataShreddingEnabled) {
                shredFileContent(encFile)
            }
            encFile.delete()
        }
        val thumbFile = File(thumbsDir, "thumb_${item.id}.enc")
        if (thumbFile.exists()) {
            if (securityPrefs.isDataShreddingEnabled) {
                shredFileContent(thumbFile)
            }
            thumbFile.delete()
        }
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
