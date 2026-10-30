package com.moneymanager.domain.model

import kotlin.time.Instant

/**
 * A file staged for import (a CSV, QIF, Excel or PDF file): what the imports screens group into tabs, folders and
 * strategies, the same way for every format.
 */
interface StagedImport<Id : Any> {
    val id: Id
    val originalFileName: String
    val importTimestamp: Instant
    val applicationCount: Int
    val errorCount: Int
    val lastAppliedStrategyId: CsvImportStrategyId?
    val lastAppliedStrategyName: String?
    val lastAppliedAt: Instant?

    /** User dismissed this file: hidden from the actionable lists and skipped by "Import all". */
    val ignored: Boolean
}
