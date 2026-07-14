package com.moneymanager.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onCopyText(publicKey) }) { Text("Copy public key") }
                TextButton(onClick = onGenerateSigningKey) { Text("Regenerate") }
            }
        }
    }
}
