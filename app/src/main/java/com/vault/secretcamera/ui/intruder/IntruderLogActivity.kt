package com.vault.secretcamera.ui.intruder

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.vault.secretcamera.SecretVaultApp
import com.vault.secretcamera.ads.AdManager
import com.vault.secretcamera.data.VaultRepository
import com.vault.secretcamera.databinding.ActivityIntruderLogBinding
import com.vault.secretcamera.security.SecurityPreferences

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
    }

    override fun onResume() {
        super.onResume()
        if (!securityPrefs.isUnlocked()) {
            finish()
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && !AdManager.isAdShowing) {
            securityPrefs.lockVault()
            finish()
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.intruderToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.intruderToolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupRecyclerView() {
        adapter = IntruderAdapter(repository) { log ->
            repository.deleteIntruder(log)
            loadLogs()
        }
        binding.rvIntruders.layoutManager = LinearLayoutManager(this)
        binding.rvIntruders.adapter = adapter
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
