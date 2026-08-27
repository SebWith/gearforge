package com.gearforge.app

import android.app.Activity
import android.util.Log
import com.google.android.ump.ConsentForm
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.FormError
import com.google.android.ump.UserMessagingPlatform

/**
 * Thin UMP (User Messaging Platform) consent wrapper.
 *
 * All UMP calls are guarded so that a missing or failing consent SDK never blocks
 * the app or ad loading: [ensureConsent] always invokes [onDone] exactly once, even
 * when the UMP APIs are unavailable or throw at runtime.
 */
class ConsentManager(private val activity: Activity) {

    private val consentInformation: ConsentInformation? by lazy {
        runCatching { UserMessagingPlatform.getConsentInformation(activity) }.getOrNull()
    }

    /**
     * Requests consent info and, if a consent form is required, loads and shows it
     * before invoking [onDone]. Call this before [AdManager.init].
     */
    fun ensureConsent(onDone: () -> Unit) {
        val info = consentInformation
        if (info == null) {
            Log.w(TAG, "UMP unavailable; proceeding without consent flow")
            onDone()
            return
        }
        val params = try {
            ConsentRequestParameters.Builder()
                .setTagForUnderAgeOfConsent(false)
                .build()
        } catch (t: Throwable) {
            Log.w(TAG, "UMP parameters could not be built", t)
            onDone()
            return
        }
        try {
            info.requestConsentInfoUpdate(
                activity,
                params,
                { onConsentInfoUpdateSuccess(info, onDone) },
                { error -> onConsentInfoUpdateFailure(error, onDone) }
            )
        } catch (t: Throwable) {
            Log.w(TAG, "UMP requestConsentInfoUpdate failed", t)
            onDone()
        }
    }

    private fun onConsentInfoUpdateSuccess(info: ConsentInformation, onDone: () -> Unit) {
        if (info.isConsentFormAvailable) {
            loadAndShowForm(info, onDone)
        } else {
            onDone()
        }
    }

    private fun onConsentInfoUpdateFailure(error: FormError, onDone: () -> Unit) {
        Log.w(TAG, "UMP consent info update failed: ${error.errorCode} ${error.message}")
        onDone()
    }

    private fun loadAndShowForm(info: ConsentInformation, onDone: () -> Unit) {
        try {
            UserMessagingPlatform.loadConsentForm(
                activity,
                { form -> showForm(info, form, onDone) },
                { error ->
                    Log.w(TAG, "UMP form load failed: ${error.errorCode} ${error.message}")
                    onDone()
                }
            )
        } catch (t: Throwable) {
            Log.w(TAG, "UMP loadConsentForm failed", t)
            onDone()
        }
    }

    private fun showForm(info: ConsentInformation, form: ConsentForm, onDone: () -> Unit) {
        if (info.consentStatus == ConsentInformation.ConsentStatus.REQUIRED) {
            try {
                form.show(activity) { onDone() }
            } catch (t: Throwable) {
                Log.w(TAG, "UMP form.show failed", t)
                onDone()
            }
        } else {
            onDone()
        }
    }

    private companion object {
        const val TAG = "ConsentManager"
    }
}
