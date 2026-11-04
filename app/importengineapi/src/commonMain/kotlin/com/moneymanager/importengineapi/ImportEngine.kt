package com.moneymanager.importengineapi

import com.moneymanager.tasks.Progress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The [ImportEngine.writeVersion] of an engine that doesn't report its writes. */
val NO_WRITES: StateFlow<Long> = MutableStateFlow(0L).asStateFlow()

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
     * Counts the batches this engine has applied (successfully or not, as a failed one may have written part of its
     * batch), so a screen that caches what it derived from the database can tell when to derive it again.
     */
    val writeVersion: StateFlow<Long> get() = NO_WRITES

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
        onProgress: (suspend (Progress) -> Unit)? = null,
        batchSize: Int = Int.MAX_VALUE,
    ): ImportResult
}
