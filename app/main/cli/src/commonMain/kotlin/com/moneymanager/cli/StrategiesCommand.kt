package com.moneymanager.cli

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.mordant.table.table
import com.moneymanager.query.StrategyJson
import com.moneymanager.query.StrategyKindJson
import com.moneymanager.query.installedStrategies
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer

internal class StrategiesCommand : SuspendingCliktCommand(name = "strategies") {
    private val runtime by requireObject<CliRuntime>()
    private val kind by option("--kind", help = "Only CSV or only API strategies").enum<StrategyKindJson> { it.name.lowercase() }

    override fun help(context: Context): String = "List the installed CSV and API import strategies"

    override suspend fun run() {
        val strategies =
            runtime.withSession { session ->
                val imports = session.services.imports
                installedStrategies(
                    csv = if (kind != StrategyKindJson.API) imports.csvImportStrategyRepository.getAllStrategies().first() else emptyList(),
                    api = if (kind != StrategyKindJson.CSV) imports.apiImportStrategyRepository.getAllStrategies().first() else emptyList(),
                )
            }
        runtime.output.emit(strategies, ListSerializer(StrategyJson.serializer())) { rows ->
            table {
                header { row("Name", "Kind", "Updated") }
                body { rows.forEach { row(it.name, it.kind.name.lowercase(), it.updatedAt) } }
            }
        }
    }
}
