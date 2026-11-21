@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.moneymanager.ui.util

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PasswordFieldTest {
    @Test
    fun typingReportsTheTextToTheCaller() =
        runMoneyManagerComposeUiTest {
            var password by mutableStateOf("")
            setContent { PasswordField(password, { password = it }, "Password") }

            onNode(hasSetTextAction()).performTextInput("hunter2")
            waitForIdle()

            assertEquals("hunter2", password)
        }

    @Test
    fun aValueSetByTheCallerReachesTheField() =
        runMoneyManagerComposeUiTest {
            var password by mutableStateOf("")
            setContent {
                Column {
                    PasswordField(password, { password = it }, "Password")
                    Button(onClick = { password = "from a key file" }) { Text("Load") }
                }
            }

            onNodeWithText("Load").performClick()
            waitForIdle()
            onNode(hasSetTextAction()).performTextInput("!")
            waitForIdle()

            assertEquals("from a key file!", password)
        }

    @Test
    fun theHintsAndTheLabelAreShown() =
        runMoneyManagerComposeUiTest {
            setContent {
                PasswordField(
                    value = "",
                    onValueChange = {},
                    label = "API key",
                    placeholder = "Paste it here",
                    supportingText = "Loaded from key.pem",
                    isError = true,
                )
            }

            onNodeWithText("API key").assertIsDisplayed()
            onNodeWithText("Loaded from key.pem").assertIsDisplayed()
        }

    @Test
    fun enterSubmitsWhenAnActionIsGiven() =
        runMoneyManagerComposeUiTest {
            var submitted = 0
            setContent { PasswordField("secret", {}, "Password", onSubmit = { submitted++ }) }

            onNode(hasSetTextAction()).performClick()
            onNode(hasSetTextAction()).performKeyInput {
                keyDown(Key.Enter)
                keyUp(Key.Enter)
            }
            waitForIdle()

            assertEquals(1, submitted)
        }
}
