package com.moneymanager.csvimporter

/** Hex-encoded SHA-256 of [input], used as the file checksum for change detection / dedup. */
internal expect fun sha256Hex(input: String): String

/** Hex-encoded SHA-256 of raw [bytes] (binary files, e.g. `.xlsx`, that can't be checksummed as text). */
internal expect fun sha256Hex(bytes: ByteArray): String
