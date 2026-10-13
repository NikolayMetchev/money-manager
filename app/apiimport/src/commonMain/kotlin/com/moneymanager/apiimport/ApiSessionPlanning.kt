package com.moneymanager.apiimport

import co.touchlab.kermit.Logger
import com.moneymanager.apiimporter.ApiSessionImportResult
import com.moneymanager.domain.model.ApiCredential
import com.moneymanager.domain.model.ApiCredentialId
import com.moneymanager.domain.model.ApiImportStrategyId
import com.moneymanager.domain.model.ApiSession
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.repository.ApiSessionImportRevision
import kotlinx.coroutines.CancellationException

private val logger = Logger.withTag("ApiSessionPlanning")

/**
 * Why [strategy]'s transaction feed can't be downloaded from [country], or null when it can. A strategy
 * that signs statement requests may only serve some countries (Wise's SCA statements); the rest of its
 * download (accounts, people) still runs.
 */
fun regionBlockReason(
    strategy: ApiImportStrategy?,
    country: String?,
): String? {
    val countries =
        strategy
            ?.config
            ?.signing
            ?.statementCountries
            .orEmpty()
    if (countries.isEmpty() || (country != null && country in countries)) return null
    return "Transaction download isn't available for your region" +
        (country?.let { " ($it)" } ?: "") +
        ". ${strategy?.name ?: "This provider"} only supports statements for: " +
        countries.sorted().joinToString(", ") + "."
}

/**
 * A session counts as imported only at the credential's current strategy revision — a session
 * imported under an older revision is outstanding again, because re-importing it would apply the
 * updated strategy.
 */
fun sessionImportedAtCurrentRevision(
    sessionId: ApiSessionId,
    currentRevision: Long?,
    importedSessionRevisions: Set<ApiSessionImportRevision>,
): Boolean =
    currentRevision?.let { revision ->
        ApiSessionImportRevision(sessionId, revision) in importedSessionRevisions
    } == true

fun filterCredentialsByStrategy(
    credentials: List<ApiCredential>,
    strategyIdByCredential: Map<ApiCredentialId, ApiImportStrategyId?>,
    selectedStrategyId: ApiImportStrategyId?,
): List<ApiCredential> =
    if (selectedStrategyId == null) {
        credentials
    } else {
        credentials.filter { strategyIdByCredential[it.id] == selectedStrategyId }
    }

data class ApiSessionsSplit(
    val outstandingByCredential: Map<ApiCredentialId, List<ApiSession>>,
    val importedByCredential: Map<ApiCredentialId, List<ApiSession>>,
) {
    val outstandingCount: Int get() = outstandingByCredential.values.sumOf { it.size }
    val importedCount: Int get() = importedByCredential.values.sumOf { it.size }
}

fun splitSessionsByImportState(
    credentials: List<ApiCredential>,
    sessionsByCredential: Map<ApiCredentialId, List<ApiSession>>,
    currentStrategyRevisionByCredential: Map<ApiCredentialId, Long?>,
    importedSessionRevisions: Set<ApiSessionImportRevision>,
): ApiSessionsSplit {
    val outstanding = mutableMapOf<ApiCredentialId, List<ApiSession>>()
    val imported = mutableMapOf<ApiCredentialId, List<ApiSession>>()
    credentials.forEach { credential ->
        val (importedSessions, outstandingSessions) =
            sessionsByCredential[credential.id].orEmpty().partition { session ->
                sessionImportedAtCurrentRevision(
                    sessionId = session.id,
                    currentRevision = currentStrategyRevisionByCredential[credential.id],
                    importedSessionRevisions = importedSessionRevisions,
                )
            }
        outstanding[credential.id] = outstandingSessions
        imported[credential.id] = importedSessions
    }
    return ApiSessionsSplit(outstandingByCredential = outstanding, importedByCredential = imported)
}

/**
 * Runs one step of "Download & import all", logging its failure instead of letting it cancel the other
 * downloads and the import queue. Null when it failed; cancellation still propagates.
 */
internal suspend fun <T> isolatedBulkStep(
    description: String,
    step: suspend () -> T,
): T? =
    try {
        step()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (expected: Exception) {
        logger.e(expected) { "$description failed: ${expected.message}" }
        null
    }

/**
 * The sessions "Download & import all" imports: every session of [credentialIds] that has never been
 * imported, oldest first, so overlapping sessions land in the order they were downloaded.
 */
fun sessionsToImportInBulk(
    sessions: List<ApiSession>,
    credentialIds: Set<ApiCredentialId>,
    everImportedSessionIds: Set<ApiSessionId>,
): List<ApiSession> =
    sessions
        .filter { it.credentialId in credentialIds && it.id !in everImportedSessionIds }
        .sortedWith(compareBy<ApiSession> { it.createdAt }.thenBy { it.id.id })

internal fun bulkDownloadImportSummary(
    credentialCount: Int,
    downloadedCount: Int,
    sessionCount: Int,
    importedCount: Int,
    needsReviewCount: Int,
): String =
    buildList {
        add("Downloaded $downloadedCount of $credentialCount connection(s).")
        add("Imported $importedCount of $sessionCount session(s).")
        if (needsReviewCount > 0) {
            add("$needsReviewCount session(s) need new counterparties confirmed; import them from their cards.")
        }
        val failedCount = sessionCount - importedCount - needsReviewCount
        if (failedCount > 0) add("$failedCount import(s) failed or were skipped; see their tasks.")
    }.joinToString(" ")

fun ApiSessionImportResult.displaySummary(): String =
    buildString {
        append("Import complete: ")
        append(accountCount)
        append(" account(s), ")
        append(transactionCount)
        append(" imported transaction(s)")
        if (personCount > 0) {
            append(", ")
            append(personCount)
            append(" person(s) created")
        }
        if (duplicateCount > 0) {
            append(", ")
            append(duplicateCount)
            append(" duplicate(s)")
        }
        if (errorCount > 0) {
            append(", ")
            append(errorCount)
            append(" error(s)")
        }
        append(".")
    }
