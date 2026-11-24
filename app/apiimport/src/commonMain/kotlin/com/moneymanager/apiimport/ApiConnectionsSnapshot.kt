package com.moneymanager.apiimport

import com.moneymanager.domain.model.ApiCredential
import com.moneymanager.domain.model.ApiCredentialId
import com.moneymanager.domain.model.ApiSession
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.repository.ApiImportStrategyReadRepository
import com.moneymanager.domain.repository.ApiSessionImportRevision
import com.moneymanager.domain.repository.ApiSessionReadRepository
import kotlinx.coroutines.flow.first

/** What a connection's card needs to know about the strategy its credential uses. */
data class ApiConnectionInfo(
    val strategy: ApiImportStrategy?,
    /** Why the transaction feed can't be downloaded (see [regionBlockReason]), or null. */
    val transactionsBlockReason: String?,
) {
    val strategyName: String? get() = strategy?.name

    /** Sessions imported at this revision count as imported; older ones are outstanding again. */
    val currentRevision: Long? get() = strategy?.revisionId

    val requiresSigning: Boolean get() = strategy?.config?.signing != null
}

/** Everything the API sessions screen lists, read together so the parts never disagree. */
data class ApiConnectionsSnapshot(
    val credentials: List<ApiCredential> = emptyList(),
    val sessionsByCredential: Map<ApiCredentialId, List<ApiSession>> = emptyMap(),
    val importedSessionRevisions: Set<ApiSessionImportRevision> = emptySet(),
    /** Every strategy, by name, for the filter. */
    val strategies: List<ApiImportStrategy> = emptyList(),
    val infoByCredential: Map<ApiCredentialId, ApiConnectionInfo> = emptyMap(),
) {
    /** Sessions that were imported at any revision. */
    val everImportedSessionIds by lazy { importedSessionRevisions.mapTo(HashSet()) { it.sessionId } }
}

suspend fun loadApiConnectionsSnapshot(
    apiSessionRepository: ApiSessionReadRepository,
    apiImportStrategyRepository: ApiImportStrategyReadRepository,
    deviceId: DeviceId,
    transactionsBlockReason: (ApiImportStrategy?) -> String?,
): ApiConnectionsSnapshot {
    val credentials = apiSessionRepository.getAllCredentials()
    val sessions = apiSessionRepository.getSessionsByDevice(deviceId)
    val allStrategies = apiImportStrategyRepository.getAllStrategies().first()
    val strategyById = allStrategies.associateBy { it.id }
    return ApiConnectionsSnapshot(
        credentials = credentials,
        sessionsByCredential = sessions.filter { it.credentialId != null }.groupBy { it.credentialId!! },
        importedSessionRevisions = apiSessionRepository.getImportedSessionRevisions(),
        strategies = allStrategies.sortedBy { it.name.lowercase() },
        infoByCredential =
            credentials.associate { credential ->
                val strategy = credential.strategyId?.let { strategyById[it] }
                credential.id to ApiConnectionInfo(strategy, transactionsBlockReason(strategy))
            },
    )
}
