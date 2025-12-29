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
    data class Success(
        val transfer: Transfer,
        val newAccountName: String?,
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
 * Result of preparing an import batch.
 */
data class ImportPreparation(
    val validTransfers: List<Transfer>,
    val errorRows: List<MappingResult.Error>,
    val newAccounts: Set<NewAccount>,
    val existingAccountMatches: Map<String, AccountId>,
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
) {
    private val columnIndexByName: Map<String, Int> =
        columns.associate { it.originalName to it.columnIndex }

    /**
     * Prepares an import by mapping all rows and collecting new accounts to create.
     */
    fun prepareImport(rows: List<CsvRow>): ImportPreparation {
        val validTransfers = mutableListOf<Transfer>()
        val errorRows = mutableListOf<MappingResult.Error>()
        val newAccounts = mutableSetOf<NewAccount>()
        val existingMatches = mutableMapOf<String, AccountId>()

        for (row in rows) {
            when (val result = mapRow(row)) {
                is MappingResult.Success -> {
                    validTransfers.add(result.transfer)
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
        for (transfer in validTransfers) {
            for ((name, account) in existingAccounts) {
                if (account.id == transfer.sourceAccountId || account.id == transfer.targetAccountId) {
                    existingMatches[name] = account.id
                }
            }
        }

        return ImportPreparation(
            validTransfers = validTransfers,
            errorRows = errorRows,
            newAccounts = newAccounts,
            existingAccountMatches = existingMatches,
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
            val (rawAmount, newAccountName) =
                parseAmountAndAccount(
                    amountMapping,
                    targetMapping,
                    values,
                )

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

            MappingResult.Success(
                transfer = transfer,
                newAccountName =
                    if (targetMapping is AccountLookupMapping) {
                        val name = getAccountName(targetMapping, values)
                        if (name.isNotBlank() && !accountExists(name)) name else null
                    } else {
                        null
                    },
            )
        } catch (e: Exception) {
            MappingResult.Error(row.rowIndex, e.message ?: "Unknown error")
        }
    }

    private fun parseAmountAndAccount(
        amountMapping: FieldMapping,
        targetMapping: FieldMapping,
        values: List<String>,
    ): Pair<BigDecimal, String?> {
        val mapping = amountMapping as AmountParsingMapping
        val rawAmount =
            when (mapping.mode) {
                AmountMode.SINGLE_COLUMN -> {
                    val columnName =
                        mapping.amountColumnName
                            ?: throw IllegalStateException("amountColumnName required for SINGLE_COLUMN mode")
                    val value = getColumnValue(columnName, values)
                    if (mapping.negateValues) -parseBigDecimal(value) else parseBigDecimal(value)
                }
                AmountMode.CREDIT_DEBIT_COLUMNS -> {
                    val creditColumnName =
                        mapping.creditColumnName
                            ?: throw IllegalStateException("creditColumnName required")
                    val debitColumnName =
                        mapping.debitColumnName
                            ?: throw IllegalStateException("debitColumnName required")
                    val creditValue = getColumnValue(creditColumnName, values)
                    val debitValue = getColumnValue(debitColumnName, values)
                    val credit = if (creditValue.isNotBlank()) parseBigDecimal(creditValue) else BigDecimal.ZERO
                    val debit = if (debitValue.isNotBlank()) parseBigDecimal(debitValue) else BigDecimal.ZERO
                    credit - debit
                }
            }

        val newAccountName =
            if (targetMapping is AccountLookupMapping) {
                val name = getAccountName(targetMapping, values)
                if (name.isNotBlank() && !accountExists(name)) name else null
            } else {
                null
            }

        return rawAmount to newAccountName
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
            ?: ""
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
        } catch (e: Exception) {
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
            ?: ""
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
        return values.getOrNull(index) ?: ""
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
}
