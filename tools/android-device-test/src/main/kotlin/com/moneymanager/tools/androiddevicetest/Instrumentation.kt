package com.moneymanager.tools.androiddevicetest

import java.nio.file.Files
import java.nio.file.Path

/** One test's status blocks: its start (code 1) and its end (0 passed, -3 ignored, -4 assumption, else failed). */
internal data class TestResult(
    val className: String,
    val name: String,
    val code: Int,
    val stack: String?,
    val millis: Long,
) {
    val failed get() = code != 0 && code != -3 && code != -4
    val skipped get() = code == -3 || code == -4
}

internal data class InstrumentationRun(
    val results: List<TestResult>,
    val code: Int?,
    val failure: String?,
) {
    /** -1 is Activity.RESULT_OK: the runner finished. A crash leaves INSTRUMENTATION_FAILED and no code. */
    val succeeded get() = results.none { it.failed } && code == -1 && failure == null && results.isNotEmpty()
}

/**
 * `am instrument -r`: `INSTRUMENTATION_STATUS: key=value` lines (a value runs on until the next `INSTRUMENTATION_` line, as
 * stack traces do), each block closed by `INSTRUMENTATION_STATUS_CODE`, then `INSTRUMENTATION_RESULT` lines and
 * `INSTRUMENTATION_CODE`.
 */
internal fun parseInstrumentation(
    lines: List<String>,
    now: () -> Long = System::currentTimeMillis,
): InstrumentationRun {
    val results = mutableListOf<TestResult>()
    val status = LinkedHashMap<String, StringBuilder>()
    var current: StringBuilder? = null
    var started = 0L
    var code: Int? = null
    var failure: String? = null
    for (line in lines) {
        when {
            line.startsWith("INSTRUMENTATION_STATUS: ") || line.startsWith("INSTRUMENTATION_RESULT: ") -> {
                val pair = line.substringAfter(": ")
                val key = pair.substringBefore('=')
                val value = if ('=' in pair) pair.substringAfter('=') else ""
                current = StringBuilder(value)
                status[key] = current
                if (line.startsWith("INSTRUMENTATION_RESULT: ") && pair.startsWith("shortMsg=")) failure = value
            }

            line.startsWith("INSTRUMENTATION_STATUS_CODE: ") -> {
                val statusCode = line.substringAfter(": ").trim().toInt()
                val className = status["class"]?.toString()
                val name = status["test"]?.toString()
                if (statusCode == 1) {
                    started = now()
                } else if (className != null && name != null) {
                    results += TestResult(className, name, statusCode, status["stack"]?.toString(), now() - started)
                }
                status.clear()
                current = null
            }

            line.startsWith("INSTRUMENTATION_CODE: ") -> {
                code = line.substringAfter(": ").trim().toInt()
                current = null
            }

            line.startsWith("INSTRUMENTATION_FAILED: ") || line.startsWith("INSTRUMENTATION_ABORTED") -> {
                failure = line
                current = null
            }

            else -> {
                current?.append('\n')?.append(line)
            }
        }
    }
    return InstrumentationRun(results, code, failure)
}

/** Bazel's test.xml: one testcase per test. */
internal fun junitXml(
    pkg: String,
    run: InstrumentationRun,
): String =
    buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuites>\n")
        append(
            "  <testsuite name=\"${escape(pkg)}\" tests=\"${run.results.size}\" failures=\"${run.results.count { it.failed }}\" " +
                "skipped=\"${run.results.count { it.skipped }}\" errors=\"${if (run.failure == null) 0 else 1}\">\n",
        )
        for (r in run.results) {
            append(
                "    <testcase classname=\"${escape(
                    r.className,
                )}\" name=\"${escape(r.name)}\" time=\"${"%.3f".format(r.millis / 1000.0)}\"",
            )
            when {
                r.failed -> {
                    append(
                        ">\n      <failure message=\"${escape(
                            r.stack
                                .orEmpty()
                                .lineSequence()
                                .first(),
                        )}\">" +
                            "${escape(r.stack.orEmpty())}</failure>\n    </testcase>\n",
                    )
                }

                r.skipped -> {
                    append(">\n      <skipped/>\n    </testcase>\n")
                }

                else -> {
                    append("/>\n")
                }
            }
        }
        run.failure?.let { append("    <error message=\"${escape(it)}\"/>\n") }
        append("  </testsuite>\n</testsuites>\n")
    }

internal fun writeJunitXml(
    output: Path,
    pkg: String,
    run: InstrumentationRun,
) {
    Files.writeString(output, junitXml(pkg, run))
}

private fun escape(text: String): String =
    buildString {
        for (c in text) {
            when {
                c == '&' -> append("&amp;")
                c == '<' -> append("&lt;")
                c == '>' -> append("&gt;")
                c == '"' -> append("&quot;")
                c.code >= 0x20 || c == '\n' || c == '\t' || c == '\r' -> append(c)
            }
        }
    }
