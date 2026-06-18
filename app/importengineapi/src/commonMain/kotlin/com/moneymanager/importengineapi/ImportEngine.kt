package com.moneymanager.importengineapi

/**
 * The central import engine: takes a fully-built [ImportBatch] and performs the entire import —
 * creates (or reuses) accounts, people and ownerships, resolves transfer account references,
 * deduplicates against existing transfers, bulk-creates new transfers, applies updates for changed
 * duplicates, and records the source of every entity/transfer it writes.
 *
 * This is the **only** component that writes imported entities, transfers and their sources to the
 * database. CSV/QIF/API importers live in modules that depend solely on this interface (never on the
 * database) and only build an [ImportBatch]; the binding to the database-backed implementation
 * ([com.moneymanager.importer.ImportEngineImpl]) happens exclusively in the DI module.
 */
interface ImportEngine {
    /**
     * @param batchSize How many transfers to write per database transaction. The default writes the
     *   whole batch in a single transaction (the behaviour all importers relied on historically). Large
     *   producers (e.g. the sample-data generator, which creates hundreds of thousands of transfers) pass
     *   a smaller value so the write is chunked into several transactions and [onProgress] reports
     *   fine-grained progress instead of freezing on one giant transaction.
     */
    suspend fun import(
        batch: ImportBatch,
        onProgress: (suspend (ImportProgress) -> Unit)? = null,
        batchSize: Int = Int.MAX_VALUE,
    ): ImportResult
}
