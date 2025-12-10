@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.repository.CurrencyRepository
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling
import kotlinx.coroutines.launch
import org.lighthousegames.logging.logging

private val logger = logging()

@Composable
fun CurrenciesScreen(currencyRepository: CurrencyRepository) {
    val currencies by currencyRepository.getAllCurrencies()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())
    var showCreateDialog by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(16.dp),
        ) {
            Text(
                text = "Your Currencies",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            if (currencies.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No currencies yet. Add your first currency!",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(currencies) { currency ->
                        CurrencyCard(
                            currency = currency,
                            currencyRepository = currencyRepository,
                        )
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { showCreateDialog = true },
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
        ) {
            Text("+", style = MaterialTheme.typography.headlineLarge)
        }

        if (showCreateDialog) {
            CreateCurrencyDialog(
                currencyRepository = currencyRepository,
                onDismiss = { showCreateDialog = false },
            )
        }
    }
}

@Composable
fun CurrencyCard(
    currency: Currency,
    currencyRepository: CurrencyRepository,
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = currency.code,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = currency.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = { showDeleteDialog = true },
            ) {
                Text(
                    text = "🗑️",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }

    if (showDeleteDialog) {
        DeleteCurrencyDialog(
            currency = currency,
            currencyRepository = currencyRepository,
            onDismiss = { showDeleteDialog = false },
        )
    }
}

@Composable
fun CreateCurrencyDialog(
    currencyRepository: CurrencyRepository,
    onDismiss: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text("Create New Currency") },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.uppercase().take(3) },
                    label = { Text("Currency Code (e.g., USD)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isSaving,
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Currency Name (e.g., US Dollar)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isSaving,
                )

                errorMessage?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when {
                        code.isBlank() -> errorMessage = "Currency code is required"
                        code.length != 3 -> errorMessage = "Currency code must be 3 characters"
                        name.isBlank() -> errorMessage = "Currency name is required"
                        else -> {
                            isSaving = true
                            errorMessage = null
                            scope.launch {
                                try {
                                    currencyRepository.upsertCurrencyByCode(code.trim(), name.trim())
                                    onDismiss()
                                } catch (e: Exception) {
                                    logger.error(e) { "Failed to create currency: ${e.message}" }
                                    errorMessage = "Failed to create currency: ${e.message}"
                                    isSaving = false
                                }
                            }
                        }
                    }
                },
                enabled = !isSaving,
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Create")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isSaving,
            ) {
                Text("Cancel")
            }
        },
    )
}

@Composable
fun DeleteCurrencyDialog(
    currency: Currency,
    currencyRepository: CurrencyRepository,
    onDismiss: () -> Unit,
) {
    var isDeleting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!isDeleting) onDismiss() },
        icon = {
            Text(
                text = "⚠️",
                style = MaterialTheme.typography.headlineMedium,
            )
        },
        title = { Text("Delete Currency?") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Are you sure you want to delete \"${currency.code} - ${currency.name}\"?",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = "This action cannot be undone. All accounts using this currency will be affected.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                errorMessage?.let { error ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    isDeleting = true
                    errorMessage = null
                    scope.launch {
                        try {
                            currencyRepository.deleteCurrency(currency.id)
                            onDismiss()
                        } catch (e: Exception) {
                            logger.error(e) { "Failed to delete currency: ${e.message}" }
                            errorMessage = "Failed to delete currency: ${e.message}"
                            isDeleting = false
                        }
                    }
                },
                enabled = !isDeleting,
                colors =
                    ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
            ) {
                if (isDeleting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Delete")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isDeleting,
            ) {
                Text("Cancel")
            }
        },
    )
}
