package com.moneymanager.ui.screens.csvstrategy.editor

import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csvstrategy.AccountRulesMapping
import com.moneymanager.domain.model.csvstrategy.AmountMode
import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.AttributeAccountMatch
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvStrategyConfig
import com.moneymanager.domain.model.csvstrategy.CurrencyLookupMapping
import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.DirectColumnMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedAccountMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedCurrencyMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedTimezoneMapping
import com.moneymanager.domain.model.csvstrategy.ReconciliationConfig
import com.moneymanager.domain.model.csvstrategy.StrategyConduit
import com.moneymanager.domain.model.csvstrategy.TimezoneLookupMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.rules.AssetCodeRules
import com.moneymanager.domain.model.rules.Condition
import com.moneymanager.domain.model.rules.ValueExpr
import kotlin.time.Instant

/**
 * Currency mapping mode for CSV import.
 */
internal enum class CurrencyMode {
    HARDCODED,
    FROM_COLUMN,
}

/**
 * Timezone mapping mode for CSV import.
 */
internal enum class TimezoneMode {
    HARDCODED,
    FROM_COLUMN,
}

/**
 * How the source account is resolved: one fixed account, or account rules read off each row.
 */
internal enum class SourceAccountMode {
    FIXED_ACCOUNT,
    RULES,
}

internal fun attributeCandidateColumns(
    csvColumns: List<CsvColumn>,
    primaryFieldColumnNames: Set<String?>,
): List<CsvColumn> {
    val usedPrimaryFieldColumns = primaryFieldColumnNames.filterNotNull().toSet()
    return csvColumns.filter { it.originalName !in usedPrimaryFieldColumns }
}

/** Null unless this column still exists in the uploaded CSV. */
internal fun String?.takeIfPresentIn(columns: Set<String>): String? = this?.takeIf { it in columns }

/** Drops conditions referencing columns absent from the uploaded CSV. */
internal fun List<Condition>?.keepPresentIn(columns: Set<String>): List<Condition> =
    this.orEmpty().filter { it.path in columns && (it.otherPath == null || it.otherPath in columns) }

/**
 * Builds a [CsvImportStrategy] from the editor's live state. Shared by the save handler and tests so
 * the round-trip (load → edit → save) is exercised by a single code path.
 *
 * Required columns (date, description, the amount columns the chosen [AmountMode] needs, and the
 * target column/template depending on mode) must be set; callers gate this behind form validation.
 */
internal fun buildStrategyFromEditorState(
    state: CsvStrategyEditorState,
    id: CsvImportStrategyId,
    createdAt: Instant,
    updatedAt: Instant,
): CsvImportStrategy {
    val fieldMappings =
        buildMap {
            when (state.sourceAccountMode) {
                SourceAccountMode.FIXED_ACCOUNT ->
                    state.selectedAccountId?.let { accountId ->
                        put(
                            TransferField.SOURCE_ACCOUNT,
                            HardCodedAccountMapping(fieldType = TransferField.SOURCE_ACCOUNT, accountId = accountId),
                        )
                    }
                SourceAccountMode.RULES ->
                    put(
                        TransferField.SOURCE_ACCOUNT,
                        AccountRulesMapping(
                            fieldType = TransferField.SOURCE_ACCOUNT,
                            rules = state.sourceRules,
                            defaultCategoryId = state.sourceDefaultCategoryId,
                        ),
                    )
            }
            put(
                TransferField.TARGET_ACCOUNT,
                AccountRulesMapping(
                    fieldType = TransferField.TARGET_ACCOUNT,
                    rules = state.targetRules,
                    defaultCategoryId = state.targetDefaultCategoryId,
                ),
            )
            // The two modes are mutually exclusive: a combined format ignores any separate time
            // column, so null it out to keep the saved mapping consistent with the chosen mode.
            val timeColumnName = state.timeColumnName.takeUnless { state.dateTimeInOneColumn }
            put(
                TransferField.TIMESTAMP,
                DateTimeParsingMapping(
                    fieldType = TransferField.TIMESTAMP,
                    dateColumnName = state.dateColumnName!!,
                    dateFormat = state.dateFormat,
                    timeColumnName = timeColumnName,
                    timeFormat = timeColumnName?.let { state.timeFormat },
                    defaultTime = state.defaultTime,
                    dateTimeFormat = state.dateTimeFormat.takeIf { state.dateTimeInOneColumn && it.isNotBlank() },
                ),
            )
            put(
                TransferField.DESCRIPTION,
                DirectColumnMapping(
                    fieldType = TransferField.DESCRIPTION,
                    value =
                        ValueExpr(
                            listOf(state.descriptionColumnName!!) + state.descriptionFallbackColumns,
                            extraction = state.descriptionExtraction,
                        ),
                ),
            )
            // The two amount modes are mutually exclusive, so only the chosen mode's columns are
            // saved; carrying the other mode's leftovers would make the mapping self-contradictory.
            val singleColumn = state.amountMode == AmountMode.SINGLE_COLUMN
            put(
                TransferField.AMOUNT,
                AmountParsingMapping(
                    fieldType = TransferField.AMOUNT,
                    mode = state.amountMode,
                    amountColumnName = if (singleColumn) state.amountColumnName else null,
                    creditColumnName = if (singleColumn) null else state.creditColumnName,
                    debitColumnName = if (singleColumn) null else state.debitColumnName,
                    direction = state.direction,
                    fee = state.fee,
                    foreignAmount = state.foreignAmount,
                ),
            )
            put(
                TransferField.CURRENCY,
                when (state.currencyMode) {
                    CurrencyMode.HARDCODED ->
                        HardCodedCurrencyMapping(
                            fieldType = TransferField.CURRENCY,
                            currencyId = state.selectedCurrencyId!!,
                        )
                    CurrencyMode.FROM_COLUMN ->
                        CurrencyLookupMapping(
                            fieldType = TransferField.CURRENCY,
                            value =
                                ValueExpr(
                                    listOf(state.currencyColumnName!!) + (state.currencyFallbackColumns - state.currencyColumnName!!),
                                    extraction = state.currencyExtraction,
                                ),
                        )
                },
            )
            putAll(state.tradeCreditMappings)
            put(
                TransferField.TIMEZONE,
                when (state.timezoneMode) {
                    TimezoneMode.HARDCODED ->
                        HardCodedTimezoneMapping(
                            fieldType = TransferField.TIMEZONE,
                            timezoneId = state.selectedTimezone,
                        )
                    TimezoneMode.FROM_COLUMN ->
                        TimezoneLookupMapping(
                            fieldType = TransferField.TIMEZONE,
                            columnName = state.timezoneColumnName!!,
                            aliases = parseAliases(state.timezoneAliasesText, upperCaseValues = false),
                        )
                },
            )
        }
    return CsvImportStrategy(
        id = id,
        name = state.name,
        config =
            CsvStrategyConfig(
                identificationColumns = state.identificationColumns,
                fieldMappings = fieldMappings,
                attributeMappings = state.attributeMappings,
                rowPreprocessingRules = state.rowPreprocessingRules,
                companionTransactionRules = state.companionTransactionRules,
                contentMatchRules = state.contentMatchRules,
                fileNamePattern = state.fileNamePattern.takeIf { it.isNotBlank() },
                crossSourceReconcileWindowSeconds = state.crossSourceReconcileWindowSeconds,
                // A funding match is saved only once a column is chosen; the attribute type always has a value.
                fundingAttributeMatch =
                    state.fundingMatchColumn
                        ?.takeIf { it.isNotBlank() }
                        ?.let { AttributeAccountMatch(column = it, attributeTypeName = state.fundingMatchAttributeTypeName) },
                legGroups = state.legGroups,
                conduit =
                    state.conduitAccountName.trim().takeIf { it.isNotEmpty() }?.let {
                        StrategyConduit(accountName = it, conditions = state.conduitConditions)
                    },
                reconciliation =
                    state.reconciliationSourceName.trim().takeIf { it.isNotEmpty() }?.let { source ->
                        ReconciliationConfig(source, state.reconciliationLinkablePrefix.takeIf { it.isNotEmpty() })
                    },
                assetCodes =
                    AssetCodeRules(
                        aliases = parseAliases(state.assetAliasesText, upperCaseValues = true),
                        stripSuffixes = state.assetSuffixesToStrip,
                    ),
            ),
        worksheetName = state.worksheetName,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

/** Renders aliases (asset codes, timezone abbreviations) as the editor's `FROM=TO, …` text. */
internal fun formatAliases(aliases: Map<String, String>): String =
    aliases.entries.sortedBy { it.key }.joinToString(", ") { "${it.key}=${it.value}" }

/**
 * Parses `FROM=TO, …` into aliases, dropping malformed entries. Keys are upper-cased; values only when
 * [upperCaseValues] (asset codes are, but a zone id like `Europe/London` is case-sensitive).
 */
internal fun parseAliases(
    text: String,
    upperCaseValues: Boolean,
): Map<String, String> =
    text
        .split(',', '\n')
        .mapNotNull { entry ->
            val parts = entry.split('=').map { it.trim() }
            parts.takeIf { it.size == 2 && it.all(String::isNotEmpty) }?.let {
                it[0].uppercase() to if (upperCaseValues) it[1].uppercase() else it[1]
            }
        }.toMap()
