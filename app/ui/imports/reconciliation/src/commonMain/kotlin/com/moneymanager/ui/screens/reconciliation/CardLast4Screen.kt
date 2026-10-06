package com.moneymanager.ui.screens.reconciliation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.moneymanager.csvimporter.AttributeAccountMatcher
import com.moneymanager.csvimporter.UnmatchedFundingReference
import com.moneymanager.csvimporter.addAttributeToken
import com.moneymanager.csvimporter.findUnmatchedFundingReferences
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.repository.AccountAttributeReadRepository
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.domain.repository.CsvImportReadRepository
import com.moneymanager.domain.repository.CsvImportStrategyReadRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.importengineapi.getOrCreateAttributeType
import com.moneymanager.importengineapi.setAccountAttributeValue
import com.moneymanager.ui.components.AccountPicker
import com.moneymanager.ui.error.rememberFlowAsStateWithSchemaErrorHandling
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.foundation.LocalImportEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Funding references (card last-4 digits and the like) that imported rows carry but no account owns yet.
 * Assigning one adds it to the picked account's attribute set — an account may own several cards — and
 * then [rerunFundingReconciles] re-runs the funding reconcile for the files that reference it.
 * Which column and attribute type count is whatever a CSV strategy's funding match says, so this
 * screen knows nothing about any particular source.
 */
@Composable
fun CardLast4Screen(
    csvImportRepository: CsvImportReadRepository,
    csvImportStrategyRepository: CsvImportStrategyReadRepository,
    accountAttributeRepository: AccountAttributeReadRepository,
    accountRepository: AccountReadRepository,
    categoryRepository: CategoryReadRepository,
    personRepository: PersonReadRepository,
    rerunFundingReconciles: suspend (List<CsvImportId>) -> Int,
) {
    val importEngine = LocalImportEngine.current
    val scope = rememberSchemaAwareCoroutineScope()
    val imports by rememberFlowAsStateWithSchemaErrorHandling(initial = null) { csvImportRepository.getAllImports() }
    val strategies by rememberFlowAsStateWithSchemaErrorHandling(initial = null) { csvImportStrategyRepository.getAllStrategies() }
    val attributes by rememberFlowAsStateWithSchemaErrorHandling(initial = null) { accountAttributeRepository.getAll() }

    var references by remember { mutableStateOf<List<UnmatchedFundingReference>?>(null) }
    var busyValue by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(imports, strategies, attributes) {
        val currentImports = imports ?: return@LaunchedEffect
        val currentStrategies = strategies ?: return@LaunchedEffect
        val currentAttributes = attributes ?: return@LaunchedEffect
        references =
            findUnmatchedFundingReferences(
                imports = currentImports,
                strategies = currentStrategies,
                csvImportRepository = csvImportRepository,
                attributeAccountMatchers = AttributeAccountMatcher.registry(currentAttributes),
            )
    }

    fun assign(
        reference: UnmatchedFundingReference,
        accountId: AccountId,
    ) {
        busyValue = reference.value
        message = null
        scope.launch {
            runCatchingUnlessCancelled {
                val typeId = importEngine.getOrCreateAttributeType(reference.attributeTypeName)
                val existing =
                    accountAttributeRepository.getByAccount(accountId).first().firstOrNull {
                        it.attributeType.id == typeId && it.groupKey.isEmpty()
                    }
                importEngine.setAccountAttributeValue(
                    accountId = accountId,
                    typeId = typeId,
                    value = addAttributeToken(existing?.value, reference.value),
                    existingAttributeId = existing?.id,
                )
                rerunFundingReconciles(reference.imports)
            }.onSuccess { reconciled ->
                error = null
                message = "Assigned ${reference.value}; reconciled $reconciled transaction${if (reconciled == 1) "" else "s"}."
            }.onFailure { error = "Assigning ${reference.value} failed: ${it.message}" }
            busyValue = null
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Cards that imports reference but no account owns yet. Pick the account each one belongs to; an account " +
                "can own several cards.",
            style = MaterialTheme.typography.bodyMedium,
        )
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        val current = references
        when {
            current == null ->
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            current.isEmpty() ->
                Text(
                    if (strategies.orEmpty().none { it.config.fundingAttributeMatch != null }) {
                        "No import strategy references funding cards."
                    } else {
                        "Every referenced card is assigned to an account."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            else ->
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(current, key = { "${it.attributeTypeName}:${it.value}" }) { reference ->
                        FundingReferenceCard(
                            reference = reference,
                            busy = busyValue == reference.value,
                            enabled = busyValue == null,
                            accountRepository = accountRepository,
                            categoryRepository = categoryRepository,
                            personRepository = personRepository,
                            onAssign = { assign(reference, it) },
                        )
                    }
                }
        }
    }
}

@Composable
private fun FundingReferenceCard(
    reference: UnmatchedFundingReference,
    busy: Boolean,
    enabled: Boolean,
    accountRepository: AccountReadRepository,
    categoryRepository: CategoryReadRepository,
    personRepository: PersonReadRepository,
    onAssign: (AccountId) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(reference.value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (busy) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
            val files = reference.imports.size
            Text(
                "${reference.rowCount} transaction${if (reference.rowCount == 1) "" else "s"} in $files " +
                    "file${if (files == 1) "" else "s"} · ${reference.strategyNames.joinToString()}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (reference.ambiguous) {
                Text(
                    "Several accounts claim this card, so none is used. Remove it from all but one in the account editor.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            AccountPicker(
                selectedAccountId = null,
                onAccountSelected = onAssign,
                label = "Card belongs to",
                accountRepository = accountRepository,
                categoryRepository = categoryRepository,
                personRepository = personRepository,
                // Another claimant can't resolve an ambiguous card; it has to be removed from the others first.
                enabled = enabled && !reference.ambiguous,
            )
        }
    }
}
