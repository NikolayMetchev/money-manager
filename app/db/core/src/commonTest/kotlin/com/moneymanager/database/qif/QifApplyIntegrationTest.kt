@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.qif
import com.moneymanager.database.csv.CsvTransferMapper
import com.moneymanager.database.csv.ExistingTransferInfo
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.model.csvstrategy.AmountMode
import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyId
import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.DirectColumnMapping
import com.moneymanager.domain.model.csvstrategy.FieldMappingId
import com.moneymanager.domain.model.csvstrategy.HardCodedAccountMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedCurrencyMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedTimezoneMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.qif.QifImportRecord
import com.moneymanager.domain.model.qif.QifRecordSplit
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.createAccount
import com.moneymanager.test.database.upsertCurrencyByCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Verifies that QIF imports reuse the CSV strategy engine end-to-end: QIF records are adapted to
 * fixed-column rows and mapped by [CsvTransferMapper], with split records expanding to one transfer
 * per split.
 */
class QifApplyIntegrationTest : DbTest() {
    private suspend fun seed(): Triple<Currency, Account, Account> {
        val currencyId = repositories.currencyRepository.upsertCurrencyByCode("GBP", "British Pound")
        val currency = repositories.currencyRepository.getCurrencyById(currencyId).first()!!
        repositories.accountRepository.createAccount(Account(id = AccountId(0), name = "Checking", openingDate = Clock.System.now()))
        repositories.accountRepository.createAccount(Account(id = AccountId(0), name = "Expenses", openingDate = Clock.System.now()))
        val accounts = repositories.accountRepository.getAllAccounts().first()
        return Triple(currency, accounts.first { it.name == "Checking" }, accounts.first { it.name == "Expenses" })
    }

    private fun strategy(
        currency: Currency,
        source: Account,
        target: Account,
    ): CsvImportStrategy =
        CsvImportStrategy(
            id = CsvImportStrategyId(Uuid.random()),
            name = "QIF Bank",
            identificationColumns = QifCsvAdapter.headers.toSet(),
            fieldMappings =
                mapOf(
                    TransferField.SOURCE_ACCOUNT to
                        HardCodedAccountMapping(FieldMappingId(Uuid.random()), TransferField.SOURCE_ACCOUNT, source.id),
                    TransferField.TARGET_ACCOUNT to
                        HardCodedAccountMapping(FieldMappingId(Uuid.random()), TransferField.TARGET_ACCOUNT, target.id),
                    TransferField.TIMESTAMP to
                        DateTimeParsingMapping(
                            id = FieldMappingId(Uuid.random()),
                            fieldType = TransferField.TIMESTAMP,
                            dateColumnName = QifCsvAdapter.COL_DATE,
                            dateFormat = "MM/dd/yyyy",
                        ),
                    TransferField.DESCRIPTION to
                        DirectColumnMapping(FieldMappingId(Uuid.random()), TransferField.DESCRIPTION, QifCsvAdapter.COL_PAYEE),
                    TransferField.AMOUNT to
                        AmountParsingMapping(
                            id = FieldMappingId(Uuid.random()),
                            fieldType = TransferField.AMOUNT,
                            mode = AmountMode.SINGLE_COLUMN,
                            amountColumnName = QifCsvAdapter.COL_AMOUNT,
                        ),
                    TransferField.CURRENCY to
                        HardCodedCurrencyMapping(FieldMappingId(Uuid.random()), TransferField.CURRENCY, currency.id),
                    TransferField.TIMEZONE to
                        HardCodedTimezoneMapping(FieldMappingId(Uuid.random()), TransferField.TIMEZONE, "UTC"),
                ),
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now(),
        )

    @Test
    fun qifRecordsMapThroughCsvEngine_splitsExpandToMultipleTransfers() =
        runTest {
            val (currency, source, target) = seed()
            val rows = QifCsvAdapter.toRows(sampleRecords())
            assertEquals(3, rows.size)

            val mapper =
                CsvTransferMapper(
                    strategy = strategy(currency, source, target),
                    columns = QifCsvAdapter.columns,
                    existingAccounts = mapOf(source.name to source, target.name to target),
                    existingCurrencies = mapOf(currency.id to currency),
                    existingCurrenciesByCode = mapOf(currency.code.uppercase() to currency),
                )

            val preparation = mapper.prepareImport(rows)

            assertEquals(emptyList(), preparation.errorRows)
            assertEquals(3, preparation.validTransfers.size)
            assertTrue(preparation.validTransfers.all { it.importStatus == ImportStatus.IMPORTED })
            assertTrue(preparation.validTransfers.all { it.transfer.sourceAccountId == source.id })
            // The two split rows both originate from record index 1.
            assertEquals(listOf(0L, 1L, 1L), preparation.validTransfers.map { it.rowIndex })
        }

    @Test
    fun reimportingSameRecords_isDetectedAsDuplicate() =
        runTest {
            val (currency, source, target) = seed()
            val rows = QifCsvAdapter.toRows(sampleRecords())
            val accountsByName = mapOf(source.name to source, target.name to target)
            val byId = mapOf(currency.id to currency)
            val byCode = mapOf(currency.code.uppercase() to currency)
            val strat = strategy(currency, source, target)

            // First import produces three new transfers.
            val first =
                CsvTransferMapper(strat, QifCsvAdapter.columns, accountsByName, byId, byCode).prepareImport(rows)
            val existing =
                first.validTransfers.mapIndexed { index, v ->
                    ExistingTransferInfo(
                        transferId = TransferId(index + 1L),
                        transfer = v.transfer.copy(id = TransferId(index + 1L)),
                        attributes = v.attributes,
                        uniqueIdentifierValues = emptyMap(),
                    )
                }

            // Re-importing the same records with those transfers present marks them all as duplicates
            // (matched on date + accounts + amount + description; QIF has no transaction id).
            val second =
                CsvTransferMapper(strat, QifCsvAdapter.columns, accountsByName, byId, byCode, existingTransfers = existing)
                    .prepareImport(rows)

            assertEquals(3, second.validTransfers.size)
            assertTrue(second.validTransfers.all { it.importStatus == ImportStatus.DUPLICATE })
        }

    // Long descriptions like the real Santander data, so a small suffix change stays >85% similar.
    private val payeeBase = "CASH WITHDRAWAL AT NATIONWIDE BUILDING SOCIETY ATM WIMBLEDON HILL, WIMBLEDON, O,"

    private fun record(
        date: String,
        amount: String,
        payee: String,
    ) = listOf(
        QifImportRecord(
            recordIndex = 0,
            sectionType = "BANK",
            accountName = "Checking",
            supported = true,
            rawText = "",
            date = date,
            amount = amount,
            payee = payee,
        ),
    )

    /** Runs an import of [records] against [existing] transfers and returns the resulting statuses. */
    private fun statuses(
        currency: Currency,
        source: Account,
        target: Account,
        records: List<QifImportRecord>,
        existing: List<ExistingTransferInfo> = emptyList(),
    ): List<ImportStatus> =
        CsvTransferMapper(
            strategy = strategy(currency, source, target),
            columns = QifCsvAdapter.columns,
            existingAccounts = mapOf(source.name to source, target.name to target),
            existingCurrencies = mapOf(currency.id to currency),
            existingCurrenciesByCode = mapOf(currency.code.uppercase() to currency),
            existingTransfers = existing,
        ).prepareImport(QifCsvAdapter.toRows(records)).validTransfers.map { it.importStatus }

    /** Builds existing transfers from [records], forcing a different target account so fuzzy matching
     *  must rely on the shared source account (the bank side), mirroring the real re-import case. */
    private fun existingFrom(
        currency: Currency,
        source: Account,
        target: Account,
        records: List<QifImportRecord>,
    ): List<ExistingTransferInfo> =
        CsvTransferMapper(
            strategy = strategy(currency, source, target),
            columns = QifCsvAdapter.columns,
            existingAccounts = mapOf(source.name to source, target.name to target),
            existingCurrencies = mapOf(currency.id to currency),
            existingCurrenciesByCode = mapOf(currency.code.uppercase() to currency),
        ).prepareImport(QifCsvAdapter.toRows(records)).validTransfers.mapIndexed { i, v ->
            ExistingTransferInfo(
                transferId = TransferId(i + 1L),
                transfer = v.transfer.copy(id = TransferId(i + 1L), targetAccountId = AccountId(99_000L + i)),
                attributes = v.attributes,
                uniqueIdentifierValues = emptyMap(),
            )
        }

    @Test
    fun nearDuplicate_driftedDescriptionDateAndAccount_isDetectedAsDuplicate() =
        runTest {
            val (currency, source, target) = seed()
            val existing = existingFrom(currency, source, target, record("03/16/2022", "-20.00", "$payeeBase 20.00GBP"))
            // Re-import: trailing GBP dropped, date shifted +2 days, and a different target account.
            val result = statuses(currency, source, target, record("03/18/2022", "-20.00", "$payeeBase 20.00"), existing)
            assertEquals(listOf(ImportStatus.DUPLICATE), result)
        }

    @Test
    fun differentAmount_isNotAFuzzyDuplicate() =
        runTest {
            val (currency, source, target) = seed()
            val existing = existingFrom(currency, source, target, record("03/16/2022", "-20.00", "$payeeBase 20.00GBP"))
            val result = statuses(currency, source, target, record("03/16/2022", "-25.00", "$payeeBase 20.00"), existing)
            assertEquals(listOf(ImportStatus.IMPORTED), result)
        }

    @Test
    fun dateBeyondTolerance_isNotAFuzzyDuplicate() =
        runTest {
            val (currency, source, target) = seed()
            val existing = existingFrom(currency, source, target, record("03/16/2022", "-20.00", "$payeeBase 20.00GBP"))
            // 9 days apart — outside the +/-3 day window.
            val result = statuses(currency, source, target, record("03/25/2022", "-20.00", "$payeeBase 20.00"), existing)
            assertEquals(listOf(ImportStatus.IMPORTED), result)
        }

    @Test
    fun differentPayee_isNotAFuzzyDuplicate() =
        runTest {
            val (currency, source, target) = seed()
            val existing =
                existingFrom(
                    currency,
                    source,
                    target,
                    record("03/16/2022", "-16.00", "DIRECT DEBIT TO THAMES WATER REF 0183323799 MONTHLY"),
                )
            val result =
                statuses(
                    currency,
                    source,
                    target,
                    record("03/16/2022", "-16.00", "CARD PURCHASE AT AMAZON UK MARKETPLACE ORDER 778229"),
                    existing,
                )
            assertEquals(listOf(ImportStatus.IMPORTED), result)
        }

    private fun sampleRecords(): List<QifImportRecord> =
        listOf(
            QifImportRecord(
                recordIndex = 0,
                sectionType = "BANK",
                accountName = "Checking",
                supported = true,
                rawText = "",
                date = "03/15/2022",
                amount = "-12.00",
                payee = "Coffee Shop",
            ),
            QifImportRecord(
                recordIndex = 1,
                sectionType = "BANK",
                accountName = "Checking",
                supported = true,
                rawText = "",
                date = "03/16/2022",
                amount = "-90.00",
                payee = "Supermarket",
                splits =
                    listOf(
                        QifRecordSplit(category = "Groceries", amount = "-60.00"),
                        QifRecordSplit(category = "Household", amount = "-30.00"),
                    ),
            ),
        )
}
