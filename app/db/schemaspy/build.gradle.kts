plugins {
    alias(libs.plugins.kotlin.jvm)
    id("moneymanager.kotlin-convention")
}

dependencies {
    implementation(projects.app.db.core)
    implementation(projects.app.model.core)
}

// SchemaSpy's own classpath, kept off the module's compile/runtime classpaths. Declared through a
// dependency scope plus a resolvable configuration carrying JVM-runtime attributes, so variant-aware
// libraries resolve to their plain-JVM jars rather than leaving the selection to chance.
val schemaspy = configurations.dependencyScope("schemaspy")
val schemaspyClasspath =
    configurations.resolvable("schemaspyClasspath") {
        extendsFrom(schemaspy.get())
        attributes {
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
            attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
            attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
            attribute(
                TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
                objects.named(TargetJvmEnvironment.STANDARD_JVM),
            )
        }
    }

dependencies {
    schemaspy(libs.schemaspy)
    schemaspy(libs.sqldelight.sqlite.driver)
}

val schemaspyDatabase = layout.buildDirectory.file("schemaspy-temp.db")
val schemaspyOutputDir = layout.buildDirectory.dir("schemaspy")

val createDatabaseForSchemaSpy =
    tasks.register<JavaExec>("createDatabaseForSchemaSpy") {
        group = "documentation"
        description = "Create a SQLite database file with the schema for SchemaSpy analysis"

        // Local copies so the task actions capture providers, not the build script, which the
        // configuration cache cannot serialize.
        val dbFile = schemaspyDatabase

        // The runtime classpath includes the SQLDelight generated code and carries the build
        // dependency on compiling it.
        classpath = sourceSets["main"].runtimeClasspath

        // SchemaSpyDatabaseCreatorKt is the generated class for the top-level suspend main function
        mainClass.set("com.moneymanager.schemaspy.SchemaSpyDatabaseCreatorKt")
        argumentProviders.add(CommandLineArgumentProvider { listOf(dbFile.get().asFile.absolutePath) })

        outputs.file(dbFile)

        doFirst {
            // Delete old database file if it exists
            dbFile.get().asFile.delete()
        }
    }

val generateSchemaSpyDocs =
    tasks.register<JavaExec>("generateSchemaSpyDocs") {
        group = "documentation"
        description = "Generate HTML database documentation using SchemaSpy"

        val dbFile = schemaspyDatabase
        val outputDir = schemaspyOutputDir

        classpath(schemaspyClasspath)
        mainClass.set("org.schemaspy.Main")

        inputs
            .files(createDatabaseForSchemaSpy)
            .withPropertyName("database")
            .withPathSensitivity(PathSensitivity.NONE)
        outputs.dir(outputDir)

        // Set PATH to include Graphviz
        environment("PATH", "C:\\Program Files\\Graphviz\\bin;${System.getenv("PATH")}")

        argumentProviders.add(
            CommandLineArgumentProvider {
                listOf(
                    "-t",
                    "sqlite-xerial",
                    "-db",
                    dbFile.get().asFile.absolutePath,
                    "-o",
                    outputDir.get().asFile.absolutePath,
                    "-cat",
                    "%",
                    "-s",
                    "main",
                    "-u",
                    "",
                    "-sso",
                    "-norows",
                )
            },
        )

        doFirst {
            outputDir.get().asFile.mkdirs()
            println("Database location: ${dbFile.get().asFile.absolutePath}")
            println("Documentation will be generated at: ${outputDir.get().asFile.absolutePath}")
        }

        doLast {
            println("\nSchemaSpy documentation generated successfully!")
            println("Open: ${outputDir.get().asFile.resolve("index.html").absolutePath}")
        }
    }

val publishedDocsDir = layout.projectDirectory.dir("../../../webpage/database")

tasks.register<Sync>("publishSchemaSpyDocs") {
    group = "documentation"
    description = "Copy generated SchemaSpy HTML into the published docs site (webpage/database)"

    // Sync mirrors the source, removing stale files (e.g. dropped tables/views)
    from(generateSchemaSpyDocs)
    into(publishedDocsDir)
}
