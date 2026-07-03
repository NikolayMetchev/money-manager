import com.android.build.api.dsl.Lint

/**
 * Shared Android lint policy: warnings are fatal, but only main sources of a single variant are
 * analyzed. Lint on test sources and re-linting of dependencies added ~2 lintAnalyze* tasks per
 * module across 40+ Android modules for little signal.
 */
fun Lint.moneyManagerLintPolicy() {
    warningsAsErrors = true
    abortOnError = true
    checkTestSources = false
    ignoreTestSources = true
    checkDependencies = false
    checkReleaseBuilds = false
}
