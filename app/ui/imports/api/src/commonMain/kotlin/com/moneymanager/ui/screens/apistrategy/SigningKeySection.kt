package com.moneymanager.ui.screens.apistrategy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * The signing-key (SCA) controls for a credential, shared by the connections screen — where a Wise
 * connection is finished — and the sessions screen, so both offer the same thing.
 */
@Composable
internal fun SigningKeySection(
    publicKey: String?,
    onGenerateSigningKey: () -> Unit,
    onCopyText: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = "Request signing (SCA)", style = MaterialTheme.typography.labelLarge)
        if (publicKey == null) {
            Text(
                text =
                    "This provider protects statements with Strong Customer Authentication. Generate a " +
                        "signing key, then register its public key in your provider account.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onGenerateSigningKey) { Text("Generate signing key") }
        } else {
            Text(
                text =
                    "Public key generated. Register it in your provider account " +
                        "(e.g. Wise → Settings → API tokens → Manage public keys).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = publicKey,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            var confirmRegenerate by remember { mutableStateOf(false) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onCopyText(publicKey) }) { Text("Copy public key") }
                TextButton(onClick = { confirmRegenerate = true }) { Text("Regenerate") }
            }
            // Regenerating replaces the key pair irrecoverably: if the old public key is registered with the
            // provider, signed requests fail until the new one is registered. Never a one-tap action.
            if (confirmRegenerate) {
                AlertDialog(
                    onDismissRequest = { confirmRegenerate = false },
                    title = { Text("Regenerate signing key?") },
                    text = {
                        Text(
                            "The key registered with your provider will stop working until you register the " +
                                "new public key. The current key cannot be recovered.",
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                confirmRegenerate = false
                                onGenerateSigningKey()
                            },
                        ) { Text("Regenerate") }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmRegenerate = false }) { Text("Cancel") }
                    },
                )
            }
        }
    }
}
