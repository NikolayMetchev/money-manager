package com.moneymanager.ofx

/**
 * One OFX element: an aggregate holding [children], or a leaf holding a [value]. OFX 1.x (SGML) may leave a leaf's
 * closing tag out, OFX 2.x (XML) never does; both read into the same tree.
 */
class OfxElement(
    val name: String,
    val value: String? = null,
    val children: List<OfxElement> = emptyList(),
) {
    /** The first direct child named [childName]. */
    fun child(childName: String): OfxElement? = children.firstOrNull { it.name == childName }

    /** The value of the leaf at [path] below this element, each segment a direct child's name. */
    fun valueAt(vararg path: String): String? {
        var element: OfxElement = this
        for (segment in path) element = element.child(segment) ?: return null
        return element.value
    }

    /** Every element named [elementName] at or below this one, in document order. */
    fun findAll(elementName: String): List<OfxElement> =
        buildList {
            fun visit(element: OfxElement) {
                if (element.name == elementName) add(element)
                element.children.forEach(::visit)
            }
            visit(this@OfxElement)
        }
}

/**
 * Reads OFX markup into an [OfxElement] tree under a synthetic root. Whatever precedes the first tag (OFX 1.x's
 * `KEY:VALUE` header) and processing instructions (OFX 2.x's `<?xml?>` and `<?OFX?>`) are skipped. An open tag
 * followed by text is a leaf, whether or not its closing tag follows; any other open tag starts an aggregate, which
 * its closing tag ends (along with any aggregate left open inside it).
 */
internal object OfxMarkup {
    const val ROOT = "#root"

    private sealed interface Token {
        data class Open(
            val name: String,
        ) : Token

        data class Close(
            val name: String,
        ) : Token

        data class Text(
            val text: String,
        ) : Token
    }

    private class Builder(
        val name: String,
    ) {
        val children = mutableListOf<OfxElement>()

        fun build() = OfxElement(name, children = children.toList())
    }

    fun parse(content: String): OfxElement {
        val tokens = tokenize(content)
        val stack = ArrayDeque<Builder>().apply { addLast(Builder(ROOT)) }
        var index = 0
        while (index < tokens.size) {
            when (val token = tokens[index]) {
                is Token.Open -> {
                    index = open(token, tokens, index, stack)
                }

                is Token.Close -> {
                    close(token.name, stack)
                }

                is Token.Text -> {}
            }
            index++
        }
        while (stack.size > 1) closeTop(stack)
        return stack.single().build()
    }

    /** Handles the open tag at [index], returning the index of the last token it consumed. */
    private fun open(
        token: Token.Open,
        tokens: List<Token>,
        index: Int,
        stack: ArrayDeque<Builder>,
    ): Int {
        val next = tokens.getOrNull(index + 1)
        return when {
            next is Token.Text -> {
                stack.last().children += OfxElement(token.name, value = next.text)
                val closed = (tokens.getOrNull(index + 2) as? Token.Close)?.name == token.name
                if (closed) index + 2 else index + 1
            }

            next is Token.Close && next.name == token.name -> {
                stack.last().children += OfxElement(token.name, value = "")
                index + 1
            }

            else -> {
                stack.addLast(Builder(token.name))
                index
            }
        }
    }

    /** Ends the open aggregate named [name] and any left open inside it; a stray closing tag is ignored. */
    private fun close(
        name: String,
        stack: ArrayDeque<Builder>,
    ) {
        if (stack.drop(1).none { it.name == name }) return
        do {
            val closedName = closeTop(stack)
        } while (closedName != name)
    }

    private fun closeTop(stack: ArrayDeque<Builder>): String {
        val closed = stack.removeLast()
        stack.last().children += closed.build()
        return closed.name
    }

    private fun tokenize(content: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var position = content.indexOf('<').takeIf { it >= 0 } ?: return tokens
        while (position < content.length) {
            if (content[position] == '<') {
                val end = content.indexOf('>', position)
                if (end < 0) break
                val tag = content.substring(position + 1, end).trim()
                when {
                    tag.startsWith("?") || tag.startsWith("!") -> Unit
                    tag.startsWith("/") -> tokens += Token.Close(tag.substring(1).trim().uppercase())
                    tag.isNotEmpty() -> tokens += Token.Open(tag.substringBefore(' ').removeSuffix("/").uppercase())
                }
                position = end + 1
            } else {
                val end = content.indexOf('<', position).let { if (it < 0) content.length else it }
                val text = content.substring(position, end).trim()
                if (text.isNotEmpty()) tokens += Token.Text(decodeEntities(text))
                position = end
            }
        }
        return tokens
    }

    private val entity = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos|nbsp);")

    private fun decodeEntities(text: String): String =
        if ('&' !in text) {
            text
        } else {
            entity.replace(text) { match ->
                when (val name = match.groupValues[1]) {
                    "amp" -> "&"
                    "lt" -> "<"
                    "gt" -> ">"
                    "quot" -> "\""
                    "apos" -> "'"
                    "nbsp" -> " "
                    else -> codePoint(name)?.let(::fromCodePoint) ?: match.value
                }
            }
        }

    private fun codePoint(reference: String): Int? =
        if (reference.startsWith("#x")) reference.substring(2).toIntOrNull(16) else reference.substring(1).toIntOrNull()

    private fun fromCodePoint(codePoint: Int): String =
        if (codePoint < 0x10000) {
            codePoint.toChar().toString()
        } else {
            val offset = codePoint - 0x10000
            charArrayOf((0xD800 + (offset shr 10)).toChar(), (0xDC00 + (offset and 0x3FF)).toChar()).concatToString()
        }
}
