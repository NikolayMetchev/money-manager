package com.moneymanager.tui

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.layout.KeyEvent
import com.jakewharton.mosaic.layout.onKeyEvent
import com.jakewharton.mosaic.layout.padding
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import com.moneymanager.credentialvault.VaultState
import com.moneymanager.presenter.status.CloudStatus
import com.moneymanager.presenter.status.StatusEvent
import com.moneymanager.presenter.status.StatusModel
import com.moneymanager.presenter.status.presentStatus
import com.moneymanager.remotestorage.sync.SyncStatus
import com.moneymanager.terminal.fit
import kotlinx.coroutines.launch

private const val LABEL_WIDTH = 11

/**
 * The open database, its cloud copy and the credential vault, with what can be done about them: upload or download
 * the cloud copy, lock or unlock the vault, and (the shell's `o`) switch to another database.
 */
val StatusScreen =
    TuiScreen(
        title = "Status",
        bindings =
            listOf(
                KeyBinding("c", "Connect to the cloud copy (asks its password)"),
                KeyBinding("s", "Sync now: upload the local copy"),
                KeyBinding("r", "Check the cloud copy for other devices' changes"),
                KeyBinding("D", "Download the cloud copy over the local one"),
                KeyBinding("l", "Lock or unlock the credential vault"),
            ),
    ) { scope ->
        val work = scope.work
        val controller = scope.controller
        val model = presentStatus(scope.session, controller.remote, scope.vault, controller.appVersion, work.scope)

        fun key(event: KeyEvent): Boolean {
            val cloud = model.cloud
            when (event.key) {
                "l" -> {
                    when (model.vault) {
                        is VaultState.Unlocked -> model.eventSink(StatusEvent.LockVault)
                        is VaultState.Locked, is VaultState.NoFile -> model.eventSink(StatusEvent.UnlockVault)
                        VaultState.Unbound -> return false
                    }
                }

                "c" -> {
                    if (cloud == null || cloud.connected) return false
                    work.scope.launch {
                        scope.prompter
                            .secret("Password for the cloud database ${cloud.remoteName}")
                            ?.let { model.eventSink(StatusEvent.Connect(it)) }
                    }
                }

                "s" -> {
                    if (cloud == null || !cloud.connected) return false
                    work.scope.launch {
                        val overwrite = cloud.sync.status == SyncStatus.CONFLICT || cloud.sync.status == SyncStatus.REMOTE_AHEAD
                        if (!overwrite) {
                            model.eventSink(StatusEvent.Upload())
                        } else if (scope.prompter.confirm("Overwrite the cloud copy, losing another device's changes", default = false)) {
                            model.eventSink(StatusEvent.Upload(force = true))
                        }
                    }
                }

                "r" -> {
                    if (cloud != null && cloud.connected) model.eventSink(StatusEvent.CheckRemote) else return false
                }

                "D" -> {
                    if (cloud == null || !cloud.connected) return false
                    work.scope.launch {
                        if (!cloud.localChanges || scope.prompter.confirm("Download, discarding this copy's changes", default = false)) {
                            controller.reloadFromRemote()
                        }
                    }
                }

                else -> {
                    return false
                }
            }
            return true
        }

        Column(modifier = Modifier.padding(horizontal = 1).onKeyEvent(::key)) {
            StatusView(model, (scope.width - 2).coerceAtLeast(0), scope.height)
        }
    }

private class Line(
    val text: String,
    val color: Color = Color.Unspecified,
    val style: TextStyle = TextStyle.Unspecified,
)

@Composable
private fun StatusView(
    model: StatusModel,
    width: Int,
    height: Int,
) {
    fun field(
        label: String,
        value: String,
        color: Color = Color.Unspecified,
    ) = Line(label.padEnd(LABEL_WIDTH) + value, color)

    val cloud = model.cloud
    val busy = model.busy
    val message = model.message
    val lines =
        listOf(
            Line("Status", style = TextStyle.Bold),
            field("Version", model.version.value),
            field("Database", model.location.toString()),
            field(
                "Cloud",
                cloud?.describe() ?: "not cloud-backed",
                if (cloud?.sync?.editingLocked ==
                    true
                ) {
                    Color.Red
                } else {
                    Color.Unspecified
                },
            ),
            field("Vault", model.vault.describe()),
            when {
                busy != null -> Line("$busy…", Color.Yellow)
                message != null -> Line(message.text, if (message.error) Color.Red else Color.Green)
                else -> Line("")
            },
            Line(model.actions(), style = TextStyle.Dim),
        )
    // A short terminal loses the last lines rather than pushing the footer off the screen.
    for (line in lines.take(height)) Text(fit(line.text, width), color = line.color, textStyle = line.style)
}

private fun CloudStatus.describe(): String {
    val state =
        when {
            !connected -> {
                if (localChanges) "local changes · not connected" else "not connected"
            }

            else -> {
                when (sync.status) {
                    SyncStatus.NO_SESSION -> "not connected"
                    SyncStatus.IN_SYNC -> "in sync"
                    SyncStatus.LOCAL_AHEAD -> "local changes to upload"
                    SyncStatus.REMOTE_AHEAD -> "another device uploaded changes · editing locked"
                    SyncStatus.CONFLICT -> "changed here and on another device · editing locked"
                }
            }
        }
    return "$remoteName ($providerId) · $state"
}

private fun VaultState.describe(): String =
    when (this) {
        VaultState.Unbound -> "none"
        is VaultState.NoFile -> "no vault file yet · $path"
        is VaultState.Locked -> "locked · $path"
        is VaultState.Unlocked -> "unlocked · $path"
    }

// Only the keys that do something now.
private fun StatusModel.actions(): String {
    val cloud = cloud
    val keys =
        buildList {
            when {
                cloud == null -> {}

                !cloud.connected -> {
                    add("c connect")
                }

                else -> {
                    add("s sync now")
                    add("r check")
                    add("D download")
                }
            }
            when (vault) {
                is VaultState.Unlocked -> {
                    add("l lock vault")
                }

                is VaultState.Locked -> {
                    add("l unlock vault")
                }

                is VaultState.NoFile -> {
                    add("l create vault")
                }

                VaultState.Unbound -> {}
            }
            add("o open another database")
        }
    return keys.joinToString(" · ")
}
