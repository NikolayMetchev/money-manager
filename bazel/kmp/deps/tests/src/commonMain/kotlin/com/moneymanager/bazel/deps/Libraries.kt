package com.moneymanager.bazel.deps

import app.cash.sqldelight.db.QueryResult
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.LocalDate
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.builtins.serializer

/** Touches every library the KMP resolver pins (#1000), so each platform's variant is compiled against. */
suspend fun describeLibraries(): List<String> {
    val buffer = Buffer().apply { writeString("io") }
    return listOf(
        flowOf("coroutines").toList().single(),
        LocalDate(2026, 10, 17).toString(),
        buffer.readString(),
        String.serializer().descriptor.serialName,
        (BigInteger.parseString("12345678901234567890") * BigInteger.TWO).toString(),
        QueryResult.Value("sqldelight").value,
    )
}
