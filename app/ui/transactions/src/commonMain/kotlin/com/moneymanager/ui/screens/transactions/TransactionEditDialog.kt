@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.moneymanager.ui.screens.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeType
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.AttributeTypeReadRepository
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportOperation
import com.moneymanager.importengineapi.ImportTransfer
import com.moneymanager.importengineapi.getOrCreateAttributeType
import com.moneymanager.ui.LocalImportEngine
import com.moneymanager.ui.components.AccountPicker
import com.moneymanager.ui.components.CurrencyPicker
import com.moneymanager.ui.components.LoadingTextButton
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.util.onEnterKeyDown
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

@Composable
fun TransactionEditDialog(
    transaction: Transfer? = null,
    accountRepository: AccountReadRepository,
    categoryRepository: CategoryReadRepository,
    currencyRepository: CurrencyReadRepository,
    attributeTypeRepository: AttributeTypeReadRepository,
    personRepository: PersonReadRepository,
    maintenance: Maintenance,
    preSelectedSourceAccountId: AccountId? = null,
    preSelectedCurrencyId: CurrencyId? = null,
    onDismiss: () -> Unit,
    onSaved: () -> Unit = {},
) {
    val isEditMode = transaction != null
    var sourceAccountId by remember { mutableStateOf(transaction?.sourceAccountId ?: preSelectedSourceAccountId) }
    var targetAccountId by remember { mutableStateOf(transaction?.targetAccountId) }
    var currencyId by remember { mutableStateOf(transaction?.amount?.currency?.id ?: preSelectedCurrencyId) }
    var amount by remember {
        mutableStateOf(
            transaction
                ?.amount
                ?.toDisplayValue()
                ?.toString()
                .orEmpty(),
        )
    }
    var description by remember { mutableStateOf(transaction?.description.orEmpty()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    val defaultDateTime = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    val transactionDateTime = transaction?.timestamp?.toLocalDateTime(TimeZone.currentSystemDefault())
    var selectedDate by remember { mutableStateOf(transactionDateTime?.date ?: defaultDateTime.date) }
    var selectedHour by remember { mutableStateOf(transactionDateTime?.hour ?: defaultDateTime.hour) }
    var selectedMinute by remember { mutableStateOf(transactionDateTime?.minute ?: defaultDateTime.minute) }
    val originalSecond = remember { transactionDateTime?.second ?: 0 }
    val originalNanosecond = remember { transactionDateTime?.nanosecond ?: 0 }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    val scope = rememberSchemaAwareCoroutineScope()
    val importEngine = LocalImportEngine.current

    // Attribute state - in edit mode, initialize from transaction's embedded attributes
    // (already loaded by TransactionsScreen via getTransactionById which calls loadAttributesForTransfer)
    var existingAttributeTypes by remember { mutableStateOf<List<AttributeType>>(emptyList()) }

    // The "excluded" attribute (type id = -1) is managed separately via its own checkbox.
    val originalExcludedAttr = remember { transaction?.attributes?.find { it.attributeType.name == "excluded" } }
    var isExcluded by remember { mutableStateOf(originalExcludedAttr != null) }
    var excludeReason by remember { mutableStateOf(originalExcludedAttr?.value.orEmpty()) }

    // Generic attributes exclude the well-known "excluded" one — that is handled above.
    val originalAttributes by remember {
        mutableStateOf(transaction?.attributes.orEmpty().filter { it.attributeType.name != "excluded" })
    }

    // EditableAttribute represents the current state of each attribute in the UI
    // key: a stable identifier (original attribute id or a negative temp id for new ones)
    // value: Pair(attributeTypeName, value)
    var editableAttributes by remember {
        mutableStateOf(
            originalAttributes.associate { attr ->
                attr.id to Pair(attr.attributeType.name, attr.value)
            },
        )
    }
    var nextTempId by remember { mutableStateOf(-1L) }

    // Load existing attribute types for autocomplete
    LaunchedEffect(Unit) {
        attributeTypeRepository.getAll().collect { types ->
            existingAttributeTypes = types
        }
    }

    // Helper to check if attributes have changed
    fun hasAttributeChanges(): Boolean {
        val originalMap = originalAttributes.associate { it.id to Pair(it.attributeType.name, it.value) }
        // Check if any new attributes were added (negative IDs)
        if (editableAttributes.keys.any { it < 0 }) return true
        // Check if any original attributes were removed
        if (originalMap.keys.any { it !in editableAttributes.keys }) return true
        // Check if any attribute values changed
        return editableAttributes.any { (id, pair) ->
            val original = originalMap[id]
            original == null || original != pair
        }
    }

    val parsedAmount =
        remember(amount) {
            runCatching { BigDecimal(amount.trim()) }.getOrNull()
        }

    // Check if any field has changed from the original transaction (edit mode only)
    val hasChanges =
        remember(
            sourceAccountId,
            targetAccountId,
            currencyId,
            amount,
            parsedAmount,
            description,
            selectedDate,
            selectedHour,
            selectedMinute,
            editableAttributes,
            originalAttributes,
            isExcluded,
            excludeReason,
        ) {
            if (transaction == null) {
                // In create mode, enable save only when all required fields are filled and valid
                sourceAccountId != null &&
                    targetAccountId != null &&
                    currencyId != null &&
                    amount.isNotBlank() &&
                    parsedAmount != null &&
                    parsedAmount > BigDecimal.ZERO &&
                    sourceAccountId != targetAccountId
            } else {
                sourceAccountId != transaction.sourceAccountId ||
                    targetAccountId != transaction.targetAccountId ||
                    currencyId != transaction.amount.currency.id ||
                    parsedAmount != transaction.amount.toDisplayValue() ||
                    description != transaction.description ||
                    selectedDate != transactionDateTime!!.date ||
                    selectedHour != transactionDateTime.hour ||
                    selectedMinute != transactionDateTime.minute ||
                    hasAttributeChanges() ||
                    isExcluded != (originalExcludedAttr != null) ||
                    (isExcluded && excludeReason != (originalExcludedAttr?.value.orEmpty()))
            }
        }

    val sourceFocusRequester = remember { FocusRequester() }
    val targetFocusRequester = remember { FocusRequester() }
    val currencyFocusRequester = remember { FocusRequester() }
    val amountFocusRequester = remember { FocusRequester() }
    val descriptionFocusRequester = remember { FocusRequester() }
    var sourceError by remember { mutableStateOf(false) }
    var targetError by remember { mutableStateOf(false) }
    var currencyError by remember { mutableStateOf(false) }
    var amountError by remember { mutableStateOf(false) }
    var descriptionError by remember { mutableStateOf(false) }

    // Focus the first field on open so Enter has a focused field to route through (the key-event handler
    // only fires while a field inside the dialog is focused).
    LaunchedEffect(Unit) { runCatching { sourceFocusRequester.requestFocus() } }

    val submit: () -> Unit = submit@{
        if (isSaving) return@submit
        // Flag every invalid required field so they all highlight at once (not just the first).
        sourceError = sourceAccountId == null
        targetError = targetAccountId == null || (sourceAccountId != null && sourceAccountId == targetAccountId)
        currencyError = currencyId == null
        amountError = amount.isBlank() || parsedAmount == null || parsedAmount <= BigDecimal.ZERO
        descriptionError = description.isBlank()

        when {
            sourceError || targetError || currencyError || amountError || descriptionError -> {
                errorMessage =
                    when {
                        sourceAccountId == null -> "Source account is required"
                        targetAccountId == null -> "Target account is required"
                        sourceAccountId == targetAccountId -> "Source and target accounts must be different"
                        currencyId == null -> "Currency is required"
                        amount.isBlank() -> "Amount is required"
                        parsedAmount == null -> "Invalid amount"
                        parsedAmount <= BigDecimal.ZERO -> "Amount must be greater than 0"
                        else -> "Description is required"
                    }
                // Move the cursor to the first invalid field; the rest stay highlighted via their flags.
                when {
                    sourceError -> sourceFocusRequester.requestFocus()
                    targetError -> targetFocusRequester.requestFocus()
                    currencyError -> currencyFocusRequester.requestFocus()
                    amountError -> amountFocusRequester.requestFocus()
                    else -> descriptionFocusRequester.requestFocus()
                }
            }
            // All fields are valid but nothing changed (edit mode) — mirror the disabled Save button.
            !hasChanges -> return@submit
            else -> {
                isSaving = true
                errorMessage = null
                scope.launch {
                    try {
                        // Get the currency object from repository
                        val currency =
                            currencyRepository.getCurrencyById(currencyId!!).first()
                                ?: error("Currency not found")

                        val timestamp =
                            selectedDate
                                .atTime(selectedHour, selectedMinute, originalSecond, originalNanosecond)
                                .toInstant(TimeZone.currentSystemDefault())

                        if (transaction != null) {
                            // EDIT MODE: Update existing transaction
                            // Check if transfer fields actually changed
                            val transferFieldsChanged =
                                sourceAccountId != transaction.sourceAccountId ||
                                    targetAccountId != transaction.targetAccountId ||
                                    currencyId != transaction.amount.currency.id ||
                                    parsedAmount != transaction.amount.toDisplayValue() ||
                                    description.trim() != transaction.description ||
                                    timestamp != transaction.timestamp

                            // Build the transfer object if fields changed
                            val updatedTransfer =
                                if (transferFieldsChanged) {
                                    Transfer(
                                        id = transaction.id,
                                        timestamp = timestamp,
                                        description = description.trim(),
                                        sourceAccountId = sourceAccountId!!,
                                        targetAccountId = targetAccountId!!,
                                        amount = Money.fromDisplayValue(parsedAmount!!, currency),
                                    )
                                } else {
                                    null
                                }

                            // Build attribute change data structures
                            val originalIds = originalAttributes.map { it.id }.toSet()
                            val editableIds = editableAttributes.keys
                            val deletedAttributeIds = (originalIds - editableIds).toMutableSet()

                            // Build updated attributes map (id -> NewAttribute)
                            val updatedAttributes = mutableMapOf<Long, NewAttribute>()
                            editableAttributes.filter { (id, _) -> id > 0 }.forEach { (id, pair) ->
                                val (typeName, value) = pair
                                val original = originalAttributes.find { it.id == id }
                                if (original != null) {
                                    val typeChanged = original.attributeType.name != typeName
                                    val valueChanged = original.value != value
                                    if (typeChanged || valueChanged) {
                                        val typeId = importEngine.getOrCreateAttributeType(typeName)
                                        updatedAttributes[id] = NewAttribute(typeId, value)
                                    }
                                }
                            }

                            // Build new attributes list
                            val newAttributes = mutableListOf<NewAttribute>()
                            editableAttributes.filter { (id, _) -> id < 0 }.forEach { (_, pair) ->
                                val (typeName, value) = pair
                                if (typeName.isNotBlank() && value.isNotBlank()) {
                                    val typeId = importEngine.getOrCreateAttributeType(typeName)
                                    newAttributes.add(NewAttribute(typeId, value))
                                }
                            }

                            // Handle the "excluded" attribute (well-known id = -1)
                            when {
                                isExcluded && originalExcludedAttr == null ->
                                    newAttributes.add(NewAttribute(AttributeTypeId(-1), excludeReason))
                                isExcluded && excludeReason != originalExcludedAttr!!.value ->
                                    updatedAttributes[originalExcludedAttr.id] =
                                        NewAttribute(AttributeTypeId(-1), excludeReason)
                                !isExcluded && originalExcludedAttr != null ->
                                    deletedAttributeIds.add(originalExcludedAttr.id)
                            }

                            // One UPDATE intent: transfer fields + attribute deltas, applied
                            // atomically (one revision bump) by the engine.
                            importEngine.import(
                                ImportBatch.manualEdits(
                                    transfers =
                                        listOf(
                                            ImportTransfer(
                                                source = Source.Manual,
                                                operation = ImportOperation.UPDATE,
                                                existingId = transaction.id,
                                                // Null when only attributes changed (no transfer-field update).
                                                // When fields didn't change, updatedTransfer is null so
                                                // from/to/timestamp/amount are all null; the engine's
                                                // toUpdatedTransfer() then returns null (attribute-only
                                                // update) and this "" description is never written.
                                                fromAccount = updatedTransfer?.let { AccountRef.Existing(it.sourceAccountId) },
                                                toAccount = updatedTransfer?.let { AccountRef.Existing(it.targetAccountId) },
                                                timestamp = updatedTransfer?.timestamp,
                                                description = updatedTransfer?.description ?: "",
                                                amount = updatedTransfer?.amount,
                                                deletedAttributeIds = deletedAttributeIds,
                                                updatedAttributes = updatedAttributes,
                                                attributes = newAttributes,
                                            ),
                                        ),
                                ),
                            )
                        } else {
                            // CREATE MODE: build attributes to save (only non-blank ones).
                            val attributesToSave =
                                editableAttributes
                                    .filter { (_, pair) -> pair.first.isNotBlank() && pair.second.isNotBlank() }
                                    .map { (_, pair) ->
                                        val typeId = importEngine.getOrCreateAttributeType(pair.first.trim())
                                        NewAttribute(typeId, pair.second.trim())
                                    }.toMutableList()

                            if (isExcluded) {
                                attributesToSave.add(NewAttribute(AttributeTypeId(-1), excludeReason))
                            }

                            importEngine.import(
                                ImportBatch.manualEdits(
                                    transfers =
                                        listOf(
                                            ImportTransfer(
                                                source = Source.Manual,
                                                fromAccount = AccountRef.Existing(sourceAccountId!!),
                                                toAccount = AccountRef.Existing(targetAccountId!!),
                                                timestamp = timestamp,
                                                description = description.trim(),
                                                amount = Money.fromDisplayValue(parsedAmount!!, currency),
                                                attributes = attributesToSave,
                                            ),
                                        ),
                                ),
                            )
                        }

                        maintenance.refreshMaterializedViews()

                        onSaved()
                        onDismiss()
                    } catch (expected: Exception) {
                        logger.error(expected) {
                            "Failed to ${if (isEditMode) "update" else "create"} transaction: ${expected.message}"
                        }
                        errorMessage =
                            "Failed to ${if (isEditMode) "update" else "create"} transaction: ${expected.message}"
                        isSaving = false
                    }
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text(if (isEditMode) "Edit Transaction" else "Create New Transaction") },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 8.dp)
                        .onEnterKeyDown(submit),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Source Account Picker
                AccountPicker(
                    selectedAccountId = sourceAccountId,
                    onAccountSelected = {
                        sourceAccountId = it
                        sourceError = false
                    },
                    label = "From Account",
                    accountRepository = accountRepository,
                    categoryRepository = categoryRepository,
                    personRepository = personRepository,
                    enabled = !isSaving,
                    excludeAccountId = targetAccountId,
                    isError = sourceError,
                    focusRequester = sourceFocusRequester,
                    onSubmit = submit,
                )

                // Target Account Picker
                AccountPicker(
                    selectedAccountId = targetAccountId,
                    onAccountSelected = {
                        targetAccountId = it
                        targetError = false
                    },
                    label = "To Account",
                    accountRepository = accountRepository,
                    categoryRepository = categoryRepository,
                    personRepository = personRepository,
                    enabled = !isSaving,
                    excludeAccountId = sourceAccountId,
                    isError = targetError,
                    focusRequester = targetFocusRequester,
                    onSubmit = submit,
                )

                // Currency Picker
                CurrencyPicker(
                    selectedCurrencyId = currencyId,
                    onCurrencySelected = {
                        currencyId = it
                        currencyError = false
                    },
                    label = "Currency",
                    currencyRepository = currencyRepository,
                    enabled = !isSaving,
                    isError = currencyError,
                    focusRequester = currencyFocusRequester,
                    onSubmit = submit,
                )

                // Date and Time Pickers
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PickerReadonlyField(
                        value = selectedDate.toString(),
                        label = "Date",
                        icon = "\uD83D\uDCC5",
                        onIconClick = { showDatePicker = true },
                        iconEnabled = !isSaving,
                        modifier = Modifier.weight(1.5f),
                    )

                    PickerReadonlyField(
                        value = LocalTime(selectedHour, selectedMinute).toString(),
                        label = "Time",
                        icon = "\uD83D\uDD54",
                        onIconClick = { showTimePicker = true },
                        iconEnabled = !isSaving,
                        modifier = Modifier.weight(1f),
                    )
                }

                // Amount Input
                OutlinedTextField(
                    value = amount,
                    onValueChange = {
                        amount = it
                        amountError = false
                    },
                    label = { Text("Amount") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().focusRequester(amountFocusRequester).onEnterKeyDown(submit),
                    singleLine = true,
                    enabled = !isSaving,
                    isError = amountError,
                )

                // Description Input
                OutlinedTextField(
                    value = description,
                    onValueChange = {
                        description = it
                        descriptionError = false
                    },
                    label = { Text("Description") },
                    modifier = Modifier.fillMaxWidth().focusRequester(descriptionFocusRequester).onEnterKeyDown(submit),
                    singleLine = true,
                    enabled = !isSaving,
                    isError = descriptionError,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                )

                // Excluded toggle
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = isExcluded,
                            onCheckedChange = { isExcluded = it },
                            enabled = !isSaving,
                        )
                        Text(
                            text = "Excluded from balances",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    if (isExcluded) {
                        OutlinedTextField(
                            value = excludeReason,
                            onValueChange = { excludeReason = it },
                            label = { Text("Reason") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = !isSaving,
                        )
                    }
                }

                EditableAttributesSection(
                    editableAttributes = editableAttributes,
                    existingAttributeTypes = existingAttributeTypes,
                    isSaving = isSaving,
                    onAttributesChange = { editableAttributes = it },
                    onAddAttribute = {
                        editableAttributes = editableAttributes + (nextTempId to Pair("", ""))
                        nextTempId--
                    },
                )

                errorMessage?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            LoadingTextButton(
                onClick = submit,
                enabled = !isSaving && hasChanges,
                loading = isSaving,
                label = if (isEditMode) "Update" else "Create",
            )
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isSaving,
            ) {
                Text("Cancel")
            }
        },
    )

    if (showDatePicker) {
        val datePickerState =
            rememberDatePickerState(
                initialSelectedDateMillis =
                    selectedDate
                        .atTime(0, 0)
                        .toInstant(TimeZone.UTC)
                        .toEpochMilliseconds(),
            )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            selectedDate =
                                Instant
                                    .fromEpochMilliseconds(millis)
                                    .toLocalDateTime(TimeZone.UTC)
                                    .date
                        }
                        showDatePicker = false
                    },
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel")
                }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showTimePicker) {
        val timePickerState =
            rememberTimePickerState(
                initialHour = selectedHour,
                initialMinute = selectedMinute,
                is24Hour = false,
            )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        selectedHour = timePickerState.hour
                        selectedMinute = timePickerState.minute
                        showTimePicker = false
                    },
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) {
                    Text("Cancel")
                }
            },
            text = {
                TimePicker(state = timePickerState)
            },
        )
    }
}

@Composable
private fun PickerReadonlyField(
    value: String,
    label: String,
    icon: String,
    onIconClick: () -> Unit,
    iconEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = {},
        readOnly = true,
        label = { Text(label) },
        textStyle = MaterialTheme.typography.bodySmall,
        trailingIcon = {
            IconButton(
                onClick = onIconClick,
                enabled = iconEnabled,
            ) {
                Text(
                    text = icon,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        },
        modifier = modifier,
        enabled = false,
        singleLine = true,
        colors =
            OutlinedTextFieldDefaults.colors(
                disabledTextColor = MaterialTheme.colorScheme.onSurface,
                disabledBorderColor = MaterialTheme.colorScheme.outline,
                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
    )
}
