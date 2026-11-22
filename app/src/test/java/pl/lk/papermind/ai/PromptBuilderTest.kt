package pl.lk.papermind.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lk.papermind.model.QuestionType

class PromptBuilderTest {
    @Test fun containsQuestionsOptionsAndRawText() {
        val p = PromptBuilder.build(page(choiceQ("abc123"), openQ("x9", QuestionType.MATH, "2+2?"), raw = "OCR tekst strony"), "pl")
        assertTrue(p.user.contains("id=Q1 ") && !p.user.contains("abc123"))
        assertTrue(p.user.contains("type=SINGLE_CHOICE"))
        assertTrue(p.user.contains("B) Mitochondrium"))
        assertTrue(p.user.contains("id=Q2 ") && p.user.contains("type=MATH") && p.user.contains("2+2?"))
        assertTrue(p.user.contains("OCR tekst strony"))
        assertTrue(p.system.contains("Polish"))
        assertTrue(p.system.contains("STRICT JSON"))
        assertTrue(p.system.contains("\"answers\""))
        assertTrue(p.system.contains("OCR"))
    }

    @Test fun languageFallsBackToQuestionLanguage() {
        assertTrue(PromptBuilder.build(page(openQ("a")), null).system.contains("language of the question"))
    }

    @Test fun compactIsShorter() {
        val full = PromptBuilder.build(page(openQ("a")), "pl")
        val compact = PromptBuilder.build(page(openQ("a")), "pl", compact = true)
        assertTrue(compact.combined.length < full.combined.length)
    }

    @Test fun rawTextTruncated() {
        val raw = (1..500).joinToString("\n") { "linia numer $it" }
        val p = PromptBuilder.build(page(openQ("a"), raw = raw), "pl", maxRawChars = 200)
        assertFalse(p.user.contains("linia numer 500"))
        assertTrue(p.user.contains("[…]"))
        assertTrue(p.user.length < 800)
    }

    @Test fun zeroBudgetOmitsRawText() {
        val p = PromptBuilder.build(page(openQ("a"), raw = "SEKRET"), "pl", maxRawChars = 0)
        assertFalse(p.user.contains("SEKRET"))
        assertFalse(p.user.contains("FULL PAGE"))
    }

    @Test fun truncateKeepsShortText() = assertEquals("abc", PromptBuilder.truncate(" abc ", 10))

    @Test fun nanoBudgetShrinksWithLongQuestions() {
        val small = GeminiNanoEngine.rawBudget(page(openQ("a")), 4000)
        val big = GeminiNanoEngine.rawBudget(page(openQ("a", text = "x".repeat(3000))), 4000)
        assertTrue(big < small)
        assertTrue(GeminiNanoEngine.rawBudget(page(openQ("a", text = "x".repeat(30000))), 4000) >= 0)
    }
}
