package com.moneymanager.tools.androiddevicetest

import java.nio.file.Path

/** adb as one-shot commands, aimed at [serial] when the run booted its own emulator, else at the device adb picks. */
internal class Adb(
    private val path: Path,
    private val serial: String? = null,
) {
    fun withSerial(serial: String) = Adb(path, serial)

    fun command(vararg args: String): ProcessBuilder =
        ProcessBuilder(listOf(path.toString()) + serial?.let { listOf("-s", it) }.orEmpty() + args)

    /** The command's stdout and stderr; its exit status is not checked. */
    fun capture(vararg args: String): String {
        val process = command(*args).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().decodeToString()
        process.waitFor()
        return output
    }

    /** Runs the command with its output on ours; a non-zero exit is an error when [check]. */
    fun run(
        vararg args: String,
        check: Boolean = true,
    ) {
        val process = command(*args).inheritIO().start()
        val exit = process.waitFor()
        check(!check || exit == 0) { "adb ${args.joinToString(" ")} exited $exit" }
    }
}
