package dev.apartejs.secrets

// Units, then byte-level BPE inside each unit, reproducing the JavaScript runtime exactly.
// Positions are counted in code points; the public API converts them to UTF-16 indices.

// ---- character classes

private val LETTERS = (1 shl Character.UPPERCASE_LETTER.toInt()) or (1 shl Character.LOWERCASE_LETTER.toInt()) or
    (1 shl Character.TITLECASE_LETTER.toInt()) or (1 shl Character.MODIFIER_LETTER.toInt()) or
    (1 shl Character.OTHER_LETTER.toInt())
private val NUMBERS = (1 shl Character.DECIMAL_DIGIT_NUMBER.toInt()) or (1 shl Character.LETTER_NUMBER.toInt()) or
    (1 shl Character.OTHER_NUMBER.toInt())

/** `[\p{L}\p{N}_\-]`: what a unit is made of. */
internal fun isUnitChar(c: Int): Boolean =
    c == '_'.code || c == '-'.code || (((LETTERS or NUMBERS) ushr Character.getType(c)) and 1) != 0

/** JavaScript's `\s` (WhiteSpace and LineTerminator), used by the unit split. */
internal fun isJSSpace(c: Int): Boolean = when (c) {
    in 0x09..0x0D, 0x20, 0xA0, 0x1680, in 0x2000..0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF -> true
    else -> false
}

/** Python's `str.isspace()`, used to measure a unit and to trim a span. */
internal fun isPythonSpace(c: Int): Boolean = when (c) {
    in 0x09..0x0D, in 0x1C..0x1F, 0x20, 0x85, 0xA0, 0x1680, in 0x2000..0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000 -> true
    else -> false
}

/** The code points of a string. A lone surrogate stays a code point of its own, as in JavaScript. */
internal fun codePoints(s: String): IntArray {
    val out = IntArray(s.codePointCount(0, s.length))
    var i = 0
    var k = 0
    while (i < s.length) {
        val c = s.codePointAt(i)
        out[k++] = c
        i += Character.charCount(c)
    }
    return out
}

// ---- units

private const val SPACE = ' '.code

/**
 * The model's units, the JavaScript regex `` ?[\p{L}\p{N}_\-]+| ?[^\s\p{L}\p{N}_\-]|\s+(?!\S)|\s+``
 * written out as a scanner: runs of letters, digits, `_` and `-` with an optional leading space;
 * every other symbol alone (with an optional leading space); whitespace. The alternatives are tried
 * in that order and the first that matches wins. Returns start and end positions, two per unit.
 */
internal fun units(s: IntArray): IntArray {
    val out = IntList()
    val n = s.size
    var i = 0
    while (i < n) {
        var j: Int
        val spaceThen = s[i] == SPACE && i + 1 < n
        if (isUnitChar(s[i]) || (spaceThen && isUnitChar(s[i + 1]))) {
            j = if (s[i] == SPACE) i + 1 else i
            while (j < n && isUnitChar(s[j])) j++
        } else if (spaceThen && !isJSSpace(s[i + 1])) {
            j = i + 2                                 // a space and one symbol
        } else if (!isJSSpace(s[i])) {
            j = i + 1                                 // one symbol
        } else {
            // whitespace: `\s+(?!\S)` keeps the last space for what follows when the run is longer than one
            j = i
            while (j < n && isJSSpace(s[j])) j++
            if (j < n && j - i >= 2) j--
        }
        out.add(i); out.add(j)
        i = j
    }
    return out.toArray()
}

// ---- BPE

/** The UTF-8 bytes of a code point; a lone surrogate becomes U+FFFD, as JavaScript's TextEncoder does. */
private fun utf8(c: Int, out: IntList) {
    val v = if (c in 0xD800..0xDFFF) 0xFFFD else c
    when {
        v < 0x80 -> out.add(v)
        v < 0x800 -> { out.add(0xC0 or (v shr 6)); out.add(0x80 or (v and 0x3F)) }
        v < 0x10000 -> { out.add(0xE0 or (v shr 12)); out.add(0x80 or ((v shr 6) and 0x3F)); out.add(0x80 or (v and 0x3F)) }
        else -> {
            out.add(0xF0 or (v shr 18)); out.add(0x80 or ((v shr 12) and 0x3F))
            out.add(0x80 or ((v shr 6) and 0x3F)); out.add(0x80 or (v and 0x3F))
        }
    }
}

/** Tokens (ids and the unit of each) and units (start and end, in code points). */
internal class Encoded(val ids: IntArray, val unitOf: IntArray, val unitStarts: IntArray, val unitEnds: IntArray)

internal class Tokenizer(header: ModelHeader, merges: IntArray) {
    private val byteIds = header.byteIds
    private val base = header.mergesBase
    private val rank = IntIntMap(merges.size / 2).apply {
        for (k in 0 until merges.size / 2) put(merges[2 * k] * 65536 + merges[2 * k + 1], k)
    }

    fun encode(text: IntArray): Encoded {
        val ids = IntList()
        val unitOf = IntList()
        val pieces = units(text)
        val bytes = IntList()
        val tokens = IntList()
        for (u in 0 until pieces.size / 2) {
            tokens.clear()
            for (k in pieces[2 * u] until pieces[2 * u + 1]) {
                bytes.clear()
                utf8(text[k], bytes)
                for (b in 0 until bytes.size) tokens.add(byteIds[bytes[b]])
            }
            while (tokens.size > 1) {                // merge the lowest-ranked pair first
                var best = -1
                var at = -1
                for (j in 0 until tokens.size - 1) {
                    val r = rank.get(tokens[j] * 65536 + tokens[j + 1])
                    if (r >= 0 && (best < 0 || r < best)) { best = r; at = j }
                }
                if (best < 0) break
                tokens[at] = base + best
                tokens.removeAt(at + 1)
            }
            for (t in 0 until tokens.size) { ids.add(tokens[t]); unitOf.add(u) }
        }
        val starts = IntArray(pieces.size / 2) { pieces[2 * it] }
        val ends = IntArray(pieces.size / 2) { pieces[2 * it + 1] }
        return Encoded(ids.toArray(), unitOf.toArray(), starts, ends)
    }
}

// ---- small primitive collections, to keep boxing out of the hot loops

internal class IntList(capacity: Int = 16) {
    private var a = IntArray(capacity)
    var size = 0
        private set

    fun add(v: Int) {
        if (size == a.size) a = a.copyOf(a.size * 2)
        a[size++] = v
    }

    operator fun get(i: Int): Int = a[i]
    operator fun set(i: Int, v: Int) { a[i] = v }

    fun removeAt(i: Int) {
        System.arraycopy(a, i + 1, a, i, size - i - 1)
        size--
    }

    fun clear() { size = 0 }
    fun toArray(): IntArray = a.copyOf(size)
}

/** An open-addressing map from non-negative ints to ints; [get] returns -1 when absent. Later puts win. */
internal class IntIntMap(expected: Int) {
    private val keys: IntArray
    private val values: IntArray
    private val mask: Int

    init {
        var capacity = 16
        while (capacity < expected * 2) capacity = capacity shl 1
        keys = IntArray(capacity) { -1 }
        values = IntArray(capacity)
        mask = capacity - 1
    }

    private fun slot(key: Int): Int {
        var h = key * -0x61c88647
        h = h xor (h ushr 16)
        return h and mask
    }

    fun put(key: Int, value: Int) {
        var i = slot(key)
        while (keys[i] != -1 && keys[i] != key) i = (i + 1) and mask
        keys[i] = key
        values[i] = value
    }

    fun get(key: Int): Int {
        var i = slot(key)
        while (true) {
            val k = keys[i]
            if (k == key) return values[i]
            if (k == -1) return -1
            i = (i + 1) and mask
        }
    }
}
