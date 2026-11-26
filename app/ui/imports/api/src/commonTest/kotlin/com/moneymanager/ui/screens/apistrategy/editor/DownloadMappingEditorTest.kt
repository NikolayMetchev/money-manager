package com.moneymanager.ui.screens.apistrategy.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.moneymanager.domain.model.apistrategy.ApiFanOut
import com.moneymanager.domain.model.apistrategy.ApiTransactionMappings
import com.moneymanager.domain.model.apistrategy.ApiValueSet
import com.moneymanager.domain.model.apistrategy.TimestampFormat
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DownloadMappingEditorTest {
    @Test
    fun `build trading pairs from downloaded assets and fixed quote currencies`() =
        runMoneyManagerComposeUiTest {
            val fanOut = mutableStateOf<ApiFanOut?>(null)
            setContent {
                MaterialTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        FanOutEditor(fanOut.value, { fanOut.value = it }, true)
                    }
                }
            }

            fun click(label: String) = onNodeWithText(label).performScrollTo().performClick()

            fun enter(
                label: String,
                value: String,
            ) = onNodeWithText(label).performScrollTo().performTextReplacement(value)

            fun select(
                label: String,
                option: String,
            ) {
                click(label)
                onNodeWithText(option).performClick()
            }

            onNodeWithContentDescription("Download once per value").performScrollTo().performClick()
            enter("Fan-out parameter", "symbol")
            select("Download values", "Cross product")
            select("Left set", "Union")
            click("Add set")
            select("Value source", "Data endpoint")
            enter("Source endpoint path", "/deposits")
            click("Add field")
            enter("Field 1", "asset")
            click("Add value")
            enter("Value 1", "USD")
            enter("Combination template", "{left}-{right}")

            val expected =
                ApiFanOut(
                    param = "symbol",
                    values =
                        ApiValueSet.CrossProduct(
                            ApiValueSet.Union(listOf(ApiValueSet.FromDataEndpoint("/deposits", listOf("asset")))),
                            ApiValueSet.Static(listOf("USD")),
                            "{left}-{right}",
                        ),
                )
            runOnIdle {
                assertEquals(expected, fanOut.value)
                assertTrue(expected.isValidForSave())
            }

            val validate = onNodeWithContentDescription("Filter against a validation set")
            validate.performScrollTo().performClick()
            runOnIdle { assertFalse(assertNotNull(fanOut.value).isValidForSave()) }
            validate.performScrollTo().performClick()
            runOnIdle { assertEquals(expected, fanOut.value) }

            onNodeWithContentDescription("Remove").performScrollTo().performClick()
            runOnIdle { assertFalse(assertNotNull(fanOut.value).isValidForSave()) }
        }

    @Test
    fun `changing transaction timestamp format clears an obsolete custom pattern`() =
        runMoneyManagerComposeUiTest {
            val mappings = mutableStateOf(ApiTransactionMappings())
            setContent {
                MaterialTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        TransactionMappingsFields(mappings.value, { mappings.value = it }, true)
                    }
                }
            }
            onNodeWithText("Timestamp format").performScrollTo().performClick()
            onNodeWithText("PATTERN").performClick()
            onNodeWithText("Timestamp pattern").performScrollTo().performTextReplacement("dd/MM/yyyy HH:mm")
            runOnIdle {
                assertEquals(TimestampFormat.PATTERN, mappings.value.timestampFormat)
                assertEquals("dd/MM/yyyy HH:mm", mappings.value.timestampPattern)
            }
            onNodeWithText("Timestamp format").performScrollTo().performClick()
            onNodeWithText("EPOCH_MS").performClick()
            runOnIdle {
                assertEquals(TimestampFormat.EPOCH_MS, mappings.value.timestampFormat)
                assertNull(mappings.value.timestampPattern)
            }
        }
}
