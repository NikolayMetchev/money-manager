@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.moneymanager.ui.screens.csv

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.moneymanager.ui.components.imports.ImportFileCard
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class ImportFileCardUnimportTest {
    private val appliedAt = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    @Composable
    private fun Card(
        lastAppliedAt: Instant?,
        lastUnimportedAt: Instant? = null,
        ignored: Boolean = false,
        onUnimport: (() -> Unit)? = null,
    ) {
        MaterialTheme {
            ImportFileCard(
                fileName = "statement.csv",
                metadataText = "2 rows, 3 columns",
                addedAt = appliedAt,
                errorCount = 0,
                lastAppliedAt = lastAppliedAt,
                applicationCount = if (lastAppliedAt == null) 0 else 1,
                lastAppliedStrategyName = "Monzo",
                dateRange = null,
                ignored = ignored,
                onClick = {},
                onSetIgnored = {},
                lastUnimportedAt = lastUnimportedAt,
                onUnimport = onUnimport,
            )
        }
    }

    @Test
    fun importedFile_offersUnimport() {
        runMoneyManagerComposeUiTest {
            var clicks = 0
            setContent { Card(lastAppliedAt = appliedAt, onUnimport = { clicks++ }) }

            onNodeWithText("Unimport").performClick()
            waitForIdle()
            assertEquals(1, clicks)
        }
    }

    @Test
    fun unimportedFile_saysSoInsteadOfNotImportedYet() {
        runMoneyManagerComposeUiTest {
            setContent { Card(lastAppliedAt = null, lastUnimportedAt = appliedAt, ignored = true) }

            onNodeWithText("Unimported on", substring = true).assertExists()
            onNodeWithText("Not imported yet").assertDoesNotExist()
            onNodeWithText("Restore").assertExists()
        }
    }
}
