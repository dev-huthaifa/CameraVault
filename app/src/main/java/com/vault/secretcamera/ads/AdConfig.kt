package com.vault.secretcamera.ads

/**
 * ملف إعدادات شبكات الإعلانات والربح المعتمد للمهندس حذيفة عارف باحيدره
 * ------------------------------------------------------------------
 * يدعم نظام الشلال الإعلاني المتعدد (Multi-Placement Waterfall):
 * 1. Unity Ads: الشبكة الأساسية لتحقيق أعلى عائد eCPM في اليمن والشرق الأوسط والعالم.
 * 2. Google AdMob: الشبكة الاحتياطية (Fallback) مع إعادة محاولة ذكية لضمان ملء الإعلانات التجارية الحقيقية بنسبة 100%.
 * ملاحظة صارمة: تم إلغاء وضع الاختبار (Test Mode) وإزالة أي معرفات تجريبية نهائياً لضمان أرباح حقيقية 100%.
 */
object AdConfig {

    const val isAdsEnabled: Boolean = true

    // =========================================================================
    // 1. إعدادات شبكة Unity Ads الرسمية (الإنتاج الحقيقي - أرباح حقيقية 100%)
    // =========================================================================
    const val UNITY_GAME_ID = "800386074"
    const val UNITY_ORGANIZATION_ID = "18968513356872"
    const val UNITY_INTERSTITIAL_ID = "BP_Interstitial_Android"
    const val UNITY_BANNER_ID = "BP_Banner_Android"

    // قائمة معرفات الشلال الإعلاني للبيني لضمان أقصى معدل تعبئة (Fill Rate) في Unity Ads
    val UNITY_INTERSTITIAL_PLACEMENTS = listOf(
        "BP_Interstitial_Android",
        "Interstitial_Android",
        "Rewarded_Android",
        "video"
    )

    // قائمة معرفات الشلال الإعلاني للبنر في Unity Ads
    val UNITY_BANNER_PLACEMENTS = listOf(
        "BP_Banner_Android",
        "Banner_Android"
    )

    /**
     * وضع الإنتاج الحقيقي مفعل دائماً (false = إعلانات تجارية مدفوعة حقيقية 100% بدون أي إعلانات Test).
     */
    const val UNITY_TEST_MODE = false

    fun isUnityAdsConfigured(): Boolean = UNITY_GAME_ID.isNotBlank()

    // =========================================================================
    // 2. إعدادات شبكة AppLovin MAX
    // =========================================================================
    const val APPLOVIN_SDK_KEY = ""
    const val APPLOVIN_BANNER_ID = ""
    const val APPLOVIN_INTERSTITIAL_ID = ""

    fun isAppLovinConfigured(): Boolean = APPLOVIN_SDK_KEY.isNotBlank()

    // =========================================================================
    // 3. إعدادات Google AdMob التجارية الرسمية (إعلانات حقيقية مدفوعة فقط)
    // =========================================================================
    const val APP_ID = "ca-app-pub-7678260783182264~4580415355"
    const val BANNER_ID = "ca-app-pub-7678260783182264/7647319984"
    const val INTERSTITIAL_ID = "ca-app-pub-7678260783182264/3263464255"

    fun getBannerId(): String = BANNER_ID
    fun getInterstitialId(): String = INTERSTITIAL_ID
}
