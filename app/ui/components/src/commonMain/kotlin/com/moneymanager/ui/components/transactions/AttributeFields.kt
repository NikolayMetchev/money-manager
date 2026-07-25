@file:OptIn(ExperimentalMaterial3Api::class)

package com.moneymanager.ui.components.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.AttributeType
import kotlin.uuid.Uuid

/**
 * Editor for an entity's attributes.
 *
 * When [grouping] is on (account editor), attributes sharing a non-empty [EditableAttribute.groupKey]
 * render together in a card and can be dissolved with Ungroup; ungrouped attributes gain a checkbox so
 * two or more can be bound into a new group. A group key is a freshly-minted opaque [Uuid], so a
 * user-formed group can never collide with an importer's key. When [grouping] is off (person/transfer
 * editors) the rendering is a flat list exactly as before, and any loaded group keys are carried through
 * untouched.
 *
 * [onAddAttribute] receives the group key the new (blank) row should belong to — `""` for an ungrouped
 * row, or an existing group's key for "+ Add to group".
 */
@Composable
fun EditableAttributesSection(
    editableAttributes: Map<Long, EditableAttribute>,
    existingAttributeTypes: List<AttributeType>,
    isSaving: Boolean,
    onAttributesChange: (Map<Long, EditableAttribute>) -> Unit,
    onAddAttribute: (groupKey: String) -> Unit,
    grouping: Boolean = false,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Attributes",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!grouping) {
            editableAttributes.forEach { (id, attribute) ->
                AttributeRow(id, attribute, existingAttributeTypes, isSaving, editableAttributes, onAttributesChange)
            }
            TextButton(onClick = { onAddAttribute("") }, enabled = !isSaving) {
                Text("+ Add Attribute")
            }
            return@Column
        }

        // Selection is transient view state, owned here like the type field's own dropdown state.
        var selectedIds by remember { mutableStateOf(emptySet<Long>()) }
        val ungrouped = editableAttributes.filterValues { it.groupKey.isEmpty() }
        val groups = editableAttributes.entries.filter { it.value.groupKey.isNotEmpty() }.groupBy { it.value.groupKey }

        ungrouped.forEach { (id, attribute) ->
            AttributeRow(
                id,
                attribute,
                existingAttributeTypes,
                isSaving,
                editableAttributes,
                onAttributesChange,
                selected = id in selectedIds,
                onSelectedChange = { checked -> selectedIds = if (checked) selectedIds + id else selectedIds - id },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = {
                    val newKey = Uuid.random().toString()
                    onAttributesChange(
                        editableAttributes.mapValues { (id, a) -> if (id in selectedIds) a.copy(groupKey = newKey) else a },
                    )
                    selectedIds = emptySet()
                },
                // Grouping one attribute is meaningless; require at least two.
                enabled = !isSaving && selectedIds.size >= 2,
            ) {
                Text("Group selected")
            }
            TextButton(onClick = { onAddAttribute("") }, enabled = !isSaving) {
                Text("+ Add Attribute")
            }
        }

        groups.forEach { (groupKey, members) ->
            AttributeGroupCard(
                groupKey = groupKey,
                members = members.map { it.key to it.value },
                existingAttributeTypes = existingAttributeTypes,
                isSaving = isSaving,
                editableAttributes = editableAttributes,
                onAttributesChange = onAttributesChange,
                onAddAttribute = onAddAttribute,
            )
        }
    }
}

/** One attribute editor row: an optional leading checkbox, a type field, a value field, and a delete button. */
@Composable
private fun AttributeRow(
    id: Long,
    attribute: EditableAttribute,
    existingAttributeTypes: List<AttributeType>,
    isSaving: Boolean,
    editableAttributes: Map<Long, EditableAttribute>,
    onAttributesChange: (Map<Long, EditableAttribute>) -> Unit,
    selected: Boolean? = null,
    onSelectedChange: (Boolean) -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected != null) {
            Checkbox(checked = selected, onCheckedChange = onSelectedChange, enabled = !isSaving)
        }
        AttributeTypeField(
            value = attribute.typeName,
            onValueChange = { newTypeName ->
                onAttributesChange(editableAttributes + (id to attribute.copy(typeName = newTypeName)))
            },
            existingTypes = existingAttributeTypes,
            enabled = !isSaving,
            modifier = Modifier.weight(0.4f),
        )
        OutlinedTextField(
            value = attribute.value,
            onValueChange = { newValue ->
                onAttributesChange(editableAttributes + (id to attribute.copy(value = newValue)))
            },
            label = { Text("Value") },
            modifier = Modifier.weight(0.5f),
            singleLine = true,
            enabled = !isSaving,
        )
        IconButton(onClick = { onAttributesChange(editableAttributes - id) }, enabled = !isSaving) {
            Text(
                text = "X",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

/** A bordered card holding one group's attributes, with Ungroup and "+ Add to group" actions. */
@Composable
private fun AttributeGroupCard(
    groupKey: String,
    members: List<Pair<Long, EditableAttribute>>,
    existingAttributeTypes: List<AttributeType>,
    isSaving: Boolean,
    editableAttributes: Map<Long, EditableAttribute>,
    onAttributesChange: (Map<Long, EditableAttribute>) -> Unit,
    onAddAttribute: (groupKey: String) -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Grouped",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Ungroup is a deliberate action: for a two-identity account, ungrouping both identities
                // leaves their sort/account numbers unpairable and the account no longer matches on import.
                TextButton(
                    onClick = {
                        onAttributesChange(
                            editableAttributes.mapValues { (_, a) -> if (a.groupKey == groupKey) a.copy(groupKey = "") else a },
                        )
                    },
                    enabled = !isSaving,
                ) {
                    Text("Ungroup")
                }
            }
            members.forEach { (id, attribute) ->
                AttributeRow(id, attribute, existingAttributeTypes, isSaving, editableAttributes, onAttributesChange)
            }
            TextButton(onClick = { onAddAttribute(groupKey) }, enabled = !isSaving) {
                Text("+ Add to group")
            }
        }
    }
}

/**
 * A dropdown field for selecting or entering an attribute type name.
 * Shows existing types in a dropdown, with the option to type a new one.
 */
@Composable
fun AttributeTypeField(
    value: String,
    onValueChange: (String) -> Unit,
    existingTypes: List<AttributeType>,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var textValue by remember(value) { mutableStateOf(value) }

    // Filter suggestions based on current text
    val suggestions =
        remember(textValue, existingTypes) {
            if (textValue.isBlank()) {
                existingTypes.map { it.name }
            } else {
                existingTypes
                    .map { it.name }
                    .filter { it.contains(textValue, ignoreCase = true) }
            }
        }

    ExposedDropdownMenuBox(
        expanded = expanded && suggestions.isNotEmpty(),
        onExpandedChange = { if (enabled) expanded = !expanded },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = textValue,
            onValueChange = { newValue ->
                textValue = newValue
                onValueChange(newValue)
                expanded = true
            },
            label = { Text("Type") },
            trailingIcon = {
                if (existingTypes.isNotEmpty()) {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                }
            },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
            enabled = enabled,
            singleLine = true,
        )

        if (suggestions.isNotEmpty()) {
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                suggestions.forEach { typeName ->
                    DropdownMenuItem(
                        text = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(typeName)
                                if (typeName == value) {
                                    Text(
                                        "✓",
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        },
                        onClick = {
                            textValue = typeName
                            onValueChange(typeName)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}
