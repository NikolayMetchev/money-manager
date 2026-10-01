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

val versionFile = rootDir.resolve("VERSION")

dependencies {
    api(libs.androidx.compose.runtime.desktop)
    api(libs.compose.ui.desktop)

    implementation(projects.app.cryptodata)
    implementation(projects.app.db.core)
    implementation(projects.app.db.di)
    implementation(projects.app.db.write)
    implementation(projects.app.di.core)
    implementation(projects.app.di.params)
    implementation(projects.app.importengineapi)
    implementation(projects.app.importfilesource.core)
    implementation(projects.app.importfilesource.di)
    implementation(projects.app.model.core)
    implementation(projects.app.remotestorage.sync)
    implementation(projects.app.strategycatalog)
    implementation(projects.app.ui.components)
    implementation(projects.app.ui.core)
    implementation(projects.app.ui.foundation)
    implementation(projects.utils.credentialvault)
    implementation(projects.utils.localsettings)
    implementation(libs.compose.ui.graphics.desktop)
    implementation(libs.compose.ui.unit.desktop)
    implementation(libs.diamondedge.logging)
    implementation(libs.kmlogging)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)

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

// Copy VERSION file to resources
tasks.named<ProcessResources>("processResources") {
    from(versionFile) {
        into(".")
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
            // Include java.sql module in the custom runtime
            modules("java.sql")
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
        dependsOn("proguardReleaseJars")
        val proguardJarsDir = layout.buildDirectory.dir("compose/tmp/main-release/proguard")
        val reportFile = layout.buildDirectory.file("reports/proguard/service-providers.txt")
        inputs.dir(proguardJarsDir)
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

// Every release installer (packageRelease{Deb,Dmg,Msi}) is built from this distributable
tasks.matching { it.name == "createReleaseDistributable" }.configureEach {
    dependsOn(verifyProguardServiceProviders)
}

// Handle duplicate JARs in distribution tasks
tasks.withType<Tar>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.withType<Zip>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
