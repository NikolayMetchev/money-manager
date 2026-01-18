@file:OptIn(
    kotlin.time.ExperimentalTime::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.moneymanager.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.Person
import com.moneymanager.domain.repository.PersonRepository
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import kotlinx.coroutines.launch
import org.lighthousegames.logging.logging

private val logger = logging()

@Composable
fun DeletePersonConfirmationDialog(
    person: Person,
    accountCount: Int,
    personRepository: PersonRepository,
    onDismiss: () -> Unit,
) {
    var isDeleting by remember { mutableStateOf(false) }
    val scope = rememberSchemaAwareCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!isDeleting) onDismiss() },
        title = { Text("Delete Person") },
        text = {
            val message =
                buildString {
                    append("Are you sure you want to delete ${person.fullName}?")
                    if (accountCount > 0) {
                        append("\n\nThis person is associated with $accountCount account${if (accountCount != 1) "s" else ""}. ")
                        append("The ownership records will be removed.")
                    }
                }
            Text(message)
        },
        confirmButton = {
            TextButton(
                onClick = {
                    isDeleting = true
                    scope.launch {
                        try {
                            personRepository.deletePerson(person.id)
                            onDismiss()
                        } catch (expected: Exception) {
                            logger.error(expected) { "Failed to delete person: ${expected.message}" }
                            isDeleting = false
                        }
                    }
                },
                enabled = !isDeleting,
            ) {
                if (isDeleting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(
                        text = "Delete",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isDeleting,
            ) {
                Text("Cancel")
            }
        },
    )
}
