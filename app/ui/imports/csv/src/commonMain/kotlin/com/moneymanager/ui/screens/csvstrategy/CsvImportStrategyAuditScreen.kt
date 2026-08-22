package com.moneymanager.ui.screens.csvstrategy

import androidx.compose.runtime.Composable
import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.csvstrategy.AttributeColumnMapping
import com.moneymanager.domain.model.csvstrategy.CompanionTransactionRule
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyAuditEntry
import com.moneymanager.domain.model.csvstrategy.FieldMapping
import com.moneymanager.domain.model.csvstrategy.RowPreprocessingRule
import com.moneymanager.domain.model.csvstrategy.TransferField
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
                        currentConfig =
                            current?.let {
                                flattenStrategy(
                                    identificationColumns = it.identificationColumns,
                                    fieldMappings = it.fieldMappings,
                                    attributeMappings = it.attributeMappings,
                                    rowRules = it.rowPreprocessingRules,
                                    companionRules = it.companionTransactionRules,
                                )
                            },
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
        config =
            flattenStrategy(
                identificationColumns = identificationColumns,
                fieldMappings = fieldMappings,
                attributeMappings = attributeMappings,
                rowRules = rowPreprocessingRules,
                companionRules = companionTransactionRules,
            ),
        source = source,
    )

private fun flattenStrategy(
    identificationColumns: Set<String>,
    fieldMappings: Map<TransferField, FieldMapping>,
    attributeMappings: List<AttributeColumnMapping>,
    rowRules: List<RowPreprocessingRule>,
    companionRules: List<CompanionTransactionRule>,
): Map<String, String> =
    buildMap {
        put("Identification columns", identificationColumns.sorted().joinToString(", "))
        fieldMappings.entries
            .sortedBy { it.key.name }
            .forEach { (field, mapping) -> put("Field: ${field.name}", mapping.toString()) }
        if (attributeMappings.isNotEmpty()) {
            put("Attribute mappings", attributeMappings.joinToString("; ") { it.toString() })
        }
        if (rowRules.isNotEmpty()) {
            put("Row rules", rowRules.joinToString("; ") { it.toString() })
        }
        if (companionRules.isNotEmpty()) {
            put("Companion rules", companionRules.joinToString("; ") { it.toString() })
        }
    }
