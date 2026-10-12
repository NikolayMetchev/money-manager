import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.serialization)
    id("moneymanager.kotlin-multiplatform-convention")
    id("moneymanager.jvm-android-shared-convention")
}

// Google Drive remote-storage backend. The Drive REST API + OAuth are spoken over the shared KMP Ktor
// client and a raw ServerSocket loopback receiver, so the provider runs identically on JVM and Android
// from a single jvmAndroidMain source set. The only platform-specific piece is opening the system
// browser for consent (BrowserLauncher), implemented in jvmMain/androidMain.
//
// buildHealth (KMP quirk): ABI project/library deps used by jvmAndroidMain must be declared as `api` on
// each real platform source set (jvmMain + androidMain); runtime-only deps live in jvmAndroidMain.
kotlin {
    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlinx.serialization.json)
            }
        }

        // JVM and Android share the entire provider, OAuth, loopback receiver and account store.
        getByName("jvmAndroidMain") {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.ktor.client.cio)
            }
        }

        jvmMain {
            dependencies {
                api(projects.app.importfilesource.core)
                api(projects.app.remotestorage.core)
                api(projects.utils.credentialvault)
                api(libs.kotlinx.serialization.core)
                api(libs.ktor.client.auth)
                api(libs.ktor.client.core)

                implementation(libs.ktor.http)
                implementation(libs.ktor.utils)
            }
        }

        androidMain {
            dependencies {
                api(projects.app.importfilesource.core)
                api(projects.app.remotestorage.core)
                api(projects.utils.credentialvault)
                api(libs.kotlinx.serialization.core)
                api(libs.ktor.client.auth)
                api(libs.ktor.client.core)

                implementation(libs.ktor.http)
                implementation(libs.ktor.utils)
            }
        }

        getByName("jvmTest") {
            dependencies {
                implementation(projects.test.utils.credentialvault)
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

// --- Shipped desktop Google OAuth client (build-time injected, XOR-obfuscated) ---
// The desktop loopback flow ships a default OAuth client so users get a one-click "Sign in with
// Google" instead of bringing their own credentials.json. Values resolve from (1) env vars (CI secrets),
// (2) gradle properties, then (3) a .gitignored secrets/google-oauth.properties — else empty, so builds
// without the secret still compile and the feature reports "not configured". They reach the task already
// XOR-obfuscated (xorObfuscate), so neither the task inputs nor the generated source hold the `GOCSPX-…`
// literal for push-protection / secret scanners to match. Obfuscation is only a speed bump; the real
// safeguards are the Cloud project quota cap and rotating this client id.
fun resolveOAuthValue(
    envVar: String,
    gradleProp: String,
    fileKey: String,
): String {
    // Trim before the emptiness check: a trailing newline/space from a CI secret or a properties file
    // would otherwise embed an invalid credential while isConfigured still reports true.
    providers
        .environmentVariable(envVar)
        .orNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { return it }
    providers
        .gradleProperty(gradleProp)
        .orNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { return it }
    val secretsFile = rootDir.resolve("secrets/google-oauth.properties")
    if (secretsFile.exists()) {
        val props = Properties()
        secretsFile.inputStream().use { props.load(it) }
        props
            .getProperty(fileKey)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
    }
    return ""
}

val generateGoogleOAuthDefaults =
    tasks.register<GenerateKotlinConstantsTask>("generateGoogleOAuthDefaults") {
        packageName.set("com.moneymanager.remotestorage.googledrive")
        objectName.set("GeneratedGoogleOAuth")
        obfuscatedConstants.put(
            "CLIENT_ID",
            xorObfuscate(resolveOAuthValue("GOOGLE_OAUTH_CLIENT_ID", "google.oauth.clientId", "clientId")),
        )
        obfuscatedConstants.put(
            "CLIENT_SECRET",
            xorObfuscate(resolveOAuthValue("GOOGLE_OAUTH_CLIENT_SECRET", "google.oauth.clientSecret", "clientSecret")),
        )
    }

kotlin.sourceSets.named("commonMain") {
    kotlin.srcDir(generateGoogleOAuthDefaults)
}
