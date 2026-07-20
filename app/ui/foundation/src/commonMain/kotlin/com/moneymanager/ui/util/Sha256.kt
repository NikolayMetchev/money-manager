package com.moneymanager.ui.util

expect fun sha256Hex(input: String): String

/** Hex-encoded SHA-256 of raw [bytes] (binary files, e.g. `.xlsx`, that can't be checksummed as text). */
expect fun sha256Hex(bytes: ByteArray): String
