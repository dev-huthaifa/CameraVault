package com.vault.secretcamera.ui.settings

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.vault.secretcamera.R
import com.vault.secretcamera.SecretVaultApp
import com.vault.secretcamera.ads.AdConfig
import com.vault.secretcamera.ads.AdManager
import com.vault.secretcamera.databinding.ActivitySettingsBinding
import com.vault.secretcamera.security.SecurityPreferences

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var securityPrefs: SecurityPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        securityPrefs = (application as SecretVaultApp).securityPreferences

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
        setupSwitches()
        setupPinDialogs()
        setupGestureSettings()
        setupDeveloperSettings()
    }

    override fun onResume() {
        super.onResume()
        if (!securityPrefs.isUnlocked()) {
            finish()
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            securityPrefs.lockVault()
            finish()
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.settingsToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.settingsToolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupSwitches() {
        binding.switchAntiScreenshot.isChecked = securityPrefs.isAntiScreenshotEnabled
        binding.switchAntiScreenshot.setOnCheckedChangeListener { _, isChecked ->
            securityPrefs.isAntiScreenshotEnabled = isChecked
            Toast.makeText(this, "تم تحديث إعداد حظر تصوير الشاشة", Toast.LENGTH_SHORT).show()
        }

        binding.switchIntruderSelfie.isChecked = securityPrefs.isIntruderSelfieEnabled
        binding.switchIntruderSelfie.setOnCheckedChangeListener { _, isChecked ->
            securityPrefs.isIntruderSelfieEnabled = isChecked
            Toast.makeText(this, "تم تحديث إعداد سيلفي الدخيل", Toast.LENGTH_SHORT).show()
        }

        binding.switchBiometric.isChecked = securityPrefs.isBiometricEnabled
        binding.switchBiometric.setOnCheckedChangeListener { _, isChecked ->
            securityPrefs.isBiometricEnabled = isChecked
            Toast.makeText(this, "تم تحديث إعداد فتح القفل بالبصمة", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupPinDialogs() {
        // Change Master PIN
        binding.rowChangePin.setOnClickListener {
            val input = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                hint = "الرمز الجديد (4 أرقام)"
            }

            AlertDialog.Builder(this)
                .setTitle(R.string.setting_change_pin)
                .setView(input)
                .setPositiveButton(R.string.btn_save) { _, _ ->
                    val newPin = input.text.toString().trim()
                    if (newPin.length == 4) {
                        securityPrefs.setMasterPin(newPin)
                        Toast.makeText(this, "تم تغيير رمز الحماية بنجاح", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "يجب أن يتكون الرمز من 4 أرقام", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(R.string.btn_cancel, null)
                .show()
        }

        // Set Decoy PIN
        binding.rowDecoyPin.setOnClickListener {
            val input = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                hint = "الرمز التمويهي (4 أرقام)"
            }

            AlertDialog.Builder(this)
                .setTitle(R.string.setup_decoy_pin)
                .setMessage(R.string.decoy_pin_desc)
                .setView(input)
                .setPositiveButton(R.string.btn_save) { _, _ ->
                    val decoy = input.text.toString().trim()
                    if (decoy.length == 4) {
                        securityPrefs.setDecoyPin(decoy)
                        Toast.makeText(this, "تم تعيين الرمز التمويهي بنجاح!", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "يجب أن يتكون الرمز من 4 أرقام", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(R.string.btn_cancel, null)
                .show()
        }
    }

    private fun setupGestureSettings() {
        updateShapeSummary()

        val shapeOptions = arrayOf(
            getString(R.string.shape_circle),
            getString(R.string.shape_triangle),
            getString(R.string.shape_square),
            getString(R.string.shape_z),
            getString(R.string.shape_multi_tap)
        )
        val shapeKeys = arrayOf(
            SecurityPreferences.SHAPE_CIRCLE,
            SecurityPreferences.SHAPE_TRIANGLE,
            SecurityPreferences.SHAPE_SQUARE,
            SecurityPreferences.SHAPE_Z,
            SecurityPreferences.SHAPE_MULTI_TAP
        )

        binding.rowSecretShape.setOnClickListener {
            val currentIndex = shapeKeys.indexOf(securityPrefs.secretGestureShape).let {
                if (it == -1) 0 else it
            }

            AlertDialog.Builder(this)
                .setTitle(R.string.setting_secret_shape)
                .setSingleChoiceItems(shapeOptions, currentIndex) { dialog, which ->
                    securityPrefs.secretGestureShape = shapeKeys[which]
                    updateShapeSummary()
                    Toast.makeText(this, "تم تغيير شكل الإيماءة السرية بنجاح!", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.btn_cancel, null)
                .show()
        }

        binding.switchShowStroke.isChecked = securityPrefs.showGestureStroke
        binding.switchShowStroke.setOnCheckedChangeListener { _, isChecked ->
            securityPrefs.showGestureStroke = isChecked
            val msg = if (isChecked) "سيتم إظهار خطوط الرسم على الشاشة" else "تم إخفاء خطوط الرسم للسرية التامة"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateShapeSummary() {
        val label = when (securityPrefs.secretGestureShape) {
            SecurityPreferences.SHAPE_CIRCLE -> getString(R.string.shape_circle)
            SecurityPreferences.SHAPE_TRIANGLE -> getString(R.string.shape_triangle)
            SecurityPreferences.SHAPE_SQUARE -> getString(R.string.shape_square)
            SecurityPreferences.SHAPE_Z -> getString(R.string.shape_z)
            SecurityPreferences.SHAPE_MULTI_TAP -> getString(R.string.shape_multi_tap)
            else -> getString(R.string.shape_circle)
        }
        binding.tvCurrentShape.text = label
    }

    private fun setupDeveloperSettings() {

        // Direct Email
        binding.rowDeveloperEmail.setOnClickListener {
            val email = "eng.huthaifa.aref.1611@gmail.com"
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email")).apply {
                putExtra(Intent.EXTRA_SUBJECT, "استفسار بخصوص تطبيق خزنة الكاميرا")
            }
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "تعذر فتح تطبيق البريد الإلكتروني", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
