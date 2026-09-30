package com.vault.secretcamera.model

import org.json.JSONObject

data class VaultItem(
    val id: String,
    val originalName: String,
    val encryptedFileName: String,
    val category: VaultCategory,
    val mimeType: String,
    val fileSize: Long,
    val dateAdded: Long,
    val isDecoy: Boolean = false,
    var isSelected: Boolean = false
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("originalName", originalName)
            put("encryptedFileName", encryptedFileName)
            put("category", category.name)
            put("mimeType", mimeType)
            put("fileSize", fileSize)
            put("dateAdded", dateAdded)
            put("isDecoy", isDecoy)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): VaultItem {
            return VaultItem(
                id = json.getString("id"),
                originalName = json.getString("originalName"),
                encryptedFileName = json.getString("encryptedFileName"),
                category = VaultCategory.valueOf(json.optString("category", VaultCategory.OTHER.name)),
                mimeType = json.optString("mimeType", "application/octet-stream"),
                fileSize = json.optLong("fileSize", 0L),
                dateAdded = json.optLong("dateAdded", System.currentTimeMillis()),
                isDecoy = json.optBoolean("isDecoy", false)
            )
        }
    }
}
