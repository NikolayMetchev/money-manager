plugins {
    id("moneymanager.kotlin-multiplatform-convention")
    id("moneymanager.android-convention")
}

// Excel parsing uses Apache POI, which is JVM-only (not Android-friendly: large, reflection-heavy,
// and not designed for the Android runtime). commonMain declares the parsing contract; jvmMain
// implements it with POI; androidMain's actual reports the platform as unsupported.
kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(projects.utils.parsers.csv)
            }
        }

        getByName("jvmMain") {
            dependencies {
                // WorkbookFactory/DataFormatter/Row/Sheet are org.apache.poi:poi (core) API; poi-ooxml
                // is only needed at runtime so WorkbookFactory can dispatch to the XSSF (.xlsx) reader.
                api(projects.utils.parsers.csv)
                implementation(libs.poi)
                runtimeOnly(libs.poi.ooxml)
            }
        }

        getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        getByName("jvmTest") {
            dependencies {
                // The test fixture builds workbooks directly with XSSFWorkbook (poi-ooxml).
                implementation(libs.poi)
                implementation(libs.poi.ooxml)
            }
        }
    }
}
