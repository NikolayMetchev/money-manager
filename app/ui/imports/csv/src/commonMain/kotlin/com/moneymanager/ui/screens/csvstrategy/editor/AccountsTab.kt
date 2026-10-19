package com.moneymanager.ui.screens.csvstrategy.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.ui.components.AccountPicker

/**
 * Accounts tab: whether the source account is one fixed account or named per row by the rules. The target
 * account is always named by the rules (Rules tab).
 */
@Composable
internal fun AccountsTab(
    state: CsvStrategyEditorState,
    enabled: Boolean,
    accountRepository: AccountReadRepository,
    categoryRepository: CategoryReadRepository,
    personRepository: PersonReadRepository,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Source Account (Optional)", style = MaterialTheme.typography.titleSmall)
        Text(
            "Can also be chosen each time you apply this strategy",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = state.sourceAccountMode == SourceAccountMode.FIXED_ACCOUNT,
                onClick = { state.sourceAccountMode = SourceAccountMode.FIXED_ACCOUNT },
                enabled = enabled,
            )
            Text("Fixed Account", modifier = Modifier.padding(end = 16.dp))
            RadioButton(
                selected = state.sourceAccountMode == SourceAccountMode.RULES,
                onClick = { state.sourceAccountMode = SourceAccountMode.RULES },
                enabled = enabled,
            )
            Text("From the row (rules)")
        }
        when (state.sourceAccountMode) {
            SourceAccountMode.FIXED_ACCOUNT ->
                AccountPicker(
                    selectedAccountId = state.selectedAccountId,
                    onAccountSelected = { state.selectedAccountId = it },
                    label = "Select Account",
                    accountRepository = accountRepository,
                    categoryRepository = categoryRepository,
                    personRepository = personRepository,
                    enabled = enabled,
                )
            SourceAccountMode.RULES ->
                Text(
                    "Named by the rules' \"Source\" account actions (Rules tab)",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.accountsHasError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text("Target Account", style = MaterialTheme.typography.titleSmall)
        Text(
            "Named for each row by the rules' \"Target\" account actions (Rules tab)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
