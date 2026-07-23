package com.moneymanager.database.audit

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.qif.QifImportRecord
import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Regression test for the audit trail failing on QIF-imported accounts.
 *
 * An account created from a QIF import gets a `Source.Qif` with a known import id but **no**
 * `recordIndex` (the account is derived from the import as a whole, not a single record). The
 * import-detail table previously forced the record index NOT NULL, so the recorder skipped the
 * detail row entirely and the import id was lost; reconstructing the source for the audit trail
 * then threw "QIF source row missing qif_import_id" and the audit screen errored out.
 *
 * This test reproduces that exact path: it records a QIF account source without a record index and
 * asserts the audit history reconstructs the source (with its import id and file name) instead of
 * throwing.
 */
class QifImportAccountSourceTest : DbTest() {
    @Test
    fun qifSourcedAccountWithoutRecordIndexHasAuditSource() =
        runTest {
            val now = Clock.System.now()

            val importId =
                repositories.qifImportRepository.createImport(
                    fileName = "statement.qif",
                    records = emptyList<QifImportRecord>(),
                    accountType = "BANK",
                    fileChecksum = "abc",
                    fileLastModified = now,
                )

            // Mirrors QifImportApplier: accounts created during a QIF import carry the import id but
            // no originating record index.
            val accountId =
                repositories.accountRepository.createAccount(
                    Account(id = AccountId(0), name = "From QIF", openingDate = now),
                    Source.Qif(importId),
                )

            val history = repositories.auditRepository.getAuditHistoryForAccount(accountId)

            assertTrue(history.isNotEmpty(), "Expected at least one audit entry for the QIF-sourced account")
            history.forEachIndexed { index, entry ->
                val record = assertNotNull(entry.source, "audit entry at index $index has null source")
                val source = record.source
                assertTrue(source is Source.Qif, "expected a QIF source but was $source")
                assertEquals(importId, source.importId)
                assertNull(source.recordIndex, "account-level QIF source should have no record index")
                assertEquals("statement.qif", record.fileName)
            }
        }
}
