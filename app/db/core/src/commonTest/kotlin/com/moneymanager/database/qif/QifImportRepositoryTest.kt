@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.qif

import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.model.qif.QifImportRecord
import com.moneymanager.domain.model.qif.QifRecordSplit
import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class QifImportRepositoryTest : DbTest() {
    private val repo get() = repositories.qifImportRepository

    private fun bankRecord(
        index: Long,
        amount: String,
        splits: List<QifRecordSplit> = emptyList(),
        supported: Boolean = true,
        sectionType: String = "BANK",
    ) = QifImportRecord(
        recordIndex = index,
        sectionType = sectionType,
        accountName = "Checking",
        supported = supported,
        rawText = "D01/01/2022\nT$amount\n^",
        date = "01/01/2022",
        amount = amount,
        payee = "Shop $index",
        memo = "memo",
        splits = splits,
    )

    @Test
    fun createImport_thenReadBack_roundTripsRecordsAndMetadata() =
        runTest {
            val now = Clock.System.now()
            val records =
                listOf(
                    bankRecord(0, "-10.00"),
                    bankRecord(
                        1,
                        "-90.00",
                        splits =
                            listOf(
                                QifRecordSplit(category = "Food", amount = "-60.00"),
                                QifRecordSplit(transferAccount = "Cash", amount = "-30.00"),
                            ),
                    ),
                    bankRecord(2, "100.00", supported = false, sectionType = "INVESTMENT"),
                )

            val importId = repo.createImport("statement.qif", records, accountType = "BANK", fileChecksum = "abc", fileLastModified = now)

            val import = repo.getImport(importId).first()!!
            assertEquals("statement.qif", import.originalFileName)
            assertEquals(3, import.recordCount)
            assertEquals(1, import.unsupportedCount)
            assertEquals("BANK", import.accountType)

            assertEquals(3, repo.countRecords(importId))
            val read = repo.getImportRecords(importId, limit = 100, offset = 0)
            assertEquals(listOf(0L, 1L, 2L), read.map { it.recordIndex })
            assertEquals(2, read[1].splits.size)
            assertEquals("Food", read[1].splits[0].category)
            assertEquals("Cash", read[1].splits[1].transferAccount)
            assertTrue(read[2].sectionType == "INVESTMENT" && !read[2].supported)
        }

    @Test
    fun updateRecordStatusesBatch_persistsStatusAndTransferLink() =
        runTest {
            val now = Clock.System.now()
            val importId = repo.createImport("s.qif", listOf(bankRecord(0, "-5.00")), "BANK", "sum", now)

            repo.updateRecordStatusesBatch(importId, ImportStatus.IMPORTED.name, mapOf(0L to TransferId(42)))

            val record = repo.getImportRecords(importId, 10, 0).single()
            assertEquals(ImportStatus.IMPORTED, record.importStatus)
            assertEquals(TransferId(42), record.transferId)
        }

    @Test
    fun findImportsByChecksum_matchesAndDeleteRemoves() =
        runTest {
            val now = Clock.System.now()
            val importId = repo.createImport("s.qif", listOf(bankRecord(0, "-5.00")), "BANK", "checksum-1", now)

            assertEquals(listOf(importId), repo.findImportsByChecksum("checksum-1").map { it.id })

            repo.deleteImport(importId)
            assertTrue(repo.findImportsByChecksum("checksum-1").isEmpty())
            assertNull(repo.getImport(importId).first())
        }
}
