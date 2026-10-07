package com.gearforge.app

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.FormError
import com.google.android.ump.UserMessagingPlatform

/**
 * Thin UMP (User Messaging Platform) consent wrapper.
 *
 * All UMP calls are guarded so that a missing or failing consent SDK never blocks
 * the app: [ensureConsent] invokes its completion at most once for a live Activity,
 * even when the UMP APIs are unavailable or throw at runtime. Ads still require [canRequestAds].
 */
class ConsentManager(private val activity: Activity) {

    private val consentInformation: ConsentInformation? by lazy {
        runCatching { UserMessagingPlatform.getConsentInformation(activity) }.getOrNull()
    }

    fun canRequestAds(): Boolean =
        runCatching { consentInformation?.canRequestAds() == true }.getOrDefault(false)

    private fun isActivityActive() = !activity.isFinishing && !activity.isDestroyed

    /**
     * Requests consent info and, if consent must be collected, loads and shows the form
     * before invoking [onDone]. Call this before [AdManager.init].
     */
    fun ensureConsent(onDone: () -> Unit) {
        if (!isActivityActive()) return
        var completed = false
        fun finish() {
            if (completed) return
            completed = true
            if (isActivityActive()) onDone()
        }
        val info = consentInformation
        if (info == null) {
            Log.w(TAG, "UMP unavailable; proceeding without consent flow")
            finish()
            return
        }
        val params = try {
            ConsentRequestParameters.Builder()
                .setTagForUnderAgeOfConsent(false)
                .build()
        } catch (t: Throwable) {
            Log.w(TAG, "UMP parameters could not be built", t)
            finish()
            return
        }
        try {
            info.requestConsentInfoUpdate(
                activity,
                params,
                { onConsentInfoUpdateSuccess(::finish) },
                { error -> onConsentInfoUpdateFailure(error, ::finish) }
            )
        } catch (t: Throwable) {
            Log.w(TAG, "UMP requestConsentInfoUpdate failed", t)
            finish()
        }
    }

    private fun onConsentInfoUpdateSuccess(onDone: () -> Unit) {
        if (!isActivityActive()) return
        // Builds a form WebView only when consent must be collected; otherwise completes at once.
        try {
            UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { error ->
                if (error != null) Log.w(TAG, "UMP consent form: ${error.errorCode} ${error.message}")
                onDone()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "UMP loadAndShowConsentFormIfRequired failed", t)
            onDone()
        }
    }

    private fun onConsentInfoUpdateFailure(error: FormError, onDone: () -> Unit) {
        Log.w(TAG, "UMP consent info update failed: ${error.errorCode} ${error.message}")
        onDone()
    }

    /**
     * True when the consent state requires a privacy-options entry point (UMP reports
     * [ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED]). The Settings sheet
     * uses this to show/hide its "Privacy options" row.
     */
    fun privacyOptionsRequired(): Boolean = runCatching {
        consentInformation?.privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
    }.getOrDefault(false)

    /**
     * Opens the UMP privacy-options form. [onDone] receives true when the form was shown
     * (no error), false otherwise. Safe on all API levels and when UMP is unavailable.
     */
    fun showPrivacyOptions(onDone: (Boolean) -> Unit = {}) {
        if (!isActivityActive()) {
            onDone(false)
            return
        }
        try {
            UserMessagingPlatform.showPrivacyOptionsForm(activity) { error ->
                if (error != null) Log.w(TAG, "UMP privacy options form: ${error.errorCode} ${error.message}")
                onDone(error == null)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "UMP showPrivacyOptionsForm failed", t)
            onDone(false)
        }
    }

    companion object {
        private const val TAG = "ConsentManager"

        /** Process-wide view of [canRequestAds] for state that outlives an activity. */
        internal fun canRequestAds(context: Context): Boolean =
            runCatching { UserMessagingPlatform.getConsentInformation(context).canRequestAds() }
                .getOrDefault(false)
    }
}
