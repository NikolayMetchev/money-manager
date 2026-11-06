package com.moneymanager.tools.repo

/**
 * The repository's scripts, in Kotlin so no Python is committed: `repin` (what a version bump leaves stale),
 * `dependency-snapshot` (GitHub's dependency graph from the lockfiles), `startup-bench` (the installed app's startup
 * times) and `accept-android-license` (what lets Bazel download the Android SDK). Run with `bazel run //tools/repo-scripts:repo-scripts-tool -- <script> <args>`.
 */
fun main(args: Array<String>) {
    val rest = args.drop(1)
    when (args.firstOrNull()) {
        "repin" -> repin(rest)
        "dependency-snapshot" -> dependencySnapshot(rest)
        "startup-bench" -> startupBench(rest)
        "accept-android-license" -> acceptAndroidLicense()
        else -> fail("usage: repo-scripts repin|dependency-snapshot|startup-bench|accept-android-license <args>")
    }
}
