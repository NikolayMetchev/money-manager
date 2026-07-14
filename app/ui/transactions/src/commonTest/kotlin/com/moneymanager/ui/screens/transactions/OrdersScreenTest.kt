@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.ui.screens.transactions

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.waitUntilExactlyOneExists
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.AuditType
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.ExchangeOrder
import com.moneymanager.domain.model.ExchangeOrderAuditEntry
import com.moneymanager.domain.model.ExchangeOrderId
import com.moneymanager.domain.model.JsonPath
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.SourceRecord
import com.moneymanager.domain.model.Trade
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.repository.AuditReadRepository
import com.moneymanager.domain.repository.ExchangeOrderReadRepository
import com.moneymanager.ui.error.ProvideSchemaAwareScope
import com.moneymanager.ui.screens.orders.ExchangeOrderAuditScreen
import com.moneymanager.ui.screens.orders.OrderDetailScreen
import com.moneymanager.ui.screens.orders.OrdersScreen
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class OrdersScreenTest {
    private val accountId = AccountId(10L)
    private val orderId = ExchangeOrderId(1L)
    private val createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private val order =
        ExchangeOrder(
            id = orderId,
            accountId = accountId,
            orderRef = "6142909958761646675",
            clientOid = "co-1",
            side = "SELL",
            orderType = "LIMIT",
            timeInForce = "GOOD_TILL_CANCEL",
            status = "FILLED",
            limitPrice = "0.0000010999",
            quantity = "1630",
            avgPrice = "0.0000010999",
            createdAt = createdAt,
        )

    private val usd = Currency(id = CurrencyId(1L), code = "USD", name = "US Dollar")
    private val btc = Currency(id = CurrencyId(2L), code = "BTC", name = "Bitcoin")

    private val fillTrade =
        Trade(
            id = TradeId(42L),
            timestamp = createdAt,
            description = "Buy BTC/USD",
            fromAccountId = accountId,
            from = Money(50_000_00L, usd),
            toAccountId = accountId,
            to = Money(1_00L, btc),
        )

    private fun repository(
        orders: List<ExchangeOrder> = listOf(order),
        fills: List<Trade> = listOf(fillTrade),
    ): ExchangeOrderReadRepository =
        mock(MockMode.autoUnit) {
            every { getOrdersByAccount(any()) } returns flowOf(orders)
            every { getFillCountsByAccount(any()) } returns flowOf(orders.associate { it.id to fills.size.toLong() })
            every { getOrderById(any()) } returns flowOf(orders.firstOrNull())
            every { getFillTradesForOrder(any()) } returns flowOf(fills)
            every { countOrdersByAccount(any()) } returns flowOf(orders.size.toLong())
        }

    @Test
    fun ordersList_rendersOrderRows_andClickReportsOrderId() =
        runMoneyManagerComposeUiTest {
            var clicked: ExchangeOrderId? = null
            setContent {
                ProvideSchemaAwareScope {
                    OrdersScreen(
                        accountId = accountId,
                        exchangeOrderRepository = repository(),
                        onOrderClick = { clicked = it },
                    )
                }
            }
            waitUntilExactlyOneExists(hasText("SELL"))
            waitForIdle()
            onNodeWithText("LIMIT · GOOD_TILL_CANCEL").assertIsDisplayed()
            onNodeWithText("FILLED").assertIsDisplayed()
            onNodeWithText("1 fill").assertIsDisplayed()

            onNodeWithText("SELL").performClick()
            waitForIdle()
            assertEquals(orderId, clicked)
        }

    @Test
    fun ordersList_showsEmptyState_whenAccountHasNoOrders() =
        runMoneyManagerComposeUiTest {
            setContent {
                ProvideSchemaAwareScope {
                    OrdersScreen(
                        accountId = accountId,
                        exchangeOrderRepository = repository(orders = emptyList(), fills = emptyList()),
                    )
                }
            }
            waitUntilExactlyOneExists(hasText("No orders"))
            waitForIdle()
        }

    @Test
    fun orderDetail_rendersFieldsAndFills_andClickReportsFillTrade() =
        runMoneyManagerComposeUiTest {
            var clickedFill: Trade? = null
            setContent {
                ProvideSchemaAwareScope {
                    OrderDetailScreen(
                        orderId = orderId,
                        exchangeOrderRepository = repository(),
                        onFillTradeClick = { clickedFill = it },
                    )
                }
            }
            waitUntilExactlyOneExists(hasText("6142909958761646675"))
            waitForIdle()
            onNodeWithText("SELL").assertIsDisplayed()
            onNodeWithText("FILLED").assertIsDisplayed()
            onNodeWithText("1 fill trade").assertIsDisplayed()
            onNodeWithText("Buy BTC/USD").assertIsDisplayed()

            onNodeWithText("Buy BTC/USD").performClick()
            waitForIdle()
            assertEquals(fillTrade, clickedFill)
        }

    @Test
    fun orderDetail_backButtonStaysReachable_whenTheOrderDoesNotResolve() =
        runMoneyManagerComposeUiTest {
            // A stale/deleted order id never emits an order; the user must still be able to navigate away.
            var backClicks = 0
            setContent {
                ProvideSchemaAwareScope {
                    OrderDetailScreen(
                        orderId = orderId,
                        exchangeOrderRepository = repository(orders = emptyList(), fills = emptyList()),
                        onBack = { backClicks++ },
                    )
                }
            }
            waitUntilExactlyOneExists(hasText("Order not found"))
            waitForIdle()

            onNodeWithText("← Back").performClick()
            waitForIdle()
            assertEquals(1, backClicks)
        }

    @Test
    fun orderDetail_fillTrade_hasOwnAuditButton_reportingTheFill() =
        runMoneyManagerComposeUiTest {
            var auditedFill: Trade? = null
            setContent {
                ProvideSchemaAwareScope {
                    OrderDetailScreen(
                        orderId = orderId,
                        exchangeOrderRepository = repository(),
                        onFillTradeAuditClick = { auditedFill = it },
                    )
                }
            }
            waitUntilExactlyOneExists(hasTestTag("fillTradeAudit-${fillTrade.id.id}"))
            waitForIdle()

            onNodeWithTag("fillTradeAudit-${fillTrade.id.id}").performClick()
            waitForIdle()
            assertEquals(fillTrade, auditedFill)
        }

    @Test
    fun ordersList_backButton_reportsBack() =
        runMoneyManagerComposeUiTest {
            var backClicks = 0
            setContent {
                ProvideSchemaAwareScope {
                    OrdersScreen(
                        accountId = accountId,
                        exchangeOrderRepository = repository(),
                        onBack = { backClicks++ },
                    )
                }
            }
            waitUntilExactlyOneExists(hasText("← Back"))
            waitForIdle()

            onNodeWithText("← Back").performClick()
            waitForIdle()
            assertEquals(1, backClicks)
        }

    @Test
    fun orderDetail_auditButton_reportsOrderId() =
        runMoneyManagerComposeUiTest {
            var auditOrder: ExchangeOrderId? = null
            setContent {
                ProvideSchemaAwareScope {
                    OrderDetailScreen(
                        orderId = orderId,
                        exchangeOrderRepository = repository(),
                        onAuditClick = { auditOrder = it },
                    )
                }
            }
            waitUntilExactlyOneExists(hasText("Audit history"))
            waitForIdle()

            onNodeWithText("Audit history").performClick()
            waitForIdle()
            assertEquals(orderId, auditOrder)
        }

    @Test
    fun orderAudit_rendersEntry_andApiSourceLinksToImportingRequest() =
        runMoneyManagerComposeUiTest {
            val sessionId = ApiSessionId(5L)
            val requestId = ApiRequestId(9L)
            val jsonPath = "\$.result.data[0]"
            val entry =
                ExchangeOrderAuditEntry(
                    id = 1L,
                    auditTimestamp = createdAt,
                    auditType = AuditType.INSERT,
                    orderId = orderId,
                    revisionId = 1L,
                    accountId = accountId,
                    orderRef = order.orderRef,
                    clientOid = order.clientOid,
                    side = order.side,
                    orderType = order.orderType,
                    timeInForce = order.timeInForce,
                    status = order.status,
                    limitPrice = order.limitPrice,
                    quantity = order.quantity,
                    avgPrice = order.avgPrice,
                    createdAt = createdAt,
                    updatedAt = null,
                    source =
                        SourceRecord(
                            id = 1L,
                            entityType = EntityType.EXCHANGE_ORDER,
                            entityId = orderId.id,
                            revisionId = 1L,
                            source = Source.Api(sessionId, requestId, JsonPath(jsonPath)),
                            deviceId = 1L,
                            deviceInfo = null,
                            fileName = null,
                            createdAt = createdAt,
                        ),
                )
            val auditRepository =
                mock<AuditReadRepository>(MockMode.autoUnit) {
                    everySuspend { getAuditHistoryForExchangeOrder(any()) } returns listOf(entry)
                }

            var linked: Triple<ApiSessionId, ApiRequestId, String>? = null
            setContent {
                ProvideSchemaAwareScope {
                    ExchangeOrderAuditScreen(
                        orderId = orderId,
                        auditRepository = auditRepository,
                        onApiSourceClick = { s, r, p -> linked = Triple(s, r, p) },
                        onBack = {},
                    )
                }
            }
            waitUntilExactlyOneExists(hasText("FILLED"))
            waitForIdle()

            onNodeWithText("API Import").performClick()
            waitForIdle()
            assertEquals(Triple(sessionId, requestId, jsonPath), linked)
        }
}
