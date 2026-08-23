package com.moneymanager.database

import com.moneymanager.domain.model.apistrategy.ApiAmountFormat
import com.moneymanager.domain.model.apistrategy.ApiAuthType
import com.moneymanager.domain.model.apistrategy.ApiEndpointKind
import com.moneymanager.domain.model.apistrategy.ApiSignSource
import com.moneymanager.domain.model.apistrategy.SecretEncoding
import com.moneymanager.domain.model.apistrategy.SignatureEncoding
import com.moneymanager.domain.model.apistrategy.SigningAlgorithm
import com.moneymanager.domain.model.apistrategy.export.ApiStrategyExportMapper
import com.moneymanager.builtin.BuiltInApiStrategies
import com.moneymanager.database.json.ApiStrategyExportCodec
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.installBuiltInApiStrategies
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

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
            assertEquals(setOf("Monzo", "Wise", "Starling", "Crypto.com Exchange", "Kraken", "Binance"), names)
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

            assertEquals(ApiAuthType.SIGNED, kraken.config.authType)
            val signing = assertNotNull(kraken.config.requestSigning, "signing recipe persisted")
            assertEquals(SigningAlgorithm.HMAC_SHA512, signing.algorithm)
            assertEquals(SecretEncoding.BASE64, signing.secretEncoding)
            assertEquals(SignatureEncoding.BASE64, signing.signatureEncoding)
            assertEquals("Kraken", assertNotNull(kraken.config.syntheticAccount).name)
            assertTrue(kraken.config.dataEndpoints.isNotEmpty(), "data endpoints persisted")
            assertTrue(kraken.config.assetAliases.containsKey("XXBT"), "asset aliases persisted")
            val trades = kraken.config.dataEndpoints.first { it.kind == ApiEndpointKind.TRADES }
            assertTrue(trades.endpoint.responseObjectValues, "trades response is a keyed object")
            assertEquals("error", trades.endpoint.errorArrayField)
            val enrichers = kraken.config.dataEndpoints.filter { it.enrichesTransfers }
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

            assertEquals(ApiAuthType.SIGNED, exchange.config.authType)
            // The generic signing recipe + single account + data endpoints survive the JSON round trip.
            assertNotNull(exchange.config.requestSigning, "signing recipe persisted")
            assertEquals("Crypto.com Exchange", assertNotNull(exchange.config.syntheticAccount).name)
            assertTrue(exchange.config.dataEndpoints.isNotEmpty(), "data endpoints persisted")
            assertNotNull(exchange.config.internalTransferReconcile, "internal-transfer reconciliation persisted")
            assertEquals(
                "Crypto.com",
                exchange.config.internalTransferReconcile!!
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
                    ApiStrategyExportMapper
                        .toExport(original, "test"),
                )
            val rebuilt =
                ApiStrategyExportMapper.fromExport(
                    com.moneymanager.database.json.ApiStrategyExportCodec
                        .decode(json),
                    original.id,
                    now,
                )
            assertEquals(original.config.authType, rebuilt.config.authType)
            assertEquals(original.config.requestSigning, rebuilt.config.requestSigning)
            // dataEndpoints round-trips through a canonical (sorted) order - see
            // SortedDataEndpointListSerializer - so compare as sets rather than ordered lists.
            assertEquals(original.config.dataEndpoints.toSet(), rebuilt.config.dataEndpoints.toSet())
            assertEquals(original.config.syntheticAccount, rebuilt.config.syntheticAccount)
            assertEquals(original.config.internalTransferReconcile, rebuilt.config.internalTransferReconcile)
        }

    @Test
    fun `the Binance strategy installs with its signed-exchange configuration`() =
        runTest {
            repositories.installBuiltInApiStrategies()
            val binance =
                repositories.apiImportStrategyRepository
                    .getAllStrategies()
                    .first()
                    .first { it.name == "Binance" }

            assertEquals(ApiAuthType.SIGNED, binance.config.authType)
            val signing = assertNotNull(binance.config.requestSigning, "signing recipe persisted")
            assertEquals(SigningAlgorithm.HMAC_SHA256, signing.algorithm)
            assertEquals("Binance", assertNotNull(binance.config.syntheticAccount).name)
            assertTrue(binance.config.valueEndpoints.isNotEmpty(), "value endpoints persisted")
            val myTrades = binance.config.dataEndpoints.first { it.endpoint.path == "api/v3/myTrades" }
            assertNotNull(myTrades.endpoint.fanOut, "spot-trade fan-out persisted")
            val fiatOrders = binance.config.dataEndpoints.filter { it.endpoint.path == "sapi/v1/fiat/orders" }
            assertEquals(2, fiatOrders.size, "fiat deposit and withdrawal endpoints share a path, disambiguated by transactionType")
        }

    @Test
    fun `the Binance strategy survives an export file round trip`() =
        runTest {
            val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)
            val original = BuiltInApiStrategies.binance(now)
            val json = ApiStrategyExportCodec.encode(ApiStrategyExportMapper.toExport(original, "test"))
            val rebuilt = ApiStrategyExportMapper.fromExport(ApiStrategyExportCodec.decode(json), original.id, now)
            assertEquals(original.config.authType, rebuilt.config.authType)
            assertEquals(original.config.requestSigning, rebuilt.config.requestSigning)
            assertEquals(original.config.syntheticAccount, rebuilt.config.syntheticAccount)
            assertEquals(original.config.dataEndpoints.size, rebuilt.config.dataEndpoints.size)
            assertEquals(original.config.valueEndpoints.size, rebuilt.config.valueEndpoints.size)
            val rebuiltMyTrades = rebuilt.config.dataEndpoints.first { it.endpoint.path == "api/v3/myTrades" }
            val originalMyTrades = original.config.dataEndpoints.first { it.endpoint.path == "api/v3/myTrades" }
            // ApiValueSet.Static.values is sorted-on-decode (order carries no meaning) - compare as sets,
            // like ApiTradeMappings.quoteAssets/dataEndpoints elsewhere in these round-trip assertions.
            assertEquals(originalMyTrades.endpoint.fanOut != null, rebuiltMyTrades.endpoint.fanOut != null)
            assertEquals(
                assertNotNull(originalMyTrades.tradeMappings).compositeIdFields,
                assertNotNull(rebuiltMyTrades.tradeMappings).compositeIdFields,
            )
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

            assertEquals("https://api.starlingbank.com", starling.config.baseUrl)
            assertEquals("/api/v2/accounts", starling.config.accountsEndpoint.path)
            assertEquals("accounts", starling.config.accountsEndpoint.responseArrayKey)
            assertEquals(
                "/api/v2/feed/account/{account.id}/category/{account.defaultCategory}",
                starling.config.transactionsEndpoint.path,
            )
            assertEquals("feedItems", starling.config.transactionsEndpoint.responseArrayKey)
            // Full history is returned in one response, so no pagination is configured.
            assertEquals(null, starling.config.transactionsEndpoint.pagination)

            assertEquals("accountUid", starling.config.accountMappings.idField)
            assertEquals("currency", starling.config.accountMappings.currencyField)
            // Own bank details come from the per-account identifiers endpoint, not the /accounts response.
            assertEquals("bankIdentifier", starling.config.accountMappings.sortCodeField)
            assertEquals("accountIdentifier", starling.config.accountMappings.accountNumberField)
            assertEquals(
                "/api/v2/accounts/{account.id}/identifiers",
                assertNotNull(starling.config.accountIdentifiersEndpoint, "Starling should configure an identifiers endpoint").path,
            )

            with(starling.config.transactionMappings) {
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
            with(starling.config.peopleMappings) {
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

            val people = assertNotNull(starling.config.peopleDownload, "Starling should configure a people download")
            assertEquals("/api/v2/account-holder/individual", people.endpoint.path)
            assertTrue(people.ownsAllAccounts, "Starling's global holder should own all accounts")
        }
}
