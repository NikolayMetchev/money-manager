package com.moneymanager.tools.androiddevicetest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceTestTest {
    @Test
    fun `only online devices count`() {
        val output = "List of devices attached\nemulator-5554\tdevice\nR58N\toffline\nXYZ\tunauthorized\n\n"
        assertEquals(listOf("emulator-5554"), onlineDevices(output))
        assertEquals(emptyList(), onlineDevices("List of devices attached\n\n"))
    }

    @Test
    fun `the AVD uses the image's own CPU`() {
        val x86 = avdConfig("x86_64", "system-images/android-36/aosp_atd/x86_64")
        assertTrue("hw.cpu.arch=x86_64\n" in x86)
        assertTrue("image.sysdir.1=system-images/android-36/aosp_atd/x86_64/\n" in x86)
        val arm = avdConfig("arm64-v8a", "system-images/android-36/aosp_atd/arm64-v8a")
        assertTrue("hw.cpu.arch=arm64\n" in arm)
        assertTrue("abi.type=arm64-v8a\n" in arm)
    }

    @Test
    fun `each OS gets its own hypervisor advice`() {
        assertTrue("Windows Hypervisor Platform" in hypervisorHint("Windows 11"))
        assertTrue("Hypervisor.framework" in hypervisorHint("Mac OS X"))
        assertTrue("/dev/kvm" in hypervisorHint("Linux"))
    }

    private val passing =
        listOf(
            "INSTRUMENTATION_STATUS: class=a.B",
            "INSTRUMENTATION_STATUS: test=works",
            "INSTRUMENTATION_STATUS_CODE: 1",
            "INSTRUMENTATION_STATUS: class=a.B",
            "INSTRUMENTATION_STATUS: test=works",
            "INSTRUMENTATION_STATUS_CODE: 0",
            "INSTRUMENTATION_STATUS: class=a.B",
            "INSTRUMENTATION_STATUS: test=breaks",
            "INSTRUMENTATION_STATUS: stack=java.lang.AssertionError: no",
            "\tat a.B.breaks(B.kt:1)",
            "INSTRUMENTATION_STATUS_CODE: -2",
            "INSTRUMENTATION_CODE: -1",
        )

    @Test
    fun `a test's status blocks become one result, its stack running on to the next line`() {
        val run = parseInstrumentation(passing)
        assertEquals(listOf("works", "breaks"), run.results.map { it.name })
        assertEquals("java.lang.AssertionError: no\n\tat a.B.breaks(B.kt:1)", run.results[1].stack)
        assertTrue(run.results[1].failed)
        assertFalse(run.succeeded)
        assertEquals(-1, run.code)
    }

    @Test
    fun `a run that finished with every test passing succeeds, a crash does not`() {
        val ok = parseInstrumentation(passing.take(6) + "INSTRUMENTATION_CODE: -1")
        assertTrue(ok.succeeded)
        val crashed = parseInstrumentation(passing.take(3) + "INSTRUMENTATION_FAILED: a.B")
        assertFalse(crashed.succeeded)
        assertNull(crashed.code)
    }

    @Test
    fun `the report escapes what XML can't hold`() {
        val run = parseInstrumentation(passing)
        val xml = junitXml("pkg", run)
        assertTrue("<testsuite name=\"pkg\" tests=\"2\" failures=\"1\"" in xml)
        assertTrue("<failure message=\"java.lang.AssertionError: no\">" in xml)
        assertTrue("&amp;" in junitXml("p&q", run))
    }
}
