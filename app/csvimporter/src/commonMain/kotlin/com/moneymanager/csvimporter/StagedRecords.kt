package com.moneymanager.csvimporter

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.accountmapping.AccountMapping
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvStrategyConfig
import com.moneymanager.domain.model.csvstrategy.FieldMapping
import com.moneymanager.domain.model.observation.ImportRecord
import com.moneymanager.domain.model.observation.ImportRecordKey
import com.moneymanager.domain.model.passthrough.PassThroughAccount
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.importengineapi.CsvImportMutation
import com.moneymanager.importengineapi.recordContentHash
import com.moneymanager.importengineapi.strategyConfigHash
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * What a staged file's import reads besides its own rows, as one hash: the strategy (with the currency the file
 * imports in), the source account it imports onto, and the inputs a person sets that decide where a row lands: the
 * account mappings, the asset aliases, the account attributes the strategy matches values against (a card's last 4)
 * and the pass-through accounts. A re-import is how a change to any of them reaches what a file booked, so each
 * one changes every row's content hash ([rowContentHashes]) and keeps the re-import from being skipped.
 */
suspend fun stagedInputsHash(
    strategy: CsvImportStrategy,
    sourceAccount: AccountId?,
    mappings: List<AccountMapping>,
    assetAliases: Map<String, String>,
    attributeAccountMatchers: Map<String, AttributeAccountMatcher>,
    passThroughAccounts: List<PassThroughAccount>,
): String {
    val canonical =
        buildString {
            appendLine(strategy.id.toString())
            appendLine(CANONICAL_JSON.encodeToString(CONFIG_SERIALIZER, strategy.config))
            appendLine("account=${sourceAccount?.id}")
            mappings
                .map { "mapping=${it.id}|${it.strategyId}|${it.valuePattern.pattern}|${it.accountId.id}" }
                .sorted()
                .forEach(::appendLine)
            assetAliases.entries
                .map { (alias, code) -> "alias=$alias|$code" }
                .sorted()
                .forEach(::appendLine)
            strategy.config
                .matchedAttributeTypes()
                .sorted()
                .forEach { type -> appendLine("attribute=$type|${attributeAccountMatchers[type]?.fingerprint.orEmpty()}") }
            appendLine(CANONICAL_JSON.encodeToString(PASS_THROUGHS_SERIALIZER, passThroughAccounts.sortedBy { it.id.value }))
        }
    return strategyConfigHash(canonical)
}

/** Each row's import-record content hash: its staged values under [inputsHash] ([stagedInputsHash]). */
suspend fun rowContentHashes(
    rows: List<CsvRow>,
    inputsHash: String,
): Map<Long, String> = rows.associate { it.rowIndex to recordContentHash(it.values, inputsHash) }

/**
 * The write that records [rows]' content hashes as [file]'s import records. A file re-imported only because another
 * re-import took it along is not [settled]: the delete + re-run that took it along can leave it short of where a fresh
 * import would (an end it created and lost is deleted as empty), and its own re-import is what puts that right. Its
 * rows are then recorded with a hash no content has, so that re-import still runs.
 */
suspend fun recordRowsMutation(
    file: CsvImport,
    rows: List<CsvRow>,
    inputsHash: String,
    settled: Boolean = true,
): CsvImportMutation.RecordRows =
    CsvImportMutation.RecordRows(
        file.id,
        if (settled) rowContentHashes(rows, inputsHash) else rows.associate { it.rowIndex to UNSETTLED_HASH },
    )

/** A content hash no row hashes to ([recordContentHash] is hex). */
private const val UNSETTLED_HASH = "unsettled"

/**
 * True when importing [file] again would change nothing: it was imported, no row failed, and every row's recorded
 * content hash is the one [rows] hash to under [inputsHash] now. Records are written when a run over the file completes
 * and deleted whenever its rows are reset, so a row the last run left waiting (a cross-file leg held back for its
 * partner, which the partner's own import books) counts as settled, while one a stopped re-import reset never does.
 * A file imported before its rows were recorded has no records, so it is never unchanged: its next re-import runs.
 */
suspend fun isUnchanged(
    file: CsvImport,
    rows: List<CsvRow>,
    records: List<ImportRecord>,
    inputsHash: String,
): Boolean {
    if (file.lastAppliedAt == null || rows.isEmpty() || records.size != rows.size) return false
    if (rows.any { it.importStatus == ImportStatus.ERROR }) return false
    val recorded = records.associate { (it.key as ImportRecordKey.CsvRow).rowIndex to it.contentHash }
    return rowContentHashes(rows, inputsHash).all { (rowIndex, hash) -> recorded[rowIndex] == hash }
}

/** The account-attribute types the strategy matches row values against: its funding match and its account rules'. */
private fun CsvStrategyConfig<*>.matchedAttributeTypes(): Set<String> =
    buildSet {
        fundingAttributeMatch?.let { add(it.attributeTypeName) }
        rules.flatMap { it.actions }.filterIsInstance<RuleAction.Account>().mapNotNullTo(this) { it.naming.attributeTypeName }
    }

private val CANONICAL_JSON = Json { encodeDefaults = true }
private val CONFIG_SERIALIZER = CsvStrategyConfig.serializer(FieldMapping.serializer())
private val PASS_THROUGHS_SERIALIZER = ListSerializer(PassThroughAccount.serializer())
