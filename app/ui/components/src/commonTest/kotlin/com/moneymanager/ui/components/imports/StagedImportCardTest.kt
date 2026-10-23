@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.moneymanager.ui.components.imports

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.onNodeWithText
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import kotlin.test.Test
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val unimported =
    CsvImport(
        id = CsvImportId(Uuid.random()),
        tableName = "t",
        originalFileName = "weird.csv",
        importTimestamp = Instant.fromEpochMilliseconds(0),
        rowCount = 1,
        columnCount = 1,
        columns = emptyList(),
        deviceInfo = DeviceInfo.Jvm("os", "machine"),
        fileChecksum = "weird.csv",
        fileLastModified = Instant.fromEpochMilliseconds(0),
    )

// A factory rather than a @Composable helper: Qodana holds every function in a test source set to the
// lowercase test-naming convention, which a PascalCase composable can't meet.
private fun cardContent(
    matchedStrategyName: String?,
    noMatchingStrategy: Boolean,
): @Composable () -> Unit =
    {
        MaterialTheme {
            StagedImportCard(
                import = unimported,
                placement =
                    StagedImportCardPlacement(
                        ignored = false,
                        showDirectories = false,
                        matchedStrategyName = matchedStrategyName,
                        noMatchingStrategy = noMatchingStrategy,
                    ),
                metadataText = "1 rows, 1 columns",
                dateRange = null,
                sourceAccountName = null,
                sourceDirectories = emptyList(),
                onClick = {},
                onSetIgnored = {},
            )
        }
    }

class StagedImportCardTest {
    @Test
    fun unmatchedFile_warnsThatNoStrategyMatches() {
        runMoneyManagerComposeUiTest {
            setContent(cardContent(matchedStrategyName = null, noMatchingStrategy = true))

            onNodeWithText("⚠ No matching strategy").assertExists()
            onNodeWithText("Strategy:", substring = true).assertDoesNotExist()
        }
    }

    @Test
    fun matchedFile_namesItsStrategy() {
        runMoneyManagerComposeUiTest {
            setContent(cardContent(matchedStrategyName = "Monzo CSV", noMatchingStrategy = false))

            onNodeWithText("Strategy: Monzo CSV").assertExists()
            onNodeWithText("⚠ No matching strategy").assertDoesNotExist()
        }
    }
}
