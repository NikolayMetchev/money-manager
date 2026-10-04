package com.moneymanager.apiimporter

import com.moneymanager.domain.model.apistrategy.ApiPaginationConfig
import com.moneymanager.domain.model.apistrategy.ApiPaging
import com.moneymanager.domain.model.apistrategy.WindowBoundFormat
import io.ktor.http.decodeURLQueryComponent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.time.Instant

/**
 * One page to fetch: the pagination parameters to send (after the endpoint's own and, for a windowed
 * page, the pagination's templated extra params — which the caller adds), in wire order, plus
 * where it sits in the walk — the date [window] (null when unwindowed) and a [pageMarker] that is stable
 * across retries of the same logical page (null for the first page of a unit), for callers that record a
 * synthetic URL per page.
 */
internal class PageRequest(
    val window: ApiDateWindow?,
    val params: LinkedHashMap<String, String>,
    val pageMarker: String?,
    val windowIndex: Int,
)

/** What fetching one [PageRequest] produced. */
internal sealed interface PageOutcome {
    /** The page's response [body] and the [items] the caller parsed out of it. */
    class Fetched(
        val body: String,
        val items: List<JsonObject>,
    ) : PageOutcome

    /** The provider refused this date window as out of range: skip it and carry on with the next. */
    data object WindowOutOfRange : PageOutcome

    /** The request failed: stop walking this endpoint. */
    data object Failed : PageOutcome
}

/**
 * How a walk ended.
 *
 * @property complete Every unit was walked without a [PageOutcome.Failed] page.
 * @property incrementalStart Where an incremental walk started (or, for an unwindowed walk, where it
 *   stops paging back to), when a watermark moved it; null for a full walk.
 */
internal class WalkResult(
    val complete: Boolean,
    val incrementalStart: Instant?,
)

/**
 * Walks an endpoint's pages per [pagination]: its date windows (or one unwindowed unit), each paged by
 * the configured scheme. The one pagination loop every API download shares — the bank transaction feed,
 * exchange data endpoints and value endpoints alike — so a scheme behaves the same wherever it is used.
 *
 * [since] is the incremental watermark (null for a full download): it moves the first window forward,
 * and stops a newest-first walk ([ApiPaging.BeforeCursor], unwindowed [ApiPaging.Token]) once it pages
 * back past it. [ApiPaging.ForwardId] ignores it.
 *
 * [onUnitComplete] runs after each fully walked unit with how far its data now reaches — the window's
 * end, or [now] for an unwindowed unit — so the caller can record download coverage. A unit that
 * failed or was out of range doesn't report.
 */
internal suspend fun walkPages(
    pagination: ApiPaginationConfig?,
    now: Instant,
    since: Instant?,
    windowed: Boolean = true,
    fetch: suspend (PageRequest) -> PageOutcome,
    onUnitComplete: suspend (coverageUntil: Instant) -> Unit = {},
): WalkResult {
    val paging = pagination?.paging ?: ApiPaging.Single
    if (pagination != null && paging is ApiPaging.ForwardId) {
        return WalkResult(complete = walkForwardIds(pagination, paging, fetch, onUnitComplete, now), incrementalStart = null)
    }
    val windowing = pagination?.window?.takeIf { windowed }
    val windows: List<ApiDateWindow?> = if (pagination != null && windowing != null) dateWindows(pagination, now, since) else listOf(null)
    // A newest-first walk has no window to start later, so an incremental one instead stops once it pages
    // back past what an earlier download already covered.
    val cutoff =
        if (since != null && pagination != null && windowing == null && paging.isNewestFirst()) {
            Instant.fromEpochMilliseconds(incrementalStartMillis(since, pagination))
        } else {
            null
        }
    val incrementalStart = if (since == null) null else windows.firstOrNull()?.start ?: cutoff

    windows.forEachIndexed { windowIndex, window ->
        when (val unit = walkUnit(pagination, paging, window, windowIndex, cutoff, fetch)) {
            UnitOutcome.Failed -> return WalkResult(complete = false, incrementalStart = incrementalStart)
            UnitOutcome.OutOfRange -> Unit
            UnitOutcome.Complete -> onUnitComplete(window?.end ?: now)
        }
    }
    return WalkResult(complete = true, incrementalStart = incrementalStart)
}

private enum class UnitOutcome { Complete, OutOfRange, Failed }

private fun ApiPaging.isNewestFirst(): Boolean = this is ApiPaging.BeforeCursor || this is ApiPaging.Token && positionField != null

private fun ApiPaging.Token.positionPath(): String? = positionField

/** Pages one request unit (a window, or the whole unwindowed endpoint). */
@Suppress("CyclomaticComplexMethod", "LongMethod")
private suspend fun walkUnit(
    pagination: ApiPaginationConfig?,
    paging: ApiPaging,
    window: ApiDateWindow?,
    windowIndex: Int,
    cutoff: Instant?,
    fetch: suspend (PageRequest) -> PageOutcome,
): UnitOutcome {
    var offset = if (paging is ApiPaging.Offset && paging.pageNumbers) 1 else 0
    var itemsSeen = 0
    var token: String? = null
    var tokenPage = 0
    var before: Instant? = null
    while (true) {
        val params = linkedMapOf<String, String>()
        if (window != null && pagination?.window != null) {
            val windowing = pagination.window!!
            params[windowing.startParam] = formatWindowBound(window.start, windowing.boundFormat)
            params[windowing.endParam] = formatWindowBound(window.end, windowing.boundFormat)
        }
        val limit = pagination?.takeIf { it.sendLimitParam }
        val marker: String?
        when (paging) {
            is ApiPaging.Offset -> {
                params[paging.param] = offset.toString()
                limit?.let { params[it.limitParam] = it.limitValue.toString() }
                marker = offset.toString()
            }
            is ApiPaging.Token -> {
                token?.let { params[paging.param] = it }
                limit?.let { params[it.limitParam] = it.limitValue.toString() }
                marker = tokenPage.toString()
            }
            is ApiPaging.BeforeCursor -> {
                // Page size first: the bank feed's recorded URLs (which resume an interrupted download)
                // have always carried it ahead of the cursor.
                limit?.let { params[it.limitParam] = it.limitValue.toString() }
                before?.let { params[paging.param] = it.toString() }
                marker = before?.toString()
            }
            ApiPaging.Single -> {
                limit?.let { params[it.limitParam] = it.limitValue.toString() }
                marker = null
            }
            is ApiPaging.ForwardId -> error("forward-id walks are not windowed")
        }
        val page =
            when (val outcome = fetch(PageRequest(window, params, marker, windowIndex))) {
                PageOutcome.Failed -> return UnitOutcome.Failed
                PageOutcome.WindowOutOfRange -> return UnitOutcome.OutOfRange
                is PageOutcome.Fetched -> outcome
            }
        val items = page.items
        val keepPaging =
            when (paging) {
                ApiPaging.Single -> false
                is ApiPaging.Offset -> {
                    val pageSize = pagination!!.limitValue
                    itemsSeen += items.size
                    offset += if (paging.pageNumbers) 1 else pageSize
                    val total = paging.totalCountField?.let { field -> totalCountFromJson(page.body, field) }
                    items.size >= pageSize && (total == null || itemsSeen < total)
                }
                is ApiPaging.Token -> {
                    val sent = token
                    token = nextPageToken(page.body, paging, sent)
                    tokenPage += 1
                    val pagedPastCutoff =
                        cutoff != null &&
                            paging.positionPath()?.let { path ->
                                items.any { item -> item.position(path)?.let { it < cutoff } == true }
                            } == true
                    token != null && items.isNotEmpty() && !pagedPastCutoff
                }
                is ApiPaging.BeforeCursor -> {
                    val earliest = items.mapNotNull { it.position(paging.positionField) }.minOrNull()
                    before = earliest
                    val reachedCutoff = cutoff != null && earliest != null && earliest <= cutoff
                    items.isNotEmpty() && earliest != null && !reachedCutoff
                }
                is ApiPaging.ForwardId -> false
            }
        if (!keepPaging) return UnitOutcome.Complete
    }
}

/** A single forward sweep by ascending id (see [ApiPaging.ForwardId]); true when it completed. */
private suspend fun walkForwardIds(
    pagination: ApiPaginationConfig,
    paging: ApiPaging.ForwardId,
    fetch: suspend (PageRequest) -> PageOutcome,
    onUnitComplete: suspend (Instant) -> Unit,
    now: Instant,
): Boolean {
    var cursor: String? = null
    while (true) {
        val params = linkedMapOf<String, String>()
        cursor?.let { params[paging.param] = it }
        if (pagination.sendLimitParam) params[pagination.limitParam] = pagination.limitValue.toString()
        val page =
            fetch(PageRequest(window = null, params = params, pageMarker = cursor, windowIndex = 0)) as? PageOutcome.Fetched ?: return false
        val maxId = page.items.mapNotNull { (it[paging.idField] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() }.maxOrNull()
        if (page.items.size < pagination.limitValue || maxId == null) break
        cursor = (maxId + 1).toString()
    }
    onUnitComplete(now)
    return true
}

/** An item's position (ISO-8601 instant or epoch millis) at [path], for newest-first cutoffs. */
private fun JsonObject.position(path: String): Instant? =
    resolveJsonPath(path)?.let { value ->
        runCatching { Instant.parse(value) }.getOrNull() ?: value.toLongOrNull()?.let(Instant::fromEpochMilliseconds)
    }

/** Formats a date-window bound per [WindowBoundFormat] for a request parameter. */
internal fun formatWindowBound(
    instant: Instant,
    format: WindowBoundFormat,
): String =
    when (format) {
        WindowBoundFormat.EPOCH_MS -> instant.toEpochMilliseconds().toString()
        WindowBoundFormat.EPOCH_S -> instant.epochSeconds.toString()
        WindowBoundFormat.ISO_8601 -> instant.toString()
    }

/**
 * The next-page token in [body] per [paging] — decoded when the provider sends it already
 * percent-encoded, since request params are percent-encoded again when sent. Null when absent, blank, or
 * the same token that was just sent: a provider echoing it back would otherwise page forever.
 */
internal fun nextPageToken(
    body: String,
    paging: ApiPaging.Token,
    sentToken: String?,
): String? {
    val raw = stringFieldFromJson(body, paging.tokenField)?.takeIf { it.isNotBlank() } ?: return null
    val token = if (paging.urlEncoded) raw.decodeURLQueryComponent() else raw
    return token.takeIf { it != sentToken }
}
