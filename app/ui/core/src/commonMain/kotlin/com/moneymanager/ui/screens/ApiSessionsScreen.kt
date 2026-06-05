@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.ui.screens

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moneymanager.compose.scrollbar.VerticalScrollbarForLazyList
import com.moneymanager.domain.EntitySource
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.ApiRequest
import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiResponse
import com.moneymanager.domain.model.ApiResponseId
import com.moneymanager.domain.model.ApiResponseTransaction
import com.moneymanager.domain.model.ApiResponseTransactionState
import com.moneymanager.domain.model.ApiSession
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.ApiSessionKind
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.MonzoCredential
import com.moneymanager.domain.model.MonzoCredentialId
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.repository.AccountAttributeRepository
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.ApiImportStrategyRepository
import com.moneymanager.domain.repository.ApiSessionImportRevision
import com.moneymanager.domain.repository.ApiSessionRepository
import com.moneymanager.domain.repository.AttributeTypeRepository
import com.moneymanager.domain.repository.CurrencyRepository
import com.moneymanager.domain.repository.PersonAccountOwnershipRepository
import com.moneymanager.domain.repository.PersonAttributeRepository
import com.moneymanager.domain.repository.PersonRepository
import com.moneymanager.domain.repository.TransactionRepository
import com.moneymanager.rest.ApiSessionTrafficRecorder
import com.moneymanager.rest.ScaParams
import com.moneymanager.rest.createApiClient
import com.moneymanager.ui.api.ApiAccountsDownloadResult
import com.moneymanager.ui.api.ApiCounterpartySuggestion
import com.moneymanager.ui.api.ApiSessionImportProgress
import com.moneymanager.ui.api.ApiSessionImportResult
import com.moneymanager.ui.api.ApiTransactionsDownloadProgress
import com.moneymanager.ui.api.ApiTransactionsDownloadResult
import com.moneymanager.ui.api.discoverApiCounterpartiesToCreate
import com.moneymanager.ui.api.downloadApiSessionAccounts
import com.moneymanager.ui.api.downloadApiSessionPeople
import com.moneymanager.ui.api.downloadApiSessionTransactions
import com.moneymanager.ui.api.importApiSessionPeople
import com.moneymanager.ui.api.importApiSessionTransactions
import com.moneymanager.ui.api.sca.generateScaKeyPair
import com.moneymanager.ui.api.sca.signScaChallenge
import com.moneymanager.ui.background.LocalBackgroundTaskManager
import com.moneymanager.ui.background.formatElapsedTime
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.util.ContentCopyIcon
import com.moneymanager.ui.util.currentCountryCode
import com.moneymanager.ui.util.displayDateTime
import com.moneymanager.ui.util.setPlainText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

@Composable
fun ApiSessionsScreen(
    apiSessionRepository: ApiSessionRepository,
    apiImportStrategyRepository: ApiImportStrategyRepository,
    attributeTypeRepository: AttributeTypeRepository,
    accountAttributeRepository: AccountAttributeRepository,
    accountRepository: AccountRepository,
    currencyRepository: CurrencyRepository,
    transactionRepository: TransactionRepository,
    entitySource: EntitySource,
    maintenance: Maintenance,
    personRepository: PersonRepository,
    personAccountOwnershipRepository: PersonAccountOwnershipRepository,
    personAttributeRepository: PersonAttributeRepository,
    deviceId: DeviceId,
    onMonzoConnectClick: () -> Unit = {},
    onApiStrategiesClick: () -> Unit = {},
    onSessionClick: (ApiSession) -> Unit = {},
    onTransactionsImported: () -> Unit = {},
) {
    val scope = rememberSchemaAwareCoroutineScope()
    val backgroundTasks = LocalBackgroundTaskManager.current
    val clipboard = LocalClipboard.current

    var credentials by remember { mutableStateOf<List<MonzoCredential>>(emptyList()) }
    var sessionsByCredential by remember { mutableStateOf<Map<MonzoCredentialId, List<ApiSession>>>(emptyMap()) }
    var importedSessionRevisions by remember { mutableStateOf<Set<ApiSessionImportRevision>>(emptySet()) }
    var currentStrategyRevisionByCredential by remember { mutableStateOf<Map<MonzoCredentialId, Long?>>(emptyMap()) }
    var strategyNameByCredential by remember { mutableStateOf<Map<MonzoCredentialId, String>>(emptyMap()) }
    var requiresSigningByCredential by remember { mutableStateOf<Map<MonzoCredentialId, Boolean>>(emptyMap()) }
    var transactionsBlockReasonByCredential by remember { mutableStateOf<Map<MonzoCredentialId, String>>(emptyMap()) }
    var peopleDownloadSupportedByCredential by remember { mutableStateOf<Map<MonzoCredentialId, Boolean>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(true) }
    // Per-session import state
    var importResultBySession by remember { mutableStateOf<Map<ApiSessionId, ApiSessionImportResult>>(emptyMap()) }
    var importErrorBySession by remember { mutableStateOf<Map<ApiSessionId, String>>(emptyMap()) }
    var importProgressBySession by remember { mutableStateOf<Map<ApiSessionId, ApiSessionImportProgress>>(emptyMap()) }

    // Per-credential download result state (cleared when a new download starts)
    var accountsDownloadResultByCredential by remember { mutableStateOf<Map<MonzoCredentialId, ApiAccountsDownloadResult>>(emptyMap()) }
    var downloadResultByCredential by remember { mutableStateOf<Map<MonzoCredentialId, ApiTransactionsDownloadResult>>(emptyMap()) }
    var downloadProgressByCredential by remember { mutableStateOf<Map<MonzoCredentialId, ApiTransactionsDownloadProgress?>>(emptyMap()) }
    var pendingImport by remember { mutableStateOf<PendingApiImport?>(null) }

    suspend fun resolveStrategy(credential: MonzoCredential): ApiImportStrategy? =
        credential.strategyId
            ?.let { apiImportStrategyRepository.getStrategyById(it).first() }
            ?: apiImportStrategyRepository.getAllStrategies().first().firstOrNull()

    // Builds the SCA request-signing params when the strategy is SCA-protected and the credential
    // has a signing key (e.g. Wise statements). Null disables signing (e.g. Monzo).
    fun scaParamsFor(
        strategy: ApiImportStrategy,
        credential: MonzoCredential,
    ): ScaParams? {
        val signing = strategy.signing ?: return null
        val privateKey = credential.privateKey ?: return null
        return ScaParams(
            challengeHeader = signing.challengeHeader,
            signatureHeader = signing.signatureHeader,
            triggerStatus = signing.triggerStatus,
            sign = { oneTimeToken -> signScaChallenge(privateKey, oneTimeToken) },
        )
    }

    fun refresh() {
        scope.launch {
            try {
                val allCredentials = apiSessionRepository.getAllCredentials()
                val allSessions = apiSessionRepository.getSessionsByDevice(deviceId)
                credentials = allCredentials
                sessionsByCredential =
                    allSessions
                        .filter { it.credentialId != null }
                        .groupBy { it.credentialId!! }
                val allStrategies = apiImportStrategyRepository.getAllStrategies().first()
                val strategyById = allStrategies.associateBy { it.id }
                val fallbackStrategyRevision = allStrategies.firstOrNull()?.revisionId
                currentStrategyRevisionByCredential =
                    allCredentials.associate { credential ->
                        credential.id to (
                            credential.strategyId
                                ?.let { strategyById[it]?.revisionId }
                                ?: fallbackStrategyRevision
                        )
                    }
                // Label each credential by its linked strategy (the actual provider), not the legacy
                // session type which is always "Monzo".
                val fallbackStrategy = allStrategies.firstOrNull()
                strategyNameByCredential =
                    allCredentials
                        .mapNotNull { credential ->
                            val name =
                                credential.strategyId?.let { strategyById[it]?.name }
                                    ?: fallbackStrategy?.name
                            name?.let { credential.id to it }
                        }.toMap()
                requiresSigningByCredential =
                    allCredentials.associate { credential ->
                        val strategy = credential.strategyId?.let { strategyById[it] } ?: fallbackStrategy
                        credential.id to (strategy?.signing != null)
                    }
                peopleDownloadSupportedByCredential =
                    allCredentials.associate { credential ->
                        val strategy = credential.strategyId?.let { strategyById[it] } ?: fallbackStrategy
                        credential.id to (strategy?.peopleDownload != null)
                    }
                val country = currentCountryCode()
                transactionsBlockReasonByCredential =
                    allCredentials
                        .mapNotNull { credential ->
                            val strategy = credential.strategyId?.let { strategyById[it] } ?: fallbackStrategy
                            val countries = strategy?.signing?.statementCountries.orEmpty()
                            if (countries.isNotEmpty() && (country == null || country !in countries)) {
                                credential.id to
                                    "Transaction download isn't available for your region" +
                                    (country?.let { " ($it)" } ?: "") +
                                    ". ${strategy?.name ?: "This provider"} only supports statements for: " +
                                    countries.sorted().joinToString(", ") + "."
                            } else {
                                null
                            }
                        }.toMap()
                importedSessionRevisions = apiSessionRepository.getImportedSessionRevisions()
            } finally {
                isLoading = false
            }
        }
    }

    fun startImport(
        session: ApiSession,
        accountsSession: ApiSession?,
        strategy: ApiImportStrategy,
        counterpartyAccountNames: Map<String, String>,
    ) {
        val importLabel =
            when (session.kind) {
                ApiSessionKind.ACCOUNTS -> "Import Accounts"
                ApiSessionKind.PEOPLE -> "Import People"
                else -> "Import Transactions"
            }
        backgroundTasks.startTask(
            key = monzoImportTaskKey(session.id),
            title = importLabel,
            initialDetail = "Starting $importLabel for session #${session.id}.",
        ) {
            val importStartedAt = System.currentTimeMillis()
            val result =
                if (session.kind == ApiSessionKind.PEOPLE) {
                    val peopleResult =
                        importApiSessionPeople(
                            apiSessionRepository = apiSessionRepository,
                            accountRepository = accountRepository,
                            accountAttributeRepository = accountAttributeRepository,
                            personRepository = personRepository,
                            personAccountOwnershipRepository = personAccountOwnershipRepository,
                            personAttributeRepository = personAttributeRepository,
                            attributeTypeRepository = attributeTypeRepository,
                            entitySource = entitySource,
                            sessionId = session.id,
                            strategy = strategy,
                            accountsSessionId = accountsSession?.id,
                        )
                    ApiSessionImportResult(accountCount = 0, transactionCount = 0, personCount = peopleResult.personCount)
                } else {
                    importApiSessionTransactions(
                        apiSessionRepository = apiSessionRepository,
                        accountRepository = accountRepository,
                        currencyRepository = currencyRepository,
                        transactionRepository = transactionRepository,
                        entitySource = entitySource,
                        personRepository = personRepository,
                        personAccountOwnershipRepository = personAccountOwnershipRepository,
                        personAttributeRepository = personAttributeRepository,
                        attributeTypeRepository = attributeTypeRepository,
                        accountAttributeRepository = accountAttributeRepository,
                        deviceId = deviceId,
                        sessionId = session.id,
                        accountsSessionId = accountsSession?.id,
                        strategy = strategy,
                        counterpartyAccountNames = counterpartyAccountNames,
                        onProgress = { progress ->
                            scope.launch {
                                importProgressBySession = importProgressBySession + (session.id to progress)
                            }
                            update(progress.detail, progress.progress)
                        },
                    )
                }
            val importDurationMillis = System.currentTimeMillis() - importStartedAt
            apiSessionRepository.markSessionImported(
                id = session.id,
                revisionId = strategy.revisionId,
                importedAt = Clock.System.now(),
                importDurationMillis = importDurationMillis,
            )
            maintenance.refreshMaterializedViews()
            importResultBySession = importResultBySession + (session.id to result)
            importProgressBySession = importProgressBySession - session.id
            refresh()
            onTransactionsImported()
            result.displaySummary()
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "API Sessions", style = MaterialTheme.typography.headlineMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onApiStrategiesClick) { Text("Strategies") }
                TextButton(onClick = onMonzoConnectClick) { Text("+ Connect") }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        when {
            isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            credentials.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No credentials yet. Click '+ Connect' to add one.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> {
                val lazyListState = rememberLazyListState()
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(state = lazyListState, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(credentials) { credential ->
                            val credentialSessions = sessionsByCredential[credential.id].orEmpty()
                            val isDownloadingAccounts = backgroundTasks.isRunning(monzoAccountsDownloadTaskKey(credential.id))
                            val isDownloadingTransactions = backgroundTasks.isRunning(monzoTransactionsDownloadTaskKey(credential.id))
                            val isDownloadingPeople = backgroundTasks.isRunning(monzoPeopleDownloadTaskKey(credential.id))
                            CredentialCard(
                                credential = credential,
                                providerLabel = strategyNameByCredential[credential.id],
                                requiresSigning = requiresSigningByCredential[credential.id] == true,
                                transactionsBlockReason = transactionsBlockReasonByCredential[credential.id],
                                peopleDownloadSupported = peopleDownloadSupportedByCredential[credential.id] == true,
                                isDownloadingPeople = isDownloadingPeople,
                                onGenerateSigningKey = {
                                    scope.launch {
                                        val keyPair = withContext(Dispatchers.Default) { generateScaKeyPair() }
                                        apiSessionRepository.updateCredentialKeys(
                                            credentialId = credential.id,
                                            privateKey = keyPair.privateKeyPem,
                                            publicKey = keyPair.publicKeyPem,
                                        )
                                        refresh()
                                    }
                                },
                                onCopyText = { text -> scope.launch { clipboard.setPlainText(text) } },
                                sessions = credentialSessions,
                                isDownloadingAccounts = isDownloadingAccounts,
                                isDownloadingTransactions = isDownloadingTransactions,
                                accountsDownloadResult = accountsDownloadResultByCredential[credential.id],
                                downloadResult = downloadResultByCredential[credential.id],
                                downloadProgress = downloadProgressByCredential[credential.id],
                                importResultBySession = importResultBySession,
                                importErrorBySession = importErrorBySession,
                                importProgressBySession = importProgressBySession,
                                importedSessionRevisions = importedSessionRevisions,
                                selectedStrategyRevision = currentStrategyRevisionByCredential[credential.id],
                                isImportingSession = { sessionId -> backgroundTasks.isRunning(monzoImportTaskKey(sessionId)) },
                                onDownloadAccounts = {
                                    accountsDownloadResultByCredential = accountsDownloadResultByCredential - credential.id
                                    scope.launch {
                                        val strategy = resolveStrategy(credential) ?: return@launch
                                        val newSessionId =
                                            apiSessionRepository.createSession(
                                                token = credential.token,
                                                deviceId = deviceId,
                                                createdAt = Clock.System.now(),
                                                expiresAt = null,
                                                credentialId = credential.id,
                                                kind = ApiSessionKind.ACCOUNTS,
                                            )
                                        refresh()
                                        backgroundTasks.startTask(
                                            key = monzoAccountsDownloadTaskKey(credential.id),
                                            title = "Download Accounts",
                                            initialDetail = "Starting accounts download for session #$newSessionId.",
                                        ) {
                                            val result =
                                                downloadApiSessionAccounts(
                                                    token = credential.token,
                                                    apiClient =
                                                        createApiClient(
                                                            trafficRecorder =
                                                                ApiSessionTrafficRecorder(
                                                                    sessionId = newSessionId,
                                                                    apiSessionRepository = apiSessionRepository,
                                                                ),
                                                            engine = null,
                                                        ),
                                                    apiSessionRepository = apiSessionRepository,
                                                    sessionId = newSessionId,
                                                    strategy = strategy,
                                                    sca = scaParamsFor(strategy, credential),
                                                )
                                            accountsDownloadResultByCredential =
                                                accountsDownloadResultByCredential + (credential.id to result)
                                            result.displaySummary()
                                        }
                                    }
                                },
                                onDownloadPeople = {
                                    scope.launch {
                                        val strategy = resolveStrategy(credential) ?: return@launch
                                        val newSessionId =
                                            apiSessionRepository.createSession(
                                                token = credential.token,
                                                deviceId = deviceId,
                                                createdAt = Clock.System.now(),
                                                expiresAt = null,
                                                credentialId = credential.id,
                                                kind = ApiSessionKind.PEOPLE,
                                            )
                                        refresh()
                                        backgroundTasks.startTask(
                                            key = monzoPeopleDownloadTaskKey(credential.id),
                                            title = "Download People",
                                            initialDetail = "Starting people download for session #$newSessionId.",
                                        ) {
                                            val result =
                                                downloadApiSessionPeople(
                                                    token = credential.token,
                                                    apiClient =
                                                        createApiClient(
                                                            trafficRecorder =
                                                                ApiSessionTrafficRecorder(
                                                                    sessionId = newSessionId,
                                                                    apiSessionRepository = apiSessionRepository,
                                                                ),
                                                            engine = null,
                                                        ),
                                                    apiSessionRepository = apiSessionRepository,
                                                    sessionId = newSessionId,
                                                    strategy = strategy,
                                                    sca = scaParamsFor(strategy, credential),
                                                )
                                            refresh()
                                            "Downloaded ${result.personCount} ${if (result.personCount == 1) "person" else "people"}."
                                        }
                                    }
                                },
                                onDownloadTransactions = {
                                    downloadResultByCredential = downloadResultByCredential - credential.id
                                    downloadProgressByCredential = downloadProgressByCredential - credential.id
                                    val accountsSession = credentialSessions.firstOrNull { it.kind == ApiSessionKind.ACCOUNTS }
                                    scope.launch {
                                        val strategy = resolveStrategy(credential) ?: return@launch
                                        val newSessionId =
                                            apiSessionRepository.createSession(
                                                token = credential.token,
                                                deviceId = deviceId,
                                                createdAt = Clock.System.now(),
                                                expiresAt = null,
                                                credentialId = credential.id,
                                                kind = ApiSessionKind.TRANSACTIONS,
                                            )
                                        refresh()
                                        backgroundTasks.startTask(
                                            key = monzoTransactionsDownloadTaskKey(credential.id),
                                            title = "Download Transactions",
                                            initialDetail = "Starting transactions download for session #$newSessionId.",
                                        ) {
                                            val result =
                                                downloadApiSessionTransactions(
                                                    token = credential.token,
                                                    apiClient =
                                                        createApiClient(
                                                            trafficRecorder =
                                                                ApiSessionTrafficRecorder(
                                                                    sessionId = newSessionId,
                                                                    apiSessionRepository = apiSessionRepository,
                                                                ),
                                                            engine = null,
                                                        ),
                                                    apiSessionRepository = apiSessionRepository,
                                                    sessionId = newSessionId,
                                                    strategy = strategy,
                                                    accountsSessionId = accountsSession?.id,
                                                    sca = scaParamsFor(strategy, credential),
                                                    onProgress = { progress ->
                                                        downloadProgressByCredential =
                                                            downloadProgressByCredential + (credential.id to progress)
                                                        update(progress.downloadDetail())
                                                    },
                                                )
                                            downloadResultByCredential = downloadResultByCredential + (credential.id to result)
                                            downloadProgressByCredential = downloadProgressByCredential - credential.id
                                            result.displaySummary()
                                        }
                                    }
                                },
                                onImport = { session ->
                                    importResultBySession = importResultBySession - session.id
                                    importErrorBySession = importErrorBySession - session.id
                                    // Transactions and People imports both correlate against the
                                    // accounts session (to attach transactions / link owners to accounts).
                                    val accountsSession =
                                        if (session.kind == ApiSessionKind.TRANSACTIONS || session.kind == ApiSessionKind.PEOPLE) {
                                            credentialSessions.firstOrNull { it.kind == ApiSessionKind.ACCOUNTS }
                                        } else {
                                            null
                                        }
                                    scope.launch {
                                        val strategy =
                                            resolveStrategy(credential) ?: run {
                                                importErrorBySession =
                                                    importErrorBySession + (session.id to "No import strategy configured")
                                                return@launch
                                            }
                                        val suggestions =
                                            if (session.kind == ApiSessionKind.TRANSACTIONS) {
                                                discoverApiCounterpartiesToCreate(
                                                    apiSessionRepository = apiSessionRepository,
                                                    accountRepository = accountRepository,
                                                    accountAttributeRepository = accountAttributeRepository,
                                                    sessionId = session.id,
                                                    strategy = strategy,
                                                )
                                            } else {
                                                emptyList()
                                            }
                                        if (suggestions.isEmpty()) {
                                            startImport(session, accountsSession, strategy, emptyMap())
                                        } else {
                                            pendingImport =
                                                PendingApiImport(
                                                    session = session,
                                                    accountsSession = accountsSession,
                                                    strategy = strategy,
                                                    counterparties = suggestions,
                                                )
                                        }
                                    }
                                },
                                onSessionClick = onSessionClick,
                                onCopyError = { error -> scope.launch { clipboard.setPlainText(error) } },
                            )
                        }
                    }
                    VerticalScrollbarForLazyList(
                        lazyListState = lazyListState,
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    )
                }
            }
        }
    }

    pendingImport?.let { import ->
        CounterpartyConfirmationDialog(
            counterparties = import.counterparties,
            onDismiss = { pendingImport = null },
            onConfirm = { namesByCounterpartyId ->
                pendingImport = null
                startImport(
                    session = import.session,
                    accountsSession = import.accountsSession,
                    strategy = import.strategy,
                    counterpartyAccountNames = namesByCounterpartyId,
                )
            },
        )
    }
}

private data class PendingApiImport(
    val session: ApiSession,
    val accountsSession: ApiSession?,
    val strategy: ApiImportStrategy,
    val counterparties: List<ApiCounterpartySuggestion>,
)

@Composable
private fun CounterpartyConfirmationDialog(
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

@Composable
private fun SigningKeySection(
    publicKey: String?,
    onGenerateSigningKey: () -> Unit,
    onCopyText: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = "Request signing (SCA)", style = MaterialTheme.typography.labelLarge)
        if (publicKey == null) {
            Text(
                text =
                    "This provider protects statements with Strong Customer Authentication. Generate a " +
                        "signing key, then register its public key in your provider account.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onGenerateSigningKey) { Text("Generate signing key") }
        } else {
            Text(
                text =
                    "Public key generated. Register it in your provider account " +
                        "(e.g. Wise → Settings → API tokens → Manage public keys).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = publicKey,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onCopyText(publicKey) }) { Text("Copy public key") }
                TextButton(onClick = onGenerateSigningKey) { Text("Regenerate") }
            }
        }
    }
}

@Composable
private fun CredentialCard(
    credential: MonzoCredential,
    providerLabel: String?,
    requiresSigning: Boolean,
    transactionsBlockReason: String?,
    peopleDownloadSupported: Boolean,
    isDownloadingPeople: Boolean,
    onGenerateSigningKey: () -> Unit,
    onCopyText: (String) -> Unit,
    onDownloadPeople: () -> Unit,
    sessions: List<ApiSession>,
    isDownloadingAccounts: Boolean,
    isDownloadingTransactions: Boolean,
    accountsDownloadResult: ApiAccountsDownloadResult?,
    downloadResult: ApiTransactionsDownloadResult?,
    downloadProgress: ApiTransactionsDownloadProgress?,
    importResultBySession: Map<ApiSessionId, ApiSessionImportResult>,
    importErrorBySession: Map<ApiSessionId, String>,
    importProgressBySession: Map<ApiSessionId, ApiSessionImportProgress>,
    importedSessionRevisions: Set<ApiSessionImportRevision>,
    selectedStrategyRevision: Long?,
    isImportingSession: (ApiSessionId) -> Boolean,
    onDownloadAccounts: () -> Unit,
    onDownloadTransactions: () -> Unit,
    onImport: (ApiSession) -> Unit,
    onSessionClick: (ApiSession) -> Unit,
    onCopyError: (String) -> Unit,
) {
    val displayToken =
        if (credential.token.length > 16) {
            "${credential.token.take(8)}...${credential.token.takeLast(8)}"
        } else {
            credential.token
        }
    val isCredentialBusy = isDownloadingAccounts || isDownloadingTransactions || isDownloadingPeople

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text =
                    "Credential · " +
                        (
                            providerLabel
                                ?: credential.type.name
                                    .lowercase()
                                    .replaceFirstChar { it.uppercase() }
                        ),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = displayToken,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Added: ${credential.createdAt.displayDateTime()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (requiresSigning) {
                SigningKeySection(
                    publicKey = credential.publicKey,
                    onGenerateSigningKey = onGenerateSigningKey,
                    onCopyText = onCopyText,
                )
            }

            accountsDownloadResult?.let {
                Text(text = it.displaySummary(), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }
            downloadResult?.let {
                Text(text = it.displaySummary(), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }

            if (isDownloadingAccounts) {
                Text(
                    text = "Downloading accounts...",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (isDownloadingTransactions) {
                Text(
                    text =
                        if (downloadProgress == null) {
                            "Preparing download..."
                        } else {
                            "Downloading account ${downloadProgress.accountIndex}/${downloadProgress.accountCount}, " +
                                "page ${downloadProgress.page}. ${downloadProgress.downloadedResponsePageCount} response(s) so far."
                        },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onDownloadAccounts, enabled = !isCredentialBusy, modifier = Modifier.weight(1f)) {
                    if (isDownloadingAccounts) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Download Accounts")
                    }
                }
                Button(
                    onClick = onDownloadTransactions,
                    enabled = !isCredentialBusy && transactionsBlockReason == null,
                    modifier = Modifier.weight(1f),
                ) {
                    if (isDownloadingTransactions) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Download Transactions")
                    }
                }
            }
            transactionsBlockReason?.let { reason ->
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (peopleDownloadSupported) {
                OutlinedButton(
                    onClick = onDownloadPeople,
                    enabled = !isCredentialBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (isDownloadingPeople) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Download People")
                    }
                }
            }

            if (sessions.isNotEmpty()) {
                HorizontalDivider()
                sessions.forEach { session ->
                    SessionRow(
                        session = session,
                        isImporting = isImportingSession(session.id),
                        isAlreadyImported =
                            selectedStrategyRevision?.let { revision ->
                                ApiSessionImportRevision(session.id, revision) in importedSessionRevisions
                            } == true,
                        importResult = importResultBySession[session.id],
                        importError = importErrorBySession[session.id],
                        importProgress = importProgressBySession[session.id],
                        onImport = { onImport(session) },
                        onOpenTraffic = { onSessionClick(session) },
                        onCopyError = onCopyError,
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionRow(
    session: ApiSession,
    isImporting: Boolean,
    isAlreadyImported: Boolean,
    importResult: ApiSessionImportResult?,
    importError: String?,
    importProgress: ApiSessionImportProgress?,
    onImport: () -> Unit,
    onOpenTraffic: () -> Unit,
    onCopyError: (String) -> Unit,
) {
    val isActive = session.expiresAt?.let { it > Clock.System.now() } ?: true

    val containerColor =
        if (isActive) {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(containerColor, MaterialTheme.shapes.small)
                .clickable(onClick = onOpenTraffic)
                .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val kindLabel =
                when (session.kind) {
                    ApiSessionKind.ACCOUNTS -> "Accounts session"
                    ApiSessionKind.TRANSACTIONS -> "Transactions session"
                    ApiSessionKind.PEOPLE -> "People session"
                    null -> "Session"
                }
            Text(text = kindLabel, style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (isAlreadyImported) {
                    ImportedBadge()
                }
                SessionStatusBadge(isActive = isActive)
            }
        }

        Text(
            text = "Created: ${session.createdAt.displayDateTime()}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        session.importDurationMillis?.let { durationMillis ->
            Text(
                text = "Import took: ${formatElapsedTime(durationMillis.milliseconds)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        importResult?.let {
            Text(text = it.displaySummary(), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        }

        if (isImporting) {
            val importLabel =
                when (session.kind) {
                    ApiSessionKind.ACCOUNTS -> "Importing accounts..."
                    ApiSessionKind.TRANSACTIONS -> "Importing transactions..."
                    ApiSessionKind.PEOPLE -> "Importing people..."
                    null -> "Importing..."
                }
            Text(text = importLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            importProgress?.let {
                val percent = it.progress?.let { p -> " (${(p * 100).toInt().coerceIn(0, 100)}%)" }.orEmpty()
                Text(
                    text = it.detail + percent,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        importError?.let { error ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onCopyError(error) }) { Text("Copy") }
            }
        }

        if (isActive) {
            val importButtonLabel =
                when (session.kind) {
                    ApiSessionKind.ACCOUNTS -> "Import Accounts"
                    ApiSessionKind.TRANSACTIONS -> "Import Transactions"
                    ApiSessionKind.PEOPLE -> "Import People"
                    null -> "Import Transactions"
                }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onImport,
                    enabled = !isImporting && !isAlreadyImported,
                    modifier = Modifier.weight(1f),
                ) {
                    if (isImporting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text(importButtonLabel)
                    }
                }
                OutlinedButton(onClick = onOpenTraffic) { Text("Traffic") }
            }
        } else {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenTraffic) { Text("Traffic") }
            }
        }
    }
}

@Composable
private fun ImportedBadge() {
    Box(
        modifier =
            Modifier
                .background(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = MaterialTheme.shapes.small,
                ).padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = "Imported",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

@Composable
private fun SessionStatusBadge(isActive: Boolean) {
    val containerColor =
        if (isActive) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
    val contentColor =
        if (isActive) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

    Box(
        modifier =
            Modifier
                .background(
                    color = containerColor,
                    shape = MaterialTheme.shapes.small,
                ).padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = if (isActive) "Active" else "Inactive",
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
        )
    }
}

@Composable
fun ApiSessionTrafficScreen(
    apiSessionRepository: ApiSessionRepository,
    sessionId: ApiSessionId,
    highlightRequestId: ApiRequestId? = null,
    highlightJsonPath: String? = null,
    onBack: () -> Unit,
) {
    var session by remember { mutableStateOf<ApiSession?>(null) }
    var requests by remember { mutableStateOf<List<ApiRequest>>(emptyList()) }
    var responses by remember { mutableStateOf<List<ApiResponse>>(emptyList()) }
    var responseTransactionsByResponseId by remember { mutableStateOf<Map<ApiResponseId, List<ApiResponseTransaction>>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(true) }
    val pairs = remember(requests, responses) { pairRequestsAndResponses(requests, responses) }
    val pairsList = remember(pairs) { pairs.values.toList() }

    LaunchedEffect(sessionId) {
        try {
            session = apiSessionRepository.getSessionById(sessionId)
            requests = apiSessionRepository.getRequestsBySession(sessionId)
            responses = apiSessionRepository.getResponsesBySession(sessionId)
            responseTransactionsByResponseId =
                apiSessionRepository
                    .getResponseTransactionsBySession(sessionId)
                    .groupBy { responseTransaction -> responseTransaction.responseId }
        } finally {
            isLoading = false
        }
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "API Traffic",
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    text =
                        session?.let { "${it.type.name.lowercase().replaceFirstChar { char -> char.uppercase() }} session #${it.id}" }
                            ?: "Session #$sessionId",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onBack) {
                Text("Back")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        when {
            isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            pairs.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No requests or responses recorded yet.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> {
                val lazyListState = rememberLazyListState()
                var listRootY by remember { mutableFloatStateOf(0f) }
                // Absolute Y (in root coordinates) of the highlighted JSON node, reported
                // by JsonTreeNode once it is laid out, so we can center it in the viewport.
                var highlightNodeRootY by remember(highlightJsonPath) { mutableFloatStateOf(-1f) }

                // Find and scroll to the pair that contains the highlighted response
                val highlightedPairIndex =
                    remember(highlightRequestId, highlightJsonPath, pairs, responseTransactionsByResponseId) {
                        if (highlightRequestId == null && highlightJsonPath == null) {
                            -1
                        } else {
                            // +1 accounts for the summary item at index 0
                            // First try to match by jsonPath in response transactions (for transaction sources).
                            // Use prefix matching so sub-paths (e.g. $.transactions[0].counterparty) still
                            // resolve to the response transaction at $.transactions[0].
                            val jsonPathMatchIndex =
                                if (highlightJsonPath != null) {
                                    pairsList.indexOfFirst { pair ->
                                        pair.response != null &&
                                            (highlightRequestId == null || pair.request?.id == highlightRequestId) &&
                                            responseTransactionsByResponseId[pair.response.id]
                                                ?.any { highlightJsonPath.startsWithJsonPath(it.jsonPath.value) } == true
                                    }
                                } else {
                                    -1
                                }
                            // Fall back to matching by requestId alone (for entity/account sources)
                            val highlightedIndex =
                                if (jsonPathMatchIndex >= 0) {
                                    jsonPathMatchIndex
                                } else if (highlightRequestId != null) {
                                    pairs[highlightRequestId]?.let { pairsList.indexOf(it) } ?: -1
                                } else {
                                    -1
                                }
                            if (highlightedIndex >= 0) highlightedIndex + 1 else -1
                        }
                    }

                LaunchedEffect(highlightedPairIndex, highlightJsonPath) {
                    if (highlightedPairIndex >= 0) {
                        lazyListState.animateScrollToItem(highlightedPairIndex)
                        if (highlightJsonPath != null) {
                            // Wait for the highlighted node's position to be reported, then
                            // do a second scroll to center it vertically in the viewport.
                            val nodeY =
                                snapshotFlow { highlightNodeRootY }
                                    .first { it >= 0f }
                            val viewportHeight = lazyListState.layoutInfo.viewportSize.height
                            val listStartY = lazyListState.layoutInfo.viewportStartOffset.toFloat()
                            val nodeOffsetInViewport = nodeY - listRootY - listStartY
                            val delta = nodeOffsetInViewport - viewportHeight / 2f
                            lazyListState.scroll { scrollBy(delta) }
                        }
                    }
                }

                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .onGloballyPositioned { coords -> listRootY = coords.positionInRoot().y },
                ) {
                    LazyColumn(
                        state = lazyListState,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item {
                            Text(
                                text = "${requests.size} request(s), ${responses.size} response(s)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        items(pairsList) { pair ->
                            val responseTransactions =
                                pair.response?.let { responseTransactionsByResponseId[it.id] }
                                    ?: emptyList()
                            val isHighlighted =
                                shouldHighlightPair(
                                    pair = pair,
                                    responseTransactions = responseTransactions,
                                    highlightRequestId = highlightRequestId,
                                    highlightJsonPath = highlightJsonPath,
                                )
                            ApiTrafficPairCard(
                                pair = pair,
                                responseTransactions = responseTransactions,
                                isHighlighted = isHighlighted,
                                highlightJsonPath = if (isHighlighted) highlightJsonPath else null,
                                onHighlightPositioned =
                                    if (isHighlighted && highlightJsonPath != null) {
                                        { y -> highlightNodeRootY = y }
                                    } else {
                                        null
                                    },
                            )
                        }
                    }
                    VerticalScrollbarForLazyList(
                        lazyListState = lazyListState,
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ApiTrafficPairCard(
    pair: ApiTrafficPair,
    responseTransactions: List<ApiResponseTransaction> = emptyList(),
    isHighlighted: Boolean = false,
    highlightJsonPath: String? = null,
    onHighlightPositioned: ((Float) -> Unit)? = null,
) {
    val cardContainerColor =
        if (isHighlighted) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        } else {
            MaterialTheme.colorScheme.surface
        }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cardContainerColor),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            pair.request?.let { request ->
                RequestTrafficItem(
                    title = "Request #${request.id}",
                    timestamp = request.requestedAt.displayDateTime(),
                    body = request.displayBody(),
                )
            } ?: MissingTrafficItem(label = "Request")

            HorizontalDivider()

            pair.response?.let { response ->
                ResponseTrafficItem(
                    title = "Response #${response.id}",
                    timestamp = response.respondedAt.displayDateTime(),
                    json = response.json,
                    responseTransactions = responseTransactions,
                    highlightJsonPath = highlightJsonPath,
                    onHighlightPositioned = onHighlightPositioned,
                )
            } ?: MissingTrafficItem(label = "Response")
        }
    }
}

@Composable
private fun MissingTrafficItem(label: String) {
    Text(
        text = "$label not recorded.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun RequestTrafficItem(
    title: String,
    timestamp: String,
    body: String,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                    shape = MaterialTheme.shapes.small,
                ).padding(8.dp),
    ) {
        TrafficItemHeader(
            title = title,
            timestamp = timestamp,
            onCopy = { scope.launch { clipboard.setPlainText(body) } },
        )
        SelectionContainer {
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ResponseTrafficItem(
    title: String,
    timestamp: String,
    json: String,
    responseTransactions: List<ApiResponseTransaction> = emptyList(),
    highlightJsonPath: String? = null,
    onHighlightPositioned: ((Float) -> Unit)? = null,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val prettyJson =
        remember(json) {
            runCatching {
                val element = Json.parseToJsonElement(json)
                Json { prettyPrint = true }.encodeToString(element)
            }.getOrDefault(json)
        }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                    shape = MaterialTheme.shapes.small,
                ).padding(8.dp),
    ) {
        TrafficItemHeader(
            title = title,
            timestamp = timestamp,
            onCopy = { scope.launch { clipboard.setPlainText(prettyJson) } },
        )
        JsonViewer(
            json = json,
            responseTransactions = responseTransactions,
            highlightJsonPath = highlightJsonPath,
            onHighlightPositioned = onHighlightPositioned,
        )
    }
}

@Composable
private fun TrafficItemHeader(
    title: String,
    timestamp: String,
    onCopy: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
            )
            if (onCopy != null) {
                Icon(
                    imageVector = ContentCopyIcon,
                    contentDescription = "Copy",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp).clickable(onClick = onCopy),
                )
            }
        }
        Text(
            text = timestamp,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun JsonViewer(
    json: String,
    responseTransactions: List<ApiResponseTransaction> = emptyList(),
    highlightJsonPath: String? = null,
    onHighlightPositioned: ((Float) -> Unit)? = null,
) {
    val jsonElement =
        remember(json) {
            runCatching { Json.parseToJsonElement(json) }.getOrNull()
        }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val prettyPrinter = remember { Json { prettyPrint = true } }
    // Parse the highlight path into segments (e.g. "$.transactions[0]" → ["transactions", "0"])
    val highlightSegments =
        remember(highlightJsonPath) {
            parseJsonPathSegments(highlightJsonPath)
        }
    val latestTransactionByJsonPath =
        remember(responseTransactions) {
            responseTransactions
                .groupBy { it.jsonPath.value }
                .mapValues { (_, transactions) ->
                    transactions.maxBy { it.id.id }
                }
        }

    if (jsonElement == null) {
        SelectionContainer {
            Text(
                text = json,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
            )
        }
    } else {
        Column {
            JsonTreeNode(
                label = null,
                element = jsonElement,
                jsonPath = "$",
                depth = 0,
                latestTransactionByJsonPath = latestTransactionByJsonPath,
                remainingHighlightSegments = highlightSegments,
                onHighlightPositioned = onHighlightPositioned,
                clipboard = clipboard,
                copyScope = scope,
                prettyPrinter = prettyPrinter,
            )
        }
    }
}

/**
 * Returns true if this path equals [prefix] or is a child of it
 * (e.g. "$.transactions[0].counterparty".startsWithJsonPath("$.transactions[0]") == true).
 */
internal fun String.startsWithJsonPath(prefix: String): Boolean =
    this == prefix || this.startsWith("$prefix.") || this.startsWith("$prefix[")

/**
 * Converts a simple JSONPath (e.g. "$.transactions[2]") into a list of
 * string segments (["transactions", "2"]) used to trace the highlight path
 * through the JSON tree.  Returns null when no highlight is needed.
 */
internal fun parseJsonPathSegments(jsonPath: String?): List<String>? {
    if (jsonPath == null) return null
    // Strip leading "$." prefix, then split on "." and "[…]"
    val stripped = jsonPath.removePrefix("$.").removePrefix("$")
    if (stripped.isEmpty()) return null

    val segments = mutableListOf<String>()
    // Split on "." first, then strip "[index]" from each part
    for (part in stripped.split(".")) {
        val bracketStart = part.indexOf('[')
        if (bracketStart < 0) {
            segments.add(part)
        } else {
            if (bracketStart > 0) segments.add(part.substring(0, bracketStart))
            val bracketEnd = part.indexOf(']', bracketStart)
            if (bracketEnd > bracketStart) {
                segments.add(part.substring(bracketStart + 1, bracketEnd))
            }
        }
    }
    return segments.ifEmpty { null }
}

internal fun isHighlightTarget(remainingHighlightSegments: List<String>?): Boolean =
    remainingHighlightSegments != null && remainingHighlightSegments.isEmpty()

internal fun shouldHighlightPair(
    pair: ApiTrafficPair,
    responseTransactions: List<ApiResponseTransaction>,
    highlightRequestId: ApiRequestId?,
    highlightJsonPath: String?,
): Boolean {
    val requestMatches =
        highlightRequestId == null ||
            pair.request?.id == highlightRequestId ||
            pair.response?.requestId == highlightRequestId
    val jsonPathMatches =
        highlightJsonPath != null &&
            responseTransactions.any {
                highlightJsonPath.startsWithJsonPath(it.jsonPath.value)
            }
    return (highlightRequestId != null || highlightJsonPath != null) &&
        requestMatches &&
        (
            jsonPathMatches ||
                (highlightRequestId != null && highlightJsonPath == null)
        )
}

@Composable
private fun JsonTreeNode(
    label: String?,
    element: JsonElement,
    jsonPath: String,
    depth: Int,
    clipboard: Clipboard,
    copyScope: CoroutineScope,
    prettyPrinter: Json,
    latestTransactionByJsonPath: Map<String, ApiResponseTransaction> = emptyMap(),
    remainingHighlightSegments: List<String>? = null,
    forceExpandSubtree: Boolean = false,
    highlightSubtree: Boolean = false,
    onHighlightPositioned: ((Float) -> Unit)? = null,
) {
    val childCount = element.childCount()
    val expandable = childCount > 0
    // This node is the target only when a highlight path exists and all segments are consumed.
    val isHighlightTarget = isHighlightTarget(remainingHighlightSegments)
    // Force-expand the node if it's on the highlight path
    val forceExpandPath = !remainingHighlightSegments.isNullOrEmpty()
    val shouldForceExpand = forceExpandSubtree || forceExpandPath || isHighlightTarget
    var expanded by remember(label, element, remainingHighlightSegments, forceExpandSubtree) {
        mutableStateOf(depth == 0 || shouldForceExpand)
    }

    val prefix =
        if (expandable) {
            if (expanded) "- " else "+ "
        } else {
            "  "
        }
    val line =
        buildString {
            append(prefix)
            if (label != null) {
                append(label)
                append(": ")
            }
            append(element.summary(expanded))
        }

    val nodeTransaction = latestTransactionByJsonPath[jsonPath]
    val highlightBackground =
        when {
            isHighlightTarget -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.85f)
            highlightSubtree -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f)
            nodeTransaction != null -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            else -> Color.Transparent
        }
    val highlightTextColor =
        if (isHighlightTarget) {
            MaterialTheme.colorScheme.onTertiaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        }
    val lineModifier =
        Modifier
            .fillMaxWidth()
            .background(highlightBackground, MaterialTheme.shapes.extraSmall)
            .padding(start = (depth * 12).dp)
            .then(
                if (isHighlightTarget && onHighlightPositioned != null) {
                    Modifier.onGloballyPositioned { coords ->
                        onHighlightPositioned(coords.positionInRoot().y)
                    }
                } else {
                    Modifier
                },
            ).then(
                if (expandable) {
                    Modifier.clickable { expanded = !expanded }
                } else {
                    Modifier
                },
            )

    Row(
        modifier = lineModifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = line,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = highlightTextColor,
            modifier = Modifier.weight(1f),
        )
        nodeTransaction?.let { transaction ->
            JsonTransactionStatus(transaction = transaction)
        }
        Icon(
            imageVector = ContentCopyIcon,
            contentDescription = "Copy",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier
                    .size(14.dp)
                    .clickable { copyScope.launch { clipboard.setPlainText(prettyPrinter.encodeToString<JsonElement>(element)) } },
        )
    }
    if (expanded) {
        when (element) {
            is JsonObject -> {
                element.entries.forEach { (key, value) ->
                    val nextSegments =
                        when {
                            remainingHighlightSegments.isNullOrEmpty() -> null
                            remainingHighlightSegments.first() == key -> remainingHighlightSegments.drop(1)
                            else -> null
                        }
                    JsonTreeNode(
                        label = "\"$key\"",
                        element = value,
                        jsonPath = jsonObjectChildPath(jsonPath, key),
                        depth = depth + 1,
                        clipboard = clipboard,
                        copyScope = copyScope,
                        prettyPrinter = prettyPrinter,
                        latestTransactionByJsonPath = latestTransactionByJsonPath,
                        remainingHighlightSegments = nextSegments,
                        forceExpandSubtree = forceExpandSubtree || isHighlightTarget,
                        highlightSubtree = highlightSubtree || isHighlightTarget,
                        onHighlightPositioned = onHighlightPositioned,
                    )
                }
            }
            is JsonArray -> {
                element.forEachIndexed { index, value ->
                    val nextSegments =
                        when {
                            remainingHighlightSegments.isNullOrEmpty() -> null
                            remainingHighlightSegments.first() == index.toString() -> remainingHighlightSegments.drop(1)
                            else -> null
                        }
                    JsonTreeNode(
                        label = "[$index]",
                        element = value,
                        jsonPath = "$jsonPath[$index]",
                        depth = depth + 1,
                        clipboard = clipboard,
                        copyScope = copyScope,
                        prettyPrinter = prettyPrinter,
                        latestTransactionByJsonPath = latestTransactionByJsonPath,
                        remainingHighlightSegments = nextSegments,
                        forceExpandSubtree = forceExpandSubtree || isHighlightTarget,
                        highlightSubtree = highlightSubtree || isHighlightTarget,
                        onHighlightPositioned = onHighlightPositioned,
                    )
                }
            }
            else -> Unit
        }
    }
}

@Composable
private fun JsonTransactionStatus(transaction: ApiResponseTransaction) {
    val stateColor =
        when (transaction.state) {
            ApiResponseTransactionState.IMPORTED -> MaterialTheme.colorScheme.primary
            ApiResponseTransactionState.DUPLICATE -> MaterialTheme.colorScheme.tertiary
            ApiResponseTransactionState.ERROR -> MaterialTheme.colorScheme.error
        }
    val label =
        when (transaction.state) {
            ApiResponseTransactionState.IMPORTED -> "Imported"
            ApiResponseTransactionState.DUPLICATE -> "Duplicate"
            ApiResponseTransactionState.ERROR -> "Error"
        }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = stateColor,
        maxLines = 1,
        modifier =
            Modifier
                .background(stateColor.copy(alpha = 0.12f), MaterialTheme.shapes.extraSmall)
                .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private fun jsonObjectChildPath(
    parentPath: String,
    key: String,
): String =
    if (parentPath == "$") {
        "$.$key"
    } else {
        "$parentPath.$key"
    }

internal data class ApiTrafficPair(
    val request: ApiRequest?,
    val response: ApiResponse?,
)

private fun pairRequestsAndResponses(
    requests: List<ApiRequest>,
    responses: List<ApiResponse>,
): Map<ApiRequestId, ApiTrafficPair> {
    val responsesByRequestId = responses.associateBy { it.requestId }
    val requestPairs =
        requests.map { request ->
            request.id to
                ApiTrafficPair(
                    request = request,
                    response = responsesByRequestId[request.id],
                )
        }
    val orphanPairs =
        responses
            .filter { response -> requests.none { request -> request.id == response.requestId } }
            .map { response -> response.requestId to ApiTrafficPair(request = null, response = response) }

    return (requestPairs + orphanPairs)
        .sortedByDescending { (_, pair) -> pair.request?.requestedAt ?: pair.response?.respondedAt ?: Instant.DISTANT_PAST }
        .toMap()
}

private fun ApiRequest.displayBody(): String =
    buildString {
        appendLine("$method $url")
        if (headers.isNotEmpty()) {
            appendLine()
            appendLine("Headers:")
            headers.forEach { header ->
                appendLine("${header.key}: ${header.value}")
            }
        }
    }.trimEnd()

private fun ApiAccountsDownloadResult.displaySummary(): String =
    if (skipped) {
        "Accounts already downloaded: $accountCount account(s) (skipped)."
    } else {
        "Accounts downloaded: $accountCount account(s)."
    }

private fun ApiTransactionsDownloadResult.displaySummary(): String =
    "Transactions downloaded: $accountCount account(s), $transactionResponseCount new response page(s)."

private fun ApiTransactionsDownloadProgress.downloadDetail(): String =
    "Downloading account $accountIndex/$accountCount, page $page. $downloadedResponsePageCount response page(s) downloaded so far."

private fun ApiSessionImportResult.displaySummary(): String =
    buildString {
        append("Import complete: ")
        append(accountCount)
        append(" account(s), ")
        append(transactionCount)
        append(" imported transaction(s)")
        if (personCount > 0) {
            append(", ")
            append(personCount)
            append(" person(s) created")
        }
        if (duplicateCount > 0) {
            append(", ")
            append(duplicateCount)
            append(" duplicate(s)")
        }
        if (errorCount > 0) {
            append(", ")
            append(errorCount)
            append(" error(s)")
        }
        append(".")
    }

private fun monzoAccountsDownloadTaskKey(credentialId: MonzoCredentialId): String = "monzo-accounts-download-cred-${credentialId.id}"

private fun monzoTransactionsDownloadTaskKey(credentialId: MonzoCredentialId): String =
    "monzo-transactions-download-cred-${credentialId.id}"

private fun monzoPeopleDownloadTaskKey(credentialId: MonzoCredentialId): String = "monzo-people-download-cred-${credentialId.id}"

private fun monzoImportTaskKey(sessionId: ApiSessionId): String = "monzo-import-${sessionId.id}"

private fun JsonElement.childCount(): Int =
    when (this) {
        is JsonObject -> size
        is JsonArray -> size
        else -> 0
    }

private fun JsonElement.summary(expanded: Boolean): String =
    when (this) {
        is JsonObject -> if (expanded) "{" else "{...} $size item(s)"
        is JsonArray -> if (expanded) "[" else "[...] $size item(s)"
        JsonNull -> "null"
        is JsonPrimitive -> toString()
    }
