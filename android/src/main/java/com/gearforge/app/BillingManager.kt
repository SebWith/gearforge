package com.gearforge.app

import android.app.Activity
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

/** Google Play Billing for the one-time Pro unlock. */
class BillingManager(private val activity: Activity, private val settings: SettingsStore) {

    // Must match the product configured in Play Console before release.
    private val productId = "gearforge_pro"

    private var billingClient: BillingClient? = null
    private var connected = false

    /** Token of a purchase whose acknowledge failed; retried on the next successful connection. */
    private var pendingAckToken: String? = null

    /** Notifies the UI whenever Pro status changes (purchase, restore, query). */
    var onProChanged: ((Boolean) -> Unit)? = null

    private val purchasesUpdated = PurchasesUpdatedListener { result, purchases ->
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (p in purchases) {
                when (p.purchaseState) {
                    Purchase.PurchaseState.PURCHASED -> {
                        // Acknowledge so the purchase is finalized and can't be auto-refunded.
                        acknowledge(p)
                        if (p.products.contains(productId)) setPro(true)
                    }
                    // PENDING: surface later, but do NOT grant until a final PURCHASED state arrives.
                    Purchase.PurchaseState.PENDING -> { /* no-op: entitlement stays unchanged */ }
                }
            }
        }
    }

    init {
        billingClient = BillingClient.newBuilder(activity)
            .setListener(purchasesUpdated)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder()
                    .enableOneTimeProducts()
                    .build()
            )
            .build()
        connect()
    }

    private fun connect() {
        billingClient?.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                connected = result.responseCode == BillingClient.BillingResponseCode.OK
                if (connected) {
                    retryPendingAcknowledge()
                    queryPurchases()
                }
            }

            override fun onBillingServiceDisconnected() {
                connected = false
                // BillingClient retries on the next request; reconnect eagerly to stay ready.
                connect()
            }
        })
    }

    private fun acknowledge(purchase: Purchase) {
        if (purchase.isAcknowledged) return
        acknowledgeToken(purchase.purchaseToken)
    }

    private fun acknowledgeToken(token: String) {
        billingClient?.acknowledgePurchase(
            AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(token)
                .build()
        ) { result ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.w(TAG, "acknowledge failed: ${result.responseCode} ${result.debugMessage}")
                // An unacknowledged purchase is auto-refunded by Google after ~3 days,
                // so keep the token and retry on the next successful connection.
                pendingAckToken = token
            } else {
                pendingAckToken = null
            }
        }
    }

    private fun retryPendingAcknowledge() {
        val token = pendingAckToken ?: return
        pendingAckToken = null
        acknowledgeToken(token)
    }

    private fun setPro(value: Boolean) {
        if (settings.isPro == value) return
        settings.isPro = value
        onProChanged?.invoke(value)
    }

    /** Queries owned purchases and refreshes Pro status. Returns whether Pro is active. */
    fun queryPurchases(onResult: (Boolean) -> Unit = {}) {
        billingClient?.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        ) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                val pro = purchases.any {
                    it.purchaseState == Purchase.PurchaseState.PURCHASED &&
                        it.products.contains(productId)
                }
                setPro(pro)
                onResult(pro)
            } else {
                onResult(settings.isPro)  // keep cached status; do NOT downgrade
            }
        }
    }

    /** Restores purchases (used by the "Restore purchases" button). */
    fun restorePurchases(onResult: (Boolean) -> Unit) {
        if (!connected) {
            onResult(false)
            return
        }
        queryPurchases(onResult)
    }

    fun purchasePro(onResult: (Boolean) -> Unit) {
        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(productId)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        )
        billingClient?.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder().setProductList(productList).build()
        ) { result, productDetailsResult ->
            val details = productDetailsResult.productDetailsList
            if (result.responseCode == BillingClient.BillingResponseCode.OK && details.isNotEmpty()) {
                val flow = BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(
                        listOf(
                            BillingFlowParams.ProductDetailsParams.newBuilder()
                                .setProductDetails(details[0])
                                .build()
                        )
                    )
                    .build()
                val launchResult = billingClient?.launchBillingFlow(activity, flow)
                onResult(launchResult?.responseCode == BillingClient.BillingResponseCode.OK)
            } else {
                Log.w(TAG, "purchase failed: ${result.responseCode} ${result.debugMessage}")
                onResult(false)
            }
        }
    }

    /** Releases the BillingClient connection (call from Activity.onDestroy). */
    fun close() {
        billingClient?.endConnection()
        billingClient = null
        connected = false
    }

    private companion object {
        const val TAG = "BillingManager"
    }
}
