import dev.apartejs.secrets.Secrets

fun main() {
    val secrets = Secrets.load()
    val found = secrets.detect("the wifi password is Sunflower77# for the guests")
    println("Kotlin ${KotlinVersion.CURRENT}: $found")
    check(found.any { it.text.startsWith("Sunflower77") && it.subtype == "password" }) { "unexpected spans: $found" }
}
