package com.moneymanager.domain

import com.moneymanager.domain.model.DeviceInfo
import java.util.concurrent.TimeUnit

private const val HOSTNAME_TIMEOUT_SECONDS = 5L

/**
 * JVM implementation of DeviceInfoProvider.
 * Captures OS name and machine hostname.
 */
actual fun getDeviceInfo(): DeviceInfo =
    DeviceInfo.Jvm(
        osName = System.getProperty("os.name") ?: "Unknown",
        machineName = machineHostname() ?: "Unknown",
    )

/**
 * The machine's own name, without a DNS lookup. `InetAddress.getLocalHost()` resolves the name, and on a host whose
 * name doesn't resolve (GitHub's macOS runners, often) that blocks for ~35 s on every JVM start that opens a database.
 * `hostname` just reads it (what the native targets' `gethostname` does), and exists on Linux, macOS and Windows.
 */
private fun machineHostname(): String? = machineHostname(System::getenv, ::runHostnameCommand)

internal fun machineHostname(
    env: (String) -> String?,
    runHostname: () -> String?,
): String? =
    env("COMPUTERNAME").nonBlank()
        ?: runCatching(runHostname).getOrNull().nonBlank()
        ?: env("HOSTNAME").nonBlank()

private fun String?.nonBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

private fun runHostnameCommand(): String? {
    val process = ProcessBuilder("hostname").redirectErrorStream(true).start()
    process.outputStream.close()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    return if (process.waitFor(HOSTNAME_TIMEOUT_SECONDS, TimeUnit.SECONDS) && process.exitValue() == 0) {
        output
    } else {
        process.destroyForcibly()
        null
    }
}
