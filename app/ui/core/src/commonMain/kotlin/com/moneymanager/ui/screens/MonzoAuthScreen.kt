@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.repository.ApiSessionRepository
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import kotlinx.coroutines.launch
import kotlin.time.Clock

private const val MONZO_DEVELOPER_PORTAL_URL = "https://developers.monzo.com/"

@Composable
fun MonzoAuthScreen(
    apiSessionRepository: ApiSessionRepository,
    onCredentialSaved: () -> Unit = {},
) {
    val scope = rememberSchemaAwareCoroutineScope()
    val uriHandler = LocalUriHandler.current

    var tokenInput by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Monzo Connection",
            style = MaterialTheme.typography.headlineMedium,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "How to connect your Monzo account",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(text = "1. Open the Monzo Developer Playground in your browser.", style = MaterialTheme.typography.bodyMedium)
                Text(text = "2. Log in with your Monzo account credentials.", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "3. Monzo will send a magic link to your email or app. Approve the login.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(text = "4. Copy the access token shown on the playground page.", style = MaterialTheme.typography.bodyMedium)
                Text(text = "5. Paste the token below and tap \"Save Token\".", style = MaterialTheme.typography.bodyMedium)
                Button(
                    onClick = { uriHandler.openUri(MONZO_DEVELOPER_PORTAL_URL) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Open Monzo Developer Playground")
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(text = "Enter Access Token", style = MaterialTheme.typography.titleMedium)

                OutlinedTextField(
                    value = tokenInput,
                    onValueChange = {
                        tokenInput = it
                        errorMessage = null
                    },
                    label = { Text("Access Token") },
                    placeholder = { Text("Paste your Monzo access token here") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    maxLines = 4,
                    isError = errorMessage != null,
                )

                errorMessage?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Button(
                    onClick = {
                        val trimmedToken = tokenInput.trim()
                        if (trimmedToken.isBlank()) {
                            errorMessage = "Token cannot be empty."
                            return@Button
                        }
                        isSaving = true
                        errorMessage = null
                        scope.launch {
                            try {
                                apiSessionRepository.createCredential(
                                    token = trimmedToken,
                                    createdAt = Clock.System.now(),
                                )
                                onCredentialSaved()
                            } catch (expected: Exception) {
                                errorMessage = "Failed to save token: ${expected.message}"
                            } finally {
                                isSaving = false
                            }
                        }
                    },
                    enabled = !isSaving && tokenInput.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Save Token")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
