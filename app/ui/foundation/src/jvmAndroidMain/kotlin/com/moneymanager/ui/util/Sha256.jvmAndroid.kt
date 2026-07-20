package com.moneymanager.ui.util

import java.security.MessageDigest

actual fun sha256Hex(input: String): String = sha256Hex(input.toByteArray(Charsets.UTF_8))

actual fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(bytes).toHexString()
}

private fun ByteArray.toHexString(): String = joinToString("") { byte -> "%02x".format(byte) }
