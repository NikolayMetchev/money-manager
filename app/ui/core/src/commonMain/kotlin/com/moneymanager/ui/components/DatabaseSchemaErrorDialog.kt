package com.moneymanager.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Dialog shown when database schema migration fails or incompatible database is detected.
 * Offers options to backup the corrupt database or delete it and start fresh.
 */
@Composable
fun DatabaseSchemaErrorDialog(
    databaseLocation: String,
    error: Throwable,
    onBackupAndCreateNew: () -> Unit,
    onDeleteAndCreateNew: () -> Unit,
) {
    // Fullscreen semi-transparent background
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier =
                Modifier
                    .width(600.dp)
                    .heightIn(max = 800.dp)
                    .padding(16.dp),
            shape = MaterialTheme.shapes.medium,
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier =
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Header with warning icon
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Warning",
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = "Database Schema Error",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                // Error explanation
                Text(
                    text =
                        "The database at the following location has an incompatible schema. " +
                            "This usually happens when the database structure has changed in a newer " +
                            "version of the application.",
                    style = MaterialTheme.typography.bodyMedium,
                )

                // Database location
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Database location:",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = databaseLocation,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }

                // Error details (collapsed)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Error details:",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = error.message ?: error.toString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                    )
                }

                HorizontalDivider()

                // Options
                Text(
                    text = "Choose how to proceed:",
                    style = MaterialTheme.typography.titleMedium,
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Option 1: Backup and create new
                    OutlinedButton(
                        onClick = onBackupAndCreateNew,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "Backup Database and Create New",
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                text =
                                    "Renames the existing database to .backup and creates a fresh database. " +
                                        "You can manually recover data from the backup later.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }

                    // Option 2: Delete and create new
                    Button(
                        onClick = onDeleteAndCreateNew,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "Delete Database and Start Fresh",
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                text =
                                    "Permanently deletes the existing database and creates a new one. " +
                                        "All data will be lost.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}
