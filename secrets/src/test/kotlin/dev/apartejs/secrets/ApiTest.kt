// API tests on the bundled model. Made-up passwords only: no secret-shaped string (provider formats)
// lives in this repository. The text-by-text equality test against the Python reader is ParityTest.

package dev.apartejs.secrets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ApiTest {
    private val secrets: Secrets by lazy { Secrets.load() }

    @Test
    fun readsTheModelHeader() {
        assertEquals("secrets", secrets.header.task)
        assertEquals("1.1.0", secrets.header.version)
        assertEquals("int8", secrets.header.precision)
        assertEquals(listOf("password", "api_key", "token", "private_key", "other"), secrets.header.subtypes)
    }

    @Test
    fun findsAPasswordWithItsKindAndOffsets() {
        val text = "the wifi password is Sunflower77# for the guests"
        val s = secrets.detect(text).firstOrNull { it.text.startsWith("Sunflower77") }
        assertNotNull(s, secrets.detect(text).toString())
        assertEquals("password", s.subtype)
        assertEquals(s.text, text.substring(s.start, s.end))
        assertTrue(s.score > 0 && s.score <= 1)
    }

    @Test
    fun anEmptyOrSecretFreeTextGivesNothing() {
        assertEquals(emptyList(), secrets.detect(""))
        assertEquals(emptyList(), secrets.detect("Can you explain how photosynthesis works?"))
    }

    @Test
    fun maskAndUnmaskRoundTrip() {
        val text = "Le mot de passe du wifi c'est Biscotte19&, et pour le NAS aussi Biscotte19&"
        val masked = secrets.mask(text)
        assertFalse(masked.text.contains("Biscotte19&"), "a value found once is masked everywhere")
        assertEquals(1, masked.secrets.size)
        assertEquals(text, secrets.unmask(masked.text, masked.secrets))
    }

    @Test
    fun ignoreAndMinScoreAreAppliedAfterTheModel() {
        val text = "the wifi password is Sunflower77# for the guests"
        val value = secrets.detect(text).first().text
        assertEquals(emptyList(), secrets.detect(text, ignore = listOf(value)))
        assertEquals(emptyList(), secrets.detect(text, ignorePatterns = listOf(Regex("^Sunflower"))))
        assertEquals(emptyList(), secrets.detect(text, minScore = 1.01))
    }

    @Test
    fun offsetsAreUtf16Indices() {
        val text = "😀 the wifi password is Sunflower77# for the guests"
        val s = secrets.detect(text).first { it.text.startsWith("Sunflower77") }
        assertEquals(s.text, text.substring(s.start, s.end))
    }

    @Test
    fun aVeryLongWordAndAVeryLongTextDoNotFail() {
        // a unit of 256+ characters is in the last length class; a text of 256+ tokens spans several windows
        secrets.detect("x".repeat(5000))
        val long = "the wifi password is Sunflower77# for the guests. ".repeat(40)
        assertTrue(secrets.detect(long).count { it.text.startsWith("Sunflower77") } > 1)
    }

    @Test
    fun rejectsAFileThatIsNotAModel() {
        assertFailsWith<SecretsException> { Secrets("not a model".toByteArray()) }
    }
}
