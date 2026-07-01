package com.moneymanager.database.service

import com.moneymanager.database.json.AccountMappingExportCodec
import com.moneymanager.database.json.ApiStrategyExportCodec
import com.moneymanager.database.json.CsvStrategyExportCodec
import com.moneymanager.domain.CsvReferenceType
import com.moneymanager.domain.CsvResolution
import com.moneymanager.domain.CsvUnresolvedReference
import com.moneymanager.domain.LocalStrategyEntry
import com.moneymanager.domain.StrategyFileNaming
import com.moneymanager.domain.StrategyKey
import com.moneymanager.domain.StrategyKind
import com.moneymanager.domain.StrategyLibrary
import com.moneymanager.domain.StrategyParseResult
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.csvstrategy.export.CsvStrategyExport
import com.moneymanager.domain.model.csvstrategy.isQifStrategy
import com.moneymanager.domain.repository.AccountMappingReadRepository
import com.moneymanager.domain.repository.ApiImportStrategyReadRepository
import com.moneymanager.domain.repository.CsvImportStrategyReadRepository
import com.moneymanager.importengineapi.AccountMappingMutation
import com.moneymanager.importengineapi.CsvStrategyMutation
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.createApiStrategy
import com.moneymanager.importengineapi.updateApiStrategy
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
    private val csvStrategyExportService: CsvStrategyExportService,
    private val apiStrategyExportService: ApiStrategyExportService,
    private val accountMappingExportService: AccountMappingExportService,
    private val importEngine: ImportEngine,
) : StrategyLibrary {
    override suspend fun listLocal(appVersion: AppVersion): List<LocalStrategyEntry> {
        val entries = mutableListOf<LocalStrategyEntry>()

        for (strategy in csvStrategyRepository.getAllStrategies().first()) {
            val json = CsvStrategyExportCodec.encode(csvStrategyExportService.toExport(strategy, appVersion))
            val kind = if (strategy.isQifStrategy()) StrategyKind.QIF else StrategyKind.CSV
            entries += entry(StrategyKey(kind, strategy.name), json)
        }

        for (strategy in apiStrategyRepository.getAllStrategies().first()) {
            val json = ApiStrategyExportCodec.encode(apiStrategyExportService.toExport(strategy, appVersion))
            entries += entry(StrategyKey(StrategyKind.API, strategy.name), json)
        }

        val globalMappings = accountMappingRepository.getAllMappings().first().filter { it.strategyId == null }
        if (globalMappings.isNotEmpty()) {
            val json = AccountMappingExportCodec.encode(accountMappingExportService.toExport(globalMappings, appVersion))
            entries += entry(StrategyKey(StrategyKind.GLOBAL_MAPPINGS, StrategyFileNaming.GLOBAL_MAPPINGS_NAME), json)
        }

        return entries
    }

    override suspend fun parseIncoming(
        key: StrategyKey,
        json: String,
    ): StrategyParseResult =
        when (key.kind) {
            StrategyKind.CSV, StrategyKind.QIF -> {
                val export = CsvStrategyExportCodec.decode(json)
                val refs = csvStrategyExportService.parseExport(export).unresolvedReferences.map { it.toDomain() }
                StrategyParseResult(key, refs)
            }
            StrategyKind.API -> StrategyParseResult(key, emptyList())
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
            StrategyKind.CSV, StrategyKind.QIF -> applyCsv(key.name, json, resolutions)
            StrategyKind.API -> applyApi(key.name, json)
            StrategyKind.GLOBAL_MAPPINGS -> applyGlobalMappings(json, resolutions)
        }
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

    private suspend fun applyGlobalMappings(
        json: String,
        resolutions: Map<CsvUnresolvedReference, CsvResolution>,
    ) {
        val export = AccountMappingExportCodec.decode(json)
        val serviceResolutions =
            accountMappingExportService.parseExport(export).unresolvedAccountNames.associateWith { name ->
                resolutions[CsvUnresolvedReference(CsvReferenceType.ACCOUNT, name, null)]?.toService()
                    ?: Resolution.CreateNew(name)
            }
        // Union semantics (keep-forever library): only import mappings not already present globally,
        // so re-importing never duplicates or trips the global-mapping unique index.
        val existing =
            accountMappingRepository
                .getAllMappings()
                .first()
                .filter { it.strategyId == null }
                .map { it.columnName to it.valuePattern.pattern }
                .toSet()
        val fresh = export.copy(mappings = export.mappings.filter { (it.columnName to it.valuePattern) !in existing })
        if (fresh.mappings.isNotEmpty()) {
            accountMappingExportService.importMappings(fresh, serviceResolutions)
        }
    }

    // Resolve every reference the CSV export needs: use the caller's choice when given, otherwise
    // default to creating the missing entity under its exported name (so a pull works unattended).
    private suspend fun csvServiceResolutions(
        export: CsvStrategyExport,
        resolutions: Map<CsvUnresolvedReference, CsvResolution>,
    ): Map<UnresolvedReference, Resolution> =
        csvStrategyExportService.parseExport(export).unresolvedReferences.associateWith { ref ->
            resolutions[ref.toDomain()]?.toService() ?: Resolution.CreateNew(ref.name)
        }

    override fun canonicalHash(
        key: StrategyKey,
        json: String,
    ): String {
        // Re-encode with the version stamp blanked so semantically-equal artifacts exported under
        // different app versions hash identically.
        val canonical =
            when (key.kind) {
                StrategyKind.CSV, StrategyKind.QIF ->
                    CsvStrategyExportCodec.encode(CsvStrategyExportCodec.decode(json).copy(version = ""))
                StrategyKind.API ->
                    ApiStrategyExportCodec.encode(ApiStrategyExportCodec.decode(json).copy(version = ""))
                StrategyKind.GLOBAL_MAPPINGS ->
                    AccountMappingExportCodec.encode(AccountMappingExportCodec.decode(json).copy(version = ""))
            }
        return contentHash(canonical)
    }

    private fun entry(
        key: StrategyKey,
        json: String,
    ): LocalStrategyEntry = LocalStrategyEntry(key, json, canonicalHash(key, json))

    // Stable, platform-independent content hash (FNV-1a 64-bit) used to detect local changes since the
    // last sync without a network round-trip.
    private fun contentHash(text: String): String {
        var hash = FNV_OFFSET_BASIS
        for (byte in text.encodeToByteArray()) {
            hash = hash xor (byte.toLong() and 0xff)
            hash *= FNV_PRIME
        }
        return hash.toULong().toString(HEX_RADIX)
    }

    private fun UnresolvedReference.toDomain(): CsvUnresolvedReference =
        CsvUnresolvedReference(
            type =
                when (type) {
                    ReferenceType.ACCOUNT -> CsvReferenceType.ACCOUNT
                    ReferenceType.CURRENCY -> CsvReferenceType.CURRENCY
                    ReferenceType.CATEGORY -> CsvReferenceType.CATEGORY
                },
            name = name,
            fieldType = fieldType,
        )

    private fun CsvResolution.toService(): Resolution =
        when (this) {
            is CsvResolution.CreateNew -> Resolution.CreateNew(name)
            is CsvResolution.MapToExisting -> Resolution.MapToExisting(id)
            is CsvResolution.MapToExistingCurrency -> Resolution.MapToExistingCurrency(id)
        }

    private companion object {
        const val FNV_OFFSET_BASIS: Long = -3750763034362895579L // 0xcbf29ce484222325
        const val FNV_PRIME: Long = 1099511628211L
        const val HEX_RADIX: Int = 16
    }
}
