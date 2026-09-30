package com.vault.secretcamera.ads

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback

object AdManager {

    private var interstitialAd: InterstitialAd? = null
    private var isInterstitialLoading: Boolean = false
    private var isInitialized: Boolean = false
    private var lastAdShowTime: Long = 0L

    fun init(context: Context) {
        if (isInitialized) return
        try {
            MobileAds.initialize(context) {
                isInitialized = true
                loadInterstitial(context)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * تحميل وعرض إعلان شريطي (Banner Ad) أسفل الشاشة
     */
    fun loadBanner(activity: Activity, container: ViewGroup) {
        try {
            container.removeAllViews()
            val adView = AdView(activity).apply {
                setAdSize(AdSize.BANNER)
                adUnitId = AdConfig.BANNER_ID
            }
            container.addView(adView)
            container.visibility = View.VISIBLE

            adView.adListener = object : AdListener() {
                override fun onAdLoaded() {
                    container.visibility = View.VISIBLE
                }

                override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                    // إذا كان حساب المستخدم لا يزال قيد المراجعة في AdMob (Error 3 No Fill)
                    // نقوم فوراً بتحميل بنر احتياطي لكي يظهر الإعلان أمامك ولن تبقى الشاشة فارغة أبداً!
                    if (adView.adUnitId != AdConfig.TEST_BANNER_ID) {
                        try {
                            container.removeAllViews()
                            val fallbackView = AdView(activity).apply {
                                setAdSize(AdSize.BANNER)
                                adUnitId = AdConfig.TEST_BANNER_ID
                            }
                            container.addView(fallbackView)
                            fallbackView.loadAd(AdRequest.Builder().build())
                        } catch (_: Exception) {}
                    }
                }
            }

            adView.loadAd(AdRequest.Builder().build())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * تحميل مسبق لإعلان ملء الشاشة (Interstitial Ad)
     */
    fun loadInterstitial(context: Context) {
        if (isInterstitialLoading || interstitialAd != null) return

        isInterstitialLoading = true
        val adRequest = AdRequest.Builder().build()
        InterstitialAd.load(
            context,
            AdConfig.INTERSTITIAL_ID,
            adRequest,
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitialAd = ad
                    isInterstitialLoading = false
                }

                override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                    // في حال كانت الوحدة الحقيقية قيد المراجعة، نحمل إعلان الاختبار كاحتياط
                    InterstitialAd.load(
                        context,
                        AdConfig.TEST_INTERSTITIAL_ID,
                        adRequest,
                        object : InterstitialAdLoadCallback() {
                            override fun onAdLoaded(testAd: InterstitialAd) {
                                interstitialAd = testAd
                                isInterstitialLoading = false
                            }

                            override fun onAdFailedToLoad(error: LoadAdError) {
                                interstitialAd = null
                                isInterstitialLoading = false
                            }
                        }
                    )
                }
            }
        )
    }

    /**
     * عرض إعلان ملء الشاشة
     */
    fun showInterstitial(activity: Activity, force: Boolean = false, onDismissed: () -> Unit = {}) {
        val now = System.currentTimeMillis()
        if (!force && (now - lastAdShowTime < 8000L)) {
            onDismissed()
            return
        }

        val ad = interstitialAd
        if (ad != null) {
            lastAdShowTime = now
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    interstitialAd = null
                    loadInterstitial(activity)
                    onDismissed()
                }

                override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                    interstitialAd = null
                    loadInterstitial(activity)
                    onDismissed()
                }
            }
            ad.show(activity)
        } else {
            loadInterstitial(activity)
            onDismissed()
        }
    }

    /**
     * إظهار الإعلان ملء الشاشة تلقائياً بعد مهلة قصيرة
     */
    fun autoShow(activity: Activity, delayMs: Long = 1000L, force: Boolean = false, onDismissed: () -> Unit = {}) {
        activity.window.decorView.postDelayed({
            if (!activity.isFinishing && !activity.isDestroyed) {
                showInterstitial(activity, force = force, onDismissed = onDismissed)
            }
        }, delayMs)
    }
}
