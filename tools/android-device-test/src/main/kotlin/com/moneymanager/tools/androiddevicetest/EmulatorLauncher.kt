package com.moneymanager.tools.androiddevicetest

import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.Properties
import java.util.concurrent.TimeUnit

private const val AVD_NAME = "money_manager"
private val BOOT_TIMEOUT = Duration.ofMinutes(6)

/** The AVD's `config.ini`, as `avdmanager` writes it for a phone with the given system image. */
internal fun avdConfig(
    abi: String,
    image: String,
): String =
    listOf(
        "AvdId=$AVD_NAME",
        "avd.ini.displayname=$AVD_NAME",
        "PlayStore.enabled=false",
        "abi.type=$abi",
        "hw.cpu.arch=${if (abi == "arm64-v8a") "arm64" else "x86_64"}",
        "hw.ramSize=2048",
        "hw.lcd.width=1080",
        "hw.lcd.height=2400",
        "hw.lcd.density=420",
        "hw.gpu.enabled=yes",
        "hw.gpu.mode=swiftshader_indirect",
        "hw.keyboard=yes",
        "hw.sdCard=no",
        "hw.audioInput=no",
        "hw.audioOutput=no",
        "disk.dataPartition.size=4G",
        "image.sysdir.1=$image/",
        "showDeviceFrame=no",
        "tag.id=aosp_atd",
        "tag.display=AOSP ATD",
    ).joinToString("\n", postfix = "\n")

/** The serials `adb devices` lists as online (not `offline` or `unauthorized`). */
internal fun onlineDevices(output: String): List<String> =
    output
        .lines()
        .map { it.trim().split(Regex("\\s+")) }
        .filter { it.size == 2 && it[1] == "device" }
        .map { it[0] }

internal fun hypervisorHint(osName: String): String =
    when {
        osName.startsWith("windows", ignoreCase = true) -> {
            "Turn on the \"Windows Hypervisor Platform\" feature (Turn Windows features on or off) and restart, or connect a device."
        }

        osName.startsWith("mac", ignoreCase = true) -> {
            "Hypervisor.framework is missing or blocked (a virtual machine without nested virtualization?): connect a device instead."
        }

        else -> {
            "Give your user access to /dev/kvm (`sudo usermod -aG kvm \$USER`, then log in again) and enable " +
                "virtualization in the firmware, or connect a device."
        }
    }

/**
 * Boots the emulator Bazel downloaded (`@android_emulator`) when a device test finds no device, and stops it again.
 * Nothing is installed on the machine: the SDK root is the repository's, the AVD lives in a temporary directory and the
 * emulator listens on a free port, so it can't clash with an emulator or a device the person already runs.
 *
 * The properties file `@android_emulator` writes says where everything is, relative to itself: `sdk`, `abi`, `api` and
 * `image` (the system image's directory under the SDK). A host the emulator can't run on, or a license not yet accepted,
 * is `blocked=<why>` instead.
 */
internal class EmulatorLauncher private constructor(
    private val adb: Adb,
    private val root: Path,
    private val process: Process,
    private val log: Path,
    val serial: String,
) : AutoCloseable {
    // Bazel ends a test that times out with a signal, which skips `use`: don't leave a 2 GB process behind.
    private val shutdownHook = Thread { process.destroyForcibly() }

    init {
        Runtime.getRuntime().addShutdownHook(shutdownHook)
    }

    private fun awaitBoot() {
        val deadline = Instant.now().plus(BOOT_TIMEOUT)
        // The emulator registers with the adb server a moment after it starts. `adb wait-for-device` would wait forever
        // for one that died instead.
        while (serial !in onlineDevices(adb.capture("devices"))) {
            check(process.isAlive) { "The emulator exited (${process.exitValue()}) before it booted:\n${tail()}" }
            check(
                Instant.now().isBefore(deadline),
            ) { "The emulator didn't appear in adb in ${BOOT_TIMEOUT.toMinutes()} minutes:\n${tail()}" }
            Thread.sleep(1000)
        }
        val device = adb.withSerial(serial)
        while (device.capture("shell", "getprop", "sys.boot_completed").trim() != "1") {
            check(process.isAlive) { "The emulator exited (${process.exitValue()}) before it booted:\n${tail()}" }
            check(
                Instant.now().isBefore(deadline),
            ) { "The emulator didn't finish booting in ${BOOT_TIMEOUT.toMinutes()} minutes:\n${tail()}" }
            Thread.sleep(2000)
        }
        // The package manager answers a little after boot_completed; an install before that fails.
        while (!device.capture("shell", "pm", "path", "android").startsWith("package:")) {
            check(Instant.now().isBefore(deadline)) { "The emulator's package manager didn't start:\n${tail()}" }
            Thread.sleep(1000)
        }
        // Animations make Compose tests flaky, and cost time.
        listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale").forEach {
            device.capture("shell", "settings", "put", "global", it, "0")
        }
    }

    private fun tail(): String = Files.readAllLines(log).takeLast(40).joinToString("\n")

    override fun close() {
        try {
            if (process.isAlive) {
                // The emulator saves nothing (-no-snapshot), so killing it through adb is quick; wait, then insist.
                runCatching { adb.withSerial(serial).capture("emu", "kill") }
                if (!process.waitFor(30, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(30, TimeUnit.SECONDS)
                }
            }
        } finally {
            // Whatever adb did (it may not even start), nothing is left running.
            process.destroyForcibly()
            runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) } // already running, if the JVM is exiting
            root.toFile().deleteRecursively()
        }
    }

    companion object {
        /**
         * Whether the tests use a device that isn't ours to boot: the one `ANDROID_SERIAL` names (adb waits for it to come
         * online, rather than the tests quietly running on an emulator instead), else any online one.
         */
        fun deviceConnected(adb: Adb): Boolean =
            !System.getenv("ANDROID_SERIAL").isNullOrEmpty() || onlineDevices(adb.capture("devices")).isNotEmpty()

        /** Starts the emulator [properties] describes and waits for it to finish booting. */
        fun start(
            adb: Adb,
            properties: Path,
        ): EmulatorLauncher {
            val config = Properties().apply { Files.newInputStream(properties).use { load(it) } }
            config.getProperty("blocked")?.let {
                error("No device is connected and the emulator can't be used: ${it.trim()}")
            }
            // The repository can be a symlink farm in a sandbox; the emulator finds its libraries beside its own binary.
            val sdk = properties.toRealPath().parent.resolve(config.getProperty("sdk"))
            val abi = config.getProperty("abi")
            val windows = System.getProperty("os.name").startsWith("windows", ignoreCase = true)
            val emulator = sdk.resolve("emulator").resolve(if (windows) "emulator.exe" else "emulator")
            requireAcceleration(sdk, windows)

            val root = Files.createTempDirectory(tempParent(), "emulator")
            var launched = false
            try {
                val avdHome = Files.createDirectories(root.resolve("avd"))
                val avd = Files.createDirectories(avdHome.resolve("$AVD_NAME.avd"))
                Files.writeString(avd.resolve("config.ini"), avdConfig(abi, config.getProperty("image")))
                Files.writeString(
                    avdHome.resolve("$AVD_NAME.ini"),
                    "avd.ini.encoding=UTF-8\npath=${avd.toString().replace('\\', '/')}\ntarget=android-${config.getProperty("api")}\n",
                )
                val port = freePort()
                val log = root.resolve("emulator.log")
                val builder =
                    ProcessBuilder(
                        emulator.toString(),
                        "-avd",
                        AVD_NAME,
                        "-port",
                        port.toString(),
                        "-no-window",
                        "-no-audio",
                        "-no-snapshot",
                        "-no-boot-anim",
                        "-no-metrics",
                        "-gpu",
                        "swiftshader_indirect",
                    ).redirectErrorStream(true).redirectOutput(log.toFile())
                builder.environment().apply {
                    put("ANDROID_SDK_ROOT", sdk.toString())
                    put("ANDROID_HOME", sdk.toString())
                    put("ANDROID_AVD_HOME", avdHome.toString())
                    put("ANDROID_EMULATOR_HOME", Files.createDirectories(root.resolve("home")).toString())
                }
                println("Booting the Android emulator emulator-$port ($abi, API ${config.getProperty("api")})")
                val launcher = EmulatorLauncher(adb, root, builder.start(), log, "emulator-$port")
                var booted = false
                try {
                    launcher.awaitBoot()
                    booted = true
                } finally {
                    // close() also removes the temporary directory
                    if (!booted) launcher.close()
                }
                launched = true
                return launcher
            } finally {
                if (!launched) root.toFile().deleteRecursively()
            }
        }

        /**
         * `emulator-check accel`, which every host's emulator package carries: exit 0 when the hypervisor (KVM,
         * Hypervisor.framework, the Windows Hypervisor Platform) is usable. Without it the emulator either refuses to
         * start or crawls, and this says why.
         */
        private fun requireAcceleration(
            sdk: Path,
            windows: Boolean,
        ) {
            val check = sdk.resolve("emulator").resolve(if (windows) "emulator-check.exe" else "emulator-check")
            val process = ProcessBuilder(check.toString(), "accel").redirectErrorStream(true).start()
            val output =
                process.inputStream
                    .readBytes()
                    .decodeToString()
                    .trim()
            check(process.waitFor() == 0) {
                "No device is connected and this machine can't accelerate the emulator:\n$output\n" +
                    hypervisorHint(System.getProperty("os.name"))
            }
        }

        /** The even port of a free pair in the range adb scans for emulators (5554..5584). */
        private fun freePort(): Int =
            (5554..5584 step 2).firstOrNull { free(it) && free(it + 1) }
                ?: error("No free emulator port in 5554..5585: stop another emulator")

        private fun free(port: Int): Boolean = runCatching { ServerSocket(port, 1, InetAddress.getLoopbackAddress()).use { } }.isSuccess

        private fun tempParent(): Path =
            System.getenv("TEST_TMPDIR")?.let { Files.createDirectories(Path.of(it)) } ?: Path.of(System.getProperty("java.io.tmpdir"))
    }
}
