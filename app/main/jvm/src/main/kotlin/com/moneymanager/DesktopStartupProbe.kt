package com.moneymanager

import co.touchlab.kermit.Logger
import com.moneymanager.domain.model.Account
import com.moneymanager.files.writeText
import com.moneymanager.ui.foundation.StartupMilestone
import com.moneymanager.ui.foundation.StartupProbe
import kotlinx.io.files.Path

private val logger = Logger.withTag("DesktopStartupProbe")

/**
 * The desktop app's [StartupProbe] (#1254), on when `MM_STARTUP_PROBE` names a file. It times each milestone
 * from the process's start. Once the accounts load it opens `MM_STARTUP_PROBE_ACCOUNT`'s transactions, when
 * that's set. After the last milestone it writes the times to the file as JSON and closes the app through
 * [onDone]. The startup benchmark (`bazel/perf`) and the AOT cache's training run drive the app this way.
 */
internal class DesktopStartupProbe(
    private val output: Path,
    private val accountName: String?,
    private val onDone: () -> Unit,
) : StartupProbe {
    private val processStartMillis =
        ProcessHandle
            .current()
            .info()
            .startInstant()
            .map { it.toEpochMilli() }
            .orElse(System.currentTimeMillis())
    private val reachedAt = linkedMapOf<StartupMilestone, Long>()
    private val last = if (accountName == null) StartupMilestone.ACCOUNTS_READY else StartupMilestone.TRANSACTIONS_READY

    @Synchronized
    override fun reached(milestone: StartupMilestone) {
        if (milestone in reachedAt) return
        val elapsed = System.currentTimeMillis() - processStartMillis
        reachedAt[milestone] = elapsed
        logger.i { "Startup milestone $milestone after ${elapsed}ms" }
        if (milestone == last) {
            writeText(output, toJson())
            onDone()
        }
    }

    override fun accountToOpen(accounts: List<Account>): Account? {
        val name = accountName ?: return null
        return accounts.firstOrNull { it.name == name }
            ?: error("Startup probe: no account named \"$name\" among ${accounts.size}")
    }

    private fun toJson(): String =
        reachedAt.entries.joinToString(",", "{", "}\n") { (milestone, millis) ->
            "\"${milestone.name.lowercase().replace('_', '-')}\":$millis"
        }

    companion object {
        /** The probe the environment asks for, or null for a normal start. */
        fun fromEnvironment(onDone: () -> Unit): DesktopStartupProbe? {
            val output = System.getenv("MM_STARTUP_PROBE")?.takeIf { it.isNotBlank() } ?: return null
            return DesktopStartupProbe(Path(output), System.getenv("MM_STARTUP_PROBE_ACCOUNT")?.takeIf { it.isNotBlank() }, onDone)
        }
    }
}
