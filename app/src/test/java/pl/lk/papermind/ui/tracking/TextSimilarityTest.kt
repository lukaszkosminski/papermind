package pl.lk.papermind.ui.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSimilarityTest {

    @Test
    fun normalize_handlesPolishDiacriticsAndPunctuation() {
        val input = "Zażółć, gęślą jaźń!? 123."
        val normalized = TextSimilarity.normalize(input)
        assertEquals("zazolc gesla jazn 123", normalized)
    }

    @Test
    fun normalize_collapsesMultipleSpacesAndTrims() {
        val input = "   Pytanie   1.   Które organelle?   "
        val normalized = TextSimilarity.normalize(input)
        assertEquals("pytanie 1 ktore organelle", normalized)
    }

    @Test
    fun normalize_handlesEmptyAndSpecialOnly() {
        assertEquals("", TextSimilarity.normalize(""))
        assertEquals("", TextSimilarity.normalize("--- ... !!! ???"))
    }

    @Test
    fun similarity_exactMatchesReturnOne() {
        assertEquals(1.0f, TextSimilarity.similarity("Mitochondria", "mitochondria"), 0.001f)
        assertEquals(1.0f, TextSimilarity.similarity("Rybosomy!", "rybosomy"), 0.001f)
    }

    @Test
    fun similarity_emptyReturnsZero() {
        assertEquals(0.0f, TextSimilarity.similarity("", "Mitochondria"), 0.001f)
        assertEquals(0.0f, TextSimilarity.similarity("   ", "   "), 0.001f)
    }

    @Test
    fun similarity_closeTextGivesHighScore() {
        // slight OCR typo: "Mitochondia" vs "Mitochondria"
        val score = TextSimilarity.similarity("Mitochondia", "Mitochondria")
        assertTrue("Expected score > 0.75, got $score", score > 0.75f)
    }

    @Test
    fun similarity_differentTextsGiveLowScore() {
        val score = TextSimilarity.similarity("Mitochondria", "Aparat Golgiego")
        assertTrue("Expected score < 0.3, got $score", score < 0.3f)
    }
}
