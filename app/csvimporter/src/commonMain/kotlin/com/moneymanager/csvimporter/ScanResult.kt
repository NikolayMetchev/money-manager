package com.moneymanager.csvimporter

/** Outcome of scanning a single import directory (download/stage only — no import is applied). */
data class ScanResult(
    val filesDownloaded: Int,
    /** Of [filesDownloaded], how many were CSV files (staged into the CSV section). */
    val csvDownloaded: Int,
    /** Of [filesDownloaded], how many were QIF files (staged into the QIF section). */
    val qifDownloaded: Int,
    val filesUnchanged: Int,
    /** Files skipped because their type isn't supported for import (e.g. .pdf). */
    val filesSkipped: Int,
    val filesFailed: Int,
    val failures: List<String>,
)
