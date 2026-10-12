import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

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
