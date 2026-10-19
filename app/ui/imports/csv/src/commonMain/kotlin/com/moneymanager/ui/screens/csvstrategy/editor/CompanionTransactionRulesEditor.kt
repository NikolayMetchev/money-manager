package com.moneymanager.ui.screens.csvstrategy.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.csvstrategy.CompanionTransactionRule

/**
 * Editor for the strategy's list of [CompanionTransactionRule]s.
 */
@Composable
internal fun CompanionTransactionRulesEditor(
    rules: List<CompanionTransactionRule>,
    onRulesChanged: (List<CompanionTransactionRule>) -> Unit,
    enabled: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        rules.forEachIndexed { index, rule ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    ),
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    EditorCardHeader(
                        title = "Rule ${index + 1}",
                        removeContentDescription = "Remove rule",
                        onRemove = { onRulesChanged(rules.filterIndexed { i, _ -> i != index }) },
                        enabled = enabled,
                    )

                    fun updateRule(transform: (CompanionTransactionRule) -> CompanionTransactionRule) {
                        onRulesChanged(rules.mapIndexed { i, r -> if (i == index) transform(r) else r })
                    }
                    OutlinedTextField(
                        value = rule.name,
                        onValueChange = { newValue -> updateRule { it.copy(name = newValue) } },
                        label = { Text("Name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = enabled,
                        isError = rule.name.isBlank(),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = rule.matchAttributeName,
                        onValueChange = { newValue -> updateRule { it.copy(matchAttributeName = newValue) } },
                        label = { Text("Match attribute name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = enabled,
                        isError = rule.matchAttributeName.isBlank(),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = rule.matchValuePattern,
                        onValueChange = { newValue -> updateRule { it.copy(matchValuePattern = newValue) } },
                        label = { Text("Match value pattern (SQL LIKE)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = enabled,
                        isError = rule.matchValuePattern.isBlank(),
                        supportingText = { Text("e.g., ACCRUAL_CHARGE-%") },
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = rule.linkAttributeName,
                        onValueChange = { newValue -> updateRule { it.copy(linkAttributeName = newValue) } },
                        label = { Text("Link attribute name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = enabled,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = rule.companionDescription,
                        onValueChange = { newValue -> updateRule { it.copy(companionDescription = newValue) } },
                        label = { Text("Companion description") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = enabled,
                    )
                }
            }
        }
        TextButton(
            onClick = {
                onRulesChanged(
                    rules +
                        CompanionTransactionRule(
                            name = "",
                            matchAttributeName = "",
                            matchValuePattern = "",
                            linkAttributeName = "",
                            companionDescription = "",
                        ),
                )
            },
            enabled = enabled,
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
            Text("Add Rule")
        }
    }
}
