package com.moneymanager.importengineapi

import com.moneymanager.domain.model.JsonPath
import com.moneymanager.domain.model.Source

/**
 * Resolves a batch-level [Source] to the per-row source for a given [ImportRowKey], filling in the
 * row/record/json-path detail the key carries. This replaces the old positional source-recorder
 * mechanism: each transfer's source is derived from its own row key, so there is no dependence on the
 * order in which transfers are written.
 */
fun Source.forRow(rowKey: ImportRowKey): Source =
    when (this) {
        is Source.Csv -> copy(rowIndex = (rowKey as ImportRowKey.CsvRow).rowIndex)
        is Source.Qif -> copy(recordIndex = (rowKey as ImportRowKey.QifRecord).recordIndex)
        is Source.Api -> {
            val key = rowKey as ImportRowKey.ApiJsonPath
            copy(requestId = key.requestId, jsonPath = JsonPath(key.jsonPath))
        }
        Source.Manual,
        Source.SampleGenerator,
        Source.System,
        Source.Merge,
        Source.Unmerge,
        -> this
    }
