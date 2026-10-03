package com.moneymanager.database

import com.moneymanager.apiimporter.discoverApiCounterpartiesToCreate
import com.moneymanager.builtin.BuiltInApiStrategies
import com.moneymanager.database.json.ApiStrategyExportCodec
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.apistrategy.ApiAccountsSource
import com.moneymanager.domain.model.apistrategy.ApiAmountFormat
import com.moneymanager.domain.model.apistrategy.ApiEndpointKind
import com.moneymanager.domain.model.apistrategy.ApiPaging
import com.moneymanager.domain.model.apistrategy.SecretEncoding
import com.moneymanager.domain.model.apistrategy.SignatureEncoding
import com.moneymanager.domain.model.apistrategy.SigningAlgorithm
import com.moneymanager.domain.model.apistrategy.export.ApiStrategyExportMapper
import com.moneymanager.domain.model.rules.Condition
import com.moneymanager.domain.model.rules.ConditionOp
import com.moneymanager.domain.model.rules.Direction
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.installBuiltInApiStrategies
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
            assertEquals(
                setOf("Monzo", "Wise", "Starling", "Crypto.com Exchange", "Kraken", "Binance", "Coinbase", "Bybit"),
                names,
            )
        }

    @Test
    fun `an exchange strategy has no bank counterparties to suggest`() =
        runTest {
            repositories.installBuiltInApiStrategies()
            val coinbase =
                repositories.apiImportStrategyRepository
                    .getAllStrategies()
                    .first()
                    .first { it.name == "Coinbase" }

            // The Import button asks every strategy for suggestions first, so one without a bank feed must
            // answer "none" rather than fail.
            assertEquals(
                emptyList(),
                discoverApiCounterpartiesToCreate(
                    repositories.apiSessionRepository,
                    repositories.accountAttributeRepository,
                    ApiSessionId(1),
                    coinbase,
                ),
            )
        }

    @Test
    fun `the Coinbase strategy installs with its JWT-signed exchange configuration`() =
        runTest {
            repositories.installBuiltInApiStrategies()
            val coinbase =
                repositories.apiImportStrategyRepository
                    .getAllStrategies()
                    .first()
                    .first { it.name == "Coinbase" }

            assertTrue(coinbase.config.isSigned)
            val jwt = assertNotNull(coinbase.config.requestSigning?.jwt, "JWT signing recipe persisted")
            assertEquals(listOf("iss", "sub", "nbf", "exp", "uri"), jwt.claims.map { it.name })
            assertEquals("Coinbase", assertIs<ApiAccountsSource.Single>(coinbase.config.accounts).name)
            val ledger = coinbase.config.dataEndpoints.first { it.endpoint.fanOut != null }
            assertTrue(assertNotNull(ledger.endpoint.fanOut).preserveCase, "wallet ids keep their case")
            assertEquals(
                listOf("buy.id", "sell.id", "advanced_trade_fill.order_id"),
                assertNotNull(ledger.transactionMappings).reconcileTradeAmountsFallbackFields,
            )
        }

    @Test
    fun `the Coinbase strategy survives an export file round trip`() =
        runTest {
            val now = kotlin.time.Instant.fromEpochMilliseconds(1_700_000_000_000L)
            val original =
                com.moneymanager.builtin.BuiltInApiStrategies
                    .coinbase(now)
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
            assertEquals(original.config.requestSigning, rebuilt.config.requestSigning)
            assertEquals(original.config.dataEndpoints.toSet(), rebuilt.config.dataEndpoints.toSet())
            assertEquals(original.config.valueEndpoints.toSet(), rebuilt.config.valueEndpoints.toSet())
        }

    @Test
    fun `the Bybit strategy installs with its header-signed exchange configuration`() =
        runTest {
            repositories.installBuiltInApiStrategies()
            val bybit =
                repositories.apiImportStrategyRepository
                    .getAllStrategies()
                    .first()
                    .first { it.name == "Bybit" }

            assertTrue(bybit.config.isSigned)
            val signing = assertNotNull(bybit.config.requestSigning, "signing recipe persisted")
            assertEquals(mapOf("X-BAPI-RECV-WINDOW" to "20000"), signing.staticHeaders)
            assertEquals("Bybit", assertIs<ApiAccountsSource.Single>(bybit.config.accounts).name)
            val trades = bybit.config.dataEndpoints.first { it.endpoint.path == "v5/execution/list" }
            assertTrue(
                assertIs<ApiPaging.Token>(assertNotNull(trades.endpoint.pagination).paging).urlEncoded,
                "pre-encoded cursor flag persisted",
            )
            val earn = bybit.config.dataEndpoints.first { it.endpoint.path == "v5/earn/order" }
            assertIs<Direction.Field>(assertNotNull(earn.transactionMappings).direction)
            val ledgers = bybit.config.dataEndpoints.filter { it.endpoint.path == "v5/account/transaction-log" }
            assertEquals(2, ledgers.size, "linear and inverse ledgers share a path, disambiguated by category")
        }

    @Test
    fun `the Bybit strategy survives an export file round trip`() =
        runTest {
            val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)
            val original = BuiltInApiStrategies.bybit(now)
            val json = ApiStrategyExportCodec.encode(ApiStrategyExportMapper.toExport(original, "test"))
            val rebuilt = ApiStrategyExportMapper.fromExport(ApiStrategyExportCodec.decode(json), original.id, now)
            assertEquals(original.config.requestSigning, rebuilt.config.requestSigning)
            assertEquals(original.config.dataEndpoints.toSet(), rebuilt.config.dataEndpoints.toSet())
        }

    @Test
    fun `strategies that leave the new signing and paging fields at their defaults do not encode them`() =
        runTest {
            val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)
            for (strategy in BuiltInApiStrategies.builtInApiStrategies(now).filter { it.name != "Bybit" }) {
                val json = ApiStrategyExportCodec.encode(ApiStrategyExportMapper.toExport(strategy, "test"))
                assertTrue("staticHeaders" !in json, "${strategy.name} must keep its hash")
            }
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

            assertTrue(kraken.config.isSigned)
            val signing = assertNotNull(kraken.config.requestSigning, "signing recipe persisted")
            assertEquals(SigningAlgorithm.HMAC_SHA512, signing.algorithm)
            assertEquals(SecretEncoding.BASE64, signing.secretEncoding)
            assertEquals(SignatureEncoding.BASE64, signing.signatureEncoding)
            assertEquals("Kraken", assertIs<ApiAccountsSource.Single>(kraken.config.accounts).name)
            assertTrue(kraken.config.dataEndpoints.isNotEmpty(), "data endpoints persisted")
            assertTrue(
                kraken.config.assetCodes.aliases
                    .containsKey("XXBT"),
                "asset aliases persisted",
            )
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

            assertTrue(exchange.config.isSigned)
            // The generic signing recipe + single account + data endpoints survive the JSON round trip.
            assertNotNull(exchange.config.requestSigning, "signing recipe persisted")
            assertEquals("Crypto.com Exchange", assertIs<ApiAccountsSource.Single>(exchange.config.accounts).name)
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
            assertEquals(original.config.isSigned, rebuilt.config.isSigned)
            assertEquals(original.config.requestSigning, rebuilt.config.requestSigning)
            // dataEndpoints round-trips through a canonical (sorted) order - see
            // SortedDataEndpointListSerializer - so compare as sets rather than ordered lists.
            assertEquals(original.config.dataEndpoints.toSet(), rebuilt.config.dataEndpoints.toSet())
            assertEquals(original.config.accounts, rebuilt.config.accounts)
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

            assertTrue(binance.config.isSigned)
            val signing = assertNotNull(binance.config.requestSigning, "signing recipe persisted")
            assertEquals(SigningAlgorithm.HMAC_SHA256, signing.algorithm)
            assertEquals("Binance", assertIs<ApiAccountsSource.Single>(binance.config.accounts).name)
            assertTrue(binance.config.valueEndpoints.isNotEmpty(), "value endpoints persisted")
            val myTrades = binance.config.dataEndpoints.first { it.endpoint.path == "api/v3/myTrades" }
            assertNotNull(myTrades.endpoint.fanOut, "spot-trade fan-out persisted")
            val fiatOrders = binance.config.dataEndpoints.filter { it.endpoint.path == "sapi/v1/fiat/orders" }
            assertEquals(2, fiatOrders.size, "fiat deposit and withdrawal endpoints share a path, disambiguated by transactionType")

            // Without these, a full first download fails endpoints with "-1021 Timestamp for this
            // request is outside of the recvWindow": Binance's default tolerance is 5s, and an
            // NTP-synced machine can still sit a second away from Binance's own clock.
            assertEquals(
                "60000",
                signing.signedParams.single { it.name == "recvWindow" }.value,
                "recvWindow is widened from Binance's 5s default to its 60s maximum",
            )
            val timeSync = assertNotNull(signing.serverTimeSync, "server-time sync persisted")
            assertEquals("api/v3/time", timeSync.path)
            assertEquals("serverTime", timeSync.field)
        }

    @Test
    fun `the Binance strategy survives an export file round trip`() =
        runTest {
            val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)
            val original = BuiltInApiStrategies.binance(now)
            val json = ApiStrategyExportCodec.encode(ApiStrategyExportMapper.toExport(original, "test"))
            val rebuilt = ApiStrategyExportMapper.fromExport(ApiStrategyExportCodec.decode(json), original.id, now)
            assertEquals(original.config.isSigned, rebuilt.config.isSigned)
            assertEquals(original.config.requestSigning, rebuilt.config.requestSigning)
            assertEquals(original.config.accounts, rebuilt.config.accounts)
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
            val starlingAccounts = assertIs<ApiAccountsSource.Downloaded>(starling.config.accounts)
            assertEquals("/api/v2/accounts", starlingAccounts.endpoint.path)
            assertEquals("accounts", starlingAccounts.endpoint.responseArrayKey)
            assertEquals(
                "/api/v2/feed/account/{account.id}/category/{account.defaultCategory}",
                checkNotNull(starling.config.bankTransactions).endpoint.path,
            )
            assertEquals("feedItems", checkNotNull(starling.config.bankTransactions).endpoint.responseArrayKey)
            // Full history is returned in one response, so no pagination is configured.
            assertEquals(null, checkNotNull(starling.config.bankTransactions).endpoint.pagination)

            assertEquals("accountUid", starlingAccounts.mappings.idField)
            assertEquals("currency", starlingAccounts.mappings.currencyField)
            // Own bank details come from the per-account identifiers endpoint, not the /accounts response.
            assertEquals("bankIdentifier", starlingAccounts.mappings.sortCodeField)
            assertEquals("accountIdentifier", starlingAccounts.mappings.accountNumberField)
            assertEquals(
                "/api/v2/accounts/{account.id}/identifiers",
                assertNotNull(starlingAccounts.identifiersEndpoint, "Starling should configure an identifiers endpoint").path,
            )

            with(checkNotNull(starling.config.bankTransactions?.transactionMappings)) {
                assertEquals("amount.minorUnits", amountField)
                assertEquals(ApiAmountFormat.MINOR_UNITS_INTEGER, amountFormat)
                assertEquals(Direction.Field(path = "direction", incomingValues = setOf("IN")), direction)
                assertEquals("feedItemUid", idField)
                assertEquals(listOf(Condition("status", ConditionOp.IN, value = "DECLINED")), declinedWhen)
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
