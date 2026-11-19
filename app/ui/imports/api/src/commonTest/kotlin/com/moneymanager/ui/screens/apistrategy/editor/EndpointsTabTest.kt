@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.moneymanager.ui.screens.apistrategy.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.moneymanager.builtin.BuiltInApiStrategies
import com.moneymanager.domain.model.apistrategy.ApiPaging
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock

class EndpointsTabTest {
    @Test
    fun `edit date ranges next-page parameters and saved response paths`() =
        runMoneyManagerComposeUiTest {
            val state = ApiStrategyEditorState(BuiltInApiStrategies.starling(Clock.System.now()))

            fun endpoint() = checkNotNull(state.config.bankTransactions).endpoint
            val previousPath = endpoint().historicalPaths.first()
            setContent {
                MaterialTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        EndpointsTab(state, enabled = true)
                    }
                }
            }

            val split = onNodeWithContentDescription("Split history into fixed-length windows")
            split.performScrollTo().performClick()
            onNodeWithText("Window days").performScrollTo().performTextReplacement("90")
            runOnIdle { assertEquals(90, endpoint().pagination?.window?.windowDays) }

            split.performScrollTo().performClick()
            runOnIdle {
                val window = assertNotNull(endpoint().pagination?.window)
                assertNull(window.windowDays)
                assertEquals("minTransactionTimestamp", window.startParam)
            }

            onNodeWithText("pageToFetch").performScrollTo().performTextReplacement("direction")
            runOnIdle {
                val paging = assertIs<ApiPaging.Token>(endpoint().pagination?.paging)
                assertEquals(listOf("direction"), paging.linkQueryParams)
            }

            onNodeWithText(previousPath).performScrollTo().performTextReplacement("/older/{account.id}")
            runOnIdle { assertEquals("/older/{account.id}", endpoint().historicalPaths.first()) }
        }
}
