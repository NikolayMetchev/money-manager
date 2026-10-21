package com.moneymanager.domain

import com.moneymanager.domain.model.DeviceInfo
import kotlin.js.ExperimentalWasmJsInterop

/** The browser app records the OS and the browser it runs in, both read from the user agent. */
actual fun getDeviceInfo(): DeviceInfo = deviceInfoFromUserAgent(userAgent())

@OptIn(ExperimentalWasmJsInterop::class)
private fun userAgent(): String = js("(globalThis.navigator && globalThis.navigator.userAgent) || ''")

/** Names the OS and the browser in [userAgent]; the order matters, since most browsers also claim to be others. */
internal fun deviceInfoFromUserAgent(userAgent: String): DeviceInfo.Web {
    fun has(token: String) = userAgent.contains(token)
    val os =
        when {
            has("Windows") -> "Windows"
            has("iPhone") || has("iPad") -> "iOS"
            has("Mac OS") -> "macOS"
            has("Android") -> "Android"
            has("CrOS") -> "ChromeOS"
            has("Linux") -> "Linux"
            else -> UNKNOWN
        }
    val browser =
        when {
            has("Edg/") -> "Edge"
            has("OPR/") -> "Opera"
            has("Firefox/") -> "Firefox"
            has("Chrome/") || has("CriOS/") -> "Chrome"
            has("Safari/") -> "Safari"
            else -> userAgent.substringBefore('/').ifBlank { UNKNOWN }
        }
    return DeviceInfo.Web(osName = os, machineName = browser)
}

private const val UNKNOWN = "Unknown"
