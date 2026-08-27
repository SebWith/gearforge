package com.gearforge.app

import android.app.Activity
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.OnUserEarnedRewardListener
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback

/** AdMob rewarded-ad wrapper (test ad unit until a real one is provided). */
class AdManager(private val activity: Activity) {

    // Single source of truth lives in android/build.gradle (gradle property
    // `admobRewardedUnitId`) exposed via BuildConfig. Defaults to Google's official
    // test rewarded unit. Swap to a real unit before Play release — see MONETIZATION_CONFIG.md.
    private val adUnitId = BuildConfig.ADMOB_REWARDED_UNIT_ID
    @Volatile private var rewardedAd: RewardedAd? = null
    @Volatile private var initialized = false
    @Volatile private var showing = false

    /**
     * Initializes the Mobile Ads SDK and pre-loads a rewarded ad. Call this only after
     * consent has been resolved (see [ConsentManager]) so ad loading respects the user's
     * UMP consent choice.
     */
    fun init() {
        if (initialized) return
        initialized = true
        MobileAds.initialize(activity) {}
        loadRewarded()
    }

    fun loadRewarded() {
        RewardedAd.load(
            activity, adUnitId, AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    rewardedAd = ad
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    rewardedAd = null
                }
            }
        )
    }

    /**
     * Shows the rewarded ad. Exactly one of the callbacks is invoked (on the main thread):
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
        // Re-entrancy guard: the SDK throws "only one fullscreen ad at a time" if a
        // second show is attempted while one is already on screen.
        if (showing) {
            onUnavailable()
            return
        }
        val ad = rewardedAd
        if (ad == null) {
            loadRewarded()
            onUnavailable()
            return
        }
        if (activity.isFinishing || activity.isDestroyed) {
            onUnavailable()
            return
        }
        showing = true
        rewardedAd = null
        var earned = false
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                showing = false
                ad.fullScreenContentCallback = null
                loadRewarded()
                if (earned) onReward() else onDismissed()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                showing = false
                ad.fullScreenContentCallback = null
                loadRewarded()
                onUnavailable()
            }
        }
        try {
            ad.show(activity, OnUserEarnedRewardListener { earned = true })
        } catch (t: Throwable) {
            showing = false
            ad.fullScreenContentCallback = null
            loadRewarded()
            onUnavailable()
        }
    }
}
