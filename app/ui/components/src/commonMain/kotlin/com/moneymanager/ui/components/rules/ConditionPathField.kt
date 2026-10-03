package com.moneymanager.ui.components.rules

import androidx.compose.runtime.Composable

/**
 * How a strategy editor lets the user pick a [com.moneymanager.domain.model.rules.Condition.path]: a
 * CSV editor offers its columns, an API editor a JSON dot-path field. [label] is the field's label, [isError] flags a missing required value.
 */
fun interface ConditionPathField {
    @Composable
    fun Field(
        label: String,
        value: String,
        onValueChange: (String) -> Unit,
        isError: Boolean,
    )
}
