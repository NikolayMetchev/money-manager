package com.moneymanager.compose.filepicker

/**
 * Converts MIME types to file extensions.
 */
internal fun mimeTypesToExtensions(mimeTypes: List<String>): List<String> =
    mimeTypes
        .flatMap { mimeType ->
            when (mimeType) {
                "text/csv" -> listOf(".csv")
                "text/plain" -> listOf(".txt", ".csv")
                "application/json" -> listOf(".json")
                "application/qif", "application/x-qif" -> listOf(".qif")
                "text/tab-separated-values" -> listOf(".tsv")
                "application/vnd.ms-excel" -> listOf(".csv", ".xls")
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> listOf(".xlsx")
                else -> emptyList()
            }
        }.distinct()
