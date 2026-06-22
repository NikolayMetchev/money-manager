@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.importengineapi

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiResponseId
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.ApiSessionType
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.MonzoCredentialId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.model.apistrategy.ApiImportStrategyId
import com.moneymanager.domain.model.csv.CsvImportId
import com.moneymanager.domain.model.csvstrategy.CsvAccountMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyId
import com.moneymanager.domain.model.qif.QifImportId
import com.moneymanager.domain.model.qif.QifImportRecord
import com.moneymanager.domain.repository.ApiResponseTransactionInsert
import kotlin.time.Instant

/*
 * Convenience ImportEngine extensions mirroring the config/staging/session/settings write repositories,
 * each building a one-item ImportBatch. Callers use these instead of injecting the corresponding write
 * repository, keeping the engine the sole writer. Each create reads its generated id back from the
 * result map under a per-call read-back key derived from the input.
 */

// region CSV strategies

suspend fun ImportEngine.createCsvStrategy(
    strategy: CsvImportStrategy,
    source: Source = Source.Manual,
): CsvImportStrategyId {
    val key = strategy.name
    return requireNotNull(
        import(ImportBatch(csvStrategyMutations = listOf(CsvStrategyMutation.Create(key, strategy, source))))
            .createdCsvStrategyIds[key],
    )
}

suspend fun ImportEngine.updateCsvStrategy(
    strategy: CsvImportStrategy,
    source: Source = Source.Manual,
) {
    import(ImportBatch(csvStrategyMutations = listOf(CsvStrategyMutation.Update(strategy, source))))
}

suspend fun ImportEngine.deleteCsvStrategy(id: CsvImportStrategyId) {
    import(ImportBatch(csvStrategyMutations = listOf(CsvStrategyMutation.Delete(id))))
}

// endregion

// region API strategies

suspend fun ImportEngine.createApiStrategy(
    strategy: ApiImportStrategy,
    source: Source = Source.Manual,
): ApiImportStrategyId {
    val key = strategy.name
    return requireNotNull(
        import(ImportBatch(apiStrategyMutations = listOf(ApiStrategyMutation.Create(key, strategy, source))))
            .createdApiStrategyIds[key],
    )
}

suspend fun ImportEngine.updateApiStrategy(
    strategy: ApiImportStrategy,
    source: Source = Source.Manual,
) {
    import(ImportBatch(apiStrategyMutations = listOf(ApiStrategyMutation.Update(strategy, source))))
}

suspend fun ImportEngine.deleteApiStrategy(id: ApiImportStrategyId) {
    import(ImportBatch(apiStrategyMutations = listOf(ApiStrategyMutation.Delete(id))))
}

// endregion

// region CSV account mappings

suspend fun ImportEngine.createCsvMapping(
    strategyId: CsvImportStrategyId,
    columnName: String,
    valuePattern: Regex,
    accountId: AccountId,
): Long =
    requireNotNull(
        import(
            ImportBatch(
                csvMappingMutations = listOf(CsvMappingMutation.Create(columnName, strategyId, columnName, valuePattern, accountId)),
            ),
        ).createdCsvMappingIds[columnName],
    )

suspend fun ImportEngine.createCsvMappings(mappings: List<CsvAccountMapping>) {
    import(ImportBatch(csvMappingMutations = listOf(CsvMappingMutation.CreateBatch(mappings))))
}

suspend fun ImportEngine.updateCsvMapping(mapping: CsvAccountMapping) {
    import(ImportBatch(csvMappingMutations = listOf(CsvMappingMutation.Update(mapping))))
}

suspend fun ImportEngine.deleteCsvMapping(id: Long) {
    import(ImportBatch(csvMappingMutations = listOf(CsvMappingMutation.Delete(id))))
}

// endregion

// region CSV staging

suspend fun ImportEngine.createCsvImport(
    fileName: String,
    headers: List<String>,
    rows: List<List<String>>,
    fileChecksum: String,
    fileLastModified: Instant,
): CsvImportId =
    requireNotNull(
        import(
            ImportBatch(
                csvImportMutations = listOf(CsvImportMutation.Create(fileName, fileName, headers, rows, fileChecksum, fileLastModified)),
            ),
        ).createdCsvImportIds[fileName],
    )

suspend fun ImportEngine.deleteCsvImport(id: CsvImportId) {
    import(ImportBatch(csvImportMutations = listOf(CsvImportMutation.Delete(id))))
}

/** Applies several CSV staging write-backs (status/error/application) in one batch. */
suspend fun ImportEngine.applyCsvImportMutations(mutations: List<CsvImportMutation>) {
    if (mutations.isNotEmpty()) import(ImportBatch(csvImportMutations = mutations))
}

// endregion

// region QIF staging

suspend fun ImportEngine.createQifImport(
    fileName: String,
    records: List<QifImportRecord>,
    accountType: String,
    fileChecksum: String,
    fileLastModified: Instant,
): QifImportId =
    requireNotNull(
        import(
            ImportBatch(
                qifImportMutations =
                    listOf(QifImportMutation.Create(fileName, fileName, records, accountType, fileChecksum, fileLastModified)),
            ),
        ).createdQifImportIds[fileName],
    )

suspend fun ImportEngine.deleteQifImport(id: QifImportId) {
    import(ImportBatch(qifImportMutations = listOf(QifImportMutation.Delete(id))))
}

/** Applies several QIF staging write-backs (status/error/application) in one batch. */
suspend fun ImportEngine.applyQifImportMutations(mutations: List<QifImportMutation>) {
    if (mutations.isNotEmpty()) import(ImportBatch(qifImportMutations = mutations))
}

// endregion

// region Settings

suspend fun ImportEngine.setDefaultCurrency(id: CurrencyId) {
    import(ImportBatch(settings = ImportSettings(defaultCurrencyId = id)))
}

suspend fun ImportEngine.setLastQifAccount(id: AccountId) {
    import(ImportBatch(settings = ImportSettings(lastQifAccountId = id)))
}

// endregion

// region API sessions

suspend fun ImportEngine.createApiCredential(
    token: String,
    createdAt: Instant,
    type: ApiSessionType = ApiSessionType.MONZO,
    strategyId: ApiImportStrategyId? = null,
    privateKey: String? = null,
    publicKey: String? = null,
): MonzoCredentialId {
    // The read-back key is echoed through ImportResult, so derive it from the (non-secret) createdAt
    // timestamp rather than the secret token. A helper creates exactly one credential per batch.
    val key = createdAt.toString()
    return requireNotNull(
        import(
            ImportBatch(
                apiSessionMutations =
                    listOf(ApiSessionMutation.CreateCredential(key, token, createdAt, type, strategyId, privateKey, publicKey)),
            ),
        ).apiCredentialIds[key],
    )
}

suspend fun ImportEngine.updateApiCredentialKeys(
    credentialId: MonzoCredentialId,
    privateKey: String?,
    publicKey: String?,
) {
    import(ImportBatch(apiSessionMutations = listOf(ApiSessionMutation.UpdateCredentialKeys(credentialId, privateKey, publicKey))))
}

suspend fun ImportEngine.createApiSession(
    token: String,
    deviceId: DeviceId,
    createdAt: Instant,
    type: ApiSessionType = ApiSessionType.MONZO,
    credentialId: MonzoCredentialId? = null,
): ApiSessionId {
    // The read-back key is echoed through ImportResult, so derive it from the (non-secret) createdAt
    // timestamp rather than the secret token. A helper creates exactly one session per batch.
    val key = createdAt.toString()
    return requireNotNull(
        import(
            ImportBatch(
                apiSessionMutations =
                    listOf(ApiSessionMutation.CreateSession(key, token, deviceId, createdAt, type, credentialId)),
            ),
        ).apiSessionIds[key],
    )
}

suspend fun ImportEngine.insertApiRequest(
    sessionId: ApiSessionId,
    method: String,
    url: String,
    headers: Map<String, String>,
): ApiRequestId =
    requireNotNull(
        import(ImportBatch(apiSessionMutations = listOf(ApiSessionMutation.InsertRequest(url, sessionId, method, url, headers))))
            .apiRequestIds[url],
    )

suspend fun ImportEngine.insertApiResponse(
    requestId: ApiRequestId,
    sessionId: ApiSessionId,
    json: String,
): ApiResponseId {
    val key = requestId.toString()
    return requireNotNull(
        import(ImportBatch(apiSessionMutations = listOf(ApiSessionMutation.InsertResponse(key, requestId, sessionId, json))))
            .apiResponseIds[key],
    )
}

suspend fun ImportEngine.insertApiResponseTransactions(transactions: List<ApiResponseTransactionInsert>) {
    import(ImportBatch(apiSessionMutations = listOf(ApiSessionMutation.InsertResponseTransactions(transactions))))
}

suspend fun ImportEngine.markApiSessionImported(
    id: ApiSessionId,
    revisionId: Long,
    importedAt: Instant,
    importDurationMillis: Long? = null,
) {
    import(
        ImportBatch(
            apiSessionMutations = listOf(ApiSessionMutation.MarkSessionImported(id, revisionId, importedAt, importDurationMillis)),
        ),
    )
}

// endregion
