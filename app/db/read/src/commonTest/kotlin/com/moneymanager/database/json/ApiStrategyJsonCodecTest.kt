package com.moneymanager.database.json

import com.moneymanager.domain.model.apistrategy.ApiAccountMappings
import com.moneymanager.domain.model.apistrategy.ApiAuthType
import com.moneymanager.domain.model.apistrategy.ApiEndpointConfig
import com.moneymanager.domain.model.apistrategy.ApiPaginationConfig
import com.moneymanager.domain.model.apistrategy.ApiQueryParam
import com.moneymanager.domain.model.apistrategy.ApiTransactionMappings
import com.moneymanager.domain.model.apistrategy.PaginationMode
import kotlin.test.Test
import kotlin.test.assertEquals

class ApiStrategyJsonCodecTest {
    private fun config(pagination: ApiPaginationConfig?) =
        ApiStrategyConfigJson(
            baseUrl = "https://example.com",
            authType = ApiAuthType.BEARER_TOKEN,
            accountsEndpoint = ApiEndpointConfig(path = "/accounts", responseArrayKey = "accounts"),
            transactionsEndpoint =
                ApiEndpointConfig(
                    path = "/transactions",
                    responseArrayKey = "transactions",
                    queryParams = listOf(ApiQueryParam(name = "account_id", dynamicSource = "account.id")),
                    pagination = pagination,
                ),
            accountMappings = ApiAccountMappings(),
            transactionMappings = ApiTransactionMappings(),
        )

    @Test
    fun `cursor pagination round-trips`() {
        val original = config(ApiPaginationConfig())
        val decoded = ApiStrategyJsonCodec.decode(ApiStrategyJsonCodec.encode(original))
        assertEquals(original, decoded)
        assertEquals(PaginationMode.CURSOR, decoded.transactionsEndpoint.pagination?.mode)
    }

    @Test
    fun `date-window pagination round-trips`() {
        val original =
            config(
                ApiPaginationConfig(
                    mode = PaginationMode.DATE_WINDOW,
                    extraParams = listOf(ApiQueryParam(name = "type", value = "FLAT")),
                ),
            )
        val decoded = ApiStrategyJsonCodec.decode(ApiStrategyJsonCodec.encode(original))
        assertEquals(original, decoded)
        assertEquals(PaginationMode.DATE_WINDOW, decoded.transactionsEndpoint.pagination?.mode)
    }

    @Test
    fun `legacy pagination without a mode discriminator decodes as cursor`() {
        // Pagination JSON persisted before the `mode` field existed: a flat cursor object.
        val legacyJson =
            """
            {
              "baseUrl": "https://example.com",
              "authType": "BEARER_TOKEN",
              "accountsEndpoint": { "path": "/accounts", "responseArrayKey": "accounts" },
              "transactionsEndpoint": {
                "path": "/transactions",
                "responseArrayKey": "transactions",
                "queryParams": [ { "name": "account_id", "dynamicSource": "account.id" } ],
                "pagination": { "limitParam": "limit", "limitValue": 100, "cursorParam": "before", "cursorResponseField": "created" }
              },
              "accountMappings": {},
              "transactionMappings": {}
            }
            """.trimIndent()

        val decoded = ApiStrategyJsonCodec.decode(legacyJson)
        val pagination = decoded.transactionsEndpoint.pagination
        assertEquals(PaginationMode.CURSOR, pagination?.mode)
        assertEquals("before", pagination?.cursorParam)
        assertEquals("created", pagination?.cursorResponseField)
        assertEquals(100, pagination?.limitValue)
    }
}
