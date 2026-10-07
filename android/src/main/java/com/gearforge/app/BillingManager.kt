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
    private var closed = false

    /** Unacknowledged tokens, retried by ownership queries or the next successful connection. */
    private val pendingAckTokens = mutableSetOf<String>()
    private val acknowledgingTokens = mutableSetOf<String>()

    /** Restore request that arrived before the billing client had connected. */
    private var pendingRestore: ((Boolean) -> Unit)? = null

    /** Notifies the UI whenever Pro status changes (purchase, restore, query). */
    var onProChanged: ((Boolean) -> Unit)? = null

    private fun isActive() = !closed && !activity.isFinishing && !activity.isDestroyed

    private val purchasesUpdated = PurchasesUpdatedListener { result, purchases ->
        if (!isActive()) return@PurchasesUpdatedListener
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
        if (!isActive()) return
        billingClient?.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (!isActive()) return
                connected = result.responseCode == BillingClient.BillingResponseCode.OK
                if (connected) {
                    retryPendingAcknowledge()
                    // A queued restore already performs the ownership query; running a
                    // second one here would just double the round trip.
                    if (pendingRestore != null) flushPendingRestore() else queryPurchases()
                } else {
                    // The store is unreachable (sideloaded build, no Play services). Answer a
                    // queued restore from the cached status rather than leaving the user's
                    // button permanently silent.
                    pendingRestore?.let { callback ->
                        pendingRestore = null
                        callback(settings.isPro)
                    }
                }
            }

            override fun onBillingServiceDisconnected() {
                if (!isActive()) return
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
        if (!isActive()) return
        val client = billingClient ?: return
        if (!acknowledgingTokens.add(token)) return
        pendingAckTokens.add(token)
        client.acknowledgePurchase(
            AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(token)
                .build()
        ) { result ->
            if (!isActive()) return@acknowledgePurchase
            acknowledgingTokens.remove(token)
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.w(TAG, "acknowledge failed: ${result.responseCode} ${result.debugMessage}")
            } else {
                pendingAckTokens.remove(token)
            }
        }
    }

    private fun retryPendingAcknowledge() {
        pendingAckTokens.toList().forEach(::acknowledgeToken)
    }

    private fun setPro(value: Boolean) {
        if (!isActive()) return
        if (settings.isPro == value) return
        settings.isPro = value
        onProChanged?.invoke(value)
    }

    /** Queries owned purchases and refreshes Pro status. Returns whether Pro is active. */
    fun queryPurchases(onResult: (Boolean) -> Unit = {}) {
        if (!isActive()) return
        billingClient?.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        ) { result, purchases ->
            if (!isActive()) return@queryPurchasesAsync
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                val proPurchases = purchases.filter {
                    it.purchaseState == Purchase.PurchaseState.PURCHASED &&
                        it.products.contains(productId)
                }
                proPurchases.forEach(::acknowledge)
                val pro = proPurchases.isNotEmpty()
                setPro(pro)
                if (isActive()) onResult(pro)
            } else {
                onResult(settings.isPro)  // keep cached status; do NOT downgrade
            }
        }
    }

    /**
     * Restores purchases (used by the "Restore purchases" button).
     *
     * The callback reports whether Pro is active. It is deliberately **never** invoked with
     * `false` just because the billing client has not finished connecting: on a cold start,
     * and again after every service disconnect (which resets [connected]), the honest answer
     * is "not known yet". Reporting `false` there told a paying user their purchase could not
     * be found — the exact opposite of what restore is for — and invited a needless re-buy.
     * The request is queued instead and answered from the first successful connection.
     */
    fun restorePurchases(onResult: (Boolean) -> Unit) {
        if (!isActive()) return
        if (connected) {
            queryPurchases(onResult)
            return
        }
        Log.i(TAG, "restore requested before billing was connected; queuing")
        pendingRestore = onResult
        connect()
    }

    /** Answers a restore request that was queued while the client was connecting. */
    private fun flushPendingRestore() {
        val callback = pendingRestore ?: return
        pendingRestore = null
        queryPurchases(callback)
    }

    fun purchasePro(onResult: (Boolean) -> Unit) {
        if (!isActive()) return
        if (!connected) {
            // The purchase flow needs a live client. Kick a reconnect so that the *next*
            // attempt can succeed instead of failing for a state the user cannot see.
            Log.i(TAG, "purchase requested before billing was connected; reconnecting")
            connect()
        }
        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(productId)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        )
        billingClient?.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder().setProductList(productList).build()
        ) { result, productDetailsResult ->
            if (!isActive()) return@queryProductDetailsAsync
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
        closed = true
        pendingRestore = null
        onProChanged = null
        pendingAckTokens.clear()
        acknowledgingTokens.clear()
        val client = billingClient
        billingClient = null
        connected = false
        client?.endConnection()
    }

    private companion object {
        const val TAG = "BillingManager"
    }
}
