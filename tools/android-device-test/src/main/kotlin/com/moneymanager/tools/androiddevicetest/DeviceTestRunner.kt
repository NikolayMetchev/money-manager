package com.moneymanager.tools.androiddevicetest

import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * A self-instrumenting test APK's tests on a device, as `connectedAndroidDeviceTest` runs them: install,
 * `am instrument -w -r` with the APK's runner, uninstall. The raw instrumentation output becomes Bazel's test.xml, one
 * testcase per test, and any failed, errored or crashed test (or a run that never finished) fails the target.
 *
 * The device is one adb already has (`ANDROID_SERIAL` when several are connected). With none, the tests boot the emulator
 * Bazel downloaded ([EmulatorLauncher]) and stop it after.
 *
 * Arguments: `<adb> <emulator.properties> <apk> <package> <runner> [<app package>=<app apk> …]`: the apps are installed
 * first and uninstalled after (the app a `com.android.test` module drives).
 */
fun main(args: Array<String>) {
    val adb = Path.of(args[0]).toAbsolutePath()
    val emulatorProperties = Path.of(args[1]).toAbsolutePath()
    val apk = Path.of(args[2]).toAbsolutePath()
    val pkg = args[3]
    val runner = args[4]
    val apps = args.drop(5).associate { it.substringBefore('=') to Path.of(it.substringAfter('=')).toAbsolutePath() }

    val tools = Adb(adb)
    val passed =
        if (EmulatorLauncher.deviceConnected(tools)) {
            runTests(tools, apk, pkg, runner, apps)
        } else {
            EmulatorLauncher.start(tools, emulatorProperties).use { emulator ->
                runTests(tools.withSerial(emulator.serial), apk, pkg, runner, apps)
            }
        }
    exitProcess(if (passed) 0 else 1)
}

private fun runTests(
    adb: Adb,
    apk: Path,
    pkg: String,
    runner: String,
    apps: Map<String, Path>,
): Boolean {
    // The build's adb can differ from the one that started the server (an emulator runner's, Android Studio's), and a
    // version mismatch restarts the server, which then takes a moment to find the device again.
    adb.run("wait-for-device")
    for (app in apps.values) adb.run("install", "-r", "-t", app.toString())
    adb.run("install", "-r", "-t", apk.toString())
    val run =
        try {
            val process = adb.command("shell", "am", "instrument", "-w", "-r", "$pkg/$runner").redirectErrorStream(true).start()
            val lines = mutableListOf<String>()
            process.inputStream.bufferedReader().useLines { output ->
                output.forEach {
                    println(it)
                    lines += it
                }
            }
            process.waitFor()
            parseInstrumentation(lines)
        } finally {
            adb.run("uninstall", pkg, check = false)
            for (app in apps.keys) adb.run("uninstall", app, check = false)
        }
    System.getenv("XML_OUTPUT_FILE")?.let { writeJunitXml(Path.of(it), pkg, run) }
    println("${run.results.size} tests, ${run.results.count { it.failed }} failed")
    run.failure?.let { println("Instrumentation failed: $it") }
    return run.succeeded
}
