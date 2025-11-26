allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

subprojects {
    // Make check task depend on detekt to run it as part of the build
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(tasks.matching { it.name == "detekt" })
    }
}

tasks.register("lintFormat") {
    description = "Runs all formatting tasks"
    group = "formatting"
    dependsOn(subprojects.mapNotNull { it.tasks.findByName("sortDependencies") })
    dependsOn(subprojects.mapNotNull { it.tasks.findByName("ktlintFormat") })
}

dependencyAnalysis {
    issues {
        all {
            onAny {
                severity("fail")
            }
        }
    }
}

