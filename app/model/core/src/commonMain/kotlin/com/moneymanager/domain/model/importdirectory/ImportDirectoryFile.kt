@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.domain.model.importdirectory

import com.moneymanager.domain.model.csv.CsvImportId
import com.moneymanager.domain.model.qif.QifImportId
import kotlin.time.Instant

/**
 * Per-(directory, file) change-detection cursor. [lastModified] is the file's last-modified time at
 * the moment it was last imported; a scan compares the file's current last-modified against this to
 * decide whether to re-import. [checksum] is a sha256 confirm used to avoid spurious re-imports when
 * timestamps move without content changing.
 */
data class ImportDirectoryFile(
    val directoryId: ImportDirectoryId,
    val fileRef: String,
    val fileName: String,
    val lastModified: Instant,
    val checksum: String? = null,
    val csvImportId: CsvImportId? = null,
    val qifImportId: QifImportId? = null,
    val importedAt: Instant? = null,
)
