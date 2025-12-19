import dev.detekt.gradle.Detekt
import kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.squareup.sort-dependencies")
    id("dev.detekt")
    id("org.jetbrains.kotlinx.kover")
    id("org.jlleitschuh.gradle.ktlint")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

// Set group based on project path
// Example: :app:model:core -> app.model.core
group = project.path.removePrefix(":").replace(":", ".")

tasks {
    val jvmTargetVersion = libs.findVersion("jvm-target").get().toString()

    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.fromTarget(jvmTargetVersion))
        }
    }

    withType<Detekt>().configureEach {
        // Detekt only supports up to JVM target 24
        jvmTarget = minOf(jvmTargetVersion.toInt(), 24).toString()
    }

    withType<JavaCompile>().configureEach {
        targetCompatibility = jvmTargetVersion
    }
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