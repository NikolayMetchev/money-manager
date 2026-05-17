package com.moneymanager.ui.audit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.ApiRequestId
import com.moneymanager.domain.model.ApiSessionId
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.EntitySource
import com.moneymanager.domain.model.SourceType

@Composable
fun FieldValueRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    labelWidth: Dp = 100.dp,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(labelWidth),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
        )
    }
}

@Composable
fun FieldChangeRow(
    label: String,
    oldValue: String,
    newValue: String,
    suffix: String? = null,
    labelWidth: Dp = 100.dp,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(labelWidth),
        )
        Text(
            text = oldValue,
            style =
                MaterialTheme.typography.bodyMedium.copy(
                    textDecoration = TextDecoration.LineThrough,
                ),
            color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
        )
        Text(
            text = "\u2192",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = newValue,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        if (suffix != null) {
            Text(
                text = suffix,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun AuditSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun NoVisibleChangesText() {
    Text(
        text = "No visible changes recorded",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun DeletedFinalValuesLabel(errorColor: androidx.compose.ui.graphics.Color) {
    Text(
        text = "Deleted (final values):",
        style = MaterialTheme.typography.labelMedium,
        color = errorColor.copy(alpha = 0.8f),
    )
}

@Composable
fun SourceInfoSection(
    source: EntitySource?,
    labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    labelWidth: Dp = 100.dp,
    onApiSourceClick: ((ApiSessionId, ApiRequestId, String) -> Unit)? = null,
) {
    Column(
        modifier = Modifier.padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "Source:",
            style = MaterialTheme.typography.labelMedium,
            color = labelColor,
        )

        if (source == null) {
            Text(
                text = "Source data missing",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            when (source.sourceType) {
                SourceType.MANUAL -> {
                    val deviceInfo = source.deviceInfo
                    when (deviceInfo) {
                        is DeviceInfo.Jvm -> {
                            FieldValueRow("Origin", "Manual (Desktop)", labelWidth = labelWidth)
                            DeviceInfoRows(deviceInfo, labelWidth)
                        }
                        is DeviceInfo.Android -> {
                            FieldValueRow("Origin", "Manual (Android)", labelWidth = labelWidth)
                            DeviceInfoRows(deviceInfo, labelWidth)
                        }
                        null -> {
                            FieldValueRow("Origin", "Manual", labelWidth = labelWidth)
                        }
                    }
                }
                SourceType.CSV_IMPORT -> {
                    val deviceInfo = source.deviceInfo
                    FieldValueRow("Origin", "CSV Import", labelWidth = labelWidth)
                    DeviceInfoRows(deviceInfo, labelWidth)
                }
                SourceType.SAMPLE_GENERATOR -> {
                    when (val deviceInfo = source.deviceInfo) {
                        is DeviceInfo.Jvm -> {
                            FieldValueRow("Origin", "Sample Generator (Desktop)", labelWidth = labelWidth)
                            DeviceInfoRows(deviceInfo, labelWidth)
                        }
                        is DeviceInfo.Android -> {
                            FieldValueRow("Origin", "Sample Generator (Android)", labelWidth = labelWidth)
                            DeviceInfoRows(deviceInfo, labelWidth)
                        }
                        null -> {
                            FieldValueRow("Origin", "Sample Generator", labelWidth = labelWidth)
                        }
                    }
                }
                SourceType.SYSTEM -> {
                    FieldValueRow("Origin", "System", labelWidth = labelWidth)
                }
                SourceType.API -> {
                    val deviceInfo = source.deviceInfo
                    val apiSource = source.apiSource
                    if (apiSource != null && onApiSourceClick != null) {
                        ApiSourceLinkRow(
                            sessionId = apiSource.sessionId,
                            requestId = apiSource.requestId,
                            jsonPath = apiSource.jsonPath.value,
                            onApiSourceClick = onApiSourceClick,
                            labelWidth = labelWidth,
                        )
                    } else {
                        FieldValueRow("Origin", "API Import", labelWidth = labelWidth)
                    }
                    DeviceInfoRows(deviceInfo, labelWidth)
                }
            }
        }
    }
}

@Composable
private fun DeviceInfoRows(
    deviceInfo: DeviceInfo?,
    labelWidth: Dp,
) {
    when (deviceInfo) {
        is DeviceInfo.Jvm -> {
            FieldValueRow("Machine", deviceInfo.machineName, labelWidth = labelWidth)
            FieldValueRow("OS", deviceInfo.osName, labelWidth = labelWidth)
        }
        is DeviceInfo.Android -> {
            FieldValueRow("Device", "${deviceInfo.deviceMake} ${deviceInfo.deviceModel}", labelWidth = labelWidth)
        }
        null -> {}
    }
}

@Composable
private fun ApiSourceLinkRow(
    sessionId: ApiSessionId,
    requestId: ApiRequestId,
    jsonPath: String,
    onApiSourceClick: (ApiSessionId, ApiRequestId, String) -> Unit,
    labelWidth: Dp = 100.dp,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Origin:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(labelWidth),
        )
        TextButton(
            onClick = { onApiSourceClick(sessionId, requestId, jsonPath) },
            contentPadding = PaddingValues(0.dp),
        ) {
            Text("API Import")
        }
    }
}
