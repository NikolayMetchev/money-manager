package com.moneymanager.ui.screens.people

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.moneymanager.compose.scrollbar.VerticalScrollbarForLazyList
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.Person
import com.moneymanager.domain.model.PersonAlias
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.model.PersonMerge
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.AttributeTypeReadRepository
import com.moneymanager.domain.repository.PersonAccountOwnershipReadRepository
import com.moneymanager.domain.repository.PersonAttributeReadRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.presenter.error.rememberFlowAsStateWithSchemaErrorHandling
import com.moneymanager.ui.components.DeletePersonConfirmationDialog
import com.moneymanager.ui.components.EditPersonDialog
import com.moneymanager.ui.foundation.rememberPersistedState
import kotlinx.serialization.builtins.serializer

@Composable
fun PeopleScreen(
    personRepository: PersonReadRepository,
    personAttributeRepository: PersonAttributeReadRepository,
    personAccountOwnershipRepository: PersonAccountOwnershipReadRepository,
    attributeTypeRepository: AttributeTypeReadRepository,
    accountRepository: AccountReadRepository,
    scrollToPersonId: PersonId? = null,
    onAuditClick: (Person) -> Unit = {},
    onAccountClick: (Account) -> Unit = {},
) {
    val people by rememberFlowAsStateWithSchemaErrorHandling(initial = emptyList()) {
        personRepository.getAllPeople()
    }
    var nameFilter by rememberPersistedState("people.nameFilter", String.serializer()) { "" }
    var accountFilter by rememberPersistedState("people.accountFilter", String.serializer()) { "" }
    var scrollTarget by remember(scrollToPersonId) { mutableStateOf(scrollToPersonId) }
    var personForAccounts by remember { mutableStateOf<Person?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var personToEdit by remember { mutableStateOf<Person?>(null) }
    var personToDelete by remember { mutableStateOf<Person?>(null) }
    var personToMerge by remember { mutableStateOf<Person?>(null) }
    var personForAliases by remember { mutableStateOf<Person?>(null) }
    var mergeToUndo by remember { mutableStateOf<PersonMerge?>(null) }
    val allAliases by rememberFlowAsStateWithSchemaErrorHandling(initial = emptyList()) {
        personRepository.getAllAliases()
    }
    val reversibleMerges by rememberFlowAsStateWithSchemaErrorHandling(initial = emptyList()) {
        personRepository.getReversibleMerges()
    }

    val accounts by rememberFlowAsStateWithSchemaErrorHandling(initial = emptyList()) {
        accountRepository.getAllAccounts()
    }
    val ownerships by rememberFlowAsStateWithSchemaErrorHandling(initial = emptyList()) {
        personAccountOwnershipRepository.getAllOwnerships()
    }
    val accountsByPerson =
        remember(accounts, ownerships) {
            val accountsById = accounts.associateBy { it.id }
            ownerships.groupBy { it.personId }.mapValues { (_, links) ->
                links.mapNotNull { accountsById[it.accountId] }.sortedBy { it.name.lowercase() }
            }
        }
    val aliasesByPerson = remember(allAliases) { allAliases.groupBy { it.personId } }
    val visiblePeople =
        remember(people, accountsByPerson, aliasesByPerson, nameFilter, accountFilter, scrollTarget) {
            val name = nameFilter.trim()
            val account = accountFilter.trim()
            people.filter { person ->
                val matchesName =
                    person.fullName.contains(name, ignoreCase = true) ||
                        aliasesByPerson[person.id].orEmpty().any { it.alias.contains(name, ignoreCase = true) }
                val matchesAccount =
                    account.isEmpty() || accountsByPerson[person.id].orEmpty().any { it.name.contains(account, ignoreCase = true) }
                person.id == scrollTarget || (matchesName && matchesAccount)
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
            Text(
                text = "People",
                style = MaterialTheme.typography.headlineMedium,
            )
            TextButton(onClick = { showCreateDialog = true }) {
                Text("+ Add Person")
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = nameFilter,
                onValueChange = {
                    scrollTarget = null
                    nameFilter = it
                },
                modifier = Modifier.weight(1f),
                label = { Text("Name") },
                singleLine = true,
            )
            OutlinedTextField(
                value = accountFilter,
                onValueChange = {
                    scrollTarget = null
                    accountFilter = it
                },
                modifier = Modifier.weight(1f),
                label = { Text("Account") },
                singleLine = true,
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        if (visiblePeople.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (people.isEmpty()) "No people yet. Add your first person!" else "No people match these filters.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            val lazyListState = rememberLazyListState()
            LaunchedEffect(scrollTarget, visiblePeople) {
                val targetIndex = scrollTarget?.let { targetId -> visiblePeople.indexOfFirst { it.id == targetId } } ?: -1
                if (targetIndex >= 0) {
                    lazyListState.animateScrollToItem(targetIndex)
                }
            }
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = lazyListState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(visiblePeople, key = { it.id.id }) { person ->
                        PersonCard(
                            person = person,
                            accountCount = accountsByPerson[person.id].orEmpty().size,
                            aliases = aliasesByPerson[person.id].orEmpty(),
                            merges = reversibleMerges.filter { it.survivingPersonId == person.id },
                            onShowAccounts = { personForAccounts = person },
                            onEdit = { personToEdit = person },
                            onMerge = { personToMerge = person },
                            onAliases = { personForAliases = person },
                            onUndoMerge = { mergeToUndo = it },
                            onAudit = { onAuditClick(person) },
                            onDelete = { personToDelete = person },
                        )
                    }
                }
                VerticalScrollbarForLazyList(
                    lazyListState = lazyListState,
                    modifier = Modifier,
                )
            }
        }
    }

    if (showCreateDialog) {
        EditPersonDialog(
            personToEdit = null,
            onDismiss = { showCreateDialog = false },
            personAttributeRepository = personAttributeRepository,
            attributeTypeRepository = attributeTypeRepository,
        )
    }

    val currentPersonToEdit = personToEdit
    if (currentPersonToEdit != null) {
        EditPersonDialog(
            personToEdit = currentPersonToEdit,
            onDismiss = { personToEdit = null },
            personAttributeRepository = personAttributeRepository,
            attributeTypeRepository = attributeTypeRepository,
        )
    }

    personForAccounts?.let { person ->
        AlertDialog(
            onDismissRequest = { personForAccounts = null },
            title = { Text("Accounts owned by ${person.fullName}") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    accountsByPerson[person.id].orEmpty().forEach { account ->
                        TextButton(
                            onClick = {
                                personForAccounts = null
                                onAccountClick(account)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(account.name, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { personForAccounts = null }) { Text("Close") }
            },
        )
    }

    personToMerge?.let { MergePersonDialog(it, people) { personToMerge = null } }
    mergeToUndo?.let { UnmergePersonDialog(it) { mergeToUndo = null } }
    personForAliases?.let { target ->
        PersonAliasesDialog(target, allAliases.filter { it.personId == target.id }) { personForAliases = null }
    }

    val currentPersonToDelete = personToDelete
    if (currentPersonToDelete != null) {
        DeletePersonConfirmationDialog(
            person = currentPersonToDelete,
            accountCount = accountsByPerson[currentPersonToDelete.id].orEmpty().size,
            onDismiss = { personToDelete = null },
        )
    }
}

@Composable
private fun PersonCard(
    person: Person,
    accountCount: Int,
    aliases: List<PersonAlias>,
    merges: List<PersonMerge>,
    onShowAccounts: () -> Unit,
    onEdit: () -> Unit,
    onMerge: () -> Unit,
    onAliases: () -> Unit,
    onUndoMerge: (PersonMerge) -> Unit,
    onAudit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onEdit),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = person.fullName,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (aliases.isNotEmpty()) {
                    Text(
                        text = "Also known as: ${aliases.joinToString { it.alias }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                merges.forEach { merge ->
                    TextButton(onClick = { onUndoMerge(merge) }) { Text("Undo merge of ${merge.deletedName}") }
                }
                if (accountCount > 0) {
                    TextButton(onClick = onShowAccounts) {
                        Text("$accountCount account${if (accountCount == 1) "" else "s"}")
                    }
                }
            }
            Row {
                IconButton(onClick = onEdit) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit ${person.fullName}",
                    )
                }
                TextButton(onClick = onAliases) { Text("Aliases") }
                TextButton(onClick = onMerge) { Text("Merge") }
                IconButton(onClick = onAudit) {
                    Text(
                        text = "📋",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete ${person.fullName}",
                    )
                }
            }
        }
    }
}
