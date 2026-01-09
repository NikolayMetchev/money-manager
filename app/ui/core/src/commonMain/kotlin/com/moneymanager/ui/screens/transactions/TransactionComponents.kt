@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.moneymanager.ui.screens.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeType
import nl.jacobras.humanreadable.HumanReadable
import org.lighthousegames.logging.logging
import kotlin.time.Instant

internal val logger = logging()

internal val ACCOUNT_COLUMN_MIN_WIDTH = 100.dp

/**
 * Screen size classification for responsive layouts.
 * Based on Material Design 3 window size classes.
 */
enum class ScreenSizeClass {
    /** Compact: phones in portrait (width < 600dp) */
    Compact,

    /** Medium: tablets in portrait, foldables (600dp <= width < 840dp) */
    Medium,

    /** Expanded: tablets in landscape, desktops (width >= 840dp) */
    Expanded,
    ;

    companion object {
        fun fromWidth(width: Dp): ScreenSizeClass =
            when {
                width < 600.dp -> Compact
                width < 840.dp -> Medium
                else -> Expanded
            }
    }
}

internal fun resolveAccountName(
    accountId: AccountId,
    accounts: List<Account>,
): String = accounts.find { it.id == accountId }?.name ?: "#${accountId.id}"

internal fun formatTimeDiff(
    oldTimestamp: Instant,
    newTimestamp: Instant,
): String {
    val duration = newTimestamp - oldTimestamp
    val sign = if (duration.isPositive()) "+" else "-"
    return "$sign${HumanReadable.duration(duration.absoluteValue)}"
}

/**
 * A dropdown field for selecting or entering an attribute type name.
 * Shows existing types in a dropdown, with the option to type a new one.
 */
@Composable
internal fun AttributeTypeField(
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
                                        "\u2713",
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
