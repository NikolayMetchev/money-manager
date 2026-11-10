package com.moneymanager.apiimporter

import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.repository.ApiImportStrategyReadRepository
import com.moneymanager.domain.repository.ApiSessionReadRepository
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.createApiSession
import com.moneymanager.importengineapi.ensureApiCredential
import com.moneymanager.importengineapi.insertApiRequest
import com.moneymanager.importengineapi.insertApiResponse
import com.moneymanager.importengineapi.recordApiDownloadCoverage
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
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

/**
 * Exports every API session's recorded traffic (requests, response bodies, download coverage) to one JSON
 * document and re-imports it, so a development database can be thrown away without re-downloading
 * everything. Only the downloaded data travels: import state (which transactions were created) is not
 * exported, because importing the restored sessions again regenerates it. Request headers are already
 * redacted when recorded, and credentials stay in the vault, so the file holds no secrets.
 */
object ApiSessionsArchive {
    private const val VERSION = 1L
    private val json = Json { prettyPrint = true }

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
                strategy.name to
                    sessionRows.map { session ->
                        val responseByRequest = sessions.getResponsesBySession(session.id).associateBy { it.requestId }
                        val requests =
                            sessions.getRequestsBySession(session.id).sortedWith(compareBy({ it.requestedAt }, { it.id.id }))
                        JsonObject(
                            mapOf(
                                "createdAt" to JsonPrimitive(session.createdAt.toEpochMilliseconds()),
                                "coverage" to
                                    JsonObject(
                                        sessions
                                            .getDownloadCoverageBySession(session.id)
                                            .mapValues { JsonPrimitive(it.value.toEpochMilliseconds()) },
                                    ),
                                "requests" to
                                    JsonArray(
                                        requests.map { request ->
                                            JsonObject(
                                                mapOf(
                                                    "method" to JsonPrimitive(request.method),
                                                    "url" to JsonPrimitive(request.url),
                                                    "headers" to
                                                        JsonObject(
                                                            request.headers.associate { it.key to JsonPrimitive(it.value) },
                                                        ),
                                                    "response" to
                                                        (responseByRequest[request.id]?.let { JsonPrimitive(it.json) } ?: JsonNull),
                                                ),
                                            )
                                        },
                                    ),
                            ),
                        )
                    }
            }
        return json.encodeToString(
            JsonElement.serializer(),
            JsonObject(
                mapOf(
                    "version" to JsonPrimitive(VERSION),
                    "strategies" to
                        JsonArray(
                            exported.map { (name, sessionList) ->
                                JsonObject(mapOf("name" to JsonPrimitive(name), "sessions" to JsonArray(sessionList)))
                            },
                        ),
                ),
            ),
        )
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
        val root = json.parseToJsonElement(content).jsonObject
        val version = root["version"]?.jsonPrimitive?.long
        require(version == VERSION) { "Unsupported API sessions export version: $version" }
        var imported = 0
        var present = 0
        val missing = mutableSetOf<String>()
        for (entry in root.getValue("strategies").jsonArray.map { it.jsonObject }) {
            val name = entry.getValue("name").jsonPrimitive.content
            val strategy: ApiImportStrategy? = strategies.getStrategyByName(name).first()
            if (strategy == null) {
                missing += name
                continue
            }
            val credentialId = engine.ensureApiCredential(strategy.id, Clock.System.now())
            val existing = sessions.getSessionsByCredential(credentialId).map { it.createdAt }.toSet()
            for (session in entry.getValue("sessions").jsonArray.map { it.jsonObject }) {
                val createdAt = Instant.fromEpochMilliseconds(session.getValue("createdAt").jsonPrimitive.long)
                if (createdAt in existing) {
                    present++
                    continue
                }
                val sessionId = engine.createApiSession(deviceId, createdAt, credentialId)
                for (request in session.getValue("requests").jsonArray.map { it.jsonObject }) {
                    val requestId =
                        engine.insertApiRequest(
                            sessionId,
                            request.getValue("method").jsonPrimitive.content,
                            request.getValue("url").jsonPrimitive.content,
                            request.getValue("headers").jsonObject.mapValues { it.value.jsonPrimitive.content },
                        )
                    request["response"]?.jsonPrimitive?.contentOrNull?.let { engine.insertApiResponse(requestId, sessionId, it) }
                }
                for ((endpoint, until) in session.getValue("coverage").jsonObject) {
                    engine.recordApiDownloadCoverage(sessionId, endpoint, Instant.fromEpochMilliseconds(until.jsonPrimitive.long))
                }
                imported++
            }
        }
        return ApiSessionsArchiveImportResult(imported, present, missing)
    }
}
