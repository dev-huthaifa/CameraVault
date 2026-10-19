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
        val hasUsage = AppLockManager.hasUsageStatsPermission(this)
        val hasOverlay = AppLockManager.hasOverlayPermission(this)

        if (!enabled) {
            binding.tvShieldStatus.text = "الدرع: معطل"
            binding.tvShieldStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        } else if (!hasUsage || !hasOverlay) {
            binding.tvShieldStatus.text = "⚠️ الدرع يحتاج تفعيل الصلاحيات (اضغط هنا)"
            binding.tvShieldStatus.setTextColor(ContextCompat.getColor(this, R.color.vault_accent_gold))
        } else {
            binding.tvShieldStatus.text = "الدرع: نشط ويحظر الفتح الخارجي 🔒"
            binding.tvShieldStatus.setTextColor(ContextCompat.getColor(this, R.color.vault_accent_cyan))
        }
    }

    private fun checkPermissionsAndStartService() {
        val hasUsage = AppLockManager.hasUsageStatsPermission(this)
        val hasOverlay = AppLockManager.hasOverlayPermission(this)

        if (!hasUsage || !hasOverlay) {
            val missingDesc = when {
                !hasOverlay && !hasUsage -> "1. الظهور فوق التطبيقات\n2. الوصول لبيانات الاستخدام"
                !hasOverlay -> "الظهور فوق التطبيقات (لإظهار شاشة الخطأ وزر استديو الخزنة)"
                else -> "الوصول لبيانات الاستخدام (لكشف فتح التطبيق من الشاشة الرئيسية وحظره فوراً)"
            }
            AlertDialog.Builder(this)
                .setTitle("تفعيل الحظر الصارم من الشاشة الرئيسية 🔒")
                .setMessage("لمنع فتح التطبيقات من الشاشة الرئيسية نهائياً وإظهار رسالة التوقف التمويهية، يرجى منح الصلاحية التالية:\n\n$missingDesc")
                .setPositiveButton("تفعيل الآن") { _, _ ->
                    isLaunchingChild = true
                    pendingPermissionChain = true
                    if (!hasOverlay) {
                        AppLockManager.requestOverlayPermission(this)
                    } else if (!hasUsage) {
                        AppLockManager.requestUsageStatsPermission(this)
                    }
                }
                .setNegativeButton("لاحقاً", null)
                .show()
        } else {
            AppLockManager.startLockService(this)
            updateShieldUI()
            Toast.makeText(this, "✅ درع الحظر من الشاشة الرئيسية وزر استديو الخزنة نشطان!", Toast.LENGTH_SHORT).show()
        }
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
        } else {
            val hasOverlay = AppLockManager.hasOverlayPermission(this)
            val hasUsage = AppLockManager.hasUsageStatsPermission(this)

            if (!hasOverlay || !hasUsage) {
                val missingName = if (!hasOverlay) "الظهور فوق التطبيقات" else "الوصول لبيانات الاستخدام"
                AlertDialog.Builder(this)
                    .setTitle("تأمين «${item.appName}» داخل الخزنة 🔒")
                    .setMessage("لمنع تطبيق «${item.appName}» من الفتح نهائياً من الشاشة الرئيسية وتفعيل زر (استديو الخزنة 🔒)، يرجى تفعيل صلاحية ($missingName) الآن.")
                    .setPositiveButton("تفعيل الصلاحية الآن") { _, _ ->
                        isLaunchingChild = true
                        pendingPermissionChain = true
                        if (!hasOverlay) {
                            AppLockManager.requestOverlayPermission(this)
                        } else {
                            AppLockManager.requestUsageStatsPermission(this)
                        }
                    }
                    .setNegativeButton("فتح التطبيق الآن") { _, _ ->
                        launchProtectedAppNow(item)
                    }
                    .show()
                return
            }
            launchProtectedAppNow(item)
        }
    }

    private fun launchProtectedAppNow(item: AppVaultItem) {
        // Grant session access token and immediately activate the floating "استديو الخزنة 🔒" button
        AppVaultRepository.grantTemporaryAccess(item.packageName, 15 * 60_000L)
        AppLockManager.notifyAppLaunchedFromVault(this, item.packageName)

        val launchIntent = packageManager.getLaunchIntentForPackage(item.packageName)
        if (launchIntent != null) {
            isLaunchingChild = true
            startActivity(launchIntent)
        } else {
            Toast.makeText(this, "تعذر تشغيل التطبيق (قد يكون تم إلغاء تثبيته)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleAppLongClick(item: AppVaultItem) {
        if (item.isBuiltIn) {
            Toast.makeText(this, "تطبيق نظام مدمج في الجوال الثاني", Toast.LENGTH_SHORT).show()
            return
        }

        val lockTitle = if (item.isLockedFromOutside) {
            "إلغاء حظر رسالة الخطأ من الشاشة الرئيسية"
        } else {
            "تفعيل حظر الفتح من الشاشة الرئيسية (رسالة خطأ) 🔒"
        }
        val options = arrayOf(
            "تشغيل التطبيق من داخل الخزنة 🚀",
            lockTitle,
            "إخراج التطبيق من الخزنة وإعادته للوضع العادي 🗑️"
        )

        AlertDialog.Builder(this)
            .setTitle(item.appName)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> handleAppClick(item)
                    1 -> {
                        repository.toggleAppLock(item.packageName, !item.isLockedFromOutside)
                        loadApps()
                        val msg = if (!item.isLockedFromOutside) "تم تفعيل القفل من الخارج" else "تم إلغاء القفل من الخارج"
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
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
                    holder.b.ivDesktopAppIcon.setImageDrawable(iconDrawable)
                } catch (_: PackageManager.NameNotFoundException) {
                    holder.b.ivDesktopAppIcon.setImageResource(R.drawable.ic_smartphone)
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
