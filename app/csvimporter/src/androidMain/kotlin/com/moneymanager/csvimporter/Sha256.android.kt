package com.moneymanager.csvimporter

import java.security.MessageDigest

internal actual fun sha256Hex(input: String): String = sha256Hex(input.toByteArray(Charsets.UTF_8))

internal actual fun sha256Hex(bytes: ByteArray): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }
