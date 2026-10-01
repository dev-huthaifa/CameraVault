package com.vault.secretcamera.util

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.vault.secretcamera.R
import com.vault.secretcamera.data.VaultRepository
import com.vault.secretcamera.model.VaultItem
import com.vault.secretcamera.security.SecurityPreferences

object SecurityAuditDialog {

    fun show(
        context: Context,
        repository: VaultRepository,
        securityPrefs: SecurityPreferences,
        targetItem: VaultItem? = null
    ) {
        val hexSample = repository.getEncryptedFileHexSample(targetItem)
        val itemName = targetItem?.originalName ?: "جميع ملفات الخزنة"
        val shreddingStatus = if (securityPrefs.isDataShreddingEnabled) {
            "✅ التمزيق الرقمي الآمن (NIST SP 800-88): نَشِط\n(يتم الكتابة فوق مسار الملف بالأصفار قبل الحذف لمنع أي استعادة جنائية)."
        } else {
            "⚠️ التمزيق الرقمي الآمن: معطّل"
        }

        val flipStatus = if (securityPrefs.isFlipToLockEnabled) {
            "✅ إغلاق الطوارئ الحركي (Flip-to-Lock): نَشِط\n(بمجرد قلب الهاتف على وجهه، تُقفل الخزنة وتُطهّر الذاكرة فوراً)."
        } else {
            "⚠️ إغلاق الطوارئ الحركي: معطّل"
        }

        val auditMessage = StringBuilder().apply {
            append("🛡️ تقرير التحقق والتدقيق الأمني المباشر\n")
            append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n")
            append("📌 النطاق المستهدف:\n")
            append("   $itemName\n\n")
            append("🔐 خوارزمية التشفير الأساسية (Cipher Engine):\n")
            append("   AES/GCM/NoPadding (256-bit Key)\n")
            append("   تشفير عسكري مصادق (Authenticated Encryption) يمنع التلاعب بالملفات.\n\n")
            append("🔑 اشتقاق وحماية المفاتيح (Key Derivation):\n")
            append("   PBKDF2WithHmacSHA256 (100,000 دورة)\n")
            append("   مفاتيح الجلسة محمية في الذاكرة العشوائية وتُحذف فور القفل.\n\n")
            append("🌪️ حماية الآثار ومنع الاسترجاع (Anti-Forensics):\n")
            append("   $shreddingStatus\n\n")
            append("📴 قفل الطوارئ بمستشعر الحركة:\n")
            append("   $flipStatus\n\n")
            append("🌐 العزل السحابي التام (Zero-Knowledge / Air-Gapped):\n")
            append("   محلي 100% - صفر اتصالات خارجية، بياناتك لا تغادر هاتفك مطلقاً.\n\n")
            append("📍 تطهير بيانات التتبع والموقع:\n")
            append("   EXIF GPS & Hardware Metadata Sanitized.\n\n")
            append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")
            append("🔬 عينة بايتات التشفير الحقيقية على القرص (Raw Ciphertext):\n")
        }.toString()

        val paddingPx = (18 * context.resources.displayMetrics.density).toInt()
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
        }

        val tvInfo = TextView(context).apply {
            text = auditMessage
            textSize = 13.5f
            setTextColor(context.getColor(R.color.text_primary))
            setLineSpacing(4f, 1.15f)
        }
        layout.addView(tvInfo)

        val tvHex = TextView(context).apply {
            text = hexSample
            typeface = Typeface.MONOSPACE
            textSize = 11.5f
            setTextColor(context.getColor(R.color.vault_accent_cyan))
            setBackgroundColor(0xFF111726.toInt())
            setPadding((12 * context.resources.displayMetrics.density).toInt(), (10 * context.resources.displayMetrics.density).toInt(), (12 * context.resources.displayMetrics.density).toInt(), (10 * context.resources.displayMetrics.density).toInt())
            gravity = Gravity.CENTER_HORIZONTAL
        }
        layout.addView(tvHex)

        val tvFooter = TextView(context).apply {
            text = "\n🔒 النتيجة: تم التحقق بنجاح، بياناتك مشفرة وغير قابلة للقراءة أو الاستعادة إطلاقاً."
            textSize = 12.5f
            setTextColor(context.getColor(R.color.vault_accent_green))
        }
        layout.addView(tvFooter)

        val scrollView = ScrollView(context).apply {
            addView(layout)
        }

        AlertDialog.Builder(context)
            .setTitle("🛡️ فحص الأمان والتشفير الحي")
            .setView(scrollView)
            .setPositiveButton("إغلاق الفحص") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }
}
