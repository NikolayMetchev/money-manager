package com.moneymanager.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.credentialvault.CredentialVault
import com.moneymanager.credentialvault.CredentialVaultLockedException
import com.moneymanager.credentialvault.VaultState
import com.moneymanager.ui.components.SettingsSectionCard
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.foundation.CredentialFilePickerMode
import com.moneymanager.ui.foundation.LocalCredentialVault
import com.moneymanager.ui.foundation.rememberCredentialFileLocationPicker
import com.moneymanager.ui.util.PasswordField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The encrypted credential file: where it is, whether it's unlocked, and the controls to lock it, move it or
 * change its password. Unlocking and creating go through the app-wide prompt, like any other secret lookup.
 */
@Composable
internal fun CredentialsCard() {
    val vault = LocalCredentialVault.current
    val state by vault.state.collectAsState()
    val scope = rememberSchemaAwareCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var changingPassword by remember { mutableStateOf(false) }

    fun runAction(action: suspend () -> Unit) {
        scope.launch {
            message =
                try {
                    action()
                    null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: CredentialVaultLockedException) {
                    null
                } catch (expected: Exception) {
                    expected.message ?: "Something went wrong"
                }
        }
    }

    val movePicker =
        rememberCredentialFileLocationPicker { chosen -> if (chosen != null) runAction { vault.moveTo(chosen) } }

    SettingsSectionCard(title = "Credentials") {
        Text(
            "API tokens and your Google sign-in are kept in an encrypted, password-protected file outside the " +
                "database, so they survive the database being wiped. The password is never stored.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = credentialStatus(state), style = MaterialTheme.typography.bodyMedium)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (val current = state) {
                is VaultState.Unlocked -> {
                    OutlinedButton(onClick = { runAction { vault.lock() } }) { Text("Lock now") }
                    OutlinedButton(onClick = { changingPassword = true }) { Text("Change password…") }
                    if (movePicker.isSupported) {
                        OutlinedButton(onClick = { movePicker.launch(CredentialFilePickerMode.CHOOSE_NEW, current.path) }) {
                            Text("Move…")
                        }
                    }
                }
                is VaultState.Locked ->
                    OutlinedButton(onClick = { runAction { vault.requireUnlocked("Unlock your credentials") } }) { Text("Unlock…") }
                is VaultState.NoFile ->
                    OutlinedButton(onClick = { runAction { vault.requireUnlocked("Create your credential file") } }) { Text("Create…") }
                VaultState.Unbound -> Unit
            }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }

    if (changingPassword) {
        ChangeCredentialPasswordDialog(
            vault = vault,
            onDismiss = { changingPassword = false },
        )
    }
}

private fun credentialStatus(state: VaultState): String =
    when (state) {
        VaultState.Unbound -> "No database is open."
        is VaultState.NoFile -> "Not created yet. It will be created at ${state.path} when you first save a credential."
        is VaultState.Locked -> "Locked: ${state.path}"
        is VaultState.Unlocked -> "Unlocked: ${state.path}"
    }

@Composable
private fun ChangeCredentialPasswordDialog(
    vault: CredentialVault,
    onDismiss: () -> Unit,
) {
    val scope = rememberSchemaAwareCoroutineScope()
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val canSubmit = !busy && password.isNotEmpty() && password == confirmation
    val submit = {
        if (canSubmit) {
            busy = true
            scope.launch {
                try {
                    vault.changePassword(password)
                    onDismiss()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (expected: Exception) {
                    error = expected.message ?: "Couldn't change the password"
                } finally {
                    busy = false
                }
            }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Change credential file password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PasswordField(password, { password = it }, "New password", modifier = Modifier.fillMaxWidth())
                PasswordField(
                    confirmation,
                    { confirmation = it },
                    "Confirm new password",
                    modifier = Modifier.fillMaxWidth(),
                    onSubmit = { submit() },
                )
                if (confirmation.isNotEmpty() && confirmation != password) {
                    Text("The passwords don't match.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = { submit() }, enabled = canSubmit) { Text("Change password") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}
