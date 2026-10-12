import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

/**
 * Compiles the SQLite amalgamation into a static `libsqlite3.a` for one Kotlin/Native target, with the
 * clang and sysroot of the Kotlin/Native distribution itself (`run_konan clang`). The result is embedded in
 * a cinterop klib (`staticLibraries`), so every native binary links it.
 *
 * Why a real archive: C compiled into a cinterop klib's own bitcode (the def file's `---` block) is
 * internalized at link time, so SQLiter's prebuilt bindings could not resolve `sqlite3_*` against it.
 *
 * `run_konan` treats every `-D` argument as a JVM system property, so [defines] are written into a wrapper
 * source file that `#include`s the amalgamation instead of being passed as flags.
 *
 * `run_konan clang` assumes the target's LLVM and sysroot are already in [konanDataDir]; only the compiler and
 * cinterop download them. On a fresh machine (an IDE import, Qodana) nothing has compiled yet, so the task
 * first runs cinterop on an empty def file, which provisions the target's dependencies and is a no-op once
 * they are there.
 */
@CacheableTask
abstract class CompileBundledSqliteTask
    @Inject
    constructor(
        private val execOperations: ExecOperations,
    ) : DefaultTask() {
        /** Holds `sqlite3.c` and `sqlite3.h`. */
        @get:InputDirectory
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val amalgamationDirectory: DirectoryProperty

        /** Kotlin/Native target name as `run_konan` spells it (`linux_x64`, `mingw_x64`, ...). */
        @get:Input
        abstract val konanTarget: Property<String>

        /** The Kotlin version: the toolchain the object code comes from, so it is part of the cache key. */
        @get:Input
        abstract val kotlinVersion: Property<String>

        /** `NAME VALUE` pairs, each written as `#define NAME VALUE`. */
        @get:Input
        abstract val defines: ListProperty<String>

        /** The Kotlin/Native distribution; machine-specific, so not an input (see [kotlinVersion]). */
        @get:Internal
        abstract val konanHome: Property<String>

        /** Where `run_konan` finds (or downloads) each target's sysroot, the same place KGP uses. */
        @get:Internal
        abstract val konanDataDir: Property<String>

        @get:OutputDirectory
        abstract val outputDirectory: DirectoryProperty

        @TaskAction
        fun compile() {
            val home = File(konanHome.get())
            val isWindows = System.getProperty("os.name").startsWith("Windows")
            val runKonan = home.resolve(if (isWindows) "bin/run_konan.bat" else "bin/run_konan")
            check(runKonan.isFile) { "Kotlin/Native distribution not found at $home (no ${runKonan.name})" }

            val emptyDef = temporaryDir.resolve("provision.def")
            emptyDef.writeText("package = provision\n")
            runKonan(
                runKonan,
                "cinterop",
                "-target",
                konanTarget.get(),
                "-def",
                emptyDef.absolutePath,
                "-o",
                temporaryDir.resolve("provision").absolutePath,
            )

            val outputDir = outputDirectory.get().asFile
            outputDir.deleteRecursively()
            outputDir.mkdirs()
            val wrapper = outputDir.resolve("sqlite3-bundled.c")
            wrapper.writeText(
                defines.get().joinToString(separator = "\n", postfix = "\n") { "#define $it" } +
                    "#include \"sqlite3.c\"\n",
            )
            val objectFile = outputDir.resolve("sqlite3.o")
            runKonan(
                runKonan,
                "clang",
                "clang",
                konanTarget.get(),
                "-c",
                "-O2",
                "-I${amalgamationDirectory.get().asFile.absolutePath}",
                wrapper.absolutePath,
                "-o",
                objectFile.absolutePath,
            )
            runKonan(runKonan, "llvm", "llvm-ar", "rcs", outputDir.resolve("libsqlite3.a").absolutePath, objectFile.absolutePath)
            objectFile.delete()
            wrapper.delete()
        }

        private fun runKonan(
            runKonan: File,
            vararg args: String,
        ) {
            execOperations.exec {
                executable = runKonan.absolutePath
                args(*args)
                environment("KONAN_DATA_DIR", konanDataDir.get())
            }
        }
    }
