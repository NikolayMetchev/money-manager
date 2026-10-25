package com.moneymanager.pdf

import co.touchlab.kermit.Logger
import com.moneymanager.files.absolutePath
import com.moneymanager.files.appDataDirectory
import com.moneymanager.files.writeAtomically
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import java.lang.foreign.Arena
import java.lang.foreign.SymbolLookup
import kotlin.random.Random

/**
 * The pdfium shared library this build carries as a classpath resource (`pdfium/<library>`, only the target
 * platform's), unpacked once under `~/.moneymanager/native/pdfium-<build>/`: a library has to be a file to be loaded.
 */
internal object PdfiumLibrary {
    private const val RESOURCE_DIRECTORY = "pdfium"
    private const val RADIX = 36
    private val logger = Logger.withTag("PdfiumLibrary")

    fun load(): SymbolLookup {
        val name = libraryName()
        val bytes =
            resource(name)
                ?: throw PdfUnsupportedPlatformException(
                    "This build carries no pdfium library for ${System.getProperty("os.name")} " +
                        System.getProperty("os.arch"),
                )
        val build = resource("VERSION")?.decodeToString()?.let(::buildNumber) ?: bytes.contentHashCode().toUInt().toString(16)
        val file =
            try {
                Path(appDataDirectory(), "native", "pdfium-$build", name).also { file ->
                    if (SystemFileSystem.metadataOrNull(file)?.size != bytes.size.toLong()) writeAtomically(file, bytes)
                }
            } catch (e: IOException) {
                // A read-only home (a sandboxed test): a directory of this process's own, never a shared name
                // another user could have put a library at.
                logger.w(e) { "Can't unpack pdfium under the home directory; using a temporary directory" }
                Path(SystemTemporaryDirectory, "moneymanager-pdfium-${Random.nextLong().toULong().toString(RADIX)}")
                    .also { SystemFileSystem.createDirectories(it, mustCreate = true) }
                    .let { Path(it, name) }
                    .also { writeAtomically(it, bytes) }
            }
        return SymbolLookup.libraryLookup(absolutePath(file).toString(), Arena.global())
    }

    private fun libraryName(): String {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.startsWith("windows") -> "pdfium.dll"
            os.startsWith("mac") -> "libpdfium.dylib"
            else -> "libpdfium.so"
        }
    }

    private fun resource(name: String): ByteArray? =
        PdfiumLibrary::class.java.classLoader
            .getResourceAsStream("$RESOURCE_DIRECTORY/$name")
            ?.use { it.readBytes() }

    // VERSION holds `MAJOR=…`, `MINOR=…`, `BUILD=…`, `PATCH=…` lines.
    private fun buildNumber(version: String): String? =
        version
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("BUILD=") }
            ?.removePrefix("BUILD=")
}
