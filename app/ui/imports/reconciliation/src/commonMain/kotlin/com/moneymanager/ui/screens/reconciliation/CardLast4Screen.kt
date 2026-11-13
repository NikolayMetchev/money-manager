package com.moneymanager.ui.screens.reconciliation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.moneymanager.csvimporter.AttributeAccountMatcher
import com.moneymanager.csvimporter.BulkReimportResult
import com.moneymanager.csvimporter.FundingReference
import com.moneymanager.csvimporter.FundingReferenceFile
import com.moneymanager.csvimporter.applyFundingReferenceMoves
import com.moneymanager.csvimporter.findFundingReferences
import com.moneymanager.csvimporter.planFundingReferenceMoves
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.repository.AccountAttributeReadRepository
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.domain.repository.CsvImportReadRepository
import com.moneymanager.domain.repository.CsvImportStrategyReadRepository
import com.moneymanager.domain.repository.ImportDirectoryReadRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.presenter.error.rememberFlowAsStateWithSchemaErrorHandling
import com.moneymanager.tasks.Progress
import com.moneymanager.ui.components.AccountPicker
import com.moneymanager.ui.components.imports.BulkImportProgressIndicator
import com.moneymanager.ui.components.imports.ImportSectionHeader
import com.moneymanager.ui.components.imports.ReimportProgressIndicator
import com.moneymanager.ui.components.imports.resolveImportDirectories
import com.moneymanager.ui.components.imports.stagedImportPath
import com.moneymanager.ui.foundation.LocalImportEngine
import com.moneymanager.ui.tasks.LocalTaskManager

/** The task that applies Card Last-4's changes. */
const val CARD_ASSIGNMENT_TASK_KEY = "card-last4"

private fun FundingReference.key(): String = "$attributeTypeName:$value"

/**
 * Funding references (card last-4 digits and the like) that imported rows carry, unassigned ones first, then
 * the ones an account owns. Picking an account only marks the card as changed; **Apply** then writes every
 * change at once — a card joins the picked account's attribute set (an account may own several cards) and
 * leaves the account that held it, so a wrong pick can be changed here — and re-imports the files that
 * reference any changed card, each once, so their rows reconcile against the cards' accounts.
 *
 * Applying runs as a background task, shown with its progress in the task panel, so it outlives this screen.
 * [prepareReimport] plans the re-import, reporting to the progress callback it is given, and refuses before
 * any card is saved when one of the files can't be re-imported; it returns what runs it.
 * Which column and attribute type count is whatever a CSV strategy's funding match says, so this
 * screen knows nothing about any particular source.
 */
@Composable
fun CardLast4Screen(
    csvImportRepository: CsvImportReadRepository,
    importDirectoryRepository: ImportDirectoryReadRepository,
    csvImportStrategyRepository: CsvImportStrategyReadRepository,
    accountAttributeRepository: AccountAttributeReadRepository,
    accountRepository: AccountReadRepository,
    categoryRepository: CategoryReadRepository,
    personRepository: PersonReadRepository,
    prepareReimport: suspend (List<CsvImportId>, suspend (Progress) -> Unit) -> suspend () -> BulkReimportResult,
    onOpenFile: (CsvImportId, Map<String, Set<String>>) -> Unit = { _, _ -> },
) {
    val importEngine = LocalImportEngine.current
    val taskManager = LocalTaskManager.current
    val tasks by taskManager.tasks.collectAsState()
    val imports by rememberFlowAsStateWithSchemaErrorHandling(initial = null) { csvImportRepository.getAllImports() }
    val strategies by rememberFlowAsStateWithSchemaErrorHandling(initial = null) { csvImportStrategyRepository.getAllStrategies() }
    val attributes by rememberFlowAsStateWithSchemaErrorHandling(initial = null) { accountAttributeRepository.getAll() }
    val csvImportDirectories by rememberFlowAsStateWithSchemaErrorHandling(initial = emptyMap()) {
        importDirectoryRepository.csvImportDirectories()
    }
    val directories by rememberFlowAsStateWithSchemaErrorHandling(initial = emptyList()) { importDirectoryRepository.getAllDirectories() }
    // Each file as the imports lists name it: its folder's path and its file name.
    val filePaths =
        remember(imports, csvImportDirectories, directories) {
            val resolved = resolveImportDirectories(csvImportDirectories, directories)
            imports.orEmpty().associate { it.id to stagedImportPath(it, resolved) }
        }

    var references by remember { mutableStateOf<List<FundingReference>?>(null) }
    // The picks not applied yet, by reference; a card picked back to its owner drops out.
    val pending = remember { mutableStateMapOf<String, AccountId>() }
    // Set on the click itself: the task list only shows the run a recomposition later.
    var submitted by remember { mutableStateOf(false) }
    var unassignedExpanded by remember { mutableStateOf(true) }
    // Folded by default: the cards to look at are the ones nobody owns yet.
    var assignedExpanded by remember { mutableStateOf(false) }
    val task = tasks.firstOrNull { it.isRunning && it.key == CARD_ASSIGNMENT_TASK_KEY }
    val busy = submitted || task != null

    LaunchedEffect(imports, strategies, attributes) {
        val currentImports = imports ?: return@LaunchedEffect
        val currentStrategies = strategies ?: return@LaunchedEffect
        val currentAttributes = attributes ?: return@LaunchedEffect
        val found =
            findFundingReferences(
                imports = currentImports,
                strategies = currentStrategies,
                csvImportRepository = csvImportRepository,
                attributeAccountMatchers = AttributeAccountMatcher.registry(currentAttributes),
            )
        references = found
        // A pick the saved cards now match (or whose card is gone) is no longer a change.
        val owners = found.associate { it.key() to it.owner }
        pending.keys.filter { key -> key !in owners || owners[key] == pending[key] }.forEach { pending.remove(it) }
    }

    fun pick(
        reference: FundingReference,
        accountId: AccountId,
    ) {
        if (accountId == reference.owner) pending.remove(reference.key()) else pending[reference.key()] = accountId
    }

    fun apply() {
        val changes =
            references.orEmpty().mapNotNull { reference -> pending[reference.key()]?.let { reference to it } }.toMap()
        if (busy || changes.isEmpty()) return
        submitted = true
        val count = changes.size
        val title = "Assign ${if (count == 1) "card ${changes.keys.single().value}" else "$count cards"}"
        val id =
            taskManager.start(CARD_ASSIGNMENT_TASK_KEY, title, "Planning the re-import") {
                try {
                    // Planned first: a refusal must leave every card where it was, so it can be retried. Every card whose
                    // owner the moves change is re-imported, not just the picked ones: a token can match other cards too.
                    val moves = planFundingReferenceMoves(changes, references.orEmpty(), accountAttributeRepository)
                    val reimport = prepareReimport(moves.affected.flatMap { it.imports }.distinct(), ::report)
                    report("Saving the cards")
                    importEngine.applyFundingReferenceMoves(moves)
                    val result = reimport()
                    if (result.filesFailed > 0) {
                        reportIssue("${result.filesFailed} file(s) failed to re-import; retry them from their import screen")
                    }
                    "Assigned ${changes.keys.joinToString { it.value }}. ${result.toSummary()}."
                } finally {
                    submitted = false
                }
            }
        if (id == null) submitted = false
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Cards that imports reference, and the account each belongs to. Pick an account for a card nobody owns, " +
                "or a different one to move a card picked wrongly; an account can own several cards. Apply saves " +
                "every change and re-imports the files that reference them, each once.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = ::apply, enabled = pending.isNotEmpty() && !busy) {
                Text(if (pending.isEmpty()) "Apply" else "Apply ${pending.size} change${if (pending.size == 1) "" else "s"}")
            }
            TextButton(onClick = { pending.clear() }, enabled = pending.isNotEmpty() && !busy) { Text("Discard") }
        }
        task?.let {
            // As the re-import dialog shows it: planning, then the re-import's phases.
            if (it.progress.phase == null) ReimportProgressIndicator(it.progress) else BulkImportProgressIndicator(it.progress)
        }
        val current = references
        when {
            current == null -> {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }

            current.isEmpty() -> {
                Text(
                    if (strategies.orEmpty().none { it.config.fundingAttributeMatch != null }) {
                        "No import strategy references funding cards."
                    } else {
                        "No imported file references a card yet."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            else -> {
                val (assigned, unassigned) = current.partition { it.assigned }
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item(key = "unassigned-header") {
                        ImportSectionHeader(
                            title = "Unassigned",
                            count = unassigned.size,
                            expanded = unassignedExpanded,
                            onToggleExpanded = { unassignedExpanded = !unassignedExpanded },
                        )
                    }
                    if (unassignedExpanded && unassigned.isEmpty()) {
                        item(key = "unassigned-empty") {
                            Text("Every referenced card is assigned to an account.", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    items(if (unassignedExpanded) unassigned else emptyList(), key = { it.key() }) { reference ->
                        FundingReferenceCard(
                            reference = reference,
                            selected = pending[reference.key()] ?: reference.owner,
                            changed = reference.key() in pending,
                            enabled = !busy,
                            accountRepository = accountRepository,
                            categoryRepository = categoryRepository,
                            personRepository = personRepository,
                            onPick = { pick(reference, it) },
                            filePaths = filePaths,
                            onOpenFile = onOpenFile,
                        )
                    }
                    if (assigned.isNotEmpty()) {
                        item(key = "assigned-header") {
                            ImportSectionHeader(
                                title = "Assigned",
                                count = assigned.size,
                                expanded = assignedExpanded,
                                onToggleExpanded = { assignedExpanded = !assignedExpanded },
                            )
                        }
                        items(if (assignedExpanded) assigned else emptyList(), key = { it.key() }) { reference ->
                            FundingReferenceCard(
                                reference = reference,
                                selected = pending[reference.key()] ?: reference.owner,
                                changed = reference.key() in pending,
                                enabled = !busy,
                                accountRepository = accountRepository,
                                categoryRepository = categoryRepository,
                                personRepository = personRepository,
                                onPick = { pick(reference, it) },
                                filePaths = filePaths,
                                onOpenFile = onOpenFile,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun FundingReferenceCard(
    reference: FundingReference,
    selected: AccountId?,
    changed: Boolean,
    enabled: Boolean,
    accountRepository: AccountReadRepository,
    categoryRepository: CategoryReadRepository,
    personRepository: PersonReadRepository,
    onPick: (AccountId) -> Unit,
    filePaths: Map<CsvImportId, String>,
    onOpenFile: (CsvImportId, Map<String, Set<String>>) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (changed) "${reference.value} · changed" else reference.value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            val files = reference.files.size
            Text(
                "${reference.rowCount} transaction${if (reference.rowCount == 1) "" else "s"} in $files " +
                    "file${if (files == 1) "" else "s"} · ${reference.strategyNames.joinToString()}",
                style = MaterialTheme.typography.bodySmall,
            )
            FundingReferenceFiles(reference, filePaths, onOpenFile)
            if (reference.ambiguous) {
                Text(
                    "Several accounts claim this card, so none is used. Remove it from all but one in the account editor.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            val matchedByPattern = reference.assigned && !reference.ownedByToken
            if (matchedByPattern) {
                Text(
                    "A pattern on this account's attribute matches the card. Change it in the account editor.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            AccountPicker(
                selectedAccountId = selected,
                onAccountSelected = onPick,
                label = "Card belongs to",
                accountRepository = accountRepository,
                categoryRepository = categoryRepository,
                personRepository = personRepository,
                // Another claimant can't resolve an ambiguous card; it has to be removed from the others first.
                enabled = enabled && !reference.ambiguous && !matchedByPattern,
            )
        }
    }
}

/** The files that reference the card: one picked from a dropdown of their paths, and a button that opens it on those rows. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FundingReferenceFiles(
    reference: FundingReference,
    filePaths: Map<CsvImportId, String>,
    onOpenFile: (CsvImportId, Map<String, Set<String>>) -> Unit,
) {
    var selected by remember(reference.files) { mutableStateOf(reference.files.first()) }
    var expanded by remember { mutableStateOf(false) }

    fun FundingReferenceFile.label(): String =
        "${filePaths[importId] ?: importId.toString()} ($rowCount transaction${if (rowCount == 1) "" else "s"})"

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.weight(1f),
        ) {
            OutlinedTextField(
                value = selected.label(),
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                label = { Text("Source file") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                reference.files.forEach { file ->
                    DropdownMenuItem(
                        text = { Text(file.label()) },
                        onClick = {
                            selected = file
                            expanded = false
                        },
                    )
                }
            }
        }
        OutlinedButton(onClick = { onOpenFile(selected.importId, selected.columnFilter) }) { Text("Go to") }
    }
}
