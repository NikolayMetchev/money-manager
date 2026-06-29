plugins {
    id("moneymanager.android-convention")
    id("moneymanager.kotlin-multiplatform-convention")
    alias(libs.plugins.sqldelight)
}

kotlin {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                // Both a normal dependency (for generated schema types) AND the sqldelight dependency()
                // below (for .sq cross-module schema resolution) are required.
                api(projects.app.db.schema)
            }
        }
        getByName("jvmMain") {
            dependencies {
                api(projects.app.db.schema)
            }
        }
    }
}

// The :app:db:seed:generator JVM tool emits the dynamic seed .sq (currencies + strategies). Resolve its
// runtime classpath through a configuration (isolated-projects-safe; never reach into another project's
// sourceSets) and run it before SQLDelight reads the seed sources.
val seedGenerator: Configuration = configurations.create("seedGenerator")
dependencies {
    seedGenerator(projects.app.db.seed.generator)
}

val generateSeedSql =
    tasks.register<JavaExec>("generateSeedSql") {
        group = "build"
        description = "Generates the dynamic seed .sq (currencies + built-in strategies)."
        val outDir = layout.buildDirectory.dir("generated/seed/sqldelight")
        outputs.dir(outDir)
        classpath = seedGenerator
        mainClass.set("com.moneymanager.database.seed.generator.SeedGeneratorKt")
        argumentProviders.add { listOf(outDir.get().asFile.absolutePath) }
    }

// The seed module: holds seed INSERTs (no DDL). dependency(:schema) makes the schema module's tables +
// triggers run during Schema.create FIRST, then these INSERTs — so audited seeds fire their audit
// triggers exactly as the old runtime seeding did. Static seeds are the checked-in StaticSeed.sq;
// currencies + strategies are generated into build/ by generateSeedSql (not checked in).
sqldelight {
    databases {
        create("MoneyManagerDatabase") {
            packageName.set("com.moneymanager.database.sql.seed")
            verifyMigrations.set(false)
            dialect(libs.sqldelight.dialect.sqlite)
            dependency(project(":app:db:schema"))
            srcDirs("src/commonMain/sqldelight", generateSeedSql)
        }
    }
}

// srcDirs(taskProvider) registers the dir but NOT the task edge (verified in spike) — wire it explicitly.
tasks.matching { it.name.contains("MoneyManagerDatabaseInterface") }.configureEach {
    dependsOn(generateSeedSql)
}

tasks.withType<app.cash.sqldelight.gradle.VerifyMigrationTask>().configureEach {
    enabled = false
}
