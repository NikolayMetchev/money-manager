plugins {
    alias(libs.plugins.kotlin.jvm)
    id("moneymanager.kotlin-convention")
}

dependencies {
    implementation(libs.sqldelight.runtime)
    implementation(libs.sqldelight.sqlite.driver)
    implementation(projects.app.db.core)
}

// SchemaSpy configuration for database documentation
val schemaspyConfiguration: Configuration by configurations.creating

dependencies {
    schemaspyConfiguration(libs.schemaspy)
    schemaspyConfiguration(libs.sqldelight.sqlite.driver)
}

val createDatabaseForSchemaSpy by tasks.registering(JavaExec::class) {
    group = "documentation"
    description = "Create a SQLite database file with the schema for SchemaSpy analysis"

    val dbFile = layout.buildDirectory.file("schemaspy-temp.db").get().asFile

    // Use the runtime classpath which includes SQLDelight generated code
    classpath = sourceSets["main"].runtimeClasspath

    mainClass.set("com.moneymanager.schemaspy.SchemaSpyDatabaseCreator")
    args(dbFile.absolutePath)

    // Depend on compilation to ensure the helper class and SQLDelight code are available
    dependsOn(tasks.classes)

    doFirst {
        // Delete old database file if it exists
        if (dbFile.exists()) {
            dbFile.delete()
        }
    }

    outputs.file(dbFile)
}

tasks.register<JavaExec>("generateSchemaSpyDocs") {
    group = "documentation"
    description = "Generate HTML database documentation using SchemaSpy"

    dependsOn(createDatabaseForSchemaSpy)

    classpath = schemaspyConfiguration
    mainClass.set("org.schemaspy.Main")

    val outputDir = layout.buildDirectory.dir("schemaspy").get().asFile
    val dbFile = layout.buildDirectory.file("schemaspy-temp.db").get().asFile

    doFirst {
        // Create output directory
        outputDir.mkdirs()
        println("Database location: ${dbFile.absolutePath}")
        println("Documentation will be generated at: ${outputDir.absolutePath}")
    }

    // Set PATH to include Graphviz
    environment("PATH", "C:\\Program Files\\Graphviz\\bin;${System.getenv("PATH")}")

    args(
        "-t", "sqlite-xerial",
        "-db", dbFile.absolutePath,
        "-o", outputDir.absolutePath,
        "-cat", "%",
        "-s", "main",
        "-u", "",
        "-sso",
        "-norows",
    )

    inputs.file(dbFile)
    outputs.dir(outputDir)

    doLast {
        println("\nSchemaSpy documentation generated successfully!")
        println("Open: ${outputDir.absolutePath}${File.separator}index.html")
    }
}
