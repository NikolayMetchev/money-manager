plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.sqldelight.gradle.plugin)
    implementation(libs.mappie.gradle.plugin)
    implementation(libs.metro.gradle.plugin)
    implementation(libs.compose.gradle.plugin)
}
