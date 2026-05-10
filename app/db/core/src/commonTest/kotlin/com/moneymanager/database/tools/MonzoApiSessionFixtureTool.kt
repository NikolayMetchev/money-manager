@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlin.system.exitProcess

private const val DEFAULT_FIXTURE_DIR =
    "app/db/core/src/commonTest/resources/monzo/sample-apis"

private val json =
    Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        printUsageAndExit()
    }

    when (args[0]) {
        "export" -> {
            requireArgs(args, 3)
            exportFixtures(
                dbPath = args[1],
                outputDir = File(args[2]),
            )
        }
        "import" -> {
            requireArgs(args, 3)
            importFixtures(
                dbPath = args[1],
                inputDir = File(args[2]),
            )
        }
        else -> printUsageAndExit()
    }
}

private fun exportFixtures(
    dbPath: String,
    outputDir: File,
) {
    outputDir.mkdirs()
    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
        connection.autoCommit = false
        exportTable(connection, outputDir, "api_session_type", "api_session_type.json", ApiSessionTypeRow.serializer()) { row ->
            ApiSessionTypeRow(row.getLong("id"), row.getString("name"))
        }
        exportTable(connection, outputDir, "api_credential", "api_credential.json", ApiCredentialRow.serializer()) { row ->
            ApiCredentialRow(
                id = row.getLong("id"),
                typeId = row.getLong("type_id"),
                token = row.getString("token"),
                createdAt = row.getLong("created_at"),
                strategyId = row.getString("strategy_id"),
            )
        }
        exportTable(connection, outputDir, "api_session", "api_session.json", ApiSessionRow.serializer()) { row ->
            ApiSessionRow(
                id = row.getLong("id"),
                typeId = row.getLong("type_id"),
                token = row.getString("token"),
                deviceId = row.getLong("device_id"),
                createdAt = row.getLong("created_at"),
                expiresAt = row.getLongOrNull("expires_at"),
                credentialId = row.getLongOrNull("credential_id"),
                kind = row.getStringOrNull("kind"),
                importedAt = row.getLongOrNull("imported_at"),
            )
        }
        exportTable(connection, outputDir, "api_request", "api_request.json", ApiRequestRow.serializer()) { row ->
            ApiRequestRow(
                id = row.getLong("id"),
                sessionId = row.getLong("session_id"),
                requestedAt = row.getLong("requested_at"),
                method = row.getString("method"),
                url = row.getString("url"),
            )
        }
        exportTable(connection, outputDir, "api_request_header", "api_request_header.json", ApiRequestHeaderRow.serializer()) { row ->
            ApiRequestHeaderRow(
                id = row.getLong("id"),
                requestId = row.getLong("request_id"),
                key = row.getString("key"),
                value = row.getString("value"),
            )
        }
        exportTable(connection, outputDir, "api_response", "api_response.json", ApiResponseRow.serializer()) { row ->
            ApiResponseRow(
                id = row.getLong("id"),
                requestId = row.getLong("request_id"),
                sessionId = row.getLong("session_id"),
                respondedAt = row.getLong("responded_at"),
                json = row.getString("json"),
            )
        }
        exportTable(
            connection,
            outputDir,
            "api_response_transaction",
            "api_response_transaction.json",
            ApiResponseTransactionRow.serializer(),
        ) { row ->
            ApiResponseTransactionRow(
                id = row.getLong("id"),
                responseId = row.getLong("response_id"),
                jsonPath = row.getString("json_path"),
                state = row.getLong("state"),
                transactionId = row.getLongOrNull("transaction_id"),
                errorMessage = row.getStringOrNull("error_message"),
                createdAt = row.getLong("created_at"),
            )
        }
    }
}

private fun importFixtures(
    dbPath: String,
    inputDir: File,
) {
    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
        connection.autoCommit = false
        connection.createStatement().use { stmt -> stmt.execute("PRAGMA foreign_keys = OFF") }
        try {
            insertTable(inputDir, "api_session_type.json", ApiSessionTypeRow.serializer()) { row ->
                connection.prepareStatement("INSERT OR IGNORE INTO api_session_type(id, name) VALUES (?, ?)").use { ps ->
                    ps.setLong(1, row.id)
                    ps.setString(2, row.name)
                    ps.executeUpdate()
                }
            }
            insertTable(inputDir, "api_credential.json", ApiCredentialRow.serializer()) { row ->
                connection
                    .prepareStatement(
                        "INSERT OR IGNORE INTO api_credential(id, type_id, token, created_at, strategy_id) VALUES (?, ?, ?, ?, ?)",
                    ).use { ps ->
                        ps.setLong(1, row.id)
                        ps.setLong(2, row.typeId)
                        ps.setString(3, row.token)
                        ps.setLong(4, row.createdAt)
                        if (row.strategyId == null) ps.setNull(5, java.sql.Types.VARCHAR) else ps.setString(5, row.strategyId)
                        ps.executeUpdate()
                    }
            }
            insertTable(inputDir, "api_session.json", ApiSessionRow.serializer()) { row ->
                connection
                    .prepareStatement(
                        "INSERT OR IGNORE INTO api_session(id, type_id, token, device_id, created_at, expires_at, credential_id, kind, imported_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    ).use { ps ->
                        ps.setLong(1, row.id)
                        ps.setLong(2, row.typeId)
                        ps.setString(3, row.token)
                        ps.setLong(4, row.deviceId)
                        ps.setLong(5, row.createdAt)
                        if (row.expiresAt == null) ps.setNull(6, java.sql.Types.INTEGER) else ps.setLong(6, row.expiresAt)
                        if (row.credentialId == null) ps.setNull(7, java.sql.Types.INTEGER) else ps.setLong(7, row.credentialId)
                        if (row.kind == null) ps.setNull(8, java.sql.Types.VARCHAR) else ps.setString(8, row.kind)
                        if (row.importedAt == null) ps.setNull(9, java.sql.Types.INTEGER) else ps.setLong(9, row.importedAt)
                        ps.executeUpdate()
                    }
            }
            insertTable(inputDir, "api_request.json", ApiRequestRow.serializer()) { row ->
                connection
                    .prepareStatement(
                        "INSERT OR IGNORE INTO api_request(id, session_id, requested_at, method, url) VALUES (?, ?, ?, ?, ?)",
                    ).use { ps ->
                        ps.setLong(1, row.id)
                        ps.setLong(2, row.sessionId)
                        ps.setLong(3, row.requestedAt)
                        ps.setString(4, row.method)
                        ps.setString(5, row.url)
                        ps.executeUpdate()
                    }
            }
            insertTable(inputDir, "api_request_header.json", ApiRequestHeaderRow.serializer()) { row ->
                connection
                    .prepareStatement(
                        "INSERT OR IGNORE INTO api_request_header(id, request_id, key, value) VALUES (?, ?, ?, ?)",
                    ).use { ps ->
                        ps.setLong(1, row.id)
                        ps.setLong(2, row.requestId)
                        ps.setString(3, row.key)
                        ps.setString(4, row.value)
                        ps.executeUpdate()
                    }
            }
            insertTable(inputDir, "api_response.json", ApiResponseRow.serializer()) { row ->
                connection
                    .prepareStatement(
                        "INSERT OR IGNORE INTO api_response(id, request_id, session_id, responded_at, json) VALUES (?, ?, ?, ?, ?)",
                    ).use { ps ->
                        ps.setLong(1, row.id)
                        ps.setLong(2, row.requestId)
                        ps.setLong(3, row.sessionId)
                        ps.setLong(4, row.respondedAt)
                        ps.setString(5, row.json)
                        ps.executeUpdate()
                    }
            }
            insertTable(inputDir, "api_response_transaction.json", ApiResponseTransactionRow.serializer()) { row ->
                connection
                    .prepareStatement(
                        "INSERT OR IGNORE INTO api_response_transaction(id, response_id, json_path, state, transaction_id, error_message, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    ).use { ps ->
                        ps.setLong(1, row.id)
                        ps.setLong(2, row.responseId)
                        ps.setString(3, row.jsonPath)
                        ps.setLong(4, row.state)
                        if (row.transactionId == null) ps.setNull(5, java.sql.Types.INTEGER) else ps.setLong(5, row.transactionId)
                        if (row.errorMessage == null) ps.setNull(6, java.sql.Types.VARCHAR) else ps.setString(6, row.errorMessage)
                        ps.setLong(7, row.createdAt)
                        ps.executeUpdate()
                    }
            }
            connection.commit()
        } finally {
            connection.createStatement().use { stmt -> stmt.execute("PRAGMA foreign_keys = ON") }
        }
    }
}

private fun <T> exportTable(
    connection: Connection,
    outputDir: File,
    tableName: String,
    fileName: String,
    serializer: kotlinx.serialization.KSerializer<T>,
    mapper: (java.sql.ResultSet) -> T,
) {
    val rows =
        connection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT * FROM $tableName ORDER BY id").use { rs ->
                buildList {
                    while (rs.next()) {
                        add(mapper(rs))
                    }
                }
            }
        }
    outputDir.resolve(fileName).writeText(json.encodeToString(ListSerializer(serializer), rows))
}

private fun <T> insertTable(
    inputDir: File,
    fileName: String,
    serializer: kotlinx.serialization.KSerializer<T>,
    insert: (T) -> Unit,
) {
    val file = inputDir.resolve(fileName)
    if (!file.exists()) return
    val rows = json.decodeFromString(ListSerializer(serializer), file.readText())
    rows.forEach(insert)
}

private fun requireArgs(
    args: Array<String>,
    minSize: Int,
) {
    if (args.size < minSize) printUsageAndExit()
}

private fun printUsageAndExit(): Nothing {
    println(
        "Usage:\n" +
            "  export <dbPath> <outputDir>\n" +
            "  import <dbPath> <inputDir>\n" +
            "Default fixture dir: $DEFAULT_FIXTURE_DIR",
    )
    exitProcess(1)
}

private fun java.sql.ResultSet.getStringOrNull(column: String): String? = getString(column)

private fun java.sql.ResultSet.getLongOrNull(column: String): Long? =
    getObject(column)?.let {
        when (it) {
            is Number -> it.toLong()
            is String -> it.toLong()
            else -> null
        }
    }

@Serializable
private data class ApiSessionTypeRow(
    val id: Long,
    val name: String,
)

@Serializable
private data class ApiCredentialRow(
    val id: Long,
    val typeId: Long,
    val token: String,
    val createdAt: Long,
    val strategyId: String? = null,
)

@Serializable
private data class ApiSessionRow(
    val id: Long,
    val typeId: Long,
    val token: String,
    val deviceId: Long,
    val createdAt: Long,
    val expiresAt: Long? = null,
    val credentialId: Long? = null,
    val kind: String? = null,
    val importedAt: Long? = null,
)

@Serializable
private data class ApiRequestRow(
    val id: Long,
    val sessionId: Long,
    val requestedAt: Long,
    val method: String,
    val url: String,
)

@Serializable
private data class ApiRequestHeaderRow(
    val id: Long,
    val requestId: Long,
    val key: String,
    val value: String,
)

@Serializable
private data class ApiResponseRow(
    val id: Long,
    val requestId: Long,
    val sessionId: Long,
    val respondedAt: Long,
    val json: String,
)

@Serializable
private data class ApiResponseTransactionRow(
    val id: Long,
    val responseId: Long,
    val jsonPath: String,
    val state: Long,
    val transactionId: Long? = null,
    val errorMessage: String? = null,
    val createdAt: Long,
)
