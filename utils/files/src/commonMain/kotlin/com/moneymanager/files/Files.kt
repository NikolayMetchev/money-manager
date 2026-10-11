package com.moneymanager.files

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.random.Random

/** The user's home directory, or null when the platform doesn't report one. */
expect fun userHomeDirectory(): String?

/** The file's last-modified time in epoch milliseconds. kotlinx-io's metadata doesn't carry it. */
expect fun lastModifiedEpochMs(path: Path): Long

/** Expands a leading `~` to the user's home, so a configured path stays portable between machines. */
fun expandTilde(path: String): String {
    val home = userHomeDirectory()
    return if (home != null && (path == "~" || path.startsWith("~/"))) {
        home + path.removePrefix("~")
    } else {
        path
    }
}

/** `~/.moneymanager`: the default database, cloud working copies and the refreshed crypto catalog live here. */
fun appDataDirectory(): Path = Path(checkNotNull(userHomeDirectory()) { "No home directory" }, ".moneymanager")

/**
 * [path] made absolute against the working directory. Unlike `SystemFileSystem.resolve`, the path itself
 * needn't exist yet.
 */
fun absolutePath(path: Path): Path = if (path.isAbsolute) path else Path(SystemFileSystem.resolve(Path(".")), path.toString())

fun isRegularFile(path: Path): Boolean = SystemFileSystem.metadataOrNull(path)?.isRegularFile == true

fun isDirectory(path: Path): Boolean = SystemFileSystem.metadataOrNull(path)?.isDirectory == true

fun readBytes(path: Path): ByteArray = SystemFileSystem.source(path).buffered().use { it.readByteArray() }

fun readText(path: Path): String = SystemFileSystem.source(path).buffered().use { it.readString() }

/** Writes [text] to [path], creating its parent directories. */
fun writeText(
    path: Path,
    text: String,
) {
    path.parent?.let { SystemFileSystem.createDirectories(it) }
    SystemFileSystem.sink(path).buffered().use { it.writeString(text) }
}

/**
 * Replaces [path] with [bytes], creating its parent directories. Writes beside the target and renames over
 * it, so a crash mid-write leaves the old file intact.
 */
fun writeAtomically(
    path: Path,
    bytes: ByteArray,
) {
    val target = absolutePath(path)
    val parent = checkNotNull(target.parent) { "No parent directory: $target" }
    SystemFileSystem.createDirectories(parent)
    val temp = Path(parent, "${target.name}.${Random.nextLong().toULong().toString(TEMP_SUFFIX_RADIX)}.tmp")
    try {
        SystemFileSystem.sink(temp).buffered().use { it.write(bytes) }
        SystemFileSystem.atomicMove(temp, target)
    } finally {
        SystemFileSystem.delete(temp, mustExist = false)
    }
}

private const val TEMP_SUFFIX_RADIX = 36
