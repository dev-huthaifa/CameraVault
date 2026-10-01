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
    // 1. إعدادات شبكة Unity Ads الرسمية (أرباح عالية ومباشرة لليمن والعالم)
    // =========================================================================
    const val UNITY_GAME_ID = "800386074"
    const val UNITY_ORGANIZATION_ID = "18968513356872"
    const val UNITY_INTERSTITIAL_ID = "BP_Interstitial_Android"
    const val UNITY_BANNER_ID = "BP_Banner_Android"

    /**
     * هام جداً: شبكة Unity Ads ترفض إرسال إعلانات مدفوعة للتطبيقات غير المنشورة على Google Play
     * (لأنه في لوحة التحكم Store ID: Not set)، وبالتالي كانت خوادم Unity تُرجع NO_FILL فيتحول
     * التطبيق فوراً إلى AdMob!
     * تفعيل Test Mode هنا يضمن ظهور إعلانات Unity بنسبة 100% للتأكد والتجربة.
     * وفور نشر التطبيق على المتجر يتم تحويلها إلى false لتبدأ الأرباح المالية المباشرة.
     */
    const val UNITY_TEST_MODE = true


    fun isUnityAdsConfigured(): Boolean = UNITY_GAME_ID.isNotBlank()

    // =========================================================================
    // 2. إعدادات شبكة AppLovin MAX
    // =========================================================================
    const val APPLOVIN_SDK_KEY = ""
    const val APPLOVIN_BANNER_ID = ""
    const val APPLOVIN_INTERSTITIAL_ID = ""

    fun isAppLovinConfigured(): Boolean = APPLOVIN_SDK_KEY.isNotBlank()

    // =========================================================================
    // 3. إعدادات Google AdMob المعتمدة (تعمل كـ Fallback احتياطي لضمان عدم ضياع أي ظهور)
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
