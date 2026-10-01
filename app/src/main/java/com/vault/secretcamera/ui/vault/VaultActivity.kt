package com.vault.secretcamera.ui.vault

import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.tabs.TabLayout
import com.vault.secretcamera.R
import com.vault.secretcamera.SecretVaultApp
import com.vault.secretcamera.ads.AdManager
import com.vault.secretcamera.data.VaultRepository
import com.vault.secretcamera.databinding.ActivityVaultBinding
import com.vault.secretcamera.databinding.DialogAddOptionsBinding
import com.vault.secretcamera.model.VaultCategory
import com.vault.secretcamera.model.VaultItem
import com.vault.secretcamera.security.SecurityPreferences
import com.vault.secretcamera.ui.camera.CameraActivity
import com.vault.secretcamera.ui.intruder.IntruderLogActivity
import com.vault.secretcamera.ui.settings.SettingsActivity
import com.vault.secretcamera.ui.viewer.MediaViewerActivity
import com.vault.secretcamera.util.SecureShareHelper
import com.vault.secretcamera.util.SecurityAuditDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VaultActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityVaultBinding
    private lateinit var repository: VaultRepository
    private lateinit var securityPrefs: SecurityPreferences
    private lateinit var adapter: VaultAdapter

    private var currentCategory = VaultCategory.ALL
    private var isPickingMedia = false

    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var proximitySensor: Sensor? = null
    private var isProximityNear = false
    private var lastFlipTime = 0L

    // System Delete Request for Android 11+ (Permanently deletes files from MediaStore & Trash)
    private val deleteRequestLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        isPickingMedia = false
        if (result.resultCode == RESULT_OK) {
            Toast.makeText(this, "تم الحذف النهائي للصور من المعرض وسلة المهملات بنجاح!", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "تم حفظ الملفات وتشفيرها في الخزنة", Toast.LENGTH_SHORT).show()
        }
        loadItems()
    }

    // Permission launcher for Manage All Files Access (Android 11+)
    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        isPickingMedia = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            Toast.makeText(this, "تم تفعيل إذن النقل والحذف الفوري التلقائي!", Toast.LENGTH_SHORT).show()
        }
    }

    // Pick Photos from Gallery
    private val pickPhotosLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        isPickingMedia = false
        if (uris.isNotEmpty()) {
            importUris(uris)
        }
    }

    // Pick Videos from Gallery
    private val pickVideosLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        isPickingMedia = false
        if (uris.isNotEmpty()) {
            importUris(uris)
        }
    }

    // Pick Documents & Any Files
    private val pickFilesLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        isPickingMedia = false
        if (uris.isNotEmpty()) {
            importUris(uris)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVaultBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val app = application as SecretVaultApp
        repository = app.vaultRepository
        securityPrefs = app.securityPreferences

        // Check if unlocked
        if (!securityPrefs.isUnlocked()) {
            finish()
            return
        }

        // Apply FLAG_SECURE
        if (securityPrefs.isAntiScreenshotEnabled) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        }

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        proximitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)

        setupToolbar()
        setupTabs()
        setupRecyclerView()
        setupActions()
        setupAds()
    }

    private fun setupAds() {
        AdManager.loadBanner(this, binding.adBannerContainer)
        AdManager.loadInterstitial(this)
        AdManager.autoShow(this, delayMs = 1000L, force = false)
    }

    override fun onResume() {
        super.onResume()
        if (!securityPrefs.isUnlocked()) {
            finish()
            return
        }
        isPickingMedia = false
        loadItems()
        AdManager.loadBanner(this, binding.adBannerContainer)
        AdManager.loadInterstitial(this)
        AdManager.autoShow(this, delayMs = 800L, force = false)

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
        // Auto-lock and exit to Camera if user backgrounds the app,
        // but guard against interstitial ads and media pickers taking foreground.
        if (!isPickingMedia && !isChangingConfigurations && !AdManager.isAdShowing) {
            securityPrefs.lockVault()
            finish()
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
            // True flip face down:
            // Either phone is facing downward on a surface (z < -5.0f && isProximityNear),
            // or phone is placed flat face-down (z < -8.5f).
            // This prevents accidental locks when looking up at the screen in bed.
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

    private fun setupToolbar() {
        // Live Cryptographic Audit Button
        binding.btnSecurityAudit.setOnClickListener {
            SecurityAuditDialog.show(this, repository, securityPrefs)
        }

        binding.btnLockVault.setOnClickListener {
            securityPrefs.lockVault()
            finish()
        }

        // Settings Button
        binding.btnVaultSettings.setOnClickListener {
            isPickingMedia = true
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Intruder Logs
        binding.btnIntruders.setOnClickListener {
            isPickingMedia = true
            startActivity(Intent(this, IntruderLogActivity::class.java))
        }
    }

    private fun setupTabs() {
        val categories = listOf(
            VaultCategory.ALL to getString(R.string.tab_all),
            VaultCategory.PHOTOS to getString(R.string.tab_photos),
            VaultCategory.VIDEOS to getString(R.string.tab_videos),
            VaultCategory.DOCUMENTS to getString(R.string.tab_documents),
            VaultCategory.AUDIO to getString(R.string.tab_audio)
        )

        for ((_, title) in categories) {
            binding.categoryTabs.addTab(binding.categoryTabs.newTab().setText(title))
        }

        binding.categoryTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentCategory = categories.getOrNull(tab?.position ?: 0)?.first ?: VaultCategory.ALL
                loadItems()
                AdManager.showInterstitial(this@VaultActivity, force = false)
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun setupRecyclerView() {
        adapter = VaultAdapter(
            repository = repository,
            onItemClick = { item ->
                isPickingMedia = true
                val intent = Intent(this, MediaViewerActivity::class.java).apply {
                    putExtra(MediaViewerActivity.EXTRA_ITEM_ID, item.id)
                }
                startActivity(intent)
            },
            onSelectionChanged = { count ->
                if (count > 0) {
                    binding.selectionActionBar.visibility = View.VISIBLE
                    binding.tvSelectionCount.text = "$count محددة"
                    binding.fabAdd.hide()
                } else {
                    binding.selectionActionBar.visibility = View.GONE
                    binding.fabAdd.show()
                }
            }
        )

        val gridLayoutManager = GridLayoutManager(this, 3)
        gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (adapter.getItemViewType(position) == VaultAdapter.VIEW_TYPE_GRID) 1 else 3
            }
        }

        binding.rvVaultItems.layoutManager = gridLayoutManager
        binding.rvVaultItems.adapter = adapter
    }

    private fun setupActions() {
        binding.fabAdd.setOnClickListener {
            showAddOptionsDialog()
        }

        // Direct Secure Share (WhatsApp, Instagram, etc.)
        binding.btnBatchShare.setOnClickListener {
            val selected = adapter.getSelectedItems()
            if (selected.isEmpty()) return@setOnClickListener
            SecureShareHelper.showShareDialog(this, repository, selected) {
                isPickingMedia = true
            }
        }

        // Batch Restore
        binding.btnBatchRestore.setOnClickListener {
            val selected = adapter.getSelectedItems()
            if (selected.isEmpty()) return@setOnClickListener

            AlertDialog.Builder(this)
                .setTitle(R.string.restore_confirm_title)
                .setMessage(R.string.restore_confirm_message)
                .setPositiveButton(R.string.btn_restore) { _, _ ->
                    restoreSelectedItems(selected)
                }
                .setNegativeButton(R.string.btn_cancel, null)
                .show()
        }

        // Batch Delete
        binding.btnBatchDelete.setOnClickListener {
            val selected = adapter.getSelectedItems()
            if (selected.isEmpty()) return@setOnClickListener

            AlertDialog.Builder(this)
                .setTitle(R.string.delete_confirm_title)
                .setMessage(R.string.delete_confirm_message)
                .setPositiveButton(R.string.btn_delete) { _, _ ->
                    deleteSelectedItems(selected)
                }
                .setNegativeButton(R.string.btn_cancel, null)
                .show()
        }
    }

    private fun checkStoragePermissionBeforePicker(onReady: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            AlertDialog.Builder(this)
                .setTitle("صلاحية النقل والحذف التلقائي")
                .setMessage("لحذف الصور والفيديوهات تلقائياً ونهائياً من المعرض وسلة المهملات فور نقلها للخزنة وبدون الحاجة لتأكيد الحذف في كل مرة، يرجى تفعيل إذن (الوصول لجميع الملفات).")
                .setPositiveButton("تفعيل الإذن") { _, _ ->
                    isPickingMedia = true
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        manageStorageLauncher.launch(intent)
                    } catch (e: Exception) {
                        try {
                            val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                            manageStorageLauncher.launch(intent)
                        } catch (_: Exception) {
                            onReady()
                        }
                    }
                }
                .setNegativeButton("متابعة بدون إذن") { _, _ ->
                    onReady()
                }
                .show()
        } else {
            onReady()
        }
    }

    private fun showAddOptionsDialog() {
        val dialog = BottomSheetDialog(this)
        val dialogBinding = DialogAddOptionsBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        // Option 1: Import Photos from Gallery
        dialogBinding.optImportPhotos.setOnClickListener {
            dialog.dismiss()
            checkStoragePermissionBeforePicker {
                try {
                    isPickingMedia = true
                    pickPhotosLauncher.launch("image/*")
                } catch (e: Exception) {
                    isPickingMedia = false
                    Toast.makeText(this, "تعذر فتح معرض الصور: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Option 2: Import Videos from Gallery
        dialogBinding.optImportVideos.setOnClickListener {
            dialog.dismiss()
            checkStoragePermissionBeforePicker {
                try {
                    isPickingMedia = true
                    pickVideosLauncher.launch("video/*")
                } catch (e: Exception) {
                    isPickingMedia = false
                    Toast.makeText(this, "تعذر فتح استديو الفيديو: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Option 3: Import Documents & Files
        dialogBinding.optImportFiles.setOnClickListener {
            dialog.dismiss()
            checkStoragePermissionBeforePicker {
                try {
                    isPickingMedia = true
                    pickFilesLauncher.launch(arrayOf("*/*"))
                } catch (e: Exception) {
                    isPickingMedia = false
                    Toast.makeText(this, "تعذر فتح مدير الملفات: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Option 4: Shoot Secret Direct Photo
        dialogBinding.optSecretCapture.setOnClickListener {
            dialog.dismiss()
            isPickingMedia = true
            val intent = Intent(this, CameraActivity::class.java).apply {
                putExtra(CameraActivity.EXTRA_SECRET_CAPTURE, true)
            }
            startActivity(intent)
        }

        dialog.show()
    }

    private fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return

        val progressDialog = ProgressDialog(this).apply {
            setTitle("حفظ وتشفير الملفات")
            setMessage("جاري التشفير والإخفاء من المعرض وسلة المهملات...")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = uris.size
            progress = 0
            setCancelable(false)
            show()
        }

        CoroutineScope(Dispatchers.IO).launch {
            val result = repository.importBatch(uris) { current, total ->
                withContext(Dispatchers.Main) {
                    progressDialog.progress = current
                    progressDialog.setMessage("جاري تشفير وإخفاء الملف ($current من $total)...")
                }
            }

            withContext(Dispatchers.Main) {
                try { progressDialog.dismiss() } catch (_: Exception) {}
                loadItems()
                AdManager.showInterstitial(this@VaultActivity, force = false)

                // If Scoped Storage requires system consent dialog to permanently delete from Gallery & Trash
                if (result.pendingDeleteUris.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        isPickingMedia = true
                        val pendingIntent = MediaStore.createDeleteRequest(contentResolver, result.pendingDeleteUris)
                        val intentSenderRequest = IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                        deleteRequestLauncher.launch(intentSenderRequest)
                    } catch (e: Exception) {
                        isPickingMedia = false
                        e.printStackTrace()
                        Toast.makeText(
                            this@VaultActivity,
                            "تم بنجاح تشفير وحفظ ${result.successCount} ملفات في الخزنة!",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    Toast.makeText(
                        this@VaultActivity,
                        "تم بنجاح تشفير وحفظ ${result.successCount} ملفات واختفاؤها نهائياً من المعرض وسلة المهملات!",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun restoreSelectedItems(items: List<VaultItem>) {
        CoroutineScope(Dispatchers.IO).launch {
            var successCount = 0
            for (item in items) {
                val res = repository.restoreFileToStorage(item)
                if (res.isSuccess) successCount++
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@VaultActivity,
                    "تمت استعادة $successCount ملفات إلى المعرض بنجاح",
                    Toast.LENGTH_SHORT
                ).show()
                adapter.clearSelection()
                loadItems()
            }
        }
    }

    private fun deleteSelectedItems(items: List<VaultItem>) {
        CoroutineScope(Dispatchers.IO).launch {
            for (item in items) {
                repository.deletePermanently(item)
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(this@VaultActivity, "تم الحذف النهائي من الخزنة", Toast.LENGTH_SHORT).show()
                adapter.clearSelection()
                loadItems()
            }
        }
    }

    private fun loadItems() {
        val items = repository.getItems(currentCategory)
        adapter.submitList(items, currentCategory)

        if (items.isEmpty()) {
            binding.emptyStateView.visibility = View.VISIBLE
            binding.rvVaultItems.visibility = View.GONE
        } else {
            binding.emptyStateView.visibility = View.GONE
            binding.rvVaultItems.visibility = View.VISIBLE
        }
    }

    override fun onBackPressed() {
        if (adapter.isSelectionMode) {
            adapter.clearSelection()
        } else {
            securityPrefs.lockVault()
            finish()
        }
    }
}
