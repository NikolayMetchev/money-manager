package com.moneymanager.ui.components

import androidx.compose.runtime.Composable
import com.moneymanager.domain.model.Person
import com.moneymanager.domain.model.Source
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportOperation
import com.moneymanager.importengineapi.ImportPersonIntent
import com.moneymanager.importengineapi.LocalPersonKey
import com.moneymanager.ui.foundation.LocalImportEngine

@Composable
fun DeletePersonConfirmationDialog(
    person: Person,
    accountCount: Int,
    onDismiss: () -> Unit,
) {
    val importEngine = LocalImportEngine.current

    DestructiveConfirmDialog(
        title = "Delete Person",
        targetName = person.fullName,
        consequence =
            if (accountCount > 0) {
                "This person is associated with $accountCount account${if (accountCount != 1) "s" else ""}. " +
                    "The ownership records will be removed."
            } else {
                null
            },
        failureMessage = "Failed to delete person",
        onConfirm = {
            importEngine.import(
                ImportBatch.manualEdits(
                    people =
                        listOf(
                            ImportPersonIntent(
                                key = LocalPersonKey("delete"),
                                source = Source.Manual,
                                operation = ImportOperation.DELETE,
                                existingId = person.id,
                            ),
                        ),
                ),
            )
            onDismiss()
        },
        onDismiss = onDismiss,
    )
}
