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
import com.google.android.gms.ads.RequestConfiguration
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
import java.lang.ref.WeakReference

/**
 * محرك الإعلانات التجاري المتقدم (Production Ad Engine):
 * - يضمن عدم ظهور أي إعلان تجريبي (Zero Test Ads) بنسبة 100%.
 * - يدعم الشلال المتعدد (Multi-Placement Waterfall) لـ Unity Ads ثم AdMob التجاري الحقيقي.
 * - يدعم إعادة المحاولة التلقائية الذكية (Auto-Retry) لضمان أعلى نسبة ظهور وأرباح.
 */
object AdManager {

    private const val TAG = "CommercialAdsVault"
    private const val PREFS_NAME = "admob_vault_prefs"
    private const val KEY_FIRST_INSTALL_TIME = "first_install_time"
    // 15 seconds initial setup grace period on very first install
    private const val FIRST_SESSION_GRACE_PERIOD_MS = 15_000L

    private val mainHandler = Handler(Looper.getMainLooper())

    // Unity Ads state (Primary high-eCPM network)
    @Volatile
    private var isUnityInitialized: Boolean = false
    @Volatile
    private var isUnityInitializing: Boolean = false
    @Volatile
    private var loadedUnityPlacementId: String? = null
    @Volatile
    private var isUnityInterstitialLoading: Boolean = false

    // Google AdMob state (Commercial Fallback network)
    @Volatile
    private var admobInterstitialAd: InterstitialAd? = null
    @Volatile
    private var isAdmobInterstitialLoading: Boolean = false
    @Volatile
    private var isAdmobInitialized: Boolean = false
    private var admobRetryAttempt: Int = 0
    private var unityRetryAttempt: Int = 0

    private var lastAdShowTime: Long = 0L

    @Volatile
    var isAdShowing: Boolean = false
        private set

    // Pending banner requests if Unity Ads is still initializing
    private var pendingBannerActivityRef: WeakReference<Activity>? = null
    private var pendingBannerContainerRef: WeakReference<ViewGroup>? = null

    fun init(context: Context) {
        val appContext = context.applicationContext

        // Ensure zero test device IDs are configured in AdMob so only real commercial ads serve
        try {
            val requestConfig = RequestConfiguration.Builder()
                .setTestDeviceIds(emptyList())
                .build()
            MobileAds.setRequestConfiguration(requestConfig)
        } catch (_: Exception) {}

        // 1. Initialize Unity Ads (Primary Commercial Network - TestMode = FALSE)
        if (AdConfig.isUnityAdsConfigured() && !isUnityInitialized && !isUnityInitializing) {
            isUnityInitializing = true
            Log.d(TAG, "Initializing Unity Ads (Production Mode) Game ID: ${AdConfig.UNITY_GAME_ID}")
            try {
                UnityAds.initialize(
                    appContext,
                    AdConfig.UNITY_GAME_ID,
                    AdConfig.UNITY_TEST_MODE,
                    object : IUnityAdsInitializationListener {
                        override fun onInitializationComplete() {
                            Log.d(TAG, "Unity Ads initialized in PRODUCTION mode!")
                            isUnityInitialized = true
                            isUnityInitializing = false
                            loadInterstitial(appContext)

                            val act = pendingBannerActivityRef?.get()
                            val cnt = pendingBannerContainerRef?.get()
                            if (act != null && cnt != null && !act.isFinishing && !act.isDestroyed) {
                                act.runOnUiThread {
                                    loadBanner(act, cnt)
                                }
                            }
                            pendingBannerActivityRef = null
                            pendingBannerContainerRef = null
                        }

                        override fun onInitializationFailed(
                            error: UnityAds.UnityAdsInitializationError,
                            message: String
                        ) {
                            Log.e(TAG, "Unity Ads init failed: $error - $message")
                            isUnityInitialized = false
                            isUnityInitializing = false

                            // Fallback queued banner to real AdMob immediately
                            val act = pendingBannerActivityRef?.get()
                            val cnt = pendingBannerContainerRef?.get()
                            if (act != null && cnt != null && !act.isFinishing && !act.isDestroyed) {
                                act.runOnUiThread {
                                    loadAdmobBanner(act, cnt, 0)
                                }
                            }
                            pendingBannerActivityRef = null
                            pendingBannerContainerRef = null

                            // Retry Unity initialization after 10 seconds
                            mainHandler.postDelayed({
                                init(appContext)
                            }, 10_000L)
                        }
                    }
                )
            } catch (e: Exception) {
                isUnityInitializing = false
                Log.e(TAG, "Unity Ads init exception", e)
            }
        }

        // 2. Initialize Google AdMob (Real Commercial Network)
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
     * Checks if user is still in the brief first-install PIN setup window
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
     * تحميل وعرض إعلان شريطي تجاري (Real Commercial Banner Ad)
     * يبدأ بشلال Unity Ads (BP_Banner_Android -> Banner_Android)، ثم ينتقل لـ AdMob التجاري الحقيقي.
     */
    fun loadBanner(activity: Activity, container: ViewGroup) {
        if (!AdConfig.isAdsEnabled || activity.isFinishing || activity.isDestroyed) return

        if (AdConfig.isUnityAdsConfigured() && !isUnityInitialized) {
            pendingBannerActivityRef = WeakReference(activity)
            pendingBannerContainerRef = WeakReference(container)
            // Also load commercial AdMob banner immediately so the slot is never empty while Unity warms up
            loadAdmobBanner(activity, container, 0)
            return
        }

        if (AdConfig.isUnityAdsConfigured() && AdConfig.UNITY_BANNER_PLACEMENTS.isNotEmpty()) {
            loadUnityBannerWaterfall(activity, container, 0)
            return
        }

        loadAdmobBanner(activity, container, 0)
    }

    private fun loadUnityBannerWaterfall(activity: Activity, container: ViewGroup, placementIndex: Int) {
        if (activity.isFinishing || activity.isDestroyed) return
        val placements = AdConfig.UNITY_BANNER_PLACEMENTS
        if (placementIndex >= placements.size) {
            loadAdmobBanner(activity, container, 0)
            return
        }

        val placementId = placements[placementIndex]
        try {
            Log.d(TAG, "Loading Unity Commercial Banner [$placementId]")
            val bannerView = BannerView(
                activity,
                placementId,
                UnityBannerSize(320, 50)
            )

            bannerView.listener = object : BannerView.IListener {
                override fun onBannerLoaded(bannerAdView: BannerView?) {
                    if (activity.isFinishing || activity.isDestroyed) return
                    Log.d(TAG, "Unity Commercial Banner loaded: $placementId")
                    container.removeAllViews()
                    val width = ViewGroup.LayoutParams.MATCH_PARENT
                    val heightPx = (50 * activity.resources.displayMetrics.density).toInt()
                    bannerView.layoutParams = FrameLayout.LayoutParams(width, heightPx)
                    container.addView(bannerView)
                    container.visibility = View.VISIBLE
                }

                override fun onBannerShown(bannerAdView: BannerView?) {
                    container.visibility = View.VISIBLE
                }

                override fun onBannerFailedToLoad(
                    bannerAdView: BannerView?,
                    errorInfo: BannerErrorInfo?
                ) {
                    Log.w(TAG, "Unity Banner [$placementId] no fill (${errorInfo?.errorMessage}), trying next placement...")
                    try { bannerView.destroy() } catch (_: Exception) {}
                    loadUnityBannerWaterfall(activity, container, placementIndex + 1)
                }

                override fun onBannerClick(bannerAdView: BannerView?) {}

                override fun onBannerLeftApplication(bannerAdView: BannerView?) {}
            }

            bannerView.load()
        } catch (e: Exception) {
            Log.e(TAG, "Unity Banner exception on $placementId", e)
            loadUnityBannerWaterfall(activity, container, placementIndex + 1)
        }
    }

    /**
     * تحميل بنر AdMob الحقيقي فقط (ممنوع استخدام أي معرفات Test نهائياً)
     */
    private fun loadAdmobBanner(activity: Activity, container: ViewGroup, retryCount: Int) {
        if (activity.isFinishing || activity.isDestroyed) return
        try {
            Log.d(TAG, "Loading Real AdMob Commercial Banner: ${AdConfig.BANNER_ID}")
            val adView = AdView(activity).apply {
                setAdSize(AdSize.BANNER)
                adUnitId = AdConfig.BANNER_ID
            }

            adView.adListener = object : AdListener() {
                override fun onAdLoaded() {
                    if (activity.isFinishing || activity.isDestroyed) return
                    Log.d(TAG, "Real AdMob Banner loaded successfully!")
                    container.removeAllViews()
                    container.addView(adView)
                    container.visibility = View.VISIBLE
                }

                override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                    Log.w(TAG, "Real AdMob Banner failed to load (code=${loadAdError.code}): ${loadAdError.message}")
                    // Never load Test Ads! Retry real commercial ad unit after backoff delay
                    if (retryCount < 3 && !activity.isFinishing && !activity.isDestroyed) {
                        val delayMs = (retryCount + 1) * 8_000L
                        mainHandler.postDelayed({
                            if (!activity.isFinishing && !activity.isDestroyed) {
                                loadAdmobBanner(activity, container, retryCount + 1)
                            }
                        }, delayMs)
                    }
                }
            }

            adView.loadAd(AdRequest.Builder().build())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * تحميل مسبق للإعلانات البينية التجارية (Unity Ads Waterfall + Real AdMob)
     */
    fun loadInterstitial(context: Context) {
        if (!AdConfig.isAdsEnabled) return
        val appContext = context.applicationContext

        if (AdConfig.isUnityAdsConfigured() && isUnityInitialized) {
            if (loadedUnityPlacementId == null && !isUnityInterstitialLoading) {
                loadUnityInterstitialWaterfall(appContext, 0)
            }
        }

        loadAdmobInterstitial(appContext)
    }

    private fun loadUnityInterstitialWaterfall(context: Context, index: Int) {
        val placements = AdConfig.UNITY_INTERSTITIAL_PLACEMENTS
        if (index >= placements.size) {
            isUnityInterstitialLoading = false
            // Schedule automatic retry of Unity waterfall
            val delay = ((unityRetryAttempt + 1).coerceAtMost(4)) * 10_000L
            unityRetryAttempt++
            mainHandler.postDelayed({
                if (loadedUnityPlacementId == null && !isUnityInterstitialLoading && isUnityInitialized) {
                    loadUnityInterstitialWaterfall(context, 0)
                }
            }, delay)
            return
        }

        isUnityInterstitialLoading = true
        val placementId = placements[index]
        Log.d(TAG, "Loading Unity Commercial Interstitial [$placementId]...")
        try {
            UnityAds.load(
                placementId,
                object : IUnityAdsLoadListener {
                    override fun onUnityAdsAdLoaded(loadedId: String) {
                        Log.d(TAG, "Unity Commercial Interstitial READY: $loadedId")
                        loadedUnityPlacementId = loadedId
                        isUnityInterstitialLoading = false
                        unityRetryAttempt = 0
                    }

                    override fun onUnityAdsFailedToLoad(
                        failedId: String,
                        error: UnityAds.UnityAdsLoadError,
                        message: String
                    ) {
                        Log.w(TAG, "Unity placement [$failedId] failed ($error): $message -> trying next placement")
                        loadUnityInterstitialWaterfall(context, index + 1)
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Unity Ads load exception on $placementId", e)
            loadUnityInterstitialWaterfall(context, index + 1)
        }
    }

    /**
     * تحميل الإعلان البيني الحقيقي من AdMob (بدون أي معرفات تجريبية Test IDs إطلاقاً)
     */
    private fun loadAdmobInterstitial(context: Context) {
        val appContext = context.applicationContext
        if (admobInterstitialAd == null && !isAdmobInterstitialLoading) {
            isAdmobInterstitialLoading = true
            val adRequest = AdRequest.Builder().build()
            InterstitialAd.load(
                appContext,
                AdConfig.INTERSTITIAL_ID,
                adRequest,
                object : InterstitialAdLoadCallback() {
                    override fun onAdLoaded(ad: InterstitialAd) {
                        Log.d(TAG, "Real AdMob Commercial Interstitial READY!")
                        admobInterstitialAd = ad
                        isAdmobInterstitialLoading = false
                        admobRetryAttempt = 0
                    }

                    override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                        Log.w(TAG, "Real AdMob Interstitial failed (code=${loadAdError.code}): ${loadAdError.message}")
                        admobInterstitialAd = null
                        isAdmobInterstitialLoading = false

                        // Schedule automatic retry with real commercial unit ID
                        val delay = ((admobRetryAttempt + 1).coerceAtMost(4)) * 10_000L
                        admobRetryAttempt++
                        mainHandler.postDelayed({
                            loadAdmobInterstitial(appContext)
                        }, delay)
                    }
                }
            )
        }
    }

    /**
     * عرض الإعلان البيني التجاري الحقيقي:
     * 1. إذا كان إعلان Unity التجاري جاهزاً -> يُعرض فوراً.
     * 2. إذا كان إعلان AdMob التجاري جاهزاً -> يُعرض فوراً.
     * 3. إذا كان التحميل جارياً -> يطلب التحميل للمرة القادمة وينفذ الإجراء فوراً بدون تجميد واجهة المستخدم.
     */
    fun showInterstitial(activity: Activity, force: Boolean = false, onDismissed: () -> Unit = {}) {
        if (!AdConfig.isAdsEnabled || activity.isFinishing || activity.isDestroyed || isGracePeriodActive(activity)) {
            onDismissed()
            return
        }

        val now = System.currentTimeMillis()
        if (!force && (now - lastAdShowTime < 12_000L)) {
            onDismissed()
            return
        }

        // 1. Check Unity Commercial Interstitial first
        val readyUnityPlacement = loadedUnityPlacementId
        if (readyUnityPlacement != null && AdConfig.isUnityAdsConfigured()) {
            showUnityInterstitial(activity, readyUnityPlacement, onDismissed)
            return
        }

        // 2. Check Real AdMob Commercial Interstitial immediately (zero UI lag)
        if (admobInterstitialAd != null) {
            showAdmobInterstitial(activity, onDismissed)
            return
        }

        // 3. Trigger background preload for next opportunity and proceed immediately
        loadInterstitial(activity.applicationContext)
        onDismissed()
    }

    private fun showUnityInterstitial(activity: Activity, placementId: String, onDismissed: () -> Unit) {
        lastAdShowTime = System.currentTimeMillis()
        isAdShowing = true
        loadedUnityPlacementId = null

        try {
            UnityAds.show(
                activity,
                placementId,
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
                        loadInterstitial(activity.applicationContext)
                        onDismissed()
                    }

                    override fun onUnityAdsShowFailure(
                        placementId: String,
                        error: UnityAds.UnityAdsShowError,
                        message: String
                    ) {
                        Log.e(TAG, "Unity show failed: $error - $message")
                        isAdShowing = false
                        if (admobInterstitialAd != null) {
                            showAdmobInterstitial(activity, onDismissed)
                        } else {
                            loadInterstitial(activity.applicationContext)
                            onDismissed()
                        }
                    }
                }
            )
        } catch (e: Exception) {
            isAdShowing = false
            if (admobInterstitialAd != null) {
                showAdmobInterstitial(activity, onDismissed)
            } else {
                loadInterstitial(activity.applicationContext)
                onDismissed()
            }
        }
    }

    private fun showAdmobInterstitial(activity: Activity, onDismissed: () -> Unit) {
        val ad = admobInterstitialAd
        if (ad != null && !activity.isFinishing && !activity.isDestroyed) {
            lastAdShowTime = System.currentTimeMillis()
            isAdShowing = true
            admobInterstitialAd = null

            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdShowedFullScreenContent() {
                    isAdShowing = true
                }

                override fun onAdDismissedFullScreenContent() {
                    isAdShowing = false
                    loadInterstitial(activity.applicationContext)
                    onDismissed()
                }

                override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                    isAdShowing = false
                    loadInterstitial(activity.applicationContext)
                    onDismissed()
                }
            }
            ad.show(activity)
        } else {
            isAdShowing = false
            loadInterstitial(activity.applicationContext)
            onDismissed()
        }
    }

    /**
     * إظهار الإعلان البيني التجاري تلقائياً بعد مهلة قصيرة
     */
    fun autoShow(activity: Activity, delayMs: Long = 900L, force: Boolean = false, onDismissed: () -> Unit = {}) {
        activity.window.decorView.postDelayed({
            if (!activity.isFinishing && !activity.isDestroyed) {
                showInterstitial(activity, force = force, onDismissed = onDismissed)
            }
        }, delayMs)
    }
}
