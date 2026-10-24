package com.moneymanager.apiimporter

import co.touchlab.kermit.Logger
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.ApiCredentialId
import com.moneymanager.domain.model.ApiSession
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.model.passthrough.PassThroughAccount
import com.moneymanager.domain.repository.ApiSessionReadRepository
import com.moneymanager.importengineapi.AffectedRecordsRerunner
import com.moneymanager.importengineapi.ApiSessionMutation
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ManualEdit
import com.moneymanager.importengineapi.RerunReads
import com.moneymanager.importengineapi.SourceRerunPlan
import com.moneymanager.importengineapi.SourceRerunResult
import com.moneymanager.importengineapi.executeSourceRerun
import com.moneymanager.importengineapi.markApiSessionImported
import com.moneymanager.importengineapi.planSourceRerun
import com.moneymanager.tasks.PhasedProgress
import com.moneymanager.tasks.Progress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.time.Clock

private val logger = Logger.withTag("ApiReimport")

/** Plans re-importing an API session: what it created, the files matched against that, and its hand edits. */
suspend fun planApiReimport(
    sessionId: ApiSessionId,
    apiSessionRepository: ApiSessionReadRepository,
    reads: RerunReads,
): SourceRerunPlan =
    planSourceRerun(
        source = Source.Api(sessionId),
        tradeIds = apiSessionRepository.getTradeIdsCreatedBySession(sessionId),
        createdAccountIds = apiSessionRepository.getAccountIdsCreatedBySession(sessionId),
        reads = reads,
    )

/** What re-running a session's stored responses imported. */
data class ApiRerunCounts(
    val transactionsImported: Int,
    val tradesImported: Int,
)

/**
 * Re-imports [session] under its current [strategy], like every other source ([executeSourceRerun]):
 * deletes what the session created, then re-parses its stored responses from scratch. Deleting first
 * matters: with the session's own transfers still present, the deduper would classify the re-parsed
 * items DUPLICATE against themselves (by `apiId`) before any reconcile ran.
 */
@Suppress("LongParameterList")
suspend fun executeApiReimport(
    plan: SourceRerunPlan,
    session: ApiSession,
    strategy: ApiImportStrategy,
    apiReads: ApiImportReads,
    reads: RerunReads,
    maintenance: Maintenance,
    importEngine: ImportEngine,
    rerunAffected: AffectedRecordsRerunner?,
    counterpartyAccountNames: Map<String, String> = emptyMap(),
    passThroughAccounts: List<PassThroughAccount> = emptyList(),
    onProgress: (suspend (Progress) -> Unit)? = null,
    refreshViews: Boolean = true,
): SourceRerunResult<ApiRerunCounts> {
    require((plan.source as? Source.Api)?.sessionId == session.id) {
        "plan is for ${plan.source} but executeApiReimport was called with session ${session.id}"
    }
    val importStartedAt = Clock.System.now()
    return importEngine.executeSourceRerun(
        plan = plan,
        resetRecords = ImportBatch(apiSessionMutations = listOf(ApiSessionMutation.DeleteResponseTransactionsBySession(session.id))),
        reads = reads,
        maintenance = maintenance,
        rerunAffected = rerunAffected,
        onProgress = onProgress,
        refreshViews = refreshViews,
    ) {
        val rerun =
            importApiSession(
                reads = apiReads,
                sessionId = session.id,
                strategy = strategy,
                importEngine = importEngine,
                counterpartyAccountNames = counterpartyAccountNames,
                passThroughAccounts = passThroughAccounts,
                onProgress = onProgress,
                engineBatchSize = if (onProgress == null) Int.MAX_VALUE else API_ENGINE_BATCH_SIZE,
            )
        importEngine.markApiSessionImported(
            id = session.id,
            revisionId = strategy.revisionId,
            importedAt = Clock.System.now(),
            importDurationMillis = (Clock.System.now() - importStartedAt).inWholeMilliseconds,
        )
        ApiRerunCounts(transactionsImported = rerun.transactionCount, tradesImported = rerun.tradeCount)
    }
}

/** One session a bulk re-import could not complete; the run carried on with the rest. */
data class ApiSessionReimportFailure(
    val sessionId: ApiSessionId,
    val message: String,
)

/** Aggregated outcome of [bulkReimportApiSessions]. */
data class ApiBulkReimportResult(
    /** Sessions that completed — failed ones are in [failures], not counted here. */
    val sessionsReimported: Int,
    val failures: List<ApiSessionReimportFailure> = emptyList(),
    val reappliedEdits: Int = 0,
    /** Hand edits whose transaction the re-import didn't create again, so they were dropped. */
    val unmatchedEdits: List<ManualEdit> = emptyList(),
)

/**
 * Re-imports every already-imported session of [credentialId], oldest first. Each session's delete
 * and rerun happen back to back rather than deleting every session's artifacts up front: the
 * un-exclusion check in [executeApiReimport] re-reads the live relationship state at the time it
 * runs, so a partner still reconciled against a not-yet-processed sibling session correctly stays
 * excluded until that session's turn — the ordering of delete vs. rerun across sessions doesn't
 * change the outcome, only the ordering of sessions relative to each other (oldest first, so an
 * earlier session's re-created transfers exist before a later session's own dedupe runs against them).
 *
 * A session that fails does not abort the run: it is logged, recorded in
 * [ApiBulkReimportResult.failures] and the remaining sessions are still re-imported — mirroring how
 * the CSV and QIF bulk re-import collects per-file failures. Materialized views are refreshed once at the end
 * whatever happens, since the sessions that did run have already changed the data they describe.
 */
@Suppress("LongParameterList")
suspend fun bulkReimportApiSessions(
    credentialId: ApiCredentialId,
    strategy: ApiImportStrategy,
    apiReads: ApiImportReads,
    reads: RerunReads,
    maintenance: Maintenance,
    importEngine: ImportEngine,
    rerunAffected: AffectedRecordsRerunner?,
    counterpartyAccountNames: Map<String, String> = emptyMap(),
    passThroughAccounts: List<PassThroughAccount> = emptyList(),
    onProgress: (suspend (Progress) -> Unit)? = null,
): ApiBulkReimportResult {
    val apiSessionRepository = apiReads.apiSessionRepository
    val importedSessionIds = apiSessionRepository.getImportedSessionRevisions().map { it.sessionId }.toSet()
    val sessions =
        apiSessionRepository
            .getSessionsByCredential(credentialId)
            .filter { it.id in importedSessionIds }
            .sortedBy { it.createdAt }

    var reapplied = 0
    val unmatched = mutableListOf<ManualEdit>()
    return reimportSessionsResiliently(sessions, maintenance, onProgress) { session, sessionProgress ->
        val result =
            executeApiReimport(
                plan = planApiReimport(session.id, apiSessionRepository, reads),
                session = session,
                strategy = strategy,
                apiReads = apiReads,
                reads = reads,
                maintenance = maintenance,
                importEngine = importEngine,
                rerunAffected = rerunAffected,
                counterpartyAccountNames = counterpartyAccountNames,
                passThroughAccounts = passThroughAccounts,
                onProgress = sessionProgress,
                refreshViews = false,
            )
        reapplied += result.reappliedEdits.size
        unmatched += result.unmatchedEdits
    }.copy(reappliedEdits = reapplied, unmatchedEdits = unmatched)
}

/**
 * Runs [reimport] over [sessions] in order, tolerating a failing session, and refreshes the
 * materialized views exactly once at the end — including on the failure path, since a session that
 * got as far as deleting its transfers has already invalidated them.
 */
internal suspend fun reimportSessionsResiliently(
    sessions: List<ApiSession>,
    maintenance: Maintenance,
    onProgress: (suspend (Progress) -> Unit)?,
    reimport: suspend (ApiSession, (suspend (Progress) -> Unit)?) -> Unit,
): ApiBulkReimportResult {
    var reimported = 0
    val failures = mutableListOf<ApiSessionReimportFailure>()
    val bar = PhasedProgress(onProgress)
    val runFailure =
        runCatching {
            for ((index, session) in sessions.withIndex()) {
                // Each session owns the slice of the bar its position gives it; its own phases sweep
                // that slice, so the run reads as one bar rather than one that restarts per session.
                val base = index.toFloat() / sessions.size
                val span = 1f / sessions.size
                val label = "Session ${index + 1} of ${sessions.size}"
                val sessionProgress: (suspend (Progress) -> Unit)? =
                    if (onProgress == null) {
                        null
                    } else {
                        { inner ->
                            bar.emit(
                                base = base,
                                span = span,
                                detail = "$label: ${inner.detail}",
                                innerFraction = inner.fraction,
                                processed = index,
                                total = sessions.size,
                            )
                        }
                    }
                bar.emit(base = base, detail = label, processed = index, total = sessions.size)
                try {
                    reimport(session, sessionProgress)
                    reimported++
                } catch (expected: CancellationException) {
                    throw expected
                } catch (expected: Exception) {
                    logger.e(expected) { "Bulk API re-import failed for session ${session.id}: ${expected.message}" }
                    failures += ApiSessionReimportFailure(session.id, expected.message ?: "Re-import failed")
                }
            }
        }.exceptionOrNull()

    // NonCancellable so a cancelled run doesn't leave stale balances behind either.
    val refreshFailure =
        runCatching { withContext(NonCancellable) { maintenance.refreshMaterializedViews() } }.exceptionOrNull()

    // A refresh that fails while the run is already unwinding must not replace the exception that ended
    // it — the caller needs to see the cancellation or error that actually happened.
    if (runFailure != null && refreshFailure != null) {
        logger.e(refreshFailure) { "Refreshing views after an aborted bulk API re-import failed" }
    }
    (runFailure ?: refreshFailure)?.let { throw it }
    return ApiBulkReimportResult(sessionsReimported = reimported, failures = failures)
}
