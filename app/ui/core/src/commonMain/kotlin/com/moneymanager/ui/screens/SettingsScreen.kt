@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.database.RepositorySet
import com.moneymanager.ui.util.GenerationProgress
import com.moneymanager.ui.util.generateSampleData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import nl.jacobras.humanreadable.HumanReadable
import kotlin.time.Duration

private enum class MaintenanceOperation {
    REINDEX,
    VACUUM,
    ANALYZE,
}

private data class MaintenanceState(
    val runningOperation: MaintenanceOperation? = null,
    val lastResults: Map<MaintenanceOperation, Duration> = emptyMap(),
    val error: String? = null,
)

@Composable
fun SettingsScreen(repositorySet: RepositorySet) {
    var showWarningDialog by remember { mutableStateOf(false) }
    var isGenerating by remember { mutableStateOf(false) }
    var showProgressDialog by remember { mutableStateOf(false) }
    var generationProgress by remember { mutableStateOf(GenerationProgress()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var successMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Maintenance state
    var maintenanceState by remember { mutableStateOf(MaintenanceState()) }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium,
        )

        // Maintenance Section
        Card(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Maintenance",
                    style = MaterialTheme.typography.titleMedium,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MaintenanceOperation.entries.forEach { operation ->
                        MaintenanceButton(
                            modifier = Modifier.weight(1f),
                            operation = operation,
                            isRunning = maintenanceState.runningOperation == operation,
                            isDisabled = maintenanceState.runningOperation != null,
                            lastDuration = maintenanceState.lastResults[operation],
                            onClick = {
                                maintenanceState =
                                    maintenanceState.copy(
                                        runningOperation = operation,
                                        error = null,
                                    )
                                scope.launch {
                                    try {
                                        val duration =
                                            when (operation) {
                                                MaintenanceOperation.REINDEX ->
                                                    repositorySet.maintenanceService.reindex()

                                                MaintenanceOperation.VACUUM ->
                                                    repositorySet.maintenanceService.vacuum()

                                                MaintenanceOperation.ANALYZE ->
                                                    repositorySet.maintenanceService.analyze()
                                            }
                                        maintenanceState =
                                            maintenanceState.copy(
                                                runningOperation = null,
                                                lastResults = maintenanceState.lastResults + (operation to duration),
                                            )
                                    } catch (e: Exception) {
                                        maintenanceState =
                                            maintenanceState.copy(
                                                runningOperation = null,
                                                error = "${operation.name} failed: ${e.message}",
                                            )
                                    }
                                }
                            },
                        )
                    }
                }

                maintenanceState.error?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                // Refresh Materialized Views
                var isRefreshingViews by remember { mutableStateOf(false) }
                var refreshViewsError by remember { mutableStateOf<String?>(null) }
                var refreshViewsDuration by remember { mutableStateOf<Duration?>(null) }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            isRefreshingViews = true
                            refreshViewsError = null
                            scope.launch {
                                try {
                                    refreshViewsDuration = repositorySet.maintenanceService.refreshMaterializedViews()
                                } catch (e: Exception) {
                                    refreshViewsError = "Refresh failed: ${e.message}"
                                } finally {
                                    isRefreshingViews = false
                                }
                            }
                        },
                        enabled = !isRefreshingViews && maintenanceState.runningOperation == null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (isRefreshingViews) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text("Refresh Materialized Views")
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = refreshViewsDuration?.let { formatDuration(it) } ?: "-",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    refreshViewsError?.let { error ->
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        // Developer Section
        Card(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Developer",
                    style = MaterialTheme.typography.titleMedium,
                )

                Button(
                    onClick = {
                        errorMessage = null
                        successMessage = null
                        showWarningDialog = true
                    },
                    enabled = !isGenerating,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (isGenerating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text("Generate Sample Data")
                    }
                }

                errorMessage?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                successMessage?.let { success ->
                    Text(
                        text = success,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }

    // Warning Dialog
    if (showWarningDialog) {
        AlertDialog(
            onDismissRequest = { showWarningDialog = false },
            title = { Text("Generate Sample Data?") },
            text = {
                Text(
                    "This will create 100 accounts with thousands of transactions spanning 2015-2025. " +
                        "This operation cannot be easily undone. Continue?",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showWarningDialog = false
                        isGenerating = true
                        showProgressDialog = true
                        generationProgress = GenerationProgress()

                        scope.launch {
                            try {
                                val progressFlow = MutableStateFlow(GenerationProgress())

                                // Collect progress updates
                                launch {
                                    progressFlow.collect { progress ->
                                        generationProgress = progress
                                    }
                                }

                                // Generate sample data
                                generateSampleData(repositorySet, progressFlow)

                                successMessage = "Sample data generated successfully! " +
                                    "Created ${generationProgress.accountsCreated} accounts and " +
                                    "${generationProgress.transactionsCreated} transactions."
                                showProgressDialog = false
                            } catch (e: Exception) {
                                errorMessage = "Failed to generate sample data: ${e.message}"
                                showProgressDialog = false
                            } finally {
                                isGenerating = false
                            }
                        }
                    },
                ) {
                    Text("Generate")
                }
            },
            dismissButton = {
                TextButton(onClick = { showWarningDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    // Progress Dialog
    if (showProgressDialog) {
        AlertDialog(
            // Cannot dismiss during generation
            onDismissRequest = { },
            title = { Text("Generating Sample Data") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = generationProgress.currentOperation,
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    LinearProgressIndicator(
                        progress = {
                            val totalProgress = generationProgress.accountsCreated + generationProgress.transactionsCreated
                            val totalExpected = generationProgress.totalAccounts + generationProgress.totalTransactions
                            if (totalExpected > 0) {
                                totalProgress.toFloat() / totalExpected.toFloat()
                            } else {
                                0f
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Text(
                        text =
                            "Created ${generationProgress.accountsCreated}/${generationProgress.totalAccounts} accounts, " +
                                "${generationProgress.transactionsCreated}/${generationProgress.totalTransactions} transactions",
                        style = MaterialTheme.typography.bodySmall,
                    )

                    val totalProgress = generationProgress.accountsCreated + generationProgress.transactionsCreated
                    val totalExpected = generationProgress.totalAccounts + generationProgress.totalTransactions
                    val percentage =
                        if (totalExpected > 0) {
                            (totalProgress.toFloat() / totalExpected.toFloat() * 100).toInt()
                        } else {
                            0
                        }
                    Text(
                        text = "$percentage% complete",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            },
            confirmButton = { },
        )
    }
}

@Composable
private fun MaintenanceButton(
    operation: MaintenanceOperation,
    isRunning: Boolean,
    isDisabled: Boolean,
    lastDuration: Duration?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        OutlinedButton(
            onClick = onClick,
            enabled = !isDisabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (isRunning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Text(operation.name)
            }
        }
        Text(
            text = lastDuration?.let { formatDuration(it) } ?: "-",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

private fun formatDuration(duration: Duration): String = HumanReadable.duration(duration)
