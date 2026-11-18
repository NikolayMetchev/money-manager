package com.moneymanager.tools.repo

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class RepinTest {
    private fun tempFile(text: String): File = Files.createTempFile("repin", ".bazel").toFile().also { it.writeText(text) }

    @Test
    fun `Zulu selection retains full versions and falls back only within the requested release`() {
        val listing =
            """[
            {"name":"old","java_version":[27,0,1,0,3],"distro_version":[27,30,109,0],"package_uuid":"old"},
            {"name":"fixed","java_version":[27,0,1,1],"distro_version":[27,30,201,0],"package_uuid":"fixed"},
            {"name":"future","java_version":[27,0,1,2],"distro_version":[27,30,202,0],"package_uuid":"future"},
            {"name":"other","java_version":[27,0,2],"distro_version":[27,31,1,0],"package_uuid":"other"},
            {"name":"musl","java_version":[27,0,1,1],"distro_version":[27,30,999,0],"package_uuid":"musl"}
            ]"""
        for (os in listOf("linux", "macos")) {
            val expected = if (os == "linux") "fixed" else "old"
            val archive =
                azulArchive("27.0.1.1", Triple(os, "x64", "tar.gz")) { url ->
                    if ("?" in url) {
                        assertEquals(true, "java_version=27.0.1&" in url)
                        (if (os == "linux") listing else listing.replace("[27,0,1,1]", "[27,0,1,2]")).toByteArray()
                    } else {
                        assertEquals(true, url.endsWith("/$expected"))
                        """{"sha256_hash":"${"a".repeat(64)}"}""".toByteArray()
                    }
                }
            assertEquals(expected to "a".repeat(64), archive)
        }
    }

    @Test
    fun `fourth component update repins platforms and retries a lagging platform`() {
        val platforms = listOf("linux_aarch64", "linux_x86_64", "macos_aarch64", "macos_x86_64", "windows_x86_64")
        val module =
            tempFile(
                "JDK = \"27.0.1.1\"\n" +
                    platforms.joinToString("\n") { p ->
                        "    \"$p\": [\"zulu27.30.109-ca-jdk27.0.1.0.3-$p.tar.gz\", \"${"c".repeat(64)}\"],"
                    },
            )
        val rc = tempFile("build --java_runtime_version=remotejdk_27\n")
        val tools = tempFile("java zulu-27.30.109\n")
        val asked = mutableListOf<String>()
        repinJdk(module, rc, tools) { _, (os, _, _) ->
            asked += os
            val version = if (os == "macos") "27.0.1.0.3" else "27.0.1.1"
            "zulu27.30.201-ca-jdk$version-$os.tar.gz" to "d".repeat(64)
        }
        assertEquals(5, asked.size)
        asked.clear()
        repinJdk(module, rc, tools) { _, (os, _, _) ->
            asked += os
            "zulu27.30.201-ca-jdk27.0.1.1-$os.tar.gz" to "e".repeat(64)
        }
        assertEquals(listOf("macos", "macos"), asked)
        repinJdk(module, rc, tools) { _, _ -> error("Exact pins must be retained") }
        assertEquals("java zulu-27.30.201\n", tools.readText())
    }

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
