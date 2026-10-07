package com.gearforge.app

import android.app.Activity
import android.app.Application
import android.util.Log
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.OnUserEarnedRewardListener
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardItem
import com.google.android.ump.ConsentForm
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentInformation.PrivacyOptionsRequirementStatus
import com.google.android.ump.FormError
import com.google.android.ump.UserMessagingPlatform
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`

class ConsentAdsRegressionTest {
    @Test
    fun umpUpdateFailureWithoutPermissionDoesNotInitializeOrLoadAds() = withAds { harness ->
        harness.failConsentUpdate()
        ConsentManager(harness.activity).ensureConsent { harness.ads.init() }
        assertEquals(0, harness.initializations)
        assertEquals(0, harness.loads)
    }

    @Test
    fun umpUpdateFailureWithCachedPermissionStillLoadsAds() = withAds { harness ->
        `when`(harness.info.canRequestAds()).thenReturn(true)
        harness.failConsentUpdate()
        ConsentManager(harness.activity).ensureConsent { harness.ads.init() }
        assertEquals(1, harness.initializations)
        assertEquals(1, harness.loads)
    }

    @Test
    fun directLoadCannotBypassConsent() = withAds { harness ->
        harness.ads.loadRewarded()
        assertEquals(0, harness.loads)
    }

    @Test
    fun unavailableShowCannotBypassConsentThroughReload() = withAds { harness ->
        var unavailable = 0
        harness.ads.showRewarded(onReward = { error("Unexpected reward") }, onUnavailable = { unavailable++ })
        assertEquals(1, unavailable)
        assertEquals(0, harness.loads)
    }

    @Test
    fun finishingActivityCannotInitializeOrLoadAds() = withAds { harness ->
        `when`(harness.info.canRequestAds()).thenReturn(true)
        `when`(harness.activity.isFinishing).thenReturn(true)
        harness.ads.init()
        harness.ads.loadRewarded()
        assertEquals(0, harness.initializations)
        assertEquals(0, harness.loads)
    }

    @Test
    fun unavailableUmpCompletesWithoutLoadingAds() = withAds { harness ->
        harness.infoAvailable = false
        var completions = 0
        ConsentManager(harness.activity).ensureConsent {
            completions++
            harness.ads.init()
        }
        assertEquals(1, completions)
        assertEquals(0, harness.initializations)
        assertEquals(0, harness.loads)
    }

    @Test
    fun permissionReadFailureBlocksAds() = withAds { harness ->
        `when`(harness.info.canRequestAds()).thenThrow(IllegalStateException("UMP unavailable"))
        harness.ads.init()
        harness.ads.loadRewarded()
        assertEquals(0, harness.initializations)
        assertEquals(0, harness.loads)
    }

    @Test
    fun callbackFollowedByUmpExceptionCompletesOnlyOnce() = withAds { harness ->
        harness.failConsentUpdate(throwAfterCallback = true)
        var completions = 0
        ConsentManager(harness.activity).ensureConsent { completions++ }
        assertEquals(1, completions)
    }

    @Test
    fun lateConsentUpdateCannotLoadFormAfterActivityDestruction() = withAds { harness ->
        ConsentManager(harness.activity).ensureConsent { error("Late completion") }
        `when`(harness.activity.isDestroyed).thenReturn(true)
        harness.consentSuccess!!.onConsentInfoUpdateSuccess()
        assertEquals(0, harness.formLoads)
    }

    @Test
    fun lateFormDismissalAfterActivityFinishesDoesNotStartAds() = withAds { harness ->
        `when`(harness.info.canRequestAds()).thenReturn(true)
        ConsentManager(harness.activity).ensureConsent { harness.ads.init() }
        harness.consentSuccess!!.onConsentInfoUpdateSuccess()
        assertEquals(1, harness.formLoads)
        `when`(harness.activity.isFinishing).thenReturn(true)
        harness.formDismissed!!.onConsentFormDismissed(null)
        assertEquals(0, harness.initializations)
        assertEquals(0, harness.loads)
    }

    @Test
    fun consentFormGoesThroughLoadAndShowIfRequiredAndAdsWaitForIt() = withAds { harness ->
        `when`(harness.info.canRequestAds()).thenReturn(true)
        var completions = 0
        ConsentManager(harness.activity).ensureConsent {
            completions++
            harness.ads.init()
        }
        harness.consentSuccess!!.onConsentInfoUpdateSuccess()
        assertEquals(1, harness.formLoads)
        assertEquals(0, harness.legacyFormLoads)
        assertEquals(0, harness.initializations)
        harness.formDismissed!!.onConsentFormDismissed(null)
        assertEquals(1, completions)
        assertEquals(1, harness.initializations)
        assertEquals(1, harness.loads)
    }

    @Test
    fun sdkInitializationRunsOnBackgroundExecutorAndLoadIsPostedToMain() = withAds(queued = true) { harness ->
        `when`(harness.info.canRequestAds()).thenReturn(true)
        harness.ads.init()
        assertEquals(0, harness.initializations)
        assertEquals(0, harness.loads)
        harness.background.drain()
        assertEquals(listOf("background"), harness.initializedOn)
        assertEquals(0, harness.loads)
        harness.main.drain()
        assertEquals(listOf("main"), harness.loadedOn)
    }

    @Test
    fun adIsLoadedWithApplicationContextNotTheActivity() = withAds { harness ->
        `when`(harness.info.canRequestAds()).thenReturn(true)
        harness.ads.init()
        assertEquals(listOf<Any?>(harness.appContext), harness.initContexts)
        assertEquals(listOf<Any?>(harness.appContext), harness.loadContexts)
    }

    @Test
    fun recreatedActivityReusesInitializationAndPreloadedAd() = withAds { harness ->
        `when`(harness.info.canRequestAds()).thenReturn(true)
        ConsentManager(harness.activity).ensureConsent { harness.ads.init() }
        harness.consentSuccess!!.onConsentInfoUpdateSuccess()
        harness.formDismissed!!.onConsentFormDismissed(null)
        val ad = mock(RewardedAd::class.java)
        harness.adLoaded!!.onAdLoaded(ad)

        // A font-scale change destroys the activity and creates a new one in the same process.
        `when`(harness.activity.isDestroyed).thenReturn(true)
        val recreated = mock(Activity::class.java)
        val recreatedAds = AdManager(recreated, harness.source)
        ConsentManager(recreated).ensureConsent { recreatedAds.init() }
        harness.consentSuccess!!.onConsentInfoUpdateSuccess()
        harness.formDismissed!!.onConsentFormDismissed(null)
        assertEquals(1, harness.initializations)
        assertEquals(1, harness.loads)

        recreatedAds.showRewarded(onReward = {}, onUnavailable = { error("Preloaded ad lost") })
        verify(ad).show(eq(recreated), any())
    }

    @Test
    fun preloadedAdOlderThanAnHourIsReplacedAndNeverShown() = withAds { harness ->
        `when`(harness.info.canRequestAds()).thenReturn(true)
        harness.ads.init()
        val stale = mock(RewardedAd::class.java)
        harness.adLoaded!!.onAdLoaded(stale)
        harness.clockMs = 60 * 60 * 1000L
        var unavailable = 0
        harness.ads.showRewarded(onReward = { error("Expired ad shown") }, onUnavailable = { unavailable++ })
        assertEquals(1, unavailable)
        assertEquals(2, harness.loads)
        verifyNoInteractions(stale)
    }

    @Test
    fun repeatedRequestsShareOneLoadAndFailureIsNotRetriedAutomatically() = withAds { harness ->
        `when`(harness.info.canRequestAds()).thenReturn(true)
        harness.ads.init()
        harness.ads.init()
        harness.ads.loadRewarded()
        assertEquals(1, harness.loads)
        harness.adLoaded!!.onAdFailedToLoad(mock(LoadAdError::class.java))
        assertEquals(1, harness.loads)
        harness.ads.loadRewarded()
        assertEquals(2, harness.loads)
    }

    @Test
    fun lateUmpFailureDoesNotNotifyDestroyedActivity() = withAds { harness ->
        ConsentManager(harness.activity).ensureConsent { error("Late completion") }
        `when`(harness.activity.isDestroyed).thenReturn(true)
        harness.consentFailure!!.onConsentInfoUpdateFailure(mock(FormError::class.java))
        assertEquals(0, harness.loads)
    }

    @Test
    fun permissionRevokedDuringLoadDiscardsAdAndBlocksShow() = withAds { harness ->
        `when`(harness.info.canRequestAds()).thenReturn(true)
        harness.ads.init()
        val ad = mock(RewardedAd::class.java)
        `when`(harness.info.canRequestAds()).thenReturn(false)
        harness.adLoaded!!.onAdLoaded(ad)
        var unavailable = 0
        harness.ads.showRewarded(onReward = { error("Unexpected reward") }, onUnavailable = { unavailable++ })
        assertEquals(1, unavailable)
        assertEquals(1, harness.loads)
        verifyNoInteractions(ad)
    }

    @Test
    fun privacyOptionsEntryIsOfferedOnlyWhenUmpRequiresIt() = withAds { harness ->
        `when`(harness.info.privacyOptionsRequirementStatus).thenReturn(PrivacyOptionsRequirementStatus.REQUIRED)
        assertTrue(ConsentManager(harness.activity).privacyOptionsRequired())
        `when`(harness.info.privacyOptionsRequirementStatus).thenReturn(PrivacyOptionsRequirementStatus.NOT_REQUIRED)
        assertFalse(ConsentManager(harness.activity).privacyOptionsRequired())
    }

    @Test
    fun privacyOptionsFormReportsShownOnSuccess() = withAds { harness ->
        var shown: Boolean? = null
        ConsentManager(harness.activity).showPrivacyOptions { shown = it }
        assertEquals(1, harness.privacyFormShows)
        harness.privacyFormDismissed!!.onConsentFormDismissed(null)
        assertEquals(true, shown)
    }

    @Test
    fun privacyOptionsFormReportsFailureOnError() = withAds { harness ->
        var shown: Boolean? = null
        ConsentManager(harness.activity).showPrivacyOptions { shown = it }
        harness.privacyFormDismissed!!.onConsentFormDismissed(mock(FormError::class.java))
        assertEquals(false, shown)
    }

    @Test
    fun privacyOptionsFormIsNotShownAfterActivityDestruction() = withAds { harness ->
        `when`(harness.activity.isDestroyed).thenReturn(true)
        var shown: Boolean? = null
        ConsentManager(harness.activity).showPrivacyOptions { shown = it }
        assertEquals(0, harness.privacyFormShows)
        assertEquals(false, shown)
    }

    @Test
    fun dismissalAfterActivityDestructionDoesNotReloadOrDeliverReward() = withAds { harness ->
        `when`(harness.info.canRequestAds()).thenReturn(true)
        harness.ads.init()
        val ad = mock(RewardedAd::class.java)
        var fullScreenCallback: FullScreenContentCallback? = null
        doAnswer { invocation ->
            fullScreenCallback = invocation.getArgument(0)
            null
        }.`when`(ad).fullScreenContentCallback = any()
        doAnswer { invocation ->
            invocation.getArgument<OnUserEarnedRewardListener>(1)
                .onUserEarnedReward(mock(RewardItem::class.java))
            null
        }.`when`(ad).show(eq(harness.activity), any())
        harness.adLoaded!!.onAdLoaded(ad)
        harness.ads.showRewarded(
            onReward = { error("Late reward") },
            onDismissed = { error("Late dismissal") },
            onUnavailable = { error("Late failure") }
        )
        `when`(harness.activity.isDestroyed).thenReturn(true)
        fullScreenCallback!!.onAdDismissedFullScreenContent()
        assertEquals(1, harness.loads)
    }

    private fun withAds(queued: Boolean = false, test: (Harness) -> Unit) {
        mockStatic(Log::class.java).use {
            val harness = Harness(queued)
            mockStatic(UserMessagingPlatform::class.java) { invocation ->
                when (invocation.method.name) {
                    "getConsentInformation" -> harness.info.takeIf { harness.infoAvailable }
                    "loadAndShowConsentFormIfRequired" -> {
                        harness.formLoads++
                        harness.formDismissed = invocation.getArgument(1)
                        null
                    }
                    "showPrivacyOptionsForm" -> {
                        harness.privacyFormShows++
                        harness.privacyFormDismissed = invocation.getArgument(1)
                        null
                    }
                    "loadConsentForm" -> {
                        harness.legacyFormLoads++
                        null
                    }
                    else -> null
                }
            }.use {
                mockStatic(MobileAds::class.java) { invocation ->
                    if (invocation.method.name == "initialize") {
                        harness.initializations++
                        harness.initializedOn.add(harness.lane)
                        harness.initContexts.add(invocation.getArgument(0))
                    }
                    null
                }.use {
                    mockStatic(RewardedAd::class.java) { invocation ->
                        if (invocation.method.name == "load") {
                            harness.loads++
                            harness.loadedOn.add(harness.lane)
                            harness.loadContexts.add(invocation.getArgument(0))
                            harness.adLoaded = invocation.getArgument(3)
                        }
                        null
                    }.use { test(harness) }
                }
            }
        }
    }

    private class Harness(queued: Boolean) {
        val activity = mock(Activity::class.java)
        val appContext = mock(Application::class.java)
        val info = mock(ConsentInformation::class.java)

        /** Name of the executor whose task is running; "caller" outside any task. */
        var lane = "caller"

        inner class Lane(private val name: String, private val queued: Boolean) : Executor {
            private val tasks = ArrayDeque<Runnable>()
            override fun execute(command: Runnable) {
                if (queued) tasks.addLast(command) else run(command)
            }

            fun drain() {
                while (tasks.isNotEmpty()) run(tasks.removeFirst())
            }

            private fun run(command: Runnable) {
                val previous = lane
                lane = name
                try {
                    command.run()
                } finally {
                    lane = previous
                }
            }
        }

        val background = Lane("background", queued)
        val main = Lane("main", queued)
        var clockMs = 0L
        val source = RewardedAdSource(
            appContext, "test-unit", { ConsentManager.canRequestAds(appContext) }, background, main, { clockMs }
        )
        val ads = AdManager(activity, source)
        var initializations = 0
        var loads = 0
        val initializedOn = mutableListOf<String>()
        val loadedOn = mutableListOf<String>()
        val initContexts = mutableListOf<Any?>()
        val loadContexts = mutableListOf<Any?>()
        var infoAvailable = true
        var formLoads = 0
        var legacyFormLoads = 0
        var privacyFormShows = 0
        var consentSuccess: ConsentInformation.OnConsentInfoUpdateSuccessListener? = null
        var consentFailure: ConsentInformation.OnConsentInfoUpdateFailureListener? = null
        var formDismissed: ConsentForm.OnConsentFormDismissedListener? = null
        var privacyFormDismissed: ConsentForm.OnConsentFormDismissedListener? = null
        var adLoaded: RewardedAdLoadCallback? = null

        init {
            doAnswer { invocation ->
                consentSuccess = invocation.getArgument(2)
                consentFailure = invocation.getArgument(3)
                null
            }.`when`(info).requestConsentInfoUpdate(any(), any(), any(), any())
        }

        fun failConsentUpdate(throwAfterCallback: Boolean = false) {
            doAnswer { invocation ->
                invocation.getArgument<ConsentInformation.OnConsentInfoUpdateFailureListener>(3)
                    .onConsentInfoUpdateFailure(mock(FormError::class.java))
                if (throwAfterCallback) throw IllegalStateException("Failure after callback")
                null
            }.`when`(info).requestConsentInfoUpdate(any(), any(), any(), any())
        }
    }
}