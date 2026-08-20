package com.moneymanager.ui.screens.apistrategy

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.ApiImportStrategyId
import com.moneymanager.domain.model.AuditType
import com.moneymanager.domain.model.SourceRecord
import com.moneymanager.domain.model.apistrategy.ApiImportStrategyAuditEntry
import com.moneymanager.domain.model.apistrategy.ApiStrategyConfig
import com.moneymanager.domain.repository.ApiImportStrategyReadRepository
import com.moneymanager.domain.repository.AuditReadRepository
import com.moneymanager.ui.audit.AuditDiffCard
import com.moneymanager.ui.audit.AuditScreen
import com.moneymanager.ui.audit.AuditScreenData
import com.moneymanager.ui.audit.AuditSectionLabel
import com.moneymanager.ui.audit.FieldChange
import com.moneymanager.ui.audit.FieldChangeRow
import com.moneymanager.ui.audit.FieldValueRow
import com.moneymanager.ui.audit.NoVisibleChangesText
import com.moneymanager.ui.audit.SourceInfoSection
import com.moneymanager.ui.audit.changedOrUnchanged
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.time.Instant

@Composable
fun ApiImportStrategyAuditScreen(
    strategyId: ApiImportStrategyId,
    auditRepository: AuditReadRepository,
    apiImportStrategyRepository: ApiImportStrategyReadRepository,
    onBack: () -> Unit,
) {
    AuditScreen(
        defaultTitle = "API Strategy Audit: $strategyId",
        entityTypeName = "API import strategy",
        loadKey = strategyId,
        loadData = {
            val entries = auditRepository.getAuditHistoryForApiImportStrategy(strategyId)
            val currentStrategy = apiImportStrategyRepository.getStrategyById(strategyId).first()
            val diffs =
                computeApiImportStrategyAuditDiffs(
                    entries = entries,
                    currentName = currentStrategy?.name,
                    currentConfig = currentStrategy?.config,
                )
            AuditScreenData(
                title = "API Strategy Audit: ${currentStrategy?.name ?: strategyId}",
                diffs = diffs,
            )
        },
        diffKey = { it.id },
        onBack = onBack,
        diffCard = { diff -> ApiImportStrategyAuditDiffCard(diff) },
    )
}

// ─── Diff model ──────────────────────────────────────────────────────────────

private data class ApiImportStrategyAuditDiff(
    val id: Long,
    val auditTimestamp: Instant,
    val auditType: AuditType,
    val revisionId: Long,
    val name: FieldChange<String>,
    val configChanges: List<Pair<String, FieldChange<String>>>,
    val source: SourceRecord?,
) {
    val hasChanges: Boolean
        get() = name is FieldChange.Changed || configChanges.isNotEmpty()
}

// ─── Diff computation ─────────────────────────────────────────────────────────

private fun computeApiImportStrategyAuditDiffs(
    entries: List<ApiImportStrategyAuditEntry>,
    currentName: String?,
    currentConfig: ApiStrategyConfig?,
): List<ApiImportStrategyAuditDiff> =
    entries.mapIndexed { index, entry ->
        when (entry.auditType) {
            AuditType.INSERT ->
                ApiImportStrategyAuditDiff(
                    id = entry.id,
                    auditTimestamp = entry.auditTimestamp,
                    auditType = entry.auditType,
                    revisionId = entry.revisionId,
                    name = FieldChange.Created(entry.name),
                    configChanges = emptyList(),
                    source = entry.source,
                )

            AuditType.DELETE ->
                ApiImportStrategyAuditDiff(
                    id = entry.id,
                    auditTimestamp = entry.auditTimestamp,
                    auditType = entry.auditType,
                    revisionId = entry.revisionId,
                    name = FieldChange.Deleted(entry.name),
                    configChanges = emptyList(),
                    source = entry.source,
                )

            AuditType.UPDATE -> {
                val previousEntry = entries.getOrNull(index - 1)
                val newName =
                    when {
                        index == 0 && currentName != null -> currentName
                        index > 0 && previousEntry != null -> previousEntry.name
                        else -> entry.name
                    }
                val newConfig =
                    when {
                        index == 0 -> currentConfig
                        else -> previousEntry?.config
                    }
                ApiImportStrategyAuditDiff(
                    id = entry.id,
                    auditTimestamp = entry.auditTimestamp,
                    auditType = entry.auditType,
                    revisionId = entry.revisionId,
                    name = changedOrUnchanged(entry.name, newName),
                    configChanges = if (newConfig != null) diffConfigs(entry.config, newConfig) else emptyList(),
                    source = entry.source,
                )
            }
        }
    }

/**
 * Diffs two configs field by field and returns only what differs, most readable label first.
 * Both sides are flattened through the config's own JSON form rather than a hand-maintained field
 * list, so every field — including ones added later — is diffed; [labelForPath] then gives each one
 * a human label. [oldConfig] is the state *before* this change; [newConfig] is the state *after*.
 */
private fun diffConfigs(
    oldConfig: ApiStrategyConfig,
    newConfig: ApiStrategyConfig,
): List<Pair<String, FieldChange<String>>> {
    val old = flattenConfig(oldConfig)
    val new = flattenConfig(newConfig)
    return (old.keys + new.keys)
        .mapNotNull { path ->
            val o = old[path].orEmpty()
            val n = new[path].orEmpty()
            if (o != n) labelForPath(path) to FieldChange.Changed(o, n) else null
        }.sortedBy { it.first }
}

private val configEncoder = Json { encodeDefaults = true }

/** Flattens a config's JSON form to `dotted.path` → rendered-value, dropping nulls. */
private fun flattenConfig(config: ApiStrategyConfig): Map<String, String> =
    buildMap { flattenInto(configEncoder.encodeToJsonElement(config), prefix = "", target = this) }

private fun flattenInto(
    element: JsonElement,
    prefix: String,
    target: MutableMap<String, String>,
) {
    when (element) {
        is JsonObject -> element.forEach { (key, value) -> flattenInto(value, if (prefix.isEmpty()) key else "$prefix.$key", target) }
        is JsonArray ->
            element.forEachIndexed { index, value -> flattenInto(value, "$prefix[$index]", target) }
        is JsonPrimitive -> if (element !is JsonNull) target[prefix] = element.content
    }
}

// ─── Field labels ─────────────────────────────────────────────────────────────

/**
 * Human names for the config's top-level sections, used as the prefix of every label beneath them
 * (`accountMappings.idField` → "Account ID field"). An empty prefix drops the section from the label
 * entirely, and a section missing here falls back to its own humanized name — so a config field added
 * later still gets a readable label instead of disappearing from the diff.
 */
private val sectionLabels =
    mapOf(
        "accountMappings" to "Account",
        "transactionMappings" to "Transaction",
        "peopleMappings" to "",
        "accountsEndpoint" to "Accounts endpoint",
        "transactionsEndpoint" to "Transactions endpoint",
        "accountIdentifiersEndpoint" to "Account identifiers endpoint",
        "ancestorEndpoints" to "Ancestor endpoint",
        "builtInCounterpartyRules" to "Counterparty rule",
        "dataEndpoints" to "Data endpoint",
        "peopleDownload" to "People download",
        "requestSigning" to "Request signing",
        "syntheticAccount" to "Synthetic account",
        "internalTransferReconcile" to "Internal transfer reconcile",
    )

private val acronyms =
    mapOf(
        "url" to "URL",
        "id" to "ID",
        "ids" to "IDs",
        "api" to "API",
        "json" to "JSON",
        "http" to "HTTP",
        "csv" to "CSV",
        "qif" to "QIF",
        "iso" to "ISO",
        "utc" to "UTC",
        "hmac" to "HMAC",
    )

private val camelBoundary = Regex("(?<=[a-z0-9])(?=[A-Z])")
private val camelIdentifier = Regex("[a-z][A-Za-z0-9]*")
private val arrayIndexSuffix = Regex("""\[(\d+)]$""")

/** Turns a flattened JSON path into a display label, e.g. `accountMappings.idField` → "Account ID field". */
internal fun labelForPath(path: String): String {
    val words = mutableListOf<String>()
    path.split('.').forEachIndexed { depth, rawSegment ->
        val index = arrayIndexSuffix.find(rawSegment)?.groupValues?.get(1)
        val segment = index?.let { rawSegment.removeSuffix("[$it]") } ?: rawSegment
        val label = if (depth == 0) sectionLabels[segment] ?: humanize(segment) else humanize(segment)
        if (label.isNotEmpty()) words += label
        // 1-based so "dataEndpoints[0]" reads as the first data endpoint, matching the editor's numbering.
        if (index != null) words += "#${index.toInt() + 1}"
    }
    return words.joinToString(" ").replaceFirstChar { it.uppercase() }
}

/**
 * Splits a camelCase field name into lower-case words with known acronyms restored. Segments that
 * are not camelCase identifiers are map keys the user named (a custom field, an asset alias), so
 * they are left exactly as typed.
 */
private fun humanize(segment: String): String =
    if (!camelIdentifier.matches(segment)) {
        segment
    } else {
        segment.split(camelBoundary).joinToString(" ") { word -> acronyms[word.lowercase()] ?: word.lowercase() }
    }

// ─── Diff card ────────────────────────────────────────────────────────────────

@Composable
private fun ApiImportStrategyAuditDiffCard(diff: ApiImportStrategyAuditDiff) {
    AuditDiffCard(
        auditType = diff.auditType,
        auditTimestamp = diff.auditTimestamp,
        revisionId = diff.revisionId,
    ) {
        when (diff.auditType) {
            AuditType.INSERT -> {
                AuditSectionLabel("Created with:")
                FieldValueRow("Name", diff.name.value())
                SourceInfoSection(diff.source)
            }

            AuditType.UPDATE -> {
                if (!diff.hasChanges) {
                    NoVisibleChangesText()
                } else {
                    AuditSectionLabel("Changed:")
                    val nameChange = diff.name
                    if (nameChange is FieldChange.Changed) {
                        FieldChangeRow("Name", nameChange.oldValue, nameChange.newValue, labelWidth = 200.dp)
                    }
                    diff.configChanges.forEach { (label, change) ->
                        if (change is FieldChange.Changed) {
                            FieldChangeRow(label, change.oldValue, change.newValue, labelWidth = 200.dp)
                        }
                    }
                }
                SourceInfoSection(diff.source)
            }

            AuditType.DELETE -> {
                val errorColor = MaterialTheme.colorScheme.error
                AuditSectionLabel("Deleted (final values):")
                FieldValueRow("Name", diff.name.value(), errorColor)
                SourceInfoSection(diff.source, labelColor = errorColor.copy(alpha = 0.8f))
            }
        }
    }
}
