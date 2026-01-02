@file:OptIn(
    kotlin.time.ExperimentalTime::class,
    kotlin.uuid.ExperimentalUuidApi::class,
    kotlinx.datetime.format.FormatStringsInDatetimeFormats::class,
)

package com.moneymanager.database.csv

import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.model.csvstrategy.AccountLookupMapping
import com.moneymanager.domain.model.csvstrategy.AmountMode
import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CurrencyLookupMapping
import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.DirectColumnMapping
import com.moneymanager.domain.model.csvstrategy.FieldMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedAccountMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedCurrencyMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedTimezoneMapping
import com.moneymanager.domain.model.csvstrategy.TimezoneLookupMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.byUnicodePattern
import kotlinx.datetime.toInstant
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Result of mapping a CSV row to a Transfer.
 */
sealed interface MappingResult {
    /**
     * @property transfer The mapped transfer
     * @property newAccountName Name of new account to create (if any)
     * @property attributes List of (attributeTypeName, value) pairs extracted from CSV
     * @property importStatus The import status (IMPORTED for new, DUPLICATE if exists with same values, UPDATED if exists with different values)
     * @property existingTransferId If status is DUPLICATE or UPDATED, the ID of the existing transfer
     */
    data class Success(
        val transfer: Transfer,
        val newAccountName: String?,
        val attributes: List<Pair<String, String>> = emptyList(),
        val importStatus: ImportStatus = ImportStatus.IMPORTED,
        val existingTransferId: TransferId? = null,
    ) : MappingResult

    data class Error(
        val rowIndex: Long,
        val errorMessage: String,
    ) : MappingResult
}

/**
 * A new account that needs to be created during import.
 */
data class NewAccount(
    val name: String,
    val categoryId: Long,
)

/**
 * A transfer with its associated attributes extracted from CSV.
 * Uses attribute type names (not IDs) since types may need to be created.
 *
 * @property transfer The transfer to import
 * @property attributes List of (attributeTypeName, value) pairs
 * @property importStatus The import status (IMPORTED, DUPLICATE, UPDATED)
 * @property existingTransferId If status is DUPLICATE or UPDATED, the ID of the existing transfer
 * @property rowIndex The original CSV row index for status tracking
 */
data class CsvTransferWithAttributes(
    val transfer: Transfer,
    val attributes: List<Pair<String, String>>,
    val importStatus: ImportStatus = ImportStatus.IMPORTED,
    val existingTransferId: TransferId? = null,
    val rowIndex: Long,
)

/**
 * Result of preparing an import batch.
 *
 * @property validTransfers List of transfers to import with their status
 * @property errorRows Rows that failed to parse
 * @property newAccounts New accounts that need to be created
 * @property existingAccountMatches Map of account name to existing account ID
 * @property statusCounts Count of transfers by import status
 */
data class ImportPreparation(
    val validTransfers: List<CsvTransferWithAttributes>,
    val errorRows: List<MappingResult.Error>,
    val newAccounts: Set<NewAccount>,
    val existingAccountMatches: Map<String, AccountId>,
    val statusCounts: Map<ImportStatus, Int> = emptyMap(),
)

/**
 * Information about an existing transfer for duplicate detection.
 */
data class ExistingTransferInfo(
    val transferId: TransferId,
    val transfer: Transfer,
    val attributes: List<Pair<String, String>>,
    val uniqueIdentifierValues: Map<String, String>,
)

/**
 * Maps CSV rows to Transfer objects using an import strategy.
 */
class CsvTransferMapper(
    private val strategy: CsvImportStrategy,
    private val columns: List<CsvColumn>,
    private val existingAccounts: Map<String, Account>,
    private val existingCurrencies: Map<CurrencyId, Currency>,
    private val existingCurrenciesByCode: Map<String, Currency>,
    private val existingTransfers: List<ExistingTransferInfo> = emptyList(),
) {
    private val columnIndexByName: Map<String, Int> =
        columns.associate { it.originalName to it.columnIndex }

    // Extract unique identifier column names from strategy
    private val uniqueIdentifierColumns: List<String> =
        strategy.attributeMappings.filter { it.isUniqueIdentifier }.map { it.columnName }

    // Index existing transfers by their unique identifier values for fast lookup
    private val existingTransfersByUniqueId: Map<Map<String, String>, ExistingTransferInfo> =
        if (uniqueIdentifierColumns.isNotEmpty()) {
            existingTransfers.associateBy { it.uniqueIdentifierValues }
        } else {
            emptyMap()
        }

    /**
     * Prepares an import by mapping all rows and collecting new accounts to create.
     */
    fun prepareImport(rows: List<CsvRow>): ImportPreparation {
        val validTransfers = mutableListOf<CsvTransferWithAttributes>()
        val errorRows = mutableListOf<MappingResult.Error>()
        val newAccounts = mutableSetOf<NewAccount>()
        val existingMatches = mutableMapOf<String, AccountId>()
        val statusCounts = mutableMapOf<ImportStatus, Int>()

        for (row in rows) {
            when (val result = mapRow(row)) {
                is MappingResult.Success -> {
                    validTransfers.add(
                        CsvTransferWithAttributes(
                            transfer = result.transfer,
                            attributes = result.attributes,
                            importStatus = result.importStatus,
                            existingTransferId = result.existingTransferId,
                            rowIndex = row.rowIndex,
                        ),
                    )
                    // Count by status
                    statusCounts[result.importStatus] = statusCounts.getOrDefault(result.importStatus, 0) + 1

                    if (result.newAccountName != null) {
                        val lookupMapping = strategy.fieldMappings[TransferField.TARGET_ACCOUNT]
                        val categoryId =
                            (lookupMapping as? AccountLookupMapping)?.defaultCategoryId
                                ?: com.moneymanager.domain.model.Category.UNCATEGORIZED_ID
                        newAccounts.add(NewAccount(result.newAccountName, categoryId))
                    }
                }
                is MappingResult.Error -> {
                    errorRows.add(result)
                }
            }
        }

        // Collect existing account matches
        for (transferWithAttrs in validTransfers) {
            for ((name, account) in existingAccounts) {
                if (account.id == transferWithAttrs.transfer.sourceAccountId ||
                    account.id == transferWithAttrs.transfer.targetAccountId
                ) {
                    existingMatches[name] = account.id
                }
            }
        }

        return ImportPreparation(
            validTransfers = validTransfers,
            errorRows = errorRows,
            newAccounts = newAccounts,
            existingAccountMatches = existingMatches,
            statusCounts = statusCounts,
        )
    }

    /**
     * Maps a single CSV row to a Transfer.
     */
    fun mapRow(row: CsvRow): MappingResult {
        return try {
            val values = row.values
            val sourceMapping =
                strategy.fieldMappings[TransferField.SOURCE_ACCOUNT]
                    ?: return MappingResult.Error(row.rowIndex, "Missing SOURCE_ACCOUNT mapping")
            val targetMapping =
                strategy.fieldMappings[TransferField.TARGET_ACCOUNT]
                    ?: return MappingResult.Error(row.rowIndex, "Missing TARGET_ACCOUNT mapping")
            val timestampMapping =
                strategy.fieldMappings[TransferField.TIMESTAMP]
                    ?: return MappingResult.Error(row.rowIndex, "Missing TIMESTAMP mapping")
            val descriptionMapping =
                strategy.fieldMappings[TransferField.DESCRIPTION]
                    ?: return MappingResult.Error(row.rowIndex, "Missing DESCRIPTION mapping")
            val amountMapping =
                strategy.fieldMappings[TransferField.AMOUNT]
                    ?: return MappingResult.Error(row.rowIndex, "Missing AMOUNT mapping")
            val currencyMapping =
                strategy.fieldMappings[TransferField.CURRENCY]
                    ?: return MappingResult.Error(row.rowIndex, "Missing CURRENCY mapping")

            // Parse amount first (needed for account flipping)
            val rawAmount = parseAmount(amountMapping, values)

            // Parse currency
            val currency =
                parseCurrency(currencyMapping, values)
                    ?: return MappingResult.Error(row.rowIndex, "Currency not found")

            // Determine if we need to flip accounts
            val flipAccounts =
                amountMapping is AmountParsingMapping &&
                    amountMapping.flipAccountsOnPositive &&
                    rawAmount > BigDecimal.ZERO

            // Parse accounts with potential flipping
            var sourceAccountId = parseAccount(sourceMapping, values)
            var targetAccountId = parseAccount(targetMapping, values)
            if (flipAccounts) {
                val temp = sourceAccountId
                sourceAccountId = targetAccountId
                targetAccountId = temp
            }

            // Parse timezone (optional - defaults to system timezone)
            val timezoneMapping = strategy.fieldMappings[TransferField.TIMEZONE]
            val timezone = parseTimezone(timezoneMapping, values)

            // Parse timestamp
            val timestamp =
                parseTimestamp(timestampMapping as DateTimeParsingMapping, values, timezone)
                    ?: return MappingResult.Error(row.rowIndex, "Failed to parse timestamp")

            // Parse description
            val description = parseDescription(descriptionMapping, values)

            // Create Money with absolute value (direction is indicated by source/target)
            val amount = Money.fromDisplayValue(rawAmount.abs(), currency)

            val transfer =
                Transfer(
                    id = TransferId(Uuid.random()),
                    timestamp = timestamp,
                    description = description,
                    sourceAccountId = sourceAccountId,
                    targetAccountId = targetAccountId,
                    amount = amount,
                )

            // Extract attributes from mapped columns
            val attributes = extractAttributes(values)

            // Check for duplicates using unique identifiers if configured, otherwise check by all fields
            val (importStatus, existingTransferId) =
                if (uniqueIdentifierColumns.isNotEmpty()) {
                    checkForDuplicateByUniqueId(values, transfer, attributes)
                } else {
                    checkForDuplicateByAllFields(transfer, attributes)
                }

            MappingResult.Success(
                transfer = transfer,
                newAccountName =
                    if (targetMapping is AccountLookupMapping) {
                        val name = getAccountName(targetMapping, values)
                        if (name.isNotBlank() && !accountExists(name)) name else null
                    } else {
                        null
                    },
                attributes = attributes,
                importStatus = importStatus,
                existingTransferId = existingTransferId,
            )
        } catch (expected: Exception) {
            MappingResult.Error(row.rowIndex, expected.message ?: "Unknown error")
        }
    }

    /**
     * Extracts attribute values from CSV row based on strategy.attributeMappings.
     * Skips attributes with blank values.
     */
    private fun extractAttributes(values: List<String>): List<Pair<String, String>> {
        return strategy.attributeMappings.mapNotNull { mapping ->
            val value = getColumnValueOrNull(mapping.columnName, values)?.trim()
            if (value.isNullOrBlank()) {
                null
            } else {
                mapping.attributeTypeName to value
            }
        }
    }

    /**
     * Gets a column value by name, returning null if the column doesn't exist.
     */
    private fun getColumnValueOrNull(
        columnName: String,
        values: List<String>,
    ): String? {
        val index = columnIndexByName[columnName] ?: return null
        return values.getOrNull(index)
    }

    private fun parseAmount(
        amountMapping: FieldMapping,
        values: List<String>,
    ): BigDecimal {
        val mapping = amountMapping as AmountParsingMapping
        return when (mapping.mode) {
            AmountMode.SINGLE_COLUMN -> {
                val columnName =
                    mapping.amountColumnName
                        ?: error("amountColumnName required for SINGLE_COLUMN mode")
                val value = getColumnValue(columnName, values)
                if (mapping.negateValues) -parseBigDecimal(value) else parseBigDecimal(value)
            }
            AmountMode.CREDIT_DEBIT_COLUMNS -> {
                val creditColumnName =
                    mapping.creditColumnName
                        ?: error("creditColumnName required")
                val debitColumnName =
                    mapping.debitColumnName
                        ?: error("debitColumnName required")
                val creditValue = getColumnValue(creditColumnName, values)
                val debitValue = getColumnValue(debitColumnName, values)
                val credit = if (creditValue.isNotBlank()) parseBigDecimal(creditValue) else BigDecimal.ZERO
                val debit = if (debitValue.isNotBlank()) parseBigDecimal(debitValue) else BigDecimal.ZERO
                credit - debit
            }
        }
    }

    private fun parseAccount(
        mapping: FieldMapping,
        values: List<String>,
    ): AccountId {
        return when (mapping) {
            is HardCodedAccountMapping -> mapping.accountId
            is AccountLookupMapping -> {
                val name = getAccountName(mapping, values)
                existingAccounts[name]?.id
                    ?: AccountId(-1) // Placeholder for new accounts
            }
            else -> throw IllegalArgumentException("Invalid account mapping type: ${mapping::class}")
        }
    }

    /**
     * Gets the effective account name from an AccountLookupMapping,
     * trying the primary column first, then fallbacks in order.
     */
    private fun getAccountName(
        mapping: AccountLookupMapping,
        values: List<String>,
    ): String {
        return mapping.allColumns
            .map { getColumnValue(it, values) }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
    }

    private fun parseTimestamp(
        mapping: DateTimeParsingMapping,
        values: List<String>,
        timezone: TimeZone,
    ): Instant? {
        val dateValue = getColumnValue(mapping.dateColumnName, values)
        val timeValue =
            mapping.timeColumnName?.let { getColumnValue(it, values) }
                ?: mapping.defaultTime

        return try {
            // Parse date and time using the specified formats
            val dateTime = parseDateTimeString(dateValue, mapping.dateFormat, timeValue, mapping.timeFormat, timezone)
            dateTime
        } catch (_: Exception) {
            null
        }
    }

    private fun parseDateTimeString(
        dateValue: String,
        dateFormat: String,
        timeValue: String,
        timeFormat: String?,
        timezone: TimeZone,
    ): Instant {
        val date = LocalDate.Format { byUnicodePattern(dateFormat) }.parse(dateValue.trim())

        val time =
            if (timeValue.isBlank()) {
                LocalTime(12, 0, 0)
            } else if (timeFormat != null) {
                LocalTime.Format { byUnicodePattern(timeFormat) }.parse(timeValue.trim())
            } else {
                LocalTime.Format { byUnicodePattern("HH:mm[:ss]") }.parse(timeValue.trim())
            }

        return LocalDateTime(date, time).toInstant(timezone)
    }

    private fun parseTimezone(
        mapping: FieldMapping?,
        values: List<String>,
    ): TimeZone {
        return when (mapping) {
            is HardCodedTimezoneMapping -> TimeZone.of(mapping.timezoneId)
            is TimezoneLookupMapping -> {
                val tzId = getColumnValue(mapping.columnName, values).trim()
                if (tzId.isNotBlank()) TimeZone.of(tzId) else TimeZone.currentSystemDefault()
            }
            else -> TimeZone.currentSystemDefault()
        }
    }

    private fun parseDescription(
        mapping: FieldMapping,
        values: List<String>,
    ): String {
        return when (mapping) {
            is DirectColumnMapping -> getDirectColumnValue(mapping, values)
            else -> throw IllegalArgumentException("Invalid description mapping type: ${mapping::class}")
        }
    }

    /**
     * Gets the effective value from a DirectColumnMapping,
     * trying the primary column first, then fallbacks in order.
     */
    private fun getDirectColumnValue(
        mapping: DirectColumnMapping,
        values: List<String>,
    ): String {
        return mapping.allColumns
            .map { getColumnValue(it, values) }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
    }

    private fun parseCurrency(
        mapping: FieldMapping,
        values: List<String>,
    ): Currency? {
        return when (mapping) {
            is HardCodedCurrencyMapping -> existingCurrencies[mapping.currencyId]
            is CurrencyLookupMapping -> {
                val code = getColumnValue(mapping.columnName, values).trim().uppercase()
                existingCurrenciesByCode[code]
            }
            else -> throw IllegalArgumentException("Invalid currency mapping type: ${mapping::class}")
        }
    }

    private fun getColumnValue(
        columnName: String,
        values: List<String>,
    ): String {
        val index =
            columnIndexByName[columnName]
                ?: throw IllegalArgumentException("Column not found: $columnName")
        return values.getOrNull(index).orEmpty()
    }

    private fun accountExists(name: String): Boolean = name in existingAccounts

    private fun parseBigDecimal(value: String): BigDecimal {
        val cleaned =
            value.trim()
                .replace(",", "") // Remove thousand separators
                .replace(" ", "")
                .replace("$", "")
                .replace("€", "")
                .replace("£", "")
        return BigDecimal(cleaned)
    }

    /**
     * Checks if a transfer is a duplicate or update of an existing transfer using unique identifiers.
     *
     * @param values CSV row values
     * @param transfer The newly mapped transfer
     * @param attributes The newly mapped attributes
     * @return Pair of (import status, existing transfer ID if found)
     */
    private fun checkForDuplicateByUniqueId(
        values: List<String>,
        transfer: Transfer,
        attributes: List<Pair<String, String>>,
    ): Pair<ImportStatus, TransferId?> {
        // Extract unique identifier values from current row
        val uniqueIdValues =
            uniqueIdentifierColumns.associateWith { columnName ->
                getColumnValueOrNull(columnName, values)?.trim().orEmpty()
            }

        // Look up existing transfer by unique identifiers
        val existingInfo =
            existingTransfersByUniqueId[uniqueIdValues]
                ?: return ImportStatus.IMPORTED to null

        // Found a match - now determine if it's identical (DUPLICATE) or different (UPDATED)
        val isIdentical = transfersAreIdentical(transfer, attributes, existingInfo.transfer, existingInfo.attributes)

        return if (isIdentical) {
            ImportStatus.DUPLICATE to existingInfo.transferId
        } else {
            ImportStatus.UPDATED to existingInfo.transferId
        }
    }

    /**
     * Checks if a transfer is a duplicate or update by comparing all fields against existing transfers.
     * Used when no unique identifiers are configured.
     *
     * @param transfer The newly mapped transfer
     * @param attributes The newly mapped attributes
     * @return Pair of (import status, existing transfer ID if found)
     */
    private fun checkForDuplicateByAllFields(
        transfer: Transfer,
        attributes: List<Pair<String, String>>,
    ): Pair<ImportStatus, TransferId?> {
        // Find an existing transfer that matches all core fields
        for (existingInfo in existingTransfers) {
            val coreFieldsMatch =
                transfer.timestamp == existingInfo.transfer.timestamp &&
                    transfer.sourceAccountId == existingInfo.transfer.sourceAccountId &&
                    transfer.targetAccountId == existingInfo.transfer.targetAccountId &&
                    transfer.amount == existingInfo.transfer.amount &&
                    transfer.description == existingInfo.transfer.description

            if (coreFieldsMatch) {
                // Core fields match - check if attributes also match
                val attributesMatch = attributesAreIdentical(attributes, existingInfo.attributes)
                return if (attributesMatch) {
                    ImportStatus.DUPLICATE to existingInfo.transferId
                } else {
                    ImportStatus.UPDATED to existingInfo.transferId
                }
            }
        }

        // No match found
        return ImportStatus.IMPORTED to null
    }

    /**
     * Compares two transfers and their attributes to determine if they are identical.
     *
     * @param newTransfer The newly mapped transfer
     * @param newAttributes The newly mapped attributes
     * @param existingTransfer The existing transfer from database
     * @param existingAttributes The existing attributes from database
     * @return true if all fields and attributes match
     */
    private fun transfersAreIdentical(
        newTransfer: Transfer,
        newAttributes: List<Pair<String, String>>,
        existingTransfer: Transfer,
        existingAttributes: List<Pair<String, String>>,
    ): Boolean {
        // Compare all transfer fields (excluding ID which will always differ)
        if (newTransfer.timestamp != existingTransfer.timestamp) return false
        if (newTransfer.description != existingTransfer.description) return false
        if (newTransfer.sourceAccountId != existingTransfer.sourceAccountId) return false
        if (newTransfer.targetAccountId != existingTransfer.targetAccountId) return false
        if (newTransfer.amount != existingTransfer.amount) return false

        // Compare attributes (order-independent comparison)
        val newAttrMap = newAttributes.toMap()
        val existingAttrMap = existingAttributes.toMap()

        if (newAttrMap.size != existingAttrMap.size) return false
        if (newAttrMap.keys != existingAttrMap.keys) return false

        return newAttrMap.all { (key, value) -> existingAttrMap[key] == value }
    }

    /**
     * Compares two attribute lists to determine if they are identical (order-independent).
     */
    private fun attributesAreIdentical(
        newAttributes: List<Pair<String, String>>,
        existingAttributes: List<Pair<String, String>>,
    ): Boolean {
        val newAttrMap = newAttributes.toMap()
        val existingAttrMap = existingAttributes.toMap()

        if (newAttrMap.size != existingAttrMap.size) return false
        if (newAttrMap.keys != existingAttrMap.keys) return false

        return newAttrMap.all { (key, value) -> existingAttrMap[key] == value }
    }
}
