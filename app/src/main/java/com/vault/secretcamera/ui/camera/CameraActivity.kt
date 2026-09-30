package com.vault.secretcamera.ui.camera

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.app.AlertDialog
import android.media.MediaActionSound
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.vault.secretcamera.R
import com.vault.secretcamera.SecretVaultApp
import com.vault.secretcamera.databinding.ActivityCameraBinding
import com.vault.secretcamera.databinding.DialogCameraSettingsBinding
import com.vault.secretcamera.ui.auth.AuthActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraBinding
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService

    private var lensFacing = CameraSelector.DEFAULT_BACK_CAMERA
    private var flashMode = ImageCapture.FLASH_MODE_AUTO
    private var isSecretCaptureMode = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] == true
        if (cameraGranted) {
            startCamera()
        } else {
            Toast.makeText(this, R.string.camera_permission_needed, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()
        isSecretCaptureMode = intent.getBooleanExtra(EXTRA_SECRET_CAPTURE, false)

        setupUI()
        setupSecretGesture()
        checkPermissionsAndStartCamera()
    }

    private fun setupUI() {
        // Shutter Button
        binding.btnShutter.setOnClickListener {
            takePhoto()
        }

        // Switch Camera (Front / Back)
        binding.btnSwitchCamera.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.DEFAULT_BACK_CAMERA) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            startCamera()
        }

        // Flash Toggle
        binding.btnFlash.setOnClickListener {
            cycleFlash()
        }

        // Camera Settings button -> Opens genuine camera settings dialog!
        binding.btnCameraSettings.setOnClickListener {
            showCameraSettingsDialog()
        }

        // Gallery thumbnail click -> Opens system gallery
        binding.ivGalleryThumbnail.setOnClickListener {
            try {
                val galleryIntent = Intent(Intent.ACTION_VIEW).apply {
                    type = "image/*"
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(galleryIntent)
            } catch (_: Exception) {
                Toast.makeText(this, "جاري فتح ألبوم الصور...", Toast.LENGTH_SHORT).show()
            }
        }

        applyCameraPrefs()
    }

    private fun applyCameraPrefs() {
        if (isSecretCaptureMode) {
            binding.tvCameraMode.text = "PRO • RAW"
        } else {
            val cameraPrefs = getSharedPreferences("camera_prefs", MODE_PRIVATE)
            val res = cameraPrefs.getString("pref_resolution", "4K • 60FPS") ?: "4K • 60FPS"
            binding.tvCameraMode.text = res
            val gridEnabled = cameraPrefs.getBoolean("pref_grid", false)
            binding.gridOverlayH.visibility = if (gridEnabled) View.VISIBLE else View.GONE
            binding.gridOverlayV.visibility = if (gridEnabled) View.VISIBLE else View.GONE
        }
    }

    private fun setupSecretGesture() {
        val prefs = (application as SecretVaultApp).securityPreferences
        binding.gestureOverlay.isStealthMode = !prefs.showGestureStroke
        binding.gestureOverlay.expectedShape = prefs.secretGestureShape

        binding.gestureOverlay.onGestureDetected = {
            openSecretAuth()
        }

        // Secret fallback: Long press or double click on "4K • 60FPS" mode badge
        binding.tvCameraMode.setOnLongClickListener {
            openSecretAuth()
            true
        }

        var modeClickCount = 0
        var lastModeClickTime = 0L
        binding.tvCameraMode.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastModeClickTime < 600) {
                modeClickCount++
                if (modeClickCount >= 2) {
                    modeClickCount = 0
                    openSecretAuth()
                }
            } else {
                modeClickCount = 1
            }
            lastModeClickTime = now
        }

        checkFirstLaunchTutorial()
    }

    private fun checkFirstLaunchTutorial() {
        val sp = getSharedPreferences("app_guide", MODE_PRIVATE)
        val shown = sp.getBoolean("first_tutorial_shown", false)
        if (!shown) {
            AlertDialog.Builder(this)
                .setTitle("دليل الاستخدام السري")
                .setMessage("مرحباً بك!\n\nلفتح الخزنة السرية في أي وقت ومن أي مكان على الشاشة:\n1. ارسم دائرة ⭕ بإصبعك على شاشة الكاميرا.\n2. أو انقر 3 نقرات سريعة على الشاشة.\n3. أو اضغط ضغطة مطولة على علامة (4K • 60FPS) بالأعلى.\n\nسيتم نقلك فوراً لشاشة إدخال رمز الخزنة.")
                .setPositiveButton("فهمت، ابدأ الآن") { _, _ ->
                    sp.edit().putBoolean("first_tutorial_shown", true).apply()
                }
                .setCancelable(false)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        val prefs = (application as SecretVaultApp).securityPreferences
        binding.gestureOverlay.isStealthMode = !prefs.showGestureStroke
        binding.gestureOverlay.expectedShape = prefs.secretGestureShape
        applyCameraPrefs()
    }

    private fun showCameraSettingsDialog() {
        val cameraPrefs = getSharedPreferences("camera_prefs", MODE_PRIVATE)
        val dialog = BottomSheetDialog(this)
        val dialogBinding = DialogCameraSettingsBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        // Close button
        dialogBinding.btnCloseCameraSettings.setOnClickListener {
            dialog.dismiss()
        }

        // Current values
        var currentRes = cameraPrefs.getString("pref_resolution", "4K • 60FPS") ?: "4K • 60FPS"
        var currentTimer = cameraPrefs.getInt("pref_timer", 0)
        val currentGrid = cameraPrefs.getBoolean("pref_grid", false)
        val currentSound = cameraPrefs.getBoolean("pref_shutter_sound", true)

        dialogBinding.tvResolutionValue.text = currentRes
        dialogBinding.switchGridLines.isChecked = currentGrid
        dialogBinding.switchShutterSound.isChecked = currentSound
        dialogBinding.tvTimerValue.text = if (currentTimer == 0) "معطل (0 ثوانٍ)" else "$currentTimer ثوانٍ"

        // 1. Resolution Picker
        val resOptions = arrayOf(
            "4K UHD (3840×2160) • 60FPS",
            "1080p Full HD (1920×1080) • 60FPS",
            "720p HD (1280×720) • 30FPS"
        )
        val resTags = arrayOf("4K • 60FPS", "1080p • 60FPS", "720p • 30FPS")

        dialogBinding.rowResolution.setOnClickListener {
            val selectedIdx = resTags.indexOf(currentRes).let { if (it == -1) 0 else it }
            AlertDialog.Builder(this)
                .setTitle(R.string.camera_resolution_title)
                .setSingleChoiceItems(resOptions, selectedIdx) { d, which ->
                    currentRes = resTags[which]
                    cameraPrefs.edit().putString("pref_resolution", currentRes).apply()
                    dialogBinding.tvResolutionValue.text = resOptions[which]
                    binding.tvCameraMode.text = currentRes
                    Toast.makeText(this, "تم ضبط الدقة: ${resOptions[which]}", Toast.LENGTH_SHORT).show()
                    d.dismiss()
                }
                .setNegativeButton(R.string.btn_cancel, null)
                .show()
        }

        // 2. Grid Lines Toggle
        dialogBinding.switchGridLines.setOnCheckedChangeListener { _, isChecked ->
            cameraPrefs.edit().putBoolean("pref_grid", isChecked).apply()
            binding.gridOverlayH.visibility = if (isChecked) View.VISIBLE else View.GONE
            binding.gridOverlayV.visibility = if (isChecked) View.VISIBLE else View.GONE
            val msg = if (isChecked) "تم تفعيل خطوط الشبكة" else "تم إيقاف خطوط الشبكة"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // 3. Shutter Sound Toggle
        dialogBinding.switchShutterSound.setOnCheckedChangeListener { _, isChecked ->
            cameraPrefs.edit().putBoolean("pref_shutter_sound", isChecked).apply()
            val msg = if (isChecked) "تم تفعيل صوت التقاط الصور" else "تم كتم صوت التقاط الصور"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // 4. Timer Picker
        val timerOptions = arrayOf("معطل (0 ثوانٍ)", "3 ثوانٍ", "5 ثوانٍ", "10 ثوانٍ")
        val timerValues = arrayOf(0, 3, 5, 10)

        dialogBinding.rowTimer.setOnClickListener {
            val selectedIdx = timerValues.indexOf(currentTimer).let { if (it == -1) 0 else it }
            AlertDialog.Builder(this)
                .setTitle(R.string.camera_timer_title)
                .setSingleChoiceItems(timerOptions, selectedIdx) { d, which ->
                    currentTimer = timerValues[which]
                    cameraPrefs.edit().putInt("pref_timer", currentTimer).apply()
                    dialogBinding.tvTimerValue.text = timerOptions[which]
                    val msg = if (currentTimer == 0) "تم إلغاء المؤقت الذاتي" else "تم ضبط المؤقت على ${timerOptions[which]}"
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    d.dismiss()
                }
                .setNegativeButton(R.string.btn_cancel, null)
                .show()
        }

        dialog.show()
    }

    private fun openSecretAuth() {
        val intent = Intent(this, AuthActivity::class.java)
        startActivity(intent)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun cycleFlash() {
        flashMode = when (flashMode) {
            ImageCapture.FLASH_MODE_AUTO -> {
                binding.btnFlash.setImageResource(R.drawable.ic_flash_auto)
                ImageCapture.FLASH_MODE_ON
            }
            ImageCapture.FLASH_MODE_ON -> {
                binding.btnFlash.setImageResource(R.drawable.ic_flash_auto)
                ImageCapture.FLASH_MODE_OFF
            }
            else -> {
                binding.btnFlash.setImageResource(R.drawable.ic_flash_auto)
                ImageCapture.FLASH_MODE_AUTO
            }
        }
        imageCapture?.flashMode = flashMode
    }

    private fun checkPermissionsAndStartCamera() {
        val required = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            required.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        val allGranted = required.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            startCamera()
        } else {
            permissionLauncher.launch(required.toTypedArray())
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                }

            imageCapture = ImageCapture.Builder()
                .setFlashMode(flashMode)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    lensFacing,
                    preview,
                    imageCapture
                )
            } catch (exc: Exception) {
                exc.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return

        // Play shutter sound if enabled in camera settings
        val cameraPrefs = getSharedPreferences("camera_prefs", MODE_PRIVATE)
        if (cameraPrefs.getBoolean("pref_shutter_sound", true)) {
            try {
                MediaActionSound().play(MediaActionSound.SHUTTER_CLICK)
            } catch (_: Exception) {}
        }

        if (isSecretCaptureMode) {
            // Secret direct capture: Capture image directly into memory and encrypt to vault!
            capture.takePicture(
                cameraExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val buffer: ByteBuffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        image.close()

                        CoroutineScope(Dispatchers.Main).launch {
                            val repo = (application as SecretVaultApp).vaultRepository
                            repo.saveSecretCapture(bytes)
                            Toast.makeText(this@CameraActivity, "تم التشفير والحفظ بالخزنة مباشرة!", Toast.LENGTH_SHORT).show()
                            finish()
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Toast.makeText(this@CameraActivity, "فشل التصوير: ${exception.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        } else {
            // Normal disguise capture: Saves genuine photo to Gallery/DCIM like a real camera!
            val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_$name.jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera")
                }
            }

            val outputOptions = ImageCapture.OutputFileOptions.Builder(
                contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            ).build()

            capture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(this),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        Toast.makeText(this@CameraActivity, R.string.photo_saved, Toast.LENGTH_SHORT).show()
                        outputFileResults.savedUri?.let { uri ->
                            try {
                                contentResolver.openInputStream(uri)?.use { stream ->
                                    val bmp = BitmapFactory.decodeStream(stream)
                                    binding.ivGalleryThumbnail.setImageBitmap(bmp)
                                }
                            } catch (_: Exception) {}
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Toast.makeText(this@CameraActivity, "خطأ: ${exception.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        const val EXTRA_SECRET_CAPTURE = "extra_secret_capture"
    }
}
