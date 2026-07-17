@file:OptIn(ExperimentalUuidApi::class, ExperimentalTime::class)

package com.moneymanager.database.repository

import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.QifImportId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.timeline.TimelineSourceKind
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.createAccount
import com.moneymanager.test.database.upsertCurrencyByCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class ImportTimelineRepositoryImplTest : DbTest() {
    private val headers = listOf("Date", "Amount", "Description")
    private val rows = listOf(listOf("2024-01-01", "100.00", "row"))
    private val fileLastModified = Instant.fromEpochMilliseconds(1700000000000L)
    private val appliedAt = Instant.fromEpochMilliseconds(1701000000000L)
    private val earliestTimestamp = Instant.parse("2024-01-05T10:00:00Z")
    private val latestTimestamp = Instant.parse("2024-03-20T18:30:00Z")

    private lateinit var currency: Currency
    private var counter = 0

    private suspend fun setupAccountsAndCurrency() {
        val currencyId = repositories.currencyRepository.upsertCurrencyByCode("GBP", "British Pound")
        currency = repositories.currencyRepository.getCurrencyById(currencyId).first()!!
        repositories.accountRepository.createAccount(
            Account(id = AccountId(0), name = "Source", openingDate = Clock.System.now()),
        )
        repositories.accountRepository.createAccount(
            Account(id = AccountId(0), name = "Target", openingDate = Clock.System.now()),
        )
    }

    private suspend fun createTransferAt(
        timestamp: Instant,
        source: Source = Source.SampleGenerator,
    ): Transfer {
        val accounts = repositories.accountRepository.getAllAccounts().first()
        val transfer =
            Transfer(
                id = TransferId(0),
                timestamp = timestamp,
                description = "transfer-${counter++}",
                sourceAccountId = accounts.first { it.name == "Source" }.id,
                targetAccountId = accounts.first { it.name == "Target" }.id,
                amount = Money.fromDisplayValue(BigDecimal("12.34"), currency),
            )
        repositories.transactionRepository.createTransfers(
            transfers = listOf(transfer),
            sources = listOf(source),
        )
        return repositories.transactionRepository
            .getTransactionsByDateRange(startDate = timestamp, endDate = timestamp)
            .first()
            .first { it.description == transfer.description }
    }

    private suspend fun attachCsvProvenance(
        transfer: Transfer,
        importId: CsvImportId,
        rowIndex: Long,
    ) {
        val source =
            repositories.transferSourceRepository.getSourceByRevision(
                transactionId = transfer.id,
                revisionId = transfer.revisionId,
            )
        assertNotNull(source)
        entitySourceQueries.insertCsvSource(
            id = source.id,
            csv_import_id = importId.id.toString(),
            csv_row_index = rowIndex,
        )
    }

    private suspend fun createCsvImport(fileName: String): CsvImportId =
        repositories.csvImportRepository.createImport(
            fileName = fileName,
            headers = headers,
            rows = rows,
            fileChecksum = "checksum-$fileName",
            fileLastModified = fileLastModified,
        )

    private suspend fun createStrategy(name: String): CsvImportStrategy {
        val strategy =
            CsvImportStrategy(
                id = CsvImportStrategyId(Uuid.random()),
                name = name,
                identificationColumns = headers.toSet(),
                fieldMappings = emptyMap(),
                createdAt = Clock.System.now(),
                updatedAt = Clock.System.now(),
            )
        repositories.csvImportStrategyRepository.createStrategy(strategy, Source.Manual)
        return strategy
    }

    @Test
    fun `csv ranges cover min max timestamps and strategy of the latest application`() =
        runTest {
            setupAccountsAndCurrency()
            val importId = createCsvImport("statement.csv")
            val strategy = createStrategy("Monzo")
            repositories.csvImportRepository.recordImportApplication(importId, strategy.id, strategy.name, appliedAt)
            attachCsvProvenance(createTransferAt(earliestTimestamp), importId, rowIndex = 0)
            attachCsvProvenance(createTransferAt(latestTimestamp), importId, rowIndex = 1)

            val ranges = repositories.importTimelineRepository.getCsvImportDateRanges().first()

            val range = ranges.single()
            assertEquals(TimelineSourceKind.CSV, range.kind)
            assertEquals(importId.id.toString(), range.fileId)
            assertEquals("statement.csv", range.fileName)
            assertEquals("Monzo", range.strategyName)
            assertEquals(earliestTimestamp, range.earliest)
            assertEquals(latestTimestamp, range.latest)
            assertEquals(2, range.transactionCount)
        }

    @Test
    fun `re-imported transfer with multiple provenance revisions is counted once`() =
        runTest {
            setupAccountsAndCurrency()
            val importId = createCsvImport("statement.csv")
            val transfer = createTransferAt(earliestTimestamp)
            attachCsvProvenance(transfer, importId, rowIndex = 0)
            // A re-import records fresh provenance under the next revision for the same transfer.
            // Sample-generator type keeps getSourceByRevision reconstructable before the CSV side
            // row lands; the timeline join only follows csv_entity_source, not the source type.
            entitySourceQueries.insertSource(
                entity_type_id = 7,
                entity_id = transfer.id.id,
                revision_id = transfer.revisionId + 1,
                source_type_id = 3,
                device_id = repositories.deviceId.id,
            )
            val reimportSource =
                repositories.transferSourceRepository.getSourceByRevision(
                    transactionId = transfer.id,
                    revisionId = transfer.revisionId + 1,
                )
            assertNotNull(reimportSource)
            entitySourceQueries.insertCsvSource(
                id = reimportSource.id,
                csv_import_id = importId.id.toString(),
                csv_row_index = 0,
            )

            val range =
                repositories.importTimelineRepository
                    .getCsvImportDateRanges()
                    .first()
                    .single()
            assertEquals(1, range.transactionCount)
        }

    @Test
    fun `imports without transactions are absent`() =
        runTest {
            createCsvImport("unapplied.csv")

            assertTrue(
                repositories.importTimelineRepository
                    .getCsvImportDateRanges()
                    .first()
                    .isEmpty(),
            )
            assertTrue(
                repositories.importTimelineRepository
                    .getAllDateRanges()
                    .first()
                    .isEmpty(),
            )
        }

    @Test
    fun `qif ranges mirror csv shape`() =
        runTest {
            setupAccountsAndCurrency()
            val qifImportId: QifImportId =
                repositories.qifImportRepository.createImport(
                    fileName = "statement.qif",
                    records = emptyList(),
                    accountType = "Bank",
                    fileChecksum = "qif-checksum",
                    fileLastModified = fileLastModified,
                )
            val strategy = createStrategy("Monzo")
            repositories.qifImportRepository.recordImportApplication(qifImportId, strategy.id, strategy.name, appliedAt)
            val transfer = createTransferAt(earliestTimestamp)
            val source =
                repositories.transferSourceRepository.getSourceByRevision(
                    transactionId = transfer.id,
                    revisionId = transfer.revisionId,
                )
            assertNotNull(source)
            entitySourceQueries.insertQifSource(
                id = source.id,
                qif_import_id = qifImportId.id.toString(),
                qif_record_index = 0,
            )

            val range =
                repositories.importTimelineRepository
                    .getQifImportDateRanges()
                    .first()
                    .single()
            assertEquals(TimelineSourceKind.QIF, range.kind)
            assertEquals("statement.qif", range.fileName)
            assertEquals("Monzo", range.strategyName)
            assertEquals(earliestTimestamp, range.earliest)
            assertEquals(earliestTimestamp, range.latest)
            assertEquals(1, range.transactionCount)
        }

    @Test
    fun `api session ranges group by session for a strategy-less credential`() =
        runTest {
            setupAccountsAndCurrency()
            val sessionId =
                repositories.apiSessionRepository.createSession(
                    token = "token",
                    deviceId = repositories.deviceId,
                    createdAt = Clock.System.now(),
                    expiresAt = null,
                )
            val requestId =
                repositories.apiSessionRepository.insertRequest(
                    sessionId = sessionId,
                    method = "GET",
                    url = "https://api.example.com/transactions",
                    headers = emptyMap(),
                )
            val transfer = createTransferAt(latestTimestamp)
            val source =
                repositories.transferSourceRepository.getSourceByRevision(
                    transactionId = transfer.id,
                    revisionId = transfer.revisionId,
                )
            assertNotNull(source)
            entitySourceQueries.insertApiSource(
                id = source.id,
                api_session_id = sessionId.id,
                api_request_id = requestId.id,
                json_path = "$.transactions[0]",
            )

            val range =
                repositories.importTimelineRepository
                    .getApiSessionDateRanges()
                    .first()
                    .single()
            assertEquals(TimelineSourceKind.API, range.kind)
            assertEquals(sessionId.id.toString(), range.fileId)
            assertNull(range.strategyName)
            assertEquals(latestTimestamp, range.earliest)
            assertEquals(latestTimestamp, range.latest)
        }

    @Test
    fun `manual range covers manually created transfers only`() =
        runTest {
            setupAccountsAndCurrency()
            createTransferAt(earliestTimestamp, source = Source.Manual)
            createTransferAt(latestTimestamp, source = Source.Manual)
            // A CSV-created transfer later edited manually must stay out of the Manual row: its
            // creation revision is CSV even though a later revision is MANUAL.
            val imported = createTransferAt(Instant.parse("2020-06-01T00:00:00Z"))
            entitySourceQueries.insertSource(
                entity_type_id = 7,
                entity_id = imported.id.id,
                revision_id = imported.revisionId + 1,
                source_type_id = 1,
                device_id = repositories.deviceId.id,
            )

            val manual = repositories.importTimelineRepository.getManualDateRange().first()

            assertNotNull(manual)
            assertEquals(TimelineSourceKind.MANUAL, manual.kind)
            assertEquals(earliestTimestamp, manual.earliest)
            assertEquals(latestTimestamp, manual.latest)
            assertEquals(2, manual.transactionCount)
        }

    @Test
    fun `manual range is null when nothing was entered manually`() =
        runTest {
            setupAccountsAndCurrency()
            createTransferAt(earliestTimestamp)

            assertNull(repositories.importTimelineRepository.getManualDateRange().first())
        }

    @Test
    fun `getAllDateRanges combines every source`() =
        runTest {
            setupAccountsAndCurrency()
            val importId = createCsvImport("statement.csv")
            attachCsvProvenance(createTransferAt(earliestTimestamp), importId, rowIndex = 0)
            createTransferAt(latestTimestamp, source = Source.Manual)

            val all = repositories.importTimelineRepository.getAllDateRanges().first()

            assertEquals(setOf(TimelineSourceKind.CSV, TimelineSourceKind.MANUAL), all.map { it.kind }.toSet())
        }
}
