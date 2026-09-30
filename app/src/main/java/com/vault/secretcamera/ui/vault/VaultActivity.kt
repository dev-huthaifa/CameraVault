package com.vault.secretcamera.ui.vault

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.tabs.TabLayout
import com.vault.secretcamera.R
import com.vault.secretcamera.SecretVaultApp
import com.vault.secretcamera.data.VaultRepository
import com.vault.secretcamera.databinding.ActivityVaultBinding
import com.vault.secretcamera.databinding.DialogAddOptionsBinding
import com.vault.secretcamera.ads.AdManager
import com.vault.secretcamera.model.VaultCategory
import com.vault.secretcamera.model.VaultItem
import com.vault.secretcamera.security.SecurityPreferences
import com.vault.secretcamera.ui.camera.CameraActivity
import com.vault.secretcamera.ui.intruder.IntruderLogActivity
import com.vault.secretcamera.ui.settings.SettingsActivity
import com.vault.secretcamera.ui.viewer.MediaViewerActivity
import com.vault.secretcamera.util.SecureShareHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VaultActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVaultBinding
    private lateinit var repository: VaultRepository
    private lateinit var securityPrefs: SecurityPreferences
    private lateinit var adapter: VaultAdapter

    private var currentCategory = VaultCategory.ALL

    // Pick Photos from Gallery
    private val pickPhotosLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            importUris(uris)
        }
    }

    // Pick Videos from Gallery
    private val pickVideosLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            importUris(uris)
        }
    }

    // Pick Documents & Any Files
    private val pickFilesLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
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

        setupToolbar()
        setupTabs()
        setupRecyclerView()
        setupActions()
        setupAds()
    }

    private fun setupAds() {
        AdManager.loadBanner(this, binding.adBannerContainer)
        AdManager.loadInterstitial(this)
        // Automatic Interstitial on vault open!
        AdManager.autoShow(this, delayMs = 1000L, force = true)
    }

    override fun onResume() {
        super.onResume()
        loadItems()
        SecureShareHelper.cleanShareCache(this)
        AdManager.loadBanner(this, binding.adBannerContainer)
        AdManager.loadInterstitial(this)
        // Auto show ad when returning to vault
        AdManager.autoShow(this, delayMs = 800L, force = false)
    }

    override fun onDestroy() {
        super.onDestroy()
        SecureShareHelper.cleanShareCache(this)
    }

    private fun setupToolbar() {
        binding.tvDecoyBadge.visibility = View.GONE

        binding.btnLockVault.setOnClickListener {
            AdManager.showInterstitial(this, force = true) {
                securityPrefs.lockVault()
                finish()
            }
        }

        binding.btnVaultSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnIntruders.setOnClickListener {
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
                // Automatic ad on tab switch!
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
            SecureShareHelper.showShareDialog(this, repository, selected)
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

    private fun showAddOptionsDialog() {
        val dialog = BottomSheetDialog(this)
        val dialogBinding = DialogAddOptionsBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        // Option 1: Import Photos from Gallery
        dialogBinding.optImportPhotos.setOnClickListener {
            dialog.dismiss()
            try {
                pickPhotosLauncher.launch("image/*")
            } catch (e: Exception) {
                Toast.makeText(this, "تعذر فتح معرض الصور: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        // Option 2: Import Videos from Gallery
        dialogBinding.optImportVideos.setOnClickListener {
            dialog.dismiss()
            try {
                pickVideosLauncher.launch("video/*")
            } catch (e: Exception) {
                Toast.makeText(this, "تعذر فتح استديو الفيديو: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        // Option 3: Import Documents & Files
        dialogBinding.optImportFiles.setOnClickListener {
            dialog.dismiss()
            try {
                pickFilesLauncher.launch(arrayOf("*/*"))
            } catch (e: Exception) {
                Toast.makeText(this, "تعذر فتح مدير الملفات: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        // Option 4: Shoot Secret Direct Photo
        dialogBinding.optSecretCapture.setOnClickListener {
            dialog.dismiss()
            val intent = Intent(this, CameraActivity::class.java).apply {
                putExtra(CameraActivity.EXTRA_SECRET_CAPTURE, true)
            }
            startActivity(intent)
        }

        dialog.show()
    }

    private fun importUris(uris: List<Uri>) {
        Toast.makeText(this, "جاري تشفير الملفات وإخفائها من المعرض...", Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.IO).launch {
            var successCount = 0
            for (uri in uris) {
                val res = repository.importAndHideFile(uri)
                if (res.isSuccess) {
                    successCount++
                }
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@VaultActivity,
                    "تم تشفير وحذف $successCount ملفات من المعرض ومدير الملفات بنجاح!",
                    Toast.LENGTH_LONG
                ).show()
                loadItems()
                AdManager.showInterstitial(this@VaultActivity, force = true)
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
                Toast.makeText(this@VaultActivity, "تم الحذف النهائي", Toast.LENGTH_SHORT).show()
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
            super.onBackPressed()
        }
    }
}
