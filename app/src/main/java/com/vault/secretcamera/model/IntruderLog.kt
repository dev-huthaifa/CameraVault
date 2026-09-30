package com.vault.secretcamera.model

import org.json.JSONObject

data class IntruderLog(
    val id: String,
    val photoFileName: String,
    val timestamp: Long,
    val reason: String = "محاولة إدخال خاطئة"
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("photoFileName", photoFileName)
            put("timestamp", timestamp)
            put("reason", reason)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): IntruderLog {
            return IntruderLog(
                id = json.getString("id"),
                photoFileName = json.getString("photoFileName"),
                timestamp = json.getLong("timestamp"),
                reason = json.optString("reason", "محاولة إدخال خاطئة")
            )
        }
    }
}
