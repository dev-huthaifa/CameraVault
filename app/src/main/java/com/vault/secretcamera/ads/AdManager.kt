package com.vault.secretcamera.ads

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
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

    private const val TAG = "UnityAdsVault"
    private const val PREFS_NAME = "admob_vault_prefs"
    private const val KEY_FIRST_INSTALL_TIME = "first_install_time"
    // 30 seconds grace period on first launch ever after installation
    private const val FIRST_SESSION_GRACE_PERIOD_MS = 30_000L

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

    // Pending banner requests if Unity Ads is still initializing
    private var pendingBannerActivity: Activity? = null
    private var pendingBannerContainer: ViewGroup? = null

    fun init(context: Context) {
        val appContext = context.applicationContext

        // 1. Initialize Unity Ads (Primary Network)
        if (AdConfig.isUnityAdsConfigured() && !isUnityInitialized) {
            Log.d(TAG, "Initializing Unity Ads with Game ID: ${AdConfig.UNITY_GAME_ID}, testMode: ${AdConfig.UNITY_TEST_MODE}")
            try {
                UnityAds.initialize(
                    appContext,
                    AdConfig.UNITY_GAME_ID,
                    AdConfig.UNITY_TEST_MODE,
                    object : IUnityAdsInitializationListener {
                        override fun onInitializationComplete() {
                            Log.d(TAG, "Unity Ads initialized successfully!")
                            isUnityInitialized = true
                            loadInterstitial(appContext)

                            // Load queued banner if waiting for initialization
                            val act = pendingBannerActivity
                            val cnt = pendingBannerContainer
                            if (act != null && cnt != null && !act.isFinishing && !act.isDestroyed) {
                                act.runOnUiThread {
                                    loadBanner(act, cnt)
                                }
                            }
                            pendingBannerActivity = null
                            pendingBannerContainer = null
                        }

                        override fun onInitializationFailed(
                            error: UnityAds.UnityAdsInitializationError,
                            message: String
                        ) {
                            Log.e(TAG, "Unity Ads initialization failed: $error - $message")
                            isUnityInitialized = false
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Unity Ads init exception", e)
            }
        }

        // 2. Initialize Google AdMob (Reliable Fallback)
        if (!isAdmobInitialized) {
            try {
                MobileAds.initialize(appContext) {
                    isAdmobInitialized = true
                    loadAdmobInterstitial(appContext)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Checks if user is still in the first grace period after initial install
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
     * الأولوية لـ Unity Ads، والبديل هو AdMob
     */
    fun loadBanner(activity: Activity, container: ViewGroup) {
        if (!AdConfig.isAdsEnabled) return

        // 1. إذا كانت Unity Ads قيد التهيئة، ننتظر انتهاء التهيئة ولا نقفز فوراً إلى AdMob
        if (AdConfig.isUnityAdsConfigured() && !isUnityInitialized) {
            Log.d(TAG, "Unity Ads still initializing, queuing banner request...")
            pendingBannerActivity = activity
            pendingBannerContainer = container
            return
        }

        // 2. تحميل بنر Unity Ads كخيار أساسي
        if (AdConfig.isUnityAdsConfigured() && AdConfig.UNITY_BANNER_ID.isNotBlank()) {
            try {
                Log.d(TAG, "Loading Unity Banner: ${AdConfig.UNITY_BANNER_ID}")
                container.removeAllViews()
                val bannerView = BannerView(
                    activity,
                    AdConfig.UNITY_BANNER_ID,
                    UnityBannerSize(320, 50)
                )

                bannerView.listener = object : BannerView.IListener {
                    override fun onBannerLoaded(bannerAdView: BannerView?) {
                        Log.d(TAG, "Unity Banner loaded successfully!")
                        container.visibility = View.VISIBLE
                    }

                    override fun onBannerShown(bannerAdView: BannerView?) {
                        Log.d(TAG, "Unity Banner shown on screen!")
                        container.visibility = View.VISIBLE
                    }

                    override fun onBannerFailedToLoad(
                        bannerAdView: BannerView?,
                        errorInfo: BannerErrorInfo?
                    ) {
                        Log.w(TAG, "Unity Banner failed to load: ${errorInfo?.errorMessage} (code: ${errorInfo?.errorCode}), falling back to AdMob")
                        loadAdmobBanner(activity, container)
                    }

                    override fun onBannerClick(bannerAdView: BannerView?) {
                        Log.d(TAG, "Unity Banner clicked")
                    }

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
                Log.e(TAG, "Unity Banner exception", e)
            }
        }

        // 3. إذا لم تكن Unity متوفرة، نحمل AdMob فوراً
        loadAdmobBanner(activity, container)
    }

    private fun loadAdmobBanner(activity: Activity, container: ViewGroup) {
        try {
            Log.d(TAG, "Loading AdMob fallback banner")
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
     * تحميل مسبق للإعلانات البينية (Unity Ads أولاً، ثم AdMob كاحتياطي)
     */
    fun loadInterstitial(context: Context) {
        if (!AdConfig.isAdsEnabled) return

        // 1. تحميل إعلان Unity البيني
        if (AdConfig.isUnityAdsConfigured() && AdConfig.UNITY_INTERSTITIAL_ID.isNotBlank()) {
            if (!isUnityInterstitialLoaded && !isUnityInterstitialLoading) {
                isUnityInterstitialLoading = true
                Log.d(TAG, "Loading Unity Interstitial: ${AdConfig.UNITY_INTERSTITIAL_ID}")
                try {
                    UnityAds.load(
                        AdConfig.UNITY_INTERSTITIAL_ID,
                        object : IUnityAdsLoadListener {
                            override fun onUnityAdsAdLoaded(placementId: String) {
                                Log.d(TAG, "Unity Interstitial loaded successfully!")
                                isUnityInterstitialLoaded = true
                                isUnityInterstitialLoading = false
                            }

                            override fun onUnityAdsFailedToLoad(
                                placementId: String,
                                error: UnityAds.UnityAdsLoadError,
                                message: String
                            ) {
                                Log.w(TAG, "Unity Interstitial failed to load: $error - $message")
                                isUnityInterstitialLoaded = false
                                isUnityInterstitialLoading = false
                            }
                        }
                    )
                } catch (e: Exception) {
                    isUnityInterstitialLoading = false
                    Log.e(TAG, "Unity Ads load exception", e)
                }
            }
        }

        // 2. تحميل AdMob احتياطياً
        loadAdmobInterstitial(context)
    }

    private fun loadAdmobInterstitial(context: Context) {
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
     * الأولوية المطلقة لـ Unity Ads:
     * 1. إذا كان إعلان Unity جاهزاً -> يُعرض فوراً!
     * 2. إذا كان إعلان Unity قيد التحميل -> ننتظر حتى ثانيتين لاكتماله بدلاً من سرقة AdMob للشاشة!
     * 3. إذا فشل Unity بعد الانتظار -> ننتقل لـ AdMob كبديل احتياطي.
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

        // 1. إعلان Unity Ads جاهز
        if (isUnityInterstitialLoaded && AdConfig.isUnityAdsConfigured()) {
            Log.d(TAG, "Showing Unity Interstitial Ad now!")
            showUnityInterstitial(activity, onDismissed)
            return
        }

        // 2. إعلان Unity قيد التحميل: ننتظر قليلاً لإعطاء الأولوية التامة لـ Unity Ads
        if (isUnityInterstitialLoading && AdConfig.isUnityAdsConfigured()) {
            Log.d(TAG, "Unity Interstitial is currently loading, waiting up to 2.5s for it...")
            var checkCount = 0
            val handler = Handler(Looper.getMainLooper())
            val checkRunnable = object : Runnable {
                override fun run() {
                    checkCount++
                    if (isUnityInterstitialLoaded) {
                        Log.d(TAG, "Unity Interstitial ready! Showing now.")
                        showUnityInterstitial(activity, onDismissed)
                    } else if (checkCount < 10 && isUnityInterstitialLoading) {
                        handler.postDelayed(this, 250L)
                    } else {
                        Log.d(TAG, "Unity load timed out, falling back to AdMob")
                        showAdmobInterstitial(activity, force = force, onDismissed = onDismissed)
                    }
                }
            }
            handler.postDelayed(checkRunnable, 250L)
            return
        }

        // 3. البديل: استخدام AdMob
        Log.d(TAG, "Unity not available, using AdMob fallback")
        showAdmobInterstitial(activity, force = force, onDismissed = onDismissed)
    }

    private fun showUnityInterstitial(activity: Activity, onDismissed: () -> Unit) {
        lastAdShowTime = System.currentTimeMillis()
        isAdShowing = true
        isUnityInterstitialLoaded = false

        try {
            UnityAds.show(
                activity,
                AdConfig.UNITY_INTERSTITIAL_ID,
                UnityAdsShowOptions(),
                object : IUnityAdsShowListener {
                    override fun onUnityAdsShowStart(placementId: String) {
                        Log.d(TAG, "Unity Interstitial started showing")
                        isAdShowing = true
                    }

                    override fun onUnityAdsShowClick(placementId: String) {
                        Log.d(TAG, "Unity Interstitial clicked")
                    }

                    override fun onUnityAdsShowComplete(
                        placementId: String,
                        state: UnityAds.UnityAdsShowCompletionState
                    ) {
                        Log.d(TAG, "Unity Interstitial completed: $state")
                        isAdShowing = false
                        loadInterstitial(activity)
                        onDismissed()
                    }

                    override fun onUnityAdsShowFailure(
                        placementId: String,
                        error: UnityAds.UnityAdsShowError,
                        message: String
                    ) {
                        Log.e(TAG, "Unity Interstitial show failed: $error - $message")
                        isAdShowing = false
                        showAdmobInterstitial(activity, force = true, onDismissed = onDismissed)
                    }
                }
            )
        } catch (e: Exception) {
            isAdShowing = false
            Log.e(TAG, "Unity show exception", e)
            showAdmobInterstitial(activity, force = true, onDismissed = onDismissed)
        }
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
