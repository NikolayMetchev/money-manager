package com.moneymanager.apiimporter

import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.apistrategy.ApiAccountsSource
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.model.passthrough.PassThroughAccount
import com.moneymanager.domain.repository.AccountAttributeReadRepository
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.ApiSessionReadRepository
import com.moneymanager.domain.repository.CryptoReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportProgress
import com.moneymanager.rest.ApiClient
import com.moneymanager.rest.ApiRequestSigner
import com.moneymanager.rest.ScaParams
import kotlin.time.Instant

/*
 * The one entry point for downloading, and the one for importing, an API session — whatever shape the
 * strategy is. A strategy whose accounts are enumerated from an endpoint (a bank) downloads accounts,
 * their identifiers, each account's transaction feed and its people; one that imports into a single
 * account (an exchange) downloads its value and data endpoints. Callers never branch on the shape.
 */

/** The secrets a download authenticates with. */
class ApiDownloadCredentials(
    /** The bearer token, or for a signed strategy its api key. */
    val token: String,
    /** A signed strategy's api secret. */
    val apiSecret: String? = null,
    /** Strong-customer-authentication signing for providers that challenge some requests (Wise). */
    val sca: ScaParams? = null,
)

/** A download that can't start because the strategy or credential is incomplete; [message] says why. */
class ApiDownloadNotPossibleException(
    override val message: String,
) : IllegalStateException(message)

/**
 * Downloads everything [strategy] fetches into session [sessionId]. [watermarks] (how far earlier
 * sessions of the same credential reached) make it incremental; [forceFullDownload] ignores them.
 * [transactionsBlocked] skips a bank's transaction feed (e.g. Wise outside its statement countries).
 *
 * @throws ApiDownloadNotPossibleException before any request, when a signed strategy has no signing
 *   recipe or the credential no api secret.
 */
suspend fun downloadApiSession(
    apiClient: ApiClient,
    apiSessionRepository: ApiSessionReadRepository,
    sessionId: ApiSessionId,
    strategy: ApiImportStrategy,
    importEngine: ImportEngine,
    credentials: ApiDownloadCredentials,
    watermarks: Map<String, Instant> = emptyMap(),
    forceFullDownload: Boolean = false,
    transactionsBlocked: Boolean = false,
    onPhase: (String) -> Unit = {},
    onProgress: (ApiTransactionsDownloadProgress) -> Unit = {},
): ApiSessionDownloadResult =
    when (strategy.config.accounts) {
        is ApiAccountsSource.Single -> {
            val requestSigning =
                strategy.config.requestSigning
                    ?: throw ApiDownloadNotPossibleException("This strategy is missing its request-signing config.")
            val apiSecret =
                credentials.apiSecret?.takeIf { it.isNotBlank() }
                    ?: throw ApiDownloadNotPossibleException("This credential has no API secret; reconnect it.")
            onPhase("Downloading exchange data...")
            val transactions =
                downloadApiSessionExchange(
                    apiClient = apiClient,
                    signer = ApiRequestSigner(requestSigning),
                    apiKey = credentials.token,
                    apiSecret = apiSecret,
                    apiSessionRepository = apiSessionRepository,
                    sessionId = sessionId,
                    strategy = strategy,
                    importEngine = importEngine,
                    watermarks = watermarks,
                    forceFullDownload = forceFullDownload,
                    onProgress = onProgress,
                )
            ApiSessionDownloadResult(accounts = ApiAccountsDownloadResult(accountCount = 1), transactions = transactions, people = null)
        }
        is ApiAccountsSource.Downloaded -> {
            onPhase("Downloading accounts...")
            val accounts =
                downloadApiSessionAccounts(credentials.token, apiClient, apiSessionRepository, sessionId, strategy, credentials.sca)
            if (strategy.config.downloadedAccounts().identifiersEndpoint != null) {
                onPhase("Downloading account identifiers...")
                downloadApiSessionAccountIdentifiers(
                    credentials.token,
                    apiClient,
                    apiSessionRepository,
                    sessionId,
                    strategy,
                    sca = credentials.sca,
                )
            }
            val transactions =
                if (transactionsBlocked) {
                    null
                } else {
                    onPhase("Downloading transactions...")
                    downloadApiSessionTransactions(
                        token = credentials.token,
                        apiClient = apiClient,
                        apiSessionRepository = apiSessionRepository,
                        sessionId = sessionId,
                        strategy = strategy,
                        sca = credentials.sca,
                        importEngine = importEngine,
                        watermarks = watermarks,
                        forceFullDownload = forceFullDownload,
                        onProgress = onProgress,
                    )
                }
            val people =
                strategy.config.peopleDownload?.let {
                    onPhase("Downloading people...")
                    downloadApiSessionPeople(credentials.token, apiClient, apiSessionRepository, sessionId, strategy, credentials.sca)
                }
            ApiSessionDownloadResult(accounts = accounts, transactions = transactions, people = people)
        }
    }

/** What importing one API session produced. */
data class ApiSessionImportOutcome(
    val accountCount: Int,
    val transactionCount: Int,
    val tradeCount: Int = 0,
    val personCount: Int = 0,
    val duplicateCount: Int = 0,
    val errorCount: Int = 0,
)

/** The read repositories an API import consults. */
class ApiImportReads(
    val apiSessionRepository: ApiSessionReadRepository,
    val accountRepository: AccountReadRepository,
    val accountAttributeRepository: AccountAttributeReadRepository,
    val currencyRepository: CurrencyReadRepository,
    val cryptoRepository: CryptoReadRepository,
)

/**
 * Imports the downloaded session [sessionId] through [importEngine]. A bank strategy imports its
 * accounts, transactions and the people derived from them, then the account holders from its people
 * endpoint (after, so the accounts they own exist); an exchange imports its trades and transfers. The
 * engine writes in chunks of [engineBatchSize] so [onProgress] can advance per chunk.
 */
suspend fun importApiSession(
    reads: ApiImportReads,
    sessionId: ApiSessionId,
    strategy: ApiImportStrategy,
    importEngine: ImportEngine,
    counterpartyAccountNames: Map<String, String> = emptyMap(),
    passThroughAccounts: List<PassThroughAccount> = emptyList(),
    onProgress: (suspend (ImportProgress) -> Unit)? = null,
    engineBatchSize: Int = Int.MAX_VALUE,
): ApiSessionImportOutcome =
    when (strategy.config.accounts) {
        is ApiAccountsSource.Single -> {
            val result =
                importApiSessionExchange(
                    apiSessionRepository = reads.apiSessionRepository,
                    accountRepository = reads.accountRepository,
                    currencyRepository = reads.currencyRepository,
                    cryptoRepository = reads.cryptoRepository,
                    sessionId = sessionId,
                    strategy = strategy,
                    importEngine = importEngine,
                    onProgress = onProgress,
                    engineBatchSize = engineBatchSize,
                )
            ApiSessionImportOutcome(
                accountCount = 0,
                transactionCount = result.transfersImported,
                tradeCount = result.tradesImported,
                duplicateCount = result.duplicatesSkipped,
            )
        }
        is ApiAccountsSource.Downloaded -> {
            val transactions =
                importApiSessionTransactions(
                    apiSessionRepository = reads.apiSessionRepository,
                    currencyRepository = reads.currencyRepository,
                    sessionId = sessionId,
                    strategy = strategy,
                    importEngine = importEngine,
                    counterpartyAccountNames = counterpartyAccountNames,
                    passThroughAccounts = passThroughAccounts,
                    onProgress = onProgress ?: {},
                )
            val people =
                importApiSessionPeople(
                    apiSessionRepository = reads.apiSessionRepository,
                    accountAttributeRepository = reads.accountAttributeRepository,
                    importEngine = importEngine,
                    sessionId = sessionId,
                    strategy = strategy,
                    accountsSessionId = sessionId,
                )
            ApiSessionImportOutcome(
                accountCount = transactions.accountCount,
                transactionCount = transactions.transactionCount,
                personCount = transactions.personCount + people.personCount,
                duplicateCount = transactions.duplicateCount,
                errorCount = transactions.errorCount,
            )
        }
    }
