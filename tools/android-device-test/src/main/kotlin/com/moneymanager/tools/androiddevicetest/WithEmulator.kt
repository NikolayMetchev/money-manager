package com.moneymanager.tools.androiddevicetest

import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Runs a command with one emulator up, for a command that runs many device tests: each device test target boots its own
 * emulator when none is connected, which is a cold boot per target, and here they all find this one.
 *
 *     bazel run --config=device //tools/android-device-test:with-emulator -- bazel test --config=device //...
 *
 * Arguments: `<adb> <emulator.properties> <command> [<args> …]` (Bazel supplies the first two). The command runs where
 * `bazel run` was started, and its exit status is this program's. A device that's already connected, or `ANDROID_SERIAL`,
 * is used instead, and nothing is booted.
 */
fun main(args: Array<String>) {
    val adb = ProcessAdb(Path.of(args[0]).toAbsolutePath())
    val properties = Path.of(args[1]).toAbsolutePath()
    val directory = System.getenv("BUILD_WORKING_DIRECTORY")?.let { Path.of(it) }
    exitProcess(withEmulator(adb, properties, args.drop(2)) { runCommand(it, directory) })
}

/** Runs [command] through [run], with an emulator booted around it unless a device is already there. */
internal fun withEmulator(
    adb: Adb,
    properties: Path,
    command: List<String>,
    start: (Adb, Path) -> AutoCloseable = EmulatorLauncher::start,
    run: (List<String>) -> Int,
): Int {
    require(command.isNotEmpty()) { "usage: with-emulator <command> [<args> …]" }
    if (EmulatorLauncher.deviceConnected(adb)) return run(command)
    return start(adb, properties).use { run(command) }
}

internal fun runCommand(
    command: List<String>,
    directory: Path?,
): Int {
    val builder = ProcessBuilder(command).inheritIO()
    directory?.let { builder.directory(it.toFile()) }
    return builder.start().waitFor()
}
