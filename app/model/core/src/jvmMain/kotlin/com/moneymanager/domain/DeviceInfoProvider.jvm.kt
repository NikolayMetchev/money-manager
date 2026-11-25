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
private fun machineHostname(): String? =
    System.getenv("COMPUTERNAME")?.takeIf { it.isNotBlank() }
        ?: runCatching {
            val process = ProcessBuilder("hostname").redirectErrorStream(true).start()
            process.outputStream.close()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (process.waitFor(HOSTNAME_TIMEOUT_SECONDS, TimeUnit.SECONDS) && process.exitValue() == 0) {
                output.trim().takeIf { it.isNotEmpty() }
            } else {
                process.destroyForcibly()
                null
            }
        }.getOrNull()
        ?: System.getenv("HOSTNAME")?.takeIf { it.isNotBlank() }
