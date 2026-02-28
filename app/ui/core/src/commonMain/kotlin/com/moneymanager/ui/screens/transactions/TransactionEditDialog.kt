@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.moneymanager.ui.screens.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.moneymanager.database.DatabaseMaintenanceService
import com.moneymanager.database.ManualSourceRecorder
import com.moneymanager.database.sql.EntitySourceQueries
import com.moneymanager.database.sql.TransferSourceQueries
import com.moneymanager.domain.getDeviceInfo
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeType
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.AttributeTypeRepository
import com.moneymanager.domain.repository.CategoryRepository
import com.moneymanager.domain.repository.CurrencyRepository
import com.moneymanager.domain.repository.DeviceRepository
import com.moneymanager.domain.repository.PersonAccountOwnershipRepository
import com.moneymanager.domain.repository.PersonRepository
import com.moneymanager.domain.repository.TransactionRepository
import com.moneymanager.domain.repository.TransferAttributeRepository
import com.moneymanager.domain.repository.TransferSourceRepository
import com.moneymanager.ui.components.AccountPicker
import com.moneymanager.ui.components.CurrencyPicker
import com.moneymanager.ui.error.rememberSchemaAwareCoroutineScope
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
    transactionRepository: TransactionRepository,
    transferSourceRepository: TransferSourceRepository,
    transferSourceQueries: TransferSourceQueries,
    entitySourceQueries: EntitySourceQueries,
    deviceRepository: DeviceRepository,
    accountRepository: AccountRepository,
    categoryRepository: CategoryRepository,
    currencyRepository: CurrencyRepository,
    attributeTypeRepository: AttributeTypeRepository,
    personRepository: PersonRepository,
    personAccountOwnershipRepository: PersonAccountOwnershipRepository,
    transferAttributeRepository: TransferAttributeRepository,
    maintenanceService: DatabaseMaintenanceService,
    deviceId: DeviceId,
    preSelectedSourceAccountId: AccountId? = null,
    preSelectedCurrencyId: CurrencyId? = null,
    onDismiss: () -> Unit,
    onSaved: () -> Unit = {},
) {
    val isEditMode = transaction != null
    var sourceAccountId by remember { mutableStateOf(transaction?.sourceAccountId ?: preSelectedSourceAccountId) }
    var targetAccountId by remember { mutableStateOf(transaction?.targetAccountId) }
    var currencyId by remember { mutableStateOf(transaction?.amount?.currency?.id ?: preSelectedCurrencyId) }
    var amount by remember { mutableStateOf(transaction?.amount?.toDisplayValue()?.toString().orEmpty()) }
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

    // Attribute state - in edit mode, initialize from transaction's embedded attributes
    // (already loaded by TransactionsScreen via getTransactionById which calls loadAttributesForTransfer)
    var existingAttributeTypes by remember { mutableStateOf<List<AttributeType>>(emptyList()) }
    var originalAttributes by remember {
        mutableStateOf(transaction?.attributes.orEmpty())
    }

    // EditableAttribute represents the current state of each attribute in the UI
    // key: a stable identifier (original attribute id or a negative temp id for new ones)
    // value: Pair(attributeTypeName, value)
    var editableAttributes by remember {
        mutableStateOf(
            transaction?.attributes.orEmpty().associate { attr ->
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

    // Check if any field has changed from the original transaction (edit mode only)
    val hasChanges =
        remember(
            sourceAccountId,
            targetAccountId,
            currencyId,
            amount,
            description,
            selectedDate,
            selectedHour,
            selectedMinute,
            editableAttributes,
            originalAttributes,
        ) {
            if (!isEditMode || transaction == null) {
                // In create mode, enable save only when all required fields are filled and valid
                sourceAccountId != null &&
                    targetAccountId != null &&
                    currencyId != null &&
                    amount.isNotBlank() &&
                    amount.toDoubleOrNull() != null &&
                    amount.toDouble() > 0 &&
                    sourceAccountId != targetAccountId
            } else {
                sourceAccountId != transaction.sourceAccountId ||
                    targetAccountId != transaction.targetAccountId ||
                    currencyId != transaction.amount.currency.id ||
                    amount != transaction.amount.toDisplayValue().toString() ||
                    description != transaction.description ||
                    selectedDate != transactionDateTime!!.date ||
                    selectedHour != transactionDateTime.hour ||
                    selectedMinute != transactionDateTime.minute ||
                    hasAttributeChanges()
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
                        .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Source Account Picker
                AccountPicker(
                    selectedAccountId = sourceAccountId,
                    onAccountSelected = { sourceAccountId = it },
                    label = "From Account",
                    accountRepository = accountRepository,
                    categoryRepository = categoryRepository,
                    personRepository = personRepository,
                    personAccountOwnershipRepository = personAccountOwnershipRepository,
                    entitySourceQueries = entitySourceQueries,
                    deviceId = deviceId,
                    enabled = !isSaving,
                    excludeAccountId = targetAccountId,
                )

                // Target Account Picker
                AccountPicker(
                    selectedAccountId = targetAccountId,
                    onAccountSelected = { targetAccountId = it },
                    label = "To Account",
                    accountRepository = accountRepository,
                    categoryRepository = categoryRepository,
                    personRepository = personRepository,
                    personAccountOwnershipRepository = personAccountOwnershipRepository,
                    entitySourceQueries = entitySourceQueries,
                    deviceId = deviceId,
                    enabled = !isSaving,
                    excludeAccountId = sourceAccountId,
                )

                // Currency Picker
                CurrencyPicker(
                    selectedCurrencyId = currencyId,
                    onCurrencySelected = { currencyId = it },
                    label = "Currency",
                    currencyRepository = currencyRepository,
                    enabled = !isSaving,
                )

                // Date and Time Pickers
                val dateTimeTextStyle = MaterialTheme.typography.bodySmall
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = selectedDate.toString(),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Date") },
                        textStyle = dateTimeTextStyle,
                        trailingIcon = {
                            IconButton(
                                onClick = { showDatePicker = true },
                                enabled = !isSaving,
                            ) {
                                Text(
                                    text = "\uD83D\uDCC5",
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                        },
                        modifier = Modifier.weight(1.5f),
                        enabled = false,
                        singleLine = true,
                        colors =
                            OutlinedTextFieldDefaults.colors(
                                disabledTextColor = MaterialTheme.colorScheme.onSurface,
                                disabledBorderColor = MaterialTheme.colorScheme.outline,
                                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                    )

                    val time = LocalTime(selectedHour, selectedMinute)
                    OutlinedTextField(
                        value = time.toString(),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Time") },
                        textStyle = dateTimeTextStyle,
                        trailingIcon = {
                            IconButton(
                                onClick = { showTimePicker = true },
                                enabled = !isSaving,
                            ) {
                                Text(
                                    text = "\uD83D\uDD54",
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                        },
                        modifier = Modifier.weight(1f),
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

                // Amount Input
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Amount") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isSaving,
                )

                // Description Input
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isSaving,
                )

                // Attributes Section
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Attributes",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // Display editable attributes
                    editableAttributes.forEach { (id, pair) ->
                        val (typeName, value) = pair
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Attribute type selector
                            AttributeTypeField(
                                value = typeName,
                                onValueChange = { newTypeName ->
                                    editableAttributes = editableAttributes + (id to Pair(newTypeName, value))
                                },
                                existingTypes = existingAttributeTypes,
                                enabled = !isSaving,
                                modifier = Modifier.weight(0.4f),
                            )
                            // Attribute value field
                            OutlinedTextField(
                                value = value,
                                onValueChange = { newValue ->
                                    editableAttributes = editableAttributes + (id to Pair(typeName, newValue))
                                },
                                label = { Text("Value") },
                                modifier = Modifier.weight(0.5f),
                                singleLine = true,
                                enabled = !isSaving,
                            )
                            // Delete button
                            IconButton(
                                onClick = {
                                    editableAttributes = editableAttributes - id
                                },
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

                    // Add new attribute button
                    TextButton(
                        onClick = {
                            editableAttributes = editableAttributes + (nextTempId to Pair("", ""))
                            nextTempId--
                        },
                        enabled = !isSaving,
                    ) {
                        Text("+ Add Attribute")
                    }
                }

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
            TextButton(
                onClick = {
                    when {
                        sourceAccountId == null -> errorMessage = "Source account is required"
                        targetAccountId == null -> errorMessage = "Target account is required"
                        sourceAccountId == targetAccountId -> errorMessage = "Source and target accounts must be different"
                        currencyId == null -> errorMessage = "Currency is required"
                        amount.isBlank() -> errorMessage = "Amount is required"
                        amount.toDoubleOrNull() == null -> errorMessage = "Invalid amount"
                        amount.toDouble() <= 0 -> errorMessage = "Amount must be greater than 0"
                        description.isBlank() -> errorMessage = "Description is required"
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

                                    if (isEditMode && transaction != null) {
                                        // EDIT MODE: Update existing transaction
                                        // Check if transfer fields actually changed
                                        val transferFieldsChanged =
                                            sourceAccountId != transaction.sourceAccountId ||
                                                targetAccountId != transaction.targetAccountId ||
                                                currencyId != transaction.amount.currency.id ||
                                                amount != transaction.amount.toDisplayValue().toString() ||
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
                                                    amount = Money.fromDisplayValue(amount, currency),
                                                )
                                            } else {
                                                null
                                            }

                                        // Build attribute change data structures
                                        val originalIds = originalAttributes.map { it.id }.toSet()
                                        val editableIds = editableAttributes.keys
                                        val deletedAttributeIds = originalIds - editableIds

                                        // Build updated attributes map (id -> NewAttribute)
                                        val updatedAttributes = mutableMapOf<Long, NewAttribute>()
                                        editableAttributes.filter { (id, _) -> id > 0 }.forEach { (id, pair) ->
                                            val (typeName, value) = pair
                                            val original = originalAttributes.find { it.id == id }
                                            if (original != null) {
                                                val typeChanged = original.attributeType.name != typeName
                                                val valueChanged = original.value != value
                                                if (typeChanged || valueChanged) {
                                                    val typeId = attributeTypeRepository.getOrCreate(typeName)
                                                    updatedAttributes[id] = NewAttribute(typeId, value)
                                                }
                                            }
                                        }

                                        // Build new attributes list
                                        val newAttributes = mutableListOf<NewAttribute>()
                                        editableAttributes.filter { (id, _) -> id < 0 }.forEach { (_, pair) ->
                                            val (typeName, value) = pair
                                            if (typeName.isNotBlank() && value.isNotBlank()) {
                                                val typeId = attributeTypeRepository.getOrCreate(typeName)
                                                newAttributes.add(NewAttribute(typeId, value))
                                            }
                                        }

                                        // Use the atomic method to update transfer and attributes together
                                        transactionRepository.updateTransfer(
                                            transfer = updatedTransfer,
                                            deletedAttributeIds = deletedAttributeIds,
                                            updatedAttributes = updatedAttributes,
                                            newAttributes = newAttributes,
                                            transactionId = transaction.id,
                                        )

                                        // Record manual source for this update
                                        val updated =
                                            transactionRepository.getTransactionById(transaction.id.id).first()
                                        if (updated != null) {
                                            transferSourceRepository.recordManualSource(
                                                transactionId = updated.id,
                                                revisionId = updated.revisionId,
                                                deviceInfo = getDeviceInfo(),
                                            )
                                        }
                                    } else {
                                        // CREATE MODE: Create new transaction
                                        val transfer =
                                            Transfer(
                                                id = TransferId(0L),
                                                timestamp = timestamp,
                                                description = description.trim(),
                                                sourceAccountId = sourceAccountId!!,
                                                targetAccountId = targetAccountId!!,
                                                amount = Money.fromDisplayValue(amount, currency),
                                            )

                                        // Build attributes to save (only non-blank ones)
                                        val attributesToSave =
                                            editableAttributes
                                                .filter { (_, pair) -> pair.first.isNotBlank() && pair.second.isNotBlank() }
                                                .map { (_, pair) ->
                                                    val typeId = attributeTypeRepository.getOrCreate(pair.first.trim())
                                                    NewAttribute(typeId, pair.second.trim())
                                                }

                                        val transferDeviceId = deviceRepository.getOrCreateDevice(getDeviceInfo())
                                        transactionRepository.createTransfers(
                                            transfers = listOf(transfer),
                                            newAttributes = mapOf(transfer.id to attributesToSave),
                                            sourceRecorder = ManualSourceRecorder(transferSourceQueries, transferDeviceId),
                                        )
                                    }

                                    maintenanceService.refreshMaterializedViews()

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
                },
                enabled = !isSaving && hasChanges,
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(if (isEditMode) "Update" else "Create")
                }
            }
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
