package com.vault.secretcamera.ui.auth

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.view.WindowManager
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.vault.secretcamera.R
import com.vault.secretcamera.SecretVaultApp
import com.vault.secretcamera.databinding.ActivityAuthBinding
import com.vault.secretcamera.security.SecurityPreferences
import com.vault.secretcamera.ui.vault.VaultActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.Executor

class AuthActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAuthBinding
    private lateinit var securityPrefs: SecurityPreferences
    private val enteredPin = StringBuilder()
    private var isFirstTimeSetup = false
    private var tempFirstPin: String? = null

    private lateinit var biometricExecutor: Executor
    private lateinit var biometricPrompt: BiometricPrompt
    private lateinit var promptInfo: BiometricPrompt.PromptInfo

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        securityPrefs = (application as SecretVaultApp).securityPreferences

        // Anti-Screenshot (FLAG_SECURE)
        if (securityPrefs.isAntiScreenshotEnabled) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        }

        isFirstTimeSetup = !securityPrefs.isPinConfigured()

        setupKeypad()
        updateUIState()

        if (!isFirstTimeSetup && securityPrefs.isBiometricEnabled) {
            setupBiometric()
            biometricPrompt.authenticate(promptInfo)
        }
    }

    private fun updateUIState() {
        if (isFirstTimeSetup) {
            binding.tvAuthTitle.text = getString(R.string.setup_pin_title)
            binding.tvAuthSubtitle.text = if (tempFirstPin == null) {
                "أدخل رمز الحماية السري (4 أرقام)"
            } else {
                getString(R.string.confirm_pin_title)
            }
            binding.btnBiometric.visibility = View.INVISIBLE
        } else {
            binding.tvAuthTitle.text = getString(R.string.vault_title)
            binding.tvAuthSubtitle.text = getString(R.string.enter_pin)
            binding.btnBiometric.visibility = if (securityPrefs.isBiometricEnabled) View.VISIBLE else View.INVISIBLE
        }
        updatePinDots()
    }

    private fun setupKeypad() {
        val numberButtons = listOf(
            binding.btn0, binding.btn1, binding.btn2, binding.btn3, binding.btn4,
            binding.btn5, binding.btn6, binding.btn7, binding.btn8, binding.btn9
        )

        for (btn in numberButtons) {
            btn.setOnClickListener {
                if (enteredPin.length < 4) {
                    enteredPin.append((it as Button).text)
                    updatePinDots()
                    vibrateKey()

                    if (enteredPin.length == 4) {
                        processCompletedPin(enteredPin.toString())
                    }
                }
            }
        }

        binding.btnBackspace.setOnClickListener {
            if (enteredPin.isNotEmpty()) {
                enteredPin.deleteCharAt(enteredPin.length - 1)
                updatePinDots()
                vibrateKey()
                binding.tvErrorMessage.visibility = View.INVISIBLE
            }
        }

        binding.btnCloseAuth.setOnClickListener {
            finish()
        }

        binding.btnBiometric.setOnClickListener {
            if (!isFirstTimeSetup && securityPrefs.isBiometricEnabled) {
                setupBiometric()
                biometricPrompt.authenticate(promptInfo)
            }
        }
    }

    private fun updatePinDots() {
        val dots = listOf(binding.dot1, binding.dot2, binding.dot3, binding.dot4)
        for (i in dots.indices) {
            if (i < enteredPin.length) {
                dots[i].setBackgroundColor(ContextCompat.getColor(this, R.color.vault_accent_cyan))
            } else {
                dots[i].setBackgroundResource(R.drawable.bg_pin_digit)
            }
        }
    }

    private fun processCompletedPin(pin: String) {
        if (isFirstTimeSetup) {
            handleSetupPin(pin)
        } else {
            handleUnlockPin(pin)
        }
    }

    private fun handleSetupPin(pin: String) {
        if (tempFirstPin == null) {
            tempFirstPin = pin
            enteredPin.clear()
            updateUIState()
            Toast.makeText(this, "يرجى تأكيد الرمز مرة أخرى", Toast.LENGTH_SHORT).show()
        } else {
            if (tempFirstPin == pin) {
                securityPrefs.setMasterPin(pin)
                Toast.makeText(this, "تم تعيين الرمز السري بنجاح!", Toast.LENGTH_SHORT).show()
                openVault()
            } else {
                tempFirstPin = null
                enteredPin.clear()
                updateUIState()
                showError("الرمزان غير متطابقين، أعد المحاولة من البداية")
            }
        }
    }

    private fun handleUnlockPin(pin: String) {
        val normPin = securityPrefs.normalizePin(pin)

        // 1. Check Master PIN
        if (securityPrefs.verifyMasterPin(normPin)) {
            openVault()
            return
        }

        // 2. Check Decoy PIN (Completely silent and seamless, no toast or hint)
        if (securityPrefs.hasDecoyPin() && securityPrefs.verifyDecoyPin(normPin)) {
            openVault()
            return
        }

        // 3. Wrong PIN
        val failedCount = securityPrefs.recordFailedAttempt()
        showError(getString(R.string.wrong_pin))
        enteredPin.clear()
        updatePinDots()

        // If >= 3 failed attempts, record intruder incident
        if (failedCount >= 3 && securityPrefs.isIntruderSelfieEnabled) {
            triggerIntruderCapture()
        }
    }

    private fun triggerIntruderCapture() {
        CoroutineScope(Dispatchers.IO).launch {
            val repo = (application as SecretVaultApp).vaultRepository
            // Create a placeholder alert capture log
            val dummyBytes = ByteArray(0)
            repo.saveIntruderPhoto(dummyBytes)
        }
    }

    private fun openVault() {
        val intent = Intent(this, VaultActivity::class.java)
        startActivity(intent)
        finish()
    }

    private fun showError(message: String) {
        binding.tvErrorMessage.text = message
        binding.tvErrorMessage.visibility = View.VISIBLE
        vibrateError()

        // Shake animation on dots
        val shake = AnimationUtils.loadAnimation(this, android.R.anim.slide_in_left)
        binding.dotsContainer.startAnimation(shake)
    }

    private fun vibrateKey() {
        try {
            val v = getVibrator()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v?.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v?.vibrate(30)
            }
        } catch (_: Exception) {}
    }

    private fun vibrateError() {
        try {
            val v = getVibrator()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 80, 50, 80), -1))
            } else {
                @Suppress("DEPRECATION")
                v?.vibrate(longArrayOf(0, 80, 50, 80), -1)
            }
        } catch (_: Exception) {}
    }

    private fun getVibrator(): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private fun setupBiometric() {
        biometricExecutor = ContextCompat.getMainExecutor(this)
        biometricPrompt = BiometricPrompt(
            this,
            biometricExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    openVault()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    showError("فشل التحقق من البصمة")
                }
            }
        )

        promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.biometric_prompt_title))
            .setSubtitle(getString(R.string.biometric_prompt_desc))
            .setNegativeButtonText(getString(R.string.btn_cancel))
            .build()
    }
}
