@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.moneymanager.ui.screens.apistrategy

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import kotlin.test.Test

class ApiDownloadNoticeBannerTest {
    @Test
    fun `the banner shows the notice the front end gives`() =
        runMoneyManagerComposeUiTest {
            setContent {
                MaterialTheme {
                    CompositionLocalProvider(LocalApiDownloadNotice provides "Downloads need the desktop app") {
                        ApiDownloadNoticeBanner()
                    }
                }
            }
            onNodeWithText("Downloads need the desktop app").assertIsDisplayed()
        }

    @Test
    fun `without a notice the banner shows nothing`() =
        runMoneyManagerComposeUiTest {
            setContent {
                MaterialTheme {
                    Column {
                        ApiDownloadNoticeBanner()
                        Text("below")
                    }
                }
            }
            onNodeWithText("below").assertIsDisplayed()
            onNodeWithText("Downloads", substring = true).assertDoesNotExist()
        }
}
