package com.vault.secretcamera.ads

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.applovin.mediation.MaxAd
import com.applovin.mediation.MaxAdListener
import com.applovin.mediation.MaxAdViewAdListener
import com.applovin.mediation.MaxError
import com.applovin.mediation.ads.MaxAdView
import com.applovin.mediation.ads.MaxInterstitialAd
import com.applovin.sdk.AppLovinMediationProvider
import com.applovin.sdk.AppLovinSdk
import com.applovin.sdk.AppLovinSdkInitializationConfiguration
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

    // Google AdMob state
    private var admobInterstitialAd: InterstitialAd? = null
    private var isAdmobInterstitialLoading: Boolean = false
    private var isAdmobInitialized: Boolean = false

    // AppLovin MAX state (Highest-paying mediation network)
    private var maxInterstitialAd: MaxInterstitialAd? = null
    private var isMaxInitialized: Boolean = false
    private var isMaxInterstitialLoading: Boolean = false

    private var lastAdShowTime: Long = 0L

    var isAdShowing: Boolean = false
        private set

    fun init(context: Context) {
        // 1. Initialize Google AdMob
        if (!isAdmobInitialized) {
            try {
                MobileAds.initialize(context) {
                    isAdmobInitialized = true
                    loadInterstitial(context)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 2. Initialize AppLovin MAX (if SDK key provided)
        if (AdConfig.isAppLovinConfigured() && !isMaxInitialized) {
            try {
                val initConfig = AppLovinSdkInitializationConfiguration.builder(AdConfig.APPLOVIN_SDK_KEY, context)
                    .setMediationProvider(AppLovinMediationProvider.MAX)
                    .build()
                AppLovinSdk.getInstance(context).initialize(initConfig) {
                    isMaxInitialized = true
                    loadInterstitial(context)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Checks if user is still in the first 45-second grace period after initial install
     */
    fun isGracePeriodActive(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val firstTime = prefs.getLong(KEY_FIRST_INSTALL_TIME, 0L)
        if (firstTime == 0L) {
            prefs.edit().putLong(KEY_FIRST_INSTALL_TIME, System.currentTimeMillis()).apply()
            return true
        }
        val elapsed = System.currentTimeMillis() - firstTime
        return elapsed < FIRST_SESSION_GRACE_PERIOD_MS
    }

    /**
     * تحميل وعرض إعلان شريطي (Banner Ad) أسفل الشاشة
     * الأولوية لـ AppLovin MAX (الأعلى عائداً)، والبديل هو AdMob
     */
    fun loadBanner(activity: Activity, container: ViewGroup) {
        if (!AdConfig.isAdsEnabled) return

        // 1. إذا كان AppLovin مفعلاً، حمله أولاً كخيار رئيسي عالي الأرباح
        if (AdConfig.isAppLovinConfigured() && AdConfig.APPLOVIN_BANNER_ID.isNotBlank()) {
            try {
                container.removeAllViews()
                val maxBanner = MaxAdView(AdConfig.APPLOVIN_BANNER_ID, activity)
                val width = ViewGroup.LayoutParams.MATCH_PARENT
                val heightPx = (50 * activity.resources.displayMetrics.density).toInt()
                maxBanner.layoutParams = FrameLayout.LayoutParams(width, heightPx)

                maxBanner.setListener(object : MaxAdViewAdListener {
                    override fun onAdLoaded(ad: MaxAd) {
                        container.visibility = View.VISIBLE
                    }
                    override fun onAdDisplayed(ad: MaxAd) {}
                    override fun onAdHidden(ad: MaxAd) {}
                    override fun onAdClicked(ad: MaxAd) {}
                    override fun onAdLoadFailed(adUnitId: String, error: MaxError) {
                        // Fallback to AdMob
                        loadAdmobBanner(activity, container)
                    }
                    override fun onAdDisplayFailed(ad: MaxAd, error: MaxError) {
                        loadAdmobBanner(activity, container)
                    }
                    override fun onAdExpanded(ad: MaxAd) {}
                    override fun onAdCollapsed(ad: MaxAd) {}
                })

                container.addView(maxBanner)
                container.visibility = View.VISIBLE
                maxBanner.loadAd()
                return
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 2. إذا لم يكن AppLovin مفعلاً أو فشل، نحمل AdMob فوراً
        loadAdmobBanner(activity, container)
    }

    private fun loadAdmobBanner(activity: Activity, container: ViewGroup) {
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
     * تحميل مسبق للإعلانات البينية (AppLovin MAX + AdMob)
     */
    fun loadInterstitial(context: Context) {
        if (!AdConfig.isAdsEnabled) return

        // 1. Load AppLovin MAX Interstitial if configured
        if (AdConfig.isAppLovinConfigured() && AdConfig.APPLOVIN_INTERSTITIAL_ID.isNotBlank() && context is Activity) {
            if (maxInterstitialAd == null) {
                maxInterstitialAd = MaxInterstitialAd(AdConfig.APPLOVIN_INTERSTITIAL_ID, context)
            }
            if (!isMaxInterstitialLoading && maxInterstitialAd?.isReady == false) {
                isMaxInterstitialLoading = true
                maxInterstitialAd?.loadAd()
            }
        }

        // 2. Load Google AdMob Interstitial (always keep loaded as fallback)
        if (admobInterstitialAd == null && !isAdmobInterstitialLoading) {
            isAdmobInterstitialLoading = true
            val adRequest = AdRequest.Builder().build()
            InterstitialAd.load(
                context,
                AdConfig.INTERSTITIAL_ID,
                adRequest,
                object : InterstitialAdLoadCallback() {
                    override fun onAdLoaded(ad: InterstitialAd) {
                        admobInterstitialAd = ad
                        isAdmobInterstitialLoading = false
                    }

                    override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                        InterstitialAd.load(
                            context,
                            AdConfig.TEST_INTERSTITIAL_ID,
                            adRequest,
                            object : InterstitialAdLoadCallback() {
                                override fun onAdLoaded(testAd: InterstitialAd) {
                                    admobInterstitialAd = testAd
                                    isAdmobInterstitialLoading = false
                                }

                                override fun onAdFailedToLoad(error: LoadAdError) {
                                    admobInterstitialAd = null
                                    isAdmobInterstitialLoading = false
                                }
                            }
                        )
                    }
                }
            )
        }
    }

    /**
     * عرض الإعلان البيني: الأولوية لـ AppLovin MAX (الأعلى عائداً)، والبديل هو AdMob
     */
    fun showInterstitial(activity: Activity, force: Boolean = false, onDismissed: () -> Unit = {}) {
        if (!AdConfig.isAdsEnabled || isGracePeriodActive(activity)) {
            onDismissed()
            return
        }

        val now = System.currentTimeMillis()
        if (!force && (now - lastAdShowTime < 8000L)) {
            onDismissed()
            return
        }

        // خيار 1: إعلان AppLovin MAX جاهز
        val maxAd = maxInterstitialAd
        if (maxAd != null && maxAd.isReady) {
            lastAdShowTime = now
            isAdShowing = true
            maxAd.setListener(object : MaxAdListener {
                override fun onAdLoaded(ad: MaxAd) {}
                override fun onAdDisplayed(ad: MaxAd) { isAdShowing = true }
                override fun onAdHidden(ad: MaxAd) {
                    isAdShowing = false
                    loadInterstitial(activity)
                    onDismissed()
                }
                override fun onAdClicked(ad: MaxAd) {}
                override fun onAdLoadFailed(adUnitId: String, error: MaxError) {
                    isMaxInterstitialLoading = false
                }
                override fun onAdDisplayFailed(ad: MaxAd, error: MaxError) {
                    isAdShowing = false
                    showAdmobInterstitial(activity, force = true, onDismissed = onDismissed)
                }
            })
            maxAd.showAd(activity)
            return
        }

        // خيار 2: استخدام Google AdMob كبديل فوري
        showAdmobInterstitial(activity, force = force, onDismissed = onDismissed)
    }

    private fun showAdmobInterstitial(activity: Activity, force: Boolean, onDismissed: () -> Unit) {
        val ad = admobInterstitialAd
        if (ad != null) {
            lastAdShowTime = System.currentTimeMillis()
            isAdShowing = true
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdShowedFullScreenContent() {
                    isAdShowing = true
                }

                override fun onAdDismissedFullScreenContent() {
                    isAdShowing = false
                    admobInterstitialAd = null
                    loadInterstitial(activity)
                    onDismissed()
                }

                override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                    isAdShowing = false
                    admobInterstitialAd = null
                    loadInterstitial(activity)
                    onDismissed()
                }
            }
            ad.show(activity)
        } else {
            isAdShowing = false
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
