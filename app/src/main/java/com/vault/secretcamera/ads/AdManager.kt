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

    private const val PREFS_NAME = "admob_vault_prefs"
    private const val KEY_FIRST_INSTALL_TIME = "first_install_time"
    // 45 seconds grace period on first launch ever after installation
    private const val FIRST_SESSION_GRACE_PERIOD_MS = 45_000L

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
     * Checks if user is still in the first 45-second grace period after initial install
     */
    fun isGracePeriodActive(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val firstTime = prefs.getLong(KEY_FIRST_INSTALL_TIME, 0L)
        if (firstTime == 0L) {
            // First launch recorded
            prefs.edit().putLong(KEY_FIRST_INSTALL_TIME, System.currentTimeMillis()).apply()
            return true
        }
        val elapsed = System.currentTimeMillis() - firstTime
        return elapsed < FIRST_SESSION_GRACE_PERIOD_MS
    }

    /**
     * تحميل وعرض إعلان شريطي (Banner Ad) أسفل الشاشة - دائم ومستمر لا ينقطع أبداً
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
                    // إذا كان الحساب جديداً أو قيد المراجعة، يتم عرض البنر الاحتياطي ليبقى البنر شغالاً دائماً!
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
                    // Fallback to test ID during review
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
     * عرض إعلان ملء الشاشة مع احترام مهلة الـ 45 ثانية الأولى بعد التثبيت
     */
    fun showInterstitial(activity: Activity, force: Boolean = false, onDismissed: () -> Unit = {}) {
        // إذا كان المستخدم في أول 45 ثانية من أول فتحة بعد التثبيت، لا نقطعه بإعلانات بينية
        if (isGracePeriodActive(activity)) {
            onDismissed()
            return
        }

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
     * إظهار الإعلان ملء الشاشة تلقائياً
     */
    fun autoShow(activity: Activity, delayMs: Long = 1000L, force: Boolean = false, onDismissed: () -> Unit = {}) {
        activity.window.decorView.postDelayed({
            if (!activity.isFinishing && !activity.isDestroyed) {
                showInterstitial(activity, force = force, onDismissed = onDismissed)
            }
        }, delayMs)
    }
}
