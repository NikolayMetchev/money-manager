package com.moneymanager.archive

/**
 * zlib-wrapped (RFC 1950) DEFLATE compression primitives. JVM and Android share a `java.util.zip`
 * implementation, native targets use the platform zlib and the browser its `CompressionStream`. All write the same
 * format, so an archive packed on one platform unpacks on any other. Suspending, as the browser's streams are. Kept separate from [ArchiveCodec] so the archive format
 * and crypto stay fully in commonMain.
 */
internal expect suspend fun deflate(data: ByteArray): ByteArray

internal expect suspend fun inflate(data: ByteArray): ByteArray
