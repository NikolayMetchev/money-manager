@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.moneymanager.ui.components.rules

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.rules.AccountNaming
import com.moneymanager.domain.model.rules.AccountSide
import com.moneymanager.domain.model.rules.ConditionOp
import com.moneymanager.domain.model.rules.FeeRule
import com.moneymanager.domain.model.rules.ForeignAmount
import com.moneymanager.domain.model.rules.LegRole
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.RuleValue
import com.moneymanager.domain.model.rules.StrategyRule
import com.moneymanager.domain.model.rules.ValueExpr
import com.moneymanager.domain.model.rules.isComplete

/** What a rule list runs over, which decides the actions its editor offers. */
enum class RuleContext {
    CSV_ROW,
    API_TRANSACTION,
    API_TRADE,
    API_ACCOUNT,
}

/** One kind of [RuleAction] the editor can add: its label, where it makes sense, and a fresh instance. */
enum class RuleActionKind(
    val label: String,
    val contexts: Set<RuleContext>,
    val create: () -> RuleAction,
) {
    ACCOUNT("Name an account", RuleContext.entries.toSet(), {
        RuleAction.Account(AccountSide.COUNTERPARTY, AccountNaming(value = ValueExpr(listOf(""))))
    }),
    DIRECTION("Set the direction", setOf(RuleContext.CSV_ROW, RuleContext.API_TRANSACTION), { RuleAction.SetDirection(incoming = true) }),
    FLIP("Flip source and target", setOf(RuleContext.CSV_ROW), { RuleAction.Flip }),
    SWAP("Swap two values", RuleContext.entries.toSet(), { RuleAction.SwapValues("", "") }),
    SET("Set a value", RuleContext.entries.toSet(), { RuleAction.SetValue("", RuleValue.constant("")) }),
    SKIP("Skip the record", RuleContext.entries.toSet(), { RuleAction.Skip }),
    EXCLUDE("Exclude from balances", setOf(RuleContext.CSV_ROW, RuleContext.API_TRANSACTION), { RuleAction.Exclude() }),
    ATTRIBUTE("Add an attribute", setOf(RuleContext.CSV_ROW, RuleContext.API_TRANSACTION, RuleContext.API_ACCOUNT), {
        RuleAction.Attribute("", RuleValue.read(""))
    }),
    EXTERNAL_ID("Set the external id", RuleContext.entries.toSet(), { RuleAction.ExternalId(RuleValue.read("")) }),
    PERSON("Counterparty is a person", setOf(RuleContext.API_TRANSACTION), { RuleAction.CounterpartyIsPerson }),
    IGNORE_ID("Ignore the counterparty id", setOf(RuleContext.API_TRANSACTION), { RuleAction.IgnoreCounterpartyId }),
    FEE("Book a fee", setOf(RuleContext.CSV_ROW, RuleContext.API_TRANSACTION, RuleContext.API_TRADE), {
        RuleAction.Fee(FeeRule(amount = ValueExpr(listOf(""))))
    }),
    FOREIGN("Foreign amount", setOf(RuleContext.CSV_ROW, RuleContext.API_TRANSACTION), {
        RuleAction.Foreign(ForeignAmount(ValueExpr(listOf("")), ValueExpr(listOf(""))))
    }),
    CONDUIT("Run through a conduit", setOf(RuleContext.CSV_ROW), { RuleAction.ThroughConduit("") }),
    TRADE_SIDE("Set the trade side", setOf(RuleContext.API_TRADE), { RuleAction.TradeSide(buy = true) }),
    LEG("Leg of a group", setOf(RuleContext.CSV_ROW), { RuleAction.Leg("", LegRole.Debit) }),
    LEDGER_LEG("Ledger trade leg", setOf(RuleContext.API_TRANSACTION), { RuleAction.LedgerTradeLeg }),
    ;

    companion object {
        fun of(action: RuleAction): RuleActionKind =
            when (action) {
                is RuleAction.Account -> ACCOUNT
                is RuleAction.SetDirection -> DIRECTION
                RuleAction.Flip -> FLIP
                is RuleAction.SwapValues -> SWAP
                is RuleAction.SetValue -> SET
                RuleAction.Skip -> SKIP
                is RuleAction.Exclude -> EXCLUDE
                is RuleAction.Attribute -> ATTRIBUTE
                is RuleAction.ExternalId -> EXTERNAL_ID
                RuleAction.CounterpartyIsPerson -> PERSON
                RuleAction.IgnoreCounterpartyId -> IGNORE_ID
                is RuleAction.Fee -> FEE
                is RuleAction.Foreign -> FOREIGN
                is RuleAction.ThroughConduit -> CONDUIT
                is RuleAction.TradeSide -> TRADE_SIDE
                is RuleAction.Leg -> LEG
                RuleAction.LedgerTradeLeg -> LEDGER_LEG
            }

        fun availableIn(context: RuleContext): List<RuleActionKind> = entries.filter { context in it.contexts }
    }
}

/** The account sides an account action can name in [context]. */
fun RuleContext.accountSides(): List<AccountSide> =
    when (this) {
        RuleContext.CSV_ROW -> listOf(AccountSide.SOURCE, AccountSide.TARGET, AccountSide.INTERMEDIATE)
        RuleContext.API_TRANSACTION, RuleContext.API_TRADE -> listOf(AccountSide.COUNTERPARTY)
        RuleContext.API_ACCOUNT -> listOf(AccountSide.OWN)
    }

/** Whether every rule has the inputs its conditions and actions need. */
fun List<StrategyRule>.isComplete(): Boolean =
    all { rule ->
        rule.conditions.all { it.isComplete() } && rule.actions.all { it.isComplete() }
    }

private fun RuleValue.isComplete(): Boolean =
    when (this) {
        is RuleValue.Read -> expr.primaryPath.isNotBlank()
        is RuleValue.Template -> true
    }

private fun RuleAction.isComplete(): Boolean =
    when (this) {
        is RuleAction.Account -> naming.value.primaryPath.isNotBlank() && naming.name.isNotBlank()

        is RuleAction.SwapValues -> first.isNotBlank() && second.isNotBlank()

        is RuleAction.SetValue -> path.isNotBlank() && value.isComplete()

        is RuleAction.Exclude -> reason?.isComplete() ?: true

        is RuleAction.Attribute -> typeName.isNotBlank() && value.isComplete()

        is RuleAction.ExternalId -> value.isComplete()

        is RuleAction.Fee -> fee.amount.primaryPath.isNotBlank()

        is RuleAction.Foreign -> foreignAmount.amount.primaryPath.isNotBlank() && foreignAmount.currency.primaryPath.isNotBlank()

        is RuleAction.ThroughConduit -> accountName.isNotBlank()

        is RuleAction.Leg -> group.isNotBlank() && (role as? LegRole.BySign)?.path?.isNotBlank() ?: true

        RuleAction.Flip, is RuleAction.SetDirection, RuleAction.Skip, RuleAction.CounterpartyIsPerson,
        RuleAction.IgnoreCounterpartyId, is RuleAction.TradeSide, RuleAction.LedgerTradeLeg,
        -> true
    }

/**
 * The one if/then rule editor every strategy editor uses: an ordered list of rules, each a set of
 * conditions ("If") and the actions applied when they all hold ("Then"). Order matters — rules run top to
 * bottom and the first claim of a single-valued outcome wins — so rules can be moved up and down.
 *
 * A rule card shows its one-line [summary] until it's opened for editing, so a long list (Binance's
 * endpoints carry dozens of rules) reads at a glance. A rule that still needs inputs, or has no actions
 * yet, stays open.
 */
@Composable
fun StrategyRulesEditor(
    rules: List<StrategyRule>,
    onRulesChanged: (List<StrategyRule>) -> Unit,
    context: RuleContext,
    pathField: ConditionPathField,
    enabled: Boolean,
    ops: List<ConditionOp> = if (context == RuleContext.CSV_ROW) FLAT_CONDITION_OPS else ConditionOp.entries,
) {
    // Indices of the rules opened by hand; they move with their rules.
    var opened by remember { mutableStateOf(emptySet<Int>()) }
    // A rule being written stays open once it's complete, so its first action doesn't snap the card shut.
    LaunchedEffect(rules) { opened = opened + rules.indices.filter { rules[it].needsEditing() } }
    Column(modifier = Modifier.fillMaxWidth()) {
        rules.forEachIndexed { index, rule ->
            RuleCard(
                index = index,
                count = rules.size,
                rule = rule,
                isOpen = index in opened || rule.needsEditing(),
                onOpenChanged = { open -> opened = if (open) opened + index else opened - index },
                onRuleChanged = { updated -> onRulesChanged(rules.mapIndexed { i, r -> if (i == index) updated else r }) },
                onMove = { delta ->
                    val to = index + delta
                    if (to in rules.indices) {
                        opened = opened.swapped(index, to)
                        onRulesChanged(rules.moved(index, to))
                    }
                },
                onDuplicate = {
                    opened = opened.map { if (it > index) it + 1 else it }.toSet() + (index + 1)
                    onRulesChanged(rules.take(index + 1) + rule + rules.drop(index + 1))
                },
                onRemove = {
                    opened = opened.filter { it != index }.map { if (it > index) it - 1 else it }.toSet()
                    onRulesChanged(rules.filterIndexed { i, _ -> i != index })
                },
                context = context,
                pathField = pathField,
                enabled = enabled,
                ops = ops,
            )
        }
        TextButton(onClick = { onRulesChanged(rules + StrategyRule(actions = emptyList())) }, enabled = enabled) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
            Text("Add rule")
        }
    }
}

/** A rule that does nothing yet, or lacks an input, is being written: collapsing it would hide that. */
private fun StrategyRule.needsEditing(): Boolean = actions.isEmpty() || !listOf(this).isComplete()

private fun Set<Int>.swapped(
    a: Int,
    b: Int,
): Set<Int> =
    map {
        when (it) {
            a -> b
            b -> a
            else -> it
        }
    }.toSet()

private fun <T> List<T>.moved(
    from: Int,
    to: Int,
): List<T> {
    if (to !in indices) return this
    val result = toMutableList()
    result.add(to, result.removeAt(from))
    return result
}

@Composable
private fun RuleCard(
    index: Int,
    count: Int,
    rule: StrategyRule,
    isOpen: Boolean,
    onOpenChanged: (Boolean) -> Unit,
    onRuleChanged: (StrategyRule) -> Unit,
    onMove: (Int) -> Unit,
    onDuplicate: () -> Unit,
    onRemove: () -> Unit,
    context: RuleContext,
    pathField: ConditionPathField,
    enabled: Boolean,
    ops: List<ConditionOp>,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1}.", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 8.dp))
                if (isOpen) {
                    OutlinedTextField(
                        value = rule.name.orEmpty(),
                        onValueChange = { onRuleChanged(rule.copy(name = it.ifBlank { null })) },
                        label = { Text("Name (optional)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        enabled = enabled,
                    )
                } else {
                    Text(
                        rule.name?.let { "$it: ${rule.summary()}" } ?: rule.summary(),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).clickable { onOpenChanged(true) },
                    )
                }
                IconButton(onClick = { onOpenChanged(!isOpen) }, enabled = !rule.needsEditing()) {
                    if (isOpen) {
                        Icon(Icons.Filled.Done, contentDescription = "Collapse rule")
                    } else {
                        Icon(Icons.Filled.Edit, contentDescription = "Expand rule")
                    }
                }
                IconButton(onClick = { onMove(-1) }, enabled = enabled && index > 0) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move rule up")
                }
                IconButton(onClick = { onMove(1) }, enabled = enabled && index < count - 1) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move rule down")
                }
                TextButton(onClick = onDuplicate, enabled = enabled) { Text("Duplicate") }
                IconButton(onClick = onRemove, enabled = enabled) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove rule")
                }
            }
            if (!isOpen) return@Column
            Spacer(modifier = Modifier.height(4.dp))
            ConditionsEditor(
                title = "If all of these hold (none: every record)",
                conditions = rule.conditions,
                onConditionsChanged = { onRuleChanged(rule.copy(conditions = it)) },
                pathField = pathField,
                enabled = enabled,
                ops = ops,
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Text("Then", style = MaterialTheme.typography.bodyMedium)
            rule.actions.forEachIndexed { actionIndex, action ->
                ActionCard(
                    action = action,
                    onActionChanged = { updated ->
                        onRuleChanged(rule.copy(actions = rule.actions.mapIndexed { i, a -> if (i == actionIndex) updated else a }))
                    },
                    onRemove = { onRuleChanged(rule.copy(actions = rule.actions.filterIndexed { i, _ -> i != actionIndex })) },
                    context = context,
                    pathField = pathField,
                    enabled = enabled,
                )
            }
            AddActionButton(context = context, enabled = enabled, onAdd = { onRuleChanged(rule.copy(actions = rule.actions + it)) })
        }
    }
}

@Composable
private fun AddActionButton(
    context: RuleContext,
    enabled: Boolean,
    onAdd: (RuleAction) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        TextButton(onClick = { expanded = true }, enabled = enabled) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
            Text("Add action")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            RuleActionKind.availableIn(context).forEach { kind ->
                DropdownMenuItem(
                    text = { Text(kind.label) },
                    onClick = {
                        onAdd(kind.create().forContext(context))
                        expanded = false
                    },
                )
            }
        }
    }
}

/** An added account action names the context's first side rather than one the context doesn't offer. */
private fun RuleAction.forContext(context: RuleContext): RuleAction =
    if (this is RuleAction.Account) copy(side = context.accountSides().first()) else this

@Composable
private fun ActionCard(
    action: RuleAction,
    onActionChanged: (RuleAction) -> Unit,
    onRemove: () -> Unit,
    context: RuleContext,
    pathField: ConditionPathField,
    enabled: Boolean,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(RuleActionKind.of(action).label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onRemove, enabled = enabled) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove action")
                }
            }
            ActionFields(action, onActionChanged, context, pathField, enabled)
        }
    }
}

@Suppress("CyclomaticComplexMethod", "LongMethod") // One editor per action kind.
@Composable
private fun ActionFields(
    action: RuleAction,
    onActionChanged: (RuleAction) -> Unit,
    context: RuleContext,
    pathField: ConditionPathField,
    enabled: Boolean,
) {
    when (action) {
        is RuleAction.Account -> {
            ChoiceDropdown("Account", action.side, context.accountSides(), { it.label() }, enabled) {
                onActionChanged(action.copy(side = it))
            }
            AccountNamingEditor(
                action.naming,
                { onActionChanged(action.copy(naming = it)) },
                pathField,
                enabled,
                builtInOption = context == RuleContext.API_TRANSACTION,
            )
        }

        is RuleAction.SetDirection -> {
            ChoiceDropdown("Money moves", action.incoming, listOf(true, false), { if (it) "In" else "Out" }, enabled) {
                onActionChanged(action.copy(incoming = it))
            }
        }

        is RuleAction.SwapValues -> {
            pathField.Field("First", action.first, { onActionChanged(action.copy(first = it)) }, action.first.isBlank())
            pathField.Field("Second", action.second, { onActionChanged(action.copy(second = it)) }, action.second.isBlank())
        }

        is RuleAction.SetValue -> {
            pathField.Field("Value to set", action.path, { onActionChanged(action.copy(path = it)) }, action.path.isBlank())
            RuleValueEditor("New value", action.value, { onActionChanged(action.copy(value = it)) }, pathField, enabled)
        }

        is RuleAction.Exclude -> {
            CheckRow("Record a reason", action.reason != null, enabled) {
                onActionChanged(action.copy(reason = if (it) RuleValue.read("") else null))
            }
            action.reason?.let { reason ->
                RuleValueEditor("Reason", reason, { onActionChanged(action.copy(reason = it)) }, pathField, enabled)
            }
        }

        is RuleAction.Attribute -> {
            TextField("Attribute type", action.typeName, enabled, isError = action.typeName.isBlank()) {
                onActionChanged(action.copy(typeName = it))
            }
            RuleValueEditor("Attribute value", action.value, { onActionChanged(action.copy(value = it)) }, pathField, enabled)
            CheckRow("Identifies the record across imports", action.uniqueIdentifier, enabled) {
                onActionChanged(action.copy(uniqueIdentifier = it))
            }
        }

        is RuleAction.ExternalId -> {
            RuleValueEditor("External id", action.value, { onActionChanged(action.copy(value = it)) }, pathField, enabled)
        }

        is RuleAction.Fee -> {
            FeeRuleEditor(
                fee = action.fee,
                onFeeChanged = { fee -> fee?.let { onActionChanged(action.copy(fee = it)) } },
                pathField = pathField,
                enabled = enabled,
                movementOptions = context != RuleContext.API_TRADE,
                showConditions = false,
            )
        }

        is RuleAction.Foreign -> {
            ForeignAmountEditor(
                foreignAmount = action.foreignAmount,
                onForeignAmountChanged = { foreign -> foreign?.let { onActionChanged(action.copy(foreignAmount = it)) } },
                pathField = pathField,
            )
        }

        is RuleAction.ThroughConduit -> {
            TextField("Conduit account", action.accountName, enabled, isError = action.accountName.isBlank()) {
                onActionChanged(action.copy(accountName = it))
            }
        }

        is RuleAction.TradeSide -> {
            ChoiceDropdown("Side", action.buy, listOf(true, false), { if (it) "Buy" else "Sell" }, enabled) {
                onActionChanged(action.copy(buy = it))
            }
        }

        is RuleAction.Leg -> {
            TextField("Group", action.group, enabled, isError = action.group.isBlank()) { onActionChanged(action.copy(group = it)) }
            LegRoleEditor(action.role, { onActionChanged(action.copy(role = it)) }, pathField, enabled)
        }

        RuleAction.Flip, RuleAction.Skip, RuleAction.CounterpartyIsPerson, RuleAction.IgnoreCounterpartyId,
        RuleAction.LedgerTradeLeg,
        -> {}
    }
}

private fun AccountSide.label(): String =
    when (this) {
        AccountSide.SOURCE -> "Source (money out)"
        AccountSide.TARGET -> "Target (money in)"
        AccountSide.COUNTERPARTY -> "Counterparty"
        AccountSide.OWN -> "Own account"
        AccountSide.INTERMEDIATE -> "Intermediate (group)"
    }

/**
 * Edits an [AccountNaming]: what it reads, when it applies, and the name it gives. [builtInOption] offers
 * [AccountNaming.builtIn], which only an API transaction's counterparty honours.
 */
@Composable
fun AccountNamingEditor(
    naming: AccountNaming,
    onNamingChanged: (AccountNaming) -> Unit,
    pathField: ConditionPathField,
    enabled: Boolean,
    builtInOption: Boolean = false,
) {
    ValueExprEditor("Read value from", naming.value, { onNamingChanged(naming.copy(value = it)) }, pathField, enabled)
    CheckRow("Trim the value", naming.trim, enabled) { onNamingChanged(naming.copy(trim = it)) }
    TextField("Only when the value matches (regex, optional)", naming.pattern.orEmpty(), enabled) {
        onNamingChanged(naming.copy(pattern = it.ifBlank { null }))
    }
    TextField($$"Account name ({value}, $1…)", naming.name, enabled, isError = naming.name.isBlank()) {
        onNamingChanged(naming.copy(name = it))
    }
    TextField("Fallback name (optional)", naming.fallbackName.orEmpty(), enabled) {
        onNamingChanged(naming.copy(fallbackName = it.ifBlank { null }))
    }
    TextField("Match an account attribute type (optional)", naming.attributeTypeName.orEmpty(), enabled) {
        onNamingChanged(naming.copy(attributeTypeName = it.ifBlank { null }))
    }
    CheckRow("The account belongs to a person", naming.person, enabled) { onNamingChanged(naming.copy(person = it)) }
    if (naming.person) {
        TextField("Person name (optional)", naming.personName.orEmpty(), enabled) {
            onNamingChanged(naming.copy(personName = it.ifBlank { null }))
        }
    }
    CheckRow("Placeholder: reconcile against the real record", naming.unidentified, enabled) {
        onNamingChanged(naming.copy(unidentified = it))
    }
    if (builtInOption || naming.builtIn) {
        CheckRow("Built-in counterparty: every record naming it shares one account", naming.builtIn, enabled) {
            onNamingChanged(naming.copy(builtIn = it))
        }
    }
}

/** Edits a [ValueExpr]: its paths, tried in order (comma-separated after the first), and its extraction. */
@Composable
fun ValueExprEditor(
    label: String,
    expr: ValueExpr,
    onExprChanged: (ValueExpr) -> Unit,
    pathField: ConditionPathField,
    enabled: Boolean,
) {
    pathField.Field(
        label,
        expr.primaryPath,
        { onExprChanged(expr.copy(paths = listOf(it) + expr.paths.drop(1))) },
        expr.primaryPath.isBlank(),
    )
    TextField("Fallbacks, in order (comma-separated, optional)", expr.paths.drop(1).joinToString(", "), enabled) { text ->
        val fallbacks = text.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        onExprChanged(expr.copy(paths = listOf(expr.primaryPath) + fallbacks))
    }
    ExtractionEditor("Extract with a regex", expr.extraction, { onExprChanged(expr.copy(extraction = it)) }, enabled)
}

/** Edits a [RuleValue]: read from the record, or written out as a `{path}` template. */
@Composable
fun RuleValueEditor(
    label: String,
    value: RuleValue,
    onValueChanged: (RuleValue) -> Unit,
    pathField: ConditionPathField,
    enabled: Boolean,
) {
    val isRead = value is RuleValue.Read
    ChoiceDropdown("$label from", isRead, listOf(true, false), {
        if (it) "A value of the record" else "Text ({path} inserts a value)"
    }, enabled) {
        if (it != isRead) onValueChanged(if (it) RuleValue.read("") else RuleValue.constant(""))
    }
    when (value) {
        is RuleValue.Read -> ValueExprEditor(label, value.expr, { onValueChanged(RuleValue.Read(it)) }, pathField, enabled)
        is RuleValue.Template -> TextField(label, value.template, enabled) { onValueChanged(RuleValue.Template(it)) }
    }
}

@Composable
private fun LegRoleEditor(
    role: LegRole,
    onRoleChanged: (LegRole) -> Unit,
    pathField: ConditionPathField,
    enabled: Boolean,
) {
    val choices = listOf("Debit", "Credit", "By the sign of a value")
    val selected =
        when (role) {
            LegRole.Debit -> choices[0]
            LegRole.Credit -> choices[1]
            is LegRole.BySign -> choices[2]
        }
    ChoiceDropdown("Leg side", selected, choices, { it }, enabled) {
        if (it == selected) return@ChoiceDropdown
        onRoleChanged(
            when (it) {
                choices[0] -> LegRole.Debit
                choices[1] -> LegRole.Credit
                else -> LegRole.BySign("")
            },
        )
    }
    if (role is LegRole.BySign) {
        pathField.Field("Signed value", role.path, { onRoleChanged(LegRole.BySign(it)) }, role.path.isBlank())
    }
}

@Composable
private fun TextField(
    label: String,
    value: String,
    enabled: Boolean,
    isError: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        enabled = enabled,
        isError = isError,
    )
}

@Composable
private fun CheckRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(label, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun <T> ChoiceDropdown(
    label: String,
    selected: T,
    choices: List<T>,
    choiceLabel: (T) -> String,
    enabled: Boolean,
    onSelected: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = !expanded }) {
        OutlinedTextField(
            value = choiceLabel(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            enabled = enabled,
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choiceLabel(choice)) },
                    onClick = {
                        onSelected(choice)
                        expanded = false
                    },
                )
            }
        }
    }
}
