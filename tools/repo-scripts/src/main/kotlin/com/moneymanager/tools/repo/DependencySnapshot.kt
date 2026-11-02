package com.moneymanager.tools.repo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * GitHub's dependency-graph snapshot from the Maven lockfiles (#1028).
 *
 * Bazel's resolution is the lockfiles `bazel run @<hub>//:pin` writes. Each becomes a manifest: every library it
 * resolved, with its dependencies. The app's own libraries (the `maven_kmp` lockfile, at each version some platform
 * resolved them to, direct when the install names it in `libraries`) are runtime dependencies. The build tools'
 * (ktlint, detekt, ProGuard, …: rules_jvm_external's `bazel/maven/<name>_install.json`, direct when its MODULE.bazel
 * `maven.install` lists it above the transitive pins) are development ones. Usage, with the job's `GITHUB_SHA`,
 * `GITHUB_REF`, `GITHUB_RUN_ID`, `GITHUB_JOB` and `GITHUB_WORKFLOW` set:
 *
 *     bazel run //tools/repo-scripts:repo-scripts-tool -- dependency-snapshot <the lockfiles>
 *         | gh api repos/{owner}/{repo}/dependency-graph/snapshots --input -
 */
fun dependencySnapshot(paths: List<String>) {
    val module = workspaceFile("MODULE.bazel").readText()
    val manifests = paths.sorted().associateWith { manifest(it, workspaceFile(it).readText(), module) }
    val scanned = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC).format(Instant.now())
    val snapshot = snapshot(manifests, scanned) { System.getenv(it) ?: fail("$it isn't set") }
    println(
        Json {
            prettyPrint = true
            prettyPrintIndent = " "
        }.encodeToString(JsonObject.serializer(), snapshot),
    )
}

internal fun snapshot(
    manifests: Map<String, JsonObject>,
    scanned: String,
    env: (String) -> String,
): JsonObject =
    buildJsonObject {
        put("version", 0)
        put("sha", env("GITHUB_SHA"))
        put("ref", env("GITHUB_REF"))
        put(
            "job",
            buildJsonObject {
                put("correlator", "${env("GITHUB_WORKFLOW")}_${env("GITHUB_JOB")}")
                put("id", env("GITHUB_RUN_ID"))
            },
        )
        put(
            "detector",
            buildJsonObject {
                put("name", "money-manager maven_kmp")
                put("version", "1")
                put("url", "https://github.com/NikolayMetchev/money-manager/tree/main/bazel/kmp/deps")
            },
        )
        put("scanned", scanned)
        put("manifests", JsonObject(manifests))
    }

private fun purl(
    coordinate: String,
    version: String,
): String {
    val (group, artifact) = coordinate.split(":")
    return "pkg:maven/$group/$artifact@$version"
}

private fun ga(coordinate: String) = coordinate.split(":").take(2).joinToString(":")

/** The coordinates MODULE.bazel's `maven.install(name = …)` asks for, without its transitive pins. */
internal fun mavenInstallArtifacts(
    module: String,
    name: String,
): Set<String> {
    val install =
        Regex("""maven\.install\(\s*name = "${Regex.escape(name)}",\s*artifacts = \[(.*?)]""", RegexOption.DOT_MATCHES_ALL).find(module)
            ?: return emptySet()
    val listed = install.groupValues[1].substringBefore("# Transitive versions")
    return Regex(""""([^"]+)"""").findAll(listed).map { ga(it.groupValues[1]) }.toSet()
}

private fun entry(
    packageUrl: String,
    direct: Boolean,
    scope: String,
    dependencies: Collection<String>,
): JsonObject =
    buildJsonObject {
        put("package_url", packageUrl)
        put("relationship", if (direct) "direct" else "indirect")
        put("scope", scope)
        put("dependencies", JsonArray(dependencies.sorted().map(::JsonPrimitive)))
    }

internal fun manifest(
    path: String,
    text: String,
    module: String,
): JsonObject {
    val lock = Json.parseToJsonElement(text).jsonObject
    val resolved: Map<String, JsonElement> =
        if ("__INPUT_ARTIFACTS_HASH" in lock) mavenInstallResolved(path, lock, module) else mavenKmpResolved(path, lock)
    return buildJsonObject {
        put("name", path)
        put("file", buildJsonObject { put("source_location", path) })
        put("resolved", JsonObject(resolved))
    }
}

/** A rules_jvm_external lockfile: one version per library, its dependencies by coordinate. */
private fun mavenInstallResolved(
    path: String,
    lock: JsonObject,
    module: String,
): Map<String, JsonElement> {
    val dependencies = lock["dependencies"]?.jsonObject ?: JsonObject(emptyMap())
    val direct = mavenInstallArtifacts(module, File(path).name.removeSuffix("_install.json"))
    val version =
        lock.getValue("artifacts").jsonObject.mapValues {
            it.value.jsonObject
                .getValue("version")
                .jsonPrimitive.content
        }
    return version.keys.sorted().associate { coordinate ->
        val packageUrl = purl(coordinate, version.getValue(coordinate))
        packageUrl to
            entry(
                packageUrl,
                coordinate in direct,
                "development",
                (dependencies[coordinate] as? JsonArray)
                    .orEmpty()
                    .map { it.jsonPrimitive.content }
                    .filter { it in version }
                    .map { purl(it, version.getValue(it)) },
            )
    }
}

private fun mavenKmpResolved(
    path: String,
    lock: JsonObject,
): Map<String, JsonElement> {
    val direct =
        lock
            .getValue("inputs")
            .jsonObject
            .getValue("libraries")
            .jsonObject.values
            .map { ga(it.jsonPrimitive.content) }
            .toSet()
    val scope = if (File(path).name == "maven_kmp.lock.json") "runtime" else "development"
    val artifacts = lock.getValue("artifacts").jsonObject

    class Pending(
        val coordinate: String,
        val dependencies: MutableSet<String> = sortedSetOf(),
    )
    val resolved = linkedMapOf<String, Pending>()
    for ((coordinate, platforms) in artifacts) {
        for ((platform, artifact) in platforms.jsonObject) {
            val version =
                artifact.jsonObject
                    .getValue("version")
                    .jsonPrimitive.content
            val pending = resolved.getOrPut(purl(coordinate, version)) { Pending(coordinate) }
            for (dependency in (artifact.jsonObject["deps"] as? JsonArray).orEmpty()) {
                val name = dependency.jsonPrimitive.content
                // A dependency's version can differ per platform: take the one this platform resolved.
                val candidates = artifacts[name]?.jsonObject
                val chosen = candidates?.get(platform) ?: candidates?.values?.firstOrNull()
                if (chosen != null) {
                    pending.dependencies +=
                        purl(
                            name,
                            chosen.jsonObject
                                .getValue("version")
                                .jsonPrimitive.content,
                        )
                }
            }
        }
    }
    return resolved.toSortedMap().mapValues { (packageUrl, p) -> entry(packageUrl, p.coordinate in direct, scope, p.dependencies) }
}

private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())
