package com.vault.secretcamera.ui.secondphone

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vault.secretcamera.R
import com.vault.secretcamera.SecretVaultApp
import com.vault.secretcamera.ads.AdManager
import com.vault.secretcamera.data.AppVaultRepository
import com.vault.secretcamera.databinding.ActivitySecondPhoneBinding
import com.vault.secretcamera.databinding.ItemSecondPhoneAppBinding
import com.vault.secretcamera.model.AppVaultItem
import com.vault.secretcamera.model.BuiltInAppType
import com.vault.secretcamera.security.SecurityPreferences
import com.vault.secretcamera.service.AppLockManager

class SecondPhoneActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySecondPhoneBinding
    private lateinit var repository: AppVaultRepository
    private lateinit var securityPrefs: SecurityPreferences
    private val appItems = mutableListOf<AppVaultItem>()
    private lateinit var adapter: SecondPhoneGridAdapter
    private var isLaunchingChild = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySecondPhoneBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val app = application as SecretVaultApp
        securityPrefs = app.securityPreferences
        if (!securityPrefs.isUnlocked()) {
            finish()
            return
        }

        if (securityPrefs.isAntiScreenshotEnabled) {
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE
            )
        }

        repository = AppVaultRepository(this)

        binding.secondPhoneToolbar.setNavigationOnClickListener {
            AdManager.showInterstitial(this) {
                finish()
            }
        }

        setupShieldToggle()
        setupRecyclerView()

        binding.fabAddApps.setOnClickListener {
            isLaunchingChild = true
            startActivity(Intent(this, AppPickerActivity::class.java))
        }

        AdManager.loadBanner(this, binding.adBannerSecondPhoneContainer)
        AdManager.loadInterstitial(this)
    }

    private var pendingPermissionChain = false

    override fun onResume() {
        super.onResume()
        if (!securityPrefs.isUnlocked()) {
            finish()
            return
        }
        isLaunchingChild = false
        AppVaultRepository.revokeAllAccess()
        com.vault.secretcamera.service.VaultStudioMountManager.unmountFromGeneralStudio(this)
        com.vault.secretcamera.service.AppLockService.cleanupAllBridgedMedia(this)
        loadApps()
        updateShieldUI()

        val hasUsage = AppLockManager.hasUsageStatsPermission(this)
        val hasOverlay = AppLockManager.hasOverlayPermission(this)

        if (repository.isAppLockShieldEnabled() && (hasUsage || hasOverlay)) {
            AppLockManager.startLockService(this)
        }

        if (pendingPermissionChain) {
            pendingPermissionChain = false
            if (!hasOverlay || !hasUsage) {
                checkPermissionsAndStartService()
            } else {
                AppLockManager.startLockService(this)
                Toast.makeText(this, "✅ تم تفعيل الحظر الصارم من الشاشة الرئيسية بنجاح!", Toast.LENGTH_LONG).show()
            }
        }

        AdManager.loadBanner(this, binding.adBannerSecondPhoneContainer)
    }

    override fun onStop() {
        super.onStop()
        if (!isFinishing && !isLaunchingChild && !isChangingConfigurations && !AdManager.isAdShowing) {
            AppVaultRepository.revokeAllAccess()
            securityPrefs.lockVault()
            finish()
        }
    }

    private fun setupShieldToggle() {
        binding.switchShield.isChecked = repository.isAppLockShieldEnabled()
        updateShieldUI()

        binding.tvShieldStatus.setOnClickListener {
            checkPermissionsAndStartService()
        }

        binding.switchShield.setOnCheckedChangeListener { _, isChecked ->
            repository.setAppLockShieldEnabled(isChecked)
            updateShieldUI()

            if (isChecked) {
                checkPermissionsAndStartService()
            } else {
                AppLockManager.stopLockService(this)
                Toast.makeText(this, "تم إيقاف درع قفل التطبيقات", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateShieldUI() {
        val enabled = repository.isAppLockShieldEnabled()
        val hasAccessibility = AppLockManager.hasAccessibilityShieldEnabled(this)
        val hasUsage = AppLockManager.hasUsageStatsPermission(this)
        val hasOverlay = AppLockManager.hasOverlayPermission(this)

        if (!enabled) {
            binding.tvShieldStatus.text = "الدرع: معطل"
            binding.tvShieldStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        } else if ((hasUsage && hasOverlay) || hasAccessibility) {
            binding.tvShieldStatus.text = "الدرع: نشط ومؤمّن 100% 🛡️"
            binding.tvShieldStatus.setTextColor(ContextCompat.getColor(this, R.color.vault_accent_cyan))
        } else {
            binding.tvShieldStatus.text = "⚠️ الدرع يحتاج تفعيل الصلاحيات (اضغط هنا)"
            binding.tvShieldStatus.setTextColor(ContextCompat.getColor(this, R.color.vault_accent_gold))
        }
    }

    private fun checkPermissionsAndStartService() {
        val hasUsage = AppLockManager.hasUsageStatsPermission(this)
        val hasOverlay = AppLockManager.hasOverlayPermission(this)

        if (hasUsage && hasOverlay) {
            AppLockManager.startLockService(this)
            updateShieldUI()
            Toast.makeText(this, "✅ درع قفل التطبيقات وشريط الحافة الذكي نشط 100%!", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("تفعيل درع الحماية الفوري 🛡️")
            .setMessage("لتشغيل حظر التطبيقات الصارم من الشاشة الرئيسية وتفعيل مقبض «استديو الخزنة» المخفي في حافة الشاشة، يرجى تفعيل الصلاحيتين التاليتين:")
            .setPositiveButton("تفعيل الآن") { _, _ ->
                isLaunchingChild = true
                pendingPermissionChain = true
                if (!hasOverlay) {
                    AppLockManager.requestOverlayPermission(this)
                } else if (!hasUsage) {
                    AppLockManager.requestUsageStatsPermission(this)
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun setupRecyclerView() {
        adapter = SecondPhoneGridAdapter(appItems,
            onItemClick = { item -> handleAppClick(item) },
            onItemLongClick = { item -> handleAppLongClick(item) }
        )
        binding.rvSecondPhoneApps.layoutManager = GridLayoutManager(this, 4)
        binding.rvSecondPhoneApps.adapter = adapter
    }

    private fun loadApps() {
        val apps = repository.getApps()
        appItems.clear()
        appItems.addAll(apps)
        adapter.notifyDataSetChanged()
    }

    private fun handleAppClick(item: AppVaultItem) {
        if (item.isBuiltIn) {
            when (item.builtInType) {
                BuiltInAppType.SECRET_BROWSER -> {
                    isLaunchingChild = true
                    startActivity(Intent(this, SecretBrowserActivity::class.java))
                }
                BuiltInAppType.SECRET_NOTES -> {
                    isLaunchingChild = true
                    startActivity(Intent(this, SecretNotesActivity::class.java))
                }
                BuiltInAppType.VAULT_STUDIO -> {
                    finish() // Returns to the main Vault Activity
                }
                null -> {}
            }
            return
        }

        val isInstalledOutside = AppLockManager.isPackageInstalledOnPhone(this, item.packageName)

        // If native app is installed on the phone, launch it directly (unless user explicitly chose isolated container)
        if (isInstalledOutside && !repository.isIsolatedContainerPreferred(item.packageName)) {
            val hasOverlay = AppLockManager.hasOverlayPermission(this)
            val hasUsage = AppLockManager.hasUsageStatsPermission(this)

            if (!hasOverlay || !hasUsage) {
                AlertDialog.Builder(this)
                    .setTitle("تفعيل درع الحماية وشريط الحافة المخفي 🔒")
                    .setMessage("قبل فتح «${item.appName}»، يرجى تفعيل صلاحية الظهور والوصول للاستخدام لضمان قفل التطبيق فور الخروج منه وتفعيل شريط «استديو الخزنة» المخفي في حافة الشاشة.")
                    .setPositiveButton("تفعيل الصلاحيات الآن") { _, _ ->
                        isLaunchingChild = true
                        pendingPermissionChain = true
                        if (!hasOverlay) {
                            AppLockManager.requestOverlayPermission(this)
                        } else {
                            AppLockManager.requestUsageStatsPermission(this)
                        }
                    }
                    .setNeutralButton("فتح التطبيق مباشرة") { _, _ ->
                        launchProtectedAppNow(item)
                    }
                    .show()
                return
            }

            launchProtectedAppNow(item)
            return
        }

        // If app was uninstalled from outside phone or isolated container was explicitly selected:
        val isolatedUrl = VaultIsolatedAppActivity.resolveIsolatedAppUrl(item.packageName, item.appName)
        if (isolatedUrl != null) {
            launchInIsolatedContainer(item)
        } else {
            val backupApk = repository.getBackedUpApkFile(item.packageName)
            if (backupApk != null) {
                installBackedUpApk(backupApk)
            } else {
                launchInIsolatedContainer(item)
            }
        }
    }

    private fun launchInIsolatedContainer(item: AppVaultItem) {
        isLaunchingChild = true
        val intent = Intent(this, VaultIsolatedAppActivity::class.java).apply {
            putExtra(VaultIsolatedAppActivity.EXTRA_PACKAGE_NAME, item.packageName)
            putExtra(VaultIsolatedAppActivity.EXTRA_APP_NAME, item.appName)
        }
        startActivity(intent)
    }

    private fun installBackedUpApk(apkFile: java.io.File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "$packageName.provider",
                apkFile
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            isLaunchingChild = true
            startActivity(installIntent)
        } catch (e: Exception) {
            Toast.makeText(this, "تعذر فتح ملف التثبيت المحفوظ: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchProtectedAppNow(item: AppVaultItem) {
        AppVaultRepository.grantTemporaryAccess(item.packageName, 15 * 60_000L)
        AppLockManager.notifyAppLaunchedFromVault(this, item.packageName)

        val launchIntent = packageManager.getLaunchIntentForPackage(item.packageName)
        if (launchIntent != null) {
            isLaunchingChild = true
            startActivity(launchIntent)
        } else {
            launchInIsolatedContainer(item)
        }
    }

    private fun handleAppLongClick(item: AppVaultItem) {
        if (item.isBuiltIn) {
            Toast.makeText(this, "تطبيق نظام مدمج في الجوال الثاني", Toast.LENGTH_SHORT).show()
            return
        }

        val isInstalledOutside = AppLockManager.isPackageInstalledOnPhone(this, item.packageName)
        val hideOrRestoreOption = if (isInstalledOutside) {
            "🗑️ حذف الأيقونة نهائياً من الشاشة الرئيسية (يبقى يعمل داخل الخزنة فقط)"
        } else {
            "📦 استعادة تثبيت التطبيق إلى الشاشة الرئيسية للهاتف"
        }

        val lockTitle = if (item.isLockedFromOutside) {
            "🔓 إلغاء حظر الفتح من الشاشة الرئيسية"
        } else {
            "🔒 تفعيل حظر الفتح من الشاشة الرئيسية (0 ثانية)"
        }

        val options = arrayOf(
            "🚀 تشغيل في المحرك المعزول داخل الخزنة (متصل مباشرة باستديو الخزنة)",
            "📱 تشغيل التطبيق الأصلي (مع دمج ألبوم استديو الخزنة بالاستديو العام)",
            hideOrRestoreOption,
            lockTitle,
            "❌ إزالة التطبيق من الجوال الثاني"
        )

        AlertDialog.Builder(this)
            .setTitle("إدارة «${item.appName}» 🔒")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        repository.setIsolatedContainerPreferred(item.packageName, true)
                        launchInIsolatedContainer(item)
                    }
                    1 -> {
                        repository.setIsolatedContainerPreferred(item.packageName, false)
                        launchProtectedAppNow(item)
                    }
                    2 -> {
                        if (isInstalledOutside) {
                            repository.archiveAppIconAndApkAsync(item.packageName)
                            repository.setIsolatedContainerPreferred(item.packageName, true)
                            isLaunchingChild = true
                            Toast.makeText(
                                this,
                                "قم بتأكيد الحذف لإخفاء «${item.appName}» نهائياً من الشاشة الرئيسية وسيبقى داخل الخزنة فقط 🔒",
                                Toast.LENGTH_LONG
                            ).show()
                            AppLockManager.uninstallAppFromHomeScreen(this, item.packageName)
                        } else {
                            val backupApk = repository.getBackedUpApkFile(item.packageName)
                            if (backupApk != null) {
                                installBackedUpApk(backupApk)
                            } else {
                                Toast.makeText(this, "التطبيق يعمل حالياً بوضع العزل الكامل داخل الخزنة", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    3 -> {
                        repository.toggleAppLock(item.packageName, !item.isLockedFromOutside)
                        loadApps()
                        val msg = if (!item.isLockedFromOutside) "تم تفعيل القفل من الخارج" else "تم إلغاء القفل من الخارج"
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    }
                    4 -> {
                        repository.removeApp(item.packageName)
                        loadApps()
                        Toast.makeText(this, "تمت إزالة ${item.appName} من الجوال الثاني", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private inner class SecondPhoneGridAdapter(
        private val items: List<AppVaultItem>,
        private val onItemClick: (AppVaultItem) -> Unit,
        private val onItemLongClick: (AppVaultItem) -> Unit
    ) : RecyclerView.Adapter<SecondPhoneGridAdapter.DesktopViewHolder>() {

        inner class DesktopViewHolder(val b: ItemSecondPhoneAppBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DesktopViewHolder {
            val b = ItemSecondPhoneAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return DesktopViewHolder(b)
        }

        override fun onBindViewHolder(holder: DesktopViewHolder, position: Int) {
            val item = items[position]
            holder.b.tvDesktopAppName.text = item.appName

            if (item.isBuiltIn) {
                holder.b.ivLockedBadge.visibility = View.GONE
                when (item.builtInType) {
                    BuiltInAppType.SECRET_BROWSER -> {
                        holder.b.ivDesktopAppIcon.setImageResource(R.drawable.ic_globe)
                        holder.b.ivDesktopAppIcon.setColorFilter(ContextCompat.getColor(this@SecondPhoneActivity, R.color.vault_accent_cyan))
                    }
                    BuiltInAppType.SECRET_NOTES -> {
                        holder.b.ivDesktopAppIcon.setImageResource(R.drawable.ic_notes)
                        holder.b.ivDesktopAppIcon.setColorFilter(ContextCompat.getColor(this@SecondPhoneActivity, R.color.vault_accent_gold))
                    }
                    BuiltInAppType.VAULT_STUDIO -> {
                        holder.b.ivDesktopAppIcon.setImageResource(R.drawable.ic_photo)
                        holder.b.ivDesktopAppIcon.setColorFilter(ContextCompat.getColor(this@SecondPhoneActivity, R.color.vault_accent_green))
                    }
                    null -> {}
                }
            } else {
                holder.b.ivDesktopAppIcon.clearColorFilter()
                try {
                    val iconDrawable = packageManager.getApplicationIcon(item.packageName)
                    repository.saveAppIcon(item.packageName, iconDrawable)
                    holder.b.ivDesktopAppIcon.setImageDrawable(iconDrawable)
                } catch (_: PackageManager.NameNotFoundException) {
                    // App was deleted/hidden from the outside phone! Load its saved icon from inside the Vault!
                    val savedIcon = repository.getSavedAppIcon(item.packageName)
                    if (savedIcon != null) {
                        holder.b.ivDesktopAppIcon.setImageDrawable(savedIcon)
                    } else {
                        holder.b.ivDesktopAppIcon.setImageResource(R.drawable.ic_smartphone)
                    }
                }
                holder.b.ivLockedBadge.visibility = if (item.isLockedFromOutside) View.VISIBLE else View.GONE
            }

            holder.itemView.setOnClickListener { onItemClick(item) }
            holder.itemView.setOnLongClickListener {
                onItemLongClick(item)
                true
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
