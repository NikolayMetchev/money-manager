@file:OptIn(
    kotlin.uuid.ExperimentalUuidApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.moneymanager.ui.screens.apistrategy.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.model.apistrategy.ApiImportStrategyId
import com.moneymanager.domain.repository.ApiImportStrategyRepository
import com.moneymanager.domain.repository.ApiSessionRepository
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.screens.apistrategy.JsonPathEntry
import com.moneymanager.ui.screens.apistrategy.extractFirstArrayItem
import com.moneymanager.ui.screens.apistrategy.extractJsonPaths
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Full-screen editor for creating or editing an API import strategy. Models the CSV strategy
 * editor: a tabbed form backed by [ApiStrategyEditorState], with the JSON-path picker fed by
 * sample items scanned from recent API session responses.
 */
@Composable
fun ApiStrategyEditorScreen(
    strategyId: ApiImportStrategyId?,
    apiImportStrategyRepository: ApiImportStrategyRepository,
    apiSessionRepository: ApiSessionRepository,
    onBack: () -> Unit,
) {
    val isEditMode = strategyId != null
    val scope = rememberSchemaAwareCoroutineScope()

    val strategyFlow =
        remember(strategyId) {
            (strategyId?.let { apiImportStrategyRepository.getStrategyById(it) } ?: flowOf(null))
                .map<ApiImportStrategy?, StrategyLoad> { StrategyLoad.Loaded(it) }
        }
    val strategyLoad by strategyFlow.collectAsStateWithSchemaErrorHandling(
        if (isEditMode) StrategyLoad.Loading else StrategyLoad.Loaded(null),
    )
    val existingStrategy = (strategyLoad as? StrategyLoad.Loaded)?.strategy

    // Render a placeholder until the strategy resolves, or a terminal message if the id is gone.
    val placeholder: (@Composable () -> Unit)? =
        when {
            strategyLoad !is StrategyLoad.Loaded -> {
                { CircularProgressIndicator() }
            }
            isEditMode && existingStrategy == null -> {
                { Text("Strategy not found.", style = MaterialTheme.typography.bodyLarge) }
            }
            else -> null
        }
    if (placeholder != null) {
        EditorPlaceholder(isEditMode = isEditMode, onBack = onBack, content = placeholder)
        return
    }

    val initial = remember(existingStrategy) { existingStrategy?.let { extractFormStateFromStrategy(it) } }
    val editKey = strategyId?.toString() ?: "create"
    val state = rememberApiStrategyEditorState(editKey, initial)

    // Sample JSON items loaded from past sessions — null = not searched yet, "" = searched, none found.
    var accountSampleItem by remember { mutableStateOf<String?>(null) }
    var txSampleItem by remember { mutableStateOf<String?>(null) }
    val accountJsonPaths = remember(accountSampleItem) { accountSampleItem?.let { extractJsonPaths(it) } ?: emptyList() }
    val txJsonPaths = remember(txSampleItem) { txSampleItem?.let { extractJsonPaths(it) } ?: emptyList() }

    val accountsArrayKey = state.accountsEndpoint.responseArrayKey
    val transactionsArrayKey = state.transactionsEndpoint.responseArrayKey

    // Load sample JSON from the most recent session responses. Prefer credentials linked to this
    // strategy; fall back to all credentials so credentials created before linking still work.
    LaunchedEffect(strategyId, accountsArrayKey, transactionsArrayKey) {
        accountSampleItem = null
        txSampleItem = null

        val allCredentials = apiSessionRepository.getAllCredentials()
        val credentials =
            if (strategyId != null) {
                allCredentials.filter { it.strategyId == strategyId }.ifEmpty { allCredentials }
            } else {
                allCredentials
            }
        var foundAccountSample: String? = null
        var foundTxSample: String? = null
        for (credential in credentials) {
            val sessions = apiSessionRepository.getSessionsByCredential(credential.id).sortedByDescending { it.createdAt }
            for (session in sessions) {
                for (response in apiSessionRepository.getResponsesBySession(session.id)) {
                    if (foundAccountSample == null) {
                        extractFirstArrayItem(response.json, accountsArrayKey)?.let { foundAccountSample = it }
                    }
                    if (foundTxSample == null) {
                        extractFirstArrayItem(response.json, transactionsArrayKey)?.let { foundTxSample = it }
                    }
                    if (foundAccountSample != null && foundTxSample != null) break
                }
                if (foundAccountSample != null && foundTxSample != null) break
            }
            if (foundAccountSample != null && foundTxSample != null) break
        }
        accountSampleItem = foundAccountSample ?: ""
        txSampleItem = foundTxSample ?: ""
    }

    // The field setter currently awaiting a path pick, plus the paths offered to it.
    var pickingForSetter by remember { mutableStateOf<((String) -> Unit)?>(null) }
    var pickingPaths by remember { mutableStateOf<List<JsonPathEntry>>(emptyList()) }
    val onRequestPick: PathPicker = { paths, setter ->
        pickingPaths = paths
        pickingForSetter = setter
    }

    fun save() {
        if (!state.isValid) return
        state.isSaving = true
        state.errorMessage = null
        scope.launch {
            try {
                val now = Clock.System.now()
                val strategy =
                    buildStrategyFromApiFormState(
                        state = state.toFormState(),
                        id = existingStrategy?.id ?: ApiImportStrategyId(Uuid.random()),
                        createdAt = existingStrategy?.createdAt ?: now,
                        updatedAt = now,
                    )
                if (isEditMode) {
                    apiImportStrategyRepository.updateStrategy(strategy)
                } else {
                    apiImportStrategyRepository.createStrategy(strategy)
                }
                onBack()
            } catch (expected: Exception) {
                val action = if (isEditMode) "save" else "create"
                state.errorMessage = "Failed to $action strategy: ${expected.message}"
                state.isSaving = false
            }
        }
    }

    pickingForSetter?.let { setter ->
        JsonNodePickerDialog(
            paths = pickingPaths,
            onPick = { path ->
                setter(path)
                pickingForSetter = null
            },
            onDismiss = { pickingForSetter = null },
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        EditorHeader(
            isEditMode = isEditMode,
            saveEnabled = state.isValid && !state.isSaving,
            isSaving = state.isSaving,
            onBack = { if (!state.isSaving) onBack() },
            onSave = { save() },
        )
        PrimaryScrollableTabRow(selectedTabIndex = state.selectedTab.ordinal) {
            EditorTab.entries.forEach { tab ->
                Tab(
                    selected = state.selectedTab == tab,
                    onClick = { state.selectedTab = tab },
                    text = { Text(if (state.tabHasError(tab)) "${tab.title} •" else tab.title) },
                )
            }
        }
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
        ) {
            val enabled = !state.isSaving
            when (state.selectedTab) {
                EditorTab.GENERAL -> GeneralTab(state, enabled)
                EditorTab.ENDPOINTS -> EndpointsTab(state, enabled)
                EditorTab.ACCOUNT_MAPPINGS ->
                    AccountMappingsTab(state, accountJsonPaths, accountSampleItem != null, onRequestPick, enabled)
                EditorTab.TRANSACTION_MAPPINGS ->
                    TransactionMappingsTab(state, txJsonPaths, txSampleItem != null, onRequestPick, enabled)
                EditorTab.PEOPLE -> PeopleTab(state, txJsonPaths, onRequestPick, enabled)
                EditorTab.RULES -> RulesTab(state, txJsonPaths, onRequestPick, enabled)
                EditorTab.ADVANCED -> AdvancedTab(state, enabled)
            }

            state.errorMessage?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun EditorHeader(
    isEditMode: Boolean,
    saveEnabled: Boolean,
    isSaving: Boolean,
    onBack: () -> Unit,
    onSave: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack, enabled = !isSaving) { Text("← Back") }
        Text(
            if (isEditMode) "Edit API Strategy" else "Create API Strategy",
            style = MaterialTheme.typography.titleMedium,
        )
        TextButton(onClick = onSave, enabled = saveEnabled) {
            if (isSaving) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
            }
            Text(if (isEditMode) "Save" else "Create")
        }
    }
}

@Composable
private fun EditorPlaceholder(
    isEditMode: Boolean,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        EditorHeader(isEditMode = isEditMode, saveEnabled = false, isSaving = false, onBack = onBack, onSave = {})
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
    }
}

/** Load state for the edited strategy: "still loading" vs "loaded" (strategy may be null). */
private sealed interface StrategyLoad {
    data object Loading : StrategyLoad

    data class Loaded(
        val strategy: ApiImportStrategy?,
    ) : StrategyLoad
}
