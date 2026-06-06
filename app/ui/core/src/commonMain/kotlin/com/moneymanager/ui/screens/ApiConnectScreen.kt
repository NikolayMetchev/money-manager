@file:OptIn(kotlin.time.ExperimentalTime::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.model.apistrategy.ApiImportStrategyId
import com.moneymanager.domain.repository.ApiImportStrategyRepository
import com.moneymanager.domain.repository.ApiSessionRepository
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * Generic "connect an API account" screen. Lists the available import strategies (Monzo, Wise, and
 * any user-defined ones), lets the user pick one and paste a bearer token, then stores a credential
 * linked to that strategy. No provider-specific behaviour lives here beyond an optional convenience
 * link to a known provider's token page.
 */
@Composable
fun ApiConnectScreen(
    apiSessionRepository: ApiSessionRepository,
    apiImportStrategyRepository: ApiImportStrategyRepository,
    onCredentialSaved: () -> Unit = {},
) {
    val scope = rememberSchemaAwareCoroutineScope()
    val uriHandler = LocalUriHandler.current

    val strategies by apiImportStrategyRepository
        .getAllStrategies()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())

    var selectedStrategyId by remember { mutableStateOf<ApiImportStrategyId?>(null) }
    LaunchedEffect(strategies) {
        if (selectedStrategyId == null || strategies.none { it.id == selectedStrategyId }) {
            selectedStrategyId = strategies.firstOrNull()?.id
        }
    }
    val selectedStrategy = strategies.find { it.id == selectedStrategyId }

    var strategyMenuExpanded by remember { mutableStateOf(false) }
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
            text = "Connect API Account",
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
                Text(text = "Choose a provider", style = MaterialTheme.typography.titleMedium)

                ExposedDropdownMenuBox(
                    expanded = strategyMenuExpanded,
                    onExpandedChange = { strategyMenuExpanded = it },
                ) {
                    OutlinedTextField(
                        value = selectedStrategy?.name.orEmpty(),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Provider") },
                        placeholder = { Text("Select a provider") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = strategyMenuExpanded) },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                        singleLine = true,
                    )
                    ExposedDropdownMenu(
                        expanded = strategyMenuExpanded,
                        onDismissRequest = { strategyMenuExpanded = false },
                    ) {
                        strategies.forEach { strategy ->
                            DropdownMenuItem(
                                text = { Text(strategy.name) },
                                onClick = {
                                    selectedStrategyId = strategy.id
                                    strategyMenuExpanded = false
                                },
                            )
                        }
                    }
                }

                providerTokenPageUrl(selectedStrategy)?.let { url ->
                    Button(
                        onClick = { uriHandler.openUri(url) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Open ${selectedStrategy?.name} token page")
                    }
                }
            }
        }

        val instructions = connectInstructions(selectedStrategy)
        if (instructions.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "How to connect your ${selectedStrategy?.name} account",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    instructions.forEachIndexed { index, step ->
                        Text(text = "${index + 1}. $step", style = MaterialTheme.typography.bodyMedium)
                    }
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
                    placeholder = { Text("Paste your ${selectedStrategy?.name ?: "API"} access token here") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
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
                        val strategyId = selectedStrategyId
                        if (trimmedToken.isBlank()) {
                            errorMessage = "Token cannot be empty."
                            return@Button
                        }
                        if (strategyId == null) {
                            errorMessage = "Select a provider first."
                            return@Button
                        }
                        isSaving = true
                        errorMessage = null
                        scope.launch {
                            try {
                                apiSessionRepository.createCredential(
                                    token = trimmedToken,
                                    createdAt = Clock.System.now(),
                                    strategyId = strategyId,
                                )
                                onCredentialSaved()
                            } catch (expected: Exception) {
                                errorMessage = "Failed to save token: ${expected.message}"
                            } finally {
                                isSaving = false
                            }
                        }
                    },
                    enabled = !isSaving && tokenInput.isNotBlank() && selectedStrategyId != null,
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

/** Convenience deep-link to a known provider's API-token page, derived from the strategy base URL. */
private fun providerTokenPageUrl(strategy: ApiImportStrategy?): String? =
    when (providerKind(strategy)) {
        ProviderKind.MONZO -> "https://developers.monzo.com/"
        ProviderKind.WISE -> "https://wise.com/your-account/integrations-and-tools/api-tokens"
        null -> null
    }

/** Step-by-step instructions for obtaining an access token from a known provider. */
private fun connectInstructions(strategy: ApiImportStrategy?): List<String> =
    when (providerKind(strategy)) {
        ProviderKind.MONZO ->
            listOf(
                "Open the Monzo Developer Playground in your browser.",
                "Log in with your Monzo account credentials.",
                "Monzo will send a magic link to your email or app. Approve the login.",
                "Copy the access token shown on the playground page.",
                "Paste the token below and tap \"Save Token\".",
                "In the Monzo app, approve the API access notification so transactions can be read.",
            )
        ProviderKind.WISE ->
            listOf(
                "Open the Wise API tokens page in your browser and sign in.",
                "Create a new API token (read access is sufficient) and copy it.",
                "Paste the token below and tap \"Save Token\".",
                "Statements are protected by Strong Customer Authentication: generate a signing key on the " +
                    "credential and register its public key in Wise (Settings → API tokens → Manage public keys).",
                "Note: retrieving statements via the API is only supported for accounts based in the US, Canada, " +
                    "Australia, New Zealand, Singapore, and Malaysia.",
            )
        null -> emptyList()
    }

private enum class ProviderKind { MONZO, WISE }

private fun providerKind(strategy: ApiImportStrategy?): ProviderKind? {
    val baseUrl = strategy?.baseUrl?.lowercase() ?: return null
    return when {
        "monzo" in baseUrl -> ProviderKind.MONZO
        "wise" in baseUrl || "transferwise" in baseUrl -> ProviderKind.WISE
        else -> null
    }
}
