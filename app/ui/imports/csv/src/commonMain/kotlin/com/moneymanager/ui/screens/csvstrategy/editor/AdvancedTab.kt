package com.moneymanager.ui.screens.csvstrategy.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AttributeType
import com.moneymanager.domain.model.accountmapping.AccountMapping
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.rules.SettlementWindow
import com.moneymanager.ui.components.rules.SettlementWindowEditor
import com.moneymanager.ui.screens.accountmapping.AccountMappingsSection
import com.moneymanager.ui.screens.csvstrategy.getSampleValue

/**
 * Advanced tab: reconciliation, companion transaction rules, content matching, leg groups, and (in edit mode)
 * the account mappings scoped to this strategy. Global mappings are managed on the Account Mappings
 * screen instead.
 */
@Composable
internal fun AdvancedTab(
    state: CsvStrategyEditorState,
    csvColumns: List<CsvColumn>,
    firstRow: CsvRow?,
    enabled: Boolean,
    existingAttributeTypes: List<AttributeType>,
    isEditMode: Boolean,
    accountMappings: List<AccountMapping>,
    accounts: List<Account>,
    onEditAccountMapping: (AccountMapping) -> Unit,
    onDeleteAccountMapping: (AccountMapping) -> Unit,
    onAddAccountMapping: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Funding Reconciliation (Optional)", style = MaterialTheme.typography.titleSmall)
        Text(
            "Match a column against an account attribute to reconcile each row's hidden funding account " +
                "(e.g. Curve's last-4 → the underlying card), so the spend isn't double-counted.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        OptionalColumnDropdown(
            columns = csvColumns,
            selectedColumn = state.fundingMatchColumn,
            onColumnSelected = { state.fundingMatchColumn = it },
            label = "Funding column (e.g. card last 4)",
            sampleValue = getSampleValue(csvColumns, firstRow, state.fundingMatchColumn),
            enabled = enabled,
        )
        if (!state.fundingMatchColumn.isNullOrBlank()) {
            val existingTypeNames = existingAttributeTypes.map { it.name }.filter { it != state.fundingMatchAttributeTypeName }.take(5)
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = state.fundingMatchAttributeTypeName,
                onValueChange = { state.fundingMatchAttributeTypeName = it },
                label = { Text("Account attribute type *") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = enabled,
                isError = state.fundingMatchAttributeTypeName.isBlank(),
                supportingText = {
                    Text(
                        if (state.fundingMatchAttributeTypeName.isBlank()) {
                            "Required (e.g. card-last4)"
                        } else if (existingTypeNames.isNotEmpty()) {
                            "Existing types: ${existingTypeNames.joinToString(", ")}"
                        } else {
                            "Each account's \"${state.fundingMatchAttributeTypeName}\" attribute holds the regex tokens to match"
                        },
                    )
                },
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text("Cross-source Reconciliation (Optional)", style = MaterialTheme.typography.titleSmall)
        Text(
            "Import rows that fuzzy-match an existing transfer from another source (same accounts and " +
                "amount, within this window) as excluded+reconciled instead of double-counting. " +
                "Funding reconciliation above reuses this window.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = state.crossSourceReconcileWindow != null,
                onCheckedChange = { checked -> state.crossSourceReconcileWindow = if (checked) SettlementWindow.Elapsed(60) else null },
                enabled = enabled,
            )
            Text(
                "Enable cross-source reconciliation",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        state.crossSourceReconcileWindow?.let { window ->
            Spacer(modifier = Modifier.height(4.dp))
            SettlementWindowEditor(
                window = window,
                onWindowChange = { state.crossSourceReconcileWindow = it },
                label = "Reconcile window",
                enabled = enabled,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "A row whose counterparty is only a placeholder (a card statement's \"PAYMENT RECEIVED\") pairs with " +
                    "the record naming the far end within this wider window. Business days skip the weekends and " +
                    "holidays of the accounts' locations.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = state.placeholderReconcileWindow != null,
                    onCheckedChange = { checked ->
                        state.placeholderReconcileWindow =
                            if (checked) SettlementWindow.Elapsed(DEFAULT_PLACEHOLDER_WINDOW_SECONDS) else null
                    },
                    enabled = enabled,
                )
                Text(
                    "Custom placeholder window (default: 3 days)",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            state.placeholderReconcileWindow?.let { placeholder ->
                SettlementWindowEditor(
                    window = placeholder,
                    onWindowChange = { state.placeholderReconcileWindow = it },
                    label = "Placeholder window",
                    enabled = enabled,
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text("Reconciliation Source (Optional)", style = MaterialTheme.typography.titleSmall)
        Text(
            "Import this data only into shadow accounts (e.g. a Koinly export) so it never affects real " +
                "balances, and compare it against your real transactions on the Reconciliation tab. " +
                "Leave blank for an ordinary strategy.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = state.reconciliationSourceName,
            onValueChange = { state.reconciliationSourceName = it },
            label = { Text("Source name") },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.reconciliationSourceName.isNotBlank()) {
            OutlinedTextField(
                value = state.reconciliationLinkablePrefix,
                onValueChange = { state.reconciliationLinkablePrefix = it },
                label = { Text("Wallet account prefix (accounts to link to real ones; blank = all)") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text("Asset Aliases (Optional)", style = MaterialTheme.typography.titleSmall)
        Text(
            "Map this source's asset codes onto the ones Money Manager uses, e.g. KNCL=KNC, CELO=CGLD.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = state.assetAliasesText,
            onValueChange = { state.assetAliasesText = it },
            label = { Text("Aliases (FROM=TO, comma-separated)") },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(16.dp))
        Text("Companion Transaction Rules (Optional)", style = MaterialTheme.typography.titleSmall)
        Text(
            "Flag imported transfers that imply a manually entered companion transaction",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        CompanionTransactionRulesEditor(
            rules = state.companionTransactionRules,
            onRulesChanged = { state.companionTransactionRules = it },
            enabled = enabled,
        )

        Spacer(modifier = Modifier.height(16.dp))
        Text("Content Match Rules (Optional)", style = MaterialTheme.typography.titleSmall)
        Text(
            "Auto-detect this strategy from row content when the column set is fixed and can't " +
                "distinguish formats (e.g. QIF). No rules means this strategy is the fallback.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        ContentMatchRulesEditor(
            rules = state.contentMatchRules,
            onRulesChanged = { state.contentMatchRules = it },
            columns = csvColumns,
            firstRow = firstRow,
            enabled = enabled,
        )

        Spacer(modifier = Modifier.height(16.dp))
        Text("Multi-Row Movements (Optional)", style = MaterialTheme.typography.titleSmall)
        Text(
            "For sources that split one movement across several rows: a trade's fills (Binance, Bybit), or a " +
                "conversion's debited/credited rows (crypto.com \"Convert Dust\"), routed through a shared account " +
                "and linked as one event.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        LegGroupsEditor(
            rules = state.legGroups,
            onRulesChanged = { state.legGroups = it },
            columns = csvColumns,
            firstRow = firstRow,
            enabled = enabled,
        )

        if (isEditMode) {
            Spacer(modifier = Modifier.height(16.dp))
            Text("Account Mappings (this strategy)", style = MaterialTheme.typography.titleSmall)
            Text(
                "Route this strategy's CSV values to specific accounts. Global mappings live on the " +
                    "Account Mappings screen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            AccountMappingsSection(
                mappings = accountMappings,
                accounts = accounts,
                enabled = enabled,
                onEditMapping = onEditAccountMapping,
                onDeleteMapping = onDeleteAccountMapping,
                onAddMapping = onAddAccountMapping,
            )
        }
    }
}

private const val DEFAULT_PLACEHOLDER_WINDOW_SECONDS = 3 * 86_400L
