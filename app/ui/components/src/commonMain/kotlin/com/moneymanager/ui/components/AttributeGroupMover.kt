package com.moneymanager.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.domain.repository.PersonReadRepository

/**
 * Moves an attribute group (a sort code and account number, say) to another account and re-imports the files and API
 * sessions that name the old one. It runs as a background task, so it outlives the dialog that asked for it.
 */
fun interface AttributeGroupMover {
    fun move(
        from: AccountId,
        groupKey: String,
        to: AccountId,
    )
}

/** Provided where the front end can re-import; without it the account editor offers no move. */
val LocalAttributeGroupMover = staticCompositionLocalOf<AttributeGroupMover?> { null }

/** Asks which account [from]'s attribute group should move to, and says what the move will re-import. */
@Composable
fun MoveAttributeGroupDialog(
    from: Account,
    accountRepository: AccountReadRepository,
    categoryRepository: CategoryReadRepository,
    personRepository: PersonReadRepository,
    onConfirm: (AccountId) -> Unit,
    onDismiss: () -> Unit,
) {
    var target by remember { mutableStateOf<AccountId?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move to another account") },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "The group's attributes leave \"${from.name}\" and join the account you pick. Every file and API session " +
                        "with transactions on \"${from.name}\" is then re-imported, so rows that name the account by these " +
                        "attributes land on the new one.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                AccountPicker(
                    selectedAccountId = target,
                    onAccountSelected = { target = it },
                    label = "Move to",
                    accountRepository = accountRepository,
                    categoryRepository = categoryRepository,
                    personRepository = personRepository,
                    excludeAccountId = from.id,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { target?.let(onConfirm) }, enabled = target != null) { Text("Move and re-import") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
