package dev.apartejs.secrets

/** Raised when a model file cannot be read: not a model, an unsupported format, or a truncated file. */
public class SecretsException(message: String) : Exception(message)

/** The JSON header of a model file: what the model is, and how to run it. */
public class ModelHeader internal constructor(
    public val format: Int?,
    public val task: String?,
    public val name: String?,
    /** The version of the weights, e.g. `"1.1.0"`. */
    public val version: String?,
    public val model: String?,
    /** `"fp32"`, `"int8"`, `"int4"` or `"int3"`. */
    public val precision: String?,
    public val dim: Int,
    public val layers: Int,
    public val heads: Int,
    public val dimEmb: Int,
    public val vocab: Int?,
    public val mergesBase: Int,
    public val maxPos: Int,
    public val window: Int,
    public val stride: Int,
    public val unitLengthClasses: Int,
    /** The kinds of secret the model tells apart: `password`, `api_key`, `token`, `private_key`, `other`. */
    public val subtypes: List<String>,
    internal val byteIds: IntArray,
    internal val tensors: List<Tensor>,
) {
    internal class Tensor(val name: String, val shape: IntArray, val type: String, val bytes: Int)

    override fun toString(): String = "ModelHeader(name=$name, version=$version, model=$model, precision=$precision)"

    internal companion object {
        fun from(json: Any?): ModelHeader {
            val m = json as? Map<*, *> ?: throw SecretsException("malformed model file: the header is not a JSON object")
            fun need(key: String): Any = m[key] ?: throw SecretsException("malformed model file: header has no $key")
            fun int(v: Any?, key: String): Int = (v as? Double)?.toInt() ?: throw SecretsException("malformed model file: $key is not a number")
            fun optInt(key: String): Int? = m[key]?.let { int(it, key) }
            fun optString(key: String): String? = m[key] as? String
            val labels = need("labels") as? Map<*, *> ?: throw SecretsException("malformed model file: labels is not an object")
            return ModelHeader(
                format = optInt("format"),
                task = optString("task"),
                name = optString("name"),
                version = optString("version"),
                model = optString("model"),
                precision = optString("precision"),
                dim = int(need("dim"), "dim"),
                layers = int(need("layers"), "layers"),
                heads = int(need("heads"), "heads"),
                dimEmb = int(need("dim_emb"), "dim_emb"),
                vocab = optInt("vocab"),
                mergesBase = int(need("merges_base"), "merges_base"),
                maxPos = int(need("max_pos"), "max_pos"),
                window = int(need("window"), "window"),
                stride = int(need("stride"), "stride"),
                unitLengthClasses = int(need("unit_length_classes"), "unit_length_classes"),
                subtypes = (labels["subtypes"] as? List<*>)?.map { it as String }
                    ?: throw SecretsException("malformed model file: labels.subtypes is not a list"),
                byteIds = (need("byte_ids") as? List<*>)?.map { int(it, "byte_ids") }?.toIntArray()
                    ?: throw SecretsException("malformed model file: byte_ids is not a list"),
                tensors = (need("tensors") as? List<*>)?.map {
                    val t = it as? Map<*, *> ?: throw SecretsException("malformed model file: bad tensor entry")
                    Tensor(
                        name = t["name"] as? String ?: throw SecretsException("malformed model file: tensor without a name"),
                        shape = (t["shape"] as? List<*>)?.map { s -> int(s, "shape") }?.toIntArray()
                            ?: throw SecretsException("malformed model file: tensor without a shape"),
                        type = t["type"] as? String ?: throw SecretsException("malformed model file: tensor without a type"),
                        bytes = int(t["bytes"], "bytes"),
                    )
                } ?: throw SecretsException("malformed model file: tensors is not a list"),
            )
        }
    }
}

/** IEEE half precision to a Double, exactly as the JavaScript runtime computes it. */
internal fun fp16(u: Int): Double {
    val s = if ((u and 0x8000) != 0) -1.0 else 1.0
    val e = (u shr 10) and 0x1f
    val m = (u and 0x3ff).toDouble()
    if (e == 0) return s * m * Math.scalb(1.0, -24)
    if (e == 31) return if (m != 0.0) Double.NaN else s * Double.POSITIVE_INFINITY
    return s * (1 + m / 1024) * Math.scalb(1.0, e - 15)
}

/** A bit stream, least significant bit first: [n] signed integers of [bits] bits, from [start]. */
internal fun unpackBits(b: ByteArray, start: Int, n: Int, bits: Int): IntArray {
    val out = IntArray(n)
    if (bits == 8) {
        for (i in 0 until n) out[i] = (b[start + i].toInt() and 0xFF) - 128
        return out
    }
    val offset = 1 shl (bits - 1)
    var pos = 0
    for (i in 0 until n) {
        var v = 0
        for (k in 0 until bits) {
            if (((b[start + (pos shr 3)].toInt() shr (pos and 7)) and 1) != 0) v = v or (1 shl k)
            pos++
        }
        out[i] = v - offset
    }
    return out
}

internal class ModelData(val header: ModelHeader, val merges: IntArray, val weights: Map<String, FloatArray>)

/** Parse a model file: its header, its BPE merges, and its weights dequantized to Float. */
internal fun readModel(b: ByteArray): ModelData {
    fun u8(p: Int) = b[p].toInt() and 0xFF
    fun u16(p: Int) = u8(p) or (u8(p + 1) shl 8)
    fun u32(p: Int) = u8(p) or (u8(p + 1) shl 8) or (u8(p + 2) shl 16) or (u8(p + 3) shl 24)
    fun malformed(why: String): Nothing = throw SecretsException("malformed model file: $why")

    if (b.size < 8 || b[0] != 0x4C.toByte() || b[1] != 0x4D.toByte() || b[2] != 0x41.toByte() || b[3] != 0x50.toByte()) {
        throw SecretsException("not an aparte model file")
    }
    var p = 4
    val headerLength = u32(p); p += 4
    if (headerLength < 0 || p.toLong() + headerLength + 4 > b.size) malformed("header runs past the end")
    val json = try {
        Json.parse(String(b, p, headerLength, Charsets.UTF_8))
    } catch (e: IllegalArgumentException) {
        malformed("header: ${e.message}")
    }
    val task = (json as? Map<*, *>)?.get("task")
    if (task != "secrets") throw SecretsException("not an aparte-secrets model (task $task)")
    val header = ModelHeader.from(json)
    p += headerLength
    header.format?.let { if (it != 1) throw SecretsException("model format $it is not supported by this runtime (format 1)") }
    if (header.byteIds.size != 256) malformed("byte_ids must have 256 entries")

    val mergesLength = u32(p); p += 4
    if (mergesLength < 0 || p.toLong() + mergesLength > b.size) malformed("merges run past the end")
    val merges = IntArray(mergesLength / 2) { u16(p + 2 * it) }
    p += mergesLength

    val weights = HashMap<String, FloatArray>()
    for (t in header.tensors) {
        var count = 1
        for (d in t.shape) count *= d
        if (t.bytes < 0 || p.toLong() + t.bytes > b.size) malformed("tensor ${t.name} runs past the end")
        val w = FloatArray(count)
        when (t.type) {
            "f32" -> for (i in 0 until count) w[i] = Float.fromBits(u32(p + 4 * i))
            "f16" -> for (i in 0 until count) w[i] = fp16(u16(p + 2 * i)).toFloat()
            else -> {
                // quantized rows: packed integers, then one fp16 scale per row
                val bits = if (t.type.startsWith("q")) t.type.substring(1).toIntOrNull() else null
                val rows = t.shape.firstOrNull() ?: 0
                if (bits == null || bits !in 1..8 || rows <= 0) malformed("tensor ${t.name} has an unknown type ${t.type}")
                val cols = count / rows
                val packedLength = (count * bits + 7) / 8
                val q = unpackBits(b, p, count, bits)
                for (row in 0 until rows) {
                    val scale = fp16(u16(p + packedLength + 2 * row))
                    for (c in 0 until cols) w[row * cols + c] = (q[row * cols + c] * scale).toFloat()
                }
            }
        }
        weights[t.name] = w
        p += t.bytes
    }
    return ModelData(header, merges, weights)
}
