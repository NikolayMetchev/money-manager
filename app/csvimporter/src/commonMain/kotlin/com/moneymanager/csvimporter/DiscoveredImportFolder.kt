package com.moneymanager.csvimporter

/** A folder discovered when adding a directory: it directly contains importable (.csv/.qif) files. */
data class DiscoveredImportFolder(
    val folderRef: String,
    val displayPath: String,
)
