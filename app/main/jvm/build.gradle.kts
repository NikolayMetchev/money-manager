import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
import org.jetbrains.compose.desktop.application.tasks.AbstractProguardTask
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

plugins {
    id("moneymanager.kotlin-convention")
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.jvm)
}

// On Windows, Compose sets up its WiX download on the root project, which isolated projects rejects
// (https://youtrack.jetbrains.com/issue/CMP-8375). Selecting any task by name configures this project too, so
// that would break every Windows build. Only an MSI/EXE package needs WiX, and those builds pass
// -Dorg.gradle.isolated-projects=false. Compose reads this project's extra property before Gradle properties.
val packagesWindowsInstaller =
    gradle.startParameter.taskNames.any { task ->
        val name = task.substringAfterLast(':')
        name.endsWith("Msi") || name.endsWith("Exe") || name.endsWith("DistributionForCurrentOS")
    }
extra["compose.desktop.application.downloadWix"] = packagesWindowsInstaller.toString()

dependencies {
    api(libs.androidx.compose.runtime.desktop)
    api(libs.compose.ui.desktop)

    implementation(projects.app.cryptodata)
    implementation(projects.app.db.core)
    implementation(projects.app.db.write)
    implementation(projects.app.di.core)
    implementation(projects.app.di.params)
    implementation(projects.app.importfilesource.core)
    implementation(projects.app.importfilesource.di)
    implementation(projects.app.model.core)
    implementation(projects.app.remotestorage.googledrive)
    implementation(projects.app.remotestorage.sync)
    implementation(projects.app.session)
    implementation(projects.app.strategycatalog)
    implementation(projects.app.tasks)
    implementation(projects.app.ui.components)
    implementation(projects.app.ui.core)
    implementation(projects.app.ui.foundation)
    implementation(projects.utils.credentialvault)
    implementation(projects.utils.localsettings)
    implementation(libs.compose.ui.graphics.desktop)
    implementation(libs.compose.ui.unit.desktop)
    implementation(libs.kermit)
    implementation(libs.kermit.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.slf4j.api)

    // The JVM build of mm rides in the installer's lib/app, started by the mm-jvm launcher.
    runtimeOnly(projects.app.main.cli)
    runtimeOnly(compose.desktop.currentOs)
    runtimeOnly(libs.log4j.core)
    runtimeOnly(libs.log4j.slf4j2.impl)
}

// sqlite-jdbc bundles its JNI library for ~24 OS/arch combinations (~11 MB compressed), and every
// installer would ship all of them. jpackage bundles the host's JRE, so each installer only ever runs
// on the OS/arch it was built on: keep that one native library and drop the rest.
abstract class StripForeignSqliteNatives : TransformAction<StripForeignSqliteNatives.Parameters> {
    interface Parameters : TransformParameters {
        /** sqlite-jdbc's folder for the host, e.g. `Linux/x86_64` (see `org.sqlite.util.OSInfo`). */
        @get:Input
        val hostNativeFolder: Property<String>
    }

    @get:InputArtifact
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val inputArtifact: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        val input = inputArtifact.get().asFile
        if (!input.name.startsWith("sqlite-jdbc-")) {
            outputs.file(input)
            return
        }
        val nativeRoot = "org/sqlite/native/"
        val keep = "$nativeRoot${parameters.hostNativeFolder.get()}/"
        var keptNative = false
        ZipInputStream(input.inputStream().buffered()).use { zipIn ->
            ZipOutputStream(outputs.file(input.name).outputStream().buffered()).use { zipOut ->
                generateSequence { zipIn.nextEntry }.forEach { entry ->
                    val name = entry.name
                    val isForeignNative =
                        name.startsWith(nativeRoot) && !name.startsWith(keep) && !keep.startsWith(name)
                    if (!isForeignNative) {
                        keptNative = keptNative || (name.startsWith(keep) && !entry.isDirectory)
                        zipOut.putNextEntry(ZipEntry(name).apply { time = entry.time })
                        zipIn.copyTo(zipOut)
                        zipOut.closeEntry()
                    }
                }
            }
        }
        check(keptNative) {
            "${input.name} has no native library under $keep; the app couldn't open a database on this host"
        }
    }
}

// Mirrors sqlite-jdbc's OSInfo naming for the platforms we build installers on
fun sqliteNativeFolder(): String {
    val osName = System.getProperty("os.name")
    val os =
        when {
            osName.startsWith("Windows") -> "Windows"
            osName.startsWith("Mac") || osName.startsWith("Darwin") -> "Mac"
            osName.startsWith("Linux") -> "Linux"
            else -> osName.replace(" ", "")
        }
    val arch =
        when (val osArch = System.getProperty("os.arch").lowercase()) {
            "amd64", "x86_64", "x64" -> "x86_64"
            "arm64", "aarch64" -> "aarch64"
            "x86", "i386", "i486", "i586", "i686" -> "x86"
            else -> osArch
        }
    return "$os/$arch"
}

val sqliteNativesStripped = Attribute.of("moneymanager.sqliteNativesStripped", Boolean::class.javaObjectType)

dependencies {
    attributesSchema { attribute(sqliteNativesStripped) }
    artifactTypes.getByName("jar").attributes.attribute(sqliteNativesStripped, false)
    registerTransform(StripForeignSqliteNatives::class) {
        from.attribute(sqliteNativesStripped, false)
        to.attribute(sqliteNativesStripped, true)
        parameters.hostNativeFolder.set(sqliteNativeFolder())
    }
}

// runtimeClasspath feeds `run`, the distributables and ProGuard, so all of them get the stripped jar
configurations.named("runtimeClasspath") {
    attributes.attribute(sqliteNativesStripped, true)
}

// The installer ships the CLI beside the app (#936): the `mm-jvm` launcher (jpackage's --add-launcher, so it runs
// on the bundled runtime) always, and the host's native `mm` with -PbundleNativeCli=true (the release workflow
// passes it) through --app-content. The native link is opt-in because it takes minutes.
val mmExecutable =
    configurations.dependencyScope("mmExecutable") {
        dependencies.add(project.dependencies.create(projects.app.main.cli))
    }
val mmExecutableFiles =
    configurations.resolvable("mmExecutableFiles") {
        extendsFrom(mmExecutable.get())
        attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named("moneymanager-mm-executable"))
    }
val bundleNativeCli = providers.gradleProperty("bundleNativeCli").map(String::toBoolean).getOrElse(false)

// jpackage copies an --app-content directory into the app under its own name, keeping file permissions.
val assembleNativeCli =
    tasks.register<Sync>("assembleNativeCli") {
        description = "Collects the host's native mm for the installer."
        into(layout.buildDirectory.dir("native-cli/cli"))
        from(mmExecutableFiles) {
            rename { if (it.endsWith(".exe")) "mm.exe" else "mm" }
        }
    }

val mmJvmLauncher = layout.projectDirectory.file("mm-jvm.properties")
tasks.withType<AbstractJPackageTask>().configureEach {
    inputs.file(mmJvmLauncher).withPropertyName("mmJvmLauncher").withPathSensitivity(PathSensitivity.NONE)
    freeArgs.addAll("--add-launcher", "mm-jvm=${mmJvmLauncher.asFile.absolutePath}")
    if (bundleNativeCli) {
        val nativeCli = assembleNativeCli.map { it.destinationDir }
        inputs.dir(nativeCli).withPropertyName("nativeCli").withPathSensitivity(PathSensitivity.RELATIVE)
        freeArgs.addAll(nativeCli.map { listOf("--app-content", it.absolutePath) })
    }
}

compose.desktop {
    application {
        mainClass = "com.moneymanager.MainKt"

        buildTypes.release {
            proguard {
                isEnabled.set(true)
                version.set(
                    libs.versions.proguard
                        .get(),
                )
                configurationFiles.from(project.file("proguard-rules.pro"))
                // -PproguardOptimize=false skips the optimizer, which is most of ProGuard's ~15 minutes.
                // CI's ProGuard check passes it: shrinking alone shows whether the keep rules hold.
                optimize.set(
                    providers
                        .gradleProperty("proguardOptimize")
                        .map(String::toBoolean)
                        .orElse(true),
                )
            }
        }

        // Add required Java modules for the bundled JRE
        jvmArgs +=
            listOf(
                "--add-modules",
                "java.sql",
            )

        // Opt-in CPU profiling: `./gradlew :app:main:jvm:run -Pjfr` records the app JVM
        // with method sampling enabled and dumps to profile.jfr on exit.
        if (providers.gradleProperty("jfr").isPresent) {
            jvmArgs +=
                "-XX:StartFlightRecording=settings=profile," +
                "filename=${rootDir.resolve("profile.jfr")},dumponexit=true"
        }

        nativeDistributions {
            // Include java.sql module in the custom runtime, and jdk.unsupported for mm-jvm's Ctrl-C handler
            // (sun.misc.Signal), which prints the task summary before exiting.
            modules("java.sql", "jdk.unsupported")
            targetFormats(
                // macOS
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg,
                // Windows
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                // Linux
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
            )

            packageName = "MoneyManager"
            packageVersion = project.version.toString()
            description = "Personal Finance Management Application"
            vendor = "MoneyManager"

            windows {
                menuGroup = "MoneyManager"
                perUserInstall = true
                shortcut = true
                menu = true
                dirChooser = true
                console = true // Show console window to see error output
                // IMPORTANT: Keep this UUID constant across versions to allow upgrades/reinstalls
                upgradeUuid = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
            }

            macOS {
                bundleID = "com.moneymanager.app"
            }
        }
    }
}

// ProGuard doesn't follow META-INF/services, so it silently strips classes that are only reached through
// ServiceLoader (the SQLite JDBC driver, the crypto provider, ...) and the release app then fails at
// runtime while the build still passes. Fail the build instead when a declared provider went missing.
val verifyProguardServiceProviders =
    tasks.register("verifyProguardServiceProviders") {
        description = "Checks every META-INF/services provider survived ProGuard shrinking."
        group = "verification"
        // The task's own output directory, so the input carries the dependency on running ProGuard.
        val proguardJarsDir = tasks.named<AbstractProguardTask>("proguardReleaseJars").flatMap { it.destinationDir }
        val reportFile = layout.buildDirectory.file("reports/proguard/service-providers.txt")
        inputs.dir(proguardJarsDir).withPropertyName("proguardJars").withPathSensitivity(PathSensitivity.RELATIVE)
        outputs.file(reportFile)
        doLast {
            val jars =
                proguardJarsDir
                    .get()
                    .asFile
                    .listFiles { file -> file.extension == "jar" }
                    .orEmpty()
            val classes = mutableSetOf<String>()
            val providers = mutableListOf<Pair<String, String>>()
            jars.forEach { jar ->
                ZipFile(jar).use { zip ->
                    zip.entries().asSequence().forEach { entry ->
                        when {
                            entry.name.endsWith(".class") ->
                                classes += entry.name.removeSuffix(".class").replace('/', '.')
                            entry.name.startsWith("META-INF/services/") && !entry.isDirectory ->
                                zip
                                    .getInputStream(entry)
                                    .bufferedReader()
                                    .readLines()
                                    .map { it.substringBefore('#').trim() }
                                    .filter { it.isNotEmpty() }
                                    .forEach { providers += entry.name.removePrefix("META-INF/services/") to it }
                        }
                    }
                }
            }
            // Annotation processors are compile-time only; the app never loads them
            val missing =
                providers.filter { (service, provider) ->
                    service != "javax.annotation.processing.Processor" && provider !in classes
                }
            reportFile.get().asFile.writeText(
                providers.joinToString("\n", postfix = "\n") { (service, provider) ->
                    "${if (provider in classes) "ok" else "MISSING"} $service -> $provider"
                },
            )
            check(missing.isEmpty()) {
                "ProGuard removed ServiceLoader providers; add -keep rules to proguard-rules.pro:\n" +
                    missing.joinToString("\n") { (service, provider) -> "  $service -> $provider" }
            }
        }
    }

// Check the shrunk jars whenever ProGuard produces them, so every release installer
// (packageRelease{Deb,Dmg,Msi}) and runRelease is covered, and a missing provider fails the build.
// matching, not named: the Compose plugin registers the ProGuard task after this script runs.
tasks.matching { it.name == "proguardReleaseJars" }.configureEach {
    finalizedBy(verifyProguardServiceProviders)
}

// Handle duplicate JARs in distribution tasks
tasks.withType<Tar>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.withType<Zip>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
