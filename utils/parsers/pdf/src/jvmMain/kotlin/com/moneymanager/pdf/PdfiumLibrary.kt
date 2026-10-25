package com.moneymanager.pdf

import java.lang.foreign.Arena
import java.lang.foreign.SymbolLookup

/**
 * The pdfium shared library this build carries as a classpath resource (`pdfium/<library>`, only the target
 * platform's), unpacked by [PdfiumLibraryFile]: a library has to be a file to be loaded.
 */
internal object PdfiumLibrary {
    private const val RESOURCE_DIRECTORY = "pdfium"

    fun load(name: String = libraryName()): SymbolLookup {
        val bytes =
            resource(name)
                ?: throw PdfUnsupportedPlatformException(
                    "This build carries no pdfium library for ${System.getProperty("os.name")} " +
                        System.getProperty("os.arch"),
                )
        val file = PdfiumLibraryFile.unpack(name, bytes, resource("VERSION")?.decodeToString())
        return SymbolLookup.libraryLookup(file, Arena.global())
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
}
