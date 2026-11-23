package com.moneymanager.ui.screens.people

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.moneymanager.domain.model.Person
import com.moneymanager.domain.model.PersonAlias
import com.moneymanager.domain.model.PersonMerge
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.PersonAliasMutation
import com.moneymanager.importengineapi.PersonMergeRequest
import com.moneymanager.presenter.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.components.DestructiveConfirmDialog
import com.moneymanager.ui.foundation.LocalImportEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Merges [person] into a person the user picks: its accounts move across and its name becomes an alias of the survivor. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MergePersonDialog(
    person: Person,
    allPeople: List<Person>,
    onDismiss: () -> Unit,
) {
    val importEngine = LocalImportEngine.current
    var selected by remember { mutableStateOf<Person?>(null) }
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val others = allPeople.filter { it.id != person.id }
    val shown = if (query.isBlank()) others else others.filter { it.fullName.contains(query, ignoreCase = true) }

    DestructiveConfirmDialog(
        title = "Merge Person?",
        targetName = person.fullName,
        confirmLabel = "Merge",
        confirmEnabled = selected != null,
        failureMessage = "Failed to merge person",
        body = { isBusy ->
            Text(
                text =
                    "\"${person.fullName}\" will be deleted. Their accounts move to the person you choose, who also " +
                        "gains \"${person.fullName}\" as an alias, so future imports find them. You can undo this later.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (others.isEmpty()) {
                Text(
                    text = "No other people available.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { if (!isBusy) expanded = !expanded },
                ) {
                    OutlinedTextField(
                        value = if (expanded) query else (selected?.fullName ?: ""),
                        onValueChange = {
                            query = it
                            expanded = true
                        },
                        label = { Text("Merge into (surviving person)") },
                        placeholder = { Text("Type to search...") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
                        enabled = !isBusy,
                        singleLine = true,
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = {
                            expanded = false
                            query = ""
                        },
                    ) {
                        shown.forEach { candidate ->
                            DropdownMenuItem(
                                text = { Text(candidate.fullName) },
                                onClick = {
                                    selected = candidate
                                    expanded = false
                                    query = ""
                                },
                            )
                        }
                    }
                }
            }
        },
        onConfirm = {
            importEngine.import(
                ImportBatch.manualEdits(
                    personMerges = listOf(PersonMergeRequest(deletedId = person.id, survivingId = requireNotNull(selected).id)),
                ),
            )
            onDismiss()
        },
        onDismiss = onDismiss,
    )
}

/** Undoes [merge], recreating the merged-away person and moving back what the merge carried. */
@Composable
fun UnmergePersonDialog(
    merge: PersonMerge,
    onDismiss: () -> Unit,
) {
    val importEngine = LocalImportEngine.current
    DestructiveConfirmDialog(
        title = "Undo Merge?",
        targetName = merge.deletedName,
        confirmLabel = "Undo merge",
        failureMessage = "Failed to undo merge",
        confirmQuestion = "Are you sure you want to undo the merge of",
        consequence = "\"${merge.deletedName}\" will be recreated and get back the accounts the merge moved.",
        onConfirm = {
            importEngine.import(ImportBatch.manualEdits(personUnmerges = listOf(merge.id)))
            onDismiss()
        },
        onDismiss = onDismiss,
    )
}

/** Lists a person's aliases (other names imports match to them) and adds or removes them. */
@Composable
fun PersonAliasesDialog(
    person: Person,
    aliases: List<PersonAlias>,
    onDismiss: () -> Unit,
) {
    val importEngine = LocalImportEngine.current
    val scope = rememberSchemaAwareCoroutineScope()
    var newAlias by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    // Runs an alias write; a failure is shown in the dialog instead of escaping the scope.
    fun write(mutation: PersonAliasMutation) {
        error = null
        scope.launch {
            try {
                importEngine.import(ImportBatch(personAliasMutations = listOf(mutation)))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (expected: Exception) {
                error = "Failed to update aliases: ${expected.message}"
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Aliases of ${person.fullName}") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (aliases.isEmpty()) {
                    Text("No aliases.", style = MaterialTheme.typography.bodyMedium)
                }
                aliases.forEach { alias ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(alias.alias, modifier = Modifier.weight(1f))
                        TextButton(
                            onClick = { write(PersonAliasMutation.Remove(alias.alias)) },
                        ) { Text("Remove") }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newAlias,
                        onValueChange = { newAlias = it },
                        label = { Text("New alias") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        enabled = newAlias.isNotBlank(),
                        onClick = {
                            val alias = newAlias
                            newAlias = ""
                            write(PersonAliasMutation.Set(alias, person.id))
                        },
                    ) { Text("Add") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
