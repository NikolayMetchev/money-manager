@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.moneymanager.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PasswordDialogsTest {
    @Test
    fun closeDialog_asksForThePasswordOnlyToEncryptTheUpload() =
        runMoneyManagerComposeUiTest {
            var confirmed: Triple<Boolean, Boolean, String?>? = null
            setContent {
                CloseDatabaseDialog(
                    remoteName = "Drive",
                    localChanged = true,
                    needsPassword = true,
                    onConfirm = { upload, keepLocal, password -> confirmed = Triple(upload, keepLocal, password) },
                    onCancel = {},
                )
            }

            onNode(hasSetTextAction()).performTextInput("secret")
            onNodeWithText("Close").performClick()
            waitForIdle()

            assertEquals(Triple(true, true, "secret"), confirmed)
        }

    @Test
    fun unlockDialog_unlocksWithTheTypedPassword() =
        runMoneyManagerComposeUiTest {
            var unlocked: String? = null
            setContent {
                RemoteDatabaseUnlockDialog(
                    state = RemoteUnlockState(prompt = "Unlock the cloud copy"),
                    onUnlock = { unlocked = it },
                    onReconnect = {},
                )
            }

            onNodeWithText("Unlock the cloud copy").assertIsDisplayed()
            onNode(hasSetTextAction()).performTextInput("hunter2")
            onNodeWithText("Unlock").performClick()
            waitForIdle()

            assertEquals("hunter2", unlocked)
        }

    @Test
    fun unlockDialog_offersReconnectingWhenTheSignInExpired() =
        runMoneyManagerComposeUiTest {
            var reconnected = false
            setContent {
                RemoteDatabaseUnlockDialog(
                    state = RemoteUnlockState(prompt = "Unlock the cloud copy", needsReconnect = true),
                    onUnlock = {},
                    onReconnect = { reconnected = true },
                )
            }

            onNodeWithText("Reconnect to Google Drive").performClick()
            waitForIdle()

            assertEquals(true, reconnected)
        }
}
