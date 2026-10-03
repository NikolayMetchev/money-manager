import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Functional tests for the build's own verification tasks, run against throwaway builds through TestKit.
 * Applying `moneymanager.pure-importer-convention` also puts build-logic's classes on the fixture's script
 * classpath, which is how the write-repository fixture can register [VerifyNoWriteRepositoryUsageTask].
 */
class VerificationTasksTest {
    private val projectDir: File = Files.createTempDirectory("build-logic-test").toFile()

    @AfterTest
    fun deleteProject() {
        projectDir.deleteRecursively()
    }

    @Test
    fun `verifyNoDbDependency passes when only allowed modules are on the classpath`() {
        writeDbFixture(libDependencies = "")

        val result = run("check")

        assertEquals(TaskOutcome.SUCCESS, result.task(":consumer:verifyNoDbDependency")?.outcome)
    }

    @Test
    fun `verifyNoDbDependency fails on a forbidden module reached transitively`() {
        writeDbFixture(libDependencies = """api(project(":app:db:core"))""")

        val result = runAndFail("check")

        assertEquals(TaskOutcome.FAILED, result.task(":consumer:verifyNoDbDependency")?.outcome)
        assertTrue(":app:db:core (via testCompileClasspath)" in result.output, result.output)
    }

    @Test
    fun `verifyNoWriteRepositoryUsage ignores comments and allowed files`() {
        writeSourceFixture(
            "src/main/kotlin/Engine.kt" to "class Engine(private val repo: AccountWriteRepository)",
            "src/main/kotlin/Reader.kt" to "// Writes go through the engine, never an AccountWriteRepository.\nclass Reader",
        )

        val result = run("verifyNoWriteRepositoryUsage")

        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyNoWriteRepositoryUsage")?.outcome)
    }

    @Test
    fun `verifyNoWriteRepositoryUsage reports each offending reference`() {
        writeSourceFixture(
            "src/main/kotlin/Engine.kt" to "class Engine",
            "src/main/kotlin/Screen.kt" to "class Screen(\n    private val repo: AccountWriteRepository,\n)",
        )

        val result = runAndFail("verifyNoWriteRepositoryUsage")

        assertTrue("src/main/kotlin/Screen.kt:2" in result.output, result.output)
    }

    /** `:consumer` applies the convention; `:lib` sits between it and the modules the convention forbids. */
    private fun writeDbFixture(libDependencies: String) {
        write("settings.gradle.kts", """include(":app:db:core", ":lib", ":consumer")""")
        write("app/db/core/build.gradle.kts", "plugins { `java-library` }")
        write(
            "lib/build.gradle.kts",
            """
            plugins { `java-library` }
            dependencies { $libDependencies }
            """.trimIndent(),
        )
        write(
            "consumer/build.gradle.kts",
            """
            plugins {
                `java-library`
                id("moneymanager.pure-importer-convention")
            }
            dependencies { implementation(project(":lib")) }
            """.trimIndent(),
        )
    }

    /** A single project scanning `src/main/kotlin`, with `Engine.kt` as the one allowed file. */
    private fun writeSourceFixture(vararg sources: Pair<String, String>) {
        write("settings.gradle.kts", "")
        write(
            "build.gradle.kts",
            """
            plugins { id("moneymanager.pure-importer-convention") }

            tasks.register<VerifyNoWriteRepositoryUsageTask>("verifyNoWriteRepositoryUsage") {
                projectPath.set(path)
                projectDirectory.set(layout.projectDirectory)
                allowedFilePaths.add("src/main/kotlin/Engine.kt")
                sources.from(fileTree("src/main/kotlin"))
            }
            """.trimIndent(),
        )
        sources.forEach { (path, content) -> write(path, content) }
    }

    private fun write(
        path: String,
        content: String,
    ) {
        projectDir.resolve(path).apply { parentFile.mkdirs() }.writeText(content)
    }

    private fun runner(task: String): GradleRunner =
        GradleRunner
            .create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            // Mirror the real build, so a convention that breaks either feature fails here too.
            .withArguments(task, "--configuration-cache", "-Dorg.gradle.isolated-projects=true", "--stacktrace")

    private fun run(task: String): BuildResult = runner(task).build()

    private fun runAndFail(task: String): BuildResult = runner(task).buildAndFail()
}
