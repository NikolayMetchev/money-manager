package com.moneymanager.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.domain.strategy.CsvResolution
import com.moneymanager.domain.strategy.CsvUnresolvedReference
import com.moneymanager.domain.strategy.StrategyKey
import com.moneymanager.domain.strategy.StrategyKind
import com.moneymanager.domain.strategy.StrategyLibrary
import com.moneymanager.remotestorage.RemoteAuthException
import com.moneymanager.remotestorage.sync.StrategyItem
import com.moneymanager.remotestorage.sync.StrategyItemStatus
import com.moneymanager.remotestorage.sync.StrategySyncController
import com.moneymanager.remotestorage.sync.SyncProgress
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import kotlinx.coroutines.launch

/** How the user chose to resolve a conflicting artifact (changed on both sides since the last sync). */
private enum class ConflictChoice {
    /** Upload the local version, overwriting the Drive copy. */
    KEEP_MINE,

    /** Import the Drive version, overwriting the local copy. */
    TAKE_DRIVE,
}

/** A sync paused on the reference-resolution dialog, keeping the directions chosen when it started. */
private data class PendingSync(
    val pull: Set<StrategyKey>,
    val forceUpload: Set<StrategyKey>,
    val unresolvedByKey: Map<StrategyKey, List<CsvUnresolvedReference>>,
)

/**
 * Settings card for the shared strategy library on remote storage: connect an independent Google Drive
 * account (works for any database, even local), see each strategy's sync state, auto-upload local
 * changes and selectively pull remote ones. One JSON file per strategy; nothing is ever deleted.
 */
@Composable
fun StrategyCloudCard(
    controller: StrategySyncController,
    library: StrategyLibrary,
    appVersion: AppVersion,
    accountRepository: AccountReadRepository,
    categoryRepository: CategoryReadRepository,
    currencyRepository: CurrencyReadRepository,
    personRepository: PersonReadRepository,
) {
    val scope = rememberSchemaAwareCoroutineScope()
    val state by controller.state.collectAsState()
    var connected by remember { mutableStateOf(controller.isConnected()) }
    var message by remember { mutableStateOf<String?>(null) }
    // Set when a failure was an expired/revoked Drive connection (Google `invalid_grant`), so the card
    // offers a "Reconnect" button (re-consent) instead of a dead error — mirrors the main-database flow.
    var needsReconnect by remember { mutableStateOf(false) }
    // Remote artifacts the user has ticked to pull (REMOTE_AHEAD / AVAILABLE).
    val selectedToPull = remember { mutableStateListOf<StrategyKey>() }
    // Per-conflict direction chosen by the user; a conflict without a choice is left untouched.
    val conflictChoices = remember { mutableStateMapOf<StrategyKey, ConflictChoice>() }
    // Set when a pull needs the user to resolve references before it can proceed.
    var pendingSync by remember { mutableStateOf<PendingSync?>(null) }
    var busyLocal by remember { mutableStateOf(false) }
    var syncProgress by remember { mutableStateOf<SyncProgress?>(null) }
    val actionsEnabled = !state.busy && !busyLocal

    // Records a failure message and, when the cause is an expired/revoked Drive connection, flags the
    // reconnect affordance so the user can re-consent (rather than being stuck on a dead error).
    fun fail(
        prefix: String,
        cause: Throwable,
    ) {
        message = "$prefix ${cause.message}"
        needsReconnect = cause is RemoteAuthException
    }

    // Runs the two-way sync (auto-upload + the given pulls/forced uploads) with collected resolutions.
    fun performSync(
        pull: Set<StrategyKey>,
        forceUpload: Set<StrategyKey>,
        resolutions: Map<StrategyKey, Map<CsvUnresolvedReference, CsvResolution>>,
    ) {
        message = null
        needsReconnect = false
        busyLocal = true
        scope.launch {
            runCatching {
                val summary =
                    controller.syncNow(
                        library,
                        appVersion,
                        selectedToPull = pull,
                        forceUpload = forceUpload,
                        resolutions = resolutions,
                        onProgress = { syncProgress = it },
                    )
                selectedToPull.clear()
                conflictChoices.clear()
                message = "Synced: ${summary.uploaded} uploaded, ${summary.pulled} imported."
            }.onFailure { fail("Sync failed:", it) }
            syncProgress = null
            busyLocal = false
        }
    }

    // On first show / once connected, refresh the view against the remote.
    LaunchedEffect(connected) {
        if (connected) {
            runCatching { controller.refresh(library, appVersion) }
                .onFailure { fail("Could not read the remote strategy library:", it) }
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Strategies (Cloud)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Share your import strategies across databases and devices — one file per strategy on Google Drive.",
                style = MaterialTheme.typography.bodySmall,
            )

            if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            if (!connected) {
                controller.availableProviders().forEach { type ->
                    OutlinedButton(
                        onClick = {
                            message = null
                            needsReconnect = false
                            controller.beginBusy()
                            scope.launch {
                                runCatching {
                                    controller.connect(type.id, config = null)
                                    connected = true
                                    controller.refresh(library, appVersion)
                                }.onFailure { fail("Could not connect:", it) }
                            }
                        },
                        enabled = actionsEnabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Connect strategies to ${type.displayName}…")
                    }
                }
            } else {
                StatusSummary(state.toUpload, state.updatesAvailable, state.newOnRemote, state.conflicts)

                StrategyItemList(
                    items = state.items,
                    selectedToPull = selectedToPull,
                    conflictChoices = conflictChoices,
                    onToggle = { key, checked -> if (checked) selectedToPull.add(key) else selectedToPull.remove(key) },
                    onConflictChoice = { key, choice ->
                        // Tapping the already-selected chip clears the choice (leave the conflict alone).
                        if (conflictChoices[key] == choice) conflictChoices.remove(key) else conflictChoices[key] = choice
                    },
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            message = null
                            needsReconnect = false
                            controller.beginBusy()
                            scope.launch {
                                runCatching { controller.refresh(library, appVersion) }
                                    .onFailure { fail("Check failed:", it) }
                            }
                        },
                        enabled = actionsEnabled,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Check remote")
                    }
                    OutlinedButton(
                        onClick = {
                            message = null
                            needsReconnect = false
                            // Only choices for keys that are still conflicts apply (state may have moved on).
                            val conflicts =
                                state.items
                                    .filter { it.status == StrategyItemStatus.CONFLICT }
                                    .map { it.key }
                                    .toSet()
                            val pull = selectedToPull.toSet() + conflicts.filter { conflictChoices[it] == ConflictChoice.TAKE_DRIVE }
                            val force = conflicts.filter { conflictChoices[it] == ConflictChoice.KEEP_MINE }.toSet()
                            busyLocal = true
                            scope.launch {
                                runCatching {
                                    // Any references the selected pulls can't resolve locally need the user first.
                                    val unresolved = mutableMapOf<StrategyKey, List<CsvUnresolvedReference>>()
                                    pull.forEachIndexed { index, key ->
                                        syncProgress = SyncProgress("Checking ${key.displayLabel()}…", index.toFloat() / pull.size)
                                        val references = controller.previewPull(library, key).unresolvedReferences
                                        if (references.isNotEmpty()) unresolved[key] = references
                                    }
                                    syncProgress = null
                                    busyLocal = false
                                    if (unresolved.isEmpty()) {
                                        performSync(pull, force, emptyMap())
                                    } else {
                                        pendingSync = PendingSync(pull, force, unresolved)
                                    }
                                }.onFailure {
                                    fail("Sync failed:", it)
                                    syncProgress = null
                                    busyLocal = false
                                }
                            }
                        },
                        enabled = actionsEnabled,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Sync now")
                    }
                }

                TextButton(onClick = {
                    controller.disconnect()
                    connected = false
                    needsReconnect = false
                    selectedToPull.clear()
                    conflictChoices.clear()
                }) {
                    Text("Disconnect")
                }
            }

            // A dead Drive connection (expired/revoked refresh token) can't be refreshed silently — the
            // user must re-consent. Offer that here (only while connected), then re-read the library.
            if (needsReconnect && connected) {
                OutlinedButton(
                    onClick = {
                        message = null
                        busyLocal = true
                        scope.launch {
                            runCatching {
                                controller.reconnect()
                                needsReconnect = false
                                controller.refresh(library, appVersion)
                            }.onFailure { fail("Reconnect failed:", it) }
                            busyLocal = false
                        }
                    },
                    enabled = actionsEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Reconnect to Google Drive")
                }
            }

            syncProgress?.let { progress ->
                Text(progress.message, style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            message?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    pendingSync?.let { pending ->
        StrategyPullResolutionDialog(
            unresolvedByKey = pending.unresolvedByKey,
            accountRepository = accountRepository,
            categoryRepository = categoryRepository,
            currencyRepository = currencyRepository,
            personRepository = personRepository,
            onConfirm = { resolutions ->
                pendingSync = null
                performSync(pending.pull, pending.forceUpload, resolutions)
            },
            onDismiss = { pendingSync = null },
        )
    }
}

@Composable
private fun StatusSummary(
    toUpload: Int,
    updatesAvailable: Int,
    newOnRemote: Int,
    conflicts: Int,
) {
    val line =
        when {
            toUpload == 0 && updatesAvailable == 0 && newOnRemote == 0 && conflicts == 0 -> "✓ Everything is synced"
            else ->
                buildList {
                    if (toUpload > 0) add("$toUpload to upload")
                    if (updatesAvailable > 0) add("$updatesAvailable to update")
                    if (newOnRemote > 0) add("$newOnRemote new on Drive")
                    if (conflicts > 0) add("$conflicts in conflict")
                }.joinToString(" · ")
        }
    Text(line, style = MaterialTheme.typography.bodyMedium)
    if (conflicts > 0) {
        Text(
            "Conflicts changed on both sides since the last sync — choose which version to keep for each. " +
                "Leaving no choice keeps both copies untouched.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun StrategyItemList(
    items: List<StrategyItem>,
    selectedToPull: List<StrategyKey>,
    conflictChoices: Map<StrategyKey, ConflictChoice>,
    onToggle: (StrategyKey, Boolean) -> Unit,
    onConflictChoice: (StrategyKey, ConflictChoice) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { item ->
            val pullable =
                item.status == StrategyItemStatus.AVAILABLE ||
                    item.status == StrategyItemStatus.REMOTE_AHEAD
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (pullable) {
                    Checkbox(
                        checked = selectedToPull.contains(item.key),
                        onCheckedChange = { onToggle(item.key, it) },
                    )
                } else {
                    // Keep names aligned with the checkboxed rows.
                    Spacer(Modifier.width(48.dp))
                }
                Text(
                    text = item.key.displayLabel(),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                if (item.status == StrategyItemStatus.CONFLICT) {
                    // A conflict needs an explicit direction: upload the local version or take Drive's.
                    val choice = conflictChoices[item.key]
                    FilterChip(
                        selected = choice == ConflictChoice.KEEP_MINE,
                        onClick = { onConflictChoice(item.key, ConflictChoice.KEEP_MINE) },
                        label = { Text("Keep mine") },
                    )
                    Spacer(Modifier.width(4.dp))
                    FilterChip(
                        selected = choice == ConflictChoice.TAKE_DRIVE,
                        onClick = { onConflictChoice(item.key, ConflictChoice.TAKE_DRIVE) },
                        label = { Text("Take Drive") },
                    )
                } else {
                    Text(
                        text = statusLabel(item.status),
                        style = MaterialTheme.typography.bodySmall,
                        fontStyle = FontStyle.Italic,
                        color = statusColor(item.status),
                    )
                }
            }
        }
    }
}

@Composable
private fun statusColor(status: StrategyItemStatus) =
    when (status) {
        StrategyItemStatus.IN_SYNC -> MaterialTheme.colorScheme.onSurfaceVariant
        StrategyItemStatus.CONFLICT -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }

private fun statusLabel(status: StrategyItemStatus): String =
    when (status) {
        StrategyItemStatus.IN_SYNC -> "In sync"
        StrategyItemStatus.NEW_LOCAL -> "Will upload"
        StrategyItemStatus.LOCAL_AHEAD -> "Will upload"
        StrategyItemStatus.REMOTE_AHEAD -> "Update available"
        StrategyItemStatus.AVAILABLE -> "New on Drive"
        StrategyItemStatus.CONFLICT -> "Conflict"
    }

/**
 * Human-readable label for a library artifact. Strategies show their name plus the kind; the single
 * global-mappings artifact has a fixed technical name, so it gets a friendly title instead.
 */
private fun StrategyKey.displayLabel(): String =
    when (kind) {
        StrategyKind.GLOBAL_MAPPINGS -> "Global account mappings"
        StrategyKind.CSV -> "$name (CSV)"
        StrategyKind.QIF -> "$name (QIF)"
        StrategyKind.API -> "$name (API)"
        StrategyKind.PASS_THROUGH -> "$name (Pass-through)"
    }
