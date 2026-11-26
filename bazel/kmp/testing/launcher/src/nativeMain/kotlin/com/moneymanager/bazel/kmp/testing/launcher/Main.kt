package com.moneymanager.bazel.kmp.testing.launcher

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.system.exitProcess

private const val PREFIX = "##teamcity["

/**
 * Runs a Kotlin/Native test executable, or a browser target's tests in Node, for `kmp_test` and reports their results
 * the way the JVM runner (`KmpJUnitRunner`) does.
 *
 * The executable (linked with `-generate-test-runner`) is the first argument. It runs with `--ktest_logger=TEAMCITY`,
 * whose service messages become JUnit XML in `$XML_OUTPUT_FILE`. The messages themselves stay out of the log: it gets
 * what the tests print, each failure and a summary, as the JVM runner writes them. `--test_filter`
 * (`TESTBRIDGE_TEST_ONLY`) is a substring of `Class#method`, as on the JVM.
 *
 * With `--node <node> <runner.mjs> <test.mjs>` it runs a wasmJs or js test program instead: runner.mjs reports the
 * same service messages and reads the filter from the environment itself.
 *
 * A native program, so CI runs the cross-linked test binaries on their own runners without a JDK.
 */
@OptIn(ExperimentalForeignApi::class)
fun main(args: Array<String>) {
    val command = mutableListOf<String>()
    if (args[0] == "--node") {
        command += absolutePath(args[1])
        // A local run's node_modules is symlinks into Bazel's repositories. Following them would leave the tree, and
        // a package's require() of its sibling (@js-joda/timezone's @js-joda/core) would no longer find it.
        command += "--preserve-symlinks"
        args.drop(2).mapTo(command, ::absolutePath)
    } else {
        command += absolutePath(args[0])
        command += "--ktest_logger=TEAMCITY"
        val only = getenv("TESTBRIDGE_TEST_ONLY")?.toKString()
        if (!only.isNullOrEmpty()) {
            command += "--ktest_regex_filter=.*" + regexLiteral(only.replace('#', '.')) + ".*"
        }
    }

    val report = JUnitXml()
    var suite = ""
    var failed = 0
    var ignored = 0
    val exit =
        runMergingOutput(command) { line ->
            val start = line.indexOf(PREFIX)
            if (start < 0) {
                printOut(line)
                return@runMergingOutput
            }
            if (start > 0) {
                // Output the test printed without a newline before the message.
                printOut(line.substring(0, start))
            }
            val message = parseServiceMessage(line, start + PREFIX.length)
            val attrs = message.attributes
            val name = attrs["name"] ?: ""
            when (message.type) {
                "testSuiteStarted" -> {
                    suite = name
                }

                "testSuiteFinished" -> {
                    suite = ""
                }

                "testStarted" -> {
                    report.caseFor(suite, name)
                }

                "testFinished" -> {
                    report.caseFor(suite, name).millis = attrs["duration"]?.toLongOrNull() ?: 0
                }

                "testIgnored" -> {
                    report.caseFor(suite, name).skipped = true
                    ignored++
                }

                "testFailed" -> {
                    val case = report.caseFor(suite, name)
                    case.failureMessage = attrs["message"] ?: ""
                    case.failureDetails = attrs["details"] ?: case.failureMessage
                    failed++
                    printErr("FAILED $suite.$name")
                    printErr(case.failureDetails.orEmpty())
                }
            }
        }

    getenv("XML_OUTPUT_FILE")?.toKString()?.let { writeFile(it, report.render()) }
    printErr("${report.count()} tests, $failed failed, $ignored ignored")
    exitProcess(
        when {
            exit != 0 -> exit
            report.count() > 0 && failed == 0 -> 0
            else -> 1
        },
    )
}

private fun regexLiteral(text: String): String =
    buildString {
        for (ch in text) {
            if (ch in "\\^$.|?*+()[]{}") append('\\')
            append(ch)
        }
    }
