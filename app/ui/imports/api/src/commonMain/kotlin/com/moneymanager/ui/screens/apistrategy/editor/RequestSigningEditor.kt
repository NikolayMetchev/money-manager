package com.moneymanager.ui.screens.apistrategy.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moneymanager.domain.model.apistrategy.ApiRequestSigningConfig
import com.moneymanager.domain.model.apistrategy.BodyFormat
import com.moneymanager.domain.model.apistrategy.FieldPlacement
import com.moneymanager.domain.model.apistrategy.NonceFormat
import com.moneymanager.domain.model.apistrategy.NonceSpec
import com.moneymanager.domain.model.apistrategy.ParamStringFormat
import com.moneymanager.domain.model.apistrategy.RequestIdFormat
import com.moneymanager.domain.model.apistrategy.RequestIdSpec
import com.moneymanager.domain.model.apistrategy.SecretEncoding
import com.moneymanager.domain.model.apistrategy.SigFieldLocation
import com.moneymanager.domain.model.apistrategy.SigPart
import com.moneymanager.domain.model.apistrategy.SignatureEncoding
import com.moneymanager.domain.model.apistrategy.SigningAlgorithm

/** The [SigPart] variants, used to drive a variant picker in [SigPartListEditor]. */
private enum class SigPartKind(
    val label: String,
) {
    LITERAL("Literal text"),
    METHOD("API method name"),
    REQUEST_ID("Request id"),
    API_KEY("API key"),
    NONCE("Nonce"),
    PATH("Request path"),
    QUERY_STRING("Query string"),
    BODY("Request body"),
    PARAM_STRING("Param string"),
    SHA256("SHA-256 of parts"),
}

private fun SigPart.kind(): SigPartKind =
    when (this) {
        is SigPart.Literal -> SigPartKind.LITERAL
        SigPart.Method -> SigPartKind.METHOD
        SigPart.RequestId -> SigPartKind.REQUEST_ID
        SigPart.ApiKey -> SigPartKind.API_KEY
        SigPart.Nonce -> SigPartKind.NONCE
        SigPart.Path -> SigPartKind.PATH
        SigPart.QueryString -> SigPartKind.QUERY_STRING
        SigPart.Body -> SigPartKind.BODY
        is SigPart.ParamString -> SigPartKind.PARAM_STRING
        is SigPart.Sha256 -> SigPartKind.SHA256
    }

private fun SigPartKind.defaultPart(): SigPart =
    when (this) {
        SigPartKind.LITERAL -> SigPart.Literal("")
        SigPartKind.METHOD -> SigPart.Method
        SigPartKind.REQUEST_ID -> SigPart.RequestId
        SigPartKind.API_KEY -> SigPart.ApiKey
        SigPartKind.NONCE -> SigPart.Nonce
        SigPartKind.PATH -> SigPart.Path
        SigPartKind.QUERY_STRING -> SigPart.QueryString
        SigPartKind.BODY -> SigPart.Body
        SigPartKind.PARAM_STRING -> SigPart.ParamString()
        SigPartKind.SHA256 -> SigPart.Sha256(emptyList())
    }

/**
 * Editor for a [FieldPlacement] (location + name) — where a signing field is written on the request.
 */
@Composable
internal fun FieldPlacementEditor(
    label: String,
    placement: FieldPlacement,
    onChange: (FieldPlacement) -> Unit,
    enabled: Boolean,
    nameRequired: Boolean = true,
) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    EnumDropdown(
        label = "Location",
        options = SigFieldLocation.entries,
        selected = placement.location,
        onSelect = { onChange(placement.copy(location = it)) },
        optionLabel = { it.name },
        enabled = enabled,
    )
    TextFieldRow(
        label = "Field name",
        value = placement.name,
        onValueChange = { onChange(placement.copy(name = it)) },
        enabled = enabled,
        isError = nameRequired && placement.name.isBlank(),
    )
}

/**
 * Editor for the ordered list of [SigPart]s that make up the signed message. Supports the recursive
 * [SigPart.Sha256] variant by nesting another [SigPartListEditor].
 */
@Composable
internal fun SigPartListEditor(
    parts: List<SigPart>,
    onChange: (List<SigPart>) -> Unit,
    enabled: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        parts.forEachIndexed { index, part ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            ) {
                Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    EditorCardHeader(
                        title = "Part ${index + 1}",
                        onRemove = { onChange(parts.filterIndexed { i, _ -> i != index }) },
                        enabled = enabled,
                    )
                    EnumDropdown(
                        label = "Kind",
                        options = SigPartKind.entries,
                        selected = part.kind(),
                        onSelect = { newKind ->
                            if (newKind != part.kind()) {
                                onChange(parts.mapIndexed { i, p -> if (i == index) newKind.defaultPart() else p })
                            }
                        },
                        optionLabel = { it.label },
                        enabled = enabled,
                    )
                    when (part) {
                        is SigPart.Literal ->
                            TextFieldRow(
                                label = "Text",
                                value = part.text,
                                onValueChange = { updated ->
                                    onChange(parts.mapIndexed { i, p -> if (i == index) SigPart.Literal(updated) else p })
                                },
                                enabled = enabled,
                            )
                        is SigPart.ParamString ->
                            EnumDropdown(
                                label = "Format",
                                options = ParamStringFormat.entries,
                                selected = part.format,
                                onSelect = { fmt ->
                                    onChange(parts.mapIndexed { i, p -> if (i == index) SigPart.ParamString(fmt) else p })
                                },
                                optionLabel = { it.name },
                                enabled = enabled,
                            )
                        is SigPart.Sha256 -> {
                            Text(
                                "Nested parts",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            SigPartListEditor(
                                parts = part.parts,
                                onChange = { nested ->
                                    onChange(parts.mapIndexed { i, p -> if (i == index) SigPart.Sha256(nested) else p })
                                },
                                enabled = enabled,
                            )
                        }
                        else -> Unit
                    }
                }
            }
        }
        TextButton(onClick = { onChange(parts + SigPart.Literal("")) }, enabled = enabled) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("Add part")
        }
    }
}

/**
 * Editor for the optional proactive [ApiRequestSigningConfig] (used when `authType == SIGNED`): the
 * complete provider-agnostic HMAC recipe (algorithm, message parts, field placements, body format).
 */
@Composable
internal fun RequestSigningEditor(
    config: ApiRequestSigningConfig?,
    onChange: (ApiRequestSigningConfig?) -> Unit,
    enabled: Boolean,
) {
    ToggleRow(
        label = "Enable proactive request signing (exchanges)",
        checked = config != null,
        onCheckedChange = { on -> onChange(if (on) defaultRequestSigning() else null) },
        enabled = enabled,
    )
    val c = config ?: return

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        EnumDropdown(
            label = "Algorithm",
            options = SigningAlgorithm.entries,
            selected = c.algorithm,
            onSelect = { onChange(c.copy(algorithm = it)) },
            optionLabel = { it.name },
            enabled = enabled,
        )
        EnumDropdown(
            label = "Secret encoding",
            options = SecretEncoding.entries,
            selected = c.secretEncoding,
            onSelect = { onChange(c.copy(secretEncoding = it)) },
            optionLabel = { it.name },
            enabled = enabled,
        )
        EnumDropdown(
            label = "Signature encoding",
            options = SignatureEncoding.entries,
            selected = c.signatureEncoding,
            onSelect = { onChange(c.copy(signatureEncoding = it)) },
            optionLabel = { it.name },
            enabled = enabled,
        )

        Text("Signed message parts", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SigPartListEditor(parts = c.message, onChange = { onChange(c.copy(message = it)) }, enabled = enabled)

        FieldPlacementEditor("API key placement", c.apiKey, { onChange(c.copy(apiKey = it)) }, enabled)
        FieldPlacementEditor("Signature placement", c.signature, { onChange(c.copy(signature = it)) }, enabled)

        Text("Nonce", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        EnumDropdown(
            label = "Nonce format",
            options = NonceFormat.entries,
            selected = c.nonce.format,
            onSelect = { onChange(c.copy(nonce = c.nonce.copy(format = it))) },
            optionLabel = { it.name },
            enabled = enabled,
        )
        FieldPlacementEditor(
            label = "Nonce placement",
            placement = c.nonce.placement,
            onChange = { onChange(c.copy(nonce = c.nonce.copy(placement = it))) },
            enabled = enabled,
        )

        ToggleRow(
            label = "Include per-request id (Crypto.com)",
            checked = c.requestId != null,
            onCheckedChange = { on ->
                onChange(
                    c.copy(
                        requestId =
                            if (on) {
                                RequestIdSpec(placement = FieldPlacement(SigFieldLocation.BODY_FIELD, "id"))
                            } else {
                                null
                            },
                    ),
                )
            },
            enabled = enabled,
        )
        c.requestId?.let { rid ->
            EnumDropdown(
                label = "Request id format",
                options = RequestIdFormat.entries,
                selected = rid.format,
                onSelect = { onChange(c.copy(requestId = rid.copy(format = it))) },
                optionLabel = { it.name },
                enabled = enabled,
            )
            FieldPlacementEditor(
                label = "Request id placement",
                placement = rid.placement,
                onChange = { onChange(c.copy(requestId = rid.copy(placement = it))) },
                enabled = enabled,
            )
        }

        ToggleRow(
            label = "Write API method name on the request",
            checked = c.method != null,
            onCheckedChange = { on ->
                onChange(c.copy(method = if (on) FieldPlacement(SigFieldLocation.BODY_FIELD, "method") else null))
            },
            enabled = enabled,
        )
        c.method?.let { method ->
            FieldPlacementEditor(
                label = "Method placement",
                placement = method,
                onChange = { onChange(c.copy(method = it)) },
                enabled = enabled,
            )
        }

        EnumDropdown(
            label = "Body format",
            options = BodyFormat.entries,
            selected = c.bodyFormat,
            onSelect = { onChange(c.copy(bodyFormat = it)) },
            optionLabel = { it.name },
            enabled = enabled,
        )
        if (c.bodyFormat == BodyFormat.JSON_ENVELOPE) {
            TextFieldRow(
                label = "Params envelope key",
                value = c.paramsEnvelopeKey.orEmpty(),
                onValueChange = { onChange(c.copy(paramsEnvelopeKey = it.ifBlank { null })) },
                enabled = enabled,
                supportingText = "The JSON key request params are nested under (Crypto.com \"params\")",
            )
        }
    }
}

private fun defaultRequestSigning(): ApiRequestSigningConfig =
    ApiRequestSigningConfig(
        algorithm = SigningAlgorithm.HMAC_SHA256,
        message = emptyList(),
        apiKey = FieldPlacement(SigFieldLocation.HEADER, ""),
        nonce = NonceSpec(placement = FieldPlacement(SigFieldLocation.QUERY, "timestamp")),
        signature = FieldPlacement(SigFieldLocation.QUERY, "signature"),
    )

/**
 * Whether a message part is complete. Only the composite [SigPart.Sha256] can be incomplete — an empty
 * nested list signs nothing — so it must have parts that are themselves valid; leaf parts are always OK.
 */
private fun SigPart.isValidForSave(): Boolean =
    when (this) {
        is SigPart.Sha256 -> parts.isNotEmpty() && parts.all { it.isValidForSave() }
        else -> true
    }

/** Whether a request-signing config has every required (and enabled-conditional) field set. */
internal fun ApiRequestSigningConfig.isValidForSave(): Boolean =
    message.isNotEmpty() &&
        message.all { it.isValidForSave() } &&
        apiKey.name.isNotBlank() &&
        signature.name.isNotBlank() &&
        nonce.placement.name.isNotBlank() &&
        (requestId?.placement?.name?.isNotBlank() ?: true) &&
        (method?.name?.isNotBlank() ?: true) &&
        (bodyFormat != BodyFormat.JSON_ENVELOPE || !paramsEnvelopeKey.isNullOrBlank())
