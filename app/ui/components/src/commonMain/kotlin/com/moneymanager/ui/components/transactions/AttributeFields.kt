@file:OptIn(ExperimentalMaterial3Api::class)

package com.moneymanager.ui.components.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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

@Composable
fun EditableAttributesSection(
    editableAttributes: Map<Long, EditableAttribute>,
    existingAttributeTypes: List<AttributeType>,
    isSaving: Boolean,
    onAttributesChange: (Map<Long, EditableAttribute>) -> Unit,
    onAddAttribute: () -> Unit,
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

        editableAttributes.forEach { (id, attribute) ->
            val (typeName, value) = attribute
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AttributeTypeField(
                    value = typeName,
                    onValueChange = { newTypeName ->
                        onAttributesChange(editableAttributes + (id to attribute.copy(typeName = newTypeName)))
                    },
                    existingTypes = existingAttributeTypes,
                    enabled = !isSaving,
                    modifier = Modifier.weight(0.4f),
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { newValue ->
                        onAttributesChange(editableAttributes + (id to attribute.copy(value = newValue)))
                    },
                    label = { Text("Value") },
                    modifier = Modifier.weight(0.5f),
                    singleLine = true,
                    enabled = !isSaving,
                )
                IconButton(
                    onClick = { onAttributesChange(editableAttributes - id) },
                    enabled = !isSaving,
                ) {
                    Text(
                        text = "X",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }

        TextButton(
            onClick = onAddAttribute,
            enabled = !isSaving,
        ) {
            Text("+ Add Attribute")
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
