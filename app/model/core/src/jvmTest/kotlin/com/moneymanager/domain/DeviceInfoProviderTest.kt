package com.moneymanager.domain

import com.moneymanager.domain.model.DeviceInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceInfoProviderTest {
    @Test
    fun `reads the machine name without hanging`() {
        val start = System.nanoTime()
        val info = getDeviceInfo()
        val elapsedSeconds = (System.nanoTime() - start) / 1_000_000_000

        assertIs<DeviceInfo.Jvm>(info)
        assertNotEquals("Unknown", info.machineName)
        assertTrue(elapsedSeconds < 5, "took ${elapsedSeconds}s")
    }

    @Test
    fun `prefers COMPUTERNAME and skips the command`() {
        val name = machineHostname({ if (it == "COMPUTERNAME") "WIN-BOX" else "env" }) { error("not needed") }

        assertEquals("WIN-BOX", name)
    }

    @Test
    fun `uses the hostname command output trimmed`() {
        assertEquals("mac-1", machineHostname({ null }) { "mac-1\n" })
    }

    @Test
    fun `falls back to HOSTNAME when the command fails or prints nothing`() {
        val env = { key: String -> if (key == "HOSTNAME") "from-env" else null }

        assertEquals("from-env", machineHostname(env) { error("no hostname binary") })
        assertEquals("from-env", machineHostname(env) { null })
        assertEquals("from-env", machineHostname(env) { "  " })
    }

    @Test
    fun `is null when nothing names the machine`() {
        assertNull(machineHostname({ null }) { null })
    }
}
