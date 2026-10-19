package com.vault.secretcamera.data

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import com.vault.secretcamera.model.AppVaultItem
import com.vault.secretcamera.model.BuiltInAppType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

class AppVaultRepository(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("app_vault_settings_prefs", Context.MODE_PRIVATE)

    private val storageFile: File by lazy {
        File(context.filesDir, "second_phone_apps.json")
    }

    private val iconsDir: File by lazy {
        File(context.filesDir, "vault_app_icons").apply { mkdirs() }
    }

    private val apksDir: File by lazy {
        File(context.filesDir, "vault_app_apks").apply { mkdirs() }
    }

    companion object {
        private const val KEY_SHIELD_ENABLED = "key_app_shield_enabled"
        private const val KEY_FIRST_INIT_DONE = "key_second_phone_first_init"
        private const val KEY_ISOLATED_PREFIX = "key_isolated_mode_"

        // Temporary granted access cache (PackageName -> Expiry timestamp in millis)
        private val temporaryAccessMap = ConcurrentHashMap<String, Long>()

        fun grantTemporaryAccess(packageName: String, durationMs: Long = 90_000L) {
            temporaryAccessMap[packageName] = System.currentTimeMillis() + durationMs
        }

        fun hasTemporaryAccess(packageName: String): Boolean {
            val expiry = temporaryAccessMap[packageName] ?: return false
            if (System.currentTimeMillis() <= expiry) {
                return true
            }
            temporaryAccessMap.remove(packageName)
            return false
        }

        fun revokeAccess(packageName: String) {
            temporaryAccessMap.remove(packageName)
        }

        fun revokeAllAccess() {
            temporaryAccessMap.clear()
        }

        fun hasAnyTemporaryAccess(): Boolean {
            return temporaryAccessMap.isNotEmpty()
        }
    }

    init {
        // First time initialization: populate default built-in apps
        if (!prefs.getBoolean(KEY_FIRST_INIT_DONE, false)) {
            val defaults = listOf(
                AppVaultItem(
                    packageName = "built_in.secret.browser",
                    appName = "المتصفح السري (Incognito)",
                    isLockedFromOutside = false,
                    isBuiltIn = true,
                    builtInType = BuiltInAppType.SECRET_BROWSER
                ),
                AppVaultItem(
                    packageName = "built_in.secret.notes",
                    appName = "الملاحظات المشفرة (Notes)",
                    isLockedFromOutside = false,
                    isBuiltIn = true,
                    builtInType = BuiltInAppType.SECRET_NOTES
                ),
                AppVaultItem(
                    packageName = "built_in.vault.studio",
                    appName = "استديو الخزنة (Studio)",
                    isLockedFromOutside = false,
                    isBuiltIn = true,
                    builtInType = BuiltInAppType.VAULT_STUDIO
                )
            )
            saveApps(defaults)
            prefs.edit().putBoolean(KEY_FIRST_INIT_DONE, true).apply()
        }
    }

    @Synchronized
    fun getApps(): List<AppVaultItem> {
        if (!storageFile.exists()) return emptyList()
        return try {
            val jsonStr = storageFile.readText(Charsets.UTF_8)
            val jsonArray = JSONArray(jsonStr)
            val list = mutableListOf<AppVaultItem>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val builtInTypeStr = obj.optString("builtInType", "")
                val builtInType = if (builtInTypeStr.isNotBlank()) {
                    try { BuiltInAppType.valueOf(builtInTypeStr) } catch (_: Exception) { null }
                } else null

                list.add(
                    AppVaultItem(
                        packageName = obj.getString("packageName"),
                        appName = obj.getString("appName"),
                        isLockedFromOutside = obj.optBoolean("isLockedFromOutside", true),
                        isBuiltIn = obj.optBoolean("isBuiltIn", false),
                        builtInType = builtInType,
                        addedTimestamp = obj.optLong("addedTimestamp", System.currentTimeMillis())
                    )
                )
            }
            list
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    @Synchronized
    fun addApp(item: AppVaultItem): Boolean {
        val current = getApps().toMutableList()
        if (current.any { it.packageName == item.packageName }) {
            return false // Already exists
        }
        current.add(item)
        saveApps(current)
        if (!item.isBuiltIn) {
            archiveAppIconAndApkAsync(item.packageName)
        }
        return true
    }

    /**
     * Saves the app's icon and backs up its base APK inside the Vault's internal storage
     * so if the user uninstalls/deletes the app from the Home Screen for 100% stealth,
     * its icon, metadata, and APK remain preserved inside the Vault!
     */
    fun archiveAppIconAndApkAsync(packageName: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val pm = context.packageManager
                val icon = pm.getApplicationIcon(packageName)
                saveAppIcon(packageName, icon)

                val appInfo = pm.getApplicationInfo(packageName, 0)
                val sourceApk = File(appInfo.sourceDir)
                val destApk = File(apksDir, "$packageName.apk")
                if (sourceApk.exists() && (!destApk.exists() || destApk.length() != sourceApk.length())) {
                    FileInputStream(sourceApk).use { input ->
                        FileOutputStream(destApk).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun saveAppIcon(packageName: String, drawable: Drawable) {
        try {
            val bmp = if (drawable is BitmapDrawable && drawable.bitmap != null) {
                drawable.bitmap
            } else {
                val width = drawable.intrinsicWidth.coerceAtLeast(96)
                val height = drawable.intrinsicHeight.coerceAtLeast(96)
                val b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(b)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                b
            }
            val iconFile = File(iconsDir, "$packageName.png")
            FileOutputStream(iconFile).use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } catch (_: Exception) {}
    }

    fun getSavedAppIcon(packageName: String): Drawable? {
        return try {
            val iconFile = File(iconsDir, "$packageName.png")
            if (iconFile.exists()) {
                Drawable.createFromPath(iconFile.absolutePath)
            } else null
        } catch (_: Exception) {
            null
        }
    }

    fun getBackedUpApkFile(packageName: String): File? {
        val f = File(apksDir, "$packageName.apk")
        return if (f.exists() && f.length() > 0L) f else null
    }

    fun setIsolatedContainerPreferred(packageName: String, isolated: Boolean) {
        prefs.edit().putBoolean(KEY_ISOLATED_PREFIX + packageName, isolated).apply()
    }

    fun isIsolatedContainerPreferred(packageName: String): Boolean {
        return prefs.getBoolean(KEY_ISOLATED_PREFIX + packageName, false)
    }

    @Synchronized
    fun removeApp(packageName: String): Boolean {
        val current = getApps().toMutableList()
        val removed = current.removeAll { it.packageName == packageName }
        if (removed) {
            saveApps(current)
            try { File(iconsDir, "$packageName.png").delete() } catch (_: Exception) {}
            try { File(apksDir, "$packageName.apk").delete() } catch (_: Exception) {}
        }
        return removed
    }

    @Synchronized
    fun toggleAppLock(packageName: String, isLocked: Boolean) {
        val current = getApps().map {
            if (it.packageName == packageName) it.copy(isLockedFromOutside = isLocked) else it
        }
        saveApps(current)
    }

    fun isAppProtected(packageName: String): Boolean {
        if (!isAppLockShieldEnabled()) return false
        val apps = getApps()
        val app = apps.find { it.packageName == packageName } ?: return false
        return !app.isBuiltIn && app.isLockedFromOutside
    }

    fun isAppInVault(packageName: String): Boolean {
        return getApps().any { it.packageName == packageName }
    }

    fun isAppLockShieldEnabled(): Boolean {
        return prefs.getBoolean(KEY_SHIELD_ENABLED, true)
    }

    fun setAppLockShieldEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHIELD_ENABLED, enabled).apply()
    }

    @Synchronized
    private fun saveApps(list: List<AppVaultItem>) {
        try {
            val jsonArray = JSONArray()
            for (item in list) {
                val obj = JSONObject().apply {
                    put("packageName", item.packageName)
                    put("appName", item.appName)
                    put("isLockedFromOutside", item.isLockedFromOutside)
                    put("isBuiltIn", item.isBuiltIn)
                    put("builtInType", item.builtInType?.name ?: "")
                    put("addedTimestamp", item.addedTimestamp)
                }
                jsonArray.put(obj)
            }
            storageFile.writeText(jsonArray.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
