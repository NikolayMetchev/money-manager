package com.moneymanager.archive

/**
 * DEFLATE compression primitives. Implemented per platform: JVM and Android share a `java.util.zip`
 * implementation; future targets (iOS/Web) supply their own actual. Kept separate from [ArchiveCodec]
 * so the archive format and crypto stay fully in commonMain.
 */
internal expect fun deflate(data: ByteArray): ByteArray

internal expect fun inflate(data: ByteArray): ByteArray
