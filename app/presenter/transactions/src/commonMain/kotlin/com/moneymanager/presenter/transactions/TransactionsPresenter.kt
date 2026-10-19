package com.moneymanager.presenter.transactions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AccountRow
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.query.TransactionCursor
import com.moneymanager.query.TransactionDetail
import com.moneymanager.query.TransactionDetailQuery
import com.moneymanager.query.TransactionPage
import com.moneymanager.query.TransactionQuery
import com.moneymanager.query.cursor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.time.Instant

/** The rows kept: `from <= timestamp < to`, either end open when null. */
data class TransactionRange(
    val from: Instant? = null,
    val to: Instant? = null,
)

sealed interface TransactionsEvent {
    /** Keep only rows in [range], starting again from its newest row. */
    data class SetRange(
        val range: TransactionRange,
    ) : TransactionsEvent

    /** Start the list at the newest row before [at] (inside the range); newer rows stay a [LoadNewer] away. */
    data class JumpTo(
        val at: Instant,
    ) : TransactionsEvent

    /** Show [accountId]'s rows around [transferId], clearing the range, e.g. to follow a linked transfer. */
    data class ShowTransfer(
        val accountId: AccountId,
        val transferId: TransferId,
    ) : TransactionsEvent

    /** Extend the list by a page of older rows, when there are any. */
    data object LoadOlder : TransactionsEvent

    /** Extend the list by a page of newer rows, when there are any. */
    data object LoadNewer : TransactionsEvent

    /** Load [id]'s detail, or drop it when null. */
    data class Select(
        val id: TransactionId?,
    ) : TransactionsEvent

    /** Read the list again where it stands, e.g. after an import. */
    data object Refresh : TransactionsEvent
}

/**
 * What a transactions screen shows: a window of [accountId]'s rows, newest first, that grows at either end as the
 * person pages. The view decides when to page, from its viewport, and sends [TransactionsEvent.LoadOlder] /
 * [TransactionsEvent.LoadNewer].
 */
data class TransactionsModel(
    val accountId: AccountId?,
    val range: TransactionRange,
    /** False until the first page for the current account, range and anchor arrives. */
    val loaded: Boolean,
    val rows: List<AccountRow>,
    val hasOlder: Boolean,
    val hasNewer: Boolean,
    /** A page is being added at one end. */
    val extending: Boolean,
    /** The row a jump landed on, for the view to put its cursor on; null when nothing asked for one. */
    val focusId: TransactionId?,
    val selectedId: TransactionId?,
    /** The detail of [selectedId], once loaded. */
    val detail: TransactionDetail?,
    val error: String?,
    val eventSink: (TransactionsEvent) -> Unit,
)

/**
 * One account's transactions with running balances, shared by the TUI and (to follow) the desktop/Android
 * transactions screen. [pageSize] is how many rows a page load reads, from the view's viewport; the window keeps up
 * to [MAX_PAGES] pages and drops the far end as it grows. [refreshViews] brings the running-balance materialized
 * view up to date before a load that the person asked for (the first one and [TransactionsEvent.Refresh]).
 */
@Composable
fun presentTransactions(
    query: TransactionQuery,
    details: TransactionDetailQuery,
    accountId: AccountId?,
    pageSize: Int,
    refreshViews: suspend () -> Unit = {},
): TransactionsModel {
    val currentPageSize by rememberUpdatedState(pageSize.coerceAtLeast(1))
    val currentRefresh by rememberUpdatedState(refreshViews)
    var target by remember { mutableStateOf(Target(accountId)) }
    var window by remember { mutableStateOf(Window()) }
    var selectedId by remember { mutableStateOf<TransactionId?>(null) }
    var detail by remember { mutableStateOf<TransactionDetail?>(null) }
    val scope = rememberCoroutineScope()

    // The account another screen asked for; a ShowTransfer that already moved there keeps its anchor.
    LaunchedEffect(accountId) {
        if (target.accountId != accountId) target = Target(accountId, refresh = false)
    }

    LaunchedEffect(target) {
        val loading = target
        window = Window(target = loading)
        val account = loading.accountId ?: return@LaunchedEffect
        window =
            reading {
                if (loading.refresh) currentRefresh()
                val size = currentPageSize
                val page =
                    when (val anchor = loading.anchor) {
                        Anchor.Newest -> {
                            query.page(account, size, loading.range.from, loading.range.to)
                        }

                        is Anchor.Position -> {
                            query.page(account, size, loading.range.from, loading.range.to, cursor = anchor.cursor)
                        }

                        is Anchor.Transfer -> {
                            query.pageContaining(account, anchor.id, size)
                        }
                    }
                val focus =
                    when (val anchor = loading.anchor) {
                        Anchor.Newest -> null
                        is Anchor.Position -> if (anchor.focus) page.rows.firstOrNull()?.transactionId else null
                        is Anchor.Transfer -> anchor.id
                    }
                Window(target = loading, loaded = true, page = page, focusId = focus)
            }.getOrElse { Window(target = loading, loaded = true, error = it.describe()) }
    }

    fun extend(older: Boolean) {
        val current = window
        val loadedFor = current.target ?: return
        val account = loadedFor.accountId
        if (!current.loaded || current.extending || account == null) return
        val cursor = (if (older) current.page.next else current.page.previous) ?: return
        window = current.copy(extending = true)
        scope.launch {
            val range = loadedFor.range
            val size = currentPageSize
            val result =
                reading {
                    val page =
                        if (older) {
                            query.page(account, size, range.from, range.to, cursor = cursor)
                        } else {
                            query.pageBefore(account, size, cursor, range.to)
                        }
                    current.extendedBy(page, older, MAX_PAGES * size)
                }.getOrElse { current.copy(error = it.describe()) }
            // A reload since this started owns the window now.
            if (window.target === loadedFor) window = result
        }
    }

    val eventSink: (TransactionsEvent) -> Unit =
        remember {
            { event ->
                when (event) {
                    is TransactionsEvent.SetRange -> {
                        target = target.next(event.range, Anchor.Newest)
                    }

                    is TransactionsEvent.JumpTo -> {
                        val to = target.range.to
                        val at = if (to != null && event.at > to) to else event.at
                        target = target.next(target.range, Anchor.Position(TransactionCursor(at, 0), focus = true))
                    }

                    is TransactionsEvent.ShowTransfer -> {
                        target =
                            Target(
                                event.accountId,
                                TransactionRange(),
                                Anchor.Transfer(event.transferId),
                                target.generation + 1,
                                refresh = false,
                            )
                    }

                    TransactionsEvent.LoadOlder -> {
                        extend(older = true)
                    }

                    TransactionsEvent.LoadNewer -> {
                        extend(older = false)
                    }

                    is TransactionsEvent.Select -> {
                        selectedId = event.id
                    }

                    TransactionsEvent.Refresh -> {
                        // Re-read from just above the newest row on screen, so the list stays where it is.
                        val anchor =
                            window.page.rows
                                .firstOrNull()
                                ?.let { Anchor.Position(TransactionCursor(it.timestamp, it.transactionId.id + 1), focus = false) }
                                ?: target.anchor
                        target = target.next(target.range, anchor).copy(refresh = true)
                    }
                }
            }
        }

    val selectedRow = window.page.rows.firstOrNull { it.transactionId == selectedId }
    LaunchedEffect(selectedRow) {
        detail = reading { selectedRow?.let { details.detail(it) } }.getOrNull()
    }

    return TransactionsModel(
        accountId = target.accountId,
        range = target.range,
        loaded = window.loaded && window.target === target,
        rows = window.page.rows,
        hasOlder = window.page.next != null,
        hasNewer = window.page.previous != null,
        extending = window.extending,
        focusId = window.focusId,
        selectedId = selectedId,
        detail = detail?.takeIf { it.row.transactionId == selectedId },
        error = window.error,
        eventSink = eventSink,
    )
}

/**
 * Runs a read, keeping a failure as a value: the screen shows it on its error line rather than the failure ending
 * the composition. Cancellation still propagates.
 */
@Suppress("TooGenericExceptionCaught") // Whatever a read throws (a schema error, a missing row) is shown, not rethrown.
private suspend fun <T> reading(read: suspend () -> T): Result<T> =
    try {
        Result.success(read())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

private fun Throwable.describe() = message ?: toString()

/** How many pages the window keeps before dropping rows from the end the person is moving away from. */
const val MAX_PAGES = 8

private sealed interface Anchor {
    data object Newest : Anchor

    data class Position(
        val cursor: TransactionCursor,
        val focus: Boolean,
    ) : Anchor

    data class Transfer(
        val id: TransferId,
    ) : Anchor
}

/** What to load. A new value, even an equal one ([generation]), means "load again". */
private data class Target(
    val accountId: AccountId?,
    val range: TransactionRange = TransactionRange(),
    val anchor: Anchor = Anchor.Newest,
    val generation: Int = 0,
    val refresh: Boolean = true,
) {
    fun next(
        range: TransactionRange,
        anchor: Anchor,
    ) = Target(accountId, range, anchor, generation + 1, refresh = false)
}

/** The rows loaded for [target], with the cursors at both ends, as one value so they never disagree. */
private data class Window(
    val target: Target? = null,
    val loaded: Boolean = false,
    val page: TransactionPage = TransactionPage(emptyList(), next = null),
    val extending: Boolean = false,
    val focusId: TransactionId? = null,
    val error: String? = null,
) {
    fun extendedBy(
        more: TransactionPage,
        older: Boolean,
        maxRows: Int,
    ): Window {
        val joined =
            if (older) {
                val rows = page.rows + more.rows
                val kept = rows.drop((rows.size - maxRows).coerceAtLeast(0))
                TransactionPage(kept, more.next, if (kept.size < rows.size) kept.first().cursor() else page.previous)
            } else {
                val rows = more.rows + page.rows
                val kept = rows.take(maxRows)
                TransactionPage(kept, if (kept.size < rows.size) kept.last().cursor() else page.next, more.previous)
            }
        return copy(page = joined, extending = false, focusId = null, error = null)
    }
}
