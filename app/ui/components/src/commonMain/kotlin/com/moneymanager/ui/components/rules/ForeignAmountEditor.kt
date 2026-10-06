package com.moneymanager.ui.components.rules

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.moneymanager.domain.model.rules.ForeignAmount

/**
 * Edits a [ForeignAmount] for any strategy. It exists only once both paths are set, so a half-entered
 * pair is held here rather than lost while the other path is being chosen. [pathField] picks each path the
 * way the owning editor picks every path (a CSV column, a JSON dot-path); a path edited here replaces only
 * the value's first path, keeping any fallbacks and extraction it already had.
 */
@Composable
fun ForeignAmountEditor(
    foreignAmount: ForeignAmount?,
    onForeignAmountChanged: (ForeignAmount?) -> Unit,
    pathField: ConditionPathField,
) {
    var amount by remember(foreignAmount) { mutableStateOf(foreignAmount?.amount) }
    var currency by remember(foreignAmount) { mutableStateOf(foreignAmount?.currency) }

    fun emit() {
        val a = amount
        val c = currency
        onForeignAmountChanged(if (a != null && c != null) ForeignAmount(amount = a, currency = c) else null)
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        pathField.Field("Foreign amount (optional)", amount?.primaryPath.orEmpty(), { path ->
            amount = amount.withPrimaryPath(path)
            emit()
        }, false)
        pathField.Field("Foreign currency", currency?.primaryPath.orEmpty(), { path ->
            currency = currency.withPrimaryPath(path)
            emit()
        }, amount != null && currency == null)
    }
}
