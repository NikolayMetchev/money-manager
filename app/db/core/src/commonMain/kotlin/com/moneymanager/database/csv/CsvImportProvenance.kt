package com.moneymanager.database.csv

import com.moneymanager.domain.EntitySource
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.SourceRecorder
import com.moneymanager.domain.model.csv.CsvImportId
import com.moneymanager.importmodel.ImportProvenance
import com.moneymanager.importmodel.ImportRowKey

/**
 * [ImportProvenance] for CSV imports: records transfer sources via [EntitySource.csvImportRecorder]
 * and account/other-entity sources via [EntitySource.record].
 */
class CsvImportProvenance(
    private val entitySource: EntitySource,
    private val csvImportId: CsvImportId,
) : ImportProvenance {
    override fun transferRecorder(orderedRowKeys: List<ImportRowKey>): SourceRecorder {
        // createTransfers invokes the recorder once per transfer in list order, so a running index
        // maps each call back to its CSV row.
        var callIndex = 0
        return entitySource.csvImportRecorder(
            csvImportId = csvImportId,
            rowIndexForTransfer = {
                val rowKey = orderedRowKeys[callIndex++]
                (rowKey as ImportRowKey.CsvRow).rowIndex
            },
        )
    }

    override fun recordEntity(
        entityType: EntityType,
        entityId: Long,
        revisionId: Long,
    ) {
        entitySource.record(entityType, entityId, revisionId)
    }
}
