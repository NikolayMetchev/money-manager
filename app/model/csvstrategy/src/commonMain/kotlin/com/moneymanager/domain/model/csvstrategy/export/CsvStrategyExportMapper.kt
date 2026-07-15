package com.moneymanager.domain.model.csvstrategy.export

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Category
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.accountmapping.export.AccountMappingExport
import com.moneymanager.domain.model.csvstrategy.AccountLookupMapping
import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.ConditionalAccountMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CurrencyLookupMapping
import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.DirectColumnMapping
import com.moneymanager.domain.model.csvstrategy.FieldMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedAccountMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedCurrencyMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedTimezoneMapping
import com.moneymanager.domain.model.csvstrategy.RegexAccountMapping
import com.moneymanager.domain.model.csvstrategy.TemplateAccountMapping
import com.moneymanager.domain.model.csvstrategy.TimezoneLookupMapping

/**
 * Pure domain-to-export mapping for CSV strategies (no repositories): the caller supplies whatever
 * id-to-name lookups its context has. Shared by the DB-backed export service and the DB-free catalog
 * generator so both render identical artifacts.
 */
object CsvStrategyExportMapper {
    fun toExport(
        strategy: CsvImportStrategy,
        version: String,
        accountNameById: (AccountId) -> String?,
        currencyCodeById: (CurrencyId) -> String?,
        categoryNameById: (Long) -> String?,
        accountMappings: List<AccountMappingExport> = emptyList(),
    ): CsvStrategyExport =
        CsvStrategyExport(
            version = version,
            name = strategy.name,
            identificationColumns = strategy.identificationColumns,
            fieldMappings =
                strategy.fieldMappings.mapValues { (_, mapping) ->
                    mapping.toExport(accountNameById, currencyCodeById, categoryNameById)
                },
            attributeMappings = strategy.attributeMappings,
            rowPreprocessingRules = strategy.rowPreprocessingRules,
            companionTransactionRules = strategy.companionTransactionRules,
            contentMatchRules = strategy.contentMatchRules,
            accountMappings = accountMappings,
            fileNamePattern = strategy.fileNamePattern,
            crossSourceReconcileWindowSeconds = strategy.crossSourceReconcileWindowSeconds,
            conversionConfig = strategy.conversionConfig,
            fundingCardColumn = strategy.fundingCardColumn,
        )

    private fun FieldMapping.toExport(
        accountNameById: (AccountId) -> String?,
        currencyCodeById: (CurrencyId) -> String?,
        categoryNameById: (Long) -> String?,
    ): FieldMappingExport =
        when (this) {
            is HardCodedAccountMapping ->
                HardCodedAccountExport(
                    fieldType = fieldType,
                    accountName = accountNameById(accountId) ?: "Unknown Account",
                )
            is AccountLookupMapping ->
                AccountLookupExport(
                    fieldType = fieldType,
                    columnName = columnName,
                    fallbackColumns = fallbackColumns,
                    defaultCategoryName = categoryNameById(defaultCategoryId) ?: Category.UNCATEGORIZED_NAME,
                )
            is RegexAccountMapping ->
                RegexAccountExport(
                    fieldType = fieldType,
                    columnName = columnName,
                    rules = rules,
                    fallbackColumns = fallbackColumns,
                    defaultCategoryName = categoryNameById(defaultCategoryId) ?: Category.UNCATEGORIZED_NAME,
                )
            is TemplateAccountMapping ->
                TemplateAccountExport(
                    fieldType = fieldType,
                    columnName = columnName,
                    prefix = prefix,
                    suffix = suffix,
                    defaultCategoryName = categoryNameById(defaultCategoryId) ?: Category.UNCATEGORIZED_NAME,
                )
            is ConditionalAccountMapping ->
                ConditionalAccountExport(
                    fieldType = fieldType,
                    conditions = conditions,
                    whenTrue = whenTrue.toExport(accountNameById, currencyCodeById, categoryNameById),
                    whenFalse = whenFalse.toExport(accountNameById, currencyCodeById, categoryNameById),
                )
            is DateTimeParsingMapping ->
                DateTimeParsingExport(
                    fieldType = fieldType,
                    dateColumnName = dateColumnName,
                    dateFormat = dateFormat,
                    timeColumnName = timeColumnName,
                    timeFormat = timeFormat,
                    defaultTime = defaultTime,
                    dateTimeFormat = dateTimeFormat,
                )
            is DirectColumnMapping ->
                DirectColumnExport(
                    fieldType = fieldType,
                    columnName = columnName,
                    fallbackColumns = fallbackColumns,
                    extraction = extraction,
                )
            is AmountParsingMapping ->
                AmountParsingExport(
                    fieldType = fieldType,
                    mode = mode,
                    amountColumnName = amountColumnName,
                    creditColumnName = creditColumnName,
                    debitColumnName = debitColumnName,
                    negateValues = negateValues,
                    flipAccountsOnPositive = flipAccountsOnPositive,
                    feeColumnName = feeColumnName,
                    feeConditions = feeConditions,
                )
            is HardCodedCurrencyMapping ->
                HardCodedCurrencyExport(
                    fieldType = fieldType,
                    currencyCode = currencyCodeById(currencyId) ?: "XXX",
                )
            is CurrencyLookupMapping ->
                CurrencyLookupExport(
                    fieldType = fieldType,
                    columnName = columnName,
                )
            is HardCodedTimezoneMapping ->
                HardCodedTimezoneExport(
                    fieldType = fieldType,
                    timezoneId = timezoneId,
                )
            is TimezoneLookupMapping ->
                TimezoneLookupExport(
                    fieldType = fieldType,
                    columnName = columnName,
                )
        }
}
