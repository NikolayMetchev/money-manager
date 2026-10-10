import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

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

configure<KotlinMultiplatformExtension> {
    linuxX64()
    linuxArm64()
    macosArm64()
    mingwX64()
}
