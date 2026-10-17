import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest
import org.jetbrains.kotlin.gradle.tasks.CInteropProcess
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeCompile
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink

/**
 * Adds the Kotlin/Native targets the `mm` CLI ships for (#917).
 *
 * While `commonMain` is shared only by JVM and Android, KGP compiles it against the JDK, so JVM-only code
 * leaks into it silently. A native target is what makes such a leak fail the build. Apply this only to the
 * CLI's dependency closure, never to the `app/ui` modules.
 *
 * The default hierarchy template derives `nativeMain`/`linuxMain`/`appleMain`/`mingwMain` from these targets
 * (`jvm-android-shared-convention` only extends that template), so no source set is wired by hand. Apple
 * targets compile klibs on any host but only link and test on macOS.
 */
plugins {
    alias(conventions.plugins.moneymanager.kotlin.multiplatform.convention)
}

// Klibs are named after the archive (default: the project name), and several modules share one (`core`,
// `read`, `write`, `csv`). Two same-named klibs on one compile path make the klib loader warn, which
// -Werror turns into a failure, so name each after its full project path.
configure<BasePluginExtension> {
    archivesName.set(project.path.removePrefix(":").replace(':', '-'))
}

configure<KotlinMultiplatformExtension> {
    linuxX64()
    linuxArm64()
    macosArm64()
    mingwX64()
}

// -PskipNative=true keeps the targets declared, so build scripts and the source-set hierarchy resolve as
// always, but runs no native work: no compile, cinterop, link or test, and no Kotlin/Native toolchain download.
// CI's Build and Test job passes it, since linking every native test binary beside the JVM build ran it out of
// memory; each native target has its own job.
if (providers.gradleProperty("skipNative").map(String::toBoolean).getOrElse(false)) {
    val nativeToolchainTask = Regex("commonize.*|.*KotlinNativeDistribution")
    tasks.configureEach {
        val native =
            this is KotlinNativeCompile ||
                this is KotlinNativeLink ||
                this is KotlinNativeTest ||
                this is CInteropProcess ||
                this is CompileBundledSqliteTask ||
                this is DownloadSqliteAmalgamationTask ||
                nativeToolchainTask.matches(name)
        if (native) enabled = false
    }
}

// Metro's and SQLDelight's runtimes both publish a klib uniquely named `runtime`, so compiling a native
// intermediate source set's metadata (nativeMain, linuxMain, appleMain) that sees both makes the klib loader
// warn, and Kotlin can't silence that one warning. Those compilations drop -Werror: every file in them is
// also compiled for each native target, where -Werror still applies, so no source warning goes unchecked.
val nativeIntermediateMetadata = Regex("compile(Native|Linux|Apple|Mingw)MainKotlinMetadata")
tasks.withType<KotlinCompilationTask<*>>().configureEach {
    if (nativeIntermediateMetadata.matches(name)) {
        compilerOptions.allWarningsAsErrors.set(false)
    }
}
