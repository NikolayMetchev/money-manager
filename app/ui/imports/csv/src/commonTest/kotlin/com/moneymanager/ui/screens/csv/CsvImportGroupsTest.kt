package com.moneymanager.ui.screens.csv

import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.ImportDirectoryId
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvStrategyConfig
import com.moneymanager.domain.model.importdirectory.ImportDirectory
import com.moneymanager.domain.model.importdirectory.ImportDirectoryProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlin.uuid.Uuid

class CsvImportGroupsTest {
    private val monzoA = csvImport("monzo-a.csv")
    private val monzoB = csvImport("monzo-b.csv")
    private val unmatched = csvImport("weird.csv")
    private val manual = csvImport("manual.csv")
    private val monzo =
        CsvImportStrategy(
            id = CsvImportStrategyId(Uuid.random()),
            name = "Monzo CSV",
            config = CsvStrategyConfig(identificationColumns = emptySet(), fieldMappings = emptyMap()),
            createdAt = Instant.fromEpochMilliseconds(0),
            updatedAt = Instant.fromEpochMilliseconds(0),
        )

    private val drive =
        directory(displayPath = "My Drive / Statements", folderRef = "drive-root", provider = ImportDirectoryProvider.GDRIVE)
    private val monzoFolder = directory("My Drive / Statements / 2024 / Monzo", "monzo-folder", parent = drive)
    private val archiveFolder = directory("My Drive / Statements / Archive", "archive-folder", parent = drive)
    private val local = directory(displayPath = null, folderRef = "/home/me/csv")

    @Test
    fun `builds a folder tree with strategies nested and manual files last`() {
        val placement =
            mapOf(
                monzoA.id to listOf(monzoFolder),
                monzoB.id to listOf(monzoFolder),
                unmatched.id to listOf(archiveFolder),
            )
        val matches = mapOf(monzoA.id to monzo, monzoB.id to monzo, unmatched.id to null, manual.id to monzo)

        val tree = buildUnimportedDirectoryTree(listOf(manual, monzoA, unmatched, monzoB), placement, allDirectories, matches)

        assertEquals(listOf("My Drive / Statements", MANUALLY_ADDED_LABEL), tree.map { it.label })
        val root = tree[0]
        assertEquals(ImportDirectoryProvider.GDRIVE, root.provider)
        assertEquals(3, root.allImports.size)
        assertEquals(emptyList(), root.strategyGroups)
        // "2024" holds no files of its own, so it collapses into its only child.
        assertEquals(listOf("2024 / Monzo", "Archive"), root.children.map { it.label })
        assertEquals(
            listOf(monzoA, monzoB),
            root.children[0]
                .strategyGroups
                .single()
                .imports,
        )
        assertEquals(
            "Monzo CSV",
            root.children[0]
                .strategyGroups
                .single()
                .label,
        )
        assertEquals(
            "No strategy",
            root.children[1]
                .strategyGroups
                .single()
                .label,
        )
        assertEquals(null, tree[1].provider)
        assertEquals(listOf(manual), tree[1].allImports)
    }

    @Test
    fun `lists a file shared by several directories once under the first`() {
        val placement = mapOf(monzoA.id to listOf(local, drive), monzoB.id to listOf(drive))

        val tree = buildUnimportedDirectoryTree(listOf(monzoA, monzoB), placement, allDirectories, matches = null)

        assertEquals(listOf("/home/me/csv", "My Drive / Statements"), tree.map { it.label })
        assertEquals(listOf(monzoA), tree[0].allImports)
        assertEquals("Matching strategies…", tree[0].strategyGroups.single().label)
    }

    @Test
    fun `resolves import directories sorted by label and drops unknown ones`() {
        val removed = ImportDirectoryId(Uuid.random())

        val resolved =
            resolveImportDirectories(
                mapOf(monzoA.id to listOf(drive.id, local.id), monzoB.id to listOf(removed)),
                allDirectories,
            )

        assertEquals(mapOf(monzoA.id to listOf(local, drive)), resolved)
    }

    private val allDirectories get() = listOf(drive, monzoFolder, archiveFolder, local)

    private fun csvImport(fileName: String) =
        CsvImport(
            id = CsvImportId(Uuid.random()),
            tableName = "t",
            originalFileName = fileName,
            importTimestamp = Instant.fromEpochMilliseconds(0),
            rowCount = 1,
            columnCount = 1,
            columns = emptyList(),
            deviceInfo = DeviceInfo.Jvm("os", "machine"),
            fileChecksum = fileName,
            fileLastModified = Instant.fromEpochMilliseconds(0),
        )

    private fun directory(
        displayPath: String?,
        folderRef: String,
        provider: ImportDirectoryProvider = ImportDirectoryProvider.LOCAL,
        parent: ImportDirectory? = null,
    ) = ImportDirectory(
        id = ImportDirectoryId(Uuid.random()),
        name = folderRef,
        provider = parent?.provider ?: provider,
        folderRef = folderRef,
        displayPath = displayPath,
        topLevel = parent == null,
        parentId = parent?.id,
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )
}
