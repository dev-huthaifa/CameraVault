package com.vault.secretcamera.ui.secondphone

import android.app.Activity
import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.format.Formatter
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import com.vault.secretcamera.R
import com.vault.secretcamera.SecretVaultApp
import com.vault.secretcamera.data.VaultRepository
import com.vault.secretcamera.databinding.ItemVaultGridBinding
import com.vault.secretcamera.databinding.ItemVaultListBinding
import com.vault.secretcamera.model.VaultCategory
import com.vault.secretcamera.model.VaultItem
import com.vault.secretcamera.security.SecurityPreferences
import com.vault.secretcamera.service.AppLockManager
import com.vault.secretcamera.service.AppLockService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * معرض استديو الخزنة المباشر (Vault Gallery Picker & Smart Studio Bridge):
 * يُفتح إما تلقائياً عند طلب ملف، أو فوراً عبر الزر العائم «استديو الخزنة 🔒» فوق الاستديو العام
 * داخل تطبيقات الجوال الثاني (مثل منصة X / تويتر، إنستغرام، واتساب، تيليجرام، سناب شات).
 */
class VaultMediaPickerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DIRECT_SHARE_PACKAGE = "extra_direct_share_package"
    }

    private lateinit var repository: VaultRepository
    private lateinit var securityPrefs: SecurityPreferences
    private lateinit var rvPicker: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var bottomActionContainer: LinearLayout
    private lateinit var btnPrimaryAction: Button
    private lateinit var btnSecondaryAction: Button
    private lateinit var pickerAdapter: PickerAdapter

    private var allowMultiple = false
    private var requestedMime = "*/*"
    private var directSharePackage: String? = null
    private var currentCategory = VaultCategory.ALL
    private val selectedItems = mutableSetOf<VaultItem>()

    // Fallback launcher for the regular system gallery when the user clicks "المعرض العادي"
    private val normalGalleryLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val outIntent = Intent().apply {
                data = result.data?.data
                clipData = result.data?.clipData
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            setResult(Activity.RESULT_OK, outIntent)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as SecretVaultApp
        repository = app.vaultRepository
        securityPrefs = app.securityPreferences

        if (securityPrefs.isAntiScreenshotEnabled) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        }

        // Ensure master key is available (auto-restore from biometric backup if unlocked via Second Phone)
        if (!securityPrefs.isUnlocked()) {
            securityPrefs.unlockWithBiometric()
        }

        directSharePackage = intent.getStringExtra(EXTRA_DIRECT_SHARE_PACKAGE)
        allowMultiple = intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false) || (directSharePackage != null)
        requestedMime = intent.type ?: "*/*"
        currentCategory = when {
            requestedMime.startsWith("image/") -> VaultCategory.PHOTOS
            requestedMime.startsWith("video/") -> VaultCategory.VIDEOS
            requestedMime.startsWith("audio/") -> VaultCategory.AUDIO
            else -> VaultCategory.ALL
        }

        buildUI()
        loadVaultMedia()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Re-ensure the floating button stays active for the target app when closing the picker
        directSharePackage?.let { pkg ->
            AppLockManager.notifyAppLaunchedFromVault(applicationContext, pkg)
        }
    }

    private fun buildUI() {
        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@VaultMediaPickerActivity, R.color.vault_bg_dark))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // 1. Top Header Bar
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@VaultMediaPickerActivity, R.color.vault_surface_dark))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val closeBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_backspace)
            setBackgroundColor(0)
            setColorFilter(ContextCompat.getColor(this@VaultMediaPickerActivity, R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            setOnClickListener {
                setResult(Activity.RESULT_CANCELED)
                finish()
            }
        }

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(10)
                marginEnd = dp(8)
            }
        }

        val tvTitle = TextView(this).apply {
            text = "استديو الخزنة المباشر 🔒"
            setTextColor(ContextCompat.getColor(this@VaultMediaPickerActivity, R.color.text_primary))
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        val tvSub = TextView(this).apply {
            text = if (directSharePackage != null) {
                "حدد صوراً أو فيديو لإدراجها فوراً بأول الاستديو العام أو إرسالها مباشرة"
            } else {
                "اختر ملفاً لإرساله مباشرة أو اضغط (الاستديو العام 📂)"
            }
            setTextColor(ContextCompat.getColor(this@VaultMediaPickerActivity, R.color.vault_accent_cyan))
            textSize = 11f
        }

        titleCol.addView(tvTitle)
        titleCol.addView(tvSub)

        // Button to switch to / return to the General Phone Studio seamlessly
        val btnNormalGallery = Button(this).apply {
            text = if (directSharePackage != null) "العودة للاستديو العام 📂" else "الاستديو العام 📂"
            textSize = 11f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@VaultMediaPickerActivity, R.color.vault_bg_dark))
            setBackgroundColor(ContextCompat.getColor(this@VaultMediaPickerActivity, R.color.vault_accent_gold))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener {
                if (directSharePackage != null) {
                    // The General Studio of X / Instagram is already open right underneath!
                    finish()
                } else {
                    openNormalSystemGallery()
                }
            }
        }

        topBar.addView(closeBtn)
        topBar.addView(titleCol)
        topBar.addView(btnNormalGallery)
        root.addView(topBar)

        // 2. Category Tabs
        val tabs = TabLayout(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@VaultMediaPickerActivity, R.color.vault_surface_dark))
            setSelectedTabIndicatorColor(ContextCompat.getColor(this@VaultMediaPickerActivity, R.color.vault_accent_cyan))
            setTabTextColors(
                ContextCompat.getColor(this@VaultMediaPickerActivity, R.color.text_secondary),
                ContextCompat.getColor(this@VaultMediaPickerActivity, R.color.vault_accent_cyan)
            )
        }
        val categories = listOf(
            VaultCategory.ALL to "الكل",
            VaultCategory.PHOTOS to "الصور",
            VaultCategory.VIDEOS to "الفيديو",
            VaultCategory.DOCUMENTS to "المستندات",
            VaultCategory.AUDIO to "الصوتيات"
        )
        for ((cat, label) in categories) {
            val tab = tabs.newTab().setText(label)
            tabs.addTab(tab, cat == currentCategory)
        }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentCategory = categories.getOrNull(tab?.position ?: 0)?.first ?: VaultCategory.ALL
                loadVaultMedia()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
        root.addView(tabs)

        // 3. Content Frame (RecyclerView + Empty State)
        val contentFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        rvPicker = RecyclerView(this).apply {
            clipToPadding = false
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        pickerAdapter = PickerAdapter()
        val gridLayoutManager = GridLayoutManager(this, 3)
        gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (pickerAdapter.getItemViewType(position) == 1) 1 else 3
            }
        }
        rvPicker.layoutManager = gridLayoutManager
        rvPicker.adapter = pickerAdapter

        emptyView = TextView(this).apply {
            text = "لا توجد ملفات مشفرة في هذا القسم داخل الخزنة.\nيمكنك إضافة صور أو فيديوهات للخزنة أو الضغط على «الاستديو العام 📂» بالأعلى."
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@VaultMediaPickerActivity, R.color.text_secondary))
            textSize = 14f
            setPadding(dp(32), dp(32), dp(32), dp(32))
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }

        contentFrame.addView(rvPicker)
        contentFrame.addView(emptyView)
        root.addView(contentFrame)

        // 4. Bottom Action Container (Supports both Instant General Studio Bridge & Direct App Share)
        bottomActionContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@VaultMediaPickerActivity, R.color.vault_surface_dark))
            setPadding(dp(12), dp(8), dp(12), dp(12))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        btnPrimaryAction = Button(this).apply {
            text = if (directSharePackage != null) {
                "⚡ إدراج فوري بأول الاستديو العام لاختيارها الآن"
            } else {
                "إرسال الملفات المحددة ✓"
            }
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@VaultMediaPickerActivity, R.color.vault_bg_dark))
            setBackgroundColor(ContextCompat.getColor(this@VaultMediaPickerActivity, R.color.vault_accent_cyan))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
            )
            setOnClickListener {
                if (selectedItems.isNotEmpty()) {
                    if (directSharePackage != null) {
                        bridgeSelectedItemsToGeneralStudio(selectedItems.toList())
                    } else {
                        deliverSelectedVaultItems(selectedItems.toList())
                    }
                }
            }
        }

        btnSecondaryAction = Button(this).apply {
            text = "🚀 إرسال مباشر كمنشور/رسالة جديدة في التطبيق"
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@VaultMediaPickerActivity, R.color.vault_bg_dark))
            setBackgroundColor(ContextCompat.getColor(this@VaultMediaPickerActivity, R.color.vault_accent_gold))
            visibility = if (directSharePackage != null) View.VISIBLE else View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(44)
            ).apply {
                topMargin = dp(6)
            }
            setOnClickListener {
                if (selectedItems.isNotEmpty()) {
                    shareDirectlyToTargetApp(selectedItems.toList())
                }
            }
        }

        bottomActionContainer.addView(btnPrimaryAction)
        bottomActionContainer.addView(btnSecondaryAction)
        root.addView(bottomActionContainer)

        setContentView(root)
    }

    private fun loadVaultMedia() {
        val items = repository.getAllItems(
            category = currentCategory,
            isDecoy = securityPrefs.isDecoySession
        )
        pickerAdapter.submit(items)
        emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        rvPicker.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun openNormalSystemGallery() {
        try {
            val docIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = requestedMime
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
            }
            normalGalleryLauncher.launch(docIntent)
        } catch (e: Exception) {
            Toast.makeText(this, "تعذر فتح الاستديو العام: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Bridges selected Vault items directly into the top (#1 spot) of the General Studio (MediaStore DCIM/Camera)
     * so when this picker closes (100ms later), the user sees the photo right at the top of X/Instagram's open General Studio,
     * and AppLockService automatically deletes it from the General Studio after 2 minutes or when leaving the app!
     */
    private fun bridgeSelectedItemsToGeneralStudio(items: List<VaultItem>) {
        if (items.isEmpty()) return
        Toast.makeText(this, "جاري إدراج الملف في أول الاستديو العام...", Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.IO).launch {
            var bridgedCount = 0
            val scannedPaths = mutableListOf<String>()

            for (item in items) {
                val bytes = repository.getDecryptedBytes(item) ?: continue
                val ext = item.originalName.substringAfterLast('.', if (item.category == VaultCategory.VIDEOS) "mp4" else "jpg")
                val fileName = "IMG_${System.currentTimeMillis()}_${bridgedCount}.$ext"
                val mime = item.mimeType.ifBlank {
                    if (item.category == VaultCategory.VIDEOS) "video/mp4" else "image/jpeg"
                }

                val isVideo = item.category == VaultCategory.VIDEOS || mime.startsWith("video/")
                val collection = if (isVideo) {
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                } else {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }

                val nowMillis = System.currentTimeMillis()
                val nowSeconds = nowMillis / 1000L

                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val values = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                            put(MediaStore.MediaColumns.MIME_TYPE, mime)
                            put(MediaStore.MediaColumns.DATE_ADDED, nowSeconds)
                            put(MediaStore.MediaColumns.DATE_MODIFIED, nowSeconds)
                            put(MediaStore.MediaColumns.DATE_TAKEN, nowMillis)
                            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera")
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        }
                        val insertedUri = contentResolver.insert(collection, values)
                        if (insertedUri != null) {
                            contentResolver.openOutputStream(insertedUri)?.use { out ->
                                out.write(bytes)
                                out.flush()
                            }
                            val publishValues = ContentValues().apply {
                                put(MediaStore.MediaColumns.IS_PENDING, 0)
                                put(MediaStore.MediaColumns.DATE_ADDED, nowSeconds)
                                put(MediaStore.MediaColumns.DATE_MODIFIED, nowSeconds)
                            }
                            contentResolver.update(insertedUri, publishValues, null, null)
                            AppLockService.registerTemporaryBridgedMedia(
                                applicationContext,
                                insertedUri,
                                null,
                                120_000L
                            )
                            bridgedCount++
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        val dcimDir = File(
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                            "Camera"
                        ).apply { mkdirs() }
                        val targetFile = File(dcimDir, fileName)
                        FileOutputStream(targetFile).use { out ->
                            out.write(bytes)
                            out.flush()
                        }
                        @Suppress("DEPRECATION")
                        val values = ContentValues().apply {
                            put(MediaStore.MediaColumns.DATA, targetFile.absolutePath)
                            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                            put(MediaStore.MediaColumns.MIME_TYPE, mime)
                            put(MediaStore.MediaColumns.DATE_ADDED, nowSeconds)
                            put(MediaStore.MediaColumns.DATE_MODIFIED, nowSeconds)
                        }
                        val insertedUri = contentResolver.insert(collection, values)
                        if (insertedUri != null) {
                            AppLockService.registerTemporaryBridgedMedia(
                                applicationContext,
                                insertedUri,
                                targetFile,
                                120_000L
                            )
                        }
                        scannedPaths.add(targetFile.absolutePath)
                        bridgedCount++
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            if (scannedPaths.isNotEmpty()) {
                MediaScannerConnection.scanFile(
                    applicationContext,
                    scannedPaths.toTypedArray(),
                    null,
                    null
                )
            }

            try {
                contentResolver.notifyChange(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, null)
                contentResolver.notifyChange(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, null)
            } catch (_: Exception) {}

            withContext(Dispatchers.Main) {
                if (bridgedCount > 0) {
                    Toast.makeText(
                        this@VaultMediaPickerActivity,
                        "✅ تم وضع الملف في أول الاستديو العام! اختره الآن (سيتم إخفاؤه تلقائياً بعد الإرسال 🔒)",
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                } else {
                    // Fallback to direct share if MediaStore insert failed
                    shareDirectlyToTargetApp(items)
                }
            }
        }
    }

    /**
     * Sends the decrypted Vault items directly to the target app (e.g., X, Instagram, WhatsApp)
     * via ACTION_SEND / ACTION_SEND_MULTIPLE.
     */
    private fun shareDirectlyToTargetApp(items: List<VaultItem>) {
        if (items.isEmpty()) return
        val targetPkg = directSharePackage

        CoroutineScope(Dispatchers.IO).launch {
            val shareDir = File(cacheDir, "secure_share").apply { mkdirs() }
            val resultUris = ArrayList<Uri>()
            var primaryMime = "image/*"

            for (item in items) {
                val bytes = repository.getDecryptedBytes(item) ?: continue
                if (item.mimeType.isNotBlank()) primaryMime = item.mimeType
                val safeName = item.originalName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                val tempFile = File(shareDir, "share_${item.id}_$safeName")
                FileOutputStream(tempFile).use { it.write(bytes) }

                val uri = FileProvider.getUriForFile(
                    applicationContext,
                    "$packageName.provider",
                    tempFile
                )
                resultUris.add(uri)
            }

            withContext(Dispatchers.Main) {
                if (resultUris.isEmpty()) {
                    Toast.makeText(this@VaultMediaPickerActivity, "تعذر فك تشفير الملف المختار", Toast.LENGTH_SHORT).show()
                    return@withContext
                }

                try {
                    val shareIntent = if (resultUris.size == 1) {
                        Intent(Intent.ACTION_SEND).apply {
                            type = primaryMime
                            putExtra(Intent.EXTRA_STREAM, resultUris.first())
                            clipData = ClipData.newUri(contentResolver, "vault_media", resultUris.first())
                        }
                    } else {
                        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                            type = "*/*"
                            putParcelableArrayListExtra(Intent.EXTRA_STREAM, resultUris)
                        }
                    }

                    shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (!targetPkg.isNullOrEmpty()) {
                        shareIntent.setPackage(targetPkg)
                        for (u in resultUris) {
                            try {
                                grantUriPermission(targetPkg, u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            } catch (_: Exception) {}
                        }
                    }

                    startActivity(shareIntent)
                    finish()
                } catch (_: Exception) {
                    // If targetPkg doesn't handle direct ACTION_SEND, open chooser
                    try {
                        val chooser = Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = primaryMime
                                putExtra(Intent.EXTRA_STREAM, resultUris.first())
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            },
                            "إرسال الملف المشفر عبر"
                        ).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(chooser)
                        finish()
                    } catch (e2: Exception) {
                        Toast.makeText(this@VaultMediaPickerActivity, "تعذر الإرسال المباشر: ${e2.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun deliverSelectedVaultItems(items: List<VaultItem>) {
        if (items.isEmpty()) return
        Toast.makeText(this, "جاري تجهيز الملف المشفر للإرسال الآمن...", Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.IO).launch {
            val shareDir = File(cacheDir, "secure_share").apply { mkdirs() }
            val resultUris = mutableListOf<Uri>()

            for (item in items) {
                val bytes = repository.getDecryptedBytes(item) ?: continue
                val safeName = item.originalName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                val tempFile = File(shareDir, "pick_${item.id}_$safeName")
                FileOutputStream(tempFile).use { it.write(bytes) }

                val uri = FileProvider.getUriForFile(
                    applicationContext,
                    "$packageName.provider",
                    tempFile
                )
                resultUris.add(uri)
            }

            withContext(Dispatchers.Main) {
                if (resultUris.isEmpty()) {
                    Toast.makeText(this@VaultMediaPickerActivity, "تعذر فك تشفير الملف المختار", Toast.LENGTH_SHORT).show()
                    return@withContext
                }

                val resultIntent = Intent().apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    if (resultUris.size == 1 && !allowMultiple) {
                        data = resultUris.first()
                        clipData = ClipData.newUri(contentResolver, "vault_media", resultUris.first())
                    } else {
                        data = resultUris.first()
                        val clip = ClipData.newUri(contentResolver, "vault_media", resultUris.first())
                        for (i in 1 until resultUris.size) {
                            clip.addItem(ClipData.Item(resultUris[i]))
                        }
                        clipData = clip
                    }
                }

                callingPackage?.let { caller ->
                    for (u in resultUris) {
                        try {
                            grantUriPermission(caller, u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        } catch (_: Exception) {}
                    }
                }

                setResult(Activity.RESULT_OK, resultIntent)
                finish()
            }
        }
    }

    private inner class PickerAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val list = mutableListOf<VaultItem>()

        fun submit(newItems: List<VaultItem>) {
            list.clear()
            list.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int {
            val item = list[position]
            return if (item.category == VaultCategory.PHOTOS || item.category == VaultCategory.VIDEOS) 1 else 2
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == 1) {
                GridHolder(ItemVaultGridBinding.inflate(inflater, parent, false))
            } else {
                ListHolder(ItemVaultListBinding.inflate(inflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = list[position]
            if (holder is GridHolder) holder.bind(item)
            else if (holder is ListHolder) holder.bind(item)
        }

        override fun getItemCount(): Int = list.size

        inner class GridHolder(private val b: ItemVaultGridBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(item: VaultItem) {
                b.ivVideoPlay.visibility = if (item.category == VaultCategory.VIDEOS) View.VISIBLE else View.GONE
                val isSel = selectedItems.contains(item)
                b.cbSelected.visibility = if (allowMultiple || isSel) View.VISIBLE else View.GONE
                b.cbSelected.isChecked = isSel

                b.ivThumbnail.tag = item.id
                val cached = repository.getCachedThumbnail(item.id)
                if (cached != null) {
                    b.ivThumbnail.setImageBitmap(cached)
                } else {
                    b.ivThumbnail.setImageResource(
                        if (item.category == VaultCategory.VIDEOS) R.drawable.ic_video else R.drawable.ic_photo
                    )
                    CoroutineScope(Dispatchers.IO).launch {
                        val bmp = repository.getThumbnailBitmap(item)
                        if (bmp != null) {
                            withContext(Dispatchers.Main) {
                                if (b.ivThumbnail.tag == item.id) {
                                    b.ivThumbnail.setImageBitmap(bmp)
                                }
                            }
                        }
                    }
                }

                b.root.setOnClickListener {
                    handleItemClick(item)
                }
            }
        }

        inner class ListHolder(private val b: ItemVaultListBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(item: VaultItem) {
                b.tvFileName.text = item.originalName
                b.tvFileSize.text = Formatter.formatFileSize(b.root.context, item.fileSize)
                b.tvFileDate.text = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date(item.dateAdded))
                val isSel = selectedItems.contains(item)
                b.cbSelected.visibility = if (allowMultiple || isSel) View.VISIBLE else View.GONE
                b.cbSelected.isChecked = isSel

                b.root.setOnClickListener {
                    handleItemClick(item)
                }
            }
        }

        private fun handleItemClick(item: VaultItem) {
            if (!allowMultiple && directSharePackage == null) {
                deliverSelectedVaultItems(listOf(item))
            } else {
                if (selectedItems.contains(item)) {
                    selectedItems.remove(item)
                } else {
                    selectedItems.add(item)
                }
                notifyDataSetChanged()
                if (selectedItems.isNotEmpty()) {
                    bottomActionContainer.visibility = View.VISIBLE
                    if (directSharePackage != null) {
                        btnPrimaryAction.text = "⚡ إدراج (${selectedItems.size}) في أول الاستديو العام الآن"
                        btnSecondaryAction.text = "🚀 إرسال (${selectedItems.size}) مباشرة إلى التطبيق"
                    } else {
                        btnPrimaryAction.text = "إرسال (${selectedItems.size}) ملفات محددة ✓"
                    }
                } else {
                    bottomActionContainer.visibility = View.GONE
                }
            }
        }
    }
}
