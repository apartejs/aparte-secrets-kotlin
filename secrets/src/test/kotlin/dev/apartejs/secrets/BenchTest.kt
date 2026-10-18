// Speed on one CPU core, measured exactly as the JavaScript benchmark (packages/secrets/bench/bench.mjs
// in aparte-secrets-model): the chat messages of the aparte-secrets-gold benchmark one by one, then its
// code texts concatenated into one file; median of three rounds.
//
// gold.jsonl is on Hugging Face (https://huggingface.co/datasets/apartejs/aparte-secrets-gold); the test
// is reported as skipped without it.
//
//     APARTE_SECRETS_GOLD=gold.jsonl ./gradlew test --tests '*BenchTest*' -i

package dev.apartejs.secrets

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test

class BenchTest {
    @Test
    fun speed() {
        val path = System.getenv("APARTE_SECRETS_GOLD")
        assumeTrue(path != null, "set APARTE_SECRETS_GOLD to a gold.jsonl from Hugging Face")
        println(bench(Secrets.load(), File(path!!).readLines(Charsets.UTF_8)))
    }
}

/** The JavaScript benchmark's measure, as one line of JSON. */
internal fun bench(secrets: Secrets, lines: List<String>): String {
    val gold = lines.filter { it.isNotBlank() }.map { Json.parse(it) as Map<*, *> }
    val chat = gold.filter { (it["group"] as String).startsWith("chat") }.map { it["text"] as String }
    val code = gold.filter { it["group"] == "code" }.joinToString("\n") { it["text"] as String }
    fun median(rounds: Int, f: () -> Unit): Double =
        List(rounds) { val t0 = System.nanoTime(); f(); (System.nanoTime() - t0) / 1e6 }.sorted()[rounds / 2]
    for (t in chat.take(20)) secrets.detect(t)                // JIT warm-up
    val chatMs = median(3) { for (t in chat) secrets.detect(t) }
    val codeMs = median(3) { secrets.detect(code) }
    return "{\"runtime\": \"${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}\", " +
        "\"chat\": {\"texts\": ${chat.size}, \"mean_chars\": ${chat.sumOf { it.length } / chat.size}, " +
        "\"ms_per_text\": ${"%.2f".format(java.util.Locale.ROOT, chatMs / chat.size)}}, " +
        "\"code\": {\"lines\": ${code.count { it == '\n' } + 1}, \"chars\": ${code.length}, " +
        "\"us_per_char\": ${"%.1f".format(java.util.Locale.ROOT, codeMs * 1000 / code.length)}}}"
}
