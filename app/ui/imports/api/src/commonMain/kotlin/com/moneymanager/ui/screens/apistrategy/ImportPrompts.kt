@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.moneymanager.ui.screens.apistrategy

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moneymanager.apiimport.API_DOWNLOAD_ALL_TASK_KEY
import com.moneymanager.apiimport.API_IMPORT_ALL_TASK_KEY
import com.moneymanager.apiimport.API_SESSIONS_ARCHIVE_IMPORT_TASK_KEY
import com.moneymanager.apiimport.ApiDownloadOutcome
import com.moneymanager.apiimport.ApiImportListener
import com.moneymanager.apiimport.ApiImportService
import com.moneymanager.apiimport.apiDownloadTaskKey
import com.moneymanager.apiimport.apiImportTaskKey
import com.moneymanager.apiimport.displaySummary
import com.moneymanager.apiimport.filterCredentialsByStrategy
import com.moneymanager.apiimport.sessionImportedAtCurrentRevision
import com.moneymanager.apiimport.sessionsToImportInBulk
import com.moneymanager.apiimport.splitSessionsByImportState
import com.moneymanager.apiimport.startApiSessionsArchiveImport
import com.moneymanager.apiimport.storeNewSigningKey
import com.moneymanager.apiimporter.ApiCounterpartySuggestion
import com.moneymanager.apiimporter.ApiImportReads
import com.moneymanager.apiimporter.ApiSessionDownloadResult
import com.moneymanager.apiimporter.ApiSessionImportResult
import com.moneymanager.apiimporter.ApiSessionsArchive
import com.moneymanager.apiimporter.displaySummary
import com.moneymanager.compose.filepicker.rememberFilePicker
import com.moneymanager.compose.filepicker.rememberFileSaver
import com.moneymanager.compose.scrollbar.VerticalScrollbarForLazyList
import com.moneymanager.coroutines.runCatchingCancellable
import com.moneymanager.credentialvault.CredentialVaultLockedException
import com.moneymanager.credentialvault.StoredApiCredential
import com.moneymanager.credentialvault.VaultState
import com.moneymanager.csvimporter.Reimports
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.ApiCredential
import com.moneymanager.domain.model.ApiCredentialId
import com.moneymanager.domain.model.ApiImportStrategyId
import com.moneymanager.domain.model.ApiRequest
import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiResponse
import com.moneymanager.domain.model.ApiResponseId
import com.moneymanager.domain.model.ApiResponseTransaction
import com.moneymanager.domain.model.ApiResponseTransactionState
import com.moneymanager.domain.model.ApiSession
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.model.timeline.ImportFileDateRange
import com.moneymanager.domain.repository.AccountAttributeReadRepository
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.ApiImportStrategyReadRepository
import com.moneymanager.domain.repository.ApiSessionImportRevision
import com.moneymanager.domain.repository.ApiSessionReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.domain.repository.ImportRecordReadRepository
import com.moneymanager.domain.repository.ImportTimelineReadRepository
import com.moneymanager.presenter.error.rememberFlowAsStateWithSchemaErrorHandling
import com.moneymanager.presenter.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.tasks.Progress
import com.moneymanager.tasks.TaskState
import com.moneymanager.tasks.formatElapsedTime
import com.moneymanager.tasks.isRunning
import com.moneymanager.ui.foundation.LocalCredentialVault
import com.moneymanager.ui.foundation.LocalImportEngine
import com.moneymanager.ui.foundation.rememberPersistedState
import com.moneymanager.ui.tasks.LocalTaskManager
import com.moneymanager.ui.util.ContentCopyIcon
import com.moneymanager.ui.util.ExposedDropdownMenu
import com.moneymanager.ui.util.displayDate
import com.moneymanager.ui.util.displayDateTime
import com.moneymanager.ui.util.setPlainText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

// [answer] is set when a bulk run asked and waits for the names (null: leave the session for later).
internal class PendingApiImport(
    val session: ApiSession,
    val strategy: ApiImportStrategy,
    val counterparties: List<ApiCounterpartySuggestion>,
    val answer: CompletableDeferred<Map<String, String>?>? = null,
)

internal data class PendingApiReimport(
    val session: ApiSession,
    val strategy: ApiImportStrategy,
)

internal data class PendingApiReimportAll(
    val credentialId: ApiCredentialId,
    val strategy: ApiImportStrategy,
    val importedSessionCount: Int,
)

@Composable
internal fun CounterpartyConfirmationDialog(
    counterparties: List<ApiCounterpartySuggestion>,
    onDismiss: () -> Unit,
    onConfirm: (Map<String, String>) -> Unit,
) {
    var names by remember(counterparties) {
        mutableStateOf(counterparties.map { it.suggestedAccountName })
    }
    val hasBlankName = names.any { it.isBlank() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Confirm Counterparties") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "These counterparties will be created before importing transactions.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().height(420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(counterparties) { index, suggestion ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            OutlinedTextField(
                                value = names[index],
                                onValueChange = { value ->
                                    names = names.toMutableList().also { it[index] = value }
                                },
                                label = { Text("Account name") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                text = "Counterparty ID: ${suggestion.counterpartyId}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "Downloaded names: ${suggestion.downloadedNames.joinToString()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !hasBlankName,
                onClick = {
                    onConfirm(counterparties.mapIndexed { index, suggestion -> suggestion.counterpartyId to names[index].trim() }.toMap())
                },
            ) {
                Text("Create and Import")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
