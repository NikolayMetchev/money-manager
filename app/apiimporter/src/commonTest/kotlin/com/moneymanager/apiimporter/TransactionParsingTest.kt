package com.moneymanager.apiimporter

import com.moneymanager.domain.model.ApiImportStrategyId
import com.moneymanager.domain.model.apistrategy.ApiAccountMappings
import com.moneymanager.domain.model.apistrategy.ApiAccountsSource
import com.moneymanager.domain.model.apistrategy.ApiAmountFormat
import com.moneymanager.domain.model.apistrategy.ApiDataEndpoint
import com.moneymanager.domain.model.apistrategy.ApiEndpointConfig
import com.moneymanager.domain.model.apistrategy.ApiEndpointKind
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.model.apistrategy.ApiStrategyConfig
import com.moneymanager.domain.model.apistrategy.ApiTransactionMappings
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.StrategyRule
import com.moneymanager.domain.model.rules.ValueExpr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

class TransactionParsingTest {
    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private fun strategy(mappings: ApiTransactionMappings = ApiTransactionMappings()) =
        ApiImportStrategy(
            id = ApiImportStrategyId(Uuid.random()),
            name = "test",
            config =
                ApiStrategyConfig(
                    baseUrl = "https://api.test.com",
                    accounts =
                        ApiAccountsSource.Downloaded(
                            endpoint = ApiEndpointConfig(path = "/accounts", responseArrayKey = "accounts"),
                            mappings = ApiAccountMappings(),
                        ),
                    dataEndpoints =
                        listOf(
                            ApiDataEndpoint(
                                endpoint = ApiEndpointConfig(path = "/transactions", responseArrayKey = "transactions"),
                                kind = ApiEndpointKind.BANK_TRANSACTIONS,
                                transactionMappings = mappings,
                            ),
                        ),
                ),
            createdAt = now,
            updatedAt = now,
        )

    private fun item(
        amount: String = "1234",
        extra: String = "",
    ) = """{"id":"t1","amount":$amount,"created":"2025-03-01T10:00:00Z","currency":"gbp","description":"Coffee"$extra}"""

    private fun parse(
        json: String,
        strategy: ApiImportStrategy = strategy(),
    ) = parseTransactionsWithPath(json, strategy)

    @Test
    fun `a complete item is parsed with its currency upper-cased`() {
        val parsed = parse("""{"transactions":[${item()}]}""").single()

        assertEquals(1234L, parsed.amountMinorUnits)
        assertEquals("GBP", parsed.currencyCode)
        assertEquals("Coffee", parsed.description)
    }

    @Test
    fun `an item missing a required field is skipped`() {
        val json = """{"transactions":[{"id":"t1","amount":5,"created":"2025-03-01T10:00:00Z"}]}"""

        assertEquals(emptyList(), parse(json))
    }

    @Test
    fun `a body that is not json yields no items`() {
        assertEquals(emptyList(), parse("not json"))
    }

    @Test
    fun `a body without the response array yields no items`() {
        assertEquals(emptyList(), parse("""{"other":[]}"""))
    }

    @Test
    fun `a rule that skips an item drops it`() {
        val skip = StrategyRule(actions = listOf(RuleAction.Skip))

        assertEquals(emptyList(), parse("""{"transactions":[${item()}]}""", strategy(ApiTransactionMappings(rules = listOf(skip)))))
    }

    @Test
    fun `a balance rule reads the reported balance and its time`() {
        val balance =
            StrategyRule(
                actions =
                    listOf(
                        RuleAction.Balance(
                            amount = ValueExpr.of("balance"),
                            at = ValueExpr.of("balanceAt"),
                        ),
                    ),
            )
        val withBalance = strategy(ApiTransactionMappings(rules = listOf(balance)))

        val parsed =
            parse(
                """{"transactions":[${item(extra = ""","balance":5000,"balanceAt":"2025-03-01T11:00:00Z"""")}]}""",
                withBalance,
            ).single()

        assertNotNull(parsed.balance)
        assertNotNull(parsed.balanceAt)
    }

    @Test
    fun `an unparsable balance time is an error`() {
        val balance =
            StrategyRule(
                actions = listOf(RuleAction.Balance(amount = ValueExpr.of("balance"), at = ValueExpr.of("balanceAt"))),
            )
        val withBalance = strategy(ApiTransactionMappings(rules = listOf(balance)))

        assertFailsWith<IllegalStateException> {
            parse(
                """{"transactions":[${item(extra = ""","balance":5000,"balanceAt":"garbage"""")}]}""",
                withBalance,
            )
        }
    }

    @Test
    fun `a decimal amount keeps its major units`() {
        val decimal = strategy(ApiTransactionMappings(amountFormat = ApiAmountFormat.DECIMAL_MAJOR_UNITS))

        val parsed = parse("""{"transactions":[${item(amount = "\"12.34\"")}]}""", decimal).single()

        assertNull(parsed.amountMinorUnits)
        assertNotNull(parsed.amountDecimalMajor)
    }
}
