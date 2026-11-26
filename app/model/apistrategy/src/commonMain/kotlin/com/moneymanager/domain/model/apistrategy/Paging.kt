package com.moneymanager.domain.model.apistrategy

import com.moneymanager.domain.model.serialization.SortedStringListSerializer
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How one request unit (the whole endpoint, or one [ApiDateWindowing] window) is paged. Every scheme
 * stops on an empty page; [ApiPaginationConfig.limitValue] is the provider's page size.
 */
@Serializable
sealed interface ApiPaging {
    /** One request per unit — the response holds everything (or the provider caps it and there is no more). */
    @Serializable
    @SerialName("single")
    data object Single : ApiPaging

    /**
     * An integer [param] that starts at 0 and advances by the page size (Kraken `ofs`), or with
     * [pageNumbers] starts at 1 and advances by 1 (Binance fiat `page`). Ends on a short page, or once
     * [totalCountField] (a dot-path into the response envelope) items have been read.
     */
    @Serializable
    @SerialName("offset")
    data class Offset(
        val param: String,
        val pageNumbers: Boolean = false,
        val totalCountField: String? = null,
    ) : ApiPaging

    /**
     * Newest-first paging (Monzo): each page after the first sends [param] = the earliest
     * [positionField] timestamp of the previous page. An incremental download stops once a page reaches
     * back past what an earlier download covered.
     */
    @Serializable
    @SerialName("before")
    data class BeforeCursor(
        val param: String = "before",
        val positionField: String = "created",
    ) : ApiPaging

    /**
     * A forward sweep by ascending numeric id, for an endpoint whose time-range parameters are too short
     * to sweep a full history (Binance `myTrades` caps `startTime`/`endTime` to 24h apart): each page
     * after the first sends [param] = one past the largest [idField] seen, until a short page. Ignores
     * the incremental watermark — a time can't seed an id cursor — and every download walks the id space
     * from the start; the import deduper absorbs the overlap.
     */
    @Serializable
    @SerialName("forwardId")
    data class ForwardId(
        val param: String,
        val idField: String,
    ) : ApiPaging

    /**
     * An opaque next-page token the response supplies at [tokenField] (a dot-path into the envelope:
     * Coinbase `pagination.next_starting_after`, Bybit `result.nextPageCursor`), sent back as [param]
     * until it comes back blank. With [urlEncoded], the token arrives already percent-encoded and is
     * decoded before sending (it would otherwise be double-encoded). Outside a date window, an
     * incremental walk stops once a page's oldest [positionField] (ISO-8601 or epoch) predates what an
     * earlier download covered — so that relies on newest-first order. When [linkQueryParams] is
     * set, the field is a URL and only [param] and the listed query parameters are carried forward.
     */
    @Serializable
    @SerialName("token")
    data class Token(
        val tokenField: String,
        val param: String,
        val urlEncoded: Boolean = false,
        val positionField: String? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        @Serializable(with = SortedStringListSerializer::class)
        val linkQueryParams: List<String> = emptyList(),
    ) : ApiPaging
}

/**
 * Pagination for an endpoint: an optional [window] split of its history, times a [paging] scheme within
 * each request unit. [limitValue] is the page size every scheme compares against; with [sendLimitParam]
 * it is also sent as [limitParam] (some providers' default page size differs unless told explicitly).
 * [extraParams] are added to every windowed request.
 *
 * Incremental downloads: when earlier sessions of the same credential already covered a period, the
 * download starts at that watermark minus [incrementalOverlapDays] rather than at the window's lookback
 * (or, for [ApiPaging.BeforeCursor]/[ApiPaging.Token] walks, stops there), so rows a provider posts with
 * a backdated timestamp after a download are still picked up. The import deduper absorbs the overlap.
 */
@Serializable
data class ApiPaginationConfig(
    val window: ApiDateWindowing? = null,
    val paging: ApiPaging = ApiPaging.Single,
    val limitParam: String = "limit",
    val limitValue: Int = 100,
    val sendLimitParam: Boolean = false,
    @Serializable(with = SortedQueryParamListSerializer::class)
    val extraParams: List<ApiQueryParam> = emptyList(),
    val incrementalOverlapDays: Int = 7,
)
