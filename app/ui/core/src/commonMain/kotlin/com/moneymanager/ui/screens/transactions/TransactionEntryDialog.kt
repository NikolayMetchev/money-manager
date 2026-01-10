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
import com.moneymanager.database.sql.TransferSourceQueries
import com.moneymanager.domain.getDeviceInfo
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeType
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.AttributeTypeRepository
import com.moneymanager.domain.repository.CategoryRepository
import com.moneymanager.domain.repository.CurrencyRepository
import com.moneymanager.domain.repository.DeviceRepository
import com.moneymanager.domain.repository.TransactionRepository
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
fun TransactionEntryDialog(
    transactionRepository: TransactionRepository,
    transferSourceQueries: TransferSourceQueries,
    deviceRepository: DeviceRepository,
    accountRepository: AccountRepository,
    categoryRepository: CategoryRepository,
    currencyRepository: CurrencyRepository,
    attributeTypeRepository: AttributeTypeRepository,
    maintenanceService: DatabaseMaintenanceService,
    preSelectedSourceAccountId: AccountId? = null,
    preSelectedCurrencyId: CurrencyId? = null,
    onDismiss: () -> Unit,
    onTransactionCreated: () -> Unit = {},
) {
    var sourceAccountId by remember { mutableStateOf(preSelectedSourceAccountId) }
    var targetAccountId by remember { mutableStateOf<AccountId?>(null) }
    var currencyId by remember { mutableStateOf<CurrencyId?>(preSelectedCurrencyId) }
    var amount by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    // Date and time picker state
    val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    var selectedDate by remember { mutableStateOf(now.date) }
    var selectedHour by remember { mutableStateOf(now.hour) }
    var selectedMinute by remember { mutableStateOf(now.minute) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    val scope = rememberSchemaAwareCoroutineScope()

    // Attribute state
    var existingAttributeTypes by remember { mutableStateOf<List<AttributeType>>(emptyList()) }
    // EditableAttribute represents the current state of each attribute in the UI
    // key: a negative temp id for new ones
    // value: Pair(attributeTypeName, value)
    var editableAttributes by remember { mutableStateOf<Map<Long, Pair<String, String>>>(emptyMap()) }
    var nextTempId by remember { mutableStateOf(-1L) }

    // Load existing attribute types for autocomplete
    LaunchedEffect(Unit) {
        attributeTypeRepository.getAll().collect { types ->
            existingAttributeTypes = types
        }
    }

    // Compute form validity - all required fields must be populated
    val isAmountValid = amount.isNotBlank() && amount.toDoubleOrNull()?.let { it > 0 } == true
    val isFormValid =
        sourceAccountId != null &&
            targetAccountId != null &&
            sourceAccountId != targetAccountId &&
            currencyId != null &&
            isAmountValid &&
            description.isNotBlank()

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text("Create New Transaction") },
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
                    enabled = !isSaving,
                    excludeAccountId = targetAccountId,
                    isError = sourceAccountId == null,
                )

                // Target Account Picker
                AccountPicker(
                    selectedAccountId = targetAccountId,
                    onAccountSelected = { targetAccountId = it },
                    label = "To Account",
                    accountRepository = accountRepository,
                    categoryRepository = categoryRepository,
                    enabled = !isSaving,
                    excludeAccountId = sourceAccountId,
                    isError = targetAccountId == null || targetAccountId == sourceAccountId,
                )

                // Currency Picker
                CurrencyPicker(
                    selectedCurrencyId = currencyId,
                    onCurrencySelected = { currencyId = it },
                    label = "Currency",
                    currencyRepository = currencyRepository,
                    enabled = !isSaving,
                    isError = currencyId == null,
                )

                // Date and Time Pickers
                val dateTimeTextStyle = MaterialTheme.typography.bodySmall
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Date Picker - ISO format (YYYY-MM-DD)
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

                    // Time Picker - ISO format (HH:MM:SS)
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
                    isError = !isAmountValid,
                )

                // Description Input
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isSaving,
                    isError = description.isBlank(),
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
                    // Form is validated via isFormValid, button is disabled when invalid
                    // This check is a safety net
                    if (isFormValid) {
                        isSaving = true
                        errorMessage = null
                        scope.launch {
                            try {
                                // Get the currency object from repository
                                val currency =
                                    currencyRepository.getCurrencyById(currencyId!!).first()
                                        ?: error("Currency not found")

                                // Convert selected date and time to Instant
                                val timestamp =
                                    selectedDate
                                        .atTime(selectedHour, selectedMinute, 0)
                                        .toInstant(TimeZone.currentSystemDefault())
                                // Placeholder ID - real ID generated by database
                                val transfer =
                                    Transfer(
                                        id = TransferId(0L),
                                        timestamp = timestamp,
                                        description = description.trim(),
                                        sourceAccountId = sourceAccountId!!,
                                        targetAccountId = targetAccountId!!,
                                        amount = Money.fromDisplayValue(amount, currency),
                                    )

                                // Prepare attributes with their type IDs
                                val attributesToSave =
                                    editableAttributes
                                        .filter { (_, pair) ->
                                            val (typeName, value) = pair
                                            typeName.isNotBlank() && value.isNotBlank()
                                        }
                                        .map { (_, pair) ->
                                            val (typeName, value) = pair
                                            val typeId = attributeTypeRepository.getOrCreate(typeName.trim())
                                            NewAttribute(typeId, value.trim())
                                        }

                                // Create transfer with attributes and source in one transaction
                                val deviceId = deviceRepository.getOrCreateDevice(getDeviceInfo())
                                transactionRepository.createTransfers(
                                    transfers = listOf(transfer),
                                    newAttributes = mapOf(transfer.id to attributesToSave),
                                    sourceRecorder = ManualSourceRecorder(transferSourceQueries, deviceId),
                                )

                                maintenanceService.refreshMaterializedViews()

                                onTransactionCreated()
                                onDismiss()
                            } catch (expected: Exception) {
                                logger.error(expected) { "Failed to create transaction: ${expected.message}" }
                                errorMessage = "Failed to create transaction: ${expected.message}"
                                isSaving = false
                            }
                        }
                    }
                },
                enabled = isFormValid && !isSaving,
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Create")
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

    // Date Picker Dialog
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
                            // Convert milliseconds to LocalDate
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

    // Time Picker Dialog
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
