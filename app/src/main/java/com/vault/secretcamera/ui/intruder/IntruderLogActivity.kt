package com.vault.secretcamera.ui.intruder

import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.vault.secretcamera.SecretVaultApp
import com.vault.secretcamera.ads.AdManager
import com.vault.secretcamera.data.VaultRepository
import com.vault.secretcamera.databinding.ActivityIntruderLogBinding
import com.vault.secretcamera.model.IntruderLog
import com.vault.secretcamera.security.SecurityPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class IntruderLogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityIntruderLogBinding
    private lateinit var repository: VaultRepository
    private lateinit var securityPrefs: SecurityPreferences
    private lateinit var adapter: IntruderAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityIntruderLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val app = application as SecretVaultApp
        repository = app.vaultRepository
        securityPrefs = app.securityPreferences

        if (!securityPrefs.isUnlocked()) {
            finish()
            return
        }

        if (securityPrefs.isAntiScreenshotEnabled) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        }

        setupToolbar()
        setupRecyclerView()
        loadLogs()
        AdManager.loadBanner(this, binding.adBannerIntruderContainer)
        AdManager.loadInterstitial(this)
    }

    override fun onResume() {
        super.onResume()
        if (!securityPrefs.isUnlocked()) {
            finish()
            return
        }
        AdManager.loadBanner(this, binding.adBannerIntruderContainer)
    }

    override fun onStop() {
        super.onStop()
        // Never lock vault when finishing (returning to VaultActivity)!
        if (!isFinishing && !isChangingConfigurations && !AdManager.isAdShowing) {
            securityPrefs.lockVault()
            finish()
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.intruderToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.intruderToolbar.setNavigationOnClickListener {
            AdManager.showInterstitial(this) {
                finish()
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = IntruderAdapter(
            repository = repository,
            onItemClick = { log ->
                showIntruderPreviewDialog(log)
            },
            onDeleteClick = { log ->
                repository.deleteIntruder(log)
                loadLogs()
            }
        )
        binding.rvIntruders.layoutManager = LinearLayoutManager(this)
        binding.rvIntruders.adapter = adapter
    }

    private fun showIntruderPreviewDialog(log: IntruderLog) {
        val file = repository.getIntruderFile(log)
        val bmp = if (file.exists() && file.length() > 0) BitmapFactory.decodeFile(file.absolutePath) else null
        val dateStr = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault()).format(Date(log.timestamp))

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 16)
        }

        if (bmp != null) {
            val imageView = ImageView(this).apply {
                setImageBitmap(bmp)
                adjustViewBounds = true
                maxHeight = (resources.displayMetrics.heightPixels * 0.55f).toInt()
            }
            container.addView(imageView)
        }

        val infoView = TextView(this).apply {
            text = "\nالوقت: $dateStr\nالتفاصيل: ${log.reason}"
            textSize = 14f
        }
        container.addView(infoView)

        AlertDialog.Builder(this)
            .setTitle("🚨 تفاصيل محاولة الاختراق")
            .setView(container)
            .setPositiveButton("إغلاق", null)
            .setNeutralButton("حذف السجل") { _, _ ->
                repository.deleteIntruder(log)
                loadLogs()
            }
            .show()
    }

    private fun loadLogs() {
        val list = repository.getIntruders()
        adapter.submitList(list)

        if (list.isEmpty()) {
            binding.tvNoIntruders.visibility = View.VISIBLE
            binding.rvIntruders.visibility = View.GONE
        } else {
            binding.tvNoIntruders.visibility = View.GONE
            binding.rvIntruders.visibility = View.VISIBLE
        }
    }
}
