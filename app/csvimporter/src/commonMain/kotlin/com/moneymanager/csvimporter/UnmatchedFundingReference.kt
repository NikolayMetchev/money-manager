package com.moneymanager.csvimporter

import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.CryptoAsset
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.passthrough.PassThroughAccount
import com.moneymanager.domain.repository.AccountMappingReadRepository
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CsvImportReadRepository
import com.moneymanager.domain.repository.TradeReadRepository
import com.moneymanager.domain.repository.TransactionReadRepository
import com.moneymanager.domain.repository.TransferRelationshipReadRepository
import com.moneymanager.domain.repository.TransferSourceReadRepository
import com.moneymanager.importengineapi.ImportEngine
import kotlinx.coroutines.flow.first

/**
 * One funding reference (e.g. a card's last 4 digits) that imported rows carry in a strategy's
 * [com.moneymanager.domain.model.csvstrategy.CsvStrategyConfig.fundingAttributeMatch] column but that no
 * single account's [attributeTypeName] attribute resolves, so those rows can't reconcile against the
 * funding account's own legs.
 */
data class UnmatchedFundingReference(
    val value: String,
    val attributeTypeName: String,
    val rowCount: Int,
    val strategyNames: List<String>,
    val imports: List<CsvImportId>,
    /** True when several accounts claim the value, rather than none. */
    val ambiguous: Boolean,
)

private const val FUNDING_SCAN_PAGE_SIZE = 1000

private val ATTRIBUTE_TOKEN_DELIMITER = Regex("[\\s,]+")

/**
 * Every funding reference in the staged rows of [imports] that [attributeAccountMatchers] can't resolve to
 * exactly one account. Only files last applied with a strategy that sets a funding match are scanned; the
 * strategy's own config names the column and the attribute type, so no source is special-cased.
 */
suspend fun findUnmatchedFundingReferences(
    imports: List<CsvImport>,
    strategies: List<CsvImportStrategy>,
    csvImportRepository: CsvImportReadRepository,
    attributeAccountMatchers: Map<String, AttributeAccountMatcher>,
): List<UnmatchedFundingReference> {
    data class Key(
        val attributeTypeName: String,
        val value: String,
    )

    class Occurrences {
        var rows = 0
        val strategyNames = linkedSetOf<String>()
        val imports = linkedSetOf<CsvImportId>()
    }

    val strategiesById = strategies.associateBy { it.id }
    val occurrences = linkedMapOf<Key, Occurrences>()
    for (listedImport in imports) {
        val strategy = listedImport.lastAppliedStrategyId?.let { strategiesById[it] }
        val fundingMatch = strategy?.config?.fundingAttributeMatch ?: continue
        // getAllImports() doesn't populate columns; re-fetch the full import to locate the column.
        val csvImport = csvImportRepository.getImport(listedImport.id).first() ?: listedImport
        val columnIndex = csvImport.columns.firstOrNull { it.originalName == fundingMatch.column }?.columnIndex
        val values = columnIndex?.let { fundingColumnValues(csvImportRepository, csvImport.id, it) }.orEmpty()
        for (value in values) {
            val entry = occurrences.getOrPut(Key(fundingMatch.attributeTypeName, value)) { Occurrences() }
            entry.rows++
            entry.strategyNames += strategy.name
            entry.imports += csvImport.id
        }
    }
    return occurrences.mapNotNull { (key, entry) ->
        val matcher = attributeAccountMatchers[key.attributeTypeName]
        if (matcher?.match(key.value) != null) return@mapNotNull null
        UnmatchedFundingReference(
            value = key.value,
            attributeTypeName = key.attributeTypeName,
            rowCount = entry.rows,
            strategyNames = entry.strategyNames.toList(),
            imports = entry.imports.toList(),
            ambiguous = matcher?.matchesSeveral(key.value) == true,
        )
    }
}

/** The non-blank, trimmed values of [columnIndex] across every staged row of [importId]. */
private suspend fun fundingColumnValues(
    csvImportRepository: CsvImportReadRepository,
    importId: CsvImportId,
    columnIndex: Int,
): List<String> {
    val values = mutableListOf<String>()
    var offset = 0
    do {
        val page = csvImportRepository.getImportRows(importId, limit = FUNDING_SCAN_PAGE_SIZE, offset = offset)
        page.mapNotNullTo(values) { row ->
            row.values
                .getOrNull(columnIndex)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }
        offset += page.size
    } while (page.size == FUNDING_SCAN_PAGE_SIZE)
    return values
}

/**
 * [existing] with [token] added to its whitespace/comma-separated token set — the format
 * [AttributeAccountMatcher] reads — dropping duplicates and keeping the existing order.
 */
fun addAttributeToken(
    existing: String?,
    token: String,
): String =
    (existing.orEmpty().split(ATTRIBUTE_TOKEN_DELIMITER) + token.trim())
        .filter { it.isNotEmpty() }
        .distinct()
        .joinToString(" ")

/**
 * Re-runs the funding reconcile for [imports] after a funding reference gained an account: each file's
 * already-imported rows that now resolve a funding leg are reset and re-imported reconciled. Only the
 * re-import plan's funding reconciles are executed, so unrelated merges, rewrites and value updates the
 * full re-import would also apply are left for the user to review there.
 */
@Suppress("LongParameterList")
suspend fun rerunFundingReconciles(
    imports: List<CsvImportId>,
    strategies: List<CsvImportStrategy>,
    currencies: List<Currency>,
    cryptoAssets: List<CryptoAsset>,
    passThroughAccounts: List<PassThroughAccount>,
    attributeAccountMatchers: Map<String, AttributeAccountMatcher>,
    accountMappingRepository: AccountMappingReadRepository,
    accountRepository: AccountReadRepository,
    csvImportRepository: CsvImportReadRepository,
    transactionRepository: TransactionReadRepository,
    relationshipRepository: TransferRelationshipReadRepository,
    transferSourceRepository: TransferSourceReadRepository,
    tradeRepository: TradeReadRepository,
    maintenance: Maintenance,
    importEngine: ImportEngine,
): Int {
    val historicalSourceAccounts = csvImportRepository.historicalSourceAccounts()
    var reconciled = 0
    for (importId in imports) {
        val csvImport = csvImportRepository.getImport(importId).first()
        val strategy = csvImport?.lastAppliedStrategyId?.let { id -> strategies.find { it.id == id } } ?: continue
        val sourceAccountOverride = historicalSourceAccounts[importId]
        val plan =
            planCsvReimport(
                csvImport = csvImport,
                strategy = strategy,
                sourceAccountOverride = sourceAccountOverride,
                currencies = currencies,
                accountMappingRepository = accountMappingRepository,
                accountRepository = accountRepository,
                csvImportRepository = csvImportRepository,
                transactionRepository = transactionRepository,
                relationshipRepository = relationshipRepository,
                transferSourceRepository = transferSourceRepository,
                tradeRepository = tradeRepository,
                passThroughAccounts = passThroughAccounts,
                cryptoAssets = cryptoAssets,
                attributeAccountMatchers = attributeAccountMatchers,
            )
        if (plan.fundingReconciles.isNotEmpty()) {
            executeCsvReimport(
                plan = ReimportPlan(merges = emptyList(), skipped = emptyList(), fundingReconciles = plan.fundingReconciles),
                csvImport = csvImport,
                strategy = strategy,
                sourceAccountOverride = sourceAccountOverride,
                currencies = currencies,
                accountMappingRepository = accountMappingRepository,
                accountRepository = accountRepository,
                csvImportRepository = csvImportRepository,
                maintenance = maintenance,
                importEngine = importEngine,
                passThroughAccounts = passThroughAccounts,
                refreshViews = false,
                tradeRepository = tradeRepository,
                attributeAccountMatchers = attributeAccountMatchers,
            )
            reconciled += plan.fundingReconciles.size
        }
    }
    if (reconciled > 0) maintenance.refreshMaterializedViews()
    return reconciled
}
