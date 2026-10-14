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
import com.moneymanager.localsettings.LocalSettings
import com.moneymanager.remotestorage.googledrive.BrowserLauncher
import com.moneymanager.test.database.createTestDatabaseManager

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

/** Test services: a test database manager, in-memory settings and vault, no cloud. */
internal class TestCli(
    val databaseManager: DatabaseManager = createTestDatabaseManager(),
    val localSettings: LocalSettings = InMemoryLocalSettings(),
    val vault: CredentialVault = inMemoryCredentialVault(),
) {
    private val platform =
        CliPlatform(
            services = { CliServices(databaseManager, localSettings, vault, remoteController = null, AppVersion("9.9.9")) },
            systemBrowser = { BrowserLauncher { error("tests never open a browser") } },
            logFile = null,
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
}

internal fun console(
    recorder: TerminalRecorder,
    env: Map<String, String> = emptyMap(),
    passwordFromStdin: Boolean = false,
) = Console(Terminal(terminalInterface = recorder), { env[it] }, passwordFromStdin, quiet = false)
