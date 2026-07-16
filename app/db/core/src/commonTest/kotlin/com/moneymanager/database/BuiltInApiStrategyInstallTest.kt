package com.moneymanager.database

import com.moneymanager.domain.model.apistrategy.ApiAmountFormat
import com.moneymanager.domain.model.apistrategy.ApiSignSource
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.installBuiltInApiStrategies
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Built-in strategies are no longer seeded; installing them through the engine (the same
// path a catalog install takes) must survive the JSON round trip through the database.
class BuiltInApiStrategyInstallTest : DbTest() {
    @Test
    fun `installing the built-in API strategies creates all of them`() =
        runTest {
            repositories.installBuiltInApiStrategies()
            val names =
                repositories.apiImportStrategyRepository
                    .getAllStrategies()
                    .first()
                    .map { it.name }
                    .toSet()
            assertEquals(setOf("Monzo", "Wise", "Starling", "Crypto.com Exchange", "Kraken"), names)
        }

    @Test
    fun `the Kraken strategy installs with its signed-exchange configuration`() =
        runTest {
            repositories.installBuiltInApiStrategies()
            val kraken =
                repositories.apiImportStrategyRepository
                    .getAllStrategies()
                    .first()
                    .first { it.name == "Kraken" }

            assertEquals(com.moneymanager.domain.model.apistrategy.ApiAuthType.SIGNED, kraken.authType)
            val signing = assertNotNull(kraken.requestSigning, "signing recipe persisted")
            assertEquals(com.moneymanager.domain.model.apistrategy.SigningAlgorithm.HMAC_SHA512, signing.algorithm)
            assertEquals(com.moneymanager.domain.model.apistrategy.SecretEncoding.BASE64, signing.secretEncoding)
            assertEquals(com.moneymanager.domain.model.apistrategy.SignatureEncoding.BASE64, signing.signatureEncoding)
            assertEquals("Kraken", assertNotNull(kraken.syntheticAccount).name)
            assertTrue(kraken.dataEndpoints.isNotEmpty(), "data endpoints persisted")
            assertTrue(kraken.assetAliases.containsKey("XXBT"), "asset aliases persisted")
            val trades = kraken.dataEndpoints.first { it.kind == com.moneymanager.domain.model.apistrategy.ApiEndpointKind.TRADES }
            assertTrue(trades.endpoint.responseObjectValues, "trades response is a keyed object")
            assertEquals("error", trades.endpoint.errorArrayField)
            val enrichers = kraken.dataEndpoints.filter { it.enrichesTransfers }
            assertTrue(enrichers.isNotEmpty(), "at least one enrichment-only endpoint persisted")
        }

    @Test
    fun `the Crypto_com Exchange strategy installs with its signed-exchange configuration`() =
        runTest {
            repositories.installBuiltInApiStrategies()
            val exchange =
                repositories.apiImportStrategyRepository
                    .getAllStrategies()
                    .first()
                    .first { it.name == "Crypto.com Exchange" }

            assertEquals(com.moneymanager.domain.model.apistrategy.ApiAuthType.SIGNED, exchange.authType)
            // The generic signing recipe + single account + data endpoints survive the JSON round trip.
            assertNotNull(exchange.requestSigning, "signing recipe persisted")
            assertEquals("Crypto.com Exchange", assertNotNull(exchange.syntheticAccount).name)
            assertTrue(exchange.dataEndpoints.isNotEmpty(), "data endpoints persisted")
            assertNotNull(exchange.internalTransferReconcile, "internal-transfer reconciliation persisted")
            assertEquals(
                "Crypto.com",
                exchange.internalTransferReconcile!!
                    .bridges
                    .single()
                    .otherAccountName,
            )
        }

    @Test
    fun `the Crypto_com Exchange strategy survives an export file round trip`() =
        runTest {
            // The distribution format used by the catalog and by the API-strategies "Import file" button:
            // toExport -> JSON encode/decode -> fromExport must reproduce the full signed-exchange config.
            val now = kotlin.time.Instant.fromEpochMilliseconds(1_700_000_000_000L)
            val original =
                com.moneymanager.builtin.BuiltInApiStrategies
                    .cryptoComExchange(now)
            val json =
                com.moneymanager.database.json.ApiStrategyExportCodec.encode(
                    com.moneymanager.domain.model.apistrategy.export.ApiStrategyExportMapper
                        .toExport(original, "test"),
                )
            val rebuilt =
                com.moneymanager.domain.model.apistrategy.export.ApiStrategyExportMapper.fromExport(
                    com.moneymanager.database.json.ApiStrategyExportCodec
                        .decode(json),
                    original.id,
                    now,
                )
            assertEquals(original.authType, rebuilt.authType)
            assertEquals(original.requestSigning, rebuilt.requestSigning)
            assertEquals(original.dataEndpoints, rebuilt.dataEndpoints)
            assertEquals(original.syntheticAccount, rebuilt.syntheticAccount)
            assertEquals(original.internalTransferReconcile, rebuilt.internalTransferReconcile)
        }

    @Test
    fun `the Starling strategy installs with its expected configuration`() =
        runTest {
            repositories.installBuiltInApiStrategies()
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
            // Own bank details come from the per-account identifiers endpoint, not the /accounts response.
            assertEquals("bankIdentifier", starling.accountMappings.sortCodeField)
            assertEquals("accountIdentifier", starling.accountMappings.accountNumberField)
            assertEquals(
                "/api/v2/accounts/{account.id}/identifiers",
                assertNotNull(starling.accountIdentifiersEndpoint, "Starling should configure an identifiers endpoint").path,
            )

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
                assertEquals("counterPartySubEntityIdentifier", counterpartySortCodeField)
                assertEquals("counterPartySubEntitySubIdentifier", counterpartyAccountNumberField)
                // Bank details (sub-entity) identify the counterparty account ahead of the uid.
                assertTrue(preferBankIdentity)
            }

            val people = assertNotNull(starling.peopleDownload, "Starling should configure a people download")
            assertEquals("/api/v2/account-holder/individual", people.endpoint.path)
            assertTrue(people.ownsAllAccounts, "Starling's global holder should own all accounts")
        }
}
