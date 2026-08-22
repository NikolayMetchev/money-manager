package com.moneymanager.ui.screens.apistrategy

import androidx.compose.runtime.Composable
import com.moneymanager.domain.model.ApiImportStrategyId
import com.moneymanager.domain.model.apistrategy.ApiImportStrategyAuditEntry
import com.moneymanager.domain.model.apistrategy.ApiStrategyConfig
import com.moneymanager.domain.repository.ApiImportStrategyReadRepository
import com.moneymanager.domain.repository.AuditReadRepository
import com.moneymanager.ui.audit.AuditScreen
import com.moneymanager.ui.audit.AuditScreenData
import com.moneymanager.ui.audit.NamedConfigAuditDiffCard
import com.moneymanager.ui.audit.NamedConfigRevision
import com.moneymanager.ui.audit.computeNamedConfigAuditDiffs
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement

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
            AuditScreenData(
                title = "API Strategy Audit: ${currentStrategy?.name ?: strategyId}",
                diffs =
                    computeNamedConfigAuditDiffs(
                        revisions = entries.map { it.toRevision() },
                        currentName = currentStrategy?.name,
                        currentConfig = currentStrategy?.config?.let { flattenConfig(it) },
                        label = ::labelForPath,
                    ),
            )
        },
        diffKey = { it.id },
        onBack = onBack,
        diffCard = { diff -> NamedConfigAuditDiffCard(diff) },
    )
}

private fun ApiImportStrategyAuditEntry.toRevision() =
    NamedConfigRevision(
        id = id,
        auditTimestamp = auditTimestamp,
        auditType = auditType,
        revisionId = revisionId,
        name = name,
        config = flattenConfig(config),
        source = source,
    )

private val configEncoder = Json { encodeDefaults = true }

/**
 * Flattens a config's JSON form to `dotted.path` -> rendered-value, dropping nulls. Going through the
 * config's own JSON form rather than a hand-maintained field list means every field — including ones
 * added later — is diffed; [labelForPath] then gives each one a human label.
 */
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
