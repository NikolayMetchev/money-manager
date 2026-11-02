package com.moneymanager.tools.repo

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class RepinTest {
    private fun tempFile(text: String): File = Files.createTempFile("repin", ".bazel").toFile().also { it.writeText(text) }

    @Test
    fun `node archives get the checksums nodejs publishes`() {
        val file =
            tempFile(
                """
                NODE = "26.1.0"
                x = ["node-v" + NODE + "-linux-x64.tar.xz", "node-v" + NODE + "-linux-x64", "${"0".repeat(64)}"]
                """.trimIndent(),
            )
        repinNode(file) { version -> "${"a".repeat(64)}  node-v$version-linux-x64.tar.xz\n${"b".repeat(64)}  other\n" }
        assertEquals(true, file.readText().contains("\"${"a".repeat(64)}\"]"))
    }

    @Test
    fun `skiko follows the one release Compose requires`() {
        val file = tempFile("COMPOSE_PLUGIN = \"1.12.0\"\nSKIKO = \"0.1.0\"\n")
        val gradleModule =
            """{"variants":[{"dependencies":[{"group":"org.jetbrains.skiko","module":"skiko","version":{"requires":"0.9.99"}}]},{}]}"""
        repinSkiko(file) { gradleModule }
        assertEquals("COMPOSE_PLUGIN = \"1.12.0\"\nSKIKO = \"0.9.99\"\n", file.readText())
    }

    @Test
    fun `a current JDK pin is kept without asking Azul`() {
        val module =
            File.createTempFile("module", ".bazel").also {
                it.writeText(
                    "JDK = \"27.0.1\"\n" +
                        listOf("linux_aarch64", "linux_x86_64", "macos_aarch64", "macos_x86_64", "windows_x86_64")
                            .joinToString("\n") { p -> "    \"$p\": [\"zulu27.30.109-ca-jdk27.0.1-$p.tar.gz\", \"${"c".repeat(64)}\"]," },
                )
            }
        val rc = tempFile("build --java_runtime_version=remotejdk_26\n")
        val tools = tempFile("java zulu-1.0 # pinned\n")
        repinJdk(module, rc, tools) { _, _ -> error("Azul must not be asked") }
        assertEquals("build --java_runtime_version=remotejdk_27\n", rc.readText())
        assertEquals("java zulu-27.30.109 # pinned\n", tools.readText())
    }
}
