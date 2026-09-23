package com.moneymanager.ui.screens.csvstrategy

import androidx.compose.runtime.Composable
import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyAuditEntry
import com.moneymanager.domain.model.csvstrategy.CsvStrategyConfig
import com.moneymanager.domain.model.csvstrategy.FieldMapping
import com.moneymanager.domain.repository.AuditReadRepository
import com.moneymanager.domain.repository.CsvImportStrategyReadRepository
import com.moneymanager.ui.audit.AuditScreen
import com.moneymanager.ui.audit.AuditScreenData
import com.moneymanager.ui.audit.NamedConfigAuditDiffCard
import com.moneymanager.ui.audit.NamedConfigRevision
import com.moneymanager.ui.audit.computeNamedConfigAuditDiffs
import kotlinx.coroutines.flow.first

@Composable
fun CsvImportStrategyAuditScreen(
    strategyId: CsvImportStrategyId,
    auditRepository: AuditReadRepository,
    csvImportStrategyRepository: CsvImportStrategyReadRepository,
    onBack: () -> Unit,
) {
    AuditScreen(
        defaultTitle = "CSV Strategy Audit: $strategyId",
        entityTypeName = "CSV import strategy",
        loadKey = strategyId,
        loadData = {
            val entries = auditRepository.getAuditHistoryForCsvImportStrategy(strategyId)
            val current = csvImportStrategyRepository.getStrategyById(strategyId).first()
            AuditScreenData(
                title = "CSV Strategy Audit: ${current?.name ?: strategyId}",
                diffs =
                    computeNamedConfigAuditDiffs(
                        revisions = entries.map { it.toRevision() },
                        currentName = current?.name,
                        currentConfig = current?.config?.let(::flattenConfig),
                    ),
            )
        },
        diffKey = { it.id },
        onBack = onBack,
        diffCard = { diff -> NamedConfigAuditDiffCard(diff) },
    )
}

private fun CsvImportStrategyAuditEntry.toRevision() =
    NamedConfigRevision(
        id = id,
        auditTimestamp = auditTimestamp,
        auditType = auditType,
        revisionId = revisionId,
        name = name,
        config = flattenConfig(config),
        source = source,
    )

private fun flattenConfig(config: CsvStrategyConfig<FieldMapping>): Map<String, String> =
    buildMap {
        put("Identification columns", config.identificationColumns.sorted().joinToString(", "))
        config.fieldMappings.entries
            .sortedBy { it.key.name }
            .forEach { (field, mapping) -> put("Field: ${field.name}", mapping.toString()) }
        putIfNotEmpty("Attribute mappings", config.attributeMappings)
        putIfNotEmpty("Row rules", config.rowPreprocessingRules)
        putIfNotEmpty("Companion rules", config.companionTransactionRules)
        putIfNotEmpty("Content match rules", config.contentMatchRules)
        config.fileNamePattern?.let { put("File name pattern", it) }
        config.crossSourceReconcileWindowSeconds?.let { put("Cross-source reconcile window (seconds)", it.toString()) }
        config.conversionConfig?.let { put("Conversion config", it.toString()) }
        config.fundingAttributeMatch?.let { put("Funding attribute match", it.toString()) }
        config.tradeGroupConfig?.let { put("Trade group config", it.toString()) }
    }

private fun MutableMap<String, String>.putIfNotEmpty(
    label: String,
    items: List<Any>,
) {
    if (items.isNotEmpty()) put(label, items.joinToString("; "))
}
