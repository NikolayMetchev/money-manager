@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.ui.screens.apistrategy

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.ApiImportStrategyAuditEntry
import com.moneymanager.domain.model.AuditType
import com.moneymanager.domain.model.SourceRecord
import com.moneymanager.domain.model.apistrategy.ApiEndpointConfig
import com.moneymanager.domain.model.apistrategy.ApiImportStrategyId
import com.moneymanager.domain.model.apistrategy.ApiStrategyConfig
import com.moneymanager.domain.model.apistrategy.PaginationMode
import com.moneymanager.domain.repository.ApiImportStrategyRepository
import com.moneymanager.domain.repository.AuditRepository
import com.moneymanager.ui.audit.AuditDiffCard
import com.moneymanager.ui.audit.AuditScreen
import com.moneymanager.ui.audit.AuditScreenData
import com.moneymanager.ui.audit.AuditSectionLabel
import com.moneymanager.ui.audit.FieldChange
import com.moneymanager.ui.audit.FieldChangeRow
import com.moneymanager.ui.audit.FieldValueRow
import com.moneymanager.ui.audit.NoVisibleChangesText
import com.moneymanager.ui.audit.SourceInfoSection
import com.moneymanager.ui.screens.changedOrUnchanged
import kotlinx.coroutines.flow.first
import kotlin.time.Instant

@Composable
fun ApiImportStrategyAuditScreen(
    strategyId: ApiImportStrategyId,
    auditRepository: AuditRepository,
    apiImportStrategyRepository: ApiImportStrategyRepository,
    onBack: () -> Unit,
) {
    AuditScreen(
        defaultTitle = "API Strategy Audit: $strategyId",
        entityTypeName = "API import strategy",
        loadKey = strategyId,
        loadData = {
            val entries = auditRepository.getAuditHistoryForApiImportStrategy(strategyId)
            val currentStrategy = apiImportStrategyRepository.getStrategyById(strategyId).first()
            val currentConfig =
                currentStrategy?.let {
                    ApiStrategyConfig(
                        baseUrl = it.baseUrl,
                        authType = it.authType,
                        accountsEndpoint = it.accountsEndpoint,
                        transactionsEndpoint = it.transactionsEndpoint,
                        accountMappings = it.accountMappings,
                        transactionMappings = it.transactionMappings,
                        accountNamePrefix = it.accountNamePrefix,
                        counterpartyPrefix = it.counterpartyPrefix,
                        peopleMappings = it.peopleMappings,
                    )
                }
            val diffs =
                computeApiImportStrategyAuditDiffs(
                    entries = entries,
                    currentName = currentStrategy?.name,
                    currentConfig = currentConfig,
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
 * Flattens two configs to label→value maps and returns only the fields that differ.
 * [oldConfig] is the state *before* this change; [newConfig] is the state *after*.
 */
private fun diffConfigs(
    oldConfig: ApiStrategyConfig,
    newConfig: ApiStrategyConfig,
): List<Pair<String, FieldChange<String>>> {
    val old = flattenConfig(oldConfig)
    val new = flattenConfig(newConfig)
    return old.keys
        .union(new.keys)
        .sorted()
        .mapNotNull { key ->
            val o = old[key] ?: ""
            val n = new[key] ?: ""
            if (o != n) key to FieldChange.Changed(o, n) else null
        }
}

private fun flattenConfig(config: ApiStrategyConfig): Map<String, String> =
    buildMap {
        put("Base URL", config.baseUrl)
        put("Auth type", config.authType.name)
        put("Account name prefix", config.accountNamePrefix)
        put("Counterparty prefix", config.counterpartyPrefix)
        flattenEndpoint("Accounts endpoint", config.accountsEndpoint, this)
        flattenEndpoint("Transactions endpoint", config.transactionsEndpoint, this)
        put("Account ID field", config.accountMappings.idField)
        put("Account description field", config.accountMappings.descriptionField)
        config.accountMappings.ownerNameField?.let { put("Account owner name field", it) }
        if (config.accountMappings.customFields.isNotEmpty()) {
            put(
                "Account custom fields",
                config.accountMappings.customFields.entries
                    .sortedBy { it.key }
                    .joinToString { "${it.key}=${it.value}" },
            )
        }
        put("Transaction amount field", config.transactionMappings.amountField)
        put("Transaction timestamp field", config.transactionMappings.timestampField)
        put("Transaction currency field", config.transactionMappings.currencyField)
        put("Transaction description field", config.transactionMappings.descriptionField)
        config.transactionMappings.merchantNameField?.let { put("Merchant name field", it) }
        config.transactionMappings.counterpartyNameField?.let { put("Counterparty name field", it) }
        config.transactionMappings.counterpartyIdField?.let { put("Counterparty ID field", it) }
        config.transactionMappings.declineReasonField?.let { put("Decline reason field", it) }
        config.transactionMappings.localAmountField?.let { put("Local amount field", it) }
        config.transactionMappings.localCurrencyField?.let { put("Local currency field", it) }
        if (config.transactionMappings.customFields.isNotEmpty()) {
            put(
                "Transaction custom fields",
                config.transactionMappings.customFields.entries
                    .sortedBy { it.key }
                    .joinToString { "${it.key}=${it.value}" },
            )
        }
        val people = config.peopleMappings
        put("Counterparty object field", people.counterpartyObjectField)
        put("Beneficiary account type field", people.beneficiaryAccountTypeField)
        put("Personal beneficiary account type value", people.personalBeneficiaryAccountTypeValue)
        put("Counterparty name field (people)", people.counterpartyNameField)
        put("Counterparty user ID field", people.counterpartyUserIdField)
        put("Counterparty sort code field", people.counterpartySortCodeField)
        put("Counterparty account number field", people.counterpartyAccountNumberField)
        put("Counterparty service user number field", people.counterpartyServiceUserNumberField)
        put("Fallback counterparty account ID suffix", people.fallbackCounterpartyAccountIdSuffix)
    }

private fun flattenEndpoint(
    prefix: String,
    endpoint: ApiEndpointConfig,
    map: MutableMap<String, String>,
) {
    map["$prefix path"] = endpoint.path
    map["$prefix response key"] = endpoint.responseArrayKey
    if (endpoint.queryParams.isNotEmpty()) {
        map["$prefix query params"] =
            endpoint.queryParams.joinToString { p ->
                "${p.name}=${p.value ?: p.dynamicSource ?: ""}"
            }
    }
    endpoint.pagination?.let { pag ->
        map["$prefix pagination mode"] = pag.mode.name
        when (pag.mode) {
            PaginationMode.CURSOR -> {
                map["$prefix pagination limit param"] = pag.limitParam
                map["$prefix pagination limit"] = pag.limitValue.toString()
                map["$prefix pagination cursor param"] = pag.cursorParam
                map["$prefix pagination cursor field"] = pag.cursorResponseField
            }
            PaginationMode.DATE_WINDOW -> {
                map["$prefix pagination start param"] = pag.startParam
                map["$prefix pagination end param"] = pag.endParam
                map["$prefix pagination window days"] = pag.windowDays.toString()
                map["$prefix pagination lookback days"] = pag.lookbackDays.toString()
            }
        }
    }
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
