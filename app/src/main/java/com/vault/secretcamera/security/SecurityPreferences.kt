package com.vault.secretcamera.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.security.MessageDigest
import javax.crypto.SecretKey

class SecurityPreferences(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("vault_security_prefs", Context.MODE_PRIVATE)

    var currentMasterKey: SecretKey? = null
    var isDecoySession: Boolean = false

    // Master PIN setup check
    fun isPinConfigured(): Boolean {
        return prefs.contains(KEY_PIN_HASH)
    }

    // Normalize digits (supports Arabic numerals ٠-٩ and English 0-9)
    fun normalizePin(pin: String): String {
        return pin.trim().map { ch ->
            when (ch) {
                in '٠'..'٩' -> ('0'.code + (ch.code - '٠'.code)).toChar()
                in '۰'..'۹' -> ('0'.code + (ch.code - '۰'.code)).toChar()
                else -> ch
            }
        }.joinToString("")
    }

    // Save new Master PIN
    fun setMasterPin(pin: String) {
        val norm = normalizePin(pin)
        val salt = CryptoManager.generateSalt()
        val hash = hashPin(norm, salt)
        val saltStr = Base64.encodeToString(salt, Base64.NO_WRAP)
        val hashStr = Base64.encodeToString(hash, Base64.NO_WRAP)

        prefs.edit()
            .putString(KEY_PIN_SALT, saltStr)
            .putString(KEY_PIN_HASH, hashStr)
            .apply()

        // Prepare session key
        currentMasterKey = CryptoManager.deriveKey(norm, salt)
    }

    // Verify Master PIN
    fun verifyMasterPin(pin: String): Boolean {
        val norm = normalizePin(pin)
        val saltStr = prefs.getString(KEY_PIN_SALT, null) ?: return false
        val hashStr = prefs.getString(KEY_PIN_HASH, null) ?: return false

        val salt = Base64.decode(saltStr, Base64.NO_WRAP)
        val expectedHash = Base64.decode(hashStr, Base64.NO_WRAP)
        val actualHash = hashPin(norm, salt)

        val matches = MessageDigest.isEqual(expectedHash, actualHash)
        if (matches) {
            currentMasterKey = CryptoManager.deriveKey(norm, salt)
            isDecoySession = false
            resetFailedAttempts()
        }
        return matches
    }

    // Decoy (Fake) PIN setup
    fun setDecoyPin(pin: String) {
        val norm = normalizePin(pin)
        val salt = CryptoManager.generateSalt()
        val hash = hashPin(norm, salt)
        val saltStr = Base64.encodeToString(salt, Base64.NO_WRAP)
        val hashStr = Base64.encodeToString(hash, Base64.NO_WRAP)

        prefs.edit()
            .putString(KEY_DECOY_SALT, saltStr)
            .putString(KEY_DECOY_HASH, hashStr)
            .apply()
    }

    fun hasDecoyPin(): Boolean {
        return prefs.contains(KEY_DECOY_HASH)
    }

    // Verify Decoy PIN
    fun verifyDecoyPin(pin: String): Boolean {
        val norm = normalizePin(pin)
        val saltStr = prefs.getString(KEY_DECOY_SALT, null) ?: return false
        val hashStr = prefs.getString(KEY_DECOY_HASH, null) ?: return false

        val salt = Base64.decode(saltStr, Base64.NO_WRAP)
        val expectedHash = Base64.decode(hashStr, Base64.NO_WRAP)
        val actualHash = hashPin(norm, salt)

        val matches = MessageDigest.isEqual(expectedHash, actualHash)
        if (matches) {
            currentMasterKey = CryptoManager.deriveKey(norm, salt)
            isDecoySession = true
            resetFailedAttempts()
        }
        return matches
    }

    // Failed PIN attempt tracking for Intruder Selfie
    fun recordFailedAttempt(): Int {
        val count = prefs.getInt(KEY_FAILED_ATTEMPTS, 0) + 1
        prefs.edit().putInt(KEY_FAILED_ATTEMPTS, count).apply()
        return count
    }

    fun resetFailedAttempts() {
        prefs.edit().putInt(KEY_FAILED_ATTEMPTS, 0).apply()
    }

    // Anti-Screenshot (FLAG_SECURE)
    var isAntiScreenshotEnabled: Boolean
        get() = prefs.getBoolean(KEY_ANTI_SCREENSHOT, true)
        set(value) = prefs.edit().putBoolean(KEY_ANTI_SCREENSHOT, value).apply()

    // Intruder Selfie
    var isIntruderSelfieEnabled: Boolean
        get() = prefs.getBoolean(KEY_INTRUDER_SELFIE, true)
        set(value) = prefs.edit().putBoolean(KEY_INTRUDER_SELFIE, value).apply()

    // Biometric Login
    var isBiometricEnabled: Boolean
        get() = prefs.getBoolean(KEY_BIOMETRIC, false)
        set(value) = prefs.edit().putBoolean(KEY_BIOMETRIC, value).apply()

    // Secret Gesture Shape (Default: CIRCLE)
    var secretGestureShape: String
        get() = prefs.getString(KEY_SECRET_GESTURE_SHAPE, SHAPE_CIRCLE) ?: SHAPE_CIRCLE
        set(value) = prefs.edit().putString(KEY_SECRET_GESTURE_SHAPE, value).apply()

    // Show Gesture Stroke (Default: false -> completely invisible)
    var showGestureStroke: Boolean
        get() = prefs.getBoolean(KEY_SHOW_GESTURE_STROKE, false)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_GESTURE_STROKE, value).apply()

    // Flip to Lock (Emergency Face-Down Lock)
    var isFlipToLockEnabled: Boolean
        get() = prefs.getBoolean(KEY_FLIP_TO_LOCK, true)
        set(value) = prefs.edit().putBoolean(KEY_FLIP_TO_LOCK, value).apply()

    // Military Data Shredding (Anti-Forensic Overwrite)
    var isDataShreddingEnabled: Boolean
        get() = prefs.getBoolean(KEY_DATA_SHREDDING, true)
        set(value) = prefs.edit().putBoolean(KEY_DATA_SHREDDING, value).apply()

    // Clear session key upon locking
    fun lockVault() {
        currentMasterKey = null
        isDecoySession = false
    }

    fun isUnlocked(): Boolean = currentMasterKey != null

    private fun hashPin(pin: String, salt: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt)
        return md.digest(pin.toByteArray(Charsets.UTF_8))
    }

    companion object {
        const val SHAPE_CIRCLE = "CIRCLE"
        const val SHAPE_TRIANGLE = "TRIANGLE"
        const val SHAPE_SQUARE = "SQUARE"
        const val SHAPE_Z = "Z_SHAPE"
        const val SHAPE_MULTI_TAP = "MULTI_TAP"

        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_PIN_SALT = "pin_salt"
        private const val KEY_DECOY_HASH = "decoy_hash"
        private const val KEY_DECOY_SALT = "decoy_salt"
        private const val KEY_ANTI_SCREENSHOT = "anti_screenshot"
        private const val KEY_INTRUDER_SELFIE = "intruder_selfie"
        private const val KEY_BIOMETRIC = "biometric_enabled"
        private const val KEY_FAILED_ATTEMPTS = "failed_attempts"
        private const val KEY_SECRET_GESTURE_SHAPE = "secret_gesture_shape"
        private const val KEY_SHOW_GESTURE_STROKE = "show_gesture_stroke"
        private const val KEY_FLIP_TO_LOCK = "flip_to_lock"
        private const val KEY_DATA_SHREDDING = "data_shredding"
    }
}
