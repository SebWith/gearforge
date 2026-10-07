package com.gearforge.app

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.OnUserEarnedRewardListener
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import java.util.concurrent.Executor

/**
 * Process-wide rewarded-ad state: one SDK initialization and at most one preloaded ad, so an
 * activity recreation (font scale, locale) neither re-initializes nor drops the loaded ad.
 * Fields are main-thread confined; see build/audit/ads-anr-20261001/REPORT.md.
 */
internal class RewardedAdSource(
    /** The Application, never an Activity: the preloaded ad outlives the activity that requested it. */
    private val context: Application,
    private val adUnitId: String,
    private val canRequestAds: () -> Boolean,
    private val background: Executor,
    private val main: Executor,
    private val now: () -> Long = SystemClock::elapsedRealtime
) {
    private var initRequested = false
    private var initialized = false
    private var loading = false
    private var ad: RewardedAd? = null
    private var loadedAt = 0L

    /** The SDK allows one full-screen ad per process at a time. */
    var showing = false

    /** Initializes once (off the main thread) and keeps one ad loaded or loading. Main thread only. */
    fun ensureLoaded() {
        if (!canRequestAds()) return
        if (!initRequested) {
            initRequested = true
            background.execute {
                MobileAds.initialize(context) {}
                main.execute {
                    initialized = true
                    ensureLoaded()
                }
            }
            return
        }
        if (ad != null && isExpired()) ad = null
        if (!initialized || loading || ad != null) return
        loading = true
        // Must be called on the main thread (SDK precondition #008); OPTIMIZE_AD_LOADING in the
        // manifest moves the load's own work to the SDK's background threads.
        RewardedAd.load(
            context, adUnitId, AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(loaded: RewardedAd) {
                    main.execute {
                        loading = false
                        ad = loaded.takeIf { canRequestAds() }
                        loadedAt = now()
                    }
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    main.execute {
                        loading = false
                        ad = null
                    }
                }
            }
        )
    }

    /** Hands out the preloaded ad once; null when there is none, it expired, or consent no longer allows ads. */
    fun take(): RewardedAd? {
        val current = ad
        ad = null
        return current?.takeIf { canRequestAds() && !isExpired() }
    }

    fun discard() {
        ad = null
    }

    // Google's rewarded guide: "ads expire after an hour"; this ad can now outlive many activities.
    private fun isExpired() = now() - loadedAt >= AD_MAX_AGE_MS

    companion object {
        private const val AD_MAX_AGE_MS = 60 * 60 * 1000L

        @Volatile private var shared: RewardedAdSource? = null

        fun shared(context: Context): RewardedAdSource =
            shared ?: synchronized(this) {
                shared ?: (context.applicationContext as Application).let { app ->
                    RewardedAdSource(
                        context = app,
                        // android/build.gradle `admobRewardedUnitId`; Google's test unit by default.
                        adUnitId = BuildConfig.ADMOB_REWARDED_UNIT_ID,
                        canRequestAds = { ConsentManager.canRequestAds(app) },
                        background = Dispatchers.IO.asExecutor(),
                        main = ContextCompat.getMainExecutor(app)
                    )
                }.also { shared = it }
            }
    }
}

/** AdMob rewarded-ad wrapper for one activity; the SDK state is process-wide ([RewardedAdSource]). */
class AdManager internal constructor(
    private val activity: Activity,
    private val source: RewardedAdSource
) {
    constructor(activity: Activity) : this(activity, RewardedAdSource.shared(activity))

    private val consentManager = ConsentManager(activity)

    private fun isActivityActive() = !activity.isFinishing && !activity.isDestroyed

    private fun canUseAds() = isActivityActive() && consentManager.canRequestAds()

    /**
     * Initializes the Mobile Ads SDK (once per process) and pre-loads a rewarded ad. Call this
     * only after consent has been resolved (see [ConsentManager]) so ad loading respects the
     * user's UMP consent choice.
     */
    fun init() {
        if (!canUseAds()) return
        source.ensureLoaded()
    }

    fun loadRewarded() {
        if (!canUseAds()) return
        source.ensureLoaded()
    }

    /**
    * Shows the rewarded ad. While the Activity is alive, exactly one callback is invoked:
     * - [onReward] after the user earns the reward and the ad has fully dismissed;
     * - [onDismissed] when the ad was closed without earning a reward;
     * - [onUnavailable] when no ad is ready, the activity is going away, or showing failed.
     *
     * The call never throws and is safe to invoke repeatedly: a concurrent or failed show
     * is reported via [onUnavailable] instead of crashing the export flow.
     */
    fun showRewarded(
        onReward: () -> Unit,
        onDismissed: () -> Unit = {},
        onUnavailable: () -> Unit = {}
    ) {
        if (!canUseAds()) {
            if (!consentManager.canRequestAds()) source.discard()
            if (isActivityActive()) onUnavailable()
            return
        }
        // Re-entrancy guard: the SDK throws "only one fullscreen ad at a time" if a
        // second show is attempted while one is already on screen.
        if (source.showing) {
            onUnavailable()
            return
        }
        val ad = source.take()
        if (ad == null) {
            loadRewarded()
            onUnavailable()
            return
        }
        source.showing = true
        var earned = false
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                source.showing = false
                ad.fullScreenContentCallback = null
                if (!isActivityActive()) return
                loadRewarded()
                if (earned) onReward() else onDismissed()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                source.showing = false
                ad.fullScreenContentCallback = null
                if (!isActivityActive()) return
                loadRewarded()
                onUnavailable()
            }
        }
        try {
            ad.show(activity, OnUserEarnedRewardListener { earned = true })
        } catch (t: Throwable) {
            source.showing = false
            ad.fullScreenContentCallback = null
            if (!isActivityActive()) return
            loadRewarded()
            onUnavailable()
        }
    }
}
