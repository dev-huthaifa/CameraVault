package com.vault.secretcamera

import android.app.Application
import com.vault.secretcamera.data.VaultRepository
import com.vault.secretcamera.security.SecurityPreferences

class SecretVaultApp : Application() {

    lateinit var securityPreferences: SecurityPreferences
    lateinit var vaultRepository: VaultRepository

    override fun onCreate() {
        super.onCreate()
        instance = this
        securityPreferences = SecurityPreferences(this)
        vaultRepository = VaultRepository(this, securityPreferences)
        com.vault.secretcamera.ads.AdManager.init(this)

        // Clean up any residual mounted studio album on startup and auto-start AppLockService
        try {
            com.vault.secretcamera.service.VaultStudioMountManager.unmountFromGeneralStudio(this)
            val appVaultRepo = com.vault.secretcamera.data.AppVaultRepository(this)
            if (appVaultRepo.isAppLockShieldEnabled() &&
                com.vault.secretcamera.service.AppLockManager.hasUsageStatsPermission(this)
            ) {
                com.vault.secretcamera.service.AppLockManager.startLockService(this)
            }
        } catch (_: Exception) {}
    }

    companion object {
        lateinit var instance: SecretVaultApp
            private set
    }
}
