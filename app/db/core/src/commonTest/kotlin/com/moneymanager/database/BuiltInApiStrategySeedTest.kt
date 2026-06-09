package com.moneymanager.database

import com.moneymanager.domain.model.apistrategy.ApiAmountFormat
import com.moneymanager.domain.model.apistrategy.ApiSignSource
import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BuiltInApiStrategySeedTest : DbTest() {
    @Test
    fun `a fresh database seeds the built-in API strategies`() =
        runTest {
            val names =
                repositories.apiImportStrategyRepository
                    .getAllStrategies()
                    .first()
                    .map { it.name }
                    .toSet()
            assertEquals(setOf("Monzo", "Wise", "Starling"), names)
        }

    @Test
    fun `the Starling strategy seeds with its expected configuration`() =
        runTest {
            val starling =
                repositories.apiImportStrategyRepository
                    .getAllStrategies()
                    .first()
                    .first { it.name == "Starling" }

            assertEquals("https://api.starlingbank.com", starling.baseUrl)
            assertEquals("/api/v2/accounts", starling.accountsEndpoint.path)
            assertEquals("accounts", starling.accountsEndpoint.responseArrayKey)
            assertEquals(
                "/api/v2/feed/account/{account.id}/category/{account.defaultCategory}",
                starling.transactionsEndpoint.path,
            )
            assertEquals("feedItems", starling.transactionsEndpoint.responseArrayKey)
            // Full history is returned in one response, so no pagination is configured.
            assertEquals(null, starling.transactionsEndpoint.pagination)

            assertEquals("accountUid", starling.accountMappings.idField)
            assertEquals("currency", starling.accountMappings.currencyField)

            with(starling.transactionMappings) {
                assertEquals("amount.minorUnits", amountField)
                assertEquals(ApiAmountFormat.MINOR_UNITS_INTEGER, amountFormat)
                assertEquals(ApiSignSource.FIELD, signSource)
                assertEquals("direction", signField)
                assertEquals(setOf("IN"), creditValues)
                assertEquals("feedItemUid", idField)
                assertEquals("status", declineStatusField)
                assertEquals(setOf("DECLINED"), declinedStatusValues)
                assertEquals("counterPartyUid", counterpartyIdField)
                assertEquals(mapOf("starling-transaction-id" to "feedItemUid"), customFields)
                assertEquals(setOf("starling-transaction-id"), uniqueIdentifierFields)
            }

            // PAYEE/SENDER counterparties are treated as people, read from flat feed-item fields.
            with(starling.peopleMappings) {
                assertEquals("", counterpartyObjectField)
                assertEquals("counterPartyType", beneficiaryAccountTypeField)
                assertEquals(setOf("PAYEE", "SENDER"), personalBeneficiaryAccountTypeValues)
                assertEquals("counterPartyName", counterpartyNameField)
                assertEquals("counterPartyUid", counterpartyUserIdField)
            }

            val people = assertNotNull(starling.peopleDownload, "Starling should configure a people download")
            assertEquals("/api/v2/account-holder/individual", people.endpoint.path)
            assertTrue(people.ownsAllAccounts, "Starling's global holder should own all accounts")
        }
}
