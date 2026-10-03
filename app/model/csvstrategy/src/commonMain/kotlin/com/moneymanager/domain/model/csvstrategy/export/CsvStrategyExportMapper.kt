package com.moneymanager.domain.model.csvstrategy.export

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Category
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.accountmapping.export.AccountMappingExport
import com.moneymanager.domain.model.csvstrategy.AccountRulesMapping
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
            config = strategy.config.mapFieldMappings { it.toExport(accountNameById, currencyCodeById, categoryNameById) },
            accountMappings = accountMappings,
            worksheetName = strategy.worksheetName,
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
            is AccountRulesMapping ->
                AccountRulesExport(
                    fieldType = fieldType,
                    rules = rules,
                    defaultCategoryName = categoryNameById(defaultCategoryId) ?: Category.UNCATEGORIZED_NAME,
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
                    value = value,
                )
            is AmountParsingMapping ->
                AmountParsingExport(
                    fieldType = fieldType,
                    mode = mode,
                    amountColumnName = amountColumnName,
                    creditColumnName = creditColumnName,
                    debitColumnName = debitColumnName,
                    direction = direction,
                    feeColumnName = feeColumnName,
                    feeConditions = feeConditions,
                    feeCurrency = feeCurrency,
                )
            is HardCodedCurrencyMapping ->
                HardCodedCurrencyExport(
                    fieldType = fieldType,
                    currencyCode = currencyCodeById(currencyId) ?: "XXX",
                )
            is CurrencyLookupMapping ->
                CurrencyLookupExport(
                    fieldType = fieldType,
                    value = value,
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
