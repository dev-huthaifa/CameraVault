package com.vault.secretcamera.ads

/**
 * ملف إعدادات إعلانات جوجل أد موب المعتمد للمهندس حذيفه عارف باحيدره
 * -----------------------------------------------------------
 * الحساب والمعرفات الحقيقية المربوطة بالأرباح البنكية:
 * App ID: ca-app-pub-7678260783182264~4580415355
 * Banner ID: ca-app-pub-7678260783182264/7647319984
 * Interstitial ID: ca-app-pub-7678260783182264/3263464255
 */
object AdConfig {

    const val isAdsEnabled: Boolean = true

    // معرّفات Google AdMob الحقيقية لتحقيق الأرباح
    const val APP_ID = "ca-app-pub-7678260783182264~4580415355"
    const val BANNER_ID = "ca-app-pub-7678260783182264/7647319984"
    const val INTERSTITIAL_ID = "ca-app-pub-7678260783182264/3263464255"

    // المعرفات الاحتياطية الرسمية من Google في حال كان حساب AdMob لا يزال قيد المراجعة
    const val TEST_BANNER_ID = "ca-app-pub-3940256099942544/6300978111"
    const val TEST_INTERSTITIAL_ID = "ca-app-pub-3940256099942544/1033173712"

    fun getBannerId(): String = BANNER_ID
    fun getInterstitialId(): String = INTERSTITIAL_ID
}
