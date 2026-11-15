package com.moneymanager.domain.model.observation

import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.CsvImportId
import kotlin.jvm.JvmInline

@JvmInline
value class ImportRecordId(
    val id: Long,
) {
    override fun toString() = id.toString()
}

/** Where an import record comes from: the stable key a re-import finds it by again. */
sealed interface ImportRecordKey {
    /** A staged CSV, QIF, OFX, Excel or PDF row. */
    data class CsvRow(
        val csvImportId: CsvImportId,
        val rowIndex: Long,
    ) : ImportRecordKey

    /** An item of a downloaded API response, at [jsonPath] in the response to [requestId]. */
    data class ApiItem(
        val sessionId: ApiSessionId,
        val requestId: ApiRequestId,
        val jsonPath: String,
    ) : ImportRecordKey
}

/** One staged row or API item a source reports, with the hash of its content as staged. */
data class ImportRecord(
    val id: ImportRecordId,
    val key: ImportRecordKey,
    val contentHash: String,
)

@JvmInline
value class ImportGroupId(
    val id: Long,
) {
    override fun toString() = id.toString()
}

/**
 * The records one `LegGroupRule` joins into one output: [strategyId] is the CSV or API strategy's id, [groupName]
 * the rule's name and [keyValue] the key its records share.
 */
data class ImportGroupKey(
    val strategyId: String,
    val groupName: String,
    val keyValue: String,
)

data class ImportGroup(
    val id: ImportGroupId,
    val key: ImportGroupKey,
    val members: Set<ImportRecordId>,
)
