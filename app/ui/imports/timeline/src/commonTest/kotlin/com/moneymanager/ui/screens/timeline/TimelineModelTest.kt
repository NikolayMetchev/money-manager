@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.ui.screens.timeline

import com.moneymanager.domain.model.ApiSessionType
import com.moneymanager.domain.model.timeline.ImportFileDateRange
import com.moneymanager.domain.model.timeline.TimelineSourceKind
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class TimelineModelTest {
    private val timeZone = TimeZone.UTC

    private fun day(isoDate: String): Long = LocalDate.parse(isoDate).toEpochDays()

    private fun instant(isoDate: String): Instant = LocalDate.parse(isoDate).atStartOfDayIn(timeZone)

    private fun range(
        start: String,
        end: String,
        kind: TimelineSourceKind = TimelineSourceKind.CSV,
        fileId: String = "id-$start",
        fileName: String = "file-$start.csv",
        strategyName: String? = "Strategy A",
        apiSessionType: ApiSessionType? = null,
        ignored: Boolean = false,
        count: Long = 10,
    ): ImportFileDateRange =
        ImportFileDateRange(
            kind = kind,
            fileId = fileId,
            fileName = fileName,
            strategyName = strategyName,
            apiSessionType = apiSessionType,
            ignored = ignored,
            earliest = instant(start),
            latest = instant(end),
            transactionCount = count,
        )

    private fun file(
        start: String,
        end: String,
    ): TimelineFile {
        val r = range(start, end)
        return TimelineFile("f", r.kind, day(start), day(end), r)
    }

    @Test
    fun emptyInputProducesNoMatrix() {
        assertNull(buildTimelineMatrix(emptyList(), timeZone, todayDay = day("2024-01-01")))
    }

    @Test
    fun disjointIntervalsKeepAGap() {
        val segments = mergeIntervals(listOf(file("2024-01-01", "2024-01-10"), file("2024-02-01", "2024-02-10")))
        assertEquals(
            listOf(
                TimelineSegment(day("2024-01-01"), day("2024-01-10"), 1),
                TimelineSegment(day("2024-02-01"), day("2024-02-10"), 1),
            ),
            segments,
        )
    }

    @Test
    fun adjacentIntervalsProduceContiguousCoverage() {
        val segments = mergeIntervals(listOf(file("2024-01-01", "2024-01-10"), file("2024-01-11", "2024-01-20")))
        assertEquals(
            listOf(
                TimelineSegment(day("2024-01-01"), day("2024-01-10"), 1),
                TimelineSegment(day("2024-01-11"), day("2024-01-20"), 1),
            ),
            segments,
        )
        assertTrue(segments.zipWithNext().all { (a, b) -> a.endDay + 1 == b.startDay })
    }

    @Test
    fun overlappingIntervalsGetDepthTwoSegment() {
        val segments = mergeIntervals(listOf(file("2024-01-01", "2024-01-15"), file("2024-01-10", "2024-01-20")))
        assertEquals(
            listOf(
                TimelineSegment(day("2024-01-01"), day("2024-01-09"), 1),
                TimelineSegment(day("2024-01-10"), day("2024-01-15"), 2),
                TimelineSegment(day("2024-01-16"), day("2024-01-20"), 1),
            ),
            segments,
        )
    }

    @Test
    fun nestedIntervalSplitsIntoThreeSegments() {
        val segments = mergeIntervals(listOf(file("2024-01-01", "2024-01-31"), file("2024-01-10", "2024-01-20")))
        assertEquals(
            listOf(
                TimelineSegment(day("2024-01-01"), day("2024-01-09"), 1),
                TimelineSegment(day("2024-01-10"), day("2024-01-20"), 2),
                TimelineSegment(day("2024-01-21"), day("2024-01-31"), 1),
            ),
            segments,
        )
    }

    @Test
    fun identicalIntervalsStackDepth() {
        val segments = mergeIntervals(listOf(file("2024-01-01", "2024-01-10"), file("2024-01-01", "2024-01-10")))
        assertEquals(listOf(TimelineSegment(day("2024-01-01"), day("2024-01-10"), 2)), segments)
    }

    @Test
    fun singleDayFileIsASegment() {
        val segments = mergeIntervals(listOf(file("2024-01-05", "2024-01-05")))
        assertEquals(listOf(TimelineSegment(day("2024-01-05"), day("2024-01-05"), 1)), segments)
    }

    @Test
    fun csvAndQifWithSameStrategyShareARow() {
        val matrix =
            buildTimelineMatrix(
                listOf(
                    range("2024-01-01", "2024-01-31", kind = TimelineSourceKind.CSV, strategyName = "Monzo"),
                    range("2024-02-01", "2024-02-28", kind = TimelineSourceKind.QIF, strategyName = "Monzo"),
                    range("2024-01-01", "2024-03-31", kind = TimelineSourceKind.CSV, strategyName = "Barclays"),
                ),
                timeZone,
                todayDay = day("2024-03-31"),
            )!!
        assertEquals(listOf("Barclays", "Monzo"), matrix.rows.map { it.label })
        val monzoRow = matrix.rows.single { it.label == "Monzo" }
        assertEquals(2, monzoRow.files.size)
        assertEquals(day("2024-01-01"), matrix.minDay)
        assertEquals(day("2024-03-31"), matrix.maxDay)
    }

    @Test
    fun apiRowsUseStrategyNameWithSessionTypeFallback() {
        val matrix =
            buildTimelineMatrix(
                listOf(
                    range(
                        "2024-01-01",
                        "2024-01-31",
                        kind = TimelineSourceKind.API,
                        strategyName = "Crypto.com REST",
                        apiSessionType = ApiSessionType.CRYPTO_COM_EXCHANGE,
                    ),
                    range(
                        "2024-01-01",
                        "2024-01-31",
                        kind = TimelineSourceKind.API,
                        strategyName = null,
                        apiSessionType = ApiSessionType.MONZO,
                    ),
                ),
                timeZone,
                todayDay = day("2024-01-31"),
            )!!
        assertEquals(listOf("Crypto.com REST", "Monzo"), matrix.rows.map { it.label })
    }

    @Test
    fun manualRowIsAlwaysLast() {
        val matrix =
            buildTimelineMatrix(
                listOf(
                    range("2024-01-01", "2024-01-31", kind = TimelineSourceKind.MANUAL, strategyName = null, fileName = "Manual entries"),
                    range("2024-01-01", "2024-01-31", strategyName = "Zebra Bank"),
                ),
                timeZone,
                todayDay = day("2024-01-31"),
            )!!
        assertEquals(listOf("Zebra Bank", "Manual entries"), matrix.rows.map { it.label })
    }

    @Test
    fun gapsBetweenReturnsUncoveredStretchesBetweenSegments() {
        val segments =
            listOf(
                TimelineSegment(day("2024-01-01"), day("2024-01-10"), 1),
                TimelineSegment(day("2024-02-01"), day("2024-02-10"), 1),
                TimelineSegment(day("2024-02-11"), day("2024-02-20"), 2),
            )
        assertEquals(
            listOf(TimelineGap(day("2024-01-11"), day("2024-01-31"))),
            gapsBetween(segments),
        )
    }

    @Test
    fun gapsBetweenIsEmptyForContiguousCoverage() {
        val segments =
            listOf(
                TimelineSegment(day("2024-01-01"), day("2024-01-10"), 1),
                TimelineSegment(day("2024-01-11"), day("2024-01-20"), 1),
            )
        assertEquals(emptyList(), gapsBetween(segments))
    }

    @Test
    fun buildTimelineMatrixExposesRowGaps() {
        val matrix =
            buildTimelineMatrix(
                listOf(
                    range("2024-01-01", "2024-01-10"),
                    range("2024-03-01", "2024-03-10"),
                ),
                timeZone,
                todayDay = day("2024-03-10"),
            )!!
        val row = matrix.rows.single()
        assertEquals(listOf(TimelineGap(day("2024-01-11"), day("2024-02-29"))), row.gaps)
        assertEquals(TimelineGap(day("2024-01-11"), day("2024-02-29")), gapAt(row, day("2024-02-01")))
        assertNull(gapAt(row, day("2024-01-05")))
        assertNull(gapAt(row, day("2024-04-01")))
    }

    @Test
    fun axisExtendsToTodayAndRowsGetLeadingAndTrailingGaps() {
        val matrix =
            buildTimelineMatrix(
                listOf(
                    range("2024-01-01", "2024-01-31", strategyName = "Alpha"),
                    range("2024-02-01", "2024-02-29", strategyName = "Beta"),
                ),
                timeZone,
                todayDay = day("2024-03-15"),
            )!!

        assertEquals(day("2024-01-01"), matrix.minDay)
        assertEquals(day("2024-03-15"), matrix.maxDay)

        val alpha = matrix.rows.single { it.label == "Alpha" }
        assertEquals(listOf(TimelineGap(day("2024-02-01"), day("2024-03-15"))), alpha.gaps)

        val beta = matrix.rows.single { it.label == "Beta" }
        assertEquals(
            listOf(
                TimelineGap(day("2024-01-01"), day("2024-01-31")),
                TimelineGap(day("2024-03-01"), day("2024-03-15")),
            ),
            beta.gaps,
        )
    }

    @Test
    fun rowCoveringTheWholeAxisHasNoGaps() {
        val matrix =
            buildTimelineMatrix(
                listOf(range("2024-01-01", "2024-03-15", strategyName = "Alpha")),
                timeZone,
                todayDay = day("2024-03-15"),
            )!!
        assertEquals(emptyList(), matrix.rows.single().gaps)
    }

    @Test
    fun filesAtReturnsOnlyFilesCoveringTheDay() {
        val row =
            TimelineRow(
                label = "r",
                files = listOf(file("2024-01-01", "2024-01-10"), file("2024-01-05", "2024-01-20")),
                segments = emptyList(),
                gaps = emptyList(),
            )
        assertEquals(1, filesAt(row, day("2024-01-02")).size)
        assertEquals(2, filesAt(row, day("2024-01-07")).size)
        assertEquals(1, filesAt(row, day("2024-01-15")).size)
        assertEquals(0, filesAt(row, day("2024-02-01")).size)
    }

    @Test
    fun longSpanUsesYearTicks() {
        val ticks = axisTicks(day("2018-06-15"), day("2024-03-01"))
        assertEquals(listOf("2019", "2020", "2021", "2022", "2023", "2024"), ticks.map { it.second })
        assertEquals(day("2019-01-01"), ticks.first().first)
    }

    @Test
    fun mediumSpanUsesQuarterTicks() {
        val ticks = axisTicks(day("2023-01-15"), day("2024-02-15"))
        assertEquals(listOf("2023-04", "2023-07", "2023-10", "2024"), ticks.map { it.second })
    }

    @Test
    fun shortSpanUsesMonthTicks() {
        val ticks = axisTicks(day("2024-01-10"), day("2024-04-20"))
        assertEquals(listOf("2024-02", "2024-03", "2024-04"), ticks.map { it.second })
    }
}
