package com.moneymanager.database.service

import com.moneymanager.database.json.CurrencyListExportCodec
import com.moneymanager.database.json.HolidayCalendarExportCodec
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.ApiImportStrategyId
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.CurrencyExportEntry
import com.moneymanager.domain.model.CurrencyListExport
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.apistrategy.ApiAccountMappings
import com.moneymanager.domain.model.apistrategy.ApiAccountsSource
import com.moneymanager.domain.model.apistrategy.ApiDataEndpoint
import com.moneymanager.domain.model.apistrategy.ApiEndpointConfig
import com.moneymanager.domain.model.apistrategy.ApiEndpointKind
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.model.apistrategy.ApiStrategyConfig
import com.moneymanager.domain.model.apistrategy.ApiTransactionMappings
import com.moneymanager.domain.model.calendar.HolidayCalendarExport
import com.moneymanager.domain.model.calendar.testing.TestHolidayCalendars
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvStrategyConfig
import com.moneymanager.domain.model.csvstrategy.PdfLineConfig
import com.moneymanager.domain.model.qif.QifColumns
import com.moneymanager.domain.model.rules.CaptureTarget
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.StrategyRule
import com.moneymanager.domain.strategy.StrategyFileNaming
import com.moneymanager.domain.strategy.StrategyKey
import com.moneymanager.domain.strategy.StrategyKind
import com.moneymanager.importengineapi.createAccountMapping
import com.moneymanager.importengineapi.createApiStrategy
import com.moneymanager.importengineapi.createCsvStrategy
import com.moneymanager.importengineapi.createCurrency
import com.moneymanager.importengineapi.deleteCsvStrategy
import com.moneymanager.regex.compileRegex
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.installBuiltInPassThroughs
import com.moneymanager.test.database.testCurrencies
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

class StrategyLibraryServiceTest : DbTest() {
    private val version = AppVersion("test")

    @Test
    fun `csv strategy round-trips with its per-strategy account mapping - keyed by name - no duplicates`() =
        runDbTest {
            val library = repositories.strategyLibrary
            val engine = repositories.importEngine
            val csvRepo = repositories.csvImportStrategyRepository
            val mappingRepo = repositories.accountMappingRepository
            val now = Clock.System.now()

            val account = Account(AccountId(0), name = "Paxos", openingDate = now)
            val accountId = repositories.accountRepository.createAccount(account, Source.Manual)
            val strategy =
                CsvImportStrategy(
                    id = CsvImportStrategyId(Uuid.random()),
                    name = "WiseTest",
                    config =
                        CsvStrategyConfig(
                            identificationColumns = setOf("Amount", "Description"),
                            fieldMappings = emptyMap(),
                        ),
                    createdAt = now,
                    updatedAt = now,
                )
            engine.createCsvStrategy(strategy)
            engine.createAccountMapping(compileRegex("PAXOS.*", RegexOption.IGNORE_CASE), accountId, strategy.id)

            val key = StrategyKey(StrategyKind.CSV, "WiseTest")
            val entry = library.listLocal(version).first { it.key == key }
            assertTrue(entry.json.contains("Paxos"), "exported CSV strategy should embed its per-strategy mapping")
            val unresolved = library.parseIncoming(key, entry.json).unresolvedReferences
            assertTrue(unresolved.isEmpty(), "account exists, so no unresolved refs")

            // Deleting the strategy cascades its per-strategy mappings; re-applying recreates both.
            engine.deleteCsvStrategy(strategy.id)
            library.applyIncoming(key, entry.json, emptyMap())
            val recreated = csvRepo.getStrategyByName("WiseTest").first()!!
            val mappings = mappingRepo.getAllMappings().first().filter { it.strategyId == recreated.id }
            assertEquals(1, mappings.size)
            assertEquals("PAXOS.*", mappings.single().valuePattern.pattern)

            // Applying the same artifact again updates in place (keyed by name) — never a duplicate.
            library.applyIncoming(key, entry.json, emptyMap())
            val named = csvRepo.getAllStrategies().first().count { it.name == "WiseTest" }
            assertEquals(1, named)
            val afterReapply = mappingRepo.getAllMappings().first().filter { it.strategyId == recreated.id }
            assertEquals(1, afterReapply.size, "re-applying replaces the per-strategy mappings, not accumulates")
        }

    @Test
    fun `a QIF strategy is classified as QIF - a plain one as CSV`() =
        runDbTest {
            val engine = repositories.importEngine
            val now = Clock.System.now()
            engine.createCsvStrategy(
                CsvImportStrategy(
                    id = CsvImportStrategyId(Uuid.random()),
                    name = "MyQif",
                    config =
                        CsvStrategyConfig(
                            identificationColumns = QifColumns.headers.toSet(),
                            fieldMappings = emptyMap(),
                        ),
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            val kinds = repositories.strategyLibrary.listLocal(version).associate { it.key.name to it.key.kind }
            assertEquals(StrategyKind.QIF, kinds["MyQif"])
        }

    @Test
    fun `a PDF strategy is classified as PDF and round-trips its line rules in order`() =
        runDbTest {
            val engine = repositories.importEngine
            val library = repositories.strategyLibrary
            val now = Clock.System.now()
            // Out of alphabetical order on purpose: line rules are first-claim-wins, so export must never sort them.
            val lineRules =
                listOf(
                    StrategyRule(
                        name = "Transaction",
                        actions = listOf(RuleAction.Capture(PdfLineConfig.TEXT, "^(?<date>\\w{3} \\d+) ", CaptureTarget.NEW_ROW)),
                    ),
                    StrategyRule(
                        name = "Statement date",
                        actions = listOf(RuleAction.Capture(PdfLineConfig.TEXT, "Date (?<statementDate>\\S+)", CaptureTarget.DOCUMENT)),
                    ),
                )
            engine.createCsvStrategy(
                CsvImportStrategy(
                    id = CsvImportStrategyId(Uuid.random()),
                    name = "MyPdf",
                    config =
                        CsvStrategyConfig(
                            identificationColumns = PdfLineConfig.BASE_COLUMNS.toSet(),
                            fieldMappings = emptyMap(),
                            pdfLines = PdfLineConfig(lineRules),
                        ),
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            val key = StrategyKey(StrategyKind.PDF, "MyPdf")
            val entry = library.listLocal(version).single { it.key.name == "MyPdf" }
            assertEquals(key, entry.key)
            assertEquals("MyPdf.pdf.json", StrategyFileNaming.fileName(key))
            assertEquals(key, StrategyFileNaming.parse(StrategyFileNaming.fileName(key)))

            library.applyIncoming(key, entry.json, emptyMap())
            val reapplied = repositories.csvImportStrategyRepository.getStrategyByName("MyPdf").first()!!
            assertEquals(lineRules, reapplied.config.pdfLines?.rules)
        }

    @Test
    fun `api strategy round-trips and re-applies by name without duplicating`() =
        runDbTest {
            val library = repositories.strategyLibrary
            val engine = repositories.importEngine
            val now = Clock.System.now()
            engine.createApiStrategy(
                ApiImportStrategy(
                    id = ApiImportStrategyId(Uuid.random()),
                    name = "MonzoTest",
                    config =
                        ApiStrategyConfig(
                            baseUrl = "https://api.monzo.com",
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
                                        transactionMappings = ApiTransactionMappings(),
                                    ),
                                ),
                        ),
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            val key = StrategyKey(StrategyKind.API, "MonzoTest")
            val entry = library.listLocal(version).first { it.key == key }
            assertTrue(entry.json.contains("api.monzo.com"))

            // The published shape is flat - config fields sit beside version/name, never nested under
            // a "config" key. Every already-published catalog file and synced Drive library uses it,
            // and nesting would change every canonicalHash, so this is a compatibility guarantee.
            assertTrue(entry.json.contains("\"baseUrl\""), "baseUrl must stay a top-level key")
            assertFalse(entry.json.contains("\"config\""), "the config must not be nested in the export")
            assertEquals(entry.contentHash, library.canonicalHash(key, entry.json))

            library.applyIncoming(key, entry.json, emptyMap())
            val apiStrategies = repositories.apiImportStrategyRepository.getAllStrategies().first()
            assertEquals(1, apiStrategies.count { it.name == "MonzoTest" })
        }

    @Test
    fun `pass-through definition round-trips and re-applies by name without duplicating`() =
        runDbTest {
            val library = repositories.strategyLibrary
            // The installed Curve definition is listed as its own artifact.
            repositories.installBuiltInPassThroughs()
            val key = StrategyKey(StrategyKind.PASS_THROUGH, "Curve")
            val entry = library.listLocal(version).first { it.key == key }
            assertTrue(entry.json.contains("CRV"))

            // Applying it back updates in place (never duplicates), preserving the definition.
            library.applyIncoming(key, entry.json, emptyMap())
            val definitions = repositories.passThroughAccountRepository.getAll().first()
            assertEquals(1, definitions.count { it.name == "Curve" })
            val curve = definitions.first { it.name == "Curve" }
            assertEquals("Curve", curve.conduitAccountName)
            assertTrue(curve.rules.isNotEmpty())

            // The artifact hash ignores the version stamp, and the filename round-trips.
            assertEquals(entry.contentHash, library.canonicalHash(key, entry.json))
            assertEquals(key, StrategyFileNaming.parse(StrategyFileNaming.fileName(key)))
        }

    @Test
    fun `currency list lists seeded currencies - skips user-created ones - and installs only missing codes`() =
        runDbTest {
            val library = repositories.strategyLibrary
            val engine = repositories.importEngine
            val key = StrategyKey(StrategyKind.CURRENCIES, StrategyFileNaming.CURRENCIES_NAME)
            engine.createCurrency("ZZZ", "Hand-made", null, Source.Manual)

            val entry = library.listLocal(version).first { it.key == key }
            val listed = CurrencyListExportCodec.decode(entry.json).currencies.map { it.code }
            // The test seed (GBP, USD, EUR); the user's own currency isn't part of the list.
            assertEquals(listOf("EUR", "GBP", "USD"), listed)

            val published =
                CurrencyListExport(
                    name = StrategyFileNaming.CURRENCIES_NAME,
                    currencies =
                        listOf(
                            CurrencyExportEntry("GBP", "Renamed Pound"),
                            CurrencyExportEntry("JPY", "Japanese Yen"),
                            CurrencyExportEntry("ZZZ", "Published name"),
                        ),
                )
            library.applyIncoming(key, CurrencyListExportCodec.encode(published), emptyMap())
            library.applyIncoming(key, CurrencyListExportCodec.encode(published), emptyMap())

            val currencies =
                repositories.currencyRepository
                    .getAllCurrencies()
                    .first()
                    .associate { it.code to it.name }
            assertEquals("Japanese Yen", currencies["JPY"], "a missing code is added")
            assertEquals("British Pound", currencies["GBP"], "an existing currency is never renamed")
            assertEquals("Hand-made", currencies["ZZZ"], "a user-created currency is left alone")
            assertEquals(
                listOf("EUR", "GBP", "JPY", "USD"),
                library.listLocal(version).first { it.key == key }.let {
                    CurrencyListExportCodec.decode(it.json).currencies.map { c -> c.code }
                },
            )
        }

    @Test
    fun `a database holding the published list hashes as it`() =
        runDbTest {
            val key = StrategyKey(StrategyKind.CURRENCIES, StrategyFileNaming.CURRENCIES_NAME)
            val published = CurrencyListExport(name = StrategyFileNaming.CURRENCIES_NAME, currencies = testCurrencies.reversed())
            val local = repositories.strategyLibrary.listLocal(version).first { it.key == key }
            assertEquals(local.contentHash, repositories.strategyLibrary.canonicalHash(key, CurrencyListExportCodec.encode(published)))
        }

    @Test
    fun `a published location replaces the database's copy and round-trips`() =
        runDbTest {
            val library = repositories.strategyLibrary
            val key = StrategyKey(StrategyKind.HOLIDAY_CALENDAR, "GBLO")
            library.applyIncoming(
                key,
                HolidayCalendarExportCodec.encode(HolidayCalendarExport(calendar = TestHolidayCalendars.LONDON)),
                emptyMap(),
            )
            val local = library.listLocal(version).first { it.key == key }
            val seeded = HolidayCalendarExportCodec.decode(local.json).calendar
            assertEquals(TestHolidayCalendars.LONDON, seeded)

            val announced = LocalDate(2027, 6, 7)
            val published = HolidayCalendarExport(calendar = seeded.copy(holidays = seeded.holidays + announced))
            library.applyIncoming(key, HolidayCalendarExportCodec.encode(published), emptyMap())

            val installed =
                repositories.holidayCalendarRepository
                    .getAll()
                    .first()
                    .first { it.id == "GBLO" }
            assertTrue(announced in installed.holidays, "the published holiday is installed")
            assertEquals(
                library.listLocal(version).first { it.key == key }.contentHash,
                library.canonicalHash(key, HolidayCalendarExportCodec.encode(published)),
                "the installed copy hashes as the published one, so the catalog shows it up to date",
            )
        }

    @Test
    fun `location files are named by their code`() {
        val key = StrategyKey(StrategyKind.HOLIDAY_CALENDAR, "GBLO")
        assertEquals(expected = "GBLO.calendar.json", actual = StrategyFileNaming.fileName(key))
        assertEquals(expected = key, actual = StrategyFileNaming.parse("GBLO.calendar.json"))
    }
}
