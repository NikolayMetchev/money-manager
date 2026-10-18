A JVM-only desktop module (#1018): no `kmp_targets`, so no native targets. Each compilation's `resources` dirs
become one `resources` glob (main and test separately), and `kmp_test_jvm_flags` / `kmp_test_flaky` set the
JVM test's flags and retries, as the Compose modules' Gradle conventions do.
