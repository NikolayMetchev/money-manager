package com.moneymanager.bazel.kmp.testing.launcher

/** One TeamCity service message: `##teamcity[type key='value' …]`. */
class ServiceMessage(
    val type: String,
    val attributes: Map<String, String>,
)

/**
 * Parses the service message starting at [from], just after `##teamcity[`. Hand-written, since a regex overflows the
 * stack on a long failure message.
 */
fun parseServiceMessage(
    line: String,
    from: Int,
): ServiceMessage {
    var i = from
    while (i < line.length && line[i] != ' ' && line[i] != ']') i++
    val type = line.substring(from, i)
    val attributes = mutableMapOf<String, String>()
    while (true) {
        while (i < line.length && line[i] == ' ') i++
        val eq = if (i < line.length && line[i] != ']') line.indexOf('=', i) else -1
        if (eq < 0 || eq + 1 >= line.length || line[eq + 1] != '\'') break
        val key = line.substring(i, eq)
        val value = StringBuilder()
        i = eq + 2
        while (i < line.length && line[i] != '\'') {
            if (line[i] == '|' && i + 1 < line.length) {
                value.append(line, i, i + 2)
                i += 2
            } else {
                value.append(line[i++])
            }
        }
        attributes[key] = unescape(value.toString())
        i++
    }
    return ServiceMessage(type, attributes)
}

/** TeamCity's escapes: `|'`, `||`, `|n`, `|r`, `|[`, `|]`, `|0xXXXX`. */
private fun unescape(value: String): String {
    val sb = StringBuilder(value.length)
    var i = 0
    while (i < value.length) {
        val ch = value[i]
        if (ch != '|' || i + 1 == value.length) {
            sb.append(ch)
            i++
            continue
        }
        val next = value[++i]
        when (next) {
            'n' -> {
                sb.append('\n')
            }

            'r' -> {
                sb.append('\r')
            }

            'x' -> {
                sb.append('\u0085')
            }

            'l' -> {
                sb.append(' ')
            }

            'p' -> {
                sb.append(' ')
            }

            '0' -> {
                if (i + 5 < value.length && value[i + 1] == 'x') {
                    sb.append(value.substring(i + 2, i + 6).toInt(16).toChar())
                    i += 5
                } else {
                    sb.append(next)
                }
            }

            else -> {
                sb.append(next)
            }
        }
        i++
    }
    return sb.toString()
}
