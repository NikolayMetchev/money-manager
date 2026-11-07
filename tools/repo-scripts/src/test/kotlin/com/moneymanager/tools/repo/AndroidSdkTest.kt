package com.moneymanager.tools.repo

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidSdkTest {
    private val dir = Files.createTempDirectory("android-sdk").toFile()

    private fun file(
        name: String,
        text: String,
    ): File = File(dir, name).also { it.writeText(text) }

    private val platformZip =
        ByteArrayOutputStream()
            .also { out ->
                ZipOutputStream(out).use { zip ->
                    zip.putNextEntry(ZipEntry("android-38.0/android.jar"))
                    zip.closeEntry()
                }
            }.toByteArray()

    private val toolsBytes = "tools".toByteArray()

    private fun sha1(bytes: ByteArray) = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun archive(
        url: String,
        sha1: String,
        host: String? = null,
    ) = "<archive><complete><checksum type=\"sha1\">$sha1</checksum><url>$url</url></complete>" +
        host?.let { "<host-os>$it</host-os>" }.orEmpty() + "</archive>"

    private fun index(platformSha1: String = sha1(platformZip)) =
        """
        <sdk-repository>
          <license id="android-sdk-license" type="text">Terms &amp; Conditions</license>
          <remotePackage path="platforms;android-38.1"><channelRef ref="channel-0"/><archives>
            ${archive("wrong.zip", "0")}
          </archives></remotePackage>
          <remotePackage path="platforms;android-38.0"><channelRef ref="channel-0"/><archives>
            ${archive("platform-38.0_r01.zip", platformSha1)}
          </archives></remotePackage>
          <remotePackage path="platform-tools"><channelRef ref="channel-3"/><archives>
            ${archive("platform-tools_r38.0.1-linux.zip", "0", "linux")}
          </archives></remotePackage>
          <remotePackage path="platform-tools"><channelRef ref="channel-0"/><archives>
            ${archive("platform-tools_r38.0.0-linux.zip", sha1(toolsBytes), "linux")}
            ${archive("platform-tools_r38.0.0-darwin.zip", sha1(toolsBytes), "macosx")}
            ${archive("platform-tools_r38.0.0-win.zip", sha1(toolsBytes), "windows")}
          </archives></remotePackage>
        </sdk-repository>
        """.trimIndent()

    private fun moduleText(
        compileSdk: Int = 38,
        platform: String = "38.0_r01",
        tools: String = "38.0.0",
    ) = "android_sdk.platform(version = \"$platform\")\n\nandroid_sdk.platform_tools(version = \"$tools\")\n\n" +
        "android_sdk.levels(\n    compile_sdk = $compileSdk,\n    min_sdk = 30,\n)\n"

    private val module = file("MODULE.bazel", moduleText())
    private val license = file("license.txt", "old terms")

    private fun pins(
        platformFile: String = "old.zip",
        linuxTools: String = "old-linux.zip",
    ) = file(
        "pins.bzl",
        "AAPT2_BUILD = \"1\"\n\nANDROID_PLATFORM = {\n    \"compile_sdk\": 38,\n    \"dir\": \"android-38.0\",\n" +
            "    \"file\": \"$platformFile\",\n    \"sha256\": \"${"a".repeat(64)}\",\n}\n\nPLATFORM_TOOLS = {\n" +
            "    \"linux\": [\"$linuxTools\", \"${"b".repeat(64)}\"],\n" +
            "    \"osx\": [\"old-darwin.zip\", \"${"b".repeat(64)}\"],\n" +
            "    \"windows\": [\"old-win.zip\", \"${"b".repeat(64)}\"],\n}\n",
    )

    private fun fakeDownload(
        asked: MutableList<String>,
        index: String = index(),
    ): (String) -> ByteArray =
        { url ->
            asked += url.substringAfterLast('/')
            when {
                url.endsWith("repository2-3.xml") -> index.toByteArray()
                url.endsWith("platform-38.0_r01.zip") -> platformZip
                else -> toolsBytes
            }
        }

    @Test
    fun `stale pins move to the stable archives the index names and the license follows`() {
        val pins = pins()
        val asked = mutableListOf<String>()
        repinAndroidSdk(module, pins, license, fakeDownload(asked))

        val text = pins.readText()
        assertTrue(text.contains("\"file\": \"platform-38.0_r01.zip\""))
        assertTrue(text.contains("\"sha256\": \"${sha256Hex(platformZip)}\""))
        assertTrue(text.contains("\"osx\": [\"platform-tools_r38.0.0-darwin.zip\", \"${sha256Hex(toolsBytes)}\"]"))
        assertTrue(text.startsWith("AAPT2_BUILD = \"1\"\n"))
        assertEquals("Terms & Conditions", license.readText())
        assertFalse(asked.any { "r38.0.1" in it || it == "wrong.zip" })
    }

    @Test
    fun `a release the index only has on another channel is refused`() {
        module.writeText(moduleText(tools = "38.0.1"))
        val pins = pins()
        assertFailsWith<IllegalStateException> { repinAndroidSdk(module, pins, license, fakeDownload(mutableListOf())) }
    }

    @Test
    fun `a release MODULE bazel doesn't name is left alone`() {
        val index =
            index().replace(
                "<remotePackage path=\"platforms;android-38.1\">",
                "<remotePackage path=\"platforms;android-38.0\"><channelRef ref=\"channel-0\"/><archives>" +
                    archive("platform-38.0_r02.zip", "0") + "</archives></remotePackage>" +
                    "<remotePackage path=\"platforms;android-38.1\">",
            )
        val pins = pins()
        repinAndroidSdk(module, pins, license, fakeDownload(mutableListOf(), index))
        assertTrue(pins.readText().contains("\"file\": \"platform-38.0_r01.zip\""))
    }

    @Test
    fun `a changed compile_sdk moves android_sdk platform to that level's newest stable release`() {
        module.writeText(moduleText(platform = "37.0_r02"))
        val pins = pins()
        repinAndroidSdk(module, pins, license, fakeDownload(mutableListOf()))
        assertTrue(pins.readText().contains("\"file\": \"platform-38.0_r01.zip\""))
        assertTrue(module.readText().contains("android_sdk.platform(version = \"38.0_r01\")"))
    }

    @Test
    fun `the platform's directory comes from its archive`() {
        val pins = pins().also { it.writeText(it.readText().replace("android-38.0", "stale")) }
        repinAndroidSdk(module, pins, license, fakeDownload(mutableListOf()))
        assertTrue(pins.readText().contains("\"dir\": \"android-38.0\""))
    }

    @Test
    fun `an archive already pinned isn't downloaded again`() {
        val pins = pins(platformFile = "platform-38.0_r01.zip", linuxTools = "platform-tools_r38.0.0-linux.zip")
        val asked = mutableListOf<String>()
        repinAndroidSdk(module, pins, license, fakeDownload(asked))

        assertEquals(listOf("repository2-3.xml", "platform-tools_r38.0.0-darwin.zip", "platform-tools_r38.0.0-win.zip"), asked)
        assertTrue(pins.readText().contains("\"sha256\": \"${"a".repeat(64)}\""))
        assertTrue(pins.readText().contains("\"linux\": [\"platform-tools_r38.0.0-linux.zip\", \"${"b".repeat(64)}\"]"))
    }

    @Test
    fun `a download that isn't what the index says is refused`() {
        val pins = pins()
        val before = pins.readText()
        assertFailsWith<IllegalStateException> {
            repinAndroidSdk(module, pins, license, fakeDownload(mutableListOf(), index(platformSha1 = "0".repeat(40))))
        }
        assertEquals(before, pins.readText())
    }

    @Test
    fun `yes records the license text and shows it first`() {
        val text = file("text.txt", "the terms\n")
        val record = File(dir, "home/.android/record.txt")
        val shown = mutableListOf<String>()

        assertTrue(acceptAndroidLicense(text, record, { shown += it }, { " Y " }))

        assertEquals("the terms\n", record.readText())
        assertEquals("the terms\n", shown.first())
    }

    @Test
    fun `no answer or anything but yes records nothing`() {
        val text = file("text.txt", "the terms\n")
        val record = File(dir, "record.txt")
        listOf(null, "", "n", "maybe").forEach { answer ->
            assertFalse(acceptAndroidLicense(text, record, {}, { answer }))
        }
        assertFalse(record.exists())
    }
}
