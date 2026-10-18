package dev.apartejs.secrets

// A minimal JSON reader for the model header, so that the runtime needs no
// dependency: objects become maps, arrays lists, numbers Double.

internal object Json {
    fun parse(text: String): Any? {
        val p = Parser(text)
        val value = p.value()
        p.skipSpaces()
        if (p.i != text.length) p.fail("trailing characters")
        return value
    }

    private class Parser(val s: String) {
        var i = 0

        fun fail(why: String): Nothing = throw IllegalArgumentException("JSON: $why at character $i")

        fun skipSpaces() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        }

        fun value(): Any? {
            skipSpaces()
            if (i >= s.length) fail("unexpected end")
            val c = s[i]
            return when {
                c == '{' -> obj()
                c == '[' -> arr()
                c == '"' -> str()
                c == 't' -> literal("true", true)
                c == 'f' -> literal("false", false)
                c == 'n' -> literal("null", null)
                c == '-' || c in '0'..'9' -> num()
                else -> fail("unexpected '$c'")
            }
        }

        private fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) fail("expected $word")
            i += word.length
            return v
        }

        private fun obj(): Map<String, Any?> {
            i++
            val m = LinkedHashMap<String, Any?>()
            skipSpaces()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                skipSpaces()
                if (i >= s.length || s[i] != '"') fail("expected a key")
                val key = str()
                skipSpaces()
                if (i >= s.length || s[i] != ':') fail("expected ':'")
                i++
                m[key] = value()
                skipSpaces()
                if (i >= s.length) fail("unexpected end")
                when (s[i++]) {
                    ',' -> {}
                    '}' -> return m
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun arr(): List<Any?> {
            i++
            val a = ArrayList<Any?>()
            skipSpaces()
            if (i < s.length && s[i] == ']') { i++; return a }
            while (true) {
                a.add(value())
                skipSpaces()
                if (i >= s.length) fail("unexpected end")
                when (s[i++]) {
                    ',' -> {}
                    ']' -> return a
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun str(): String {
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
                                if (i + 4 > s.length) fail("short \\u escape")
                                val code = s.substring(i, i + 4).toIntOrNull(16) ?: fail("bad \\u escape")
                                b.append(code.toChar())   // surrogate pairs come as two escapes
                                i += 4
                            }
                            else -> fail("bad escape \\$e")
                        }
                    }
                    else -> b.append(c)
                }
            }
        }

        private fun num(): Double {
            val start = i
            while (i < s.length && (s[i] in '0'..'9' || s[i] == '-' || s[i] == '+' || s[i] == '.' || s[i] == 'e' || s[i] == 'E')) i++
            return s.substring(start, i).toDoubleOrNull() ?: fail("bad number")
        }
    }
}
