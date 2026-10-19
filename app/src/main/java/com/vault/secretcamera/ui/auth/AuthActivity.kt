package com.vault.secretcamera.ui.auth

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
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
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.vault.secretcamera.R
import com.vault.secretcamera.SecretVaultApp
import com.vault.secretcamera.data.AppVaultRepository
import com.vault.secretcamera.databinding.ActivityAuthBinding
import com.vault.secretcamera.security.SecurityPreferences
import com.vault.secretcamera.ui.vault.VaultActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class AuthActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_LOCKED_PACKAGE = "extra_locked_package"
    }

    private lateinit var binding: ActivityAuthBinding
    private lateinit var securityPrefs: SecurityPreferences
    private val enteredPin = StringBuilder()
    private var isFirstTimeSetup = false
    private var tempFirstPin: String? = null

    private lateinit var biometricExecutor: Executor
    private lateinit var biometricPrompt: BiometricPrompt
    private lateinit var promptInfo: BiometricPrompt.PromptInfo

    // Silent Front Camera for Intruder Selfie Capture
    private var intruderImageCapture: ImageCapture? = null
    private lateinit var intruderCameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        securityPrefs = (application as SecretVaultApp).securityPreferences
        intruderCameraExecutor = Executors.newSingleThreadExecutor()

        // Anti-Screenshot (FLAG_SECURE)
        if (securityPrefs.isAntiScreenshotEnabled) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        }

        isFirstTimeSetup = !securityPrefs.isPinConfigured()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleCancelOrBack()
            }
        })

        setupKeypad()
        updateUIState()
        setupSilentIntruderCamera()

        if (!isFirstTimeSetup && securityPrefs.isBiometricEnabled) {
            setupBiometric()
            biometricPrompt.authenticate(promptInfo)
        }
    }

    /**
     * Binds CameraX ImageCapture silently to the Front Camera (without any PreviewView)
     * so if an intruder enters wrong PINs, their face is captured instantaneously in 0.2s.
     */
    private fun setupSilentIntruderCamera() {
        if (isFirstTimeSetup || !securityPrefs.isIntruderSelfieEnabled) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setFlashMode(ImageCapture.FLASH_MODE_OFF)
                    .build()

                cameraProvider.unbindAll()
                val selector = if (cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }
                cameraProvider.bindToLifecycle(this, selector, capture)
                intruderImageCapture = capture
            } catch (_: Exception) {
                intruderImageCapture = null
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun updateUIState() {
        val lockedPkg = intent.getStringExtra(EXTRA_LOCKED_PACKAGE)
        if (isFirstTimeSetup) {
            binding.tvAuthTitle.text = getString(R.string.setup_pin_title)
            binding.tvAuthSubtitle.text = if (tempFirstPin == null) {
                "أدخل رمز الحماية السري (4 أرقام)"
            } else {
                getString(R.string.confirm_pin_title)
            }
            binding.btnBiometric.visibility = View.INVISIBLE
        } else if (!lockedPkg.isNullOrBlank()) {
            binding.tvAuthTitle.text = "درع حماية الجوال الثاني"
            binding.tvAuthSubtitle.text = "أدخل الرمز لفتح التطبيق المحمي"
            binding.btnBiometric.visibility = if (securityPrefs.isBiometricEnabled) View.VISIBLE else View.INVISIBLE
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
            handleCancelOrBack()
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
                onAuthSuccess()
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
            onAuthSuccess()
            return
        }

        // 2. Check Decoy PIN (Completely silent and seamless, no toast or hint)
        if (securityPrefs.hasDecoyPin() && securityPrefs.verifyDecoyPin(normPin)) {
            onAuthSuccess()
            return
        }

        // 3. Wrong PIN
        val failedCount = securityPrefs.recordFailedAttempt()
        showError(getString(R.string.wrong_pin))
        enteredPin.clear()
        updatePinDots()

        // Capture intruder selfie on 2nd or 3rd+ failed attempt when enabled
        if (failedCount >= 2 && securityPrefs.isIntruderSelfieEnabled) {
            triggerIntruderCapture(normPin, failedCount)
        }
    }

    private fun triggerIntruderCapture(attemptedPin: String, attemptNumber: Int) {
        val capture = intruderImageCapture
        if (capture != null && !intruderCameraExecutor.isShutdown) {
            capture.takePicture(
                intruderCameraExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        try {
                            val buffer: ByteBuffer = image.planes[0].buffer
                            val rawBytes = ByteArray(buffer.remaining())
                            buffer.get(rawBytes)
                            val rotation = image.imageInfo.rotationDegrees
                            image.close()

                            val stampedJpeg = stampIntruderBitmap(rawBytes, rotation, attemptedPin, attemptNumber)
                            saveIntruderBytes(stampedJpeg, "محاولة اختراق فاشلة (#$attemptNumber) - الرمز المدخل: $attemptedPin")
                        } catch (_: Exception) {
                            try { image.close() } catch (_: Exception) {}
                            val fallbackBytes = generateForensicAlertJpeg(attemptedPin, attemptNumber)
                            saveIntruderBytes(fallbackBytes, "محاولة اختراق فاشلة (#$attemptNumber) - الرمز المدخل: $attemptedPin")
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        val fallbackBytes = generateForensicAlertJpeg(attemptedPin, attemptNumber)
                        saveIntruderBytes(fallbackBytes, "محاولة اختراق فاشلة (#$attemptNumber) - الرمز المدخل: $attemptedPin")
                    }
                }
            )
        } else {
            CoroutineScope(Dispatchers.IO).launch {
                val fallbackBytes = generateForensicAlertJpeg(attemptedPin, attemptNumber)
                saveIntruderBytes(fallbackBytes, "محاولة اختراق فاشلة (#$attemptNumber) - الرمز المدخل: $attemptedPin")
            }
        }
    }

    private fun saveIntruderBytes(jpegBytes: ByteArray, reasonText: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val repo = (application as SecretVaultApp).vaultRepository
            repo.saveIntruderPhoto(jpegBytes, reasonText)
        }
    }

    private fun stampIntruderBitmap(rawBytes: ByteArray, rotationDegrees: Int, attemptedPin: String, attemptNumber: Int): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 960 || bounds.outHeight / sample > 960) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, opts)
            ?: return generateForensicAlertJpeg(attemptedPin, attemptNumber)

        val matrix = Matrix()
        if (rotationDegrees != 0) {
            matrix.postRotate(rotationDegrees.toFloat())
        }
        // Mirror front camera horizontally so face looks natural
        matrix.postScale(-1f, 1f, decoded.width / 2f, decoded.height / 2f)

        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        val mutable = rotated.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutable)

        val bannerHeight = (mutable.height * 0.14f).coerceAtLeast(70f)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(190, 12, 16, 23)
        }
        canvas.drawRect(0f, mutable.height - bannerHeight, mutable.width.toFloat(), mutable.height.toFloat(), bgPaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF5252")
            textSize = (bannerHeight * 0.34f).coerceAtLeast(20f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = (bannerHeight * 0.28f).coerceAtLeast(16f)
        }

        val dateStr = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US).format(Date())
        canvas.drawText("🚨 INTRUDER ALERT (#$attemptNumber) - PIN: $attemptedPin", 20f, mutable.height - bannerHeight * 0.52f, textPaint)
        canvas.drawText("Captured: $dateStr", 20f, mutable.height - bannerHeight * 0.16f, subPaint)

        return ByteArrayOutputStream().use { baos ->
            mutable.compress(Bitmap.CompressFormat.JPEG, 85, baos)
            baos.toByteArray()
        }
    }

    private fun generateForensicAlertJpeg(attemptedPin: String, attemptNumber: Int): ByteArray {
        val bmp = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.parseColor("#121824"))

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF5252")
            textSize = 36f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val infoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00E5FF")
            textSize = 26f
            textAlign = Paint.Align.CENTER
        }
        val dateStr = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US).format(Date())
        canvas.drawText("⚠️ تنبيه محاولة اختراق", 320f, 170f, titlePaint)
        canvas.drawText("المحاولة الفاشلة رقم #$attemptNumber (الرمز: $attemptedPin)", 320f, 250f, infoPaint)
        canvas.drawText(dateStr, 320f, 320f, infoPaint)

        return ByteArrayOutputStream().use { baos ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, baos)
            baos.toByteArray()
        }
    }

    private fun onAuthSuccess() {
        val lockedPackage = intent.getStringExtra(EXTRA_LOCKED_PACKAGE)
        if (!lockedPackage.isNullOrBlank()) {
            AppVaultRepository.grantTemporaryAccess(lockedPackage)
            finish()
            return
        }
        val intent = Intent(this, VaultActivity::class.java)
        startActivity(intent)
        finish()
    }

    private fun handleCancelOrBack() {
        val lockedPackage = intent.getStringExtra(EXTRA_LOCKED_PACKAGE)
        if (!lockedPackage.isNullOrBlank()) {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(homeIntent)
        }
        finish()
    }

    private fun showError(message: String) {
        binding.tvErrorMessage.text = message
        binding.tvErrorMessage.visibility = View.VISIBLE
        vibrateError()

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
                    if (securityPrefs.unlockWithBiometric()) {
                        onAuthSuccess()
                    } else {
                        showError("يرجى إدخال الرمز السري أول مرة لتفعيل مفتاح البصمة")
                    }
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

    override fun onDestroy() {
        super.onDestroy()
        try {
            intruderCameraExecutor.shutdown()
        } catch (_: Exception) {}
    }
}
