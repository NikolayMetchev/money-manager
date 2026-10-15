package com.moneymanager.apiimport

import co.touchlab.kermit.Logger
import com.moneymanager.apiimporter.API_ENGINE_BATCH_SIZE
import com.moneymanager.apiimporter.ApiCounterpartySuggestion
import com.moneymanager.apiimporter.ApiDownloadCredentials
import com.moneymanager.apiimporter.ApiDownloadNotPossibleException
import com.moneymanager.apiimporter.ApiImportReads
import com.moneymanager.apiimporter.ApiSessionDownloadResult
import com.moneymanager.apiimporter.ApiSessionImportResult
import com.moneymanager.apiimporter.discoverApiCounterpartiesToCreate
import com.moneymanager.apiimporter.displaySummary
import com.moneymanager.apiimporter.downloadApiSession
import com.moneymanager.apiimporter.importApiSession
import com.moneymanager.credentialvault.CredentialVault
import com.moneymanager.credentialvault.CredentialVaultLockedException
import com.moneymanager.credentialvault.StoredApiCredential
import com.moneymanager.credentialvault.VaultState
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.ApiCredential
import com.moneymanager.domain.model.ApiCredentialId
import com.moneymanager.domain.model.ApiSession
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.repository.AccountAttributeReadRepository
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.ApiImportStrategyReadRepository
import com.moneymanager.domain.repository.ApiSessionReadRepository
import com.moneymanager.domain.repository.CryptoReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.domain.repository.PassThroughAccountReadRepository
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.createApiSession
import com.moneymanager.importengineapi.ensureApiCredentials
import com.moneymanager.importengineapi.markApiSessionImported
import com.moneymanager.rest.ApiSessionTrafficRecorder
import com.moneymanager.rest.ScaParams
import com.moneymanager.rest.createApiClient
import com.moneymanager.rest.signScaChallenge
import com.moneymanager.tasks.Progress
import com.moneymanager.tasks.TaskScope
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.TimeSource

private val logger = Logger.withTag("ApiImportService")

/** How one credential's download ended; [message] is what its task shows. */
sealed interface ApiDownloadOutcome {
    val message: String

    data class Downloaded(
        val sessionId: ApiSessionId,
        val result: ApiSessionDownloadResult,
    ) : ApiDownloadOutcome {
        override val message: String get() = result.displaySummary()
    }

    /** Nothing was downloaded: no strategy, no secrets, or the strategy can't download as configured. */
    data class NotDownloaded(
        override val message: String,
    ) : ApiDownloadOutcome
}

/**
 * A downloaded session "Download & import all" left for a person: importing it would create
 * [counterparties] whose account names nobody has confirmed.
 */
data class ApiSessionNeedsAttention(
    val session: ApiSession,
    val strategy: ApiImportStrategy,
    val counterparties: List<ApiCounterpartySuggestion>,
)

/**
 * How one credential's part of "Download & import all" went: its [download] outcome, or [error] when the
 * download threw (null both: a download of it was already running).
 */
data class ApiCredentialDownload(
    val credential: ApiCredential,
    val strategyName: String?,
    val download: ApiDownloadOutcome?,
    val error: String? = null,
)

enum class ApiSessionImportStatus {
    IMPORTED,

    /** Its counterparties need confirming; see [ApiDownloadAndImportAllResult.needsAttention]. */
    NEEDS_ATTENTION,

    /** Its credential has no strategy any more. */
    SKIPPED_NO_STRATEGY,
    FAILED,
}

/** One queued session's part of "Download & import all": [result] when imported, [error] when it failed. */
data class ApiSessionImport(
    val session: ApiSession,
    val strategyName: String?,
    val status: ApiSessionImportStatus,
    val result: ApiSessionImportResult? = null,
    val error: String? = null,
)

/** What "Download & import all" did, credential by credential and session by session; the counts derive from those. */
data class ApiDownloadAndImportAllResult(
    val credentials: List<ApiCredentialDownload>,
    val sessions: List<ApiSessionImport>,
    val needsAttention: List<ApiSessionNeedsAttention>,
) {
    val credentialCount: Int get() = credentials.size
    val downloadedCount: Int get() = credentials.count { it.download is ApiDownloadOutcome.Downloaded }
    val sessionCount: Int get() = sessions.size
    val importedCount: Int get() = sessions.count { it.status == ApiSessionImportStatus.IMPORTED }

    fun summary(): String =
        bulkDownloadImportSummary(
            credentialCount = credentialCount,
            downloadedCount = downloadedCount,
            sessionCount = sessionCount,
            importedCount = importedCount,
            needsReviewCount = needsAttention.size,
        )
}

/**
 * What a front end hears while a download or import runs, to keep its own view of the sessions current.
 * Every callback defaults to doing nothing, so a front end (the CLI) passes only the ones it needs.
 *
 * @property onSessionCreated a credential's download created a session, before any request is made.
 */
class ApiImportListener(
    val onSessionCreated: (ApiCredentialId, ApiSessionId) -> Unit = { _, _ -> },
    val onDownloaded: (ApiCredentialId, ApiSessionDownloadResult) -> Unit = { _, _ -> },
    val onImported: (ApiSessionId, ApiSessionImportResult) -> Unit = { _, _ -> },
) {
    companion object {
        val None = ApiImportListener()
    }
}

/**
 * Downloads and imports API sessions outside any UI, so the desktop app, Android and the CLI share one
 * implementation. Every run reports through the [TaskScope] it is given; "Download & import all" starts one
 * child task per credential download and per session import. Imports hold [writeLock], the session's
 * write lock that `ImportDirectoryService` shares,
 * since the engine doesn't serialize writes itself.
 *
 * [httpEngine] replaces the platform HTTP engine (tests pass a mock one); [countryCode] is the device
 * country the statement-region gate checks.
 */
class ApiImportService(
    private val apiSessionRepository: ApiSessionReadRepository,
    private val apiImportStrategyRepository: ApiImportStrategyReadRepository,
    private val accountRepository: AccountReadRepository,
    private val accountAttributeRepository: AccountAttributeReadRepository,
    private val currencyRepository: CurrencyReadRepository,
    private val cryptoRepository: CryptoReadRepository,
    private val passThroughAccountRepository: PassThroughAccountReadRepository,
    private val maintenance: Maintenance,
    private val importEngine: ImportEngine,
    private val credentialVault: CredentialVault,
    private val deviceId: DeviceId,
    private val httpEngine: HttpClientEngine? = null,
    private val countryCode: () -> String? = ::currentCountryCode,
    private val writeLock: Mutex = Mutex(),
) {
    /**
     * The strategy [credential] downloads with. Null for an orphaned credential (its strategy was deleted,
     * or it was never linked), which callers report rather than guessing a provider.
     */
    suspend fun resolveStrategy(credential: ApiCredential): ApiImportStrategy? =
        credential.strategyId?.let { apiImportStrategyRepository.getStrategyById(it).first() }

    /** Why [strategy]'s transactions can't be downloaded from this device's country, or null when they can. */
    fun transactionsBlockReason(strategy: ApiImportStrategy?): String? = regionBlockReason(strategy, countryCode())

    /**
     * Gives every installed strategy with secrets in the vault a connection row (see
     * [strategiesMissingConnections]). Only when the vault is already unlocked: this runs unprompted, so it
     * never asks for the password. Best effort, because editing may be locked (a cloud-backed database whose
     * remote copy is ahead); the rows stay missing and a later call retries. Returns how many it created.
     */
    suspend fun ensureConnections(): Int {
        val bundle = (credentialVault.state.value as? VaultState.Unlocked)?.bundle ?: return 0
        val missing =
            strategiesMissingConnections(
                strategies = apiImportStrategyRepository.getAllStrategies().first(),
                credentialRows = apiSessionRepository.getAllCredentials(),
                bundle = bundle,
            )
        if (missing.isEmpty()) return 0
        return try {
            importEngine.ensureApiCredentials(missing, Clock.System.now()).size
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (expected: Exception) {
            logger.e(expected) { "Couldn't create API connection rows: ${expected.message}" }
            0
        }
    }

    /**
     * Downloads a new session for [credential] within [task]. The secrets come first (prompting to unlock
     * the vault), so a locked or empty vault doesn't leave an empty session behind. Unless [forceFull], the
     * download only fetches what earlier sessions of the credential didn't reach.
     */
    suspend fun download(
        task: TaskScope,
        credential: ApiCredential,
        forceFull: Boolean,
        listener: ApiImportListener = ApiImportListener.None,
    ): ApiDownloadOutcome {
        val strategy =
            resolveStrategy(credential)
                ?: return ApiDownloadOutcome.NotDownloaded("No import strategy is linked to this credential; reconnect it.")
        val secrets =
            try {
                credentialVault.downloadSecretsFor(strategy)
            } catch (blocked: MissingApiSecretsException) {
                return ApiDownloadOutcome.NotDownloaded(blocked.message.orEmpty())
            }
        val sessionId =
            importEngine.createApiSession(
                deviceId = deviceId,
                createdAt = Clock.System.now(),
                credentialId = credential.id,
            )
        // How far earlier sessions of this credential already reached. Read before any request is recorded
        // into the new session.
        val watermarks = if (forceFull) emptyMap() else apiSessionRepository.getDownloadWatermarks(credential.id, sessionId)
        listener.onSessionCreated(credential.id, sessionId)
        task.report("Starting download for session #$sessionId.")
        // One client/session for accounts, transactions and people.
        val apiClient =
            createApiClient(
                trafficRecorder = ApiSessionTrafficRecorder(sessionId = sessionId, importEngine = importEngine),
                engine = httpEngine,
            )
        val result =
            try {
                downloadApiSession(
                    apiClient = apiClient,
                    apiSessionRepository = apiSessionRepository,
                    sessionId = sessionId,
                    strategy = strategy,
                    importEngine = importEngine,
                    credentials =
                        ApiDownloadCredentials(
                            token = secrets.token,
                            apiSecret = secrets.apiSecret,
                            sca = scaParamsFor(strategy, secrets),
                        ),
                    watermarks = watermarks,
                    forceFullDownload = forceFull,
                    transactionsBlocked = transactionsBlockReason(strategy) != null,
                    onPhase = { task.report(it) },
                    onProgress = { task.report(it) },
                )
            } catch (notPossible: ApiDownloadNotPossibleException) {
                logger.w { "Download for '${strategy.name}' (session #$sessionId) not possible: ${notPossible.message}" }
                return ApiDownloadOutcome.NotDownloaded(notPossible.message)
            }
        listener.onDownloaded(credential.id, result)
        return ApiDownloadOutcome.Downloaded(sessionId, result)
    }

    /**
     * The counterparties importing [sessionId] would create, whose names a person should confirm first.
     * Empty when the strategy has no counterparty field.
     */
    suspend fun counterpartiesToConfirm(
        sessionId: ApiSessionId,
        strategy: ApiImportStrategy,
        onProgress: suspend (Progress) -> Unit = {},
    ): List<ApiCounterpartySuggestion> =
        discoverApiCounterpartiesToCreate(
            apiSessionRepository = apiSessionRepository,
            accountAttributeRepository = accountAttributeRepository,
            sessionId = sessionId,
            strategy = strategy,
            onProgress = onProgress,
        )

    /**
     * Imports downloaded session [sessionId] within [task], creating counterparty accounts under
     * [counterpartyAccountNames], then records it as imported at [strategy]'s revision and refreshes the
     * materialized views (unless [refreshViews] is false: a caller importing many refreshes once).
     */
    suspend fun import(
        task: TaskScope,
        sessionId: ApiSessionId,
        strategy: ApiImportStrategy,
        counterpartyAccountNames: Map<String, String> = emptyMap(),
        refreshViews: Boolean = true,
    ): ApiSessionImportResult =
        writeLock.withLock {
            importLocked(task, sessionId, strategy, counterpartyAccountNames, refreshViews)
        }

    private suspend fun importLocked(
        task: TaskScope,
        sessionId: ApiSessionId,
        strategy: ApiImportStrategy,
        counterpartyAccountNames: Map<String, String>,
        refreshViews: Boolean,
    ): ApiSessionImportResult {
        val startedAt = TimeSource.Monotonic.markNow()
        val outcome =
            importApiSession(
                reads =
                    ApiImportReads(
                        apiSessionRepository = apiSessionRepository,
                        accountRepository = accountRepository,
                        accountAttributeRepository = accountAttributeRepository,
                        currencyRepository = currencyRepository,
                        cryptoRepository = cryptoRepository,
                    ),
                sessionId = sessionId,
                strategy = strategy,
                importEngine = importEngine,
                counterpartyAccountNames = counterpartyAccountNames,
                passThroughAccounts = passThroughAccountRepository.getAll().first(),
                onProgress = { task.report(it) },
                engineBatchSize = API_ENGINE_BATCH_SIZE,
            )
        importEngine.markApiSessionImported(
            id = sessionId,
            revisionId = strategy.revisionId,
            importedAt = Clock.System.now(),
            importDurationMillis = startedAt.elapsedNow().inWholeMilliseconds,
        )
        if (refreshViews) maintenance.refreshMaterializedViews()
        return ApiSessionImportResult(
            accountCount = outcome.accountCount,
            transactionCount = outcome.transactionCount + outcome.tradeCount,
            personCount = outcome.personCount,
            duplicateCount = outcome.duplicateCount,
            errorCount = outcome.errorCount,
        )
    }

    /**
     * Downloads every credential in [targets] at once (different providers don't contend) and imports as the
     * downloads land: each credential's never-imported sessions join one import queue, oldest first, the
     * moment its own download finishes, and the queue is imported one session at a time. A session whose
     * import needs counterparty names confirmed comes back in [ApiDownloadAndImportAllResult.needsAttention]
     * rather than being imported with unconfirmed names; a session imported under an older strategy revision
     * is left for re-import. Credentials in [forceFullIds] ignore what earlier sessions already covered.
     * [importSessions] false only downloads. [refreshViews] false leaves the materialized views to the
     * caller; otherwise each import refreshes them. Every download or import that fails is reported on [task].
     *
     * The vault is unlocked once up front, so the parallel downloads don't each prompt.
     *
     * @throws CredentialVaultLockedException when the vault stays locked.
     */
    @Suppress("LongMethod")
    suspend fun downloadAndImportAll(
        task: TaskScope,
        targets: List<ApiCredential>,
        forceFullIds: Set<ApiCredentialId> = emptySet(),
        listener: ApiImportListener = ApiImportListener.None,
        importSessions: Boolean = true,
        refreshViews: Boolean = true,
    ): ApiDownloadAndImportAllResult {
        credentialVault.requireUnlocked("Download all APIs")
        val strategyByCredential = targets.associate { it.id to resolveStrategy(it) }
        val readyToImport = Channel<ApiSession>(Channel.UNLIMITED)
        var downloadsLeft = targets.size
        val downloads = mutableListOf<ApiCredentialDownload>()
        val sessions = mutableListOf<ApiSessionImport>()
        val needsAttention = mutableListOf<ApiSessionNeedsAttention>()
        // One thread for the coordination only: the downloads and imports run as child tasks, and this keeps
        // the task registrations and counters here from racing each other.
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            launch {
                targets
                    .map { credential ->
                        async {
                            val strategyName = strategyByCredential[credential.id]?.name
                            // Each connection fails on its own: one provider's error mustn't cancel the other
                            // downloads or the import queue.
                            val downloaded =
                                task.awaitChild(
                                    key = apiDownloadTaskKey(credential.id),
                                    title = strategyName?.let { "Download $it" } ?: "Download",
                                    initialDetail = "Fetching credentials.",
                                    finalDetail = { it.message },
                                ) { download(this, credential, credential.id in forceFullIds, listener) }
                            val error = downloaded?.exceptionOrNull()?.let { it.message ?: it::class.simpleName }
                            val outcome = downloaded?.getOrNull()
                            downloads += ApiCredentialDownload(credential, strategyName, outcome, error)
                            when {
                                error != null -> task.reportIssue("${strategyName ?: "Connection"}: download failed: $error")
                                outcome is ApiDownloadOutcome.NotDownloaded ->
                                    task.reportIssue("${strategyName ?: "Connection"}: not downloaded: ${outcome.message}")
                            }
                            downloadsLeft--
                            // Queued even when the download failed: sessions left from earlier downloads still
                            // hold data the next download won't fetch again.
                            if (importSessions) {
                                isolatedBulkStep("Queueing sessions of credential ${credential.id}") {
                                    sessionsToImportInBulk(
                                        sessions = apiSessionRepository.getSessionsByDevice(deviceId),
                                        credentialIds = setOf(credential.id),
                                        everImportedSessionIds =
                                            apiSessionRepository.getImportedSessionRevisions().map { it.sessionId }.toSet(),
                                    ).forEach { readyToImport.send(it) }
                                }
                            }
                        }
                    }.awaitAll()
                readyToImport.close()
            }
            for (session in readyToImport) {
                val strategy = strategyByCredential[session.credentialId]
                if (strategy == null) {
                    sessions += ApiSessionImport(session, null, ApiSessionImportStatus.SKIPPED_NO_STRATEGY)
                    continue
                }
                task.report("Importing session #${session.id}; $downloadsLeft download(s) still running.")
                sessions += importQueued(task, session, strategy, listener, refreshViews, needsAttention)
            }
        }
        return ApiDownloadAndImportAllResult(
            credentials = downloads.sortedBy { download -> targets.indexOfFirst { it.id == download.credential.id } },
            sessions = sessions.toList(),
            needsAttention = needsAttention.toList(),
        )
    }

    // One queued session of "Download & import all": left for a person when its counterparties need
    // confirming, else imported as its own child task. A failure is reported and the queue moves on.
    private suspend fun importQueued(
        task: TaskScope,
        session: ApiSession,
        strategy: ApiImportStrategy,
        listener: ApiImportListener,
        refreshViews: Boolean,
        needsAttention: MutableList<ApiSessionNeedsAttention>,
    ): ApiSessionImport {
        val counterparties =
            try {
                withContext(Dispatchers.Default) { counterpartiesToConfirm(session.id, strategy) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (expected: Exception) {
                logger.e(expected) { "Checking counterparties of session ${session.id} failed: ${expected.message}" }
                return failedImport(task, session, strategy, expected)
            }
        if (counterparties.isNotEmpty()) {
            needsAttention += ApiSessionNeedsAttention(session, strategy, counterparties)
            return ApiSessionImport(session, strategy.name, ApiSessionImportStatus.NEEDS_ATTENTION)
        }
        val imported =
            task.awaitChild(
                key = apiImportTaskKey(session.id),
                title = "Import ${strategy.name}",
                initialDetail = "Starting import for session #${session.id}.",
                finalDetail = { it.displaySummary() },
            ) { import(this, session.id, strategy, refreshViews = refreshViews) }
        val result = imported?.getOrNull()
        return when {
            result != null -> {
                listener.onImported(session.id, result)
                ApiSessionImport(session, strategy.name, ApiSessionImportStatus.IMPORTED, result)
            }
            else -> failedImport(task, session, strategy, imported?.exceptionOrNull())
        }
    }

    private fun failedImport(
        task: TaskScope,
        session: ApiSession,
        strategy: ApiImportStrategy,
        cause: Throwable?,
    ): ApiSessionImport {
        // No cause: an import of this session was already running elsewhere.
        val error = cause?.let { it.message ?: it::class.simpleName } ?: "an import of it was already running"
        task.reportIssue("${strategy.name} session #${session.id}: import failed: $error")
        return ApiSessionImport(session, strategy.name, ApiSessionImportStatus.FAILED, error = error)
    }

    // Builds the SCA request-signing params when the strategy is SCA-protected and the credential has a
    // signing key (e.g. Wise statements). Null disables signing (e.g. Monzo).
    private fun scaParamsFor(
        strategy: ApiImportStrategy,
        secrets: StoredApiCredential,
    ): ScaParams? {
        val signing = strategy.config.signing ?: return null
        val privateKey = secrets.privateKeyPem ?: return null
        return ScaParams(
            challengeHeader = signing.challengeHeader,
            signatureHeader = signing.signatureHeader,
            triggerStatus = signing.triggerStatus,
            sign = { oneTimeToken -> signScaChallenge(privateKey, oneTimeToken) },
        )
    }
}

/**
 * Runs [block] as a child task of this one, finishing it with [finalDetail], and returns its result: a
 * failure when it threw (the child is marked failed), or null when a task with [key] is already running —
 * a credential's own Download, say. Unlike [TaskScope.child], a running [key] is skipped rather than run twice.
 */
private suspend fun <T : Any> TaskScope.awaitChild(
    key: String,
    title: String,
    initialDetail: String,
    finalDetail: (T) -> String,
    block: suspend TaskScope.() -> T,
): Result<T>? {
    val finished = CompletableDeferred<Result<T>?>()
    val started =
        startChild(key, title, initialDetail) {
            try {
                val result = block()
                finished.complete(Result.success(result))
                finalDetail(result)
            } catch (cancelled: CancellationException) {
                finished.complete(Result.failure(cancelled))
                throw cancelled
            } catch (expected: Exception) {
                finished.complete(Result.failure(expected))
                throw expected
            }
        }
    if (started == null) finished.complete(null)
    return finished.await()
}
