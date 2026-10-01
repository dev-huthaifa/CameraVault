package com.vault.secretcamera.ui.settings

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.vault.secretcamera.R
import com.vault.secretcamera.SecretVaultApp
import com.vault.secretcamera.ads.AdManager
import com.vault.secretcamera.databinding.ActivitySettingsBinding
import com.vault.secretcamera.security.SecurityPreferences

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var securityPrefs: SecurityPreferences
    private var isLaunchingPermission = false

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
        updateManageFilesPermissionUI()
    }

    override fun onResume() {
        super.onResume()
        if (!securityPrefs.isUnlocked()) {
            finish()
            return
        }
        isLaunchingPermission = false
        updateManageFilesPermissionUI()
    }

    override fun onStop() {
        super.onStop()
        if (!isLaunchingPermission && !isChangingConfigurations && !AdManager.isAdShowing) {
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

    private fun updateManageFilesPermissionUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val isGranted = Environment.isExternalStorageManager()
            if (isGranted) {
                binding.tvManageFilesStatus.text = "مفعل: يتم حذف الصور تلقائياً وفورياً من المعرض وسلة المهملات"
                binding.tvManageFilesStatus.setTextColor(ContextCompat.getColor(this, R.color.vault_accent_green))
                binding.badgeManageFiles.text = "نشط ومفعل ✓"
                binding.badgeManageFiles.setTextColor(ContextCompat.getColor(this, R.color.vault_accent_green))
            } else {
                binding.tvManageFilesStatus.text = "انقر هنا لمنح الإذن من إعدادات النظام للحذف الفوري بدون تأكيد"
                binding.tvManageFilesStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                binding.badgeManageFiles.text = "تفعيل"
                binding.badgeManageFiles.setTextColor(ContextCompat.getColor(this, R.color.vault_accent_cyan))
            }

            binding.rowManageAllFiles.setOnClickListener {
                isLaunchingPermission = true
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    } catch (_: Exception) {
                        isLaunchingPermission = false
                        Toast.makeText(this, "تعذر فتح إعدادات النظام", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else {
            binding.rowManageAllFiles.visibility = View.GONE
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

        binding.switchFlipToLock.isChecked = securityPrefs.isFlipToLockEnabled
        binding.switchFlipToLock.setOnCheckedChangeListener { _, isChecked ->
            securityPrefs.isFlipToLockEnabled = isChecked
            val msg = if (isChecked) "تم تفعيل قفل الطوارئ بقلب الهاتف" else "تم تعطيل قفل الطوارئ بقلب الهاتف"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        binding.switchDataShredding.isChecked = securityPrefs.isDataShreddingEnabled
        binding.switchDataShredding.setOnCheckedChangeListener { _, isChecked ->
            securityPrefs.isDataShreddingEnabled = isChecked
            val msg = if (isChecked) "تم تفعيل التمزيق الرقمي الآمن (Anti-Forensics)" else "تم تعطيل التمزيق الرقمي الآمن"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        binding.rowSecurityAudit.setOnClickListener {
            val repository = (application as SecretVaultApp).vaultRepository
            com.vault.secretcamera.util.SecurityAuditDialog.show(this, repository, securityPrefs)
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
