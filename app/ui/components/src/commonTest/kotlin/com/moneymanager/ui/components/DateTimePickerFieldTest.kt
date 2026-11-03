@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.moneymanager.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val CALENDAR = "📅"
private const val CLOCK = "🕔"
private val picked = LocalDateTime(2024, 3, 15, 10, 30, 45, 0)

class DateTimePickerFieldTest {
    @Test
    fun anEmptyField_showsNothingAndItsClearButtonIsDisabled() {
        runMoneyManagerComposeUiTest {
            setContent { MaterialTheme { DateTimePickerField(value = null, onValueChange = {}, onClear = {}) } }
            onNodeWithText("Clear").assertIsNotEnabled()
            onNodeWithText("2024-03-15").assertDoesNotExist()
        }
    }

    @Test
    fun aValue_isShownAndClearResetsIt() {
        runMoneyManagerComposeUiTest {
            var cleared = false
            setContent {
                MaterialTheme { DateTimePickerField(value = picked, onValueChange = {}, onClear = { cleared = true }) }
            }
            onNodeWithText("2024-03-15").assertExists()
            onNodeWithText("10:30").assertExists()
            onNodeWithText("Clear").performClick()
            waitForIdle()
            assertEquals(true, cleared)
        }
    }

    @Test
    fun withoutOnClear_thereIsNoClearButton() {
        runMoneyManagerComposeUiTest {
            setContent { MaterialTheme { DateTimePickerField(value = picked, onValueChange = {}) } }
            onNodeWithText("Clear").assertDoesNotExist()
        }
    }

    @Test
    fun confirmingTheDatePicker_keepsTheValueAndItsSeconds() {
        runMoneyManagerComposeUiTest {
            var result: LocalDateTime? = null
            setContent { MaterialTheme { DateTimePickerField(value = picked, onValueChange = { result = it }) } }
            onNodeWithText(CALENDAR).performClick()
            waitForIdle()
            onNodeWithText("OK").performClick()
            waitForIdle()
            assertEquals(picked, result)
        }
    }

    @Test
    fun cancellingThePickers_changesNothing() {
        runMoneyManagerComposeUiTest {
            var result: LocalDateTime? = null
            setContent { MaterialTheme { DateTimePickerField(value = picked, onValueChange = { result = it }) } }
            onNodeWithText(CALENDAR).performClick()
            waitForIdle()
            onNodeWithText("Cancel").performClick()
            waitForIdle()
            onNodeWithText(CLOCK).performClick()
            waitForIdle()
            onNodeWithText("Cancel").performClick()
            waitForIdle()
            assertNull(result)
        }
    }

    @Test
    fun confirmingTheTimePicker_keepsTheValue() {
        runMoneyManagerComposeUiTest {
            var result: LocalDateTime? = null
            setContent { MaterialTheme { DateTimePickerField(value = picked, onValueChange = { result = it }) } }
            onNodeWithText(CLOCK).performClick()
            waitForIdle()
            onNodeWithText("OK").performClick()
            waitForIdle()
            assertEquals(picked, result)
        }
    }

    @Test
    fun aPickBeyondTheLimits_isClampedIntoThem() {
        runMoneyManagerComposeUiTest {
            var result: LocalDateTime? = null
            val latest = LocalDateTime(2024, 3, 10, 12, 0)
            setContent {
                MaterialTheme { DateTimePickerField(value = picked, onValueChange = { result = it }, maxValue = latest) }
            }
            onNodeWithText(CLOCK).performClick()
            waitForIdle()
            onNodeWithText("OK").performClick()
            waitForIdle()
            assertEquals(latest, result)
        }
    }

    @Test
    fun anEmptyField_startsFromTodayClampedIntoTheLimits() {
        runMoneyManagerComposeUiTest {
            var result: LocalDateTime? = null
            val upper = LocalDateTime(2024, 3, 12, 23, 59)
            setContent {
                MaterialTheme {
                    DateTimePickerField(
                        value = null,
                        onValueChange = { result = it },
                        minValue = LocalDateTime(2024, 3, 10, 0, 0),
                        maxValue = upper,
                    )
                }
            }
            onNodeWithText(CALENDAR).performClick()
            waitForIdle()
            onNodeWithText("OK").performClick()
            waitForIdle()
            assertEquals(upper.date, result?.date)
        }
    }
}
