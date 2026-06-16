import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

/**
 * Fails the build if a "pure import" module transitively depends on a forbidden project module
 * (db/di/ui). The resolved classpath roots are supplied as lazy providers so resolution happens at
 * execution time; the graph walk lives here (not in a build-script lambda) to stay configuration-cache
 * compatible.
 */
abstract class VerifyNoDbDependencyTask : DefaultTask() {
    @get:Input
    abstract val projectPath: Property<String>

    @get:Input
    abstract val forbiddenModules: SetProperty<String>

    /** Names of the inspected classpaths, parallel to [rootComponents]. */
    @get:Input
    abstract val configurationNames: ListProperty<String>

    /** Root of each inspected classpath's resolved dependency graph. */
    @get:Internal
    abstract val rootComponents: ListProperty<ResolvedComponentResult>

    @TaskAction
    fun verify() {
        val forbidden = forbiddenModules.get()
        val names = configurationNames.get()
        val roots = rootComponents.get()
        val offenders = sortedSetOf<String>()

        roots.forEachIndexed { index, root ->
            val configurationName = names.getOrElse(index) { "?" }
            val seen = HashSet<ResolvedComponentResult>()
            val stack = ArrayDeque<ResolvedComponentResult>()
            stack.addLast(root)
            while (stack.isNotEmpty()) {
                val component = stack.removeLast()
                if (!seen.add(component)) continue
                (component.id as? ProjectComponentIdentifier)?.let { id ->
                    if (id.projectPath in forbidden) {
                        offenders.add("${id.projectPath} (via $configurationName)")
                    }
                }
                component.dependencies
                    .filterIsInstance<ResolvedDependencyResult>()
                    .forEach { stack.addLast(it.selected) }
            }
        }

        require(offenders.isEmpty()) {
            "Pure-import module ${projectPath.get()} must not depend on db/di/ui modules, but found: " +
                offenders.joinToString()
        }
    }
}
