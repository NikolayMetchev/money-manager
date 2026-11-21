package com.moneymanager.tools.androiddevicetest

import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** An adb that answers from [reply] and remembers what it was asked. */
private class FakeAdb(
    private val commands: MutableList<List<String>> = mutableListOf(),
    private val reply: (List<String>) -> String = { "" },
) : Adb {
    val asked: List<List<String>> get() = commands

    override fun withSerial(serial: String): Adb = FakeAdb(commands, reply)

    override fun capture(vararg args: String): String = args.toList().also { commands += it }.let(reply)

    override fun run(
        vararg args: String,
        check: Boolean,
    ) {
        commands += args.toList()
    }

    override fun stream(
        vararg args: String,
        onLine: (String) -> Unit,
    ) {
        commands += args.toList()
        reply(args.toList()).lines().forEach(onLine)
    }
}

private val java: Path = Path.of(System.getProperty("java.home"), "bin", "java")

private const val PASSING =
    "INSTRUMENTATION_STATUS: class=a.B\nINSTRUMENTATION_STATUS: test=works\nINSTRUMENTATION_STATUS_CODE: 1\n" +
        "INSTRUMENTATION_STATUS: class=a.B\nINSTRUMENTATION_STATUS: test=works\nINSTRUMENTATION_STATUS_CODE: 0\n" +
        "INSTRUMENTATION_CODE: -1"

class RunnerTest {
    private val dir = Files.createTempDirectory("device-test")

    @Test
    fun `tests install the apps then the APK, run, and uninstall them all`() {
        val adb = FakeAdb { if ("instrument" in it) PASSING else "" }
        val xml = dir.resolve("test.xml")
        val passed = runTests(adb, Path.of("t.apk"), "p.test", "r.Runner", mapOf("p.app" to Path.of("app.apk")), xml)

        assertTrue(passed)
        assertEquals(
            listOf(
                listOf("wait-for-device"),
                listOf("install", "-r", "-t", Path.of("app.apk").toString()),
                listOf("install", "-r", "-t", Path.of("t.apk").toString()),
                listOf("shell", "am", "instrument", "-w", "-r", "p.test/r.Runner"),
                listOf("uninstall", "p.test"),
                listOf("uninstall", "p.app"),
            ),
            adb.asked,
        )
        assertTrue("tests=\"1\"" in Files.readString(xml))
    }

    @Test
    fun `a run that crashed fails, and still uninstalls`() {
        val adb = FakeAdb { if ("instrument" in it) "INSTRUMENTATION_FAILED: p.test/r.Runner" else "" }
        assertFalse(runTests(adb, Path.of("t.apk"), "p.test", "r.Runner", emptyMap(), null))
        assertTrue(listOf("uninstall", "p.test") in adb.asked)
    }

    @Test
    fun `a device that is connected runs the tests without booting anything`() {
        val adb =
            FakeAdb {
                if (it ==
                    listOf("devices")
                ) {
                    "List of devices attached\nR58N\tdevice\n"
                } else if ("instrument" in it) {
                    PASSING
                } else {
                    ""
                }
            }
        assertTrue(runOnDevice(adb, dir.resolve("none.properties"), Path.of("t.apk"), "p.test", "r.Runner", emptyMap(), null))
    }

    @Test
    fun `a device that is connected is used, none is not`() {
        assertTrue(EmulatorLauncher.deviceConnected(FakeAdb { "List of devices attached\nR58N\tdevice\n" }))
        assertFalse(EmulatorLauncher.deviceConnected(FakeAdb { "List of devices attached\n\n" }))
    }

    @Test
    fun `adb runs through a process, on the device it is aimed at`() {
        val adb = ProcessAdb(java)
        assertTrue("version" in adb.capture("-version"))
        adb.run("-version")
        assertFailsWith<IllegalStateException> { adb.run("--no-such-option") }
        adb.run("--no-such-option", check = false)
        val lines = mutableListOf<String>()
        adb.stream("-version") { lines += it }
        assertTrue(lines.isNotEmpty())
        // java rejects -s, so the serial reached the command line
        assertTrue("-s" in adb.withSerial("emulator-5554").capture("-version"))
    }

    @Test
    fun `the properties file is read beside itself, and a blocked one says why`() {
        val properties = Files.writeString(dir.resolve("emulator.properties"), "sdk=sdk\nabi=x86_64\napi=36\nimage=system-images/x\n")
        val config = EmulatorConfig.read(properties)
        assertEquals(dir.toRealPath().resolve("sdk"), config.sdk)
        assertEquals("x86_64", config.abi)

        val blocked = Files.writeString(dir.resolve("blocked.properties"), "blocked=no KVM here\n")
        val error = assertFailsWith<IllegalStateException> { EmulatorConfig.read(blocked) }
        assertTrue("no KVM here" in error.message.orEmpty())
    }

    @Test
    fun `the AVD, command and environment point at the temporary directories`() {
        val config = EmulatorConfig(dir.resolve("sdk"), "arm64-v8a", "36", "system-images/android-36/aosp_atd/arm64-v8a")
        val avdHome = writeAvd(dir, config)
        assertTrue("abi.type=arm64-v8a" in Files.readString(avdHome.resolve("money_manager.avd").resolve("config.ini")))
        assertTrue("target=android-36" in Files.readString(avdHome.resolve("money_manager.ini")))

        val unix = emulatorCommand(config.sdk, windows = false, port = 5556)
        assertTrue(unix.first().endsWith("emulator"))
        assertTrue(emulatorCommand(config.sdk, windows = true, port = 5556).first().endsWith("emulator.exe"))
        assertEquals("5556", unix[unix.indexOf("-port") + 1])

        val environment = emulatorEnvironment(config.sdk, avdHome, dir.resolve("home"))
        assertEquals(avdHome.toString(), environment["ANDROID_AVD_HOME"])
        assertEquals(config.sdk.toString(), environment["ANDROID_SDK_ROOT"])
    }

    @Test
    fun `an emulator port is a free even one, and a taken one is not free`() {
        val port = EmulatorLauncher.freePort()
        assertTrue(port in 5554..5584 && port % 2 == 0)
        ServerSocket(port, 1, InetAddress.getLoopbackAddress()).use {
            assertFalse(EmulatorLauncher.free(port))
        }
    }

    private fun properties(): Path =
        Files.writeString(dir.resolve("emulator.properties"), "sdk=sdk\nabi=x86_64\napi=36\nimage=system-images/x\n")

    private fun sleeper(): Process {
        val source =
            Files.writeString(
                dir.resolve("Sleeper.java"),
                "public class Sleeper { public static void main(String[] a) throws Exception { Thread.sleep(600000); } }",
            )
        return ProcessBuilder(java.toString(), source.toString()).start()
    }

    @Test
    fun `an emulator that boots is waited for, set up, and stopped with its directory`() {
        var serial = ""
        var process: Process? = null
        val adb =
            FakeAdb {
                when {
                    it == listOf("devices") -> "List of devices attached\n$serial\tdevice\n"
                    "sys.boot_completed" in it -> "1\n"
                    "pm" in it -> "package:/system/framework/framework-res.apk\n"
                    else -> ""
                }
            }
        val launcher =
            EmulatorLauncher.start(
                adb,
                properties(),
                launch = { command, environment, _ ->
                    serial = "emulator-" + command[command.indexOf("-port") + 1]
                    assertTrue(environment.containsKey("ANDROID_AVD_HOME"))
                    sleeper().also { process = it }
                },
                requireAcceleration = { _, _ -> },
            )
        assertEquals(serial, launcher.serial)
        assertTrue(adb.asked.any { it.takeLast(3) == listOf("window_animation_scale", "0") || "window_animation_scale" in it })
        assertTrue(process!!.isAlive)

        launcher.close()

        assertFalse(process.isAlive)
        assertTrue(adb.asked.any { it == listOf("emu", "kill") })
    }

    @Test
    fun `an emulator that dies before it appears is reported, with its log`() {
        val adb = FakeAdb { "List of devices attached\n\n" }
        val error =
            assertFailsWith<IllegalStateException> {
                EmulatorLauncher.start(
                    adb,
                    properties(),
                    launch = { _, _, log ->
                        Files.writeString(log, "FATAL | Broken AVD\n")
                        ProcessBuilder(java.toString(), "-version").start().also { it.waitFor() }
                    },
                    requireAcceleration = { _, _ -> },
                )
            }
        assertTrue("exited" in error.message.orEmpty())
        assertTrue("Broken AVD" in error.message.orEmpty())
    }
}
