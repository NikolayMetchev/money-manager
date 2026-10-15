package com.moneymanager.cli

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.mordant.table.table
import com.moneymanager.query.ImportDirectoryJson
import com.moneymanager.query.ImportDirectoryQuery
import com.moneymanager.query.toJson
import kotlinx.serialization.builtins.ListSerializer

internal class ImportDirsCommand : SuspendingCliktCommand(name = "import-dirs") {
    private val runtime by requireObject<CliRuntime>()

    override fun help(context: Context): String = "List the configured import directories and how far their files got"

    override suspend fun run() {
        val directories =
            runtime.withSession { session ->
                val services = session.services
                ImportDirectoryQuery(services.imports.importDirectoryRepository, services.deviceId).directories().map { it.toJson() }
            }
        runtime.output.emit(directories, ListSerializer(ImportDirectoryJson.serializer())) { rows ->
            table {
                header { row("Name", "Provider", "Path", "Files", "Last import", "Status") }
                body {
                    rows.forEach {
                        val status =
                            listOfNotNull("excluded".takeIf { _ -> it.excluded }, "other device".takeIf { _ -> !it.onThisDevice })
                                .joinToString()
                        row(
                            if (it.topLevel) it.name else "  ${it.name}",
                            it.provider,
                            it.path,
                            "${it.importedFiles}/${it.trackedFiles} imported",
                            it.lastImportedAt.orEmpty(),
                            status,
                        )
                    }
                }
            }
        }
    }
}
