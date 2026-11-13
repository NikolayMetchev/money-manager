package com.moneymanager.ofx

/**
 * [bytes] as text. OFX 1.x declares its character set in its header (`CHARSET:1252` is usual, Windows-1252) and
 * OFX 2.x in its XML declaration; a file that declares a single-byte set, or isn't valid UTF-8, is read as
 * Windows-1252. Anything else is UTF-8, without its byte-order mark.
 */
fun decodeOfxBytes(bytes: ByteArray): String {
    val header = bytes.copyOf(minOf(bytes.size, HEADER_SCAN_BYTES)).decodeToString().uppercase()
    if (SINGLE_BYTE_DECLARATION.containsMatchIn(header)) return decodeWindows1252(bytes)
    val utf8 =
        try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            return decodeWindows1252(bytes)
        }
    return utf8.removePrefix(BYTE_ORDER_MARK)
}

private const val HEADER_SCAN_BYTES = 512

private const val BYTE_ORDER_MARK = "\uFEFF"

private val SINGLE_BYTE_DECLARATION =
    Regex("""CHARSET:\s*(1252|WINDOWS-1252|ISO-8859-1|8859-1|LATIN-?1)\b|ENCODING="(WINDOWS-1252|ISO-8859-1|LATIN-?1)"""")

/** Windows-1252's characters for bytes 0x80–0x9F (U+FFFD where it defines none); every other byte is Latin-1. */
private val WINDOWS_1252_HIGH =
    charArrayOf(
        '€',
        '�',
        '‚',
        'ƒ',
        '„',
        '…',
        '†',
        '‡',
        'ˆ',
        '‰',
        'Š',
        '‹',
        'Œ',
        '�',
        'Ž',
        '�',
        '�',
        '‘',
        '’',
        '“',
        '”',
        '•',
        '–',
        '—',
        '˜',
        '™',
        'š',
        '›',
        'œ',
        '�',
        'ž',
        'Ÿ',
    )

private const val WINDOWS_1252_HIGH_START = 0x80
private const val WINDOWS_1252_HIGH_END = 0x9F
private const val BYTE_MASK = 0xFF

private fun decodeWindows1252(bytes: ByteArray): String =
    CharArray(bytes.size) { index ->
        val byte = bytes[index].toInt() and BYTE_MASK
        if (byte in WINDOWS_1252_HIGH_START..WINDOWS_1252_HIGH_END) {
            WINDOWS_1252_HIGH[byte - WINDOWS_1252_HIGH_START]
        } else {
            byte.toChar()
        }
    }.concatToString()
