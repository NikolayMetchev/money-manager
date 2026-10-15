package com.moneymanager.test.database

/**
 * Monzo API responses for tests that drive the built-in Monzo strategy against a mocked provider: one
 * account and one card payment. Kotlin constants, since native tests have no classpath resources.
 */
object MonzoApiFixtures {
    const val ACCOUNT_ID = "acc_00009TEST000000000001"

    val ACCOUNTS_JSON =
        """
        {"accounts": [{"id": "$ACCOUNT_ID", "closed": false, "created": "2022-01-01T00:00:00.000Z",
          "description": "user_00009TEST000000user", "type": "uk_retail", "currency": "GBP"}]}
        """.trimIndent()

    val TRANSACTIONS_JSON =
        """
        {"transactions": [
          {"id": "tx_1", "account_id": "$ACCOUNT_ID", "created": "2024-06-03T09:15:00.000Z", "amount": -1250,
           "currency": "GBP", "description": "COFFEE SHOP LTD", "merchant": {"name": "Coffee Shop Ltd"}, "counterparty": {}}
        ]}
        """.trimIndent()

    const val EMPTY_TRANSACTIONS_JSON = """{"transactions": []}"""
}
