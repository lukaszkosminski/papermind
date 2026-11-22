package pl.lk.papermind.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextNormalizerTest {

    @Test
    fun `fold usuwa polskie diakrytyki`() {
        assertEquals("zazolc gesla jazn", TextNormalizer.fold("Zażółć gęślą jaźń"))
        assertEquals("lodz", TextNormalizer.fold("Łódź"))
    }

    @Test
    fun `squash zostawia tylko litery i cyfry`() {
        assertEquals("pytanie1ktore", TextNormalizer.squash("Pytanie 1. Które?"))
        assertEquals("", TextNormalizer.squash(" .,;!?  "))
    }

    @Test
    fun `tokeny i jaccard`() {
        val a = TextNormalizer.tokens("Który narząd produkuje insulinę?")
        val b = TextNormalizer.tokens("Który narząd produkuje insulinę ?")
        assertEquals(1.0, TextNormalizer.jaccard(a, b), 1e-9)

        val c = TextNormalizer.tokens("Który narząd wydziela żółć?")
        assertTrue(TextNormalizer.jaccard(a, c) in 0.0..0.7)
    }

    @Test
    fun `podobienstwo jest odporne na lamanie linii i interpunkcje`() {
        val a = "Który narząd produkuje insulinę? A. wątroba B. trzustka"
        val b = "Który narząd produkuje insulinę ?\nA. wątroba\nB. trzustka"
        assertTrue(TextNormalizer.similarity(a, b) > 0.98)
    }

    @Test
    fun `podobienstwo roznych tekstow jest niskie`() {
        val a = "Który narząd produkuje insulinę? A. wątroba B. trzustka"
        val b = "Oblicz pole koła o promieniu 3 cm. Wynik podaj w cm2"
        assertTrue(TextNormalizer.similarity(a, b) < 0.55)
    }

    @Test
    fun `levenshtein dziala dla pustych i roznych dlugosci`() {
        assertEquals(1.0, TextNormalizer.levenshteinSimilarity("", ""), 1e-9)
        assertEquals(0.0, TextNormalizer.levenshteinSimilarity("abc", ""), 1e-9)
        assertEquals(0.5, TextNormalizer.levenshteinSimilarity("abcd", "ab"), 1e-9)
    }

    @Test
    fun `jaccard dla pustych zbiorow`() {
        assertEquals(1.0, TextNormalizer.jaccard(emptySet(), emptySet()), 1e-9)
        assertEquals(0.0, TextNormalizer.jaccard(setOf("a"), emptySet()), 1e-9)
    }

    @Test
    fun `normalizeSpace sklada biale znaki`() {
        assertEquals("a b c", TextNormalizer.normalizeSpace("  a \n\t b   c  "))
    }

    @Test
    fun `fold nie gubi cyfr i lacinskich znakow`() {
        val f = TextNormalizer.fold("Zad. 12: àéîõü 100%")
        assertTrue(f.contains("zad. 12"))
        assertTrue(f.contains("aeiou"))
        assertFalse(f.contains("à"))
    }
}
