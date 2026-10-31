package com.moneymanager.database.service

import com.moneymanager.database.json.AccountMappingExportCodec
import com.moneymanager.database.json.ApiStrategyExportCodec
import com.moneymanager.database.json.CsvStrategyExportCodec
import com.moneymanager.database.json.CurrencyListExportCodec
import com.moneymanager.database.json.HolidayCalendarExportCodec
import com.moneymanager.database.json.PassThroughExportCodec
import com.moneymanager.database.json.StrategyArtifactCodec
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.domain.model.CurrencyExportEntry
import com.moneymanager.domain.model.CurrencyListExport
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.calendar.HolidayCalendarExport
import com.moneymanager.domain.model.csvstrategy.export.CsvStrategyExport
import com.moneymanager.domain.model.passthrough.PassThroughAccount
import com.moneymanager.domain.model.passthrough.PassThroughAccountId
import com.moneymanager.domain.model.passthrough.export.PassThroughExport
import com.moneymanager.domain.repository.AccountMappingReadRepository
import com.moneymanager.domain.repository.ApiImportStrategyReadRepository
import com.moneymanager.domain.repository.CsvImportStrategyReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.domain.repository.HolidayCalendarReadRepository
import com.moneymanager.domain.repository.PassThroughAccountReadRepository
import com.moneymanager.domain.strategy.CsvReferenceType
import com.moneymanager.domain.strategy.CsvResolution
import com.moneymanager.domain.strategy.CsvUnresolvedReference
import com.moneymanager.domain.strategy.LocalStrategyEntry
import com.moneymanager.domain.strategy.StrategyFileNaming
import com.moneymanager.domain.strategy.StrategyKey
import com.moneymanager.domain.strategy.StrategyKind
import com.moneymanager.domain.strategy.StrategyLibrary
import com.moneymanager.domain.strategy.StrategyParseResult
import com.moneymanager.domain.strategy.libraryKind
import com.moneymanager.importengineapi.AccountMappingMutation
import com.moneymanager.importengineapi.CsvStrategyMutation
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportCurrencyIntent
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.LocalCurrencyKey
import com.moneymanager.importengineapi.createApiStrategy
import com.moneymanager.importengineapi.createPassThroughAccount
import com.moneymanager.importengineapi.updateApiStrategy
import com.moneymanager.importengineapi.updatePassThroughAccount
import com.moneymanager.importengineapi.upsertHolidayCalendar
import kotlinx.coroutines.flow.first

/**
 * Local half of the strategy-sync feature: renders the database's strategies + global account mappings
 * to portable JSON artifacts, and applies incoming artifacts back (create-or-update keyed by name, so a
 * re-import never duplicates). Composes the existing per-kind export services and the sole-writer engine.
 */
class StrategyLibraryService(
    private val csvStrategyRepository: CsvImportStrategyReadRepository,
    private val apiStrategyRepository: ApiImportStrategyReadRepository,
    private val accountMappingRepository: AccountMappingReadRepository,
    private val passThroughAccountRepository: PassThroughAccountReadRepository,
    private val currencyRepository: CurrencyReadRepository,
    private val holidayCalendarRepository: HolidayCalendarReadRepository,
    private val csvStrategyExportService: CsvStrategyExportService,
    private val apiStrategyExportService: ApiStrategyExportService,
    private val accountMappingExportService: AccountMappingExportService,
    private val importEngine: ImportEngine,
) : StrategyLibrary {
    override suspend fun listLocal(appVersion: AppVersion): List<LocalStrategyEntry> {
        val entries = mutableListOf<LocalStrategyEntry>()

        for (strategy in csvStrategyRepository.getAllStrategies().first()) {
            val json = CsvStrategyExportCodec.encode(csvStrategyExportService.toExport(strategy, appVersion))
            entries += entry(StrategyKey(strategy.libraryKind(), strategy.name), json)
        }

        for (strategy in apiStrategyRepository.getAllStrategies().first()) {
            val json = ApiStrategyExportCodec.encode(apiStrategyExportService.toExport(strategy, appVersion))
            entries += entry(StrategyKey(StrategyKind.API, strategy.name), json)
        }

        for (definition in passThroughAccountRepository.getAll().first()) {
            val json = PassThroughExportCodec.encode(definition.toExport(appVersion))
            entries += entry(StrategyKey(StrategyKind.PASS_THROUGH, definition.name), json)
        }

        val globalMappings = accountMappingRepository.getAllMappings().first().filter { it.strategyId == null }
        if (globalMappings.isNotEmpty()) {
            val json = AccountMappingExportCodec.encode(accountMappingExportService.toExport(globalMappings, appVersion))
            entries += entry(StrategyKey(StrategyKind.GLOBAL_MAPPINGS, StrategyFileNaming.GLOBAL_MAPPINGS_NAME), json)
        }

        val listCurrencies = currencyRepository.getSystemCreatedCurrencies().first()
        if (listCurrencies.isNotEmpty()) {
            val export =
                CurrencyListExport(
                    version = appVersion.value,
                    name = StrategyFileNaming.CURRENCIES_NAME,
                    currencies = listCurrencies.map { CurrencyExportEntry(it.code, it.name) },
                )
            entries +=
                entry(StrategyKey(StrategyKind.CURRENCIES, StrategyFileNaming.CURRENCIES_NAME), CurrencyListExportCodec.encode(export))
        }

        for (calendar in holidayCalendarRepository.getAll().first()) {
            val json = HolidayCalendarExportCodec.encode(HolidayCalendarExport(version = appVersion.value, calendar = calendar))
            entries += entry(StrategyKey(StrategyKind.HOLIDAY_CALENDAR, calendar.id), json)
        }

        return entries
    }

    override suspend fun parseIncoming(
        key: StrategyKey,
        json: String,
    ): StrategyParseResult =
        when (key.kind) {
            StrategyKind.CSV, StrategyKind.QIF, StrategyKind.XLSX, StrategyKind.PDF -> {
                val export = CsvStrategyExportCodec.decode(json)
                val refs = csvStrategyExportService.parseExport(export).unresolvedReferences
                StrategyParseResult(key, refs)
            }

            // API strategies, pass-through definitions, the currency list and locations carry no entity references.
            StrategyKind.API, StrategyKind.PASS_THROUGH, StrategyKind.CURRENCIES, StrategyKind.HOLIDAY_CALENDAR -> {
                StrategyParseResult(key, emptyList())
            }

            StrategyKind.GLOBAL_MAPPINGS -> {
                val export = AccountMappingExportCodec.decode(json)
                val refs =
                    accountMappingExportService.parseExport(export).unresolvedAccountNames.map {
                        CsvUnresolvedReference(CsvReferenceType.ACCOUNT, it, null)
                    }
                StrategyParseResult(key, refs)
            }
        }

    override suspend fun applyIncoming(
        key: StrategyKey,
        json: String,
        resolutions: Map<CsvUnresolvedReference, CsvResolution>,
    ) {
        when (key.kind) {
            StrategyKind.CSV, StrategyKind.QIF, StrategyKind.XLSX, StrategyKind.PDF -> applyCsv(key.name, json, resolutions)
            StrategyKind.API -> applyApi(key.name, json)
            StrategyKind.PASS_THROUGH -> applyPassThrough(key.name, json)
            StrategyKind.GLOBAL_MAPPINGS -> applyGlobalMappings(json, resolutions)
            StrategyKind.CURRENCIES -> applyCurrencies(json)
            StrategyKind.HOLIDAY_CALENDAR -> applyHolidayCalendar(key.name, json)
        }
    }

    /**
     * Union semantics: adds every listed code the database lacks, as SYSTEM so it joins the local list.
     * Existing currencies are never renamed or removed (transfers reference them), so a code dropped
     * from the published list keeps the local list differing until the user deletes it.
     */
    private suspend fun applyCurrencies(json: String) {
        val existing =
            currencyRepository
                .getAllCurrencies()
                .first()
                .map { it.code }
                .toSet()
        val missing = CurrencyListExportCodec.decode(json).currencies.filter { it.code !in existing }
        if (missing.isEmpty()) return
        importEngine.import(
            ImportBatch(
                currencies =
                    missing.map {
                        ImportCurrencyIntent(key = LocalCurrencyKey(it.code), source = Source.System, code = it.code, name = it.name)
                    },
            ),
        )
    }

    /**
     * The file is authoritative for its location, as for a strategy: it replaces the database's copy, holidays and
     * all, keyed by the filename's code. Accounts name a location by code, so they keep pointing at it.
     */
    private suspend fun applyHolidayCalendar(
        code: String,
        json: String,
    ) {
        importEngine.upsertHolidayCalendar(HolidayCalendarExportCodec.decode(json).calendar.copy(id = code))
    }

    private suspend fun applyCsv(
        name: String,
        json: String,
        resolutions: Map<CsvUnresolvedReference, CsvResolution>,
    ) {
        // Keep the strategy's name aligned with its filename key (the file is authoritative).
        val export = CsvStrategyExportCodec.decode(json).copy(name = name)
        val serviceResolutions = csvServiceResolutions(export, resolutions)
        val result = csvStrategyExportService.createStrategyFromExport(export, serviceResolutions)

        // Apply the strategy and its per-strategy mapping replacement as ONE engine batch, so a failure
        // can't leave the strategy without its mappings (or the old mappings deleted but not replaced).
        // The engine processes CSV-strategy mutations before account-mapping mutations, and mapping
        // mutations in list order (deletes before the create batch).
        val existing = csvStrategyRepository.getStrategyByName(name).first()
        if (existing == null) {
            val mappingMutations =
                if (result.accountMappings.isEmpty()) {
                    emptyList()
                } else {
                    listOf(AccountMappingMutation.CreateBatch(result.accountMappings))
                }
            importEngine.import(
                ImportBatch(
                    csvStrategyMutations = listOf(CsvStrategyMutation.Create(name, result.strategy, Source.Manual)),
                    accountMappingMutations = mappingMutations,
                ),
            )
        } else {
            // The file is authoritative for this strategy's per-strategy mappings: replace them.
            val deletes =
                accountMappingRepository
                    .getAllMappings()
                    .first()
                    .filter { it.strategyId == existing.id }
                    .map { AccountMappingMutation.Delete(it.id) }
            val remapped = result.accountMappings.map { it.copy(strategyId = existing.id) }
            val creates = if (remapped.isEmpty()) emptyList() else listOf(AccountMappingMutation.CreateBatch(remapped))
            importEngine.import(
                ImportBatch(
                    csvStrategyMutations = listOf(CsvStrategyMutation.Update(result.strategy.copy(id = existing.id), Source.Manual)),
                    accountMappingMutations = deletes + creates,
                ),
            )
        }
    }

    private suspend fun applyApi(
        name: String,
        json: String,
    ) {
        val export = ApiStrategyExportCodec.decode(json).copy(name = name)
        val strategy = apiStrategyExportService.createStrategyFromExport(export)
        val existing = apiStrategyRepository.getStrategyByName(name).first()
        if (existing == null) {
            importEngine.createApiStrategy(strategy)
        } else {
            importEngine.updateApiStrategy(strategy.copy(id = existing.id))
        }
    }

    private suspend fun applyPassThrough(
        name: String,
        json: String,
    ) {
        // Keep the definition's name aligned with its filename key (the file is authoritative).
        val export = PassThroughExportCodec.decode(json).copy(name = name)
        val existing = passThroughAccountRepository.getAll().first().find { it.name == name }
        val account =
            PassThroughAccount(
                id = existing?.id ?: PassThroughAccountId(0),
                name = export.name,
                conduitAccountName = export.conduitAccountName,
                relationshipTypeId = export.relationshipTypeId,
                rules = export.rules,
            )
        if (existing == null) {
            importEngine.createPassThroughAccount(account)
        } else {
            importEngine.updatePassThroughAccount(account)
        }
    }

    private suspend fun applyGlobalMappings(
        json: String,
        resolutions: Map<CsvUnresolvedReference, CsvResolution>,
    ) {
        val export = AccountMappingExportCodec.decode(json)
        val serviceResolutions =
            accountMappingExportService.parseExport(export).unresolvedAccountNames.associateWith { name ->
                resolutions[CsvUnresolvedReference(CsvReferenceType.ACCOUNT, name, null)]
                    ?: CsvResolution.CreateNew(name)
            }
        // Union semantics (keep-forever library): only import mappings not already present globally,
        // so re-importing never duplicates or trips the global-mapping unique index.
        val existing =
            accountMappingRepository
                .getAllMappings()
                .first()
                .filter { it.strategyId == null }
                .map { it.valuePattern.pattern }
                .toSet()
        val fresh = export.copy(mappings = export.mappings.filter { it.valuePattern !in existing })
        if (fresh.mappings.isNotEmpty()) {
            accountMappingExportService.importMappings(fresh, serviceResolutions)
        }
    }

    // Resolve every reference the CSV export needs: use the caller's choice when given, otherwise
    // default to creating the missing entity under its exported name (so a pull works unattended).
    private suspend fun csvServiceResolutions(
        export: CsvStrategyExport,
        resolutions: Map<CsvUnresolvedReference, CsvResolution>,
    ): Map<CsvUnresolvedReference, CsvResolution> =
        csvStrategyExportService.parseExport(export).unresolvedReferences.associateWith { ref ->
            resolutions[ref] ?: CsvResolution.CreateNew(ref.name)
        }

    override fun canonicalHash(
        key: StrategyKey,
        json: String,
    ): String = StrategyArtifactCodec.canonicalHash(key, json)

    private fun entry(
        key: StrategyKey,
        json: String,
    ): LocalStrategyEntry = LocalStrategyEntry(key, json, canonicalHash(key, json))

    private fun PassThroughAccount.toExport(appVersion: AppVersion): PassThroughExport =
        PassThroughExport(
            version = appVersion.value,
            name = name,
            conduitAccountName = conduitAccountName,
            relationshipTypeId = relationshipTypeId,
            rules = rules,
        )
}
