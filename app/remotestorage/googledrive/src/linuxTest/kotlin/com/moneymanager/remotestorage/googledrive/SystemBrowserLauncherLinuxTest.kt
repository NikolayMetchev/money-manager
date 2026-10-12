package com.moneymanager.remotestorage.googledrive

import com.moneymanager.files.writeText
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import platform.posix.chmod
import platform.posix.getenv
import platform.posix.getpid
import platform.posix.setenv
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

// A fake xdg-open on PATH stands in for the real one, so no browser opens.
@OptIn(ExperimentalForeignApi::class)
class SystemBrowserLauncherLinuxTest {
    private val originalPath = getenv("PATH")?.toKString()
    private val binDir = Path(SystemTemporaryDirectory, "mm-xdg-open-${getpid()}")

    @AfterTest
    fun restorePath() {
        setenv("PATH", originalPath.orEmpty(), 1)
        SystemFileSystem.list(binDir).forEach(SystemFileSystem::delete)
        SystemFileSystem.delete(binDir)
    }

    @Test
    fun returnsWithoutWaitingForALongRunningOpener() {
        withFakeOpener("#!/bin/sh\nsleep 30\n")
        val started = TimeSource.Monotonic.markNow()
        assertTrue(openInSystemBrowser("https://example.test/?a=1&b=2"))
        assertTrue(started.elapsedNow() < 10.seconds, "waited ${started.elapsedNow()}")
    }

    @Test
    fun reportsAMissingOpener() {
        withFakeOpener(null)
        assertFalse(openInSystemBrowser("https://example.test/"))
    }

    private fun withFakeOpener(script: String?) {
        SystemFileSystem.createDirectories(binDir)
        if (script != null) {
            val opener = Path(binDir, "xdg-open")
            writeText(opener, script)
            chmod(opener.toString(), "755".toUInt(8))
        }
        setenv("PATH", binDir.toString(), 1)
    }
}
