package com.gearforge.app

import android.app.Activity
import android.content.SharedPreferences
import android.text.TextUtils
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.AcknowledgePurchaseResponseListener
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetailsResponseListener
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesResponseListener
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsResult
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.RETURNS_SELF
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class BillingAcknowledgementTest {
    @Test
    fun coldStartAcknowledgesPurchasedProWithoutWaitingToGrantEntitlement() = withBilling { harness ->
        harness.connected()
        harness.answerQuery(listOf(purchase()))
        assertTrue(harness.settings.isPro)
        assertEquals(listOf("pro-token"), harness.acknowledgements.map { it.token })
    }

    @Test
    fun restoreAcknowledgesPurchasedProAndReportsExistingEntitlementPolicy() = withBilling { harness ->
        harness.connected()
        harness.answerQuery(emptyList())
        var restored: Boolean? = null
        harness.manager.restorePurchases { restored = it }
        harness.answerQuery(listOf(purchase()))
        assertEquals(true, restored)
        assertTrue(harness.settings.isPro)
        assertEquals(1, harness.acknowledgements.size)
    }

    @Test
    fun queuedRestoreAcknowledgesOnFirstSuccessfulConnection() = withBilling { harness ->
        var restored: Boolean? = null
        harness.manager.restorePurchases { restored = it }
        assertEquals(null, restored)
        harness.connected()
        assertEquals(1, harness.queries.size)
        harness.answerQuery(listOf(purchase()))
        assertEquals(true, restored)
        assertEquals(1, harness.acknowledgements.size)
    }

    @Test
    fun alreadyAcknowledgedProDoesNotSendAnotherAcknowledgement() = withBilling { harness ->
        harness.connected()
        harness.answerQuery(listOf(purchase(acknowledged = true)))
        assertTrue(harness.settings.isPro)
        assertTrue(harness.acknowledgements.isEmpty())
    }

    @Test
    fun pendingProDoesNotGrantOrAcknowledge() = withBilling { harness ->
        val pending = purchase(pending = true)
        assertEquals(Purchase.PurchaseState.PENDING, pending.purchaseState)
        harness.connected()
        harness.answerQuery(listOf(pending))
        harness.updated.onPurchasesUpdated(result(), listOf(pending))
        assertFalse(harness.settings.isPro)
        assertTrue(harness.acknowledgements.isEmpty())
    }

    @Test
    fun unrelatedQueriedProductDoesNotGrantOrAcknowledge() = withBilling { harness ->
        harness.connected()
        harness.answerQuery(listOf(purchase(product = "other-product")))
        assertFalse(harness.settings.isPro)
        assertTrue(harness.acknowledgements.isEmpty())
    }

    @Test
    fun queryFailurePreservesCachedProWithoutAcknowledging() = withBilling { harness ->
        harness.settings.isPro = true
        harness.connected()
        harness.answerQuery(listOf(purchase()), BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE)
        assertTrue(harness.settings.isPro)
        assertTrue(harness.acknowledgements.isEmpty())
    }

    @Test
    fun failedAcknowledgementIsRetriedByNextQueryWithoutDowngradingPro() = withBilling { harness ->
        harness.connected()
        harness.answerQuery(listOf(purchase()))
        assertEquals(1, harness.acknowledgements.size)
        harness.acknowledgements.single().reply(BillingClient.BillingResponseCode.NETWORK_ERROR)
        assertTrue(harness.settings.isPro)
        harness.manager.queryPurchases()
        harness.answerQuery(listOf(purchase()))
        assertEquals(listOf("pro-token", "pro-token"), harness.acknowledgements.map { it.token })
        harness.acknowledgements.last().reply()
        assertTrue(harness.settings.isPro)
    }

    @Test
    fun reconnectRetriesFailedAckWithoutDuplicatingAnInFlightQueryAck() = withBilling { harness ->
        harness.connected()
        harness.updated.onPurchasesUpdated(result(), listOf(purchase()))
        harness.acknowledgements.single().reply(BillingClient.BillingResponseCode.NETWORK_ERROR)
        harness.connection.onBillingServiceDisconnected()
        harness.connected()
        harness.answerQuery(listOf(purchase()))
        assertEquals(2, harness.acknowledgements.size)
    }

    @Test
    fun concurrentListenerAndQueryResultsAcknowledgeTokenOnlyOnce() = withBilling { harness ->
        harness.connected()
        harness.updated.onPurchasesUpdated(result(), listOf(purchase()))
        harness.updated.onPurchasesUpdated(result(), listOf(purchase()))
        harness.answerQuery(listOf(purchase()))
        assertEquals(1, harness.acknowledgements.size)
    }

    @Test
    fun successfulAckDoesNotDiscardAnotherTokensFailedAck() = withBilling { harness ->
        harness.connected()
        harness.updated.onPurchasesUpdated(result(), listOf(purchase(token = "first"), purchase(token = "second")))
        harness.acknowledgements[0].reply(BillingClient.BillingResponseCode.NETWORK_ERROR)
        harness.acknowledgements[1].reply()
        harness.connection.onBillingServiceDisconnected()
        harness.connected()
        assertEquals(listOf("first", "second", "first"), harness.acknowledgements.map { it.token })
    }

    @Test
    fun queryCallbackAfterCloseDoesNotAcknowledgeGrantOrNotifyUi() = withBilling { harness ->
        harness.connected()
        harness.answerQuery(emptyList())
        harness.manager.queryPurchases { error("Late UI result") }
        harness.manager.onProChanged = { error("Late Pro change") }
        harness.manager.close()
        harness.answerQuery(listOf(purchase()))
        assertFalse(harness.settings.isPro)
        assertTrue(harness.acknowledgements.isEmpty())
    }

    @Test
    fun queryCallbackWhileActivityFinishesDoesNotAcknowledgeOrGrant() = withBilling { harness ->
        harness.connected()
        `when`(harness.activity.isFinishing).thenReturn(true)
        harness.answerQuery(listOf(purchase()))
        assertFalse(harness.settings.isPro)
        assertTrue(harness.acknowledgements.isEmpty())
    }

    @Test
    fun latePurchaseListenerAfterCloseDoesNotGrantPro() = withBilling { harness ->
        harness.manager.close()
        harness.updated.onPurchasesUpdated(result(), listOf(purchase()))
        assertFalse(harness.settings.isPro)
        assertTrue(harness.acknowledgements.isEmpty())
    }

    @Test
    fun lateConnectionAfterActivityDestructionDoesNotQuery() = withBilling { harness ->
        `when`(harness.activity.isDestroyed).thenReturn(true)
        harness.connected()
        assertTrue(harness.queries.isEmpty())
    }

    @Test
    fun lateProductDetailsAfterCloseCannotLaunchBillingOrNotifyUi() = withBilling { harness ->
        harness.connected()
        harness.manager.purchasePro { error("Late purchase result") }
        harness.manager.close()
        harness.detailsCallback!!.onProductDetailsResponse(result(), mock(QueryProductDetailsResult::class.java))
        verify(harness.client, never()).launchBillingFlow(any(), any())
    }

    @Test
    fun lateFailedAckAndDisconnectAfterCloseCannotRestartBilling() = withBilling { harness ->
        harness.connected()
        harness.updated.onPurchasesUpdated(result(), listOf(purchase()))
        val starts = harness.connectionStarts
        harness.manager.close()
        harness.acknowledgements.single().reply(BillingClient.BillingResponseCode.NETWORK_ERROR)
        harness.connection.onBillingServiceDisconnected()
        harness.connected()
        assertEquals(starts, harness.connectionStarts)
        assertEquals(1, harness.acknowledgements.size)
    }

    @Test
    fun successfulAckIsNotRetriedOnReconnect() = withBilling { harness ->
        harness.connected()
        harness.answerQuery(listOf(purchase()))
        harness.acknowledgements.single().reply()
        harness.connection.onBillingServiceDisconnected()
        harness.connected()
        harness.answerQuery(listOf(purchase(acknowledged = true)))
        assertEquals(1, harness.acknowledgements.size)
    }

    @Test
    fun closeDuringProNotificationStopsSubsequentQueryCallback() = withBilling { harness ->
        harness.connected()
        harness.answerQuery(emptyList())
        harness.manager.onProChanged = { harness.manager.close() }
        harness.manager.queryPurchases { error("Callback after close") }
        harness.answerQuery(listOf(purchase()))
        assertTrue(harness.settings.isPro)
    }

    private fun withBilling(test: (Harness) -> Unit) {
        mockStatic(Log::class.java).use {
            mockStatic(TextUtils::class.java) { invocation ->
                if (invocation.method.name == "isEmpty") {
                    invocation.getArgument<CharSequence?>(0).isNullOrEmpty()
                } else null
            }.use {
                val harness = Harness()
                mockStatic(BillingClient::class.java) { invocation ->
                    if (invocation.method.name == "newBuilder") harness.builder else null
                }.use {
                    harness.manager = BillingManager(harness.activity, harness.settings)
                    try {
                        test(harness)
                    } finally {
                        harness.manager.close()
                    }
                }
            }
        }
    }

    private class Harness {
        val activity = mock(Activity::class.java)
        val client = mock(BillingClient::class.java)
        val builder = mock(BillingClient.Builder::class.java, RETURNS_SELF)
        private val preferences = mock(SharedPreferences::class.java)
        val settings = SettingsStore(preferences)
        lateinit var manager: BillingManager
        lateinit var connection: BillingClientStateListener
        lateinit var updated: PurchasesUpdatedListener
        val queries = mutableListOf<PurchasesResponseListener>()
        val acknowledgements = mutableListOf<Ack>()
        var detailsCallback: ProductDetailsResponseListener? = null
        var connectionStarts = 0

        init {
            val values = mutableMapOf<String, Boolean>()
            val editor = mock(SharedPreferences.Editor::class.java, RETURNS_SELF)
            `when`(preferences.edit()).thenReturn(editor)
            doAnswer { invocation ->
                values[invocation.getArgument(0)] ?: invocation.getArgument<Boolean>(1)
            }.`when`(preferences).getBoolean(anyString(), anyBoolean())
            doAnswer { invocation ->
                values[invocation.getArgument(0)] = invocation.getArgument(1)
                editor
            }.`when`(editor).putBoolean(anyString(), anyBoolean())
            `when`(builder.build()).thenReturn(client)
            doAnswer { invocation ->
                updated = invocation.getArgument(0)
                builder
            }.`when`(builder).setListener(any())
            doAnswer { invocation ->
                connection = invocation.getArgument(0)
                connectionStarts++
                null
            }.`when`(client).startConnection(any())
            doAnswer { invocation ->
                queries.add(invocation.getArgument(1))
                null
            }.`when`(client).queryPurchasesAsync(any(), any())
            doAnswer { invocation ->
                val params = invocation.getArgument<AcknowledgePurchaseParams>(0)
                acknowledgements.add(Ack(params.purchaseToken, invocation.getArgument(1)))
                null
            }.`when`(client).acknowledgePurchase(any(), any())
            doAnswer { invocation ->
                detailsCallback = invocation.getArgument(1)
                null
            }.`when`(client).queryProductDetailsAsync(any(), any())
        }

        fun connected() = connection.onBillingSetupFinished(result())

        fun answerQuery(purchases: List<Purchase>, code: Int = BillingClient.BillingResponseCode.OK) {
            queries.removeAt(0).onQueryPurchasesResponse(result(code), purchases)
        }
    }

    private class Ack(val token: String, private val callback: AcknowledgePurchaseResponseListener) {
        fun reply(code: Int = BillingClient.BillingResponseCode.OK) =
            callback.onAcknowledgePurchaseResponse(result(code))
    }

    private companion object {
        fun result(code: Int = BillingClient.BillingResponseCode.OK): BillingResult =
            BillingResult.newBuilder().setResponseCode(code).build()

        fun purchase(
            token: String = "pro-token",
            product: String = "gearforge_pro",
            acknowledged: Boolean = false,
            pending: Boolean = false
        ): Purchase = Purchase(
            JSONObject()
                .put("productId", product)
                .put("purchaseToken", token)
                .put("purchaseState", if (pending) 4 else 0)
                .put("acknowledged", acknowledged)
                .toString(),
            "test-signature"
        )
    }
}