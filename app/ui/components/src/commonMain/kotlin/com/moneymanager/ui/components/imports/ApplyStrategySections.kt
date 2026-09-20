@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.moneymanager.ui.components.imports

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.moneymanager.csvimporter.DiscoveredAccountMapping
import com.moneymanager.csvimporter.ImportPreparation
import com.moneymanager.csvimporter.NewAccount
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.ui.navigation.linuxHorizontalScrollWheel

@Composable
fun StrategySelector(
    strategies: List<CsvImportStrategy>,
    selectedStrategy: CsvImportStrategy?,
    onStrategySelected: (CsvImportStrategy) -> Unit,
    csvColumns: List<CsvColumn>,
    enabled: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    val columnNames = csvColumns.map { it.originalName }

    Column {
        Text(
            text = "Select Strategy",
            style = MaterialTheme.typography.titleSmall,
        )

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { if (enabled) expanded = !expanded },
        ) {
            ReadonlyDropdownField(
                value = selectedStrategy?.name ?: "No strategy selected",
                expanded = expanded,
                modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                enabled = enabled,
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                strategies.forEach { strategy ->
                    val isMatch = strategy.matchesColumns(columnNames.toSet())
                    DropdownMenuItem(
                        text = {
                            Row(
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(strategy.name)
                                if (isMatch) {
                                    Text(
                                        text = "Match",
                                        color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        },
                        onClick = {
                            onStrategySelected(strategy)
                            expanded = false
                        },
                    )
                }
            }
        }

        if (strategies.isEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "No strategies available. Create a strategy first.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
fun NewAccountResolutionSection(
    newAccounts: List<NewAccount>,
    discoveredMappings: List<DiscoveredAccountMapping>,
    accounts: List<Account>,
    selectedExistingAccounts: Map<String, AccountId>,
    selectedNewAccountNames: Map<String, String>,
    onSelectionChanged: (String, AccountId?) -> Unit,
    onNewAccountNameChanged: (String, String) -> Unit,
    enabled: Boolean,
) {
    Column {
        Text(
            text = "New Account Handling",
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text =
                "Choose whether to create each detected account or map it to an existing one. " +
                    "Selected mappings will be saved to the strategy when you import.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))

        newAccounts
            .sortedBy { it.name.lowercase() }
            .forEach { newAccount ->
                val matchCount = discoveredMappings.count { it.targetAccountName == newAccount.name }
                NewAccountResolutionRow(
                    detectedAccountName = newAccount.name,
                    matchCount = matchCount,
                    accounts = accounts,
                    selectedAccountId = selectedExistingAccounts[newAccount.name],
                    newAccountName = selectedNewAccountNames[newAccount.name] ?: newAccount.name,
                    onSelectionChanged = { accountId ->
                        onSelectionChanged(newAccount.name, accountId)
                    },
                    onNewAccountNameChanged = { newName ->
                        onNewAccountNameChanged(newAccount.name, newName)
                    },
                    enabled = enabled,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
    }
}

@Composable
private fun NewAccountResolutionRow(
    detectedAccountName: String,
    matchCount: Int,
    accounts: List<Account>,
    selectedAccountId: AccountId?,
    newAccountName: String,
    onSelectionChanged: (AccountId?) -> Unit,
    onNewAccountNameChanged: (String) -> Unit,
    enabled: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedAccount = accounts.find { it.id == selectedAccountId }
    val isCreateNewSelection = selectedAccountId == null && newAccountName != detectedAccountName
    val dropdownLabel =
        when {
            selectedAccount != null -> selectedAccount.name
            isCreateNewSelection && newAccountName.isNotBlank() -> "Create New Account: $newAccountName"
            isCreateNewSelection -> "Create New Account"
            else -> "Exact match: $detectedAccountName"
        }

    Column {
        Text(
            text = detectedAccountName,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text =
                if (matchCount == 1) {
                    "1 matching CSV value in this import"
                } else {
                    "$matchCount matching CSV values in this import"
                },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { if (enabled) expanded = !expanded },
        ) {
            ReadonlyDropdownField(
                value = dropdownLabel,
                expanded = expanded,
                modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                enabled = enabled,
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Exact match: $detectedAccountName") },
                    onClick = {
                        onSelectionChanged(null)
                        onNewAccountNameChanged(detectedAccountName)
                        expanded = false
                    },
                )
                DropdownMenuItem(
                    text = { Text("Create New Account") },
                    onClick = {
                        onSelectionChanged(null)
                        onNewAccountNameChanged("")
                        expanded = false
                    },
                )
                accounts
                    .sortedBy { it.name.lowercase() }
                    .forEach { account ->
                        DropdownMenuItem(
                            text = { Text(account.name) },
                            onClick = {
                                onSelectionChanged(account.id)
                                expanded = false
                            },
                        )
                    }
            }
        }
        if (isCreateNewSelection) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = newAccountName,
                onValueChange = onNewAccountNameChanged,
                label = { Text("New account name") },
                placeholder = { Text("Detected: $detectedAccountName") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = enabled,
                isError = newAccountName.isBlank(),
                supportingText = {
                    Text(
                        text =
                            if (newAccountName.isBlank()) {
                                "Enter the name to create for this detected account"
                            } else {
                                "This name will be created and used for future mappings"
                            },
                    )
                },
            )
        }
    }
}

@Composable
fun ImportPreviewSection(
    prep: ImportPreparation,
    renamedNewAccountNames: Map<String, String> = emptyMap(),
) {
    Column {
        // Summary stats
        Text(
            text = "Import Preview",
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Status breakdown if available
        if (prep.statusCounts.isNotEmpty()) {
            Text(
                text = "Status Breakdown:",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(modifier = Modifier.height(8.dp))
            StatCardRow(
                stats =
                    listOfNotNull(
                        prep.statusCounts[ImportStatus.IMPORTED]?.let { StatCardData("New", it, MaterialTheme.colorScheme.primary) },
                        prep.statusCounts[ImportStatus.DUPLICATE]?.let {
                            StatCardData(
                                "Duplicate",
                                it,
                                MaterialTheme.colorScheme.secondary,
                            )
                        },
                        prep.statusCounts[ImportStatus.UPDATED]?.let { StatCardData("Updated", it, MaterialTheme.colorScheme.tertiary) },
                    ),
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        StatCardRow(
            stats =
                listOf(
                    StatCardData("Valid", prep.validTransfers.size, MaterialTheme.colorScheme.primary),
                    StatCardData("Errors", prep.errorRows.size, MaterialTheme.colorScheme.error),
                    StatCardData("New Accounts", prep.newAccounts.size, MaterialTheme.colorScheme.tertiary),
                ),
        )

        // New accounts to create
        if (prep.newAccounts.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "New Accounts to Create:",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            MaterialTheme.shapes.small,
                        ).padding(8.dp),
            ) {
                prep.newAccounts
                    .map { account ->
                        account.copy(
                            name =
                                renamedNewAccountNames[account.name]
                                    ?.takeIf { it.isNotBlank() }
                                    ?: account.name,
                        )
                    }.forEach { account ->
                        Text(
                            text = "• ${account.name}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
            }
        }

        // Error rows
        if (prep.errorRows.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Rows with Errors (will be skipped):",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.errorContainer,
                            MaterialTheme.shapes.small,
                        ).padding(8.dp),
            ) {
                prep.errorRows.take(5).forEach { error ->
                    Text(
                        text = "Row ${error.rowIndex}: ${error.errorMessage}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                if (prep.errorRows.size > 5) {
                    Text(
                        text = "... and ${prep.errorRows.size - 5} more errors",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        // Preview of valid transfers
        if (prep.validTransfers.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Transfer Preview (first 5):",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(modifier = Modifier.height(4.dp))
            TransferPreviewTable(prep.validTransfers.take(5).map { it.transfer })
        }
    }
}

@Composable
private fun StatCard(
    label: String,
    count: Int,
    color: Color,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            Modifier
                .background(
                    color.copy(alpha = 0.1f),
                    MaterialTheme.shapes.small,
                ).padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.headlineMedium,
            color = color,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
    }
}

private data class StatCardData(
    val label: String,
    val count: Int,
    val color: Color,
)

@Composable
private fun StatCardRow(stats: List<StatCardData>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        stats.forEach { stat ->
            StatCard(
                label = stat.label,
                count = stat.count,
                color = stat.color,
            )
        }
    }
}

@Composable
private fun TransferPreviewTable(transfers: List<Transfer>) {
    val scrollState = rememberScrollState()

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .linuxHorizontalScrollWheel(scrollState)
                .horizontalScroll(scrollState),
    ) {
        Column {
            // Header
            Row(
                modifier =
                    Modifier
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                TableCell("Date", isHeader = true)
                TableCell("Description", isHeader = true, width = 200.dp)
                TableCell("Amount", isHeader = true)
            }
            // Data rows
            transfers.forEach { transfer ->
                Row(
                    modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    TableCell(transfer.timestamp.toString().take(10))
                    TableCell(transfer.description, width = 200.dp)
                    TableCell(transfer.amount.toDisplayValue().toString())
                }
            }
        }
    }
}

@Composable
private fun TableCell(
    text: String,
    isHeader: Boolean = false,
    width: Dp = 100.dp,
) {
    Box(
        modifier =
            Modifier
                .width(width)
                .padding(8.dp),
    ) {
        Text(
            text = text,
            style =
                if (isHeader) {
                    MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold)
                } else {
                    MaterialTheme.typography.bodySmall
                },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ReadonlyDropdownField(
    value: String,
    expanded: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean,
) {
    OutlinedTextField(
        value = value,
        onValueChange = {},
        readOnly = true,
        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
        modifier = modifier,
        enabled = enabled,
    )
}
