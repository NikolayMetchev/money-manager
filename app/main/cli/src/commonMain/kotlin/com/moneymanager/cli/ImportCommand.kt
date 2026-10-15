package com.moneymanager.cli

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option

/** `mm import`: download and import from import directories and API connections. */
internal class ImportCommand : SuspendingCliktCommand(name = "import") {
    init {
        subcommands(ImportDirectoriesCommand(), ImportApisCommand(), ImportEverythingCommand())
    }

    override fun help(context: Context): String =
        """
        Download and import from import directories and API connections.

        Prints what was imported, per folder, file, connection and session. Anything only a person can
        resolve (no matching strategy, a source account to pick, counterparties to confirm, an unassigned
        card) is listed under needsAttention and exits with 3, as does any failure; mm never guesses.
        """.trimIndent()

    override suspend fun run() = Unit
}

internal class ImportDirectoriesCommand : SuspendingCliktCommand(name = "dirs") {
    private val runtime by requireObject<CliRuntime>()
    private val dirs by option("--dir", metavar = "NAME", help = "Only this folder and its subfolders (repeatable)").multiple()
    private val downloadOnly by option("--download-only", help = "Download new files but don't import them").flag()
    private val redownload by option(
        "--redownload",
        help = "Re-download the files already staged first (never unimports: a file that would need it is listed)",
    ).flag()

    override fun help(context: Context): String =
        "Download every included import directory (local and Google Drive) and import every new CSV/QIF file"

    override suspend fun run() {
        ImportRun(runtime).run(directories = DirectoriesRequest(dirs, apply = !downloadOnly, redownload = redownload), apis = null)
    }
}

internal class ImportApisCommand : SuspendingCliktCommand(name = "apis") {
    private val runtime by requireObject<CliRuntime>()
    private val strategies by option("--strategy", metavar = "NAME", help = "Only this API strategy's connections (repeatable)")
        .multiple()
    private val forceFull by option("--force-full", help = "Download everything again, not just what's new").flag()
    private val downloadOnly by option("--download-only", help = "Download new sessions but don't import them").flag()

    override fun help(context: Context): String = "Download every connected API in parallel and import the new sessions"

    override suspend fun run() {
        ImportRun(runtime).run(directories = null, apis = ApisRequest(strategies, forceFull, apply = !downloadOnly))
    }
}

internal class ImportEverythingCommand : SuspendingCliktCommand(name = "all") {
    private val runtime by requireObject<CliRuntime>()
    private val forceFull by option("--force-full", help = "APIs: download everything again, not just what's new").flag()
    private val downloadOnly by option("--download-only", help = "Download but don't import").flag()

    override fun help(context: Context): String = "Import directories and APIs at once (see `import dirs` and `import apis`)"

    override suspend fun run() {
        ImportRun(runtime).run(
            directories = DirectoriesRequest(emptyList(), apply = !downloadOnly, redownload = false),
            apis = ApisRequest(emptyList(), forceFull, apply = !downloadOnly, optional = true),
        )
    }
}
