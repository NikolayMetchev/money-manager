@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.moneymanager.ui.components.imports

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.ImportDirectoryId
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvStrategyConfig
import com.moneymanager.domain.model.importdirectory.ImportDirectory
import com.moneymanager.domain.model.importdirectory.ImportDirectoryProvider
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val epoch = Instant.fromEpochMilliseconds(0)

private val statements =
    ImportDirectory(
        id = ImportDirectoryId(Uuid.random()),
        name = "Statements",
        provider = ImportDirectoryProvider.GDRIVE,
        folderRef = "statements",
        displayPath = "Statements",
        createdAt = epoch,
        updatedAt = epoch,
    )
private val santander =
    statements.copy(
        id = ImportDirectoryId(Uuid.random()),
        name = "Statements / Santander",
        folderRef = "santander",
        displayPath = "Statements / Santander",
        topLevel = false,
        parentId = statements.id,
    )

private val bank =
    CsvImportStrategy(
        id = CsvImportStrategyId(Uuid.random()),
        name = "QIF Bank",
        config = CsvStrategyConfig(identificationColumns = emptySet(), fieldMappings = emptyMap()),
        createdAt = epoch,
        updatedAt = epoch,
    )

private fun qifImport(
    fileName: String,
    appliedWith: CsvImportStrategy? = null,
) = CsvImport(
    id = CsvImportId(Uuid.random()),
    tableName = "csv_import",
    originalFileName = fileName,
    importTimestamp = epoch,
    rowCount = 1,
    columnCount = 0,
    columns = emptyList(),
    deviceInfo = DeviceInfo.Jvm("os", "machine"),
    fileChecksum = fileName,
    fileLastModified = epoch,
    applicationCount = if (appliedWith == null) 0 else 1,
    lastAppliedStrategyId = appliedWith?.id,
    lastAppliedStrategyName = appliedWith?.name,
    lastAppliedAt = appliedWith?.let { epoch },
)

private val santanderFile = qifImport("santander.qif")
private val manualFile = qifImport("manual.qif")
private val importedFile = qifImport("old.qif", appliedWith = bank)

// A factory rather than a @Composable helper: Qodana holds every function in a test source set to the
// lowercase test-naming convention, which a PascalCase composable can't meet.
private fun tabsContent(
    onImportAll: (List<CsvImport>) -> Unit = {},
    onReimportAll: (List<CsvImport>) -> Unit = {},
    filter: StagedImportFilter = StagedImportFilter(),
): @Composable () -> Unit =
    {
        MaterialTheme {
            Column {
                StagedImportsTabs(
                    imports = listOf(santanderFile, manualFile, importedFile),
                    data =
                        StagedImportsData(
                            directories = listOf(statements, santander),
                            importDirectories = mapOf(santanderFile.id to listOf(santander)),
                            matchedStrategies = mapOf(santanderFile.id to bank, manualFile.id to null),
                            strategies = listOf(bank),
                            accounts = emptyList(),
                            directoryAccounts = emptyMap(),
                        ),
                    onImportAll = onImportAll,
                    onReimportAll = onReimportAll,
                    onSetIgnored = { _, _ -> },
                    filter = filter,
                    header = { item { Text("Header") } },
                ) { import, placement ->
                    Text(if (placement.noMatchingStrategy) "${import.originalFileName} unmatched" else import.originalFileName)
                }
            }
        }
    }

class StagedImportsTabsTest {
    @Test
    fun unimportedFiles_areGroupedByFolderThenStrategy() {
        runMoneyManagerComposeUiTest {
            var importAll: List<CsvImport>? = null
            setContent(tabsContent(onImportAll = { importAll = it }))

            onNodeWithText("Statements (1)").assertExists()
            onNodeWithText("Santander (1)").assertExists()
            onNodeWithText("Strategy: QIF Bank (1)").assertExists()
            onNodeWithText("santander.qif").assertExists()
            onNodeWithText("$MANUALLY_ADDED_LABEL (1)").assertExists()
            onNodeWithText("⚠ No strategy (1)").assertExists()
            onNodeWithText("manual.qif unmatched").assertExists()

            onNodeWithText("Import all (2)").performClick()
            waitForIdle()
            assertEquals(listOf(santanderFile, manualFile), importAll)
        }
    }

    @Test
    fun importedFiles_areGroupedByAppliedStrategy_withoutUnimportWhenTheFormatHasNone() {
        runMoneyManagerComposeUiTest {
            var reimportAll: List<CsvImport>? = null
            setContent(tabsContent(onReimportAll = { reimportAll = it }))

            onNodeWithText("Imported (1)").performClick()
            waitForIdle()
            onNodeWithText("QIF Bank (1)").assertExists()
            onNodeWithText("old.qif").assertExists()
            onNodeWithText("Unimport all", substring = true).assertDoesNotExist()

            onNodeWithText("Re-import all (1)").performClick()
            waitForIdle()
            assertEquals(listOf(importedFile), reimportAll)
        }
    }

    @Test
    fun aFilter_narrowsTheCountsTheListAndImportAll() {
        runMoneyManagerComposeUiTest {
            var importAll: List<CsvImport>? = null
            setContent(tabsContent(onImportAll = { importAll = it }, filter = StagedImportFilter(directories = setOf(statements.id))))

            onNodeWithText("Header").assertExists()
            onNodeWithText("Unimported (1)").assertExists()
            onNodeWithText("Imported (0)").assertExists()
            onNodeWithText("santander.qif").assertExists()
            onNodeWithText("manual.qif unmatched").assertDoesNotExist()

            onNodeWithText("Import all (1)").performClick()
            waitForIdle()
            assertEquals(listOf(santanderFile), importAll)
        }
    }

    @Test
    fun aFilterNothingMatches_saysSo() {
        runMoneyManagerComposeUiTest {
            setContent(tabsContent(filter = StagedImportFilter(text = "nothing like this")))

            onNodeWithText("Unimported (0)").assertExists()
            onNodeWithText(NO_MATCHING_FILES_MESSAGE).assertExists()
        }
    }
}
