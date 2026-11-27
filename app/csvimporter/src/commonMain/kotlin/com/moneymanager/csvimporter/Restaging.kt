package com.moneymanager.csvimporter

import co.touchlab.kermit.Logger
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.repository.CsvImportReadRepository
import com.moneymanager.importengineapi.CsvImportMutation
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.applyCsvImportMutations
import com.moneymanager.xlsx.createXlsxParser
import kotlinx.coroutines.flow.first

private val logger = Logger.withTag("Restaging")

/**
 * [csvImport] staged as [strategy] reads it (see [restageXlsxForStrategy] and [restagePdfForStrategy]): reloaded
 * when its rows had to be staged again, else as it was.
 */
suspend fun restageForStrategy(
    csvImport: CsvImport,
    strategy: CsvImportStrategy,
    csvImportRepository: CsvImportReadRepository,
    importEngine: ImportEngine,
): CsvImport =
    if (strategy.config.sqliteQuery != null) {
        planSqliteRestage(csvImport, strategy, csvImportRepository)?.apply(importEngine, csvImportRepository) ?: csvImport
    } else {
        restagePdfForStrategy(
            restageXlsxForStrategy(csvImport, strategy, csvImportRepository, importEngine),
            strategy,
            csvImportRepository,
            importEngine,
        )
    }

internal suspend fun planSqliteRestage(
    staged: CsvImport,
    strategy: CsvImportStrategy,
    reads: CsvImportReadRepository,
): CsvImportMutation.Restage? {
    val query = strategy.config.sqliteQuery ?: return null
    val file = requireNotNull(reads.getSqliteFile(staged.id))
    if (file.stagedQuery == query || staged.lastAppliedAt != null) return null
    val projection = readSqliteImport(staged.originalFileName, file.bytes, listOf(strategy))
    require(projection.strategy != null) { "SQLite query does not match the file schema" }
    return CsvImportMutation.Restage(staged.id, projection.data.headers, projection.data.rows, sqliteQuery = query)
}

private suspend fun restagePdfForStrategy(
    csvImport: CsvImport,
    strategy: CsvImportStrategy,
    csvImportRepository: CsvImportReadRepository,
    importEngine: ImportEngine,
): CsvImport = planPdfRestage(csvImport, strategy, csvImportRepository)?.apply(importEngine, csvImportRepository) ?: csvImport

/** The rows the strategy assembles from a PDF's staged text, or null when the staging can be used as it is. */
suspend fun planPdfRestage(
    csvImport: CsvImport,
    strategy: CsvImportStrategy,
    csvImportRepository: CsvImportReadRepository,
): CsvImportMutation.Restage? {
    val pdf = csvImportRepository.getPdfSource(csvImport.id) ?: return null
    val lineConfig = strategy.config.pdfLines
    val rulesHash = lineConfig?.let { PdfRowAssembler.rulesHash(it) }
    if (pdf.stagedRulesHash == rulesHash) return null
    if (csvImport.lastAppliedAt != null) {
        // Its transactions point at the staged rows by index; a re-import reserves them before replacing the rows.
        logger.w { "PDF import ${csvImport.id.id} was imported with other line rules; keeping its rows until it's re-imported" }
        return null
    }
    val staged = lineConfig?.let { PdfRowAssembler(it).assemble(pdf.lines) } ?: PdfRowAssembler.rawLines(pdf.lines)
    return CsvImportMutation.Restage(csvImport.id, staged.headers, staged.rows, pdfRulesHash = rulesHash)
}

internal suspend fun restageXlsxForStrategy(
    csvImport: CsvImport,
    strategy: CsvImportStrategy,
    csvImportRepository: CsvImportReadRepository,
    importEngine: ImportEngine,
): CsvImport = planXlsxRestage(csvImport, strategy, csvImportRepository)?.apply(importEngine, csvImportRepository) ?: csvImport

/** The rows of the strategy's worksheet when it differs from the staged sheet. */
suspend fun planXlsxRestage(
    csvImport: CsvImport,
    strategy: CsvImportStrategy,
    csvImportRepository: CsvImportReadRepository,
): CsvImportMutation.Restage? {
    val targetSheet = strategy.worksheetName ?: return null
    val blob = csvImportRepository.getXlsxBlob(csvImport.id) ?: return null
    if (blob.worksheetName == targetSheet) return null
    val parsed = createXlsxParser().parse(blob.fileBytes, targetSheet)
    return CsvImportMutation.Restage(csvImport.id, parsed.headers, parsed.rows, worksheetName = targetSheet)
}

internal suspend fun CsvImportMutation.Restage.apply(
    importEngine: ImportEngine,
    reads: CsvImportReadRepository,
): CsvImport? {
    importEngine.applyCsvImportMutations(listOf(this))
    logger.i { "Re-staged import $id with ${rows.size} rows" }
    return reads.getImport(id).first()
}
