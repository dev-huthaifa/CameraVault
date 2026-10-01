package com.vault.secretcamera.ads

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
import com.unity3d.ads.IUnityAdsInitializationListener
import com.unity3d.ads.IUnityAdsLoadListener
import com.unity3d.ads.IUnityAdsShowListener
import com.unity3d.ads.UnityAds
import com.unity3d.ads.UnityAdsShowOptions
import com.unity3d.services.banners.BannerErrorInfo
import com.unity3d.services.banners.BannerView
import com.unity3d.services.banners.UnityBannerSize

object AdManager {

    private const val PREFS_NAME = "admob_vault_prefs"
    private const val KEY_FIRST_INSTALL_TIME = "first_install_time"
    // 45 seconds grace period on first launch ever after installation
    private const val FIRST_SESSION_GRACE_PERIOD_MS = 45_000L

    // Unity Ads state (Primary high-eCPM network)
    private var isUnityInitialized: Boolean = false
    private var isUnityInterstitialLoaded: Boolean = false
    private var isUnityInterstitialLoading: Boolean = false

    // Google AdMob state (Fallback network)
    private var admobInterstitialAd: InterstitialAd? = null
    private var isAdmobInterstitialLoading: Boolean = false
    private var isAdmobInitialized: Boolean = false

    private var lastAdShowTime: Long = 0L

    var isAdShowing: Boolean = false
        private set

    fun init(context: Context) {
        val appContext = context.applicationContext

        // 1. Initialize Unity Ads (Primary Network)
        if (AdConfig.isUnityAdsConfigured() && !isUnityInitialized) {
            try {
                UnityAds.initialize(
                    appContext,
                    AdConfig.UNITY_GAME_ID,
                    AdConfig.UNITY_TEST_MODE,
                    object : IUnityAdsInitializationListener {
                        override fun onInitializationComplete() {
                            isUnityInitialized = true
                            loadInterstitial(appContext)
                        }

                        override fun onInitializationFailed(
                            error: UnityAds.UnityAdsInitializationError,
                            message: String
                        ) {
                            isUnityInitialized = false
                        }
                    }
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 2. Initialize Google AdMob (Reliable Fallback)
        if (!isAdmobInitialized) {
            try {
                MobileAds.initialize(appContext) {
                    isAdmobInitialized = true
                    loadInterstitial(appContext)
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
     * الأولوية لـ Unity Ads (الأعلى عائداً في اليمن والعالم)، والبديل الفوري هو AdMob
     */
    fun loadBanner(activity: Activity, container: ViewGroup) {
        if (!AdConfig.isAdsEnabled) return

        // 1. تجربة تحميل بنر Unity Ads أولاً
        if (AdConfig.isUnityAdsConfigured() && AdConfig.UNITY_BANNER_ID.isNotBlank()) {
            try {
                container.removeAllViews()
                val bannerView = BannerView(
                    activity,
                    AdConfig.UNITY_BANNER_ID,
                    UnityBannerSize(320, 50)
                )

                bannerView.listener = object : BannerView.IListener {
                    override fun onBannerLoaded(bannerAdView: BannerView?) {
                        container.visibility = View.VISIBLE
                    }

                    override fun onBannerShown(bannerAdView: BannerView?) {
                        container.visibility = View.VISIBLE
                    }

                    override fun onBannerFailedToLoad(
                        bannerAdView: BannerView?,
                        errorInfo: BannerErrorInfo?
                    ) {
                        // في حال فشل تحميل Unity ننتقل فوراً إلى AdMob كبديل احتياطي
                        loadAdmobBanner(activity, container)
                    }

                    override fun onBannerClick(bannerAdView: BannerView?) {}
                    override fun onBannerLeftApplication(bannerAdView: BannerView?) {}
                }

                val width = ViewGroup.LayoutParams.MATCH_PARENT
                val heightPx = (50 * activity.resources.displayMetrics.density).toInt()
                bannerView.layoutParams = FrameLayout.LayoutParams(width, heightPx)

                container.addView(bannerView)
                container.visibility = View.VISIBLE
                bannerView.load()
                return
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 2. إذا لم يكن Unity متوفراً أو فشل، نحمل AdMob فوراً
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
     * تحميل مسبق للإعلانات البينية (Unity Ads + AdMob)
     */
    fun loadInterstitial(context: Context) {
        if (!AdConfig.isAdsEnabled) return

        // 1. تحميل إعلان Unity البيني
        if (AdConfig.isUnityAdsConfigured() && AdConfig.UNITY_INTERSTITIAL_ID.isNotBlank()) {
            if (!isUnityInterstitialLoaded && !isUnityInterstitialLoading) {
                isUnityInterstitialLoading = true
                try {
                    UnityAds.load(
                        AdConfig.UNITY_INTERSTITIAL_ID,
                        object : IUnityAdsLoadListener {
                            override fun onUnityAdsAdLoaded(placementId: String) {
                                isUnityInterstitialLoaded = true
                                isUnityInterstitialLoading = false
                            }

                            override fun onUnityAdsFailedToLoad(
                                placementId: String,
                                error: UnityAds.UnityAdsLoadError,
                                message: String
                            ) {
                                isUnityInterstitialLoaded = false
                                isUnityInterstitialLoading = false
                            }
                        }
                    )
                } catch (e: Exception) {
                    isUnityInterstitialLoading = false
                    e.printStackTrace()
                }
            }
        }

        // 2. تحميل إعلان Google AdMob الاحتياطي لضمان توفر إعلان دائماً
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
     * عرض الإعلان البيني:
     * 1. الأولوية لـ Unity Ads (الأعلى عائداً في اليمن والشرق الأوسط)
     * 2. البديل الفوري التلقائي هو Google AdMob لضمان عدم ضياع أي ظهور
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

        // خيار 1: إعلان Unity Ads جاهز
        if (isUnityInterstitialLoaded && AdConfig.isUnityAdsConfigured()) {
            lastAdShowTime = now
            isAdShowing = true
            isUnityInterstitialLoaded = false

            try {
                UnityAds.show(
                    activity,
                    AdConfig.UNITY_INTERSTITIAL_ID,
                    UnityAdsShowOptions(),
                    object : IUnityAdsShowListener {
                        override fun onUnityAdsShowStart(placementId: String) {
                            isAdShowing = true
                        }

                        override fun onUnityAdsShowClick(placementId: String) {}

                        override fun onUnityAdsShowComplete(
                            placementId: String,
                            state: UnityAds.UnityAdsShowCompletionState
                        ) {
                            isAdShowing = false
                            loadInterstitial(activity)
                            onDismissed()
                        }

                        override fun onUnityAdsShowFailure(
                            placementId: String,
                            error: UnityAds.UnityAdsShowError,
                            message: String
                        ) {
                            isAdShowing = false
                            // في حال الفشل أثناء العرض، نعرض AdMob كبديل مباشر
                            showAdmobInterstitial(activity, force = true, onDismissed = onDismissed)
                        }
                    }
                )
                return
            } catch (e: Exception) {
                isAdShowing = false
                e.printStackTrace()
            }
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
