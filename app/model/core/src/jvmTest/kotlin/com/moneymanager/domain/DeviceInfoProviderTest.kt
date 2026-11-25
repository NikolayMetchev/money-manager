package com.moneymanager.domain

import com.moneymanager.domain.model.DeviceInfo
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
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
}
