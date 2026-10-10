package com.moneymanager.csvimporter

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256

/** Hex-encoded SHA-256 of [input], used as the file checksum for change detection / dedup. */
fun sha256Hex(input: String): String = sha256Hex(input.encodeToByteArray())

/** Hex-encoded SHA-256 of raw [bytes] (binary files, e.g. `.xlsx`, that can't be checksummed as text). */
fun sha256Hex(bytes: ByteArray): String =
    CryptographyProvider.Default
        .get(SHA256)
        .hasher()
        .hashBlocking(bytes)
        .toHexString()
