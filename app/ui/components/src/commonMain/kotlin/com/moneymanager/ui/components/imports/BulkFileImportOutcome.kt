package com.moneymanager.ui.components.imports

/** What to report after staging a batch of picked files; see [importPickedFiles]. */
data class BulkFileImportOutcome(
    val message: String,
    val isError: Boolean,
)

/**
 * Stages each picked file in turn, keeping one failure from abandoning the rest of the batch, and
 * summarises the run for the screen's status banner. [stageFile] returns false for a file that was
 * skipped because it is already staged (formats detect that themselves, from their own checksum).
 */
suspend fun <T> importPickedFiles(
    files: List<T>,
    fileName: (T) -> String,
    stageFile: suspend (T) -> Boolean,
): BulkFileImportOutcome {
    var imported = 0
    var skipped = 0
    val failures = mutableListOf<String>()
    for (file in files) {
        try {
            if (stageFile(file)) imported++ else skipped++
        } catch (expected: Exception) {
            failures.add("${fileName(file)}: ${expected.message}")
        }
    }
    return BulkFileImportOutcome(
        message =
            buildString {
                append("Imported $imported file${if (imported == 1) "" else "s"}")
                if (skipped > 0) append(", skipped $skipped already imported")
                if (failures.isNotEmpty()) {
                    append(", ${failures.size} failed: ")
                    append(failures.joinToString("; "))
                }
            },
        isError = failures.isNotEmpty(),
    )
}
