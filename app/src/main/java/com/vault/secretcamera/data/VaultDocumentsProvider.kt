package com.vault.secretcamera.data

import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Point
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import com.vault.secretcamera.R
import com.vault.secretcamera.SecretVaultApp
import com.vault.secretcamera.model.VaultCategory
import com.vault.secretcamera.model.VaultItem
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileOutputStream

class VaultDocumentsProvider : DocumentsProvider() {

    companion object {
        const val AUTHORITY = "com.vault.secretcamera.documents"
        private const val ROOT_ID = "vault_root"

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_MIME_TYPES,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_ICON,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_SUMMARY,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE
        )
    }

    private val app: SecretVaultApp by lazy {
        context!!.applicationContext as SecretVaultApp
    }

    private val repository: VaultRepository by lazy {
        app.vaultRepository
    }

    private fun ensureUnlocked() {
        if (!app.securityPreferences.isUnlocked()) {
            app.securityPreferences.unlockWithBiometric()
        }
    }

    override fun onCreate(): Boolean {
        return true
    }

    override fun isChildDocument(parentDocumentId: String?, documentId: String?): Boolean {
        return parentDocumentId == ROOT_ID && !documentId.isNullOrEmpty()
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val row = result.newRow()
        row.add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID)
        row.add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_ID)
        row.add(DocumentsContract.Root.COLUMN_TITLE, "استديو الخزنة 🔒")
        row.add(DocumentsContract.Root.COLUMN_SUMMARY, "الصور والفيديوهات والملفات المشفرة")
        row.add(DocumentsContract.Root.COLUMN_ICON, R.drawable.ic_camera_launcher)
        row.add(DocumentsContract.Root.COLUMN_MIME_TYPES, "image/*\nvideo/*\naudio/*\napplication/*\n*/*")
        row.add(
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD or DocumentsContract.Root.FLAG_LOCAL_ONLY
        )
        return result
    }

    override fun queryDocument(documentId: String?, projection: Array<out String>?): Cursor {
        ensureUnlocked()
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        if (documentId == null || documentId == ROOT_ID) {
            val row = result.newRow()
            row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, ROOT_ID)
            row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR)
            row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, "استديو الخزنة 🔒")
            row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, System.currentTimeMillis())
            row.add(DocumentsContract.Document.COLUMN_FLAGS, 0)
            row.add(DocumentsContract.Document.COLUMN_SIZE, 0L)
            return result
        }

        val item = repository.getItemById(documentId)
        if (item != null) {
            includeVaultItem(result, item)
        }
        return result
    }

    override fun queryChildDocuments(
        parentDocumentId: String?,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        ensureUnlocked()
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        if (parentDocumentId == ROOT_ID) {
            val items = repository.getAllItems(category = VaultCategory.ALL, isDecoy = false)
            for (item in items) {
                includeVaultItem(result, item)
            }
        }
        return result
    }

    override fun openDocument(
        documentId: String?,
        mode: String?,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        ensureUnlocked()
        val item = repository.getItemById(documentId ?: "")
            ?: throw IllegalArgumentException("الملف غير موجود في الخزنة: $documentId")

        val ctx = context ?: throw IllegalStateException("Context is null")
        val cacheDir = File(ctx.cacheDir, "vault_saf_temp").apply { mkdirs() }
        val safeName = item.originalName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val tempFile = File(cacheDir, "temp_${item.id}_$safeName")

        if (!tempFile.exists() || tempFile.length() == 0L) {
            val bytes = runBlocking { repository.getDecryptedBytes(item) }
                ?: throw IllegalStateException("فشل فك تشفير الملف")
            FileOutputStream(tempFile).use { it.write(bytes) }
        }

        return ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun openDocumentThumbnail(
        documentId: String?,
        sizeHint: Point?,
        signal: CancellationSignal?
    ): AssetFileDescriptor {
        ensureUnlocked()
        val item = repository.getItemById(documentId ?: "")
            ?: throw IllegalArgumentException("الملف غير موجود: $documentId")

        val ctx = context ?: throw IllegalStateException("Context is null")
        val thumbDir = File(ctx.cacheDir, "vault_saf_thumbs").apply { mkdirs() }
        val thumbFile = File(thumbDir, "thumb_${item.id}.jpg")

        if (!thumbFile.exists() || thumbFile.length() == 0L) {
            val bitmap = runBlocking { repository.getThumbnailBitmap(item) }
                ?: throw IllegalStateException("لا توجد صورة مصغرة")
            FileOutputStream(thumbFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
        }

        val pfd = ParcelFileDescriptor.open(thumbFile, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    private fun includeVaultItem(result: MatrixCursor, item: VaultItem) {
        val row = result.newRow()
        val supportsThumb = item.category == VaultCategory.PHOTOS || item.category == VaultCategory.VIDEOS
        val flags = if (supportsThumb) DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL else 0

        row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, item.id)
        row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, item.mimeType)
        row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, item.originalName)
        row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, item.dateAdded)
        row.add(DocumentsContract.Document.COLUMN_FLAGS, flags)
        row.add(DocumentsContract.Document.COLUMN_SIZE, item.fileSize)
    }
}
