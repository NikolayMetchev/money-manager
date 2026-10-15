import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.mpp.DisableCacheInKotlinVersion
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeCacheApi
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType

plugins {
    id("moneymanager.kotlin-multiplatform-convention")
    id("moneymanager.native-convention")
    alias(libs.plugins.kotlin.serialization)
}

// `mm`, the command-line client (#917): native executables for every native target, plus a JVM build that
// the desktop installer runs on its bundled runtime. It opens databases through app/session like the desktop
// app, so the two front ends share every read and write.
kotlin {
    targets.withType<KotlinNativeTarget>().configureEach {
        // Release only: the installer and the release artifacts ship it, and a debug link of the whole app
        // costs as much again. For quick local runs use `runJvm`.
        binaries.executable(listOf(NativeBuildType.RELEASE)) {
            baseName = "mm"
            entryPoint = "com.moneymanager.cli.main"
        }
        // Clikt 5.1.0's clikt and clikt-mordant klibs both define a private `Context.selfAndAncestors()`. The
        // compiler caches give each klib its own object file, so a cached link (debug, tests) fails on the
        // duplicate symbol. Release links don't use the caches. Pinned to this Kotlin version so a bump re-checks it.
        binaries.configureEach {
            @OptIn(KotlinNativeCacheApi::class)
            disableNativeCache(
                DisableCacheInKotlinVersion.`2_4_21`,
                "clikt and clikt-mordant both define a private Context.selfAndAncestors(), a duplicate symbol in cached links",
            )
        }
    }

    jvm {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        binaries {
            executable {
                mainClass.set("com.moneymanager.cli.MainKt")
                applicationName = "mm-jvm"
            }
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(projects.app.cryptodata)
                implementation(projects.app.di.core)
                implementation(projects.app.di.params)
                implementation(projects.app.query)
                implementation(projects.app.remotestorage.googledrive)
                implementation(projects.app.session)
                implementation(projects.app.tasks)
                implementation(projects.app.terminal)
                implementation(projects.utils.archive)
                implementation(projects.utils.files)
                implementation(libs.clikt)
                implementation(libs.clikt.core)
                implementation(libs.kermit)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.datetime)
                implementation(libs.kotlinx.io.core)
                implementation(libs.kotlinx.serialization.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.mordant.core)
            }
        }

        // Common code reaches these through :app:session's api, which is all a native compile needs. buildHealth
        // only reads JVM bytecode, and wants the ones it sees used declared here.
        jvmMain {
            dependencies {
                implementation(projects.app.db.core)
                implementation(projects.app.db.di)
                implementation(projects.app.db.write)
                implementation(projects.app.importengineapi)
                implementation(projects.app.model.core)
                implementation(projects.app.model.repository.read)
                implementation(projects.app.remotestorage.sync)
                implementation(projects.utils.credentialvault)
                implementation(projects.utils.localsettings)
                implementation(libs.clikt.core)
                implementation(libs.kermit.core)
                implementation(libs.kotlinx.serialization.core)
                implementation(libs.mosaic.terminal)
            }
        }

        commonTest {
            dependencies {
                implementation(projects.test.app.db)
                implementation(projects.test.utils.credentialvault)
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        jvmTest {
            dependencies {
                implementation(projects.app.db.core)
                implementation(projects.app.db.di)
                implementation(projects.app.db.write)
                implementation(projects.app.model.core)
                implementation(projects.app.model.importdirectory)
                implementation(projects.app.model.repository.write)
                implementation(projects.app.remotestorage.sync)
                implementation(projects.utils.credentialvault)
                implementation(projects.utils.localsettings)
                implementation(libs.clikt.core)
                implementation(libs.kotlinx.serialization.core)
            }
        }
    }
}

// From 1.9 JetBrains' compose runtime-desktop jar (pulled in by Mosaic) is an empty pointer to androidx's, and the two
// share a file name, so `mm-jvm`'s lib/ would hold one or the other at random. app/terminal declares androidx's directly.
configurations.named("jvmRuntimeClasspath") {
    exclude(group = "org.jetbrains.compose.runtime", module = "runtime-desktop")
}

// `build` links the host's `mm` only (so a link failure still fails it). The other targets link on demand: the
// release workflow cross-links linuxArm64, and macOS and Windows link on their own runners.
// The host's release `mm` is also what the desktop installer bundles beside the app (app/main/jvm consumes it):
// the installers are built on each OS, so the host's own target is the one they ship.
val hostNativeTarget: String? =
    run {
        val os = System.getProperty("os.name").lowercase()
        val arm = System.getProperty("os.arch").lowercase() in setOf("aarch64", "arm64")
        when {
            os.startsWith("linux") -> if (arm) "linuxArm64" else "linuxX64"
            os.startsWith("mac") -> if (arm) "macosArm64" else null
            os.startsWith("windows") -> if (arm) null else "mingwX64"
            else -> null
        }
    }

val mmExecutable =
    configurations.consumable("mmExecutable") {
        attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named("moneymanager-mm-executable"))
    }

val crossLinks =
    kotlin.targets
        .withType<KotlinNativeTarget>()
        .filter { it.name != hostNativeTarget }
        .map { it.binaries.getExecutable("RELEASE").linkTaskName }
        .toSet()
tasks.named("assemble") {
    setDependsOn(dependsOn.filterNot { (it as? Named)?.name in crossLinks })
}

if (hostNativeTarget != null) {
    val link =
        kotlin.targets
            .getByName<KotlinNativeTarget>(hostNativeTarget)
            .binaries
            .getExecutable("RELEASE")
            .linkTaskProvider
    artifacts.add(mmExecutable.name, link.flatMap { it.outputFile }) { builtBy(link) }
}
