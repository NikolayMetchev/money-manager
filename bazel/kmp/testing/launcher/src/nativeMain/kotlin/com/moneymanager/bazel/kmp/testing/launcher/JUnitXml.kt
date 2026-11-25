package com.moneymanager.bazel.kmp.testing.launcher

/**
 * The JUnit XML report Bazel reads from `$XML_OUTPUT_FILE`: one testsuite per class, in the order the cases were first
 * seen. Writes exactly what the JVM runner's `JUnitXml.java` does, so every platform's tests report the same way: keep
 * the two in step.
 */
class JUnitXml {
    class Case(val name: String) {
        var millis: Long = 0
        var failureMessage: String? = null
        var failureDetails: String? = null
        var skipped = false

        val failed: Boolean get() = failureDetails != null
    }

    private val suites = LinkedHashMap<String, LinkedHashMap<String, Case>>()

    fun caseFor(className: String, method: String): Case =
        suites.getOrPut(className) { LinkedHashMap() }.getOrPut(method) { Case(method) }

    fun count(): Int = suites.values.sumOf { it.size }

    fun render(): String =
        buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuites>\n")
            for ((suite, cases) in suites) {
                val failures = cases.values.count { it.failed }
                val skipped = cases.values.count { it.skipped }
                val millis = cases.values.sumOf { it.millis }
                append("  <testsuite name=\"${escape(suite)}\" tests=\"${cases.size}\" failures=\"$failures\" ")
                append("errors=\"0\" skipped=\"$skipped\" time=\"${seconds(millis)}\">\n")
                for (case in cases.values) {
                    append("    <testcase classname=\"${escape(suite)}\" name=\"${escape(case.name)}\" ")
                    append("time=\"${seconds(case.millis)}\"")
                    when {
                        case.failed -> {
                            append(">\n      <failure message=\"")
                            append(escape(case.failureMessage.toString()))
                            append("\">")
                            append(escape(case.failureDetails.orEmpty()))
                            append("</failure>\n    </testcase>\n")
                        }
                        case.skipped -> append(">\n      <skipped/>\n    </testcase>\n")
                        else -> append("/>\n")
                    }
                }
                append("  </testsuite>\n")
            }
            append("</testsuites>\n")
        }

    private fun seconds(millis: Long): String = "${millis / 1000}.${(millis % 1000).toString().padStart(3, '0')}"

    private fun escape(text: String): String =
        buildString(text.length) {
            for (ch in text) {
                when (ch) {
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '&' -> append("&amp;")
                    '"' -> append("&quot;")
                    else -> append(if (ch < ' ' && ch != '\n' && ch != '\t' && ch != '\r') ' ' else ch)
                }
            }
        }
}
