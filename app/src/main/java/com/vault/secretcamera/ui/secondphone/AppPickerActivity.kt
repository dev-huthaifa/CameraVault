package com.vault.secretcamera.ui.secondphone

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vault.secretcamera.R
import com.vault.secretcamera.SecretVaultApp
import com.vault.secretcamera.ads.AdManager
import com.vault.secretcamera.data.AppVaultRepository
import com.vault.secretcamera.databinding.ActivityAppPickerBinding
import com.vault.secretcamera.databinding.ItemAppPickerBinding
import com.vault.secretcamera.model.AppVaultItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class InstalledApp(
    val packageName: String,
    val appName: String,
    val icon: Drawable,
    var isAdded: Boolean
)

class AppPickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppPickerBinding
    private lateinit var repository: AppVaultRepository
    private val allApps = mutableListOf<InstalledApp>()
    private val filteredApps = mutableListOf<InstalledApp>()
    private lateinit var adapter: AppPickerAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val app = application as SecretVaultApp
        if (!app.securityPreferences.isUnlocked()) {
            finish()
            return
        }

        if (app.securityPreferences.isAntiScreenshotEnabled) {
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE
            )
        }

        repository = AppVaultRepository(this)

        binding.pickerToolbar.setNavigationOnClickListener {
            finish()
        }

        setupRecyclerView()
        setupSearch()
        loadInstalledApps()
    }

    private var isLaunchingPermission = false
    private var pendingSecondPermissionCheck = false

    override fun onResume() {
        super.onResume()
        val app = application as SecretVaultApp
        if (!app.securityPreferences.isUnlocked()) {
            finish()
            return
        }
        isLaunchingPermission = false

        val hasUsage = com.vault.secretcamera.service.AppLockManager.hasUsageStatsPermission(this)
        val hasOverlay = com.vault.secretcamera.service.AppLockManager.hasOverlayPermission(this)

        if (hasUsage || hasOverlay) {
            com.vault.secretcamera.service.AppLockManager.startLockService(this)
        }

        if (pendingSecondPermissionCheck) {
            pendingSecondPermissionCheck = false
            if (!hasUsage) {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("الخطوة الأخيرة: تفعيل كشف الفتح الخارجي 🔒")
                    .setMessage("تم تفعيل الظهور فوق التطبيقات بنجاح! الآن يرجى تفعيل (الوصول لبيانات الاستخدام) ليكتشف التطبيق أي محاولة لفتح التطبيق من الشاشة الرئيسية ويحظره فوراً.")
                    .setPositiveButton("تفعيل الآن") { _, _ ->
                        isLaunchingPermission = true
                        com.vault.secretcamera.service.AppLockManager.requestUsageStatsPermission(this)
                    }
                    .setNegativeButton("لاحقاً", null)
                    .show()
            } else if (hasOverlay && hasUsage) {
                com.vault.secretcamera.service.AppLockManager.startLockService(this)
                Toast.makeText(this, "✅ تم تفعيل الحظر الصارم من الشاشة الرئيسية بنجاح!", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        val app = application as SecretVaultApp
        if (!isFinishing && !isLaunchingPermission && !isChangingConfigurations && !AdManager.isAdShowing) {
            app.securityPreferences.lockVault()
            finish()
        }
    }

    private fun setupRecyclerView() {
        adapter = AppPickerAdapter(filteredApps) { app ->
            toggleAddApp(app)
        }
        binding.rvApps.layoutManager = LinearLayoutManager(this)
        binding.rvApps.adapter = adapter
    }

    private fun setupSearch() {
        binding.etSearchApp.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.trim() ?: ""
                binding.btnClearSearch.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
                filterList(query)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnClearSearch.setOnClickListener {
            binding.etSearchApp.text?.clear()
        }
    }

    private fun loadInstalledApps() {
        binding.progressLoading.visibility = View.VISIBLE
        binding.rvApps.visibility = View.GONE
        binding.tvEmptyPicker.visibility = View.GONE

        CoroutineScope(Dispatchers.IO).launch {
            val pm = packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
            val addedApps = repository.getApps()
            val addedPackageSet = addedApps.map { it.packageName }.toSet()

            val list = mutableListOf<InstalledApp>()
            for (resolveInfo in resolveInfos) {
                val pkgName = resolveInfo.activityInfo.packageName
                if (pkgName == packageName) continue // Skip self

                val appName = resolveInfo.loadLabel(pm).toString()
                val icon = resolveInfo.loadIcon(pm)
                val isAdded = addedPackageSet.contains(pkgName)

                list.add(
                    InstalledApp(
                        packageName = pkgName,
                        appName = appName,
                        icon = icon,
                        isAdded = isAdded
                    )
                )
            }

            // Sort: not-added first, then alphabetically
            list.sortBy { it.appName.lowercase() }

            withContext(Dispatchers.Main) {
                allApps.clear()
                allApps.addAll(list)
                filterList(binding.etSearchApp.text?.toString()?.trim() ?: "")
                binding.progressLoading.visibility = View.GONE
                binding.rvApps.visibility = View.VISIBLE
            }
        }
    }

    private fun filterList(query: String) {
        filteredApps.clear()
        if (query.isEmpty()) {
            filteredApps.addAll(allApps)
        } else {
            val lower = query.lowercase()
            filteredApps.addAll(
                allApps.filter {
                    it.appName.lowercase().contains(lower) || it.packageName.lowercase().contains(lower)
                }
            )
        }
        adapter.notifyDataSetChanged()
        binding.tvEmptyPicker.visibility = if (filteredApps.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun toggleAddApp(app: InstalledApp) {
        if (!app.isAdded) {
            repository.saveAppIcon(app.packageName, app.icon)
            val item = AppVaultItem(
                packageName = app.packageName,
                appName = app.appName,
                isLockedFromOutside = true,
                isBuiltIn = false
            )
            repository.addApp(item)
            repository.setIsolatedContainerPreferred(app.packageName, false)
            repository.setAppLockShieldEnabled(true)
            app.isAdded = true
            adapter.notifyItemChanged(filteredApps.indexOf(app))

            ensureLockPermissions(app.appName)
        } else {
            repository.removeApp(app.packageName)
            app.isAdded = false
            adapter.notifyItemChanged(filteredApps.indexOf(app))
            Toast.makeText(this, "تمت إزالة ${app.appName} من الجوال الثاني", Toast.LENGTH_SHORT).show()
        }
    }

    private fun ensureLockPermissions(appName: String) {
        val hasUsage = com.vault.secretcamera.service.AppLockManager.hasUsageStatsPermission(this)
        val hasOverlay = com.vault.secretcamera.service.AppLockManager.hasOverlayPermission(this)

        if (hasUsage && hasOverlay) {
            com.vault.secretcamera.service.AppLockManager.startLockService(this)
            Toast.makeText(
                this,
                "✅ تم نقل «$appName» إلى الخزنة وتأمينه بالكامل 🔒",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("تفعيل درع الحماية لـ «$appName» 🔒")
            .setMessage("لمنع فتح التطبيق من الشاشة الرئيسية وتشغيل شريط استديو الخزنة الذكي المخفي في الحافة، يرجى منح صلاحية الظهور والوصول لبيانات الاستخدام (لمرة واحدة فقط).")
            .setPositiveButton("تفعيل الآن") { _, _ ->
                isLaunchingPermission = true
                pendingSecondPermissionCheck = true
                if (!hasOverlay) {
                    com.vault.secretcamera.service.AppLockManager.requestOverlayPermission(this)
                } else if (!hasUsage) {
                    com.vault.secretcamera.service.AppLockManager.requestUsageStatsPermission(this)
                }
            }
            .setNegativeButton("لاحقاً", null)
            .show()
    }

    private inner class AppPickerAdapter(
        private val items: List<InstalledApp>,
        private val onItemClick: (InstalledApp) -> Unit
    ) : RecyclerView.Adapter<AppPickerAdapter.AppViewHolder>() {

        inner class AppViewHolder(val b: ItemAppPickerBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
            val b = ItemAppPickerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return AppViewHolder(b)
        }

        override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
            val item = items[position]
            holder.b.ivAppIcon.setImageDrawable(item.icon)
            holder.b.tvAppName.text = item.appName
            holder.b.tvAppPackage.text = item.packageName

            if (item.isAdded) {
                holder.b.btnAddApp.text = "مضاف ✓"
                holder.b.btnAddApp.setBackgroundColor(ContextCompat.getColor(this@AppPickerActivity, R.color.vault_surface_variant))
                holder.b.btnAddApp.setTextColor(ContextCompat.getColor(this@AppPickerActivity, R.color.vault_accent_green))
            } else {
                holder.b.btnAddApp.text = "إضافة +"
                holder.b.btnAddApp.setBackgroundColor(ContextCompat.getColor(this@AppPickerActivity, R.color.vault_accent_cyan))
                holder.b.btnAddApp.setTextColor(ContextCompat.getColor(this@AppPickerActivity, R.color.vault_bg_dark))
            }

            holder.b.btnAddApp.setOnClickListener {
                onItemClick(item)
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
