package com.vault.secretcamera.ads

/**
 * ملف إعدادات شبكات الإعلانات والربح المعتمد للمهندس حذيفة عارف باحيدره
 * ------------------------------------------------------------------
 * يدعم نظام الوساطة والمزايدة المفتوحة الهجين (AppLovin MAX + Google AdMob):
 * 1. AppLovin MAX: الشبكة الأساسية لتحقيق أعلى عائد eCPM في اليمن والشرق الأوسط.
 * 2. Google AdMob: الشبكة الاحتياطية (Fallback) لضمان ملء الإعلانات بنسبة 100% دائماً.
 */
object AdConfig {

    const val isAdsEnabled: Boolean = true

    // =========================================================================
    // 1. إعدادات شبكة AppLovin MAX (الأعلى ربحاً في الشرق الأوسط ومناطق Tier 3)
    // =========================================================================
    // مفتاح SDK Key من لوحة تحكم AppLovin: (Account -> Keys -> SDK Key)
    // الصق مفتاحك هنا لتبدأ أرباح AppLovin فوراً:
    const val APPLOVIN_SDK_KEY = ""

    // معرّف إعلان البنر الشريطي من AppLovin (Max Ad Unit - Banner)
    const val APPLOVIN_BANNER_ID = ""

    // معرّف إعلان ملء الشاشة البيني من AppLovin (Max Ad Unit - Interstitial)
    const val APPLOVIN_INTERSTITIAL_ID = ""

    /**
     * هل تم إدخال مفتاح AppLovin؟ إذا نعم يتم تفعيلها كالشبكة الأساسية فوراً
     */
    fun isAppLovinConfigured(): Boolean = APPLOVIN_SDK_KEY.isNotBlank()

    // =========================================================================
    // 2. إعدادات Google AdMob المعتمدة (الحالية وتعمل كـ Fallback احتياطي)
    // =========================================================================
    const val APP_ID = "ca-app-pub-7678260783182264~4580415355"
    const val BANNER_ID = "ca-app-pub-7678260783182264/7647319984"
    const val INTERSTITIAL_ID = "ca-app-pub-7678260783182264/3263464255"

    // المعرفات التجريبية الرسمية لـ AdMob أثناء فترة المراجعة
    const val TEST_BANNER_ID = "ca-app-pub-3940256099942544/6300978111"
    const val TEST_INTERSTITIAL_ID = "ca-app-pub-3940256099942544/1033173712"

    fun getBannerId(): String = BANNER_ID
    fun getInterstitialId(): String = INTERSTITIAL_ID
}
