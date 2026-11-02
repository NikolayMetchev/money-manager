package com.moneymanager.tools.repo

import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

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
