package com.moneymanager.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import co.touchlab.kermit.Logger
import com.moneymanager.domain.model.calendar.HolidayCalendar
import com.moneymanager.domain.repository.HolidayCalendarReadRepository
import com.moneymanager.domain.repository.SettingsReadRepository
import com.moneymanager.importengineapi.deleteHolidayCalendar
import com.moneymanager.importengineapi.setDefaultLocation
import com.moneymanager.importengineapi.upsertHolidayCalendar
import com.moneymanager.presenter.error.rememberFlowAsStateWithSchemaErrorHandling
import com.moneymanager.presenter.error.rememberSchemaAwareCoroutineScope
import com.moneymanager.ui.components.DestructiveConfirmDialog
import com.moneymanager.ui.components.LocationPicker
import com.moneymanager.ui.components.SettingsSectionCard
import com.moneymanager.ui.foundation.LocalImportEngine
import kotlinx.coroutines.launch
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Clock

private val logger = Logger.withTag("HolidayCalendarsCard")

/**
 * The database's default location and its holiday calendars: what business-day reconcile windows count. Calendars
 * are seeded from Strata on creation; this is where a person adds one, or a holiday announced after the app shipped.
 */
@Composable
internal fun HolidayCalendarsCard(
    holidayCalendarRepository: HolidayCalendarReadRepository,
    settingsRepository: SettingsReadRepository,
) {
    val importEngine = LocalImportEngine.current
    val scope = rememberSchemaAwareCoroutineScope()
    val calendars by rememberFlowAsStateWithSchemaErrorHandling(initial = emptyList()) {
        holidayCalendarRepository.getAll()
    }
    val defaultLocation by rememberFlowAsStateWithSchemaErrorHandling(initial = null) {
        settingsRepository.getDefaultLocation()
    }
    var expanded by remember { mutableStateOf(false) }
    // A calendar being edited; a blank id is a new one.
    var editing by remember { mutableStateOf<HolidayCalendar?>(null) }
    var deleting by remember { mutableStateOf<HolidayCalendar?>(null) }

    SettingsSectionCard(title = "Locations and holiday calendars") {
        LocationPicker(
            calendars = calendars,
            selectedCode = defaultLocation,
            onLocationSelected = { code -> scope.launch { importEngine.setDefaultLocation(code) } },
            label = "Default location",
            noneLabel = "None (weekends only)",
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text =
                "Business-day reconcile windows count the days every account in a match is open, by each account's " +
                    "location (set on the account), or this default.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide calendars" else "Show calendars (${calendars.size})")
            }
            OutlinedButton(onClick = { editing = HolidayCalendar(id = "", name = "", timeZone = TimeZone.UTC.id) }) {
                Text("New calendar")
            }
        }
        if (expanded) {
            calendars.forEach { calendar ->
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(calendar.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "${calendar.timeZone} · ${calendar.holidays.size} holidays" + yearsSummary(calendar),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { editing = calendar }) { Text("Edit") }
                    TextButton(onClick = { deleting = calendar }) { Text("Delete") }
                }
            }
        }
    }

    editing?.let { calendar ->
        HolidayCalendarEditorDialog(
            initial = calendar,
            takenIds = calendars.map { it.id }.toSet() - calendar.id,
            onSave = { updated ->
                importEngine.upsertHolidayCalendar(updated)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }

    deleting?.let { calendar ->
        DestructiveConfirmDialog(
            title = "Delete holiday calendar?",
            targetName = calendar.label,
            consequence =
                "Accounts at this location, and a default location naming it, will fail business-day imports until " +
                    "you choose another location or add the calendar again.",
            onConfirm = {
                importEngine.deleteHolidayCalendar(calendar.id)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
}

private fun yearsSummary(calendar: HolidayCalendar): String {
    val first = calendar.holidays.minOrNull() ?: return ""
    val last = calendar.holidays.maxOrNull() ?: return ""
    return ", ${first.year}–${last.year}"
}

/**
 * Edits one calendar. Its holidays and extra working days are edited a year at a time, since a seeded calendar holds
 * 150 years of them. The code is the key accounts name, so it is fixed once the calendar exists.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
private fun HolidayCalendarEditorDialog(
    initial: HolidayCalendar,
    takenIds: Set<String>,
    onSave: suspend (HolidayCalendar) -> Unit,
    onDismiss: () -> Unit,
) {
    val isNew = initial.id.isBlank()
    val scope = rememberSchemaAwareCoroutineScope()
    var id by remember { mutableStateOf(initial.id) }
    var name by remember { mutableStateOf(initial.name) }
    var timeZone by remember { mutableStateOf(initial.timeZone) }
    var countries by remember { mutableStateOf(initial.countries.joinToString(" ")) }
    var weekend by remember { mutableStateOf(initial.weekend.toSet()) }
    var holidays by remember { mutableStateOf(initial.holidays.toSet()) }
    var workingDays by remember { mutableStateOf(initial.workingDays.toSet()) }
    var year by remember { mutableStateOf(Clock.System.todayIn(TimeZone.currentSystemDefault()).year) }
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val code = id.trim().uppercase()
    val zoneValid = remember(timeZone) { runCatching { TimeZone.of(timeZone.trim()) }.isSuccess }
    val countryList = countries.split(' ', ',').map { it.trim().uppercase() }.filter { it.isNotEmpty() }
    val codeError =
        when {
            code.isEmpty() -> "A code is required"
            code in takenIds -> "A calendar with code $code already exists"
            else -> null
        }
    val canSave = codeError == null && zoneValid && countryList.all { it.length == 2 } && !isSaving

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text(if (isNew) "New holiday calendar" else "Edit ${initial.label}") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = id,
                    onValueChange = { id = it },
                    label = { Text("Code (e.g. GBLO)") },
                    enabled = isNew && !isSaving,
                    singleLine = true,
                    isError = isNew && codeError != null,
                    supportingText = { if (isNew && codeError != null) Text(codeError) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    enabled = !isSaving,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = timeZone,
                    onValueChange = { timeZone = it },
                    label = { Text("Time zone (e.g. Europe/London)") },
                    enabled = !isSaving,
                    singleLine = true,
                    isError = !zoneValid,
                    supportingText = { Text(if (zoneValid) "Dates are read in this zone" else "Unknown time zone") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = countries,
                    onValueChange = { countries = it },
                    label = { Text("Countries (ISO codes, e.g. GB)") },
                    enabled = !isSaving,
                    singleLine = true,
                    isError = !countryList.all { it.length == 2 },
                    supportingText = { Text("The setup wizard suggests this calendar for a device in these countries") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Weekend", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DayOfWeek.entries.forEach { day ->
                        FilterChip(
                            selected = day in weekend,
                            onClick = { weekend = if (day in weekend) weekend - day else weekend + day },
                            label = {
                                Text(
                                    day.name
                                        .take(3)
                                        .lowercase()
                                        .replaceFirstChar { it.uppercase() },
                                )
                            },
                            enabled = !isSaving,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { year-- }, enabled = !isSaving) { Text("‹") }
                    Text("$year", style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = { year++ }, enabled = !isSaving) { Text("›") }
                }
                YearDatesEditor(
                    title = "Holidays",
                    dates = holidays,
                    year = year,
                    enabled = !isSaving,
                    onDatesChange = { holidays = it },
                )
                YearDatesEditor(
                    title = "Working days on a weekend",
                    dates = workingDays,
                    year = year,
                    enabled = !isSaving,
                    onDatesChange = { workingDays = it },
                )
                errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    isSaving = true
                    errorMessage = null
                    scope.launch {
                        try {
                            onSave(
                                HolidayCalendar(
                                    id = code,
                                    name = name.trim().ifEmpty { code },
                                    timeZone = timeZone.trim(),
                                    countries = countryList.distinct(),
                                    weekend = weekend.sorted(),
                                    holidays = holidays.sorted(),
                                    workingDays = workingDays.sorted(),
                                ),
                            )
                        } catch (expected: Exception) {
                            logger.e(expected) { "Failed to save holiday calendar $code" }
                            errorMessage = "Failed to save: ${expected.message}"
                            isSaving = false
                        }
                    }
                },
                enabled = canSave,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isSaving) { Text("Cancel") } },
    )
}

/** [dates] in [year] as removable chips, plus a field that adds one. Dates in other years are kept as they are. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun YearDatesEditor(
    title: String,
    dates: Set<LocalDate>,
    year: Int,
    enabled: Boolean,
    onDatesChange: (Set<LocalDate>) -> Unit,
) {
    var input by remember(year) { mutableStateOf("") }
    val parsed = runCatching { LocalDate.parse(input.trim()) }.getOrNull()
    Text(title, style = MaterialTheme.typography.labelLarge)
    val inYear = dates.filter { it.year == year }.sorted()
    if (inYear.isEmpty()) {
        Text("None in $year", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        inYear.forEach { date ->
            InputChip(
                selected = false,
                onClick = { onDatesChange(dates - date) },
                label = { Text("$date ✕") },
                enabled = enabled,
            )
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("Add (YYYY-MM-DD)") },
            singleLine = true,
            enabled = enabled,
            isError = input.isNotBlank() && parsed == null,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = {
                parsed?.let { onDatesChange(dates + it) }
                input = ""
            },
            enabled = enabled && parsed != null,
        ) { Text("Add") }
    }
}
