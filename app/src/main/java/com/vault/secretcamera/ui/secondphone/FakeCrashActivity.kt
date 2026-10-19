package com.vault.secretcamera.ui.secondphone

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.vault.secretcamera.ui.auth.AuthActivity

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
            setBackgroundColor(Color.parseColor("#B8000000"))
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setOnClickListener { exitToHomeLauncher() }
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(22), dp(24), dp(14))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(28).toFloat()
                setColor(Color.parseColor("#2C2C2E"))
            }
            elevation = dp(20).toFloat()
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val titleView = TextView(this).apply {
            text = "توقف تطبيق «$appName» بشكل متكرر"
            setTextColor(Color.parseColor("#F2F2F7"))
            textSize = 17.5f
            setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, 0, 0, dp(16))
            setOnLongClickListener {
                if (targetPackage.isNotBlank()) {
                    val authIntent = Intent(this@FakeCrashActivity, AuthActivity::class.java).apply {
                        putExtra(AuthActivity.EXTRA_LOCKED_PACKAGE, targetPackage)
                    }
                    startActivity(authIntent)
                    finish()
                }
                true
            }
        }

        val infoBtn = Button(this).apply {
            text = "ⓘ    معلومات التطبيق"
            setTextColor(Color.parseColor("#E5E5EA"))
            setBackgroundColor(Color.TRANSPARENT)
            textSize = 14.5f
            isAllCaps = false
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(11), dp(4), dp(11))
            setOnClickListener { exitToHomeLauncher() }
        }

        val closeBtn = Button(this).apply {
            text = "✕    إغلاق التطبيق"
            setTextColor(Color.parseColor("#E5E5EA"))
            setBackgroundColor(Color.TRANSPARENT)
            textSize = 14.5f
            isAllCaps = false
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(11), dp(4), dp(11))
            setOnClickListener { exitToHomeLauncher() }
        }

        card.addView(titleView)
        card.addView(infoBtn)
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
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION
        }
        startActivity(homeIntent)
        finish()
    }
}
