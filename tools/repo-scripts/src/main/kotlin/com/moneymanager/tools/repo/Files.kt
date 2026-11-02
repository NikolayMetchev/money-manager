package com.moneymanager.tools.repo

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration

/** `bazel run` starts in the runfiles, so a relative path is the caller's: Bazel names that directory for us. */
fun workspaceFile(path: String): File {
    val file = File(path)
    if (file.isAbsolute) return file
    return File(System.getenv("BUILD_WORKSPACE_DIRECTORY") ?: ".", path)
}

fun fail(message: String): Nothing {
    System.err.println(message)
    kotlin.system.exitProcess(1)
}

/** Fetches a URL, following redirects (GitHub's release downloads redirect). */
fun httpGet(url: String): ByteArray {
    val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
    val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(120)).build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
    check(response.statusCode() == 200) { "GET $url answered ${response.statusCode()}" }
    return response.body()
}

fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** Rewrites [file] when [updated] differs, and says whether it did. */
fun writeIfChanged(
    file: File,
    original: String,
    updated: String,
): Boolean {
    if (updated == original) return false
    file.writeText(updated)
    return true
}

/** [Regex.replace] that also counts the matches, as the repin tools insist on finding the entries they pin. */
fun Regex.replaceCounting(
    input: String,
    transform: (MatchResult) -> String,
): Pair<String, Int> {
    var count = 0
    val result =
        replace(input) {
            count++
            transform(it)
        }
    return result to count
}

val MULTILINE = setOf(RegexOption.MULTILINE)
