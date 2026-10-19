package com.vault.secretcamera.ui.secondphone

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.vault.secretcamera.R
import com.vault.secretcamera.ui.auth.AuthActivity

/**
 * شاشة تمويه الأعطال القسرية (Fake System Crash Camouflage):
 * تظهر فوراً في جزء من الثانية عندما يحاول أي متطفل فتح تطبيق تم نقله إلى داخل الخزنة
 * من الشاشة الرئيسية الخارجية للهاتف!
 * - يظهر للمتطفل أن التطبيق متعطل في النظام ("التطبيق متوقف باستمرار / خطأ في النظام 0x80040111").
 * - إذا نقر على "إغلاق التطبيق" يخرج فوراً إلى شاشة الهاتف الرئيسية (Launcher) ولا يفتح التطبيق نهائياً.
 * - لصاحب الهاتف فقط: الضغط المطول على أيقونة الخطأ أو عنوان الخطأ يفتح شاشة رمز الخزنة السري.
 */
class FakeCrashActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TARGET_PACKAGE = "extra_target_package"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        val targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE) ?: ""
        var appName = "التطبيق"
        try {
            if (targetPackage.isNotBlank()) {
                val appInfo = packageManager.getApplicationInfo(targetPackage, 0)
                appName = packageManager.getApplicationLabel(appInfo).toString()
            }
        } catch (_: PackageManager.NameNotFoundException) {}

        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xD9000000.toInt())
            setPadding(dp(28), dp(28), dp(28), dp(28))
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card_vault)
            setPadding(dp(24), dp(24), dp(24), dp(20))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val iconView = ImageView(this).apply {
            setImageResource(android.R.drawable.stat_notify_error)
            setColorFilter(ContextCompat.getColor(this@FakeCrashActivity, R.color.vault_accent_red))
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply {
                marginEnd = dp(12)
            }
        }

        val titleView = TextView(this).apply {
            text = "يتعذر تشغيل $appName"
            setTextColor(ContextCompat.getColor(this@FakeCrashActivity, R.color.text_primary))
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        headerRow.addView(iconView)
        headerRow.addView(titleView)

        val messageView = TextView(this).apply {
            text = "توقف تطبيق «$appName» بشكل غير متوقع نتيجة خطأ في ملفات التشغيل الأساسية (رمز الخطأ: ERR_PACKAGE_CORRUPTED_0x84).\n\nلا يمكن فتح هذا التطبيق حالياً."
            setTextColor(ContextCompat.getColor(this@FakeCrashActivity, R.color.text_secondary))
            textSize = 14f
            setLineSpacing(dp(3).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(14)
                bottomMargin = dp(20)
            }
        }

        val closeBtn = Button(this).apply {
            text = "إغلاق التطبيق"
            setTextColor(ContextCompat.getColor(this@FakeCrashActivity, R.color.vault_bg_dark))
            setBackgroundColor(ContextCompat.getColor(this@FakeCrashActivity, R.color.vault_accent_red))
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(46)
            )
            setOnClickListener {
                exitToHomeLauncher()
            }
        }

        // Secret owner bypass: Long-pressing the error icon or header opens PIN Auth
        val secretOwnerAction = {
            if (targetPackage.isNotBlank()) {
                val authIntent = Intent(this@FakeCrashActivity, AuthActivity::class.java).apply {
                    putExtra(AuthActivity.EXTRA_LOCKED_PACKAGE, targetPackage)
                }
                startActivity(authIntent)
                finish()
            }
            true
        }
        iconView.setOnLongClickListener { secretOwnerAction() }
        titleView.setOnLongClickListener { secretOwnerAction() }

        card.addView(headerRow)
        card.addView(messageView)
        card.addView(closeBtn)
        root.addView(card)

        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                exitToHomeLauncher()
            }
        })
    }

    private fun exitToHomeLauncher() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(homeIntent)
        finish()
    }
}
