package com.moneymanager.importengineapi

/**
 * The central write engine. It has a **single** entry point, [import], which applies a declarative
 * [ImportBatch]: bulk imports (CSV/QIF/API) and every manual edit (create/update/delete of transfers,
 * accounts, categories, people and ownerships, plus account merge/unmerge) are all expressed as data in
 * the batch — the UI never writes through repositories directly. [import] consults an [EditGate] once, so
 * the app can block all writes at one place when they would be unsafe (e.g. a cloud-backed database whose
 * remote copy is ahead).
 *
 * The database-backed implementation (`com.moneymanager.importer.ImportEngineImpl`) owns the import logic
 * and source/provenance recording; CSV/QIF/API importers depend only on this interface and build an
 * [ImportBatch]. The binding to the implementation happens where services are assembled.
 */
interface ImportEngine {
    /**
     * Applies [batch] and returns the outcome (counts, per-row outcomes, and the real ids generated for
     * the batch's create intents — see [ImportResult.createdAccountIds]/`createdCategoryIds`/`createdPersonIds`).
     *
     * @param batchSize How many transfers to write per database transaction. The default writes the whole
     *   batch in a single transaction (the behaviour all importers relied on historically). Large producers
     *   (e.g. the sample-data generator) pass a smaller value so the write is chunked and [onProgress]
     *   reports fine-grained progress instead of freezing on one giant transaction.
     */
    suspend fun import(
        batch: ImportBatch,
        onProgress: (suspend (ImportProgress) -> Unit)? = null,
        batchSize: Int = Int.MAX_VALUE,
    ): ImportResult
}
