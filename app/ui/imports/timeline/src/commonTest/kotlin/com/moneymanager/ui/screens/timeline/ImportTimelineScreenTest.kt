@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.ui.screens.timeline

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import com.moneymanager.domain.model.timeline.ImportFileDateRange
import com.moneymanager.domain.model.timeline.TimelineSourceKind
import com.moneymanager.domain.repository.ImportTimelineReadRepository
import com.moneymanager.ui.error.ProvideSchemaAwareScope
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class ImportTimelineScreenTest {
    private fun createRepository(ranges: List<ImportFileDateRange>): ImportTimelineReadRepository =
        object : ImportTimelineReadRepository {
            override fun getCsvImportDateRanges(): Flow<List<ImportFileDateRange>> = flowOf(emptyList())

            override fun getQifImportDateRanges(): Flow<List<ImportFileDateRange>> = flowOf(emptyList())

            override fun getApiSessionDateRanges(): Flow<List<ImportFileDateRange>> = flowOf(emptyList())

            override fun getManualDateRange(): Flow<ImportFileDateRange?> = flowOf(null)

            override fun getAllDateRanges(): Flow<List<ImportFileDateRange>> = flowOf(ranges)
        }

    private fun range(
        strategyName: String?,
        kind: TimelineSourceKind = TimelineSourceKind.CSV,
        fileName: String = "file.csv",
    ): ImportFileDateRange =
        ImportFileDateRange(
            kind = kind,
            fileId = "$kind-$fileName",
            fileName = fileName,
            strategyName = strategyName,
            earliest = Instant.parse("2024-01-01T00:00:00Z"),
            latest = Instant.parse("2024-03-01T00:00:00Z"),
            transactionCount = 42,
        )

    @Test
    fun timelineScreen_displaysEmptyState_whenNoRanges() =
        runMoneyManagerComposeUiTest {
            setContent {
                ProvideSchemaAwareScope {
                    ImportTimelineScreen(importTimelineRepository = createRepository(emptyList()))
                }
            }

            onNodeWithText("No imported transactions yet").assertIsDisplayed()
        }

    @Test
    fun timelineScreen_displaysStrategyRowsAndManualRow() =
        runMoneyManagerComposeUiTest {
            val ranges =
                listOf(
                    range(strategyName = "Monzo", kind = TimelineSourceKind.CSV, fileName = "monzo.csv"),
                    range(strategyName = "Monzo", kind = TimelineSourceKind.QIF, fileName = "monzo.qif"),
                    range(strategyName = "Crypto.com Card", kind = TimelineSourceKind.CSV, fileName = "card.csv"),
                    range(strategyName = null, kind = TimelineSourceKind.MANUAL, fileName = "Manual entries"),
                )

            setContent {
                ProvideSchemaAwareScope {
                    ImportTimelineScreen(importTimelineRepository = createRepository(ranges))
                }
            }

            // Labels can appear both as a timeline row and in the gaps table below it.
            onAllNodesWithText("Monzo").onFirst().assertIsDisplayed()
            onAllNodesWithText("Crypto.com Card").onFirst().assertIsDisplayed()
            onAllNodesWithText("Manual entries").onFirst().assertIsDisplayed()
            // Monzo groups the CSV and QIF files into one row.
            onNodeWithText("2 files").assertIsDisplayed()
            // Every row's data stops before today, so the gaps table lists them.
            onNodeWithText("Gaps").assertIsDisplayed()
        }
}
