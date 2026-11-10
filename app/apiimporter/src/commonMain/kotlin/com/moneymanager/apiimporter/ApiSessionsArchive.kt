package com.moneymanager.apiimporter

import com.moneymanager.domain.model.ApiCredentialId
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.repository.ApiImportStrategyReadRepository
import com.moneymanager.domain.repository.ApiSessionReadRepository
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.createApiSession
import com.moneymanager.importengineapi.deleteApiSession
import com.moneymanager.importengineapi.ensureApiCredential
import com.moneymanager.importengineapi.insertApiRequest
import com.moneymanager.importengineapi.insertApiResponse
import com.moneymanager.importengineapi.recordApiDownloadCoverage
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Instant

/** What [ApiSessionsArchive.import] did with the file. */
data class ApiSessionsArchiveImportResult(
    val sessionsImported: Int,
    /** Sessions already present (same strategy and creation time), left untouched. */
    val sessionsAlreadyPresent: Int,
    /** Names of strategies in the file that this database has no strategy for; their sessions were skipped. */
    val missingStrategies: Set<String>,
) {
    fun summary(): String =
        buildString {
            append("Imported $sessionsImported session(s)")
            if (sessionsAlreadyPresent > 0) append(", $sessionsAlreadyPresent already present")
            if (missingStrategies.isNotEmpty()) {
                append(
                    ". Skipped sessions for unknown strategies: ${missingStrategies.sorted().joinToString()}",
                )
            }
        }
}

/** The export file's shape: what [ApiSessionsArchive] writes and reads. */
@Serializable
private data class ArchiveFile(
    val version: Long,
    val strategies: List<ArchiveStrategy>,
)

@Serializable
private data class ArchiveStrategy(
    val name: String,
    val sessions: List<ArchiveSession>,
)

@Serializable
private data class ArchiveSession(
    val createdAt: Long,
    /** Endpoint key to the epoch millisecond it is downloaded up to. */
    val coverage: Map<String, Long>,
    /** In the order they were made. */
    val requests: List<ArchiveRequest>,
)

@Serializable
private data class ArchiveRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    /** Null for a request that never received a response. */
    val response: String?,
)

/**
 * Exports every API session's recorded traffic (requests, response bodies, download coverage) to one JSON
 * document and re-imports it, so a development database can be thrown away without re-downloading
 * everything. Only the downloaded data travels: import state (which transactions were created) is not
 * exported, because importing the restored sessions again regenerates it. Request headers are already
 * redacted when recorded, and credentials stay in the vault, so the file holds no secrets.
 */
object ApiSessionsArchive {
    private const val VERSION = 1L
    private val json =
        Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

    suspend fun export(
        sessions: ApiSessionReadRepository,
        strategies: ApiImportStrategyReadRepository,
    ): String {
        val strategyById = strategies.getAllStrategies().first().associateBy { it.id }
        val exported =
            sessions.getAllCredentials().mapNotNull { credential ->
                val strategy = credential.strategyId?.let(strategyById::get) ?: return@mapNotNull null
                val sessionRows = sessions.getSessionsByCredential(credential.id).sortedBy { it.createdAt }
                if (sessionRows.isEmpty()) return@mapNotNull null
                ArchiveStrategy(
                    name = strategy.name,
                    sessions =
                        sessionRows.map { session ->
                            val responseByRequest = sessions.getResponsesBySession(session.id).associateBy { it.requestId }
                            ArchiveSession(
                                createdAt = session.createdAt.toEpochMilliseconds(),
                                coverage =
                                    sessions
                                        .getDownloadCoverageBySession(session.id)
                                        .mapValues { it.value.toEpochMilliseconds() },
                                requests =
                                    sessions
                                        .getRequestsBySession(session.id)
                                        .sortedWith(compareBy({ it.requestedAt }, { it.id.id }))
                                        .map { request ->
                                            ArchiveRequest(
                                                method = request.method,
                                                url = request.url,
                                                headers = request.headers.associate { it.key to it.value },
                                                response = responseByRequest[request.id]?.json,
                                            )
                                        },
                            )
                        },
                )
            }
        return json.encodeToString(ArchiveFile.serializer(), ArchiveFile(VERSION, exported))
    }

    /** [import] for the UI: the outcome, or why the file couldn't be imported, as one message to show. */
    suspend fun importAndDescribe(
        content: String,
        deviceId: DeviceId,
        sessions: ApiSessionReadRepository,
        strategies: ApiImportStrategyReadRepository,
        engine: ImportEngine,
    ): String =
        @Suppress("TooGenericExceptionCaught")
        try {
            import(content, deviceId, sessions, strategies, engine).summary()
        } catch (expected: Exception) {
            "Failed to import API sessions: ${expected.message}"
        }

    suspend fun import(
        content: String,
        deviceId: DeviceId,
        sessions: ApiSessionReadRepository,
        strategies: ApiImportStrategyReadRepository,
        engine: ImportEngine,
    ): ApiSessionsArchiveImportResult {
        val file = json.decodeFromString(ArchiveFile.serializer(), content)
        require(file.version == VERSION) { "Unsupported API sessions export version: ${file.version}" }
        var imported = 0
        var present = 0
        val missing = mutableSetOf<String>()
        for (entry in file.strategies) {
            val strategy: ApiImportStrategy? = strategies.getStrategyByName(entry.name).first()
            if (strategy == null) {
                missing += entry.name
                continue
            }
            val credentialId = engine.ensureApiCredential(strategy.id, Clock.System.now())
            val existing = sessions.getSessionsByCredential(credentialId).map { it.createdAt }.toSet()
            for (session in entry.sessions) {
                val createdAt = Instant.fromEpochMilliseconds(session.createdAt)
                if (createdAt in existing) {
                    present++
                    continue
                }
                restoreSession(session, createdAt, deviceId, credentialId, engine)
                imported++
            }
        }
        return ApiSessionsArchiveImportResult(imported, present, missing)
    }

    /** Writes one session; a failure partway removes it again, so it is never left half imported. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun restoreSession(
        session: ArchiveSession,
        createdAt: Instant,
        deviceId: DeviceId,
        credentialId: ApiCredentialId,
        engine: ImportEngine,
    ) {
        val sessionId = engine.createApiSession(deviceId, createdAt, credentialId)
        try {
            for (request in session.requests) {
                val requestId = engine.insertApiRequest(sessionId, request.method, request.url, request.headers)
                request.response?.let { engine.insertApiResponse(requestId, sessionId, it) }
            }
            for ((endpoint, until) in session.coverage) {
                engine.recordApiDownloadCoverage(sessionId, endpoint, Instant.fromEpochMilliseconds(until))
            }
        } catch (failure: Throwable) {
            // A half-written session would later read as "already present" and never be completed.
            withContext(NonCancellable) { engine.deleteApiSession(sessionId) }
            throw failure
        }
    }
}
