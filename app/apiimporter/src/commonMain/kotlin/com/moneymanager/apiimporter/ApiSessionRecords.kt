package com.moneymanager.apiimporter

import com.moneymanager.domain.model.ApiSession
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.model.apistrategy.ApiStrategyConfig
import com.moneymanager.domain.model.passthrough.PassThroughAccount
import com.moneymanager.domain.repository.assetAliasMap
import com.moneymanager.importengineapi.strategyConfigHash
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * What a session's import reads besides its stored responses, as one hash: the strategy, the counterparty account
 * names, the asset aliases and the pass-through accounts. A session's responses never change once downloaded, so a
 * re-import is how a change to any of these reaches what the session booked, and only such a change makes it run.
 */
suspend fun apiSessionInputsHash(
    strategy: ApiImportStrategy,
    counterpartyAccountNames: Map<String, String>,
    passThroughAccounts: List<PassThroughAccount>,
    assetAliases: Map<String, String>,
): String {
    val canonical =
        buildString {
            appendLine(strategy.id.toString())
            appendLine(CANONICAL_JSON.encodeToString(ApiStrategyConfig.serializer(), strategy.config))
            counterpartyAccountNames.entries
                .map { (name, account) -> "counterparty=$name|$account" }
                .sorted()
                .forEach(::appendLine)
            assetAliases.entries
                .map { (alias, code) -> "alias=$alias|$code" }
                .sorted()
                .forEach(::appendLine)
            appendLine(CANONICAL_JSON.encodeToString(PASS_THROUGHS_SERIALIZER, passThroughAccounts.sortedBy { it.id.value }))
        }
    return strategyConfigHash(canonical)
}

/**
 * True when importing [session] again would change nothing: it was imported, and each of its import records carries
 * the hash of what it would read now ([apiSessionInputsHash]). Its records are hashed only once a whole import of it
 * completed, so a session a stopped run left behind, or one imported before its records were hashed, always runs.
 */
suspend fun isApiSessionUnchanged(
    session: ApiSession,
    strategy: ApiImportStrategy,
    reads: ApiImportReads,
    counterpartyAccountNames: Map<String, String>,
    passThroughAccounts: List<PassThroughAccount>,
): Boolean {
    if (reads.apiSessionRepository.getImportedSessionRevisions().none { it.sessionId == session.id }) return false
    val records = reads.importRecordRepository.getApiSessionRecords(session.id)
    if (records.isEmpty()) return false
    val inputsHash =
        apiSessionInputsHash(strategy, counterpartyAccountNames, passThroughAccounts, reads.cryptoRepository.assetAliasMap())
    return records.all { it.contentHash == inputsHash }
}

private val CANONICAL_JSON = Json { encodeDefaults = true }
private val PASS_THROUGHS_SERIALIZER = ListSerializer(PassThroughAccount.serializer())
