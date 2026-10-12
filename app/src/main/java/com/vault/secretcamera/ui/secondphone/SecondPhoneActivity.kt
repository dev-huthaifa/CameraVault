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
import com.vault.secretcamera.data.AppVaultRepository
import com.vault.secretcamera.databinding.ActivitySecondPhoneBinding
import com.vault.secretcamera.databinding.ItemSecondPhoneAppBinding
import com.vault.secretcamera.model.AppVaultItem
import com.vault.secretcamera.model.BuiltInAppType
import com.vault.secretcamera.service.AppLockManager

class SecondPhoneActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySecondPhoneBinding
    private lateinit var repository: AppVaultRepository
    private val appItems = mutableListOf<AppVaultItem>()
    private lateinit var adapter: SecondPhoneGridAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySecondPhoneBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = AppVaultRepository(this)

        binding.secondPhoneToolbar.setNavigationOnClickListener {
            finish()
        }

        setupShieldToggle()
        setupRecyclerView()

        binding.fabAddApps.setOnClickListener {
            startActivity(Intent(this, AppPickerActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        loadApps()
        updateShieldUI()
    }

    private fun setupShieldToggle() {
        binding.switchShield.isChecked = repository.isAppLockShieldEnabled()
        updateShieldUI()

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
        binding.tvShieldStatus.text = if (enabled) "الدرع: نشط 🔒" else "الدرع: معطل"
        binding.tvShieldStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (enabled) R.color.vault_accent_cyan else R.color.text_secondary
            )
        )
    }

    private fun checkPermissionsAndStartService() {
        val hasUsage = AppLockManager.hasUsageStatsPermission(this)
        val hasOverlay = AppLockManager.hasOverlayPermission(this)

        if (!hasUsage || !hasOverlay) {
            AlertDialog.Builder(this)
                .setTitle("صلاحيات درع الحماية 🔒")
                .setMessage("لتأمين وقفل التطبيقات من خارج الخزنة بنجاح، يحتاج التطبيق إلى صلاحيتي (الوصول لبيانات الاستخدام) و(الظهور فوق التطبيقات).\nهل تريد تفعيلها الآن؟")
                .setPositiveButton("تفعيل الصلاحيات") { _, _ ->
                    if (!hasUsage) {
                        AppLockManager.requestUsageStatsPermission(this)
                    } else if (!hasOverlay) {
                        AppLockManager.requestOverlayPermission(this)
                    }
                }
                .setNegativeButton("لاحقاً", null)
                .show()
        } else {
            AppLockManager.startLockService(this)
            Toast.makeText(this, "تم تفعيل درع الحماية بنجاح!", Toast.LENGTH_SHORT).show()
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
                    startActivity(Intent(this, SecretBrowserActivity::class.java))
                }
                BuiltInAppType.SECRET_NOTES -> {
                    startActivity(Intent(this, SecretNotesActivity::class.java))
                }
                BuiltInAppType.VAULT_STUDIO -> {
                    finish() // Returns to the main Vault Activity
                }
                null -> {}
            }
        } else {
            // Grant temporary token so AppLock doesn't block opening from inside the vault
            AppVaultRepository.grantTemporaryAccess(item.packageName, 120_000L)
            val launchIntent = packageManager.getLaunchIntentForPackage(item.packageName)
            if (launchIntent != null) {
                startActivity(launchIntent)
            } else {
                Toast.makeText(this, "تعذر تشغيل التطبيق (قد يكون تم إلغاء تثبيته)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleAppLongClick(item: AppVaultItem) {
        if (item.isBuiltIn) {
            Toast.makeText(this, "تطبيق نظام مدمج في الجوال الثاني", Toast.LENGTH_SHORT).show()
            return
        }

        val lockTitle = if (item.isLockedFromOutside) "إلغاء قفل التطبيق من الخارج" else "تفعيل قفل التطبيق من الخارج 🔒"
        val options = arrayOf(
            "تشغيل التطبيق 🚀",
            lockTitle,
            "إزالة من الجوال الثاني 🗑️"
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
