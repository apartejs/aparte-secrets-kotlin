package dev.apartejs.secrets

// The forward pass, written as the same arithmetic as the JavaScript runtime, step for step:
// JavaScript computes in Double and rounds to Float32 each time it stores into a Float32Array, so
// this code accumulates in Double and rounds to Float at exactly the same places. Logits are not
// guaranteed identical to the bit (`exp` differs between V8 and each platform's math library), but
// spans are: the tests check them text by text against the references.

/** out[t] = layer norm of x[t], over [d] values per token. */
private fun layerNorm(x: FloatArray, len: Int, d: Int, g: FloatArray, b: FloatArray, out: FloatArray) {
    for (t in 0 until len) {
        val r = t * d
        var mean = 0.0
        for (k in 0 until d) mean += x[r + k]
        mean /= d
        var v = 0.0
        for (k in 0 until d) { val e = x[r + k] - mean; v += e * e }
        v /= d
        val inv = 1 / Math.sqrt(v + 1e-5)
        for (k in 0 until d) out[r + k] = ((x[r + k] - mean) * inv * g[k] + b[k]).toFloat()
    }
}

private const val SQRT2 = 1.4142135623730951

/** Abramowitz-Stegun 7.1.26, the same approximation as the JavaScript runtime. */
private fun erf(x0: Double): Double {
    val s = if (x0 > 0) 1.0 else if (x0 < 0) -1.0 else 0.0
    val x = Math.abs(x0)
    val t = 1 / (1 + 0.3275911 * x)
    val y = 1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * Math.exp(-x * x)
    return s * y
}

private fun gelu(x: Double): Double = 0.5 * x * (1 + erf(x / SQRT2))

/** y[len x n] = x[len x k] · W[n x k]ᵀ + b[n], with the bias added first, as in JavaScript. */
private fun linear(x: FloatArray, len: Int, k: Int, w: FloatArray, b: FloatArray?, n: Int, out: FloatArray) {
    for (t in 0 until len) {
        val xr = t * k
        for (j in 0 until n) {
            var s = if (b != null) b[j].toDouble() else 0.0
            val wr = j * k
            for (i in 0 until k) s += x[xr + i].toDouble() * w[wr + i]
            out[t * n + j] = s.toFloat()
        }
    }
}

internal class Model(header: ModelHeader, private val w: Map<String, FloatArray>) {
    private val dim = header.dim
    private val heads = header.heads
    private val layers = header.layers
    private val embDim = header.dimEmb
    val classes: Int

    init {
        if (dim <= 0 || heads <= 0 || dim % heads != 0 || embDim <= 0) throw SecretsException("malformed model file: inconsistent dimensions")
        val needed = mutableListOf("emb_tok.weight", "proj_emb.weight", "emb_len.weight", "norm.weight", "norm.bias", "head.weight", "head.bias")
        for (c in 0 until layers) {
            for (part in listOf("n1", "att.qkv", "att.output", "n2", "ff.net.0", "ff.net.2")) {
                needed += "blocks.$c.$part.weight"
                needed += "blocks.$c.$part.bias"
            }
        }
        for (name in needed) if (name !in w) throw SecretsException("malformed model file: missing tensor $name")
        classes = header.tensors.first { it.name == "head.weight" }.shape[0]
    }

    private fun t(name: String): FloatArray = w.getValue(name)

    /** [classes] logits per token for one window; every token attends to every token of the window. */
    fun logits(ids: IntArray, lengths: IntArray): FloatArray {
        val len = ids.size
        val d = dim
        val dh = d / heads
        val x = FloatArray(len * d)
        val embTok = t("emb_tok.weight")
        val e = FloatArray(len * embDim)
        for (i in 0 until len) for (k in 0 until embDim) e[i * embDim + k] = embTok[ids[i] * embDim + k]
        linear(e, len, embDim, t("proj_emb.weight"), null, d, x)
        val embLen = t("emb_len.weight")
        for (i in 0 until len) {
            var k = 0
            while (k < d) {                         // sinusoidal positions, relative to the window
                val f = Math.exp(k * (-Math.log(10000.0) / d))
                x[i * d + k] = (x[i * d + k] + Math.sin(i * f)).toFloat()
                x[i * d + k + 1] = (x[i * d + k + 1] + Math.cos(i * f)).toFloat()
                k += 2
            }
            for (j in 0 until d) x[i * d + j] = (x[i * d + j].toDouble() + embLen[lengths[i] * d + j]).toFloat()
        }

        val y = FloatArray(len * d)
        val qkv = FloatArray(len * 3 * d)
        val att = FloatArray(len * d)
        val f1 = FloatArray(len * 4 * d)
        val f2 = FloatArray(len * d)
        val s = FloatArray(len)
        val scale = Math.sqrt(dh.toDouble())
        for (c in 0 until layers) {
            val p = "blocks.$c."
            layerNorm(x, len, d, t(p + "n1.weight"), t(p + "n1.bias"), y)
            linear(y, len, d, t(p + "att.qkv.weight"), t(p + "att.qkv.bias"), 3 * d, qkv)
            att.fill(0f)
            for (h in 0 until heads) {
                val o = h * dh
                for (i in 0 until len) {
                    var mx = Double.NEGATIVE_INFINITY
                    for (j in 0 until len) {
                        var dot = 0.0
                        for (q in 0 until dh) dot += qkv[i * 3 * d + o + q].toDouble() * qkv[j * 3 * d + d + o + q]
                        s[j] = (dot / scale).toFloat()
                        if (s[j] > mx) mx = s[j].toDouble()
                    }
                    var sum = 0.0
                    for (j in 0 until len) { s[j] = Math.exp(s[j] - mx).toFloat(); sum += s[j] }
                    for (j in 0 until len) {
                        val pr = s[j] / sum
                        for (q in 0 until dh) {
                            att[i * d + o + q] = (att[i * d + o + q] + pr * qkv[j * 3 * d + 2 * d + o + q]).toFloat()
                        }
                    }
                }
            }
            linear(att, len, d, t(p + "att.output.weight"), t(p + "att.output.bias"), d, f2)
            for (i in 0 until len * d) x[i] = (x[i].toDouble() + f2[i]).toFloat()
            layerNorm(x, len, d, t(p + "n2.weight"), t(p + "n2.bias"), y)
            linear(y, len, d, t(p + "ff.net.0.weight"), t(p + "ff.net.0.bias"), 4 * d, f1)
            for (i in 0 until len * 4 * d) f1[i] = gelu(f1[i].toDouble()).toFloat()
            linear(f1, len, 4 * d, t(p + "ff.net.2.weight"), t(p + "ff.net.2.bias"), d, f2)
            for (i in 0 until len * d) x[i] = (x[i].toDouble() + f2[i]).toFloat()
        }
        layerNorm(x, len, d, t("norm.weight"), t("norm.bias"), y)
        val z = FloatArray(len * classes)
        linear(y, len, d, t("head.weight"), t("head.bias"), classes, z)
        return z
    }
}
