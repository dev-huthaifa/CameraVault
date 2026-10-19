package com.vault.secretcamera.ui.secondphone

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
 * معرض استديو الخزنة المباشر (Vault Gallery Picker Interceptor):
 * يُفتح تلقائياً عندما يضغط المستخدم على زر إرفاق صورة أو فيديو أو ملف من داخل تطبيقات الجوال الثاني
 * (مثل منصة X / تويتر، إنستغرام، واتساب، تيليجرام، سناب شات، وغيرها).
 *
 * يوفر قانوناً صارماً يمنع التداخل:
 * 1. يعرض صور وفيديوهات وملفات الخزنة المشفرة مباشرة (مع فك تشفير لحظي آمن للتطبيق الطالب فقط).
 * 2. يحتوي على زر علوي وسفلي واضح ("فتح المعرض العادي للهاتف 📂") لمن يريد التحويل للمعرض العام بدون أي تعارض.
 */
class VaultMediaPickerActivity : AppCompatActivity() {

    private lateinit var repository: VaultRepository
    private lateinit var securityPrefs: SecurityPreferences
    private lateinit var rvPicker: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var btnConfirmMulti: Button
    private lateinit var pickerAdapter: PickerAdapter

    private var allowMultiple = false
    private var requestedMime = "*/*"
    private var currentCategory = VaultCategory.ALL
    private val selectedItems = mutableSetOf<VaultItem>()

    // Fallback launcher for the regular system gallery when the user explicitly clicks "المعرض العادي"
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

        allowMultiple = intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
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
            }
        }

        val tvTitle = TextView(this).apply {
            text = "معرض الخزنة السري 🔒"
            setTextColor(ContextCompat.getColor(this@VaultMediaPickerActivity, R.color.text_primary))
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        val tvSub = TextView(this).apply {
            text = if (allowMultiple) "اختر صورة أو عدة ملفات لإرسالها مباشرة" else "اختر ملفاً لإرساله مباشرة بدون إظهاره بالاستوديو"
            setTextColor(ContextCompat.getColor(this@VaultMediaPickerActivity, R.color.vault_accent_cyan))
            textSize = 11f
        }

        titleCol.addView(tvTitle)
        titleCol.addView(tvSub)

        // Button to switch to Normal Phone Gallery without conflict
        val btnNormalGallery = Button(this).apply {
            text = "المعرض العادي 📂"
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@VaultMediaPickerActivity, R.color.vault_bg_dark))
            setBackgroundColor(ContextCompat.getColor(this@VaultMediaPickerActivity, R.color.vault_accent_gold))
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener {
                openNormalSystemGallery()
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
            text = "لا توجد ملفات مشفرة في هذا القسم داخل الخزنة.\nيمكنك الضغط على «المعرض العادي 📂» بالأعلى للاختيار من هاتفك."
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

        // 4. Bottom Action Bar (Confirm multi-select or Switch to Normal Gallery)
        btnConfirmMulti = Button(this).apply {
            text = "إرسال الملفات المحددة ✓"
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@VaultMediaPickerActivity, R.color.vault_bg_dark))
            setBackgroundColor(ContextCompat.getColor(this@VaultMediaPickerActivity, R.color.vault_accent_cyan))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
            ).apply {
                setMargins(dp(12), dp(6), dp(12), dp(10))
            }
            setOnClickListener {
                if (selectedItems.isNotEmpty()) {
                    deliverSelectedVaultItems(selectedItems.toList())
                }
            }
        }
        root.addView(btnConfirmMulti)

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

    /**
     * Opens the standard system document/media picker while strictly excluding our own package
     * so there is zero recursion or conflict!
     */
    private fun openNormalSystemGallery() {
        try {
            val docIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = requestedMime
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
            }
            normalGalleryLauncher.launch(docIntent)
        } catch (e: Exception) {
            Toast.makeText(this, "تعذر فتح المعرض العادي: ${e.message}", Toast.LENGTH_SHORT).show()
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

                // Grant read URI permission to calling package if known
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
            if (!allowMultiple) {
                deliverSelectedVaultItems(listOf(item))
            } else {
                if (selectedItems.contains(item)) {
                    selectedItems.remove(item)
                } else {
                    selectedItems.add(item)
                }
                notifyDataSetChanged()
                if (selectedItems.isNotEmpty()) {
                    btnConfirmMulti.visibility = View.VISIBLE
                    btnConfirmMulti.text = "إرسال (${selectedItems.size}) ملفات محددة ✓"
                } else {
                    btnConfirmMulti.visibility = View.GONE
                }
            }
        }
    }
}
