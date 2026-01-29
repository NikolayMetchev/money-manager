@file:OptIn(
    kotlin.time.ExperimentalTime::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.moneymanager.ui.screens

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.compose.scrollbar.VerticalScrollbarForLazyList
import com.moneymanager.database.sql.EntitySourceQueries
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.Person
import com.moneymanager.domain.model.PersonAccountOwnership
import com.moneymanager.domain.repository.PersonAccountOwnershipRepository
import com.moneymanager.domain.repository.PersonRepository
import com.moneymanager.ui.components.DeletePersonConfirmationDialog
import com.moneymanager.ui.components.EditPersonDialog
import com.moneymanager.ui.error.collectAsStateWithSchemaErrorHandling

@Composable
fun PeopleScreen(
    personRepository: PersonRepository,
    personAccountOwnershipRepository: PersonAccountOwnershipRepository,
    entitySourceQueries: EntitySourceQueries,
    deviceId: DeviceId,
    onAuditClick: (Person) -> Unit = {},
) {
    val people by personRepository.getAllPeople()
        .collectAsStateWithSchemaErrorHandling(initial = emptyList())
    var showCreateDialog by remember { mutableStateOf(false) }
    var personToEdit by remember { mutableStateOf<Person?>(null) }
    var personToDelete by remember { mutableStateOf<Person?>(null) }

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

        if (people.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No people yet. Add your first person!",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            val lazyListState = rememberLazyListState()
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = lazyListState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(people) { person ->
                        val ownerships by personAccountOwnershipRepository.getOwnershipsByPerson(person.id)
                            .collectAsStateWithSchemaErrorHandling(initial = emptyList())
                        PersonCard(
                            person = person,
                            ownerships = ownerships,
                            onEdit = { personToEdit = person },
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
            personRepository = personRepository,
            entitySourceQueries = entitySourceQueries,
            deviceId = deviceId,
            onDismiss = { showCreateDialog = false },
        )
    }

    val currentPersonToEdit = personToEdit
    if (currentPersonToEdit != null) {
        EditPersonDialog(
            personToEdit = currentPersonToEdit,
            personRepository = personRepository,
            entitySourceQueries = entitySourceQueries,
            deviceId = deviceId,
            onDismiss = { personToEdit = null },
        )
    }

    val currentPersonToDelete = personToDelete
    if (currentPersonToDelete != null) {
        val ownerships by personAccountOwnershipRepository.getOwnershipsByPerson(currentPersonToDelete.id)
            .collectAsStateWithSchemaErrorHandling(initial = emptyList())
        DeletePersonConfirmationDialog(
            person = currentPersonToDelete,
            accountCount = ownerships.size,
            personRepository = personRepository,
            onDismiss = { personToDelete = null },
        )
    }
}

@Composable
private fun PersonCard(
    person: Person,
    ownerships: List<PersonAccountOwnership>,
    onEdit: () -> Unit,
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
                if (ownerships.isNotEmpty()) {
                    Text(
                        text = "${ownerships.size} account${if (ownerships.size != 1) "s" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row {
                IconButton(onClick = onEdit) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit ${person.fullName}",
                    )
                }
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
