package com.moneymanager.tools.repo

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RepinMoreTest {
    private fun tempFile(text: String): File = Files.createTempFile("repin", ".txt").toFile().also { it.writeText(text) }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `a stale JDK pin is replaced by the newest Zulu build Azul names`() {
        val platforms = listOf("linux_aarch64", "linux_x86_64", "macos_aarch64", "macos_x86_64", "windows_x86_64")
        val module =
            tempFile(
                "JDK = \"27.0.2\"\n" +
                    platforms.joinToString("\n") { p -> "    \"$p\": [\"zulu27.30.109-ca-jdk27.0.1-$p.tar.gz\", \"${"c".repeat(64)}\"]," },
            )
        val rc = tempFile("build --java_runtime_version=remotejdk_26\n")
        val tools = tempFile("java zulu-1.0\n")
        val asked = mutableListOf<String>()
        repinJdk(module, rc, tools) { version, (os, _, _) ->
            asked += "$version $os"
            "zulu27.31.1-ca-jdk27.0.2-$os.tar.gz" to "d".repeat(64)
        }
        assertEquals(5, asked.size)
        assertTrue(module.readText().contains("zulu27.31.1-ca-jdk27.0.2-linux.tar.gz\", \"${"d".repeat(64)}\""))
        assertEquals("java zulu-27.31.1\n", tools.readText())
    }

    @Test
    fun `pdfium archives get the checksum of what is downloaded`() {
        val module =
            tempFile(
                "PDFIUM = \"7000\"\n" +
                    "http_archive(\n    sha256 = \"${"0".repeat(64)}\",\n" +
                    "    url = \"https://github.com/bblanchon/pdfium-binaries/releases/download/chromium%2F\" + PDFIUM + \"/pdfium-linux-x64.tgz\",\n)\n",
            )
        val urls = mutableListOf<String>()
        repinPdfium(module) { url ->
            urls += url
            "abc".toByteArray()
        }
        assertEquals(listOf("https://github.com/bblanchon/pdfium-binaries/releases/download/chromium%2F7000/pdfium-linux-x64.tgz"), urls)
        assertTrue(module.readText().contains("sha256 = \"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad\""))
    }

    @Test
    fun `aapt2 is pinned to the build AGP names with each host jar's checksum`() {
        val module = tempFile("android_sdk.agp(version = \"9.1.0\")\n")
        val pins =
            tempFile(
                "AAPT2_BUILD = \"old\"\nSHA = {\n" +
                    listOf("linux", "osx", "windows").joinToString("\n") { "    \"$it\": \"${"0".repeat(64)}\"," } + "\n}\n",
            )
        val plugin = zip("com/android/build/gradle/internal/res/aapt2_version.properties" to "aapt2Version=123-456\n".toByteArray())
        val urls = mutableListOf<String>()
        repinAapt2(module, pins) { url ->
            urls += url
            if (url.contains("/gradle/")) plugin else "abc".toByteArray()
        }
        val text = pins.readText()
        assertTrue(text.contains("AAPT2_BUILD = \"123-456\""))
        assertEquals(3, Regex("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad").findAll(text).count())
        assertTrue(urls.any { it.endsWith("aapt2-9.1.0-123-456-osx.jar") })
    }

    @Test
    fun `the bytecode is the JDK's, as far as the Kotlin compiler targets`() {
        val root = Files.createTempDirectory("root").toFile()

        fun write(
            path: String,
            text: String,
        ) = File(root, path).also { it.parentFile.mkdirs() }.writeText(text)
        write("MODULE.bazel", "JDK = \"27.0.1\"\nKOTLIN = \"2.4.21\"\n")
        write(".bazelrc", "build --java_language_version=21\n")
        write("bazel/kmp/BUILD.bazel", "jvm_target = \"21\"\n")
        write("bazel/kmp/java/BUILD.bazel", "source_version = \"21\"\ntarget_version = \"21\"\n")
        val kotlinc = Files.createTempDirectory("kotlinc").toFile()
        File(kotlinc, "build.txt").writeText("2.4.21\n")
        File(kotlinc, "lib").mkdirs()
        File(kotlinc, "lib/kotlin-compiler.jar").writeBytes(
            zip("org/jetbrains/kotlin/config/JvmTarget.class" to "JVM_1_8 JVM_9 JVM_25 JVM_26 JVM_26_X".toByteArray()),
        )
        repinBytecode(kotlinc, root)
        assertEquals("build --java_language_version=26\n", File(root, ".bazelrc").readText())
        assertEquals("jvm_target = \"26\"\n", File(root, "bazel/kmp/BUILD.bazel").readText())
        assertEquals("source_version = \"26\"\ntarget_version = \"26\"\n", File(root, "bazel/kmp/java/BUILD.bazel").readText())
    }
}
