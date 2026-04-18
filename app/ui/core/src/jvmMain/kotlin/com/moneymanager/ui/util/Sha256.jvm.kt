package com.moneymanager.ui.util

import java.security.MessageDigest

actual fun sha256Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(input.toByteArray(Charsets.UTF_8)).toHexString()
}

private fun ByteArray.toHexString(): String = joinToString("") { byte -> "%02x".format(byte) }
