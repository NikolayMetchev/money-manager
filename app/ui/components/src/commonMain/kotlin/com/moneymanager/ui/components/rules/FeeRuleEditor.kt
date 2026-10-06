package com.moneymanager.ui.components.rules

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.rules.FeeRule
import com.moneymanager.domain.model.rules.ValueExpr
import com.moneymanager.domain.model.rules.isComplete

/**
 * Edits a [FeeRule] for any strategy: a blank amount means no fee (null). [movementOptions] offers what
 * only a fee booked as its own movement can use — a description, a gross amount it is carved out of, and
 * the one asset it is charged on — and is off for a trade, whose fee is a field of the trade. [pathField]
 * picks each path the way the owning editor picks every path (a CSV column, a JSON dot-path); a path
 * edited here replaces only the value's first path, keeping any fallbacks and extraction it already had.
 */
@Composable
fun FeeRuleEditor(
    fee: FeeRule?,
    onFeeChanged: (FeeRule?) -> Unit,
    pathField: ConditionPathField,
    enabled: Boolean,
    movementOptions: Boolean = true,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        pathField.Field("Fee amount (optional)", fee?.amount?.primaryPath.orEmpty(), { path ->
            val amount = fee?.amount.withPrimaryPath(path)
            onFeeChanged(amount?.let { fee?.copy(amount = it) ?: FeeRule(amount = it) })
        }, false)
        if (fee == null) return@Column
        pathField.Field(
            "Fee currency (optional; defaults to the movement's own)",
            fee.currency?.primaryPath.orEmpty(),
            { onFeeChanged(fee.copy(currency = fee.currency.withPrimaryPath(it))) },
            false,
        )
        if (movementOptions) {
            pathField.Field(
                "Fee description (optional)",
                fee.description?.primaryPath.orEmpty(),
                { onFeeChanged(fee.copy(description = fee.description.withPrimaryPath(it))) },
                false,
            )
            pathField.Field(
                "Only on the row in this asset (optional)",
                fee.chargedOnAsset?.primaryPath.orEmpty(),
                { onFeeChanged(fee.copy(chargedOnAsset = fee.chargedOnAsset.withPrimaryPath(it))) },
                false,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = fee.includedInAmount,
                    onCheckedChange = { onFeeChanged(fee.copy(includedInAmount = it)) },
                    enabled = enabled,
                )
                Text("The amount already includes the fee", modifier = Modifier.padding(start = 4.dp))
            }
        }
        ConditionsEditor(
            title = "Only charge the fee when all of these hold",
            conditions = fee.conditions,
            onConditionsChanged = { onFeeChanged(fee.copy(conditions = it)) },
            pathField = pathField,
            enabled = enabled,
        )
    }
}

/** This value reading [path] first instead, or null for a blank path. */
internal fun ValueExpr?.withPrimaryPath(path: String): ValueExpr? =
    when {
        path.isBlank() -> null
        this == null -> ValueExpr(listOf(path))
        else -> copy(paths = listOf(path) + paths.drop(1))
    }

/** Whether [this] fee has every input it needs. */
fun FeeRule?.isComplete(): Boolean = this == null || conditions.all { it.isComplete() }
