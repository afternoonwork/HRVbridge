package com.placeholder.hrvbridge.core

class JsonException(message: String) : IllegalArgumentException(message)

/**
 * Minimal JSON reader/quoter so :core needs no dependencies. Objects become
 * Map<String, Any?>, arrays List<Any?>, integers Long, other numbers Double.
 */
object Json {
    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.value()
        p.skipWs()
        if (p.i != text.length) p.fail("trailing characters")
        return v
    }

    fun quote(s: String): String = buildString {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    private class Parser(val s: String) {
        var i = 0

        fun fail(msg: String): Nothing = throw JsonException("$msg at offset $i")

        fun skipWs() {
            while (i < s.length && s[i] in " \t\r\n") i++
        }

        fun value(): Any? {
            if (i >= s.length) fail("unexpected end")
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> num()
            }
        }

        fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) fail("invalid literal")
            i += word.length
            return v
        }

        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++
            skipWs()
            if (peek() == '}') { i++; return m }
            while (true) {
                skipWs()
                if (peek() != '"') fail("expected key")
                val k = str()
                skipWs()
                expect(':')
                skipWs()
                m[k] = value()
                skipWs()
                when (peek()) {
                    ',' -> i++
                    '}' -> { i++; return m }
                    else -> fail("expected , or }")
                }
            }
        }

        fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++
            skipWs()
            if (peek() == ']') { i++; return l }
            while (true) {
                skipWs()
                l.add(value())
                skipWs()
                when (peek()) {
                    ',' -> i++
                    ']' -> { i++; return l }
                    else -> fail("expected , or ]")
                }
            }
        }

        fun str(): String {
            i++
            val b = StringBuilder()
            while (true) {
                if (i >= s.length) fail("unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return b.toString()
                    '\\' -> {
                        if (i >= s.length) fail("unterminated escape")
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> b.append(e)
                            'b' -> b.append('\b')
                            'f' -> b.append('\u000C')
                            'n' -> b.append('\n')
                            'r' -> b.append('\r')
                            't' -> b.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("bad unicode escape")
                                b.append(s.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: fail("bad unicode escape"))
                                i += 4
                            }
                            else -> fail("bad escape")
                        }
                    }
                    else -> b.append(c)
                }
            }
        }

        fun num(): Any {
            val start = i
            if (peek() == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val t = s.substring(start, i)
            if (!NUMBER.matches(t)) fail("invalid number '$t'")
            return if (t.any { it in ".eE" }) t.toDouble() else t.toLongOrNull() ?: t.toDouble()
        }

        fun peek(): Char = if (i < s.length) s[i] else fail("unexpected end")

        fun expect(c: Char) {
            if (peek() != c) fail("expected $c")
            i++
        }
    }

    private val NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
}
