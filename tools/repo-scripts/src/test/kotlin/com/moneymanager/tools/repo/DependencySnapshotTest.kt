package com.moneymanager.tools.repo

import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DependencySnapshotTest {
    @Test
    fun `a maven install lockfile is a development manifest with its direct libraries marked`() {
        val module = """maven.install(
            name = "tools",
            artifacts = [
                "g:a:1",
                # Transitive versions
                "g:b:2",
            ],"""
        assertEquals(setOf("g:a"), mavenInstallArtifacts(module, "tools"))
        val lock =
            """{"__INPUT_ARTIFACTS_HASH":{},"artifacts":{"g:a":{"version":"1"},"g:b":{"version":"2"}},"dependencies":{"g:a":["g:b"]}}"""
        val result: JsonObject = manifest("bazel/maven/tools_install.json", lock, module)
        val resolved = result.toString()
        assertEquals(
            true,
            resolved.contains(
                """"package_url":"pkg:maven/g/a@1","relationship":"direct","scope":"development","dependencies":["pkg:maven/g/b@2"]""",
            ),
        )
        assertEquals(true, resolved.contains(""""relationship":"indirect""""))
    }

    @Test
    fun `the median of an even count is the mean of the middle two`() {
        assertEquals(2.5, median(listOf(4.0, 1.0, 3.0, 2.0)))
        assertEquals(12.3, round1(12.34))
    }
}

class DependencySnapshotMoreTest {
    @Test
    fun `the maven_kmp lockfile is a runtime manifest whose dependencies take the version of their platform`() {
        val lock =
            """{"inputs":{"libraries":{"core":"g:a:1"}},"artifacts":{
              "g:a":{"jvm":{"version":"1","deps":["g:b"]},"native":{"version":"1","deps":["g:b"]}},
              "g:b":{"jvm":{"version":"2"},"native":{"version":"3"}}}}"""
        val result: JsonObject = manifest("bazel/kmp/deps/maven_kmp.lock.json", lock, "")
        val text = result.toString()
        assertTrue(text.contains(""""scope":"runtime""""))
        assertTrue(text.contains(""""dependencies":["pkg:maven/g/b@2","pkg:maven/g/b@3"]"""))
        assertTrue(text.contains(""""package_url":"pkg:maven/g/b@3","relationship":"indirect""""))
    }

    @Test
    fun `the snapshot names the job and the manifests`() {
        val env =
            mapOf(
                "GITHUB_SHA" to "sha",
                "GITHUB_REF" to "ref",
                "GITHUB_RUN_ID" to "7",
                "GITHUB_JOB" to "job",
                "GITHUB_WORKFLOW" to "wf",
            )
        val result = snapshot(mapOf("a.json" to JsonObject(emptyMap())), "2026-01-01T00:00:00Z") { env.getValue(it) }.toString()
        assertTrue(result.contains(""""correlator":"wf_job""""))
        assertTrue(result.contains(""""manifests":{"a.json":{}}"""))
    }
}
