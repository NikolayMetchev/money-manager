import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Fails the build if any inspected Kotlin source references a `*WriteRepository` type. This backstops
 * the read/write split: only the ImportEngine may take write repositories, so the producer/UI modules
 * (which this task is applied to) must talk to the engine, never a write repository directly. The
 * compile-time narrowing in `Application`/`AppServices` is the primary guard; this catches regressions
 * a contributor might introduce by re-injecting a write repository.
 *
 * [allowedFileNames] lists file names exempt from the check (e.g. a documented bootstrap exception).
 */
abstract class VerifyNoWriteRepositoryUsageTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Input
    abstract val allowedFileNames: SetProperty<String>

    @get:Input
    abstract val projectPath: Property<String>

    @TaskAction
    fun verify() {
        val allowed = allowedFileNames.get()
        val regex = Regex("""\b[A-Za-z]+WriteRepository\b""")
        val offenders = sortedSetOf<String>()

        sources.files
            .filter { it.isFile && it.name.endsWith(".kt") && it.name !in allowed }
            .forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    val trimmed = line.trimStart()
                    // Skip comment lines: KDoc/block (`*`, `/*`) and line comments (`//`) may name a
                    // write repository in prose without being a real usage.
                    val isComment = trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")
                    if (!isComment && regex.containsMatchIn(line)) {
                        offenders.add("${file.name}:${index + 1}  ${line.trim()}")
                    }
                }
            }

        require(offenders.isEmpty()) {
            buildString {
                appendLine("${projectPath.get()} must not reference any *WriteRepository — route writes through the ImportEngine.")
                appendLine("Offending references:")
                offenders.forEach { appendLine("  $it") }
            }
        }
    }
}
