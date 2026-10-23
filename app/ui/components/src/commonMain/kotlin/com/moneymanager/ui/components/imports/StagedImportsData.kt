package com.moneymanager.ui.components.imports

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.ImportDirectoryId
import com.moneymanager.domain.model.StagedImport
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.HardCodedAccountMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.importdirectory.ImportDirectory
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CsvImportStrategyReadRepository
import com.moneymanager.domain.repository.ImportDirectoryReadRepository
import com.moneymanager.presenter.error.rememberFlowAsStateWithSchemaErrorHandling
import kotlinx.coroutines.flow.Flow

internal const val FROM_EACH_ROW = "From each row"

/** What an import list screen reads beside its files, for [StagedImportsTabs] and [StagedImportCard]. */
class StagedImportsData<K : Any>(
    val directories: List<ImportDirectory>,
    // Each file's source directories (see resolveImportDirectories).
    val importDirectories: Map<K, List<ImportDirectory>>,
    // Each unimported file's strategy as "Import all" would match it; null until matching finishes.
    val matchedStrategies: Map<K, CsvImportStrategy?>?,
    private val strategies: List<CsvImportStrategy>,
    private val accounts: List<Account>,
    // The account files scanned from each import directory belong to (see ImportDirectory.accountId).
    private val directoryAccounts: Map<K, AccountId>,
) {
    /**
     * The account [import]'s transfers were (or will be) created against, resolved the way an import run
     * resolves it: the applied (else matched) strategy's own hard-coded SOURCE_ACCOUNT (e.g. crypto.com's
     * fixed conduit account), else the file's import-directory account (its folder's or its parent's), else
     * the account named after the file's folder that the run uses or creates, for a strategy with no
     * SOURCE_ACCOUNT mapping (one that maps it from a column reads it from each row). Null for a manually
     * added file whose strategy names no account: the person chooses at import.
     */
    fun sourceAccountName(import: StagedImport<K>): String? {
        val strategyId = import.lastAppliedStrategyId ?: matchedStrategies?.get(import.id)?.id
        val strategy = strategyId?.let { id -> strategies.find { it.id == id } }
        val sourceMapping = strategy?.config?.fieldMappings?.get(TransferField.SOURCE_ACCOUNT)
        val hardCoded = (sourceMapping as? HardCodedAccountMapping)?.accountId
        (hardCoded ?: directoryAccounts[import.id])?.let { id -> return accounts.firstOrNull { it.id == id }?.name }
        // Only a strategy with no SOURCE_ACCOUNT mapping takes its account from the folder.
        if (sourceMapping != null) return FROM_EACH_ROW
        // A file staged from several folders has no one folder to name its account after.
        val folderName = importDirectories[import.id]?.singleOrNull()?.folderName ?: return null
        return if (accounts.any { it.name == folderName }) folderName else "$folderName (created at import)"
    }
}

/**
 * Loads [StagedImportsData] for [imports], the same way for every format: the format supplies only
 * where its files' directories ([importDirectoryIds]) and directory accounts ([sourceAccounts]) come from,
 * and how "Import all" matches a strategy ([matchStrategies]).
 */
@Suppress("LongParameterList")
@Composable
fun <K : Any, F : StagedImport<K>> rememberStagedImportsData(
    imports: List<F>,
    importDirectoryRepository: ImportDirectoryReadRepository,
    csvImportStrategyRepository: CsvImportStrategyReadRepository,
    accountRepository: AccountReadRepository,
    importDirectoryIds: ImportDirectoryReadRepository.() -> Flow<Map<K, List<ImportDirectoryId>>>,
    sourceAccounts: suspend ImportDirectoryReadRepository.() -> Map<K, AccountId>,
    matchStrategies: suspend (List<F>) -> Map<K, CsvImportStrategy?>,
): StagedImportsData<K> {
    val strategies by rememberFlowAsStateWithSchemaErrorHandling(initial = emptyList()) {
        csvImportStrategyRepository.getAllStrategies()
    }
    val accounts by rememberFlowAsStateWithSchemaErrorHandling(initial = emptyList()) { accountRepository.getAllAccounts() }
    val directories by rememberFlowAsStateWithSchemaErrorHandling(initial = emptyList()) {
        importDirectoryRepository.getAllDirectories()
    }
    val directoryIds by rememberFlowAsStateWithSchemaErrorHandling(initial = emptyMap()) {
        importDirectoryRepository.importDirectoryIds()
    }
    var directoryAccounts by remember { mutableStateOf<Map<K, AccountId>>(emptyMap()) }
    LaunchedEffect(imports) {
        directoryAccounts = importDirectoryRepository.sourceAccounts()
    }
    val importDirectories = remember(directoryIds, directories) { resolveImportDirectories(directoryIds, directories) }

    // Unimported files carry no stored strategy, so match one per file exactly as "Import all" will, to
    // group the Unimported tab by strategy and flag files with no match. Re-run when the strategies change.
    val unimported = remember(imports) { imports.filter { !it.ignored && it.lastAppliedAt == null } }
    var matchedStrategies by remember { mutableStateOf<Map<K, CsvImportStrategy?>?>(null) }
    LaunchedEffect(unimported, strategies) {
        matchedStrategies = matchStrategies(unimported)
    }
    return StagedImportsData(directories, importDirectories, matchedStrategies, strategies, accounts, directoryAccounts)
}
