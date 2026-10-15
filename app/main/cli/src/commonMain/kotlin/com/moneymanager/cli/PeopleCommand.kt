package com.moneymanager.cli

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.mordant.table.table
import com.moneymanager.query.PersonJson
import com.moneymanager.query.toJson
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer

internal class PeopleCommand : SuspendingCliktCommand(name = "people") {
    private val runtime by requireObject<CliRuntime>()

    override fun help(context: Context): String = "List people (the names --owner takes)"

    override suspend fun run() {
        val people =
            runtime.withSession { session ->
                session.services.people.personRepository
                    .getAllPeople()
                    .first()
                    .map { it.toJson() }
            }
        runtime.output.emit(people, ListSerializer(PersonJson.serializer())) { rows ->
            table {
                header { row("Id", "Name") }
                body { rows.forEach { row(it.id, it.fullName) } }
            }
        }
    }
}
