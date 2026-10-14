package com.moneymanager.cli

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.requireObject
import kotlinx.serialization.Serializable

@Serializable
internal data class VersionJson(
    val version: String,
)

internal class VersionCommand : SuspendingCliktCommand(name = "version") {
    private val runtime by requireObject<CliRuntime>()

    override fun help(context: Context): String = "Print the Money Manager version"

    override suspend fun run() {
        val version = VersionJson(runtime.services.appVersion.value)
        runtime.output.emit(version, VersionJson.serializer()) { keyValueTable(listOf("Version" to it.version)) }
    }
}
