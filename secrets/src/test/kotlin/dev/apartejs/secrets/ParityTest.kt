// Equality test: the Kotlin runtime must give exactly the spans of the Python reference implementation
// (and of the JavaScript runtime), text by text: same text, same subtype, score within 2e-4.
//
// The references hold secret-shaped strings, so they are not in this repository: they are on Hugging
// Face (https://huggingface.co/apartejs/aparte-secrets/tree/main/references). Point APARTE_SECRETS_REFS
// at a downloaded references_int8.jsonl; the test is reported as skipped otherwise.
//
//     APARTE_SECRETS_REFS=references_int8.jsonl ./gradlew test

package dev.apartejs.secrets

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class ParityTest {
    @Test
    fun givesTheReferenceSpans() {
        val path = System.getenv("APARTE_SECRETS_REFS")
        assumeTrue(path != null, "set APARTE_SECRETS_REFS to a references_int8.jsonl from Hugging Face")
        val secrets = Secrets.load()
        val bad = ArrayList<String>()
        var n = 0
        File(path!!).forEachLine(Charsets.UTF_8) { line ->
            if (line.isBlank()) return@forEachLine
            val m = Json.parse(line) as Map<*, *>
            val text = m["text"] as String
            val cps = codePoints(text)
            // Python offsets count code points
            val ref = (m["spans"] as List<*>).map {
                val s = it as Map<*, *>
                Triple(String(cps, (s["start"] as Double).toInt(), (s["end"] as Double).toInt() - (s["start"] as Double).toInt()),
                    s["subtype"] as String, s["score"] as Double)
            }
            val mine = secrets.detect(text)
            val same = mine.size == ref.size && mine.indices.all { i ->
                mine[i].text == ref[i].first && mine[i].subtype == ref[i].second && Math.abs(mine[i].score - ref[i].third) <= 2e-4
            }
            if (!same && bad.size < 10) bad += "${text.take(60)}\n  ref ${ref}\n  kt  ${mine.map { Triple(it.text, it.subtype, it.score) }}"
            n++
        }
        assertTrue(n > 0, "no reference in $path")
        assertTrue(bad.isEmpty(), "${bad.size}+ of $n texts differ:\n" + bad.joinToString("\n"))
    }
}
