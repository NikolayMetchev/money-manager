package com.moneymanager.archive

/**
 * zlib-wrapped (RFC 1950) DEFLATE compression primitives. JVM and Android share a `java.util.zip`
 * implementation and native targets use the platform zlib. Both write the same format, so an archive
 * packed on one platform unpacks on any other. Kept separate from [ArchiveCodec] so the archive format
 * and crypto stay fully in commonMain.
 */
internal expect fun deflate(data: ByteArray): ByteArray

internal expect fun inflate(data: ByteArray): ByteArray
