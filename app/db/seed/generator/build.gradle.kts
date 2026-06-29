plugins {
    alias(libs.plugins.kotlin.jvm)
    id("moneymanager.kotlin-convention")
    alias(libs.plugins.kotlin.serialization)
}

// JVM tool: emits the dynamic seed .sq (currencies + built-in strategies) consumed at build time by
// :app:db:seed via the generateSeedSql JavaExec. Depends only on db-free model/currency + the read
// module's JSON codecs — NOT on :app:db:seed (which would cycle).
dependencies {
    api(projects.app.db.read)

    implementation(projects.app.model.core)
}
