package com.moneymanager.domain

import com.moneymanager.domain.model.DeviceInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class DeviceInfoProviderTest {
    @Test
    fun namesTheOsAndBrowser() {
        assertEquals(
            DeviceInfo.Web("Windows", "Edge"),
            deviceInfoFromUserAgent(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36 Edg/141.0.0.0",
            ),
        )
        assertEquals(
            DeviceInfo.Web("macOS", "Safari"),
            deviceInfoFromUserAgent(
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15",
            ),
        )
        assertEquals(
            DeviceInfo.Web("Linux", "Firefox"),
            deviceInfoFromUserAgent("Mozilla/5.0 (X11; Linux x86_64; rv:144.0) Gecko/20100101 Firefox/144.0"),
        )
        assertEquals(
            DeviceInfo.Web("Android", "Chrome"),
            deviceInfoFromUserAgent(
                "Mozilla/5.0 (Linux; Android 16; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36",
            ),
        )
    }

    @Test
    fun readsTheRunningUserAgent() {
        // Node reports itself (`Node.js/26`), with no OS.
        assertEquals(DeviceInfo.Web("Unknown", "Node.js"), getDeviceInfo())
    }
}
