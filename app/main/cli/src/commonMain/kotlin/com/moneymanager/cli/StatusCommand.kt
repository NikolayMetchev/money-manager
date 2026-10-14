package com.moneymanager.cli

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.requireObject
import com.moneymanager.credentialvault.VaultState
import kotlinx.serialization.Serializable

@Serializable
internal data class StatusJson(
    val version: String,
    val database: String,
    /** Null for a database that isn't cloud-backed. */
    val cloud: CloudStatusJson?,
    val vault: VaultStatusJson,
)

@Serializable
internal data class CloudStatusJson(
    val remoteName: String,
    val provider: String,
    /** The sync state, as the desktop's sync indicator shows it (`no_session` until the cloud password is known). */
    val status: String,
    val localChanges: Boolean,
)

@Serializable
internal data class VaultStatusJson(
    /** `unbound`, `no_file`, `locked` or `unlocked`. */
    val state: String,
    val path: String?,
)

internal class StatusCommand : SuspendingCliktCommand(name = "status") {
    private val runtime by requireObject<CliRuntime>()

    override fun help(context: Context): String = "Show the open database, its cloud sync state and the credential vault"

    override suspend fun run() {
        val services = runtime.services
        val status =
            runtime.withSession { session ->
                val controller = services.remoteController
                val binding = controller?.activeBinding()?.takeIf { it.localCachePath == session.location.path }
                StatusJson(
                    version = services.appVersion.value,
                    database = session.location.path,
                    cloud =
                        binding?.let {
                            CloudStatusJson(
                                remoteName = it.remoteName,
                                provider = it.providerId,
                                status =
                                    controller.syncState.value.status.name
                                        .lowercase(),
                                localChanges = runCatching { controller.hasUnsyncedChanges(session.database) }.getOrDefault(true),
                            )
                        },
                    vault =
                        services.credentialVault.state.value
                            .toJson(),
                )
            }
        runtime.output.emit(status, StatusJson.serializer()) { it.toTable() }
    }
}

private fun VaultState.toJson(): VaultStatusJson =
    when (this) {
        VaultState.Unbound -> VaultStatusJson("unbound", null)
        is VaultState.NoFile -> VaultStatusJson("no_file", path)
        is VaultState.Locked -> VaultStatusJson("locked", path)
        is VaultState.Unlocked -> VaultStatusJson("unlocked", path)
    }

private fun StatusJson.toTable() =
    keyValueTable(
        buildList {
            add("Version" to version)
            add("Database" to database)
            cloud?.let {
                add("Cloud copy" to "${it.remoteName} (${it.provider})")
                add("Sync" to it.status + if (it.localChanges) ", local changes" else "")
            }
            add("Credential vault" to vault.state + (vault.path?.let { " ($it)" }.orEmpty()))
        },
    )
