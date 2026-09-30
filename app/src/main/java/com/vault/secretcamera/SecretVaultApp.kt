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
    }

    companion object {
        lateinit var instance: SecretVaultApp
            private set
    }
}
