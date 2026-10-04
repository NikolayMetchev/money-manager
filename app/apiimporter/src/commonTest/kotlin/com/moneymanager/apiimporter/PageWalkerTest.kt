package com.moneymanager.apiimporter

import com.moneymanager.domain.model.apistrategy.ApiDateWindowing
import com.moneymanager.domain.model.apistrategy.ApiPaginationConfig
import com.moneymanager.domain.model.apistrategy.ApiPaging
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class PageWalkerTest {
    private val now = Instant.parse("2026-06-01T00:00:00Z")

    private fun item(vararg fields: Pair<String, String>) = JsonObject(fields.associate { (k, v) -> k to JsonPrimitive(v) })

    private fun page(vararg items: JsonObject) = PageOutcome.Fetched("{}", items.toList())

    @Test
    fun `offset paging advances by the page size until a short page`() =
        runTest {
            val pagination = ApiPaginationConfig(paging = ApiPaging.Offset(param = "ofs"), limitValue = 2, sendLimitParam = true)
            val sent = mutableListOf<Map<String, String>>()
            val result =
                walkPages(pagination, now, since = null, fetch = { request ->
                    sent += request.params
                    if (sent.size < 3) page(item(), item()) else page(item())
                })
            assertTrue(result.complete)
            assertEquals(listOf("0", "2", "4"), sent.map { it["ofs"] })
            assertTrue(sent.all { it["limit"] == "2" })
        }

    @Test
    fun `page numbers start at one`() =
        runTest {
            val pagination = ApiPaginationConfig(paging = ApiPaging.Offset(param = "page", pageNumbers = true), limitValue = 1)
            val sent = mutableListOf<String?>()
            walkPages(pagination, now, since = null, fetch = { request ->
                sent += request.params["page"]
                if (sent.size < 2) page(item()) else page()
            })
            assertEquals(listOf<String?>("1", "2"), sent)
        }

    @Test
    fun `a before-cursor walk sends the earliest position and stops at the watermark`() =
        runTest {
            val pagination = ApiPaginationConfig(paging = ApiPaging.BeforeCursor(), sendLimitParam = true, incrementalOverlapDays = 0)
            val since = Instant.parse("2026-05-20T00:00:00Z")
            val pages =
                listOf(
                    page(item("created" to "2026-05-30T00:00:00Z"), item("created" to "2026-05-25T00:00:00Z")),
                    page(item("created" to "2026-05-19T00:00:00Z")),
                    page(item("created" to "2026-05-01T00:00:00Z")),
                )
            val sent = mutableListOf<Map<String, String>>()
            val coverage = mutableListOf<Instant>()
            val result =
                walkPages(
                    pagination,
                    now,
                    since,
                    fetch = { request -> pages[sent.size].also { sent += request.params } },
                    onUnitComplete = { coverage += it },
                )
            assertEquals(2, sent.size, "the second page reached past the watermark")
            assertNull(sent[0]["before"])
            assertEquals("2026-05-25T00:00:00Z", sent[1]["before"])
            assertEquals(listOf("limit", "before"), sent[1].keys.toList(), "page size precedes the cursor")
            assertEquals(listOf(now), coverage)
            assertEquals(since, result.incrementalStart)
        }

    @Test
    fun `a token walk stops on a blank token`() =
        runTest {
            val pagination = ApiPaginationConfig(paging = ApiPaging.Token(tokenField = "next", param = "cursor"))
            val bodies = listOf("""{"next":"abc"}""", """{"next":""}""")
            val sent = mutableListOf<String?>()
            walkPages(pagination, now, since = null, fetch = { request ->
                sent += request.params["cursor"]
                PageOutcome.Fetched(bodies[sent.size - 1], listOf(item()))
            })
            assertEquals(listOf(null, "abc"), sent)
        }

    @Test
    fun `a forward-id sweep pages from one past the largest id`() =
        runTest {
            val pagination = ApiPaginationConfig(paging = ApiPaging.ForwardId(param = "fromId", idField = "id"), limitValue = 2)
            val sent = mutableListOf<String?>()
            walkPages(pagination, now, since = now, fetch = { request ->
                sent += request.params["fromId"]
                if (sent.size == 1) page(item("id" to "7"), item("id" to "9")) else page(item("id" to "10"))
            })
            assertEquals(listOf(null, "10"), sent)
        }

    @Test
    fun `an out-of-range window is skipped and a failed one stops the walk`() =
        runTest {
            val pagination = ApiPaginationConfig(window = ApiDateWindowing(windowDays = 10, lookbackDays = 30))
            val covered = mutableListOf<Instant>()
            var calls = 0
            val skipped =
                walkPages(pagination, now, since = null, fetch = {
                    calls += 1
                    if (calls == 1) PageOutcome.WindowOutOfRange else page()
                }, onUnitComplete = { covered += it })
            assertTrue(skipped.complete)
            assertEquals(calls - 1, covered.size)

            val failed = walkPages(pagination, now, since = null, fetch = { PageOutcome.Failed })
            assertFalse(failed.complete)
        }

    @Test
    fun `a watermark starts the first window later`() =
        runTest {
            val pagination = ApiPaginationConfig(window = ApiDateWindowing(windowDays = 5, lookbackDays = 365), incrementalOverlapDays = 0)
            val windows = mutableListOf<ApiDateWindow?>()
            walkPages(pagination, now, since = now - 7.days, fetch = { request ->
                windows += request.window
                page()
            })
            assertTrue(windows.size <= 3)
            // The final window reaches now (a window landing exactly on it ends a millisecond early).
            assertTrue(now - checkNotNull(windows.last()).end <= 1.milliseconds)
        }
}
