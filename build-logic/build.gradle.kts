import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(libs.versions.jvm.toolchain.get().toInt()))
    }
    targetCompatibility = JavaVersion.toVersion(libs.versions.jvm.target.get().toInt())
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.jvm.target.get()))
    }
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.sqldelight.gradle.plugin)
    implementation(libs.mappie.gradle.plugin)
    implementation(libs.metro.gradle.plugin)
    implementation(libs.compose.gradle.plugin)
    implementation(libs.android.gradle.plugin)
}
