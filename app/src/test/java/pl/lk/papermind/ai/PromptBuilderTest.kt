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

    @Test fun nanoBudgetShrinksWithImage() {
        val p = page(openQ("a"))
        assertTrue(GeminiNanoEngine.rawBudget(p, 2500, withImage = true) < GeminiNanoEngine.rawBudget(p, 2500))
    }

    @Test fun nanoBudgetShrinksWithLongQuestions() {
        val small = GeminiNanoEngine.rawBudget(page(openQ("a")), 4000)
        val big = GeminiNanoEngine.rawBudget(page(openQ("a", text = "x".repeat(3000))), 4000)
        assertTrue(big < small)
        assertTrue(GeminiNanoEngine.rawBudget(page(openQ("a", text = "x".repeat(30000))), 4000) >= 0)
    }
}

class CrosswordPromptTest {
    @Test fun cluesCarryNumberDirectionLengthAndPattern() {
        val d2 = Cw.clues().first { it.id == "idD2" }.let { it.copy(clue = it.clue!!.copy(pattern = "T_S_M")) }
        val page = Cw.page().copy(questions = listOf(d2))
        val p = PromptBuilder.build(page, "pl")
        assertTrue(p.user, p.user.contains("type=CROSSWORD_CLUE | no=2 DOWN | length=5 | pattern=T_S_M]"))
        assertTrue(p.system.contains("EXACTLY the stated length"))
        assertTrue(p.system.contains("UPPERCASE"))
        assertTrue(p.system.contains("Ą Ć Ę Ł Ń Ó Ś Ź Ż"))
        assertTrue(p.system.contains("CROSSWORD_CLUE"))
    }

    @Test fun lengthComesFromGridSlotWhenClueHasNone() {
        val p = PromptBuilder.build(Cw.page(), "pl")
        assertTrue(p.user.contains("no=1 ACROSS | length=5]"))
    }

    @Test fun crossingsDescribedBetweenAcrossAndDown() {
        val p = PromptBuilder.build(Cw.page(), "pl")
        // A1 (Q1) litera 3 = D2 (Q5) litera 1
        assertTrue(p.user, p.user.contains("Q1[1 ACROSS] letter 3 = Q5[2 DOWN] letter 1"))
        assertTrue(p.user.contains("CROSSINGS"))
        val lines = PromptBuilder.crossings(Cw.page(), 100)
        assertEquals(9, lines.size) // 3 poziome x 3 pionowe
        assertEquals(3, PromptBuilder.crossings(Cw.page(), 3).size)
    }

    @Test fun noGridMeansNoCrossings() {
        val p = PromptBuilder.build(Cw.page(withGrid = false, withLength = true), "pl")
        assertFalse(p.user.contains("CROSSINGS"))
        assertTrue(p.user.contains("length=5"))
    }

    @Test fun photoMentionedOnlyWhenAttached() {
        assertTrue(PromptBuilder.build(Cw.page(jpeg = byteArrayOf(1, 2, 3)), "pl").user.contains("photo of the page is attached"))
        assertFalse(PromptBuilder.build(Cw.page(), "pl").user.contains("photo"))
    }

    @Test fun ordinaryPageHasNoCrosswordRules() {
        assertFalse(PromptBuilder.build(page(openQ("a")), "pl").system.contains("Crossword rules"))
    }
}
