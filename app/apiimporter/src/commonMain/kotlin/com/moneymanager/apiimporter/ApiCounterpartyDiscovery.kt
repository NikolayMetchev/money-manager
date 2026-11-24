package com.moneymanager.apiimporter

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.ApiRequest
import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiResponse
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.repository.AccountAttributeReadRepository
import com.moneymanager.domain.repository.ApiSessionReadRepository
import com.moneymanager.tasks.Progress
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first

suspend fun discoverApiCounterpartiesToCreate(
    apiSessionRepository: ApiSessionReadRepository,
    accountAttributeRepository: AccountAttributeReadRepository,
    sessionId: ApiSessionId,
    strategy: ApiImportStrategy,
    onProgress: suspend (Progress) -> Unit = {},
): List<ApiCounterpartySuggestion> {
    // Only a bank feed names counterparty ids; an exchange strategy has nothing to suggest.
    val counterpartyIdField =
        strategy.config.bankTransactions
            ?.transactionMappings
            ?.counterpartyIdField
            ?: return emptyList()
    // Reported as sub-steps so the (potentially slow) preparation phase shows what it is doing rather than
    // a single opaque "Preparing import…". Left indeterminate (no fraction) so the bar does not fill and
    // then reset when the engine phases take over with their own 0–100% progress.
    onProgress(Progress(detail = "Scanning existing accounts..."))
    val existingCounterpartyIds = loadCounterpartyIdIndex(accountAttributeRepository).keys
    onProgress(Progress(detail = "Reading downloaded transactions..."))
    val requestsById = apiSessionRepository.getRequestsBySession(sessionId).associateBy { it.id }
    val transactionResponses =
        apiSessionRepository
            .getResponsesBySession(sessionId)
            .filter { requestsById[it.requestId]?.resolveAccountExternalId(strategy) != null }

    return collectCounterpartiesFromResponses(
        responses = transactionResponses,
        strategy = strategy,
        counterpartyIdField = counterpartyIdField,
        onResponseProcessed = { done, total ->
            onProgress(Progress(detail = "Finding new counterparties ($done/$total)..."))
        },
    ).filterKeys { it !in existingCounterpartyIds }
        .map { (counterpartyId, names) ->
            ApiCounterpartySuggestion(
                counterpartyId = counterpartyId,
                suggestedAccountName = suggestCounterpartyName(names),
                downloadedNames = names.distinct().sorted(),
            )
        }.sortedBy { it.suggestedAccountName }
}

/**
 * Allocates one counterparty account intent per distinct counterparty id, with its canonical name (the
 * user-confirmed name when provided, else the most common downloaded name). Each id's intent is allocated
 * once here so transaction-time [BatchAccountResolver.resolveCounterpartyAccount] reuses it by id rather
 * than re-deriving a (possibly different) per-transaction name; the engine then creates/reuses the account.
 */
internal suspend fun precreateCounterparties(
    transactionResponses: List<ApiResponse>,
    sessionId: ApiSessionId,
    requestsById: Map<ApiRequestId, ApiRequest>,
    strategy: ApiImportStrategy,
    accountResolver: BatchAccountResolver,
    counterpartyIdField: String?,
    counterpartyAccountNames: Map<String, String>,
) {
    if (counterpartyIdField == null) return

    val config = strategy.config

    val counterparties =
        coroutineScope {
            transactionResponses
                .map { response ->
                    async {
                        val request = requestsById[response.requestId] ?: return@async emptyList()
                        parseTransactionsWithPath(response.json, strategy).mapNotNull { item ->
                            if (item.hasFixedCounterparty) return@mapNotNull null
                            val counterpartyId =
                                item.counterpartyIdentity(counterpartyIdField, config.peopleMappings)
                                    ?: return@mapNotNull null
                            val downloadedName = item.counterpartyName()
                            CounterpartyImportCandidate(
                                counterpartyId = counterpartyId,
                                downloadedName = downloadedName,
                                apiSource = AccountApiSource(sessionId, request.id, item.counterpartyJsonPath(config.peopleMappings)),
                                dedupeKey =
                                    item.personalCounterpartyIdentity(config.peopleMappings)?.dedupeKey
                                        ?: bankDedupeKeyFromCounterpartyId(counterpartyId),
                            )
                        }
                    }
                }.awaitAll()
                .flatten()
                .groupBy { it.counterpartyId }
        }

    for ((counterpartyId, candidates) in counterparties) {
        val accountName =
            counterpartyAccountNames[counterpartyId]
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: suggestCounterpartyName(candidates.map { it.downloadedName })
        accountResolver.resolveCounterpartyAccount(
            counterpartyId = counterpartyId,
            dedupeKey = candidates.firstNotNullOfOrNull { it.dedupeKey },
            builtInType = null,
            name = accountName,
            source = candidates.first().apiSource.toSource(),
        )
    }
}

internal suspend fun collectCounterpartiesFromResponses(
    responses: List<ApiResponse>,
    strategy: ApiImportStrategy,
    counterpartyIdField: String,
    onResponseProcessed: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
): Map<String, List<String>> =
    responses
        .flatMapIndexed { index, response ->
            onResponseProcessed(index + 1, responses.size)
            parseTransactionsWithPath(response.json, strategy).mapNotNull { item ->
                // Skip transactions handled by built-in type logic — their counterpartyId is
                // irrelevant because they all route to a single built-in account.
                if (item.hasFixedCounterparty) return@mapNotNull null
                val counterpartyId =
                    item.counterpartyIdentity(counterpartyIdField, strategy.config.peopleMappings)
                        ?: return@mapNotNull null
                // Use only the name the rules give, not the description fallback. Description-based
                // names are per-transaction references that would corrupt the name suggestion when the
                // same counterpartyId appears in transactions with and without a proper name.
                counterpartyId to item.counterpartyAccountName
            }
        }.groupBy(
            keySelector = { it.first },
            valueTransform = { it.second },
        ).mapValues { (_, names) -> names.filterNotNull() }

internal suspend fun loadCounterpartyIdIndex(accountAttributeRepository: AccountAttributeReadRepository): Map<String, AccountId> =
    loadAccountExternalIdIndex(accountAttributeRepository)

internal suspend fun loadAccountExternalIdIndex(accountAttributeRepository: AccountAttributeReadRepository): Map<String, AccountId> {
    // One query for every account's attributes rather than a per-account read: this used to be an N+1
    // over the whole account table, which dominated the "Preparing import…" phase on a large database.
    val index = mutableMapOf<String, AccountId>()
    for (attr in accountAttributeRepository.getAll().first()) {
        if (attr.attributeType.id == ACCOUNT_EXTERNAL_ID_ATTR_TYPE_ID) {
            index[attr.value] = attr.accountId
        }
    }
    return index
}

/**
 * Suggests a single account name for a counterparty seen under several spellings (e.g. Monzo prefixes a
 * per-transaction reference number, yielding "120386070PAXOS TE", "991204771PAXOS TE", …). Prefers the
 * longest substring common to *all* the spellings ("PAXOS TE"), which strips such varying noise; falls
 * back to the first non-blank name when the names share nothing meaningful.
 */
internal fun suggestCounterpartyName(names: List<String>): String {
    val nonBlank = names.filter { it.isNotBlank() }
    if (nonBlank.isEmpty()) return "Unknown"
    val common = longestCommonSubstring(nonBlank).trim()
    return common.takeIf { it.length >= MIN_COMMON_NAME_LENGTH } ?: nonBlank.first()
}

/** Minimum length of a shared substring before it is trusted as a name rather than incidental overlap. */
internal const val MIN_COMMON_NAME_LENGTH = 3

/**
 * Returns the leftmost longest substring contained in every one of [strings] (empty when there is none).
 * Any such substring must occur in the shortest string, so it enumerates that string's substrings from
 * longest to shortest and returns the first one present in all others. Inputs here are short and few.
 */
internal fun longestCommonSubstring(strings: List<String>): String {
    if (strings.isEmpty()) return ""
    val shortest = strings.minBy { it.length }
    for (length in shortest.length downTo 1) {
        for (start in 0..shortest.length - length) {
            val candidate = shortest.substring(start, start + length)
            if (strings.all { it.contains(candidate) }) return candidate
        }
    }
    return ""
}

internal data class CounterpartyImportCandidate(
    val counterpartyId: String,
    val downloadedName: String,
    val apiSource: AccountApiSource,
    // Bank identity ("sortCode|accountNumber") when the counterparty carries one; lets the resolver
    // merge it into an account that shares those bank details (e.g. your own account at another
    // provider) instead of minting a duplicate.
    val dedupeKey: String?,
)
