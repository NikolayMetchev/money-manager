package com.moneymanager.tools.repo

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val prettyJson =
    Json {
        prettyPrint = true
        prettyPrintIndent = " "
    }

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
    println(prettyJson.encodeToString(Snapshot.serializer(), snapshot))
}

/** GitHub's dependency-snapshot document; the field names are its REST API's. */
@Serializable
internal data class Snapshot(
    val version: Int,
    val sha: String,
    val ref: String,
    val job: SnapshotJob,
    val detector: SnapshotDetector,
    val scanned: String,
    val manifests: Map<String, Manifest>,
)

@Serializable
internal data class SnapshotJob(
    val correlator: String,
    val id: String,
)

@Serializable
internal data class SnapshotDetector(
    val name: String,
    val version: String,
    val url: String,
)

@Serializable
internal data class Manifest(
    val name: String,
    val file: ManifestFile,
    val resolved: Map<String, ResolvedPackage>,
)

@Serializable
internal data class ManifestFile(
    @SerialName("source_location") val sourceLocation: String,
)

@Serializable
internal data class ResolvedPackage(
    @SerialName("package_url") val packageUrl: String,
    val relationship: String,
    val scope: String,
    val dependencies: List<String>,
)

internal fun snapshot(
    manifests: Map<String, Manifest>,
    scanned: String,
    env: (String) -> String,
): Snapshot =
    Snapshot(
        version = 0,
        sha = env("GITHUB_SHA"),
        ref = env("GITHUB_REF"),
        job = SnapshotJob(correlator = "${env("GITHUB_WORKFLOW")}_${env("GITHUB_JOB")}", id = env("GITHUB_RUN_ID")),
        detector =
            SnapshotDetector(
                name = "money-manager maven_kmp",
                version = "1",
                url = "https://github.com/NikolayMetchev/money-manager/tree/main/bazel/kmp/deps",
            ),
        scanned = scanned,
        manifests = manifests,
    )

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
) = ResolvedPackage(packageUrl, if (direct) "direct" else "indirect", scope, dependencies.sorted())

/** What the two lockfile kinds share: rules_jvm_external's alone carries this hash. */
@Serializable
private class LockKind(
    @SerialName("__INPUT_ARTIFACTS_HASH") val inputArtifactsHash: JsonElement? = null,
)

/** A rules_jvm_external lockfile: one version per library, its dependencies by coordinate. */
@Serializable
private class MavenInstallLock(
    val artifacts: Map<String, LockedVersion>,
    val dependencies: Map<String, List<String>> = emptyMap(),
)

@Serializable
private class LockedVersion(
    val version: String,
)

/** The `maven_kmp` lockfile: each library's artifact per platform. */
@Serializable
private class MavenKmpLock(
    val inputs: MavenKmpInputs,
    val artifacts: Map<String, Map<String, MavenKmpArtifact>>,
)

@Serializable
private class MavenKmpInputs(
    val libraries: Map<String, String>,
)

@Serializable
private class MavenKmpArtifact(
    val version: String,
    val deps: List<String> = emptyList(),
)

private val lockfileJson = Json { ignoreUnknownKeys = true }

internal fun manifest(
    path: String,
    text: String,
    module: String,
): Manifest {
    val resolved =
        if (lockfileJson.decodeFromString<LockKind>(text).inputArtifactsHash != null) {
            mavenInstallResolved(path, lockfileJson.decodeFromString<MavenInstallLock>(text), module)
        } else {
            mavenKmpResolved(path, lockfileJson.decodeFromString<MavenKmpLock>(text))
        }
    return Manifest(name = path, file = ManifestFile(path), resolved = resolved)
}

private fun mavenInstallResolved(
    path: String,
    lock: MavenInstallLock,
    module: String,
): Map<String, ResolvedPackage> {
    val direct = mavenInstallArtifacts(module, File(path).name.removeSuffix("_install.json"))
    val version = lock.artifacts.mapValues { it.value.version }
    return version.keys.sorted().associate { coordinate ->
        val packageUrl = purl(coordinate, version.getValue(coordinate))
        packageUrl to
            entry(
                packageUrl,
                coordinate in direct,
                "development",
                lock.dependencies[coordinate]
                    .orEmpty()
                    .filter { it in version }
                    .map { purl(it, version.getValue(it)) },
            )
    }
}

private fun mavenKmpResolved(
    path: String,
    lock: MavenKmpLock,
): Map<String, ResolvedPackage> {
    val direct =
        lock.inputs.libraries.values
            .map(::ga)
            .toSet()
    val scope = if (File(path).name == "maven_kmp.lock.json") "runtime" else "development"

    class Pending(
        val coordinate: String,
        val dependencies: MutableSet<String> = sortedSetOf(),
    )
    val resolved = linkedMapOf<String, Pending>()
    for ((coordinate, platforms) in lock.artifacts) {
        for ((platform, artifact) in platforms) {
            val pending = resolved.getOrPut(purl(coordinate, artifact.version)) { Pending(coordinate) }
            for (name in artifact.deps) {
                // A dependency's version can differ per platform: take the one this platform resolved.
                val candidates = lock.artifacts[name]
                val chosen = candidates?.get(platform) ?: candidates?.values?.firstOrNull()
                if (chosen != null) pending.dependencies += purl(name, chosen.version)
            }
        }
    }
    return resolved.toSortedMap().mapValues { (packageUrl, p) -> entry(packageUrl, p.coordinate in direct, scope, p.dependencies) }
}
