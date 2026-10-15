package com.moneymanager.cli

import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import com.moneymanager.credentialvault.CredentialVault
import com.moneymanager.credentialvault.testing.InMemoryLocalSettings
import com.moneymanager.credentialvault.testing.inMemoryCredentialVault
import com.moneymanager.database.DatabaseManager
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.importfilesource.ImportFileSourceFactory
import com.moneymanager.importfilesource.di.createImportFileSourceFactory
import com.moneymanager.localsettings.LocalSettings
import com.moneymanager.remotestorage.googledrive.BrowserLauncher
import com.moneymanager.test.database.createTestDatabaseManager
import io.ktor.client.engine.HttpClientEngine

internal class MmRun(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

internal fun recorder(
    interactive: Boolean = false,
    input: List<String> = emptyList(),
) = TerminalRecorder(ansiLevel = AnsiLevel.NONE, width = 120, outputInteractive = interactive, inputInteractive = interactive).apply {
    inputLines = input.toMutableList()
}

/**
 * Test services: a test database manager, in-memory settings and vault, no cloud. Import directories read
 * real local folders; [httpEngine] answers API downloads.
 */
internal class TestCli(
    val databaseManager: DatabaseManager = createTestDatabaseManager(),
    val localSettings: LocalSettings = InMemoryLocalSettings(),
    val vault: CredentialVault = inMemoryCredentialVault(),
    private val httpEngine: HttpClientEngine? = null,
) {
    private val platform =
        CliPlatform(
            services = {
                CliServices(
                    databaseManager = databaseManager,
                    localSettings = localSettings,
                    credentialVault = vault,
                    remoteController = null,
                    appVersion = AppVersion("9.9.9"),
                    importFileSources = fileSources(vault),
                    httpEngine = httpEngine,
                )
            },
            systemBrowser = { NO_BROWSER },
            logFile = null,
            stderrIsTerminal = { false },
        )

    suspend fun run(
        vararg argv: String,
        env: Map<String, String> = emptyMap(),
        interactive: Boolean = false,
        input: List<String> = emptyList(),
    ): MmRun {
        val recorder = recorder(interactive, input)
        val command = MmCommand(platform)
        command.configureContext {
            terminal = Terminal(terminalInterface = recorder)
            readEnvvar = { env[it] }
        }
        val code = command.execute(argv.asList())
        return MmRun(code, recorder.stdout(), recorder.stderr())
    }

    companion object {
        private val NO_BROWSER = BrowserLauncher { error("tests never open a browser") }

        /** The real file sources `mm` reads import directories with (local folders; Drive would need a browser). */
        fun fileSources(vault: CredentialVault): ImportFileSourceFactory =
            createImportFileSourceFactory(cliComponentParams(NO_BROWSER), vault)
    }
}

internal fun console(
    recorder: TerminalRecorder,
    env: Map<String, String> = emptyMap(),
    passwordFromStdin: Boolean = false,
) = Console(Terminal(terminalInterface = recorder), { env[it] }, passwordFromStdin, quiet = false)
