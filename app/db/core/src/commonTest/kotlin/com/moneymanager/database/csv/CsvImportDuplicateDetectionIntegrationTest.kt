@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.csv

import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvColumnId
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.model.csvstrategy.AmountMode
import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.AttributeColumnMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyId
import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.DirectColumnMapping
import com.moneymanager.domain.model.csvstrategy.FieldMappingId
import com.moneymanager.domain.model.csvstrategy.HardCodedAccountMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedCurrencyMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedTimezoneMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Integration test that verifies the full CSV import flow with duplicate detection.
 * This test ensures that:
 * 1. Existing transfers are fetched from the repository
 * 2. They are properly passed to CsvTransferMapper
 * 3. Duplicate detection works end-to-end
 */
class CsvImportDuplicateDetectionIntegrationTest : DbTest() {
    private lateinit var testCurrency: Currency
    private lateinit var sourceAccount: Account
    private lateinit var targetAccount: Account

    private suspend fun setupTestData() {
        // Create currency
        val currencyId = repositories.currencyRepository.upsertCurrencyByCode("GBP", "British Pound")
        testCurrency = repositories.currencyRepository.getCurrencyById(currencyId).first()!!

        // Create accounts
        sourceAccount =
            Account(
                id = AccountId(0),
                name = "Test Source",
                openingDate = Clock.System.now(),
            )
        repositories.accountRepository.createAccount(sourceAccount)
        sourceAccount = repositories.accountRepository.getAllAccounts().first().first { it.name == "Test Source" }

        targetAccount =
            Account(
                id = AccountId(0),
                name = "Test Target",
                openingDate = Clock.System.now(),
            )
        repositories.accountRepository.createAccount(targetAccount)
        targetAccount = repositories.accountRepository.getAllAccounts().first().first { it.name == "Test Target" }
    }

    private fun createTestStrategy(): CsvImportStrategy {
        return CsvImportStrategy(
            id = CsvImportStrategyId(Uuid.random()),
            name = "Test Strategy",
            identificationColumns = setOf("Date", "Description", "Amount"),
            fieldMappings =
                mapOf(
                    TransferField.SOURCE_ACCOUNT to
                        HardCodedAccountMapping(
                            id = FieldMappingId(Uuid.random()),
                            fieldType = TransferField.SOURCE_ACCOUNT,
                            accountId = sourceAccount.id,
                        ),
                    TransferField.TARGET_ACCOUNT to
                        HardCodedAccountMapping(
                            id = FieldMappingId(Uuid.random()),
                            fieldType = TransferField.TARGET_ACCOUNT,
                            accountId = targetAccount.id,
                        ),
                    TransferField.TIMESTAMP to
                        DateTimeParsingMapping(
                            id = FieldMappingId(Uuid.random()),
                            fieldType = TransferField.TIMESTAMP,
                            dateColumnName = "Date",
                            dateFormat = "dd/MM/yyyy",
                        ),
                    TransferField.DESCRIPTION to
                        DirectColumnMapping(
                            id = FieldMappingId(Uuid.random()),
                            fieldType = TransferField.DESCRIPTION,
                            columnName = "Description",
                        ),
                    TransferField.AMOUNT to
                        AmountParsingMapping(
                            id = FieldMappingId(Uuid.random()),
                            fieldType = TransferField.AMOUNT,
                            mode = AmountMode.SINGLE_COLUMN,
                            amountColumnName = "Amount",
                            negateValues = true,
                        ),
                    TransferField.CURRENCY to
                        HardCodedCurrencyMapping(
                            id = FieldMappingId(Uuid.random()),
                            fieldType = TransferField.CURRENCY,
                            currencyId = testCurrency.id,
                        ),
                    TransferField.TIMEZONE to
                        HardCodedTimezoneMapping(
                            id = FieldMappingId(Uuid.random()),
                            fieldType = TransferField.TIMEZONE,
                            timezoneId = "UTC",
                        ),
                ),
            attributeMappings =
                listOf(
                    AttributeColumnMapping(
                        columnName = "Transaction ID",
                        attributeTypeName = "Transaction ID",
                        isUniqueIdentifier = true,
                    ),
                ),
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now(),
        )
    }

    private fun createTestColumns(): List<CsvColumn> {
        return listOf(
            CsvColumn(CsvColumnId(Uuid.random()), 0, "Transaction ID"),
            CsvColumn(CsvColumnId(Uuid.random()), 1, "Date"),
            CsvColumn(CsvColumnId(Uuid.random()), 2, "Description"),
            CsvColumn(CsvColumnId(Uuid.random()), 3, "Amount"),
        )
    }

    @Test
    fun `full import flow should detect duplicates using existing transfers from repository`() =
        runTest {
            setupTestData()

            // Create an existing transfer with attributes
            val localDate = kotlinx.datetime.LocalDate.parse("2024-01-01")
            val localTime = kotlinx.datetime.LocalTime(12, 0, 0)
            val timestamp = LocalDateTime(localDate, localTime).toInstant(TimeZone.UTC)

            val existingTransfer =
                Transfer(
                    id = TransferId(0L),
                    timestamp = timestamp,
                    description = "Coffee",
                    sourceAccountId = sourceAccount.id,
                    targetAccountId = targetAccount.id,
                    amount = Money.fromDisplayValue(BigDecimal("3.50"), testCurrency),
                )

            // Create attribute type and attributes
            val transactionIdTypeId = repositories.attributeTypeRepository.getOrCreate("Transaction ID")
            val attributes =
                listOf(
                    NewAttribute(transactionIdTypeId, "TX001"),
                )

            // Insert transfer with attributes using repository
            val deviceId = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test", "test"))
            repositories.transactionRepository.createTransfers(
                transfers = listOf(existingTransfer),
                newAttributes = mapOf(TransferId(0L) to attributes),
                sourceRecorder =
                    com.moneymanager.database.SampleGeneratorSourceRecorder(
                        transferSourceQueries,
                        deviceId,
                    ),
            )

            // Query back the created transfer
            val createdTransfersList =
                repositories.transactionRepository.getTransactionsByDateRange(
                    startDate = timestamp,
                    endDate = timestamp,
                ).first()
            val existingTransferId = createdTransfersList.first { it.description == "Coffee" }.id

            // Verify transfer was created
            val allTransfersBeforeImport =
                repositories.transactionRepository.getTransactionsByAccount(sourceAccount.id).first()
            assertEquals(1, allTransfersBeforeImport.size, "Should have 1 existing transfer")
            assertEquals(1, allTransfersBeforeImport[0].attributes.size, "Transfer should have 1 attribute")
            assertEquals("Transaction ID", allTransfersBeforeImport[0].attributes[0].attributeType.name)
            assertEquals("TX001", allTransfersBeforeImport[0].attributes[0].value)

            // Now simulate the import flow like ApplyStrategyDialog does
            val strategy = createTestStrategy()
            val columns = createTestColumns()

            // Fetch existing transfers by account and date range (optimized approach)
            val existingTransfers =
                repositories.transactionRepository.getTransactionsByAccountAndDateRange(
                    accountId = sourceAccount.id,
                    startDate = timestamp,
                    endDate = timestamp,
                ).first()

            // Build ExistingTransferInfo list like ApplyStrategyDialog does
            val existingTransferInfoList =
                existingTransfers.map { transfer ->
                    val attributesList =
                        transfer.attributes.map { attr ->
                            attr.attributeType.name to attr.value
                        }

                    val uniqueIdValues =
                        strategy.attributeMappings
                            .filter { it.isUniqueIdentifier }
                            .associate { mapping ->
                                val attributeValue =
                                    transfer.attributes
                                        .firstOrNull { it.attributeType.name == mapping.attributeTypeName }
                                        ?.value.orEmpty()
                                mapping.columnName to attributeValue
                            }

                    ExistingTransferInfo(
                        transferId = transfer.id,
                        transfer = transfer,
                        attributes = attributesList,
                        uniqueIdentifierValues = uniqueIdValues,
                    )
                }

            assertEquals(1, existingTransferInfoList.size, "Should have 1 existing transfer info")
            assertEquals("TX001", existingTransferInfoList[0].uniqueIdentifierValues["Transaction ID"])

            // Create mapper with existing transfers
            val accountsByName =
                repositories.accountRepository.getAllAccounts().first().associateBy { it.name }
            val currenciesById = mapOf(testCurrency.id to testCurrency)
            val currenciesByCode = mapOf("GBP" to testCurrency)

            val mapper =
                CsvTransferMapper(
                    strategy = strategy,
                    columns = columns,
                    existingAccounts = accountsByName,
                    existingCurrencies = currenciesById,
                    existingCurrenciesByCode = currenciesByCode,
                    existingTransfers = existingTransferInfoList,
                )

            // Import CSV rows with one duplicate and one new transaction
            val rows =
                listOf(
                    // Duplicate - same transaction ID
                    com.moneymanager.domain.model.csv.CsvRow(1, listOf("TX001", "01/01/2024", "Coffee", "3.50")),
                    // New transaction
                    com.moneymanager.domain.model.csv.CsvRow(2, listOf("TX002", "02/01/2024", "Lunch", "12.00")),
                )

            val prep = mapper.prepareImport(rows)

            // Verify duplicate detection worked
            assertEquals(2, prep.validTransfers.size, "Should have 2 valid transfers")
            assertEquals(0, prep.errorRows.size, "Should have no errors")
            assertEquals(1, prep.statusCounts[ImportStatus.IMPORTED], "Should have 1 new import")
            assertEquals(1, prep.statusCounts[ImportStatus.DUPLICATE], "Should detect 1 duplicate")
            assertEquals(null, prep.statusCounts[ImportStatus.UPDATED])

            // Verify the duplicate has the existing transfer ID
            assertEquals(ImportStatus.DUPLICATE, prep.validTransfers[0].importStatus)
            assertEquals(existingTransferId, prep.validTransfers[0].existingTransferId)

            // Verify the new transaction is marked as imported
            assertEquals(ImportStatus.IMPORTED, prep.validTransfers[1].importStatus)
            assertEquals(null, prep.validTransfers[1].existingTransferId)
        }

    @Test
    fun `duplicate detection should work without unique identifiers by comparing all fields`() =
        runTest {
            setupTestData()

            // Create an existing transfer WITHOUT any attributes
            val localDate = kotlinx.datetime.LocalDate.parse("2024-01-01")
            val localTime = kotlinx.datetime.LocalTime(12, 0, 0)
            val timestamp = LocalDateTime(localDate, localTime).toInstant(TimeZone.UTC)

            val existingTransfer =
                Transfer(
                    id = TransferId(0L),
                    timestamp = timestamp,
                    description = "Coffee",
                    sourceAccountId = sourceAccount.id,
                    targetAccountId = targetAccount.id,
                    amount = Money.fromDisplayValue(BigDecimal("3.50"), testCurrency),
                )

            val deviceId = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test", "test"))
            repositories.transactionRepository.createTransfers(
                transfers = listOf(existingTransfer),
                newAttributes = emptyMap(),
                sourceRecorder =
                    com.moneymanager.database.SampleGeneratorSourceRecorder(
                        transferSourceQueries,
                        deviceId,
                    ),
            )

            // Query back the created transfer
            val createdTransfersList =
                repositories.transactionRepository.getTransactionsByDateRange(
                    startDate = timestamp,
                    endDate = timestamp,
                ).first()
            val existingTransferId = createdTransfersList.first { it.description == "Coffee" }.id

            // Create strategy WITHOUT unique identifier attributes
            val strategyWithoutUniqueId =
                CsvImportStrategy(
                    id = CsvImportStrategyId(Uuid.random()),
                    name = "Test Strategy No Unique ID",
                    identificationColumns = setOf("Date", "Description", "Amount"),
                    fieldMappings = createTestStrategy().fieldMappings,
                    attributeMappings = emptyList(),
                    createdAt = Clock.System.now(),
                    updatedAt = Clock.System.now(),
                )

            // Fetch existing transfers by account and date range
            val existingTransfers =
                repositories.transactionRepository.getTransactionsByAccountAndDateRange(
                    accountId = sourceAccount.id,
                    startDate = timestamp,
                    endDate = timestamp,
                ).first()
            val existingTransferInfoList =
                existingTransfers.map { transfer ->
                    ExistingTransferInfo(
                        transferId = transfer.id,
                        transfer = transfer,
                        attributes = emptyList(),
                        uniqueIdentifierValues = emptyMap(),
                    )
                }

            val accountsByName =
                repositories.accountRepository.getAllAccounts().first().associateBy { it.name }
            val currenciesById = mapOf(testCurrency.id to testCurrency)
            val currenciesByCode = mapOf("GBP" to testCurrency)

            val mapper =
                CsvTransferMapper(
                    strategy = strategyWithoutUniqueId,
                    columns = createTestColumns(),
                    existingAccounts = accountsByName,
                    existingCurrencies = currenciesById,
                    existingCurrenciesByCode = currenciesByCode,
                    existingTransfers = existingTransferInfoList,
                )

            val rows =
                listOf(
                    // Exact duplicate by all fields
                    com.moneymanager.domain.model.csv.CsvRow(
                        1,
                        listOf("TX001", "01/01/2024", "Coffee", "3.50"),
                    ),
                    // New transaction
                    com.moneymanager.domain.model.csv.CsvRow(2, listOf("TX002", "02/01/2024", "Lunch", "12.00")),
                )

            val prep = mapper.prepareImport(rows)

            // Verify duplicate detection worked by comparing all fields
            assertEquals(2, prep.validTransfers.size)
            assertEquals(1, prep.statusCounts[ImportStatus.IMPORTED], "Should have 1 new import")
            assertEquals(1, prep.statusCounts[ImportStatus.DUPLICATE], "Should detect 1 duplicate by field comparison")
            assertEquals(ImportStatus.DUPLICATE, prep.validTransfers[0].importStatus)
            assertEquals(existingTransferId, prep.validTransfers[0].existingTransferId)
        }
}
