A JVM-only desktop module (#1018): no `kmp_targets`, so no native targets. Each compilation's `resources` dirs
become one `resources` glob (main and test separately), and `kmp_test_jvm_flags` / `kmp_test_flaky` set the
JVM test's flags and retries, as the Compose modules' Gradle conventions do. `kmp_test_jvm_tags` tags the JVM test
(`no-remote-exec`: Skiko needs the host's libGL).

`app` is a `kotlin("jvm")` module: `src/main` and `src/test` are its JVM compilations, and `src/main/resources`
its resources.
