package com.vault.secretcamera.model

import java.io.Serializable

enum class BuiltInAppType {
    SECRET_BROWSER,
    SECRET_NOTES,
    VAULT_STUDIO
}

data class AppVaultItem(
    val packageName: String,
    val appName: String,
    val isLockedFromOutside: Boolean = true,
    val isBuiltIn: Boolean = false,
    val builtInType: BuiltInAppType? = null,
    val addedTimestamp: Long = System.currentTimeMillis()
) : Serializable
