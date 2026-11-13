package com.moneymanager.csvimporter

/** A folder discovered when adding a directory: it directly contains importable (.csv/.qif/.ofx/.xlsx/.pdf) files. */
data class DiscoveredImportFolder(
    val folderRef: String,
    val displayPath: String,
)
