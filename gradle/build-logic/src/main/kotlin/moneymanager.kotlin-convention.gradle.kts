import kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.squareup.sort-dependencies")
    id("dev.detekt")
    id("org.jetbrains.kotlinx.kover")
    id("org.jlleitschuh.gradle.ktlint")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

// Configure JVM target for all Kotlin compilation tasks
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.findVersion("jvm-target").get().toString()))
    }
}

// Configure Java compilation target
tasks.withType<JavaCompile>().configureEach {
    targetCompatibility = libs.findVersion("jvm-target").get().toString()
}

detekt {
    config.setFrom(rootProject.file("detekt.yml"))
    buildUponDefaultConfig = true
    allRules = false
}

ktlint {
    android.set(false)
    // Allow CI to ignore failures when auto-formatting via system property
    // Usage: ./gradlew lintFormat -Dktlint.ignoreFailures=true
    ignoreFailures.set(System.getProperty("ktlint.ignoreFailures", "false").toBoolean())

    // Exclude generated files from linting
    filter {
        exclude { entry ->
            entry.file.toString().contains("generated")
        }
    }
}

configure<KoverProjectExtension> {
    reports {
        filters {
            excludes {
                // Exclude generated code
                classes("*_Factory", "*_Factory\$*")
                classes("*_Impl", "*_Impl\$*")
                classes("*MapperImpl")
                // Exclude Metro DI generated code
                classes("*Component\$*")
                classes("*Module\$*")
                // Exclude SQLDelight generated code
                classes("com.moneymanager.database.sql.*")
            }
        }
    }
}