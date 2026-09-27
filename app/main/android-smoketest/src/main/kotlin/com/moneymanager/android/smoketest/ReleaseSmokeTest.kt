package com.moneymanager.android.smoketest

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream

private const val APP_PACKAGE = "com.moneymanager"
private const val APP_ACTIVITY = "$APP_PACKAGE/com.moneymanager.android.MainActivity"
private const val TIMEOUT_MILLIS = 60_000L
private const val POLL_MILLIS = 2_000L

/**
 * Walks first run on the minified release app: creating a local database and picking its default
 * currency exercises SQLite, SQLDelight, Metro DI, seeding and the import engine, then each main
 * screen gets opened once.
 */
class ReleaseSmokeTest {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Before
    fun launchFreshApp() {
        // A clean slate so the first-run screen always appears, whatever a previous run left behind.
        device.executeShellCommand("pm clear $APP_PACKAGE")
        device.executeShellCommand("logcat -b crash -c")
        device.executeShellCommand("am start -W -n $APP_ACTIVITY")
    }

    @After
    fun stopApp() {
        device.executeShellCommand("am force-stop $APP_PACKAGE")
    }

    @Test
    fun firstRunReachesAndNavigatesTheMainScreens() {
        waitFor("Welcome to Money Manager")
        tap("Local database")
        tap("Skip setup")
        // Skipping the wizard still leaves a fresh database without a default currency.
        waitFor("Select Default Currency")
        tap("Confirm")
        waitFor("Accounts")
        for (screen in listOf("Categories", "People", "Imports", "Settings", "Accounts")) {
            tap(screen)
            device.waitForIdle()
            assertAppAlive("after opening $screen")
        }
    }

    private fun waitFor(text: String) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (device.wait(Until.hasObject(By.text(text)), POLL_MILLIS)) return
            dismissSystemAnrDialog()
        }
        assertAppAlive("while waiting for \"$text\"")
        val screen = ByteArrayOutputStream().also(device::dumpWindowHierarchy).toString()
        fail("\"$text\" never appeared on screen. Window hierarchy:\n$screen")
    }

    // A freshly booted emulator often shows "Process system isn't responding" over the app.
    private fun dismissSystemAnrDialog() {
        if (device.hasObject(By.textContains("isn't responding"))) {
            device.findObject(By.text("Wait"))?.click()
        }
    }

    private fun tap(text: String) {
        waitFor(text)
        device.findObject(By.text(text)).click()
    }

    private fun assertAppAlive(context: String) {
        val crashes = device.executeShellCommand("logcat -d -b crash")
        if ("Process: $APP_PACKAGE" in crashes) fail("The app crashed $context:\n$crashes")
        assertTrue("The app is no longer running $context", device.executeShellCommand("pidof $APP_PACKAGE").isNotBlank())
    }
}
