package com.moneymanager.ui.screens

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilDoesNotExist
import com.moneymanager.database.DatabaseManager
import com.moneymanager.database.di.DatabaseComponent
import com.moneymanager.database.di.openDatabaseComponent
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.domain.model.DbLocation
import com.moneymanager.domain.model.calendar.HolidayCalendar
import com.moneymanager.test.database.createTestDatabaseLocation
import com.moneymanager.test.database.createTestDatabaseManager
import com.moneymanager.test.database.deleteTestDatabase
import com.moneymanager.ui.test.MoneyManagerTestApp
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock

/** Settings → Locations and holiday calendars: create a location, make it the default, then delete it. */
@OptIn(ExperimentalTestApi::class)
class HolidayCalendarsE2ETest {
    private var testDbLocation: DbLocation? = null

    @AfterTest
    fun cleanup() {
        testDbLocation?.let(::deleteTestDatabase)
    }

    @Test
    fun aNewLocationIsSavedMadeTheDefaultAndDeleted() =
        runMoneyManagerComposeUiTest {
            val location = createTestDatabaseLocation().also { testDbLocation = it }
            val databaseManager = createTestDatabaseManager()
            setContent {
                MoneyManagerTestApp(databaseManager = AtLocation(databaseManager, location), appVersion = AppVersion("1.0.0-test"))
            }
            waitUntilAtLeastOneExists(hasText("Your Accounts"), timeoutMillis = 20_000)
            onNodeWithText("Settings").performClick()
            waitUntilAtLeastOneExists(hasText("New calendar"), timeoutMillis = 15_000)

            val year = Clock.System.todayIn(TimeZone.currentSystemDefault()).year
            onNodeWithText("New calendar").performScrollTo().performClick()
            waitUntilAtLeastOneExists(hasText("New holiday calendar"))
            onNodeWithText("Code (e.g. GBLO)").performTextInput("TSTX")
            onNodeWithText("Name").performTextInput("AAA Test town")
            onNodeWithText("Time zone (e.g. Europe/London)").performTextReplacement("Europe/London")
            onNodeWithText("Countries (ISO codes, e.g. GB)").performTextInput("XX")
            // Friday joins the weekend.
            onNodeWithText("Fri").performClick()
            addDate(index = 0, date = "$year-12-24")
            // A chip removes its date; add it back.
            onNodeWithText("$year-12-24 ✕").performClick()
            waitUntilDoesNotExist(hasText("$year-12-24 ✕"))
            addDate(index = 0, date = "$year-12-24")
            addDate(index = 1, date = "$year-06-06")
            onNodeWithText("Save").performClick()
            waitUntilDoesNotExist(hasText("New holiday calendar"), timeoutMillis = 10_000)

            onNodeWithText("Default location").performClick()
            onNodeWithText("Default location").performTextInput("TSTX")
            waitUntilAtLeastOneExists(hasText("AAA Test town (TSTX)"))
            onNodeWithText("AAA Test town (TSTX)").performClick()
            waitForIdle()

            val saved =
                withComponent(databaseManager, location) {
                    it.holidayCalendarRepository
                        .getAll()
                        .first()
                        .single { calendar -> calendar.id == "TSTX" } to
                        it.settingsRepository.getDefaultLocation().first()
                }
            assertEquals(
                HolidayCalendar(
                    id = "TSTX",
                    name = "AAA Test town",
                    timeZone = "Europe/London",
                    countries = listOf("XX"),
                    weekend = listOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
                    holidays = listOf(LocalDate(year, 12, 24)),
                    workingDays = listOf(LocalDate(year, 6, 6)),
                ),
                saved.first,
            )
            assertEquals("TSTX", saved.second)

            // Sorted by name, the new location is the first row.
            onNodeWithText("Show calendars", substring = true).performScrollTo().performClick()
            waitUntilAtLeastOneExists(hasText("Hide calendars"))
            onAllNodesWithText("Edit")[0].performScrollTo().performClick()
            waitUntilAtLeastOneExists(hasText("Edit AAA Test town (TSTX)"))
            onNodeWithText("‹").performClick()
            onAllNodesWithText("None in ${year - 1}").assertCountEquals(2)
            onNodeWithText("Cancel").performClick()
            waitUntilDoesNotExist(hasText("Edit AAA Test town (TSTX)"))

            onAllNodesWithText("Delete")[0].performScrollTo().performClick()
            waitUntilAtLeastOneExists(hasText("Delete holiday calendar?"))
            onAllNodesWithText("Delete").let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
            waitUntilDoesNotExist(hasText("Delete holiday calendar?"), timeoutMillis = 10_000)
            waitForIdle()
            assertNull(
                withComponent(databaseManager, location) {
                    it.holidayCalendarRepository
                        .getAll()
                        .first()
                        .find { calendar -> calendar.id == "TSTX" }
                },
            )
        }

    private fun ComposeUiTest.addDate(
        index: Int,
        date: String,
    ) {
        onAllNodesWithText("Add (YYYY-MM-DD)")[index].performScrollTo().performTextInput(date)
        onAllNodesWithText("Add")[index].performClick()
        waitUntilAtLeastOneExists(hasText("$date ✕"))
    }

    private fun <T> withComponent(
        databaseManager: DatabaseManager,
        location: DbLocation,
        read: suspend (DatabaseComponent) -> T,
    ): T =
        runBlocking {
            val database = databaseManager.openDatabase(location)
            try {
                read(openDatabaseComponent(database))
            } finally {
                database.close()
            }
        }
}

/** Opens [location] as the app's default database. */
private class AtLocation(
    private val databaseManager: DatabaseManager,
    private val location: DbLocation,
) : DatabaseManager by databaseManager {
    override fun getDefaultLocation(): DbLocation = location
}
