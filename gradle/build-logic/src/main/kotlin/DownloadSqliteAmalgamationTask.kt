import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Downloads the SQLite amalgamation zip, refuses it unless its SHA-256 matches the pinned one, and extracts
 * `sqlite3.c` and `sqlite3.h` (flattened) into [outputDirectory].
 *
 * The native CLI links this SQLite statically instead of the host's, so every target (including the
 * cross-linked linuxArm64 and mingw, which have no system SQLite in the Kotlin/Native sysroot) runs the same
 * version. Cacheable, so CI only hits sqlite.org when the pin changes.
 */
@CacheableTask
abstract class DownloadSqliteAmalgamationTask : DefaultTask() {
    @get:Input
    abstract val url: Property<String>

    @get:Input
    abstract val sha256: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun download() {
        val bytes = URI(url.get()).toURL().openStream().use { it.readBytes() }
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(actual.equals(sha256.get(), ignoreCase = true)) {
            "SQLite amalgamation checksum mismatch for ${url.get()}: expected ${sha256.get()}, got $actual"
        }
        val outputDir = outputDirectory.get().asFile
        outputDir.deleteRecursively()
        outputDir.mkdirs()
        val wanted = setOf("sqlite3.c", "sqlite3.h")
        val extracted = mutableSetOf<String>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                val name = entry.name.substringAfterLast('/')
                if (!entry.isDirectory && name in wanted) {
                    outputDir.resolve(name).outputStream().use { zip.copyTo(it) }
                    extracted += name
                }
            }
        }
        check(extracted == wanted) { "SQLite amalgamation is missing ${wanted - extracted}" }
    }
}
