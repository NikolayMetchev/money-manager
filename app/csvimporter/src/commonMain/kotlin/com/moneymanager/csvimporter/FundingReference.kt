package com.moneymanager.csvimporter

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.rules.ColumnRecord
import com.moneymanager.domain.model.rules.RuleEngine
import com.moneymanager.domain.repository.CsvImportReadRepository
import kotlinx.coroutines.flow.first

/**
 * One funding reference (e.g. a card's last 4 digits) that imported rows carry in a strategy's
 * [com.moneymanager.domain.model.csvstrategy.CsvStrategyConfig.fundingAttributeMatch] column, and the account
 * whose [attributeTypeName] attribute resolves it, if exactly one does. A reference no single account owns
 * leaves those rows unable to reconcile against the funding account's own legs.
 */
data class FundingReference(
    val value: String,
    val attributeTypeName: String,
    val rowCount: Int,
    val strategyNames: List<String>,
    /** The files whose rows carry the value, in the order they were found. */
    val files: List<FundingReferenceFile>,
    /** True when several accounts claim the value, rather than one or none. */
    val ambiguous: Boolean,
    /** The one account whose attribute resolves the value, or null when none or several do. */
    val owner: AccountId?,
    /**
     * True when [owner] holds the value itself as a token, so it can be moved to another account. False when
     * only a pattern on [owner] matches it, which the account editor changes.
     */
    val ownedByToken: Boolean,
) {
    val assigned: Boolean get() = owner != null
    val imports: List<CsvImportId> get() = files.map { it.importId }
}

/**
 * One file a [FundingReference] appears in: its [rowCount] rows carrying the value, and the [columnFilter] (column name
 * to the cell values it keeps) that shows only those rows in the file's own table. The filter is empty when a rule set
 * the value from elsewhere, so no column of the file holds it.
 */
data class FundingReferenceFile(
    val importId: CsvImportId,
    val rowCount: Int,
    val columnFilter: Map<String, Set<String>>,
)

private const val FUNDING_SCAN_PAGE_SIZE = 1000

private val ATTRIBUTE_TOKEN_DELIMITER = Regex("[\\s,]+")

/**
 * Every funding reference in the staged rows of [imports], with the account [attributeAccountMatchers] resolves
 * it to (see [FundingReference.owner]). Only files last applied with a strategy that sets a funding match are scanned; the
 * strategy's own config names the column and the attribute type, so no source is special-cased.
 */
suspend fun findFundingReferences(
    imports: List<CsvImport>,
    strategies: List<CsvImportStrategy>,
    csvImportRepository: CsvImportReadRepository,
    attributeAccountMatchers: Map<String, AttributeAccountMatcher>,
): List<FundingReference> {
    data class Key(
        val attributeTypeName: String,
        val value: String,
    )

    class InFile(
        var rows: Int = 0,
        val cells: MutableSet<String> = linkedSetOf(),
    )

    class Occurrences {
        var rows = 0
        val strategyNames = linkedSetOf<String>()
        val files = linkedMapOf<CsvImportId, InFile>()
    }

    val strategiesById = strategies.associateBy { it.id }
    val occurrences = linkedMapOf<Key, Occurrences>()
    val fileColumns = mutableMapOf<CsvImportId, String?>()
    for (listedImport in imports) {
        val strategy = listedImport.lastAppliedStrategyId?.let { strategiesById[it] }
        val fundingMatch = strategy?.config?.fundingAttributeMatch ?: continue
        // getAllImports() doesn't populate columns; re-fetch the full import to locate the column.
        val csvImport = csvImportRepository.getImport(listedImport.id).first() ?: listedImport
        // The value is read as the rules leave the row, because a rule may set the column from whichever one the file has.
        val columnIndex = columnIndexWithRuleColumns(csvImport.columns, strategy.config)
        val fileColumn = csvImport.columns.firstOrNull { it.originalName == fundingMatch.column }
        fileColumns[csvImport.id] = fileColumn?.originalName
        val values = fundingColumnValues(csvImportRepository, csvImport, strategy, columnIndex, fundingMatch.column)
        for ((value, cells) in values) {
            val entry = occurrences.getOrPut(Key(fundingMatch.attributeTypeName, value)) { Occurrences() }
            entry.rows++
            entry.strategyNames += strategy.name
            val inFile = entry.files.getOrPut(csvImport.id) { InFile() }
            inFile.rows++
            fileColumn?.let { inFile.cells += cells.getOrElse(it.columnIndex) { "" } }
        }
    }
    return occurrences.map { (key, entry) ->
        val matcher = attributeAccountMatchers[key.attributeTypeName]
        val owner = matcher?.match(key.value)
        FundingReference(
            value = key.value,
            attributeTypeName = key.attributeTypeName,
            rowCount = entry.rows,
            strategyNames = entry.strategyNames.toList(),
            files =
                entry.files.map { (importId, inFile) ->
                    // The file's own cells of those rows, so its table's column filter keeps exactly them.
                    val column = fileColumns[importId]
                    FundingReferenceFile(
                        importId = importId,
                        rowCount = inFile.rows,
                        columnFilter = if (column == null) emptyMap() else mapOf(column to inFile.cells.toSet()),
                    )
                },
            ambiguous = matcher?.matchesSeveral(key.value) == true,
            owner = owner,
            ownedByToken = owner != null && matcher.holdsToken(owner, key.value),
        )
    }
}

/**
 * The non-blank, trimmed values of [column] across every staged row of [csvImport], once [strategy]'s rules have run,
 * each with the row's own cells.
 */
private suspend fun fundingColumnValues(
    csvImportRepository: CsvImportReadRepository,
    csvImport: CsvImport,
    strategy: CsvImportStrategy,
    columnIndex: Map<String, Int>,
    column: String,
): List<Pair<String, List<String>>> {
    val engine = RuleEngine()
    val values = mutableListOf<Pair<String, List<String>>>()
    var offset = 0
    do {
        val page = csvImportRepository.getImportRows(csvImport.id, limit = FUNDING_SCAN_PAGE_SIZE, offset = offset)
        page.mapNotNullTo(values) { row ->
            engine
                .evaluate(
                    strategy.config.rules,
                    ColumnRecord(row.values.withFileName(columnIndex, csvImport.originalFileName), columnIndex),
                ).record
                .text(column)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { it to row.values }
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
 * [existing] without [token] (compared case-insensitively), in the same token format as [addAttributeToken].
 * Blank when [token] was the only one.
 */
fun removeAttributeToken(
    existing: String?,
    token: String,
): String =
    existing
        .orEmpty()
        .split(ATTRIBUTE_TOKEN_DELIMITER)
        .filter { it.isNotEmpty() && !it.equals(token.trim(), ignoreCase = true) }
        .distinct()
        .joinToString(" ")
