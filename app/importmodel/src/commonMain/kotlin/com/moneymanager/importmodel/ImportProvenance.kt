package com.moneymanager.importmodel

import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.SourceRecorder

/**
 * Records the source/provenance of entities created during an import, abstracting over the
 * CSV/QIF/API source kinds. Implementations live with each importer and delegate to the existing
 * `EntitySource` recorder factory.
 */
interface ImportProvenance {
    /**
     * A [SourceRecorder] for the transfers being created. `createTransfers` invokes [SourceRecorder.insert]
     * once per created transfer in the same order as [orderedRowKeys], so the recorder can map each
     * call by position to the originating [ImportRowKey] and attach the right source detail.
     */
    fun transferRecorder(orderedRowKeys: List<ImportRowKey>): SourceRecorder

    /**
     * A [SourceRecorder] for transfers that were UPDATED (existing transfers re-touched), invoked once
     * per updated transfer in the same order as [orderedRowKeys]. Defaults to the same mechanism as
     * [transferRecorder]; override only if updates need different source recording.
     */
    fun updatedTransferRecorder(orderedRowKeys: List<ImportRowKey>): SourceRecorder = transferRecorder(orderedRowKeys)

    /** Records provenance for a newly created account/person/ownership/etc. */
    fun recordEntity(
        entityType: EntityType,
        entityId: Long,
        revisionId: Long,
        rowKey: ImportRowKey?,
    )
}
