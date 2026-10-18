// aparte-secrets — finds passwords, API keys, tokens and private keys in text, on-device.
//
// Reads the `.bin` model files published at https://huggingface.co/apartejs/aparte-secrets (format 1);
// the int8 model is bundled. No dependency.
//
//     val secrets = Secrets.load()
//     secrets.detect("the wifi password is Sunflower77# for the guests")
//     // [Span(start=21, end=33, text=Sunflower77#, subtype=password, score=…)]
//
// It gives exactly the spans of the JavaScript runtime (@aparte/secrets on npm) and of the Python
// reference implementation; the tests check it text by text.

package dev.apartejs.secrets

import java.io.File
import java.io.InputStream

/**
 * A secret found in the text.
 *
 * @property start where it starts, as a UTF-16 index of the text (`text.substring(start, end)`).
 * @property end where it ends (exclusive), in the same units.
 * @property subtype `password`, `api_key`, `token`, `private_key` or `other`.
 * @property score the model's confidence, from 0 to 1.
 */
public class Span(public val start: Int, public val end: Int, public val text: String, public val subtype: String, public val score: Double) {
    override fun equals(other: Any?): Boolean =
        other is Span && start == other.start && end == other.end && text == other.text && subtype == other.subtype && score == other.score

    override fun hashCode(): Int = ((start * 31 + end) * 31 + text.hashCode()) * 31 + subtype.hashCode()
    override fun toString(): String = "Span(start=$start, end=$end, text=$text, subtype=$subtype, score=$score)"
}

/** The result of [Secrets.mask]: the masked text, and each placeholder's value (keep it on the device). */
public class Masked(public val text: String, public val secrets: Map<String, String>)

/**
 * The detector: load a model file once, then scan as many texts as you like. It holds no mutable
 * state and can be shared across threads.
 */
public class Secrets(bytes: ByteArray) {
    /** The model file's header: version, precision, dimensions, subtypes. */
    public val header: ModelHeader

    private val tokenizer: Tokenizer
    private val model: Model
    private val classes: Int
    private val trans: Transitions

    init {
        val data = readModel(bytes)
        header = data.header
        tokenizer = Tokenizer(data.header, data.merges)
        model = Model(data.header, data.weights)
        classes = model.classes
        if (classes != 1 + 4 * header.subtypes.size) throw SecretsException("malformed model file: ${header.subtypes.size} subtypes for $classes classes")
        trans = Transitions(classes)
    }

    /**
     * Every secret found in the text, in text order. The model reports raw spans; [minScore] and
     * [ignore] (exact values) and [ignorePatterns] are the caller's policy, applied after it.
     */
    @JvmOverloads
    public fun detect(
        text: String,
        minScore: Double = 0.0,
        ignore: Collection<String> = emptyList(),
        ignorePatterns: Collection<Regex> = emptyList(),
    ): List<Span> {
        val spans = raw(text)
        if (minScore <= 0.0 && ignore.isEmpty() && ignorePatterns.isEmpty()) return spans
        return spans.filter { s -> s.score >= minScore && s.text !in ignore && ignorePatterns.none { it.containsMatchIn(s.text) } }
    }

    /**
     * Replace every secret with a placeholder (`[PASSWORD_1]`, `[API_KEY_2]`…), reversibly. A value found
     * once is masked everywhere it appears: the model may only flag the occurrence whose context gives it away.
     */
    @JvmOverloads
    public fun mask(
        text: String,
        minScore: Double = 0.0,
        ignore: Collection<String> = emptyList(),
        ignorePatterns: Collection<Regex> = emptyList(),
        placeholder: (subtype: String, n: Int) -> String = { subtype, n -> "[${subtype.uppercase()}_$n]" },
    ): Masked {
        val found = detect(text, minScore, ignore, ignorePatterns)
        val all = ArrayList<Span>(found)
        for (value in found.map { it.text }.distinct()) {
            val subtype = found.first { it.text == value }.subtype
            var i = text.indexOf(value)
            while (i >= 0) {
                val end = i + value.length
                if (all.none { it.start < end && i < it.end }) all += Span(i, end, value, subtype, 0.0)
                i = text.indexOf(value, end)
            }
        }
        all.sortBy { it.start }
        val out = StringBuilder()
        val seen = HashMap<String, String>()
        val secrets = LinkedHashMap<String, String>()
        var pos = 0
        var n = 0
        for (s in all) {
            if (s.start < pos) continue
            val ph = seen.getOrPut(s.text) { placeholder(s.subtype, ++n).also { secrets[it] = s.text } }
            out.append(text, pos, s.start).append(ph)
            pos = s.end
        }
        out.append(text, pos, text.length)
        return Masked(out.toString(), secrets)
    }

    /** Put the values back: the inverse of [mask] (e.g. on an AI's answer). */
    public fun unmask(text: String, secrets: Map<String, String>): String {
        var out = text
        for ((ph, value) in secrets) out = out.replace(ph, value)
        return out
    }

    private fun raw(text: String): List<Span> {
        val cps = codePoints(text)
        val enc = tokenizer.encode(cps)
        val n = enc.ids.size
        if (n == 0) return emptyList()
        val c = classes
        val nUnits = enc.unitStarts.size
        val unitLen = IntArray(nUnits) { u -> lengthClass(cps, enc.unitStarts[u], enc.unitEnds[u], header.unitLengthClasses) }
        val total = DoubleArray(n * c)
        val count = DoubleArray(n)
        for (d in windows(n, header.window, header.stride)) {
            val end = minOf(d + header.window, n)
            val len = end - d
            val ids = IntArray(len) { enc.ids[d + it] }
            val lengths = IntArray(len) { unitLen[enc.unitOf[d + it]] }
            val z = model.logits(ids, lengths)
            // one decision per unit inside the window: mean of its tokens' logits, then log-softmax
            val u0 = enc.unitOf[d]
            val nu = enc.unitOf[end - 1] - u0 + 1
            val sums = DoubleArray(nu * c)
            val cnt = DoubleArray(nu)
            for (t in 0 until len) {
                val u = enc.unitOf[d + t] - u0
                cnt[u] += 1.0
                for (k in 0 until c) sums[u * c + k] += z[t * c + k].toDouble()
            }
            for (t in 0 until len) {
                val u = enc.unitOf[d + t] - u0
                var max = Double.NEGATIVE_INFINITY
                for (k in 0 until c) max = maxOf(max, sums[u * c + k] / cnt[u])
                var s = 0.0
                for (k in 0 until c) s += Math.exp(sums[u * c + k] / cnt[u] - max)
                val lse = max + Math.log(s)
                for (k in 0 until c) total[(d + t) * c + k] += sums[u * c + k] / cnt[u] - lse
                count[d + t] += 1.0
            }
        }
        // mean log-probabilities per unit (units that have tokens, in text order)
        val index = IntArray(nUnits) { -1 }
        val kept = IntList()
        for (u in enc.unitOf) if (index[u] < 0) { index[u] = kept.size; kept.add(u) }
        val kn = kept.size
        val lp = DoubleArray(kn * c)
        val nk = DoubleArray(kn)
        for (t in 0 until n) {
            val i = index[enc.unitOf[t]]
            nk[i] += 1.0
            for (k in 0 until c) lp[i * c + k] += total[t * c + k] / count[t]
        }
        for (i in 0 until kn) for (k in 0 until c) lp[i * c + k] /= nk[i]
        val path = trans.viterbi(lp, kn)
        val utf16 = IntArray(cps.size + 1)
        for (i in cps.indices) utf16[i + 1] = utf16[i] + Character.charCount(cps[i])
        val out = ArrayList<Span>()
        var first = -1
        for (i in 0 until kn) {
            val e = path[i]
            if (e == 0) { first = -1; continue }
            val tag = (e - 1) % 4
            if (tag == 0 || tag == 3) first = i
            if ((tag == 2 || tag == 3) && first >= 0) {
                var a = enc.unitStarts[kept[first]]
                val b = enc.unitEnds[kept[i]]
                while (a < b && isPythonSpace(cps[a])) a++
                var score = Double.POSITIVE_INFINITY
                for (j in first..i) score = minOf(score, 1 - Math.exp(lp[j * c]))
                val start = utf16[a]
                val stop = utf16[b]
                out += Span(start, stop, text.substring(start, stop), header.subtypes[(e - 1) / 4], Math.round(score * 1e4) / 1e4)
                first = -1
            }
        }
        return out
    }

    public companion object {
        /** The name of the bundled model file. */
        public const val MODEL_FILE: String = "secrets-v1.1-int8.bin"

        // An absolute path: it still resolves when R8 renames or moves this class.
        private const val RESOURCE = "/dev/apartejs/secrets/$MODEL_FILE"

        /** Load the bundled model (int8). Load it once and keep the [Secrets]: it is immutable and thread-safe. */
        @JvmStatic
        public fun load(): Secrets {
            val stream = Secrets::class.java.getResourceAsStream(RESOURCE)
                ?: throw SecretsException("the bundled model $MODEL_FILE is missing")
            return stream.use { load(it) }
        }

        /** Load a model file read from a stream (e.g. an Android asset). */
        @JvmStatic
        public fun load(stream: InputStream): Secrets = Secrets(stream.readBytes())

        /** Load a model file from disk. */
        @JvmStatic
        public fun load(file: File): Secrets = Secrets(file.readBytes())
    }
}

/** Length class of a unit: log2 scale of its non-blank code points (0 for blank), capped at the model's classes. */
private fun lengthClass(cps: IntArray, start: Int, end: Int, classes: Int): Int {
    var a = start
    var b = end
    while (a < b && isPythonSpace(cps[a])) a++
    while (b > a && isPythonSpace(cps[b - 1])) b--
    val n = b - a
    return if (n == 0) 0 else minOf(32 - Integer.numberOfLeadingZeros(n), classes - 1)
}

private fun windows(n: Int, size: Int, stride: Int): IntArray {
    if (n <= size) return intArrayOf(0)
    val out = IntList()
    var d = 0
    while (d <= n - size) { out.add(d); d += stride }
    if (out[out.size - 1] + size < n) out.add(n - size)
    return out.toArray()
}

/** Allowed BIOES moves, one subtype per span: state k > 0 is subtype (k-1)/4, tag (k-1)%4 (B, I, E, S). */
private class Transitions(private val c: Int) {
    private val m = DoubleArray(c * c) { -1e9 }
    private val start = DoubleArray(c)
    private val end = DoubleArray(c)

    init {
        fun tag(k: Int) = if (k == 0) -1 else (k - 1) % 4
        fun sub(k: Int) = if (k == 0) -1 else (k - 1) / 4
        fun opens(k: Int) = k == 0 || tag(k) == 0 || tag(k) == 3
        for (j in 0 until c) for (k in 0 until c) {
            val ok = if (tag(j) == 0 || tag(j) == 1) (tag(k) == 1 || tag(k) == 2) && sub(k) == sub(j) else opens(k)
            if (ok) m[j * c + k] = 0.0
        }
        for (k in 0 until c) {
            start[k] = if (opens(k)) 0.0 else -1e9
            end[k] = if (k == 0 || tag(k) == 2 || tag(k) == 3) 0.0 else -1e9
        }
    }

    fun viterbi(logp: DoubleArray, len: Int): IntArray {
        var score = DoubleArray(c) { logp[it] + start[it] }
        val back = IntArray(len * c)
        for (t in 1 until len) {
            val next = DoubleArray(c)
            for (k in 0 until c) {
                var best = Double.NEGATIVE_INFINITY
                var arg = 0
                for (j in 0 until c) { val v = score[j] + m[j * c + k]; if (v > best) { best = v; arg = j } }
                back[t * c + k] = arg
                next[k] = best + logp[t * c + k]
            }
            score = next
        }
        var k = 0
        var best = Double.NEGATIVE_INFINITY
        for (j in 0 until c) if (score[j] + end[j] > best) { best = score[j] + end[j]; k = j }
        val path = IntArray(len)
        path[len - 1] = k
        for (t in len - 1 downTo 1) { k = back[t * c + k]; path[t - 1] = k }
        return path
    }
}
