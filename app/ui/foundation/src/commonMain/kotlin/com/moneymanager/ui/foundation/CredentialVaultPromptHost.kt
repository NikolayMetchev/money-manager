package com.moneymanager.ui.foundation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.moneymanager.archive.ArchiveDecryptionException
import com.moneymanager.credentialvault.CredentialVault
import com.moneymanager.credentialvault.VaultState
import com.moneymanager.ui.util.PasswordField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Answers the vault's [CredentialVault.unlockRequest]s: asks for the password of an existing credential
 * file, or, when none exists yet, where to create one and with what password. Mount it once at the app
 * root so every screen's secret lookup can prompt.
 */
@Composable
fun CredentialVaultPromptHost(vault: CredentialVault) {
    val request by vault.unlockRequest.collectAsState()
    val state by vault.state.collectAsState()
    val reason = request?.reason ?: return
    val initialMode =
        when (val current = state) {
            is VaultState.Locked -> PromptMode.Unlock(current.path)
            is VaultState.NoFile -> PromptMode.Create(current.path)
            else -> return
        }
    CredentialFileDialog(vault = vault, reason = reason, initialMode = initialMode)
}

private sealed interface PromptMode {
    val path: String

    data class Unlock(
        override val path: String,
    ) : PromptMode

    data class Create(
        override val path: String,
    ) : PromptMode
}

@Composable
private fun CredentialFileDialog(
    vault: CredentialVault,
    reason: String,
    initialMode: PromptMode,
) {
    val scope = rememberCoroutineScope()
    var mode by remember(initialMode) { mutableStateOf(initialMode) }
    var location by remember(initialMode) { mutableStateOf(initialMode.path) }
    var password by remember(mode) { mutableStateOf("") }
    var confirmation by remember(mode) { mutableStateOf("") }
    var error by remember(mode) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(mode) { runCatching { focus.requestFocus() } }

    val openExisting =
        rememberCredentialFileLocationPicker { chosen -> if (chosen != null) mode = PromptMode.Unlock(chosen) }
    val chooseNew =
        rememberCredentialFileLocationPicker { chosen ->
            if (chosen != null) {
                location = chosen
                mode = PromptMode.Create(chosen)
            }
        }

    val isCreate = mode is PromptMode.Create
    val canSubmit =
        !busy && password.isNotEmpty() && (!isCreate || (password == confirmation && location.isNotBlank()))
    val submit: () -> Unit = {
        if (canSubmit) {
            busy = true
            error = null
            scope.launch {
                try {
                    when (val current = mode) {
                        is PromptMode.Unlock -> vault.unlock(password, current.path)
                        is PromptMode.Create -> vault.create(location.trim(), password)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: ArchiveDecryptionException) {
                    error = "Wrong password, or that isn't a Money Manager credential file."
                } catch (expected: Exception) {
                    error = expected.message ?: "Couldn't open the credential file."
                } finally {
                    busy = false
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) vault.cancelUnlockRequest() },
        title = { Text(if (isCreate) "Create a credential file" else "Unlock your credentials") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(reason, style = MaterialTheme.typography.bodyMedium)
                if (isCreate) {
                    Text(
                        "Your API tokens and Google sign-in are kept in an encrypted file outside the database, so " +
                            "they survive the database being wiped or recreated. The password is never stored: if " +
                            "you forget it, the credentials in the file can't be recovered.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = location,
                        onValueChange = {
                            location = it
                            error = null
                        },
                        label = { Text("Location") },
                        singleLine = true,
                        enabled = chooseNew.isSupported,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(
                        "Credential file: ${mode.path}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PasswordField(
                    value = password,
                    onValueChange = {
                        password = it
                        error = null
                    },
                    label = "Password",
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    onSubmit = if (isCreate) null else submit,
                )
                if (isCreate) {
                    PasswordField(
                        value = confirmation,
                        onValueChange = {
                            confirmation = it
                            error = null
                        },
                        label = "Confirm password",
                        modifier = Modifier.fillMaxWidth(),
                        onSubmit = submit,
                    )
                    if (confirmation.isNotEmpty() && confirmation != password) {
                        Text(
                            "The passwords don't match.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (openExisting.isSupported) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (isCreate) {
                            TextButton(onClick = { chooseNew.launch(CredentialFilePickerMode.CHOOSE_NEW, location) }, enabled = !busy) {
                                Text("Browse…")
                            }
                        } else {
                            TextButton(onClick = { chooseNew.launch(CredentialFilePickerMode.CHOOSE_NEW, mode.path) }, enabled = !busy) {
                                Text("Create a new file…")
                            }
                        }
                        TextButton(onClick = { openExisting.launch(CredentialFilePickerMode.OPEN_EXISTING, mode.path) }, enabled = !busy) {
                            Text("Open another file…")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = submit, enabled = canSubmit) { Text(if (isCreate) "Create" else "Unlock") }
        },
        dismissButton = {
            TextButton(onClick = { vault.cancelUnlockRequest() }, enabled = !busy) { Text("Cancel") }
        },
    )
}
