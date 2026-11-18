package com.moneymanager.importengineapi

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.ApiCredentialId
import com.moneymanager.domain.model.ApiImportStrategyId
import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiResponseId
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.ImportDirectoryId
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.RelationshipId
import com.moneymanager.domain.model.RelationshipMember
import com.moneymanager.domain.model.RelationshipRole
import com.moneymanager.domain.model.RelationshipTypeId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.accountmapping.AccountMapping
import com.moneymanager.domain.model.apistrategy.ApiImportStrategy
import com.moneymanager.domain.model.calendar.HolidayCalendar
import com.moneymanager.domain.model.csv.PdfImportSource
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.importdirectory.ImportDirectory
import com.moneymanager.domain.model.observation.ObservationId
import com.moneymanager.domain.model.observation.ObservationPin
import com.moneymanager.domain.model.observation.ObservationSplit
import com.moneymanager.domain.model.passthrough.PassThroughAccount
import com.moneymanager.domain.model.passthrough.PassThroughAccountId
import com.moneymanager.regex.PortableRegex
import kotlin.time.Instant

/*
 * Convenience ImportEngine extensions mirroring the config/staging/session/settings write repositories,
 * each building a one-item ImportBatch. Callers use these instead of injecting the corresponding write
 * repository, keeping the engine the sole writer. Each create reads its generated id back from the
 * result map under a per-call read-back key derived from the input.
 */

// region Observation decisions

/**
 * "These are the same": pins every one of [observationIds] to [transactionId], then re-matches, so the matcher joins
 * them whatever its rules say and keeps them joined through re-imports.
 */
suspend fun ImportEngine.pinObservations(
    observationIds: Collection<ObservationId>,
    transactionId: TransactionId,
): ImportResult =
    import(
        ImportBatch(
            observationDecisions = observationIds.map { ObservationDecision.Pin(ObservationPin(it, transactionId)) },
            syncMatches = true,
        ),
    )

/** Takes back the pins of [observationIds], then re-matches: the matcher's rules decide them again. */
suspend fun ImportEngine.unpinObservations(observationIds: Collection<ObservationId>): ImportResult =
    import(ImportBatch(observationDecisions = observationIds.map { ObservationDecision.Unpin(it) }, syncMatches = true))

/**
 * "These are different": keeps observations [a] and [b] apart, then re-matches, so the transaction that joined them
 * splits in two and both count, now and after every re-import.
 */
suspend fun ImportEngine.separateObservations(
    a: ObservationId,
    b: ObservationId,
): ImportResult =
    import(ImportBatch(observationDecisions = listOf(ObservationDecision.Split(ObservationSplit.of(a, b))), syncMatches = true))

/** Takes back a split of [a] and [b], then re-matches. */
suspend fun ImportEngine.unseparateObservations(
    a: ObservationId,
    b: ObservationId,
): ImportResult =
    import(ImportBatch(observationDecisions = listOf(ObservationDecision.Unsplit(ObservationSplit.of(a, b))), syncMatches = true))

// endregion

// region Trade attributes

/** Excludes trade [tradeId] from balances with [reason], or brings it back when [reason] is null. */
suspend fun ImportEngine.setTradeExcluded(
    tradeId: TradeId,
    reason: String?,
) {
    val typeId = AttributeTypeId(WellKnownIds.EXCLUDED_ATTR_TYPE_ID)
    val mutation =
        if (reason == null) TradeAttributeMutation.Remove(tradeId, typeId) else TradeAttributeMutation.Set(tradeId, typeId, reason)
    import(ImportBatch(tradeAttributeMutations = listOf(mutation)))
}

/**
 * Excludes [transfer] from balances with [reason], or brings it back when [reason] is null: a hand edit
 * ([Source.Manual]), so it's audited and a re-import of the transfer's source puts it back.
 */
suspend fun ImportEngine.setTransferExcluded(
    transfer: Transfer,
    reason: String?,
) {
    val typeId = AttributeTypeId(WellKnownIds.EXCLUDED_ATTR_TYPE_ID)
    val current = transfer.attributes.filter { it.attributeType.id == typeId }
    if (current.map { it.value } == listOfNotNull(reason)) return
    val update =
        ImportTransfer(
            source = Source.Manual,
            operation = ImportOperation.UPDATE,
            existingId = transfer.id,
            description = transfer.description,
            deletedAttributeIds = current.mapTo(mutableSetOf()) { it.id },
            attributes = listOfNotNull(reason?.let { NewAttribute(typeId, it) }),
        )
    import(ImportBatch.manualEdits(transfers = listOf(update)))
}

// endregion

// region Relationships

/**
 * Stores a person's statement that [members] (transfers and trades) record one movement: a "reconciliation-match"
 * relationship, every member a MEMBER, written by hand so a re-import rebuilds it.
 */
suspend fun ImportEngine.createReconciliationMatch(members: Collection<TransactionId>) = createReconciliationMatches(listOf(members))

/**
 * [createReconciliationMatch] for several movements at once, in one batch, with [pins]: a person's match also pins its
 * members' observations where they are, so a re-import re-makes each member as the transaction the match names.
 */
suspend fun ImportEngine.createReconciliationMatches(
    matches: Collection<Collection<TransactionId>>,
    pins: Collection<ObservationPin> = emptyList(),
) {
    if (matches.isEmpty()) return
    val typeId = RelationshipTypeId(WellKnownIds.RECONCILIATION_MATCH_RELATIONSHIP_TYPE_ID)
    val mutations =
        matches.map { members ->
            val distinct = members.distinct()
            require(distinct.size >= 2) { "A match needs at least two distinct transactions" }
            RelationshipMutation.Create(
                typeId = typeId,
                members = distinct.map { RelationshipMember(it, RelationshipRole.MEMBER) },
                source = Source.Manual,
            )
        }
    import(ImportBatch(relationshipMutations = mutations, observationDecisions = pins.map { ObservationDecision.Pin(it) }))
}

suspend fun ImportEngine.deleteRelationship(id: RelationshipId) {
    import(ImportBatch(relationshipMutations = listOf(RelationshipMutation.Delete(id))))
}

// endregion

// region Reconciliation links

/** Links [shadowAccountId] to exactly [realAccountIds] (see [ReconciliationLinkMutation.SetLinks]). */
suspend fun ImportEngine.setReconciliationLinks(
    shadowAccountId: AccountId,
    realAccountIds: Set<AccountId>,
) {
    import(ImportBatch(reconciliationLinkMutations = listOf(ReconciliationLinkMutation.SetLinks(shadowAccountId, realAccountIds))))
}

// endregion

// region Pass-through accounts

suspend fun ImportEngine.createPassThroughAccount(account: PassThroughAccount) {
    import(ImportBatch(passThroughMutations = listOf(PassThroughMutation.Create(account))))
}

suspend fun ImportEngine.updatePassThroughAccount(account: PassThroughAccount) {
    import(ImportBatch(passThroughMutations = listOf(PassThroughMutation.Update(account))))
}

suspend fun ImportEngine.deletePassThroughAccount(id: PassThroughAccountId) {
    import(ImportBatch(passThroughMutations = listOf(PassThroughMutation.Delete(id))))
}

// endregion

// region Asset aliases

suspend fun ImportEngine.setAssetAlias(
    alias: String,
    assetCode: String,
) {
    import(ImportBatch(assetAliasMutations = listOf(AssetAliasMutation.Set(alias, assetCode))))
}

suspend fun ImportEngine.removeAssetAlias(alias: String) {
    import(ImportBatch(assetAliasMutations = listOf(AssetAliasMutation.Remove(alias))))
}

// endregion

// region Holiday calendars

suspend fun ImportEngine.upsertHolidayCalendar(calendar: HolidayCalendar) {
    import(ImportBatch(holidayCalendarMutations = listOf(HolidayCalendarMutation.Upsert(calendar))))
}

suspend fun ImportEngine.deleteHolidayCalendar(code: String) {
    import(ImportBatch(holidayCalendarMutations = listOf(HolidayCalendarMutation.Delete(code))))
}

// endregion

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

// region account mappings

suspend fun ImportEngine.createAccountMapping(
    valuePattern: PortableRegex,
    accountId: AccountId,
    strategyId: CsvImportStrategyId? = null,
): Long =
    requireNotNull(
        import(
            ImportBatch(
                accountMappingMutations =
                    listOf(AccountMappingMutation.Create(valuePattern.pattern, valuePattern, accountId, strategyId)),
            ),
        ).createdAccountMappingIds[valuePattern.pattern],
    )

suspend fun ImportEngine.createAccountMappings(mappings: List<AccountMapping>) {
    import(ImportBatch(accountMappingMutations = listOf(AccountMappingMutation.CreateBatch(mappings))))
}

suspend fun ImportEngine.updateAccountMapping(mapping: AccountMapping) {
    import(ImportBatch(accountMappingMutations = listOf(AccountMappingMutation.Update(mapping))))
}

suspend fun ImportEngine.deleteAccountMapping(id: Long) {
    import(ImportBatch(accountMappingMutations = listOf(AccountMappingMutation.Delete(id))))
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

/**
 * Stages an Excel worksheet exactly like [createCsvImport], additionally storing the raw workbook
 * [xlsxBytes] and the [xlsxWorksheetName] [headers]/[rows] were parsed from, so the sheet can be
 * re-extracted later if a different worksheet is selected. The caller (JVM-side, via the injected
 * `XlsxParser`) has already parsed the sheet into the same headers/rows shape a CSV file would produce.
 */
suspend fun ImportEngine.createXlsxImport(
    fileName: String,
    headers: List<String>,
    rows: List<List<String>>,
    fileChecksum: String,
    fileLastModified: Instant,
    xlsxBytes: ByteArray,
    xlsxWorksheetName: String,
): CsvImportId =
    requireNotNull(
        import(
            ImportBatch(
                csvImportMutations =
                    listOf(
                        CsvImportMutation.Create(
                            fileName,
                            fileName,
                            headers,
                            rows,
                            fileChecksum,
                            fileLastModified,
                            xlsxBytes,
                            xlsxWorksheetName,
                        ),
                    ),
            ),
        ).createdCsvImportIds[fileName],
    )

/**
 * Stages a PDF exactly like [createCsvImport], additionally storing the file and the text [PdfImportSource.lines]
 * read from it. [headers]/[rows] are those lines (see `PdfRowAssembler`) until a strategy restages them through
 * [CsvImportMutation.Restage].
 */
suspend fun ImportEngine.createPdfImport(
    fileName: String,
    headers: List<String>,
    rows: List<List<String>>,
    fileChecksum: String,
    fileLastModified: Instant,
    pdf: PdfImportSource,
): CsvImportId =
    requireNotNull(
        import(
            ImportBatch(
                csvImportMutations =
                    listOf(CsvImportMutation.Create(fileName, fileName, headers, rows, fileChecksum, fileLastModified, pdf = pdf)),
            ),
        ).createdCsvImportIds[fileName],
    )

suspend fun ImportEngine.deleteCsvImport(id: CsvImportId) {
    import(ImportBatch(csvImportMutations = listOf(CsvImportMutation.Delete(id))))
}

suspend fun ImportEngine.setCsvImportIgnored(
    id: CsvImportId,
    ignored: Boolean,
) {
    import(ImportBatch(csvImportMutations = listOf(CsvImportMutation.SetIgnored(id, ignored))))
}

/** Applies several CSV staging write-backs (status/error/application) in one batch. */
suspend fun ImportEngine.applyCsvImportMutations(mutations: List<CsvImportMutation>) {
    if (mutations.isNotEmpty()) import(ImportBatch(csvImportMutations = mutations))
}

// endregion

// region Settings

suspend fun ImportEngine.setDefaultCurrency(id: CurrencyId) {
    import(ImportBatch(settings = ImportSettings(defaultCurrencyId = id)))
}

suspend fun ImportEngine.setDefaultLocation(code: String?) {
    import(ImportBatch(settings = ImportSettings(defaultLocation = DefaultLocation(code))))
}

suspend fun ImportEngine.setSetupWizardCompleted(completed: Boolean) {
    import(ImportBatch(settings = ImportSettings(setupWizardCompleted = completed)))
}

// endregion

// region API sessions

/**
 * Gets or creates the connection rows for [strategyIds], returning each one's credential id. Idempotent,
 * so it is safe to call whenever the credential vault shows a strategy as connected.
 */
suspend fun ImportEngine.ensureApiCredentials(
    strategyIds: Collection<ApiImportStrategyId>,
    createdAt: Instant,
): Map<ApiImportStrategyId, ApiCredentialId> {
    if (strategyIds.isEmpty()) return emptyMap()
    val distinct = strategyIds.distinct()
    val result =
        import(
            ImportBatch(
                apiSessionMutations = distinct.map { ApiSessionMutation.EnsureCredential(it.toString(), it, createdAt) },
            ),
        )
    return distinct.associateWith { requireNotNull(result.apiCredentialIds[it.toString()]) }
}

suspend fun ImportEngine.ensureApiCredential(
    strategyId: ApiImportStrategyId,
    createdAt: Instant,
): ApiCredentialId = ensureApiCredentials(listOf(strategyId), createdAt).getValue(strategyId)

suspend fun ImportEngine.createApiSession(
    deviceId: DeviceId,
    createdAt: Instant,
    credentialId: ApiCredentialId? = null,
): ApiSessionId {
    // A helper creates exactly one session per batch, so any unique key will do.
    val key = createdAt.toString()
    return requireNotNull(
        import(
            ImportBatch(
                apiSessionMutations =
                    listOf(ApiSessionMutation.CreateSession(key, deviceId, createdAt, credentialId)),
            ),
        ).apiSessionIds[key],
    )
}

suspend fun ImportEngine.deleteApiSession(id: ApiSessionId) {
    import(ImportBatch(apiSessionMutations = listOf(ApiSessionMutation.DeleteSession(id))))
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

suspend fun ImportEngine.recordApiDownloadCoverage(
    sessionId: ApiSessionId,
    endpointKey: String,
    coversUntil: Instant,
) {
    import(
        ImportBatch(
            apiSessionMutations = listOf(ApiSessionMutation.RecordDownloadCoverage(sessionId, endpointKey, coversUntil)),
        ),
    )
}

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

// region Import directories

suspend fun ImportEngine.createImportDirectory(
    directory: ImportDirectory,
    source: Source = Source.Manual,
): ImportDirectoryId {
    val key = directory.id.toString()
    return requireNotNull(
        import(ImportBatch(importDirectoryMutations = listOf(ImportDirectoryMutation.Create(key, directory, source))))
            .createdImportDirectoryIds[key],
    )
}

suspend fun ImportEngine.updateImportDirectory(
    directory: ImportDirectory,
    source: Source = Source.Manual,
) {
    import(ImportBatch(importDirectoryMutations = listOf(ImportDirectoryMutation.Update(directory, source))))
}

suspend fun ImportEngine.deleteImportDirectory(id: ImportDirectoryId) {
    import(ImportBatch(importDirectoryMutations = listOf(ImportDirectoryMutation.Delete(id))))
}

@Suppress("LongParameterList")
suspend fun ImportEngine.recordDirectoryFileImported(
    directoryId: ImportDirectoryId,
    fileRef: String,
    fileName: String,
    lastModified: Instant,
    checksum: String?,
    remoteContentHash: String? = null,
    csvImportId: CsvImportId? = null,
    importedAt: Instant,
) {
    import(
        ImportBatch(
            importDirectoryMutations =
                listOf(
                    ImportDirectoryMutation.RecordFileImported(
                        directoryId = directoryId,
                        fileRef = fileRef,
                        fileName = fileName,
                        lastModified = lastModified,
                        checksum = checksum,
                        remoteContentHash = remoteContentHash,
                        csvImportId = csvImportId,
                        importedAt = importedAt,
                    ),
                ),
        ),
    )
}

// endregion
