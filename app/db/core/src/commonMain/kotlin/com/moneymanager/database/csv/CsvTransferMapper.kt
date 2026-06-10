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
import com.moneymanager.domain.model.csvstrategy.ColumnPairSwap
import com.moneymanager.domain.model.csvstrategy.ConditionalAccountMapping
import com.moneymanager.domain.model.csvstrategy.CsvAccountMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CurrencyLookupMapping
import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.DirectColumnMapping
import com.moneymanager.domain.model.csvstrategy.FieldMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedAccountMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedCurrencyMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedTimezoneMapping
import com.moneymanager.domain.model.csvstrategy.RegexAccountMapping
import com.moneymanager.domain.model.csvstrategy.RowCondition
import com.moneymanager.domain.model.csvstrategy.RowConditionOperator
import com.moneymanager.domain.model.csvstrategy.TemplateAccountMapping
import com.moneymanager.domain.model.csvstrategy.TimezoneLookupMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.importer.StringSimilarity
import com.moneymanager.importmodel.DESCRIPTION_SIMILARITY_THRESHOLD
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.byUnicodePattern
import kotlinx.datetime.toInstant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Result of mapping a CSV row to a Transfer.
 */
sealed interface MappingResult {
    /**
     * @property transfer The mapped transfer
     * @property newAccounts New accounts to create (source and/or target side)
     * @property attributes List of (attributeTypeName, value) pairs extracted from CSV
     * @property importStatus The import status (IMPORTED for new, DUPLICATE if exists with same values, UPDATED if exists with different values)
     * @property existingTransferId If status is DUPLICATE or UPDATED, the ID of the existing transfer
     * @property discoveredMappings For each new account being created, the CSV column/value that triggered it (for auto-capture)
     */
    data class Success(
        val transfer: Transfer,
        val newAccounts: List<NewAccount> = emptyList(),
        val attributes: List<Pair<String, String>> = emptyList(),
        val importStatus: ImportStatus = ImportStatus.IMPORTED,
        val existingTransferId: TransferId? = null,
        val discoveredMappings: List<DiscoveredAccountMapping> = emptyList(),
    ) : MappingResult {
        /** Convenience for flows where only the target side can discover a new account. */
        val newAccountName: String? get() = newAccounts.firstOrNull()?.name

        /** Convenience for flows where only the target side can discover a new account. */
        val discoveredMapping: DiscoveredAccountMapping? get() = discoveredMappings.firstOrNull()
    }

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
 * @property discoveredMappings For each new account being created, the CSV column/value that triggered it
 */
data class CsvTransferWithAttributes(
    val transfer: Transfer,
    val attributes: List<Pair<String, String>>,
    val importStatus: ImportStatus = ImportStatus.IMPORTED,
    val existingTransferId: TransferId? = null,
    val rowIndex: Long,
    val discoveredMappings: List<DiscoveredAccountMapping> = emptyList(),
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
 * Represents a mapping discovered during import that can be persisted.
 * Used for auto-capturing mappings when new accounts are created.
 *
 * @property columnName The CSV column that was matched
 * @property csvValue The actual value from the CSV that led to this account
 * @property targetAccountName The name of the account that will be/was created from this value.
 *           For AccountLookupMapping, this equals csvValue. For RegexAccountMapping, this is the
 *           extracted account name which may differ from csvValue.
 * @property matchedPattern The regex pattern that matched (for RegexAccountMapping with rules),
 *           or null if this was from AccountLookupMapping or RegexAccountMapping fallback logic.
 *           When non-null, this pattern should be used for the persisted mapping instead of
 *           creating an exact-match pattern for csvValue.
 */
data class DiscoveredAccountMapping(
    val columnName: String,
    val csvValue: String,
    val targetAccountName: String,
    val matchedPattern: String? = null,
)

/**
 * Maps CSV rows to Transfer objects using an import strategy.
 */
class CsvTransferMapper(
    private val strategy: CsvImportStrategy,
    columns: List<CsvColumn>,
    private val existingAccounts: Map<String, Account>,
    private val existingCurrencies: Map<CurrencyId, Currency>,
    private val existingCurrenciesByCode: Map<String, Currency>,
    private val existingTransfers: List<ExistingTransferInfo> = emptyList(),
    accountMappings: List<CsvAccountMapping> = emptyList(),
    /** When set, overrides the strategy's SOURCE_ACCOUNT mapping for every row. */
    private val sourceAccountOverride: AccountId? = null,
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

    // Index account mappings by column name for fast lookup
    private val accountMappingsByColumn: Map<String, List<CsvAccountMapping>> =
        accountMappings.groupBy { it.columnName }

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
                            discoveredMappings = result.discoveredMappings,
                        ),
                    )
                    // Count by status
                    statusCounts[result.importStatus] = statusCounts.getOrDefault(result.importStatus, 0) + 1

                    newAccounts.addAll(result.newAccounts)
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
            // Attribute extraction and unique-id dedup use the original values so they stay
            // faithful to the CSV; field parsing uses the preprocessed (possibly swapped) values.
            val originalValues = row.values
            val (values, rulesFlip) = applyRowPreprocessing(originalValues)
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

            // Determine if we need to flip accounts (sign-based flip XOR preprocessing flip)
            val amountFlip =
                amountMapping is AmountParsingMapping &&
                    amountMapping.flipAccountsOnPositive &&
                    rawAmount > BigDecimal.ZERO
            val flipAccounts = amountFlip xor rulesFlip

            // Resolve the source account: use override if provided, otherwise fall back to the
            // strategy's SOURCE_ACCOUNT mapping (if present), or return an error.
            val resolvedSourceAccountId: AccountId =
                if (sourceAccountOverride != null) {
                    sourceAccountOverride
                } else {
                    val sourceMapping =
                        strategy.fieldMappings[TransferField.SOURCE_ACCOUNT]
                            ?: return MappingResult.Error(
                                row.rowIndex,
                                "No source account selected. Please choose a source account before importing.",
                            )
                    parseAccount(sourceMapping, values)
                }

            // Parse accounts with potential flipping
            var sourceAccountId = resolvedSourceAccountId
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

            // Placeholder ID - real ID generated by database
            val transfer =
                Transfer(
                    id = TransferId(0L),
                    timestamp = timestamp,
                    description = description,
                    sourceAccountId = sourceAccountId,
                    targetAccountId = targetAccountId,
                    amount = amount,
                )

            // Extract attributes from mapped columns (original, pre-swap values)
            val attributes = extractAttributes(originalValues)

            // Check for duplicates using unique identifiers if configured, otherwise check by all fields
            val (importStatus, existingTransferId) =
                if (uniqueIdentifierColumns.isNotEmpty()) {
                    checkForDuplicateByUniqueId(originalValues, transfer, attributes)
                } else {
                    checkForDuplicateByAllFields(transfer, attributes)
                }

            // Determine which new accounts need to be created and capture mapping info.
            // The source side participates only when it is resolved per-row (no UI override).
            val discoveries =
                buildList {
                    if (sourceAccountOverride == null) {
                        strategy.fieldMappings[TransferField.SOURCE_ACCOUNT]?.let { add(discoverNewAccount(it, values)) }
                    }
                    add(discoverNewAccount(targetMapping, values))
                }
            val newAccounts = discoveries.mapNotNull { it?.first }
            val discoveredMappings = discoveries.mapNotNull { it?.second }

            MappingResult.Success(
                transfer = transfer,
                newAccounts = newAccounts,
                attributes = attributes,
                importStatus = importStatus,
                existingTransferId = existingTransferId,
                discoveredMappings = discoveredMappings,
            )
        } catch (expected: Exception) {
            MappingResult.Error(row.rowIndex, expected.message ?: "Unknown error")
        }
    }

    /**
     * Extracts attribute values from CSV row based on strategy.attributeMappings.
     * Skips attributes with blank values.
     */
    private fun extractAttributes(values: List<String>): List<Pair<String, String>> =
        strategy.attributeMappings.mapNotNull { mapping ->
            val value = getColumnValueOrNull(mapping.columnName, values)?.trim()
            if (value.isNullOrBlank()) {
                null
            } else {
                mapping.attributeTypeName to value
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
        val baseAmount =
            when (mapping.mode) {
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
        return baseAmount + parseFee(mapping, values, baseAmount)
    }

    /**
     * Returns the fee to add to the amount's magnitude, signed to match [baseAmount].
     * Zero when no fee column is configured, the value is blank, or the conditions don't hold.
     */
    private fun parseFee(
        mapping: AmountParsingMapping,
        values: List<String>,
        baseAmount: BigDecimal,
    ): BigDecimal {
        val feeColumnName = mapping.feeColumnName ?: return BigDecimal.ZERO
        if (!mapping.feeConditions.all { evaluateCondition(it, values) }) return BigDecimal.ZERO
        val feeValue = getColumnValueOrNull(feeColumnName, values)?.trim()
        if (feeValue.isNullOrBlank()) return BigDecimal.ZERO
        val fee = parseBigDecimal(feeValue)
        return if (baseAmount < BigDecimal.ZERO) -fee else fee
    }

    /**
     * Applies the strategy's row preprocessing rules to the raw row values.
     * Returns the (possibly column-swapped) values and whether source/target accounts must flip.
     */
    private fun applyRowPreprocessing(values: List<String>): Pair<List<String>, Boolean> {
        var effective = values
        var flip = false
        for (rule in strategy.rowPreprocessingRules) {
            if (rule.conditions.all { evaluateCondition(it, effective) }) {
                effective = applyColumnSwaps(rule.columnSwaps, effective)
                if (rule.flipSourceAndTarget) flip = !flip
            }
        }
        return effective to flip
    }

    private fun applyColumnSwaps(
        swaps: List<ColumnPairSwap>,
        values: List<String>,
    ): List<String> {
        if (swaps.isEmpty()) return values
        val mutable = values.toMutableList()
        for (swap in swaps) {
            val firstIndex = columnIndexByName[swap.firstColumn]
            val secondIndex = columnIndexByName[swap.secondColumn]
            if (firstIndex != null && secondIndex != null) {
                // Rows may have fewer values than columns; pad so both indices are addressable
                while (mutable.size <= maxOf(firstIndex, secondIndex)) {
                    mutable.add("")
                }
                val temp = mutable[firstIndex]
                mutable[firstIndex] = mutable[secondIndex]
                mutable[secondIndex] = temp
            }
        }
        return mutable
    }

    private fun evaluateCondition(
        condition: RowCondition,
        values: List<String>,
    ): Boolean {
        val value = getColumnValueOrNull(condition.columnName, values)?.trim().orEmpty()
        return when (condition.operator) {
            RowConditionOperator.EQUALS_VALUE -> value == condition.value?.trim().orEmpty()
            RowConditionOperator.EQUALS_COLUMN -> value == otherColumnValue(condition, values)
            RowConditionOperator.NOT_EQUALS_COLUMN -> value != otherColumnValue(condition, values)
            RowConditionOperator.IS_BLANK -> value.isBlank()
            RowConditionOperator.IS_NOT_BLANK -> value.isNotBlank()
        }
    }

    private fun otherColumnValue(
        condition: RowCondition,
        values: List<String>,
    ): String {
        val otherColumn =
            condition.otherColumnName
                ?: error("otherColumnName required for ${condition.operator}")
        return getColumnValueOrNull(otherColumn, values)?.trim().orEmpty()
    }

    /**
     * Resolves a ConditionalAccountMapping to its active branch for the given row.
     */
    private fun resolveConditional(
        mapping: ConditionalAccountMapping,
        values: List<String>,
    ): FieldMapping =
        if (mapping.conditions.all { evaluateCondition(it, values) }) {
            mapping.whenTrue
        } else {
            mapping.whenFalse
        }

    /**
     * Builds the templated account name for a TemplateAccountMapping,
     * or an empty string when the column value is blank.
     */
    private fun templatedAccountName(
        mapping: TemplateAccountMapping,
        csvValue: String,
    ): String = if (csvValue.isBlank()) "" else mapping.prefix + csvValue + mapping.suffix

    private fun parseAccount(
        mapping: FieldMapping,
        values: List<String>,
    ): AccountId {
        // For hardcoded accounts, return immediately
        if (mapping is HardCodedAccountMapping) {
            return mapping.accountId
        }

        return when (mapping) {
            is TemplateAccountMapping -> {
                val csvValue = getColumnValue(mapping.columnName, values).trim()

                // Check persisted mappings FIRST - this handles renamed accounts
                val persistedMatch = findPersistedMapping(mapping.columnName, csvValue)
                if (persistedMatch != null) {
                    return persistedMatch
                }

                val name = templatedAccountName(mapping, csvValue)
                existingAccounts[name]?.id
                    ?: AccountId(-1) // Placeholder for new accounts
            }
            is ConditionalAccountMapping -> parseAccount(resolveConditional(mapping, values), values)
            is AccountLookupMapping -> {
                val columnName = mapping.columnName
                val csvValue = getColumnValue(columnName, values)

                // Check persisted mappings FIRST - this handles renamed accounts
                val persistedMatch = findPersistedMapping(columnName, csvValue)
                if (persistedMatch != null) {
                    return persistedMatch
                }

                // Fall back to lookup by name
                val name = getAccountName(mapping, values)
                existingAccounts[name]?.id
                    ?: AccountId(-1) // Placeholder for new accounts
            }
            is RegexAccountMapping -> {
                // For RegexAccountMapping, we need to determine which column/value
                // will actually be used (could be fallback column)
                val result = getAccountNameFromRegexWithPattern(mapping, values)

                // Check persisted mappings using the ACTUAL column/value that was resolved
                val persistedMatch = findPersistedMapping(result.sourceColumnName, result.sourceColumnValue)
                if (persistedMatch != null) {
                    return persistedMatch
                }

                // Fall back to lookup by name
                existingAccounts[result.accountName]?.id
                    ?: AccountId(-1) // Placeholder for new accounts
            }
            else -> throw IllegalArgumentException("Invalid account mapping type: ${mapping::class}")
        }
    }

    /**
     * Finds a persisted account mapping that matches the given column and value.
     * First matching mapping wins (ordered by id).
     *
     * @param columnName The CSV column name
     * @param value The value to match against
     * @return The mapped AccountId, or null if no match found
     */
    private fun findPersistedMapping(
        columnName: String,
        value: String,
    ): AccountId? {
        val mappings = accountMappingsByColumn[columnName] ?: return null
        for (mapping in mappings) {
            if (mapping.valuePattern.containsMatchIn(value)) {
                return mapping.accountId
            }
        }
        return null
    }

    /**
     * Determines whether resolving [mapping] for this row requires creating a new account.
     * Returns the account to create together with the discovered mapping for auto-capture,
     * or null when no new account is needed (existing account, persisted mapping, or blank value).
     */
    private fun discoverNewAccount(
        mapping: FieldMapping,
        values: List<String>,
    ): Pair<NewAccount, DiscoveredAccountMapping>? =
        when (mapping) {
            is AccountLookupMapping -> {
                val csvValue = getColumnValue(mapping.columnName, values)
                // If a persisted mapping matched, don't create a new account
                if (findPersistedMapping(mapping.columnName, csvValue) != null) {
                    null
                } else {
                    val name = getAccountName(mapping, values)
                    if (name.isNotBlank() && !accountExists(name)) {
                        // For AccountLookupMapping, csvValue == name (account name)
                        NewAccount(name, mapping.defaultCategoryId) to
                            DiscoveredAccountMapping(mapping.columnName, csvValue, name)
                    } else {
                        null
                    }
                }
            }
            is RegexAccountMapping -> {
                val result = getAccountNameFromRegexWithPattern(mapping, values)
                // If a persisted mapping matched, don't create a new account
                if (findPersistedMapping(result.sourceColumnName, result.sourceColumnValue) != null) {
                    null
                } else if (result.accountName.isNotBlank() && !accountExists(result.accountName)) {
                    // For RegexAccountMapping, accountName differs from sourceColumnValue
                    // (e.g., sourceColumnValue="Paxos Technology LTD", accountName="Paxos")
                    // Use the actual source column that produced the value (important for fallback)
                    NewAccount(result.accountName, mapping.defaultCategoryId) to
                        DiscoveredAccountMapping(
                            columnName = result.sourceColumnName,
                            csvValue = result.sourceColumnValue,
                            targetAccountName = result.accountName,
                            matchedPattern = result.matchedPattern,
                        )
                } else {
                    null
                }
            }
            is TemplateAccountMapping -> {
                val csvValue = getColumnValue(mapping.columnName, values).trim()
                val name = templatedAccountName(mapping, csvValue)
                if (findPersistedMapping(mapping.columnName, csvValue) != null ||
                    name.isBlank() ||
                    accountExists(name)
                ) {
                    null
                } else {
                    NewAccount(name, mapping.defaultCategoryId) to
                        DiscoveredAccountMapping(mapping.columnName, csvValue, name)
                }
            }
            is ConditionalAccountMapping -> discoverNewAccount(resolveConditional(mapping, values), values)
            else -> null
        }

    /**
     * Gets the effective account name from an AccountLookupMapping,
     * trying the primary column first, then fallbacks in order.
     */
    private fun getAccountName(
        mapping: AccountLookupMapping,
        values: List<String>,
    ): String =
        mapping.allColumns
            .map { getColumnValue(it, values) }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()

    /**
     * Result of resolving a RegexAccountMapping to an account name.
     */
    private data class RegexAccountResult(
        val accountName: String,
        val sourceColumnName: String,
        val sourceColumnValue: String,
        val matchedPattern: String?,
    )

    /**
     * Gets the effective account name and the matched pattern from a RegexAccountMapping.
     * Returns a pair of (accountName, matchedPattern) where matchedPattern is null if
     * no regex rule matched (fallback logic was used).
     */
    private fun getAccountNameFromRegexWithPattern(
        mapping: RegexAccountMapping,
        values: List<String>,
    ): RegexAccountResult {
        val primaryValue = getColumnValue(mapping.columnName, values)

        // Try each rule in order; first match wins
        if (primaryValue.isNotBlank()) {
            for (rule in mapping.rules) {
                val regex = Regex(rule.pattern, RegexOption.IGNORE_CASE)
                if (regex.containsMatchIn(primaryValue)) {
                    return RegexAccountResult(
                        accountName = rule.accountName,
                        sourceColumnName = mapping.columnName,
                        sourceColumnValue = primaryValue,
                        matchedPattern = rule.pattern,
                    )
                }
            }
        }

        // No rules matched - use fallback logic (try columns in order for non-empty value)
        // Track which column provided the value
        for (columnName in mapping.allColumns) {
            val columnValue = getColumnValue(columnName, values)
            if (columnValue.isNotBlank()) {
                return RegexAccountResult(
                    accountName = columnValue,
                    sourceColumnName = columnName,
                    sourceColumnValue = columnValue,
                    matchedPattern = null,
                )
            }
        }

        // No value found in any column
        return RegexAccountResult(
            accountName = "",
            sourceColumnName = mapping.columnName,
            sourceColumnValue = "",
            matchedPattern = null,
        )
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

        val dateTimeFormat = mapping.dateTimeFormat
        return try {
            if (dateTimeFormat != null) {
                // Single column holding a combined date+time value
                LocalDateTime
                    .Format { byUnicodePattern(dateTimeFormat) }
                    .parse(dateValue.trim())
                    .toInstant(timezone)
            } else {
                // Parse date and time using the specified formats
                parseDateTimeString(dateValue, mapping.dateFormat, timeValue, mapping.timeFormat, timezone)
            }
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
    ): TimeZone =
        when (mapping) {
            is HardCodedTimezoneMapping -> TimeZone.of(mapping.timezoneId)
            is TimezoneLookupMapping -> {
                val tzId = getColumnValue(mapping.columnName, values).trim()
                if (tzId.isNotBlank()) TimeZone.of(tzId) else TimeZone.currentSystemDefault()
            }
            else -> TimeZone.currentSystemDefault()
        }

    private fun parseDescription(
        mapping: FieldMapping,
        values: List<String>,
    ): String =
        when (mapping) {
            is DirectColumnMapping -> getDirectColumnValue(mapping, values)
            else -> throw IllegalArgumentException("Invalid description mapping type: ${mapping::class}")
        }

    /**
     * Gets the effective value from a DirectColumnMapping,
     * trying the primary column first, then fallbacks in order.
     */
    private fun getDirectColumnValue(
        mapping: DirectColumnMapping,
        values: List<String>,
    ): String =
        mapping.allColumns
            .map { getColumnValue(it, values) }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()

    private fun parseCurrency(
        mapping: FieldMapping,
        values: List<String>,
    ): Currency? =
        when (mapping) {
            is HardCodedCurrencyMapping -> existingCurrencies[mapping.currencyId]
            is CurrencyLookupMapping -> {
                val code = getColumnValue(mapping.columnName, values).trim().uppercase()
                existingCurrenciesByCode[code]
            }
            else -> throw IllegalArgumentException("Invalid currency mapping type: ${mapping::class}")
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
            value
                .trim()
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
        // First pass: an exact core-field match preserves the existing DUPLICATE/UPDATED distinction.
        for (existingInfo in existingTransfers) {
            val coreFieldsMatch =
                transfer.timestamp == existingInfo.transfer.timestamp &&
                    transfer.sourceAccountId == existingInfo.transfer.sourceAccountId &&
                    transfer.targetAccountId == existingInfo.transfer.targetAccountId &&
                    transfer.amount == existingInfo.transfer.amount &&
                    transfer.description == existingInfo.transfer.description

            if (coreFieldsMatch) {
                val attributesMatch = attributesAreIdentical(attributes, existingInfo.attributes)
                return if (attributesMatch) {
                    ImportStatus.DUPLICATE to existingInfo.transferId
                } else {
                    ImportStatus.UPDATED to existingInfo.transferId
                }
            }
        }

        // Second pass: tolerate the formatting drift in bank re-exports (different trailing text, a
        // posting date shifted by a day or two, and a different counterparty account derived from the
        // varying payee). Same amount + a shared account + close date + similar description = the same
        // transaction, so skip it as a duplicate rather than re-creating it.
        for (existingInfo in existingTransfers) {
            if (isFuzzyDuplicate(transfer, existingInfo.transfer)) {
                return ImportStatus.DUPLICATE to existingInfo.transferId
            }
        }

        // No match found
        return ImportStatus.IMPORTED to null
    }

    private fun isFuzzyDuplicate(
        transfer: Transfer,
        existing: Transfer,
    ): Boolean {
        if (transfer.amount != existing.amount) return false
        val sharesAccount =
            transfer.sourceAccountId == existing.sourceAccountId ||
                transfer.targetAccountId == existing.targetAccountId
        if (!sharesAccount) return false
        val withinDateTolerance =
            (transfer.timestamp - existing.timestamp).absoluteValue <= DUPLICATE_DATE_TOLERANCE
        if (!withinDateTolerance) return false
        return StringSimilarity.similarity(transfer.description, existing.description) >=
            DESCRIPTION_SIMILARITY_THRESHOLD
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

        return attributesAreIdentical(newAttributes, existingAttributes)
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

    private companion object {
        /** Posting dates of the same transaction can drift between bank exports by a day or two. */
        val DUPLICATE_DATE_TOLERANCE: Duration = 3.days
    }
}
