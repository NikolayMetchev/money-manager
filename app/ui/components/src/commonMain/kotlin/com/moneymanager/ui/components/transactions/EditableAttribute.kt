package com.moneymanager.ui.components.transactions

/**
 * One row in the attribute editor.
 *
 * [groupKey] is carried through the editor untouched. It ties this attribute to the others in the same
 * logical tuple (a sort code and an account number sharing a group key are one bank identity, and an
 * account may own several). The editor never shows or changes it, but it must survive the round trip:
 * rebuilding a row without its group would rewrite every identity into the ungrouped slot on save,
 * collapsing them into one and destroying all but the first.
 */
data class EditableAttribute(
    val typeName: String,
    val value: String,
    val groupKey: String = "",
)
