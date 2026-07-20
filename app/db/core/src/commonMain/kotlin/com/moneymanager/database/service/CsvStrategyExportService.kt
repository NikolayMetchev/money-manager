@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.service

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.domain.model.Category
import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.accountmapping.AccountMapping
import com.moneymanager.domain.model.accountmapping.export.AccountMappingExport
import com.moneymanager.domain.model.csvstrategy.AccountLookupMapping
import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.AttributeMatchAccountMapping
import com.moneymanager.domain.model.csvstrategy.ConditionalAccountMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CurrencyLookupMapping
import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.DirectColumnMapping
import com.moneymanager.domain.model.csvstrategy.FieldMapping
import com.moneymanager.domain.model.csvstrategy.FieldMappingId
import com.moneymanager.domain.model.csvstrategy.HardCodedAccountMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedCurrencyMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedTimezoneMapping
import com.moneymanager.domain.model.csvstrategy.RegexAccountMapping
import com.moneymanager.domain.model.csvstrategy.TemplateAccountMapping
import com.moneymanager.domain.model.csvstrategy.TimezoneLookupMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.csvstrategy.export.AccountLookupExport
import com.moneymanager.domain.model.csvstrategy.export.AmountParsingExport
import com.moneymanager.domain.model.csvstrategy.export.AttributeMatchAccountExport
import com.moneymanager.domain.model.csvstrategy.export.ConditionalAccountExport
import com.moneymanager.domain.model.csvstrategy.export.CsvStrategyExport
import com.moneymanager.domain.model.csvstrategy.export.CsvStrategyExportMapper
import com.moneymanager.domain.model.csvstrategy.export.CurrencyLookupExport
import com.moneymanager.domain.model.csvstrategy.export.DateTimeParsingExport
import com.moneymanager.domain.model.csvstrategy.export.DirectColumnExport
import com.moneymanager.domain.model.csvstrategy.export.FieldMappingExport
import com.moneymanager.domain.model.csvstrategy.export.HardCodedAccountExport
import com.moneymanager.domain.model.csvstrategy.export.HardCodedCurrencyExport
import com.moneymanager.domain.model.csvstrategy.export.HardCodedTimezoneExport
import com.moneymanager.domain.model.csvstrategy.export.RegexAccountExport
import com.moneymanager.domain.model.csvstrategy.export.TemplateAccountExport
import com.moneymanager.domain.model.csvstrategy.export.TimezoneLookupExport
import com.moneymanager.domain.repository.AccountMappingReadRepository
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.domain.strategy.CsvStrategyImportResult
import com.moneymanager.importengineapi.AccountMatchKey
import com.moneymanager.importengineapi.ImportAccountIntent
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportCategoryIntent
import com.moneymanager.importengineapi.ImportCurrencyIntent
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportResult
import com.moneymanager.importengineapi.LocalAccountKey
import com.moneymanager.importengineapi.LocalCategoryKey
import com.moneymanager.importengineapi.LocalCurrencyKey
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
 * @property fieldType Which transfer field this reference belongs to, or null for references that are not field-scoped
 */
data class UnresolvedReference(
    val type: ReferenceType,
    val name: String,
    val fieldType: TransferField?,
)

/**
 * How to resolve a missing reference during import.
 */
sealed interface Resolution {
    /**
     * Map to an existing entity by ID.
     */
    data class MapToExisting(
        val id: Long,
    ) : Resolution

    /**
     * Map currency reference to an existing currency by UUID string.
     */
    data class MapToExistingCurrency(
        val id: String,
    ) : Resolution

    /**
     * Create a new entity with the given name.
     */
    data class CreateNew(
        val name: String,
    ) : Resolution
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

private data class StrategyReferenceData(
    val accounts: List<Account>,
    val currencies: List<Currency>,
    val categories: List<Category>,
) {
    val accountsById = accounts.associateBy { it.id }
    val currenciesById = currencies.associateBy { it.id }
    val categoriesById = categories.associateBy { it.id }
    val accountsByName = accounts.associateBy { it.name }
    val currenciesByCode = currencies.associateBy { it.code }
    val categoriesByName = categories.associateBy { it.name }
}

/**
 * Service for converting between domain models and portable export format.
 */
class CsvStrategyExportService(
    private val accountRepository: AccountReadRepository,
    private val currencyRepository: CurrencyReadRepository,
    private val categoryRepository: CategoryReadRepository,
    private val accountMappingRepository: AccountMappingReadRepository,
    private val importEngine: ImportEngine,
) {
    // Entities created while importing a strategy are a manual user action on this device.
    private val source = Source.Manual

    /**
     * Converts a CsvImportStrategy to its portable export format.
     * Resolves all database IDs to human-readable names/codes.
     */
    suspend fun toExport(
        strategy: CsvImportStrategy,
        appVersion: AppVersion,
    ): CsvStrategyExport {
        val referenceData = loadReferenceData()

        return CsvStrategyExportMapper.toExport(
            strategy = strategy,
            version = appVersion.value,
            accountNameById = { referenceData.accountsById[it]?.name },
            currencyCodeById = { referenceData.currenciesById[it]?.code },
            categoryNameById = { referenceData.categoriesById[it]?.name },
            accountMappings = exportPerStrategyMappings(strategy.id, referenceData.accountsById),
        )
    }

    // This strategy's own per-strategy account mappings, by account name, so they travel with the
    // strategy file. Global mappings (strategyId == null) are exported separately.
    private suspend fun exportPerStrategyMappings(
        strategyId: CsvImportStrategyId,
        accountsById: Map<AccountId, Account>,
    ): List<AccountMappingExport> =
        accountMappingRepository
            .getAllMappings()
            .first()
            .filter { it.strategyId == strategyId }
            .map { mapping ->
                AccountMappingExport(
                    valuePattern = mapping.valuePattern.pattern,
                    accountName = accountsById[mapping.accountId]?.name ?: "Unknown Account",
                )
            }

    /**
     * Parses an export and identifies any unresolved references.
     * Does not create the strategy yet - that happens after resolution.
     */
    suspend fun parseExport(export: CsvStrategyExport): ImportParseResult {
        val referenceData = loadReferenceData()

        val unresolvedReferences = mutableListOf<UnresolvedReference>()

        for ((fieldType, mappingExport) in export.fieldMappings) {
            collectUnresolvedReferences(mappingExport, fieldType, referenceData, unresolvedReferences)
        }

        // Per-strategy account mappings reference accounts by name; surface any that don't resolve so
        // the caller can create/map them alongside the field-mapping references.
        for (mapping in export.accountMappings) {
            if (referenceData.accountsByName[mapping.accountName] == null) {
                unresolvedReferences.add(
                    UnresolvedReference(type = ReferenceType.ACCOUNT, name = mapping.accountName, fieldType = null),
                )
            }
        }

        return ImportParseResult(
            strategyName = export.name,
            export = export,
            unresolvedReferences = unresolvedReferences.distinct(),
        )
    }

    private fun collectUnresolvedReferences(
        mappingExport: FieldMappingExport,
        fieldType: TransferField,
        referenceData: StrategyReferenceData,
        unresolvedReferences: MutableList<UnresolvedReference>,
    ) {
        when (mappingExport) {
            is HardCodedAccountExport -> {
                if (referenceData.accountsByName[mappingExport.accountName] == null) {
                    unresolvedReferences.add(
                        UnresolvedReference(
                            type = ReferenceType.ACCOUNT,
                            name = mappingExport.accountName,
                            fieldType = fieldType,
                        ),
                    )
                }
            }
            is AccountLookupExport ->
                addCategoryReferenceIfMissing(mappingExport.defaultCategoryName, fieldType, referenceData, unresolvedReferences)
            is RegexAccountExport ->
                addCategoryReferenceIfMissing(mappingExport.defaultCategoryName, fieldType, referenceData, unresolvedReferences)
            is TemplateAccountExport ->
                addCategoryReferenceIfMissing(mappingExport.defaultCategoryName, fieldType, referenceData, unresolvedReferences)
            is AttributeMatchAccountExport ->
                addCategoryReferenceIfMissing(mappingExport.defaultCategoryName, fieldType, referenceData, unresolvedReferences)
            is ConditionalAccountExport -> {
                collectUnresolvedReferences(mappingExport.whenTrue, fieldType, referenceData, unresolvedReferences)
                collectUnresolvedReferences(mappingExport.whenFalse, fieldType, referenceData, unresolvedReferences)
            }
            is HardCodedCurrencyExport -> {
                if (referenceData.currenciesByCode[mappingExport.currencyCode] == null) {
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

    private fun addCategoryReferenceIfMissing(
        defaultCategoryName: String,
        fieldType: TransferField,
        referenceData: StrategyReferenceData,
        unresolvedReferences: MutableList<UnresolvedReference>,
    ) {
        if (defaultCategoryName != Category.UNCATEGORIZED_NAME &&
            referenceData.categoriesByName[defaultCategoryName] == null
        ) {
            unresolvedReferences.add(
                UnresolvedReference(
                    type = ReferenceType.CATEGORY,
                    name = defaultCategoryName,
                    fieldType = fieldType,
                ),
            )
        }
    }

    private suspend fun loadReferenceData(): StrategyReferenceData =
        StrategyReferenceData(
            accounts = accountRepository.getAllAccounts().first(),
            currencies = currencyRepository.getAllCurrencies().first(),
            categories = categoryRepository.getAllCategories().first(),
        )

    /**
     * Creates a CsvImportStrategy from an export with resolved references, together with its resolved
     * per-strategy account mappings (scoped to the new strategy's id).
     *
     * @param export The export to convert
     * @param resolutions Map of unresolved references to their resolutions
     * @return The (not-yet-saved) strategy and its resolved per-strategy account mappings
     */
    suspend fun createStrategyFromExport(
        export: CsvStrategyExport,
        resolutions: Map<UnresolvedReference, Resolution>,
    ): CsvStrategyImportResult {
        // First, create any new entities that were requested — in one engine batch (the sole writer).
        val accountIntents = mutableMapOf<String, ImportAccountIntent>()
        val categoryIntents = mutableMapOf<String, ImportCategoryIntent>()
        val currencyIntents = mutableMapOf<String, ImportCurrencyIntent>()

        for ((ref, resolution) in resolutions) {
            if (resolution is Resolution.CreateNew) {
                when (ref.type) {
                    ReferenceType.ACCOUNT ->
                        accountIntents[ref.name] =
                            ImportAccountIntent(
                                key = LocalAccountKey(ref.name),
                                source = source,
                                match = AccountMatchKey.AlwaysCreate,
                                name = resolution.name,
                                openingDate = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
                            )
                    ReferenceType.CATEGORY ->
                        categoryIntents[ref.name] =
                            ImportCategoryIntent(key = LocalCategoryKey(ref.name), source = source, name = resolution.name)
                    ReferenceType.CURRENCY ->
                        currencyIntents[ref.name] =
                            ImportCurrencyIntent(
                                key = LocalCurrencyKey(ref.name),
                                source = source,
                                code = resolution.name,
                                name = resolution.name,
                            )
                }
            }
        }

        // Create the requested new entities through the engine (the sole writer); they are then read
        // back below via the re-fetched lookup maps, keyed by the engine-returned ids.
        val importResult =
            importEngine.import(
                ImportBatch(
                    accountsToCreate = accountIntents.values.toList(),
                    categories = categoryIntents.values.toList(),
                    currencies = currencyIntents.values.toList(),
                ),
            )

        // Build lookup maps including both existing and newly created entities
        val accounts = accountRepository.getAllAccounts().first()
        val currencies = currencyRepository.getAllCurrencies().first()
        val categories = categoryRepository.getAllCategories().first()

        val accountsByName = accounts.associateBy { it.name }.toMutableMap()
        val currenciesByCode = currencies.associateBy { it.code }.toMutableMap()
        val categoriesByName = categories.associateBy { it.name }.toMutableMap()

        // Add resolution mappings for MapToExisting
        for ((ref, resolution) in resolutions) {
            when (resolution) {
                is Resolution.MapToExisting -> {
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
                        ReferenceType.CURRENCY -> Unit
                    }
                }
                is Resolution.MapToExistingCurrency -> {
                    if (ref.type == ReferenceType.CURRENCY) {
                        val currency = currencies.find { it.id.id.toString() == resolution.id }
                        if (currency != null) {
                            currenciesByCode[ref.name] = currency
                        }
                    }
                }
                is Resolution.CreateNew ->
                    aliasCreatedEntity(
                        ref,
                        importResult,
                        accounts,
                        categories,
                        currencies,
                        accountsByName,
                        categoriesByName,
                        currenciesByCode,
                    )
            }
        }

        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())

        val strategy =
            CsvImportStrategy(
                id = CsvImportStrategyId(Uuid.random()),
                name = export.name,
                identificationColumns = export.identificationColumns,
                fieldMappings =
                    export.fieldMappings.mapValues { (_, mappingExport) ->
                        mappingExport.toDomain(accountsByName, currenciesByCode, categoriesByName)
                    },
                attributeMappings = export.attributeMappings,
                rowPreprocessingRules = export.rowPreprocessingRules,
                companionTransactionRules = export.companionTransactionRules,
                contentMatchRules = export.contentMatchRules,
                fileNamePattern = export.fileNamePattern,
                crossSourceReconcileWindowSeconds = export.crossSourceReconcileWindowSeconds,
                conversionConfig = export.conversionConfig,
                fundingAttributeMatch = export.fundingAttributeMatch,
                worksheetName = export.worksheetName,
                createdAt = now,
                updatedAt = now,
            )

        // Resolve the embedded per-strategy mappings against the same account lookup (existing +
        // created + MapToExisting). strategy.id is the id the engine will persist under, so scope to it.
        // Fail loudly like the field-mapping path: silently dropping a mapping would import a strategy
        // that's missing part of itself with no signal to the caller.
        val accountMappings =
            export.accountMappings
                // Legacy exports carried a column per mapping, so the same pattern could appear once
                // per column; without columns those would collide on the unique constraint. First wins.
                .distinctBy { it.valuePattern }
                .map { mappingExport ->
                    val account =
                        accountsByName[mappingExport.accountName]
                            ?: error("Account not found: ${mappingExport.accountName}")
                    AccountMapping(
                        id = 0,
                        strategyId = strategy.id,
                        valuePattern = Regex(mappingExport.valuePattern, RegexOption.IGNORE_CASE),
                        accountId = account.id,
                        createdAt = now,
                        updatedAt = now,
                    )
                }

        return CsvStrategyImportResult(strategy = strategy, accountMappings = accountMappings)
    }

    // A new entity is created under its (possibly renamed) resolution name, but the export's field
    // mappings still reference it by the original ref.name. Alias ref.name to the just-created entity
    // (looked up by the engine-returned id) so toDomain resolves it instead of failing/UNCATEGORIZED.
    @Suppress("LongParameterList")
    private fun aliasCreatedEntity(
        ref: UnresolvedReference,
        importResult: ImportResult,
        accounts: List<Account>,
        categories: List<Category>,
        currencies: List<Currency>,
        accountsByName: MutableMap<String, Account>,
        categoriesByName: MutableMap<String, Category>,
        currenciesByCode: MutableMap<String, Currency>,
    ) {
        when (ref.type) {
            ReferenceType.ACCOUNT -> {
                val id = importResult.createdAccountIds[LocalAccountKey(ref.name)] ?: return
                accounts.find { it.id == id }?.let { accountsByName[ref.name] = it }
            }
            ReferenceType.CATEGORY -> {
                val id = importResult.createdCategoryIds[LocalCategoryKey(ref.name)] ?: return
                categories.find { it.id == id }?.let { categoriesByName[ref.name] = it }
            }
            ReferenceType.CURRENCY -> {
                val id = importResult.createdCurrencyIds[LocalCurrencyKey(ref.name)] ?: return
                currencies.find { it.id == id }?.let { currenciesByCode[ref.name] = it }
            }
        }
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
            is AttributeMatchAccountExport ->
                AttributeMatchAccountMapping(
                    id = newId,
                    fieldType = fieldType,
                    columnName = columnName,
                    attributeTypeName = attributeTypeName,
                    defaultCategoryId =
                        categoriesByName[defaultCategoryName]?.id
                            ?: Category.UNCATEGORIZED_ID,
                )
            is TemplateAccountExport ->
                TemplateAccountMapping(
                    id = newId,
                    fieldType = fieldType,
                    columnName = columnName,
                    prefix = prefix,
                    suffix = suffix,
                    defaultCategoryId =
                        categoriesByName[defaultCategoryName]?.id
                            ?: Category.UNCATEGORIZED_ID,
                )
            is ConditionalAccountExport ->
                ConditionalAccountMapping(
                    id = newId,
                    fieldType = fieldType,
                    conditions = conditions,
                    whenTrue = whenTrue.toDomain(accountsByName, currenciesByCode, categoriesByName),
                    whenFalse = whenFalse.toDomain(accountsByName, currenciesByCode, categoriesByName),
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
                    dateTimeFormat = dateTimeFormat,
                )
            is DirectColumnExport ->
                DirectColumnMapping(
                    id = newId,
                    fieldType = fieldType,
                    columnName = columnName,
                    fallbackColumns = fallbackColumns,
                    extraction = extraction,
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
                    feeColumnName = feeColumnName,
                    feeConditions = feeConditions,
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
