plugins {
    id("moneymanager.android-convention")
}

kotlin {
    sourceSets {
        // Shared source set for JVM and Android (both have access to java.text.NumberFormat)
        val jvmAndroidMain by creating {
            dependsOn(commonMain.get())
        }

        jvmMain {
            dependsOn(jvmAndroidMain)
        }

        androidMain {
            dependsOn(jvmAndroidMain)
        }
    }
}
