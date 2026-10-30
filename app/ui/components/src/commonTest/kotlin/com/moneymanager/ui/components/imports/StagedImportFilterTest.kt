package com.moneymanager.ui.components.imports

import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.ImportDirectoryId
import com.moneymanager.domain.model.ImportFileKind
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvStrategyConfig
import com.moneymanager.domain.model.importdirectory.ImportDirectory
import com.moneymanager.domain.model.importdirectory.ImportDirectoryProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlin.uuid.Uuid

class StagedImportFilterTest {
    private val epoch = Instant.fromEpochMilliseconds(0)
    private val banking = directory("Banking", parentId = null)
    private val monzo = directory("Banking / Monzo", parentId = banking.id)
    private val amex = directory("Amex", parentId = null)
    private val uploads = directory("Banking / Monzo / Uploads", parentId = banking.id)
    private val monzoStrategy = strategy("Monzo")
    private val amexStrategy = strategy("American Express (PDF)")

    private val monzoCsv = file("monzo-2024.csv")
    private val bankingQif = file("Banking.qif")
    private val amexPdf = file("amex-march.PDF", appliedWith = amexStrategy)
    private val pickedXlsx = file("picked.xlsx")
    private val files = listOf(monzoCsv, bankingQif, amexPdf, pickedXlsx)

    private val importDirectories =
        mapOf(monzoCsv.id to listOf(monzo), bankingQif.id to listOf(banking), amexPdf.id to listOf(amex))
    private val matched = mapOf(monzoCsv.id to monzoStrategy, bankingQif.id to null, pickedXlsx.id to null)

    private fun filtered(filter: StagedImportFilter) =
        filterStagedImports(files, filter, importDirectories, listOf(banking, monzo, amex), matched)

    @Test
    fun `no filter keeps every file`() {
        assertEquals(files, filtered(StagedImportFilter()))
    }

    @Test
    fun `search matches part of the file name ignoring case`() {
        assertEquals(listOf(monzoCsv), filtered(StagedImportFilter(text = "MONZO")))
        assertEquals(listOf(amexPdf), filtered(StagedImportFilter(text = " march ")))
    }

    @Test
    fun `file type keeps the chosen kinds`() {
        assertEquals(
            listOf(amexPdf, pickedXlsx),
            filtered(StagedImportFilter(kinds = setOf(ImportFileKind.PDF, ImportFileKind.XLSX))),
        )
    }

    @Test
    fun `strategy reads the applied strategy else the matched one and null means none`() {
        assertEquals(listOf(monzoCsv), filtered(StagedImportFilter(strategies = setOf(monzoStrategy.id))))
        assertEquals(listOf(amexPdf), filtered(StagedImportFilter(strategies = setOf(amexStrategy.id))))
        assertEquals(listOf(bankingQif, pickedXlsx), filtered(StagedImportFilter(strategies = setOf(null))))
    }

    @Test
    fun `a top-level directory keeps the files of its subfolders`() {
        assertEquals(listOf(monzoCsv, bankingQif), filtered(StagedImportFilter(directories = setOf(banking.id))))
        assertEquals(listOf(monzoCsv), filtered(StagedImportFilter(directories = setOf(monzo.id))))
    }

    @Test
    fun `a subfolder keeps the files of the folders nested beneath it`() {
        val upload = file("upload.csv")
        val nested =
            filterStagedImports(
                listOf(monzoCsv, upload, bankingQif),
                StagedImportFilter(directories = setOf(monzo.id)),
                importDirectories + (upload.id to listOf(uploads)),
                listOf(banking, monzo, uploads, amex),
                matched,
            )
        assertEquals(listOf(monzoCsv, upload), nested)
    }

    @Test
    fun `null directory keeps the files added by hand`() {
        assertEquals(listOf(pickedXlsx), filtered(StagedImportFilter(directories = setOf(null))))
    }

    @Test
    fun `filters combine`() {
        assertEquals(
            listOf(bankingQif),
            filtered(StagedImportFilter(text = "bank", directories = setOf(banking.id), strategies = setOf(null))),
        )
    }

    @Test
    fun `options list what the files have`() {
        assertEquals(
            listOf(ImportFileKind.CSV, ImportFileKind.QIF, ImportFileKind.XLSX, ImportFileKind.PDF),
            stagedImportKinds(files),
        )
        assertEquals(
            listOf(
                StagedImportStrategyOption(amexStrategy.id, "American Express (PDF)"),
                StagedImportStrategyOption(monzoStrategy.id, "Monzo"),
                StagedImportStrategyOption(null, NO_STRATEGY_LABEL),
            ),
            stagedImportStrategyOptions(files, matched),
        )
        assertEquals(
            listOf(amex.id to "Amex", banking.id to "Banking", monzo.id to "Banking / Monzo", null to MANUALLY_ADDED_LABEL),
            stagedImportDirectoryOptions(files, importDirectories, listOf(banking, monzo, amex)),
        )
    }

    @Test
    fun `a top-level folder is an option when only its subfolders have files`() {
        assertEquals(
            listOf(banking.id to "Banking", monzo.id to "Banking / Monzo"),
            stagedImportDirectoryOptions(listOf(monzoCsv), importDirectories, listOf(banking, monzo, amex)),
        )
    }

    private fun file(
        name: String,
        appliedWith: CsvImportStrategy? = null,
    ) = CsvImport(
        id = CsvImportId(Uuid.random()),
        tableName = "csv_import",
        originalFileName = name,
        importTimestamp = epoch,
        rowCount = 1,
        columnCount = 0,
        columns = emptyList(),
        deviceInfo = DeviceInfo.Jvm("os", "machine"),
        fileChecksum = name,
        fileLastModified = epoch,
        lastAppliedStrategyId = appliedWith?.id,
        lastAppliedStrategyName = appliedWith?.name,
        lastAppliedAt = appliedWith?.let { epoch },
    )

    private fun directory(
        path: String,
        parentId: ImportDirectoryId?,
    ) = ImportDirectory(
        id = ImportDirectoryId(Uuid.random()),
        name = path.substringAfterLast(" / "),
        provider = ImportDirectoryProvider.GDRIVE,
        folderRef = path,
        displayPath = path,
        topLevel = parentId == null,
        parentId = parentId,
        createdAt = epoch,
        updatedAt = epoch,
    )

    private fun strategy(name: String) =
        CsvImportStrategy(
            id = CsvImportStrategyId(Uuid.random()),
            name = name,
            config = CsvStrategyConfig(identificationColumns = emptySet(), fieldMappings = emptyMap()),
            createdAt = epoch,
            updatedAt = epoch,
        )
}
