package com.moneymanager.tools.androiddevicetest

import java.nio.file.Path

/** adb as one-shot commands, aimed at one device once [withSerial] names it. */
internal interface Adb {
    fun withSerial(serial: String): Adb

    /** The command's stdout and stderr; its exit status is not checked. */
    fun capture(vararg args: String): String

    /** Runs the command with its output on ours; a non-zero exit is an error when [check]. */
    fun run(
        vararg args: String,
        check: Boolean = true,
    )

    /** Runs the command, handing each line of its output to [onLine] as it arrives. */
    fun stream(
        vararg args: String,
        onLine: (String) -> Unit,
    )
}

/** The real adb at [path], aimed at [serial] when the run booted its own emulator, else at the device adb picks. */
internal class ProcessAdb(
    private val path: Path,
    private val serial: String? = null,
) : Adb {
    override fun withSerial(serial: String) = ProcessAdb(path, serial)

    private fun command(args: Array<out String>): ProcessBuilder =
        ProcessBuilder(listOf(path.toString()) + serial?.let { listOf("-s", it) }.orEmpty() + args)

    override fun capture(vararg args: String): String {
        val process = command(args).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().decodeToString()
        process.waitFor()
        return output
    }

    override fun run(
        vararg args: String,
        check: Boolean,
    ) {
        val exit = command(args).inheritIO().start().waitFor()
        check(!check || exit == 0) { "adb ${args.joinToString(" ")} exited $exit" }
    }

    override fun stream(
        vararg args: String,
        onLine: (String) -> Unit,
    ) {
        val process = command(args).redirectErrorStream(true).start()
        process.inputStream.bufferedReader().useLines { it.forEach(onLine) }
        process.waitFor()
    }
}
