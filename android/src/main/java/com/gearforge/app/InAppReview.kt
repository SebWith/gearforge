package com.gearforge.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.util.Log
import com.google.android.play.core.review.ReviewManagerFactory

/**
 * Google Play In-App Review — closed-test report, opportunity 4 ("Rate your app").
 *
 * There are deliberately two different entry points, because Google's own guidance
 * for the in-app review flow forbids launching it from a call-to-action button:
 *
 *  - [maybeRequestAfterExport] shows the native review card **once per install**,
 *    immediately after the user has finished something real — a gear file actually
 *    written to Downloads. This is the "neutral timing, after a successful gear
 *    design or export" the tester report asked for.
 *  - [openStoreListing] backs the explicit "Rate this app" row in Settings. It opens
 *    the Play listing, which is the supported pattern for a *user-initiated* rating
 *    and still works when the review quota is exhausted.
 *
 * Everything here is best-effort. The Play Core API may decide not to show anything
 * at all (quota, device policy, sideloaded build), so no path may throw, block the
 * export, or surface an error to the user.
 */
internal object InAppReview {

    private const val TAG = "InAppReview"

    /** Guards against launching two flows in the same session. */
    @Volatile
    private var flowInFlight = false

    /**
     * Requests the native review card after a successful export, at most once per
     * install. Safe to call from any thread; [activity] may be null when the host
     * activity is already gone, in which case the request is simply skipped.
     */
    fun maybeRequestAfterExport(activity: Activity?, settings: SettingsStore) {
        if (activity == null) return
        if (settings.reviewRequested) return
        if (flowInFlight) return
        flowInFlight = true
        // Set before the call: the flow is quota-limited and commonly displays
        // nothing, and we never want to ask the same user twice.
        settings.reviewRequested = true
        request(activity)
    }

    /**
     * Opens the Play Store listing so the user can rate the app directly. This is the
     * explicit, user-initiated path — it must never be wired to anything automatic.
     */
    fun openStoreListing(context: Context) {
        val packageName = context.packageName
        val market = Intent(Intent.ACTION_VIEW, "market://details?id=$packageName".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val web = Intent(
            Intent.ACTION_VIEW,
            "https://play.google.com/store/apps/details?id=$packageName".toUri()
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        // Devices without the Play Store (bare emulator images, some tablets) throw
        // ActivityNotFoundException; fall back to the web listing instead of crashing.
        runCatching { context.startActivity(market) }
            .onFailure { runCatching { context.startActivity(web) } }
    }

    private fun request(activity: Activity) {
        runCatching {
            val manager = ReviewManagerFactory.create(activity)
            manager.requestReviewFlow().addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    // Expected on sideloaded builds and when the quota is exhausted.
                    Log.d(TAG, "review flow unavailable", task.exception)
                    flowInFlight = false
                    return@addOnCompleteListener
                }
                runCatching { manager.launchReviewFlow(activity, task.result) }
                    .onFailure { Log.d(TAG, "launchReviewFlow failed", it) }
            }
        }.onFailure {
            Log.d(TAG, "requestReviewFlow failed", it)
            flowInFlight = false
        }
    }
}
