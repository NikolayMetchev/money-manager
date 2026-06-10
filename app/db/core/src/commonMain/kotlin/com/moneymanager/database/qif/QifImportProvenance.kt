package com.moneymanager.database.qif

import com.moneymanager.domain.EntitySource
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.SourceRecorder
import com.moneymanager.domain.model.qif.QifImportId
import com.moneymanager.importmodel.ImportProvenance
import com.moneymanager.importmodel.ImportRowKey

/**
 * [ImportProvenance] for QIF imports: records transfer sources via [EntitySource.qifImportRecorder]
 * and account/other-entity sources via [EntitySource.record].
 */
class QifImportProvenance(
    private val entitySource: EntitySource,
    private val qifImportId: QifImportId,
) : ImportProvenance {
    override fun transferRecorder(orderedRowKeys: List<ImportRowKey>): SourceRecorder {
        // createTransfers invokes the recorder once per transfer in list order, so a running index
        // maps each call back to its QIF record (splits of one record share a recordIndex).
        var callIndex = 0
        return entitySource.qifImportRecorder(
            qifImportId = qifImportId,
            recordIndexForTransfer = {
                val rowKey = orderedRowKeys[callIndex++]
                require(rowKey is ImportRowKey.QifRecord) { "Expected a QIF row key, got ${rowKey::class.simpleName}" }
                rowKey.recordIndex
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
