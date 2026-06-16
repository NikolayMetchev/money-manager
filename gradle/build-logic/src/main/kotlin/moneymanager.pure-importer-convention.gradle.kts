/**
 * Applied to the pure import modules (importengineapi + the engine impl + the csv/qif/api importers).
 * These modules must only ever build the import model and talk to the [ImportEngine] interface — they
 * must NEVER gain a (transitive) dependency on the database, DI, or UI layers. The only seam that
 * binds the interface to the database-backed implementation is the DI module.
 *
 * Registers a [VerifyNoDbDependencyTask] wired into `check` that walks every compile/runtime classpath
 * and fails the build if a forbidden project module appears on it.
 */
val verifyNoDbDependency =
    tasks.register<VerifyNoDbDependencyTask>("verifyNoDbDependency") {
        group = "verification"
        description = "Fails if this pure-import module transitively depends on db/di/ui modules."
        projectPath.set(project.path)
        forbiddenModules.set(setOf(":app:db:core", ":app:di:core", ":app:ui:core"))
    }

afterEvaluate {
    configurations
        .filter {
            it.isCanBeResolved &&
                (it.name.endsWith("CompileClasspath") || it.name.endsWith("RuntimeClasspath"))
        }.forEach { configuration ->
            verifyNoDbDependency.configure {
                configurationNames.add(configuration.name)
                rootComponents.add(configuration.incoming.resolutionResult.rootComponent)
            }
        }
}

tasks.matching { it.name == "check" }.configureEach {
    dependsOn(verifyNoDbDependency)
}
