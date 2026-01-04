@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.service

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.domain.model.Category
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.csvstrategy.AccountLookupMapping
import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyId
import com.moneymanager.domain.model.csvstrategy.CurrencyLookupMapping
import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.DirectColumnMapping
import com.moneymanager.domain.model.csvstrategy.FieldMapping
import com.moneymanager.domain.model.csvstrategy.FieldMappingId
import com.moneymanager.domain.model.csvstrategy.HardCodedAccountMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedCurrencyMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedTimezoneMapping
import com.moneymanager.domain.model.csvstrategy.RegexAccountMapping
import com.moneymanager.domain.model.csvstrategy.TimezoneLookupMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.csvstrategy.export.AccountLookupExport
import com.moneymanager.domain.model.csvstrategy.export.AmountParsingExport
import com.moneymanager.domain.model.csvstrategy.export.CsvStrategyExport
import com.moneymanager.domain.model.csvstrategy.export.CurrencyLookupExport
import com.moneymanager.domain.model.csvstrategy.export.DateTimeParsingExport
import com.moneymanager.domain.model.csvstrategy.export.DirectColumnExport
import com.moneymanager.domain.model.csvstrategy.export.FieldMappingExport
import com.moneymanager.domain.model.csvstrategy.export.HardCodedAccountExport
import com.moneymanager.domain.model.csvstrategy.export.HardCodedCurrencyExport
import com.moneymanager.domain.model.csvstrategy.export.HardCodedTimezoneExport
import com.moneymanager.domain.model.csvstrategy.export.RegexAccountExport
import com.moneymanager.domain.model.csvstrategy.export.TimezoneLookupExport
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.CategoryRepository
import com.moneymanager.domain.repository.CurrencyRepository
import kotlinx.coroutines.flow.first
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Type of reference that needs to be resolved during import.
 */
enum class ReferenceType {
    ACCOUNT,
    CURRENCY,
    CATEGORY,
}

/**
 * An unresolved reference found during import.
 *
 * @property type The type of reference (account, currency, category)
 * @property name The name/code from the export that couldn't be resolved
 * @property fieldType Which transfer field this reference belongs to
 */
data class UnresolvedReference(
    val type: ReferenceType,
    val name: String,
    val fieldType: TransferField,
)

/**
 * How to resolve a missing reference during import.
 */
sealed interface Resolution {
    /**
     * Map to an existing entity by ID.
     */
    data class MapToExisting(val id: Long) : Resolution

    /**
     * Create a new entity with the given name.
     */
    data class CreateNew(val name: String) : Resolution
}

/**
 * Result of parsing an export file, before resolution.
 *
 * @property strategyName Name of the strategy being imported
 * @property export The parsed export data
 * @property unresolvedReferences List of references that need resolution
 */
data class ImportParseResult(
    val strategyName: String,
    val export: CsvStrategyExport,
    val unresolvedReferences: List<UnresolvedReference>,
)

/**
 * Service for converting between domain models and portable export format.
 */
class CsvStrategyExportService(
    private val accountRepository: AccountRepository,
    private val currencyRepository: CurrencyRepository,
    private val categoryRepository: CategoryRepository,
) {
    /**
     * Converts a CsvImportStrategy to its portable export format.
     * Resolves all database IDs to human-readable names/codes.
     */
    suspend fun toExport(
        strategy: CsvImportStrategy,
        appVersion: AppVersion,
    ): CsvStrategyExport {
        val accounts = accountRepository.getAllAccounts().first()
        val currencies = currencyRepository.getAllCurrencies().first()
        val categories = categoryRepository.getAllCategories().first()

        val accountsById = accounts.associateBy { it.id }
        val currenciesById = currencies.associateBy { it.id }
        val categoriesById = categories.associateBy { it.id }

        return CsvStrategyExport(
            version = appVersion.value,
            name = strategy.name,
            identificationColumns = strategy.identificationColumns,
            fieldMappings =
                strategy.fieldMappings.mapValues { (_, mapping) ->
                    mapping.toExport(accountsById, currenciesById, categoriesById)
                },
            attributeMappings = strategy.attributeMappings,
        )
    }

    /**
     * Parses an export and identifies any unresolved references.
     * Does not create the strategy yet - that happens after resolution.
     */
    suspend fun parseExport(export: CsvStrategyExport): ImportParseResult {
        val accounts = accountRepository.getAllAccounts().first()
        val currencies = currencyRepository.getAllCurrencies().first()
        val categories = categoryRepository.getAllCategories().first()

        val accountsByName = accounts.associateBy { it.name }
        val currenciesByCode = currencies.associateBy { it.code }
        val categoriesByName = categories.associateBy { it.name }

        val unresolvedReferences = mutableListOf<UnresolvedReference>()

        for ((fieldType, mappingExport) in export.fieldMappings) {
            when (mappingExport) {
                is HardCodedAccountExport -> {
                    if (accountsByName[mappingExport.accountName] == null) {
                        unresolvedReferences.add(
                            UnresolvedReference(
                                type = ReferenceType.ACCOUNT,
                                name = mappingExport.accountName,
                                fieldType = fieldType,
                            ),
                        )
                    }
                }
                is AccountLookupExport -> {
                    if (mappingExport.defaultCategoryName != Category.UNCATEGORIZED_NAME &&
                        categoriesByName[mappingExport.defaultCategoryName] == null
                    ) {
                        unresolvedReferences.add(
                            UnresolvedReference(
                                type = ReferenceType.CATEGORY,
                                name = mappingExport.defaultCategoryName,
                                fieldType = fieldType,
                            ),
                        )
                    }
                }
                is RegexAccountExport -> {
                    if (mappingExport.defaultCategoryName != Category.UNCATEGORIZED_NAME &&
                        categoriesByName[mappingExport.defaultCategoryName] == null
                    ) {
                        unresolvedReferences.add(
                            UnresolvedReference(
                                type = ReferenceType.CATEGORY,
                                name = mappingExport.defaultCategoryName,
                                fieldType = fieldType,
                            ),
                        )
                    }
                }
                is HardCodedCurrencyExport -> {
                    if (currenciesByCode[mappingExport.currencyCode] == null) {
                        unresolvedReferences.add(
                            UnresolvedReference(
                                type = ReferenceType.CURRENCY,
                                name = mappingExport.currencyCode,
                                fieldType = fieldType,
                            ),
                        )
                    }
                }
                // These don't have ID references
                is DateTimeParsingExport,
                is DirectColumnExport,
                is AmountParsingExport,
                is CurrencyLookupExport,
                is HardCodedTimezoneExport,
                is TimezoneLookupExport,
                -> Unit
            }
        }

        return ImportParseResult(
            strategyName = export.name,
            export = export,
            unresolvedReferences = unresolvedReferences.distinctBy { it.name to it.type },
        )
    }

    /**
     * Creates a CsvImportStrategy from an export with resolved references.
     *
     * @param export The export to convert
     * @param resolutions Map of unresolved references to their resolutions
     * @return The created strategy (not yet saved to database)
     */
    suspend fun createStrategyFromExport(
        export: CsvStrategyExport,
        resolutions: Map<UnresolvedReference, Resolution>,
    ): CsvImportStrategy {
        // First, create any new entities that were requested
        val createdAccounts = mutableMapOf<String, AccountId>()
        val createdCategories = mutableMapOf<String, Long>()
        val createdCurrencies = mutableMapOf<String, CurrencyId>()

        for ((ref, resolution) in resolutions) {
            if (resolution is Resolution.CreateNew) {
                when (ref.type) {
                    ReferenceType.ACCOUNT -> {
                        val account =
                            Account(
                                id = AccountId(0),
                                name = resolution.name,
                                openingDate = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
                            )
                        val id = accountRepository.createAccount(account)
                        createdAccounts[ref.name] = id
                    }
                    ReferenceType.CATEGORY -> {
                        val category = Category(name = resolution.name)
                        val id = categoryRepository.createCategory(category)
                        createdCategories[ref.name] = id
                    }
                    ReferenceType.CURRENCY -> {
                        val id =
                            currencyRepository.upsertCurrencyByCode(
                                code = resolution.name,
                                name = resolution.name,
                            )
                        createdCurrencies[ref.name] = id
                    }
                }
            }
        }

        // Build lookup maps including both existing and newly created entities
        val accounts = accountRepository.getAllAccounts().first()
        val currencies = currencyRepository.getAllCurrencies().first()
        val categories = categoryRepository.getAllCategories().first()

        val accountsByName = accounts.associateBy { it.name }.toMutableMap()
        val currenciesByCode = currencies.associateBy { it.code }.toMutableMap()
        val categoriesByName = categories.associateBy { it.name }.toMutableMap()

        // Add resolution mappings for MapToExisting
        for ((ref, resolution) in resolutions) {
            if (resolution is Resolution.MapToExisting) {
                when (ref.type) {
                    ReferenceType.ACCOUNT -> {
                        val account = accounts.find { it.id.id == resolution.id }
                        if (account != null) {
                            accountsByName[ref.name] = account
                        }
                    }
                    ReferenceType.CATEGORY -> {
                        val category = categories.find { it.id == resolution.id }
                        if (category != null) {
                            categoriesByName[ref.name] = category
                        }
                    }
                    ReferenceType.CURRENCY -> {
                        val currency = currencies.find { it.id.id.toString() == resolution.id.toString() }
                        if (currency != null) {
                            currenciesByCode[ref.name] = currency
                        }
                    }
                }
            }
        }

        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())

        return CsvImportStrategy(
            id = CsvImportStrategyId(Uuid.random()),
            name = export.name,
            identificationColumns = export.identificationColumns,
            fieldMappings =
                export.fieldMappings.mapValues { (_, mappingExport) ->
                    mappingExport.toDomain(accountsByName, currenciesByCode, categoriesByName)
                },
            attributeMappings = export.attributeMappings,
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun FieldMapping.toExport(
        accountsById: Map<AccountId, Account>,
        currenciesById: Map<CurrencyId, Currency>,
        categoriesById: Map<Long, Category>,
    ): FieldMappingExport =
        when (this) {
            is HardCodedAccountMapping ->
                HardCodedAccountExport(
                    fieldType = fieldType,
                    accountName = accountsById[accountId]?.name ?: "Unknown Account",
                )
            is AccountLookupMapping ->
                AccountLookupExport(
                    fieldType = fieldType,
                    columnName = columnName,
                    fallbackColumns = fallbackColumns,
                    defaultCategoryName =
                        categoriesById[defaultCategoryId]?.name
                            ?: Category.UNCATEGORIZED_NAME,
                )
            is RegexAccountMapping ->
                RegexAccountExport(
                    fieldType = fieldType,
                    columnName = columnName,
                    rules = rules,
                    fallbackColumns = fallbackColumns,
                    defaultCategoryName =
                        categoriesById[defaultCategoryId]?.name
                            ?: Category.UNCATEGORIZED_NAME,
                )
            is DateTimeParsingMapping ->
                DateTimeParsingExport(
                    fieldType = fieldType,
                    dateColumnName = dateColumnName,
                    dateFormat = dateFormat,
                    timeColumnName = timeColumnName,
                    timeFormat = timeFormat,
                    defaultTime = defaultTime,
                )
            is DirectColumnMapping ->
                DirectColumnExport(
                    fieldType = fieldType,
                    columnName = columnName,
                    fallbackColumns = fallbackColumns,
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
                )
            is HardCodedCurrencyMapping ->
                HardCodedCurrencyExport(
                    fieldType = fieldType,
                    currencyCode = currenciesById[currencyId]?.code ?: "XXX",
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

    private fun FieldMappingExport.toDomain(
        accountsByName: Map<String, Account>,
        currenciesByCode: Map<String, Currency>,
        categoriesByName: Map<String, Category>,
    ): FieldMapping {
        val newId = FieldMappingId(Uuid.random())

        return when (this) {
            is HardCodedAccountExport ->
                HardCodedAccountMapping(
                    id = newId,
                    fieldType = fieldType,
                    accountId =
                        accountsByName[accountName]?.id
                            ?: error("Account not found: $accountName"),
                )
            is AccountLookupExport ->
                AccountLookupMapping(
                    id = newId,
                    fieldType = fieldType,
                    columnName = columnName,
                    fallbackColumns = fallbackColumns,
                    defaultCategoryId =
                        categoriesByName[defaultCategoryName]?.id
                            ?: Category.UNCATEGORIZED_ID,
                )
            is RegexAccountExport ->
                RegexAccountMapping(
                    id = newId,
                    fieldType = fieldType,
                    columnName = columnName,
                    rules = rules,
                    fallbackColumns = fallbackColumns,
                    defaultCategoryId =
                        categoriesByName[defaultCategoryName]?.id
                            ?: Category.UNCATEGORIZED_ID,
                )
            is DateTimeParsingExport ->
                DateTimeParsingMapping(
                    id = newId,
                    fieldType = fieldType,
                    dateColumnName = dateColumnName,
                    dateFormat = dateFormat,
                    timeColumnName = timeColumnName,
                    timeFormat = timeFormat,
                    defaultTime = defaultTime,
                )
            is DirectColumnExport ->
                DirectColumnMapping(
                    id = newId,
                    fieldType = fieldType,
                    columnName = columnName,
                    fallbackColumns = fallbackColumns,
                )
            is AmountParsingExport ->
                AmountParsingMapping(
                    id = newId,
                    fieldType = fieldType,
                    mode = mode,
                    amountColumnName = amountColumnName,
                    creditColumnName = creditColumnName,
                    debitColumnName = debitColumnName,
                    negateValues = negateValues,
                    flipAccountsOnPositive = flipAccountsOnPositive,
                )
            is HardCodedCurrencyExport ->
                HardCodedCurrencyMapping(
                    id = newId,
                    fieldType = fieldType,
                    currencyId =
                        currenciesByCode[currencyCode]?.id
                            ?: error("Currency not found: $currencyCode"),
                )
            is CurrencyLookupExport ->
                CurrencyLookupMapping(
                    id = newId,
                    fieldType = fieldType,
                    columnName = columnName,
                )
            is HardCodedTimezoneExport ->
                HardCodedTimezoneMapping(
                    id = newId,
                    fieldType = fieldType,
                    timezoneId = timezoneId,
                )
            is TimezoneLookupExport ->
                TimezoneLookupMapping(
                    id = newId,
                    fieldType = fieldType,
                    columnName = columnName,
                )
        }
    }
}
