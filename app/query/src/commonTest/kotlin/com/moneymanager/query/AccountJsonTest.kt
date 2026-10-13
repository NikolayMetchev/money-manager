package com.moneymanager.query

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class AccountJsonTest {
    private val json = Json { prettyPrint = true }

    @Test
    fun accountsSerializeWithDecimalStringAmounts() {
        val accounts =
            listOf(
                AccountWithBalances(
                    account = account(1, "Kraken Wallet"),
                    owners = listOf(alice, bob),
                    balances =
                        listOf(
                            money("12.50", gbp),
                            money("-3", gbp),
                            money("0", usd),
                            money("0.000000000000000001", btc),
                            money("123456789012.123456789", btc),
                        ),
                    isShadow = true,
                ),
            )

        assertEquals(
            """
            [
                {
                    "id": 1,
                    "name": "Kraken Wallet",
                    "openingDate": "2024-01-02T03:04:05Z",
                    "categoryId": -1,
                    "shadow": true,
                    "owners": [
                        {
                            "id": 1,
                            "name": "Alice Smith"
                        },
                        {
                            "id": 2,
                            "name": "Bob"
                        }
                    ],
                    "balances": [
                        {
                            "asset": "GBP",
                            "kind": "currency",
                            "amount": "12.5"
                        },
                        {
                            "asset": "GBP",
                            "kind": "currency",
                            "amount": "-3"
                        },
                        {
                            "asset": "USD",
                            "kind": "currency",
                            "amount": "0"
                        },
                        {
                            "asset": "BTC",
                            "kind": "crypto",
                            "amount": "0.000000000000000001"
                        },
                        {
                            "asset": "BTC",
                            "kind": "crypto",
                            "amount": "123456789012.123456789"
                        }
                    ]
                }
            ]
            """.trimIndent(),
            json.encodeToString(accounts.map { it.toJson() }),
        )
    }
}
