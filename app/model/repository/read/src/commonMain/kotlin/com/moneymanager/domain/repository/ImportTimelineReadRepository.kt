package com.moneymanager.domain.repository

import com.moneymanager.domain.model.timeline.ImportFileDateRange
import kotlinx.coroutines.flow.Flow

/**
 * Read-only aggregate of per-import transaction date ranges, powering the import Timeline view
 * and the date-range lines on the CSV/QIF/API import screens.
 */
interface ImportTimelineReadRepository {
    /** One entry per CSV import that produced transactions. */
    fun getCsvImportDateRanges(): Flow<List<ImportFileDateRange>>

    /** One entry per QIF import that produced transactions. */
    fun getQifImportDateRanges(): Flow<List<ImportFileDateRange>>

    /** One entry per API session that produced transactions. */
    fun getApiSessionDateRanges(): Flow<List<ImportFileDateRange>>

    /** Aggregate range of manually created transactions, or null when there are none. */
    fun getManualDateRange(): Flow<ImportFileDateRange?>

    /** All of the above combined — the timeline screen's single input. */
    fun getAllDateRanges(): Flow<List<ImportFileDateRange>>

    /** One entry per (CSV import, touched account) pair — a file touching several accounts repeats. */
    fun getCsvImportAccountRanges(): Flow<List<ImportFileDateRange>>

    /** One entry per (QIF import, touched account) pair. */
    fun getQifImportAccountRanges(): Flow<List<ImportFileDateRange>>

    /** One entry per (API session, touched account) pair. */
    fun getApiSessionAccountRanges(): Flow<List<ImportFileDateRange>>

    /** One entry per account touched by manually created transactions. */
    fun getManualAccountRanges(): Flow<List<ImportFileDateRange>>

    /** All of the above combined — the timeline screen's input for the account-grouped view. */
    fun getAllAccountRanges(): Flow<List<ImportFileDateRange>>
}
