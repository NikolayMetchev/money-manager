package com.moneymanager.remotestorage.sync

private const val FNV_OFFSET_BASIS: Long = -3750763034362895579L
private const val FNV_PRIME: Long = 1099511628211L
private const val HEX_RADIX: Int = 16

/** FNV-1a 64-bit hex of [bytes]: a compact, stable fingerprint (well within the JVM prefs 80-char key limit). */
internal fun stableHash(bytes: ByteArray): String {
    var hash = FNV_OFFSET_BASIS
    for (byte in bytes) {
        hash = hash xor (byte.toLong() and 0xff)
        hash *= FNV_PRIME
    }
    return hash.toULong().toString(HEX_RADIX)
}

internal fun stableHash(text: String): String = stableHash(text.encodeToByteArray())

// XML 1.0 forbids control characters below 0x20 other than tab/newline/carriage-return. On JVM,
// LocalSettings is java.util.prefs backed by prefs.xml, where one such character makes the WHOLE node fail
// to flush, silently losing every setting written that session.
internal fun String.isXmlSafe(): Boolean = none { it.code < ' '.code && it != '\t' && it != '\n' && it != '\r' }
