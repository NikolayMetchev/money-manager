package com.moneymanager.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.Asset
import com.moneymanager.domain.repository.AssetRepository
import kotlinx.coroutines.launch
import org.lighthousegames.logging.logging

private val logger = logging()

@Composable
fun AssetsScreen(assetRepository: AssetRepository) {
    val assets by assetRepository.getAllAssets().collectAsState(initial = emptyList())
    var showCreateDialog by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(16.dp),
        ) {
            Text(
                text = "Your Assets",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            if (assets.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No assets yet. Add your first asset!",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(assets) { asset ->
                        AssetCard(
                            asset = asset,
                            assetRepository = assetRepository,
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
            CreateAssetDialog(
                assetRepository = assetRepository,
                onDismiss = { showCreateDialog = false },
            )
        }
    }
}

@Composable
fun AssetCard(
    asset: Asset,
    assetRepository: AssetRepository,
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
            Text(
                text = asset.name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
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
        DeleteAssetDialog(
            asset = asset,
            assetRepository = assetRepository,
            onDismiss = { showDeleteDialog = false },
        )
    }
}

@Composable
fun CreateAssetDialog(
    assetRepository: AssetRepository,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text("Create New Asset") },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Asset Name") },
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
                    if (name.isBlank()) {
                        errorMessage = "Asset name is required"
                    } else {
                        isSaving = true
                        errorMessage = null
                        scope.launch {
                            try {
                                assetRepository.upsertAssetByName(name.trim())
                                onDismiss()
                            } catch (e: Exception) {
                                logger.error(e) { "Failed to create asset: ${e.message}" }
                                errorMessage = "Failed to create asset: ${e.message}"
                                isSaving = false
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
fun DeleteAssetDialog(
    asset: Asset,
    assetRepository: AssetRepository,
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
        title = { Text("Delete Asset?") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Are you sure you want to delete \"${asset.name}\"?",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = "This action cannot be undone. All accounts using this asset will be affected.",
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
                            assetRepository.deleteAsset(asset.id)
                            onDismiss()
                        } catch (e: Exception) {
                            logger.error(e) { "Failed to delete asset: ${e.message}" }
                            errorMessage = "Failed to delete asset: ${e.message}"
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
