package pl.lk.papermind.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionPatternsTest {

    @Test
    fun `rozne warianty numeracji pytan`() {
        assertEquals("1", QuestionPatterns.matchQuestionStart("1. Które z poniższych?")?.number)
        assertEquals("2", QuestionPatterns.matchQuestionStart("2) Wskaż odpowiedź")?.number)
        assertEquals("3", QuestionPatterns.matchQuestionStart("3: Podaj przykład")?.number)
        assertEquals("4", QuestionPatterns.matchQuestionStart("Pytanie 4. Co to jest?")?.number)
        assertEquals("5", QuestionPatterns.matchQuestionStart("Pyt. 5: Wyjaśnij")?.number)
        assertEquals("6", QuestionPatterns.matchQuestionStart("Zad. 6. Oblicz")?.number)
        assertEquals("7", QuestionPatterns.matchQuestionStart("Zadanie 7: Opisz")?.number)
        assertEquals("8", QuestionPatterns.matchQuestionStart("Question 8. Which is true?")?.number)
        assertEquals("9", QuestionPatterns.matchQuestionStart("Zadanie nr 9. Uzupełnij")?.number)
        assertEquals("10", QuestionPatterns.matchQuestionStart("(10) Podaj wzór")?.number)

        assertEquals("Które z poniższych?", QuestionPatterns.matchQuestionStart("1. Które z poniższych?")?.rest)
    }

    @Test
    fun `daty i liczby nie sa numeracja pytan`() {
        assertNull(QuestionPatterns.matchQuestionStart("2024. Rok ważnych zmian"))
        assertNull(QuestionPatterns.matchQuestionStart("3.5 g glukozy"))
        assertNull(QuestionPatterns.matchQuestionStart("12,5% roztworu"))
        assertNull(QuestionPatterns.matchQuestionStart("Temperatura 36.6 stopnia"))
    }

    @Test
    fun `poczatek tresci pytania`() {
        val text = "Zad. 12: Oblicz wartość"
        val start = QuestionPatterns.questionStemStart(text)
        assertEquals("Oblicz wartość", text.substring(start).trim())
    }

    @Test
    fun `wykrywanie znacznikow opcji`() {
        assertTrue(QuestionPatterns.startsWithOptionMarker("A. Warszawa"))
        assertTrue(QuestionPatterns.startsWithOptionMarker("b) Kraków"))
        assertTrue(QuestionPatterns.startsWithOptionMarker("(c) Gdańsk"))
        assertTrue(QuestionPatterns.startsWithOptionMarker("□ Hel"))
        assertTrue(QuestionPatterns.startsWithOptionMarker("○ Neon"))
        assertFalse(QuestionPatterns.startsWithOptionMarker("Warszawa jest stolicą"))
        assertFalse(QuestionPatterns.startsWithOptionMarker("A witamina"))
    }

    @Test
    fun `pary etykieta wypelniacz`() {
        val pairs = QuestionPatterns.matchFormPairs("Imię: ______ Nazwisko: ______")
        assertEquals(listOf("Imię", "Nazwisko"), pairs.map { it.label })

        assertEquals(listOf("Data"), QuestionPatterns.matchFormPairs("Data: ......").map { it.label })
        assertEquals(listOf("PESEL"), QuestionPatterns.matchFormPairs("PESEL: ▢▢▢▢").map { it.label })

        // to nie jest formularz — zwykłe zdanie z dwukropkiem
        assertTrue(QuestionPatterns.matchFormPairs("Uwaga: to jest ważne zdanie").isEmpty())
    }

    @Test
    fun `pole formularza bez wypelniacza`() {
        assertEquals("Imię", QuestionPatterns.matchBareField("Imię:"))
        assertEquals("Data urodzenia", QuestionPatterns.matchBareField("Data urodzenia:"))
        assertNull(QuestionPatterns.matchBareField("Dlaczego:"))
        assertNull(QuestionPatterns.matchBareField("Które z poniższych:"))
        assertNull(QuestionPatterns.matchBareField("To nie jest pole"))
    }

    @Test
    fun `etykieta z wypelniaczem bez dwukropka`() {
        assertEquals("Podpis", QuestionPatterns.matchLabelWithFill("Podpis ........"))
        assertNull(QuestionPatterns.matchLabelWithFill("Podpis"))
    }

    @Test
    fun `wykrywanie typu wielokrotnego wyboru`() {
        assertTrue(QuestionPatterns.isMultiHint("Zaznacz wszystkie poprawne odpowiedzi"))
        assertTrue(QuestionPatterns.isMultiHint("Pytanie wielokrotnego wyboru"))
        assertTrue(QuestionPatterns.isMultiHint("Select all that apply"))
        assertTrue(QuestionPatterns.isMultiHint("more than one answer"))
        assertFalse(QuestionPatterns.isMultiHint("Zaznacz jedną poprawną odpowiedź"))
        assertTrue(QuestionPatterns.isSingleHint("Zaznacz jedną poprawną odpowiedź"))
    }

    @Test
    fun `wykrywanie matematyki`() {
        assertTrue(QuestionPatterns.isMath("Oblicz 2 + 2"))
        assertTrue(QuestionPatterns.isMath("Rozwiąż równanie 2x + 7 = 15"))
        assertTrue(QuestionPatterns.isMath("12 × 8 = ?"))
        assertTrue(QuestionPatterns.isMath("√16 + 3"))
        assertTrue(QuestionPatterns.isMath("Ile wynosi 15% z 200?"))
        assertFalse(QuestionPatterns.isMath("Który narząd produkuje insulinę?"))
        assertFalse(QuestionPatterns.isMath("Wymień objawy zawału serca"))
    }

    @Test
    fun `pytanie vs naglowek`() {
        assertTrue(QuestionPatterns.looksLikeQuestion("Który narząd produkuje insulinę?"))
        assertTrue(QuestionPatterns.looksLikeQuestion("Wymień narządy układu pokarmowego"))
        assertTrue(QuestionPatterns.looksLikeQuestion("Uzupełnij tabelę:"))
        assertTrue(QuestionPatterns.looksLikeQuestion("Oblicz 2+2"))
        assertFalse(QuestionPatterns.looksLikeQuestion("TEST Z BIOLOGII — GRUPA A"))
        assertFalse(QuestionPatterns.looksLikeQuestion("Imię i nazwisko"))
    }

    @Test
    fun `instrukcja wyboru odpowiedzi nie jest pytaniem`() {
        assertTrue(QuestionPatterns.isAnswerInstruction("Zaznacz wszystkie poprawne odpowiedzi."))
        assertTrue(QuestionPatterns.isAnswerInstruction("Wybierz jedną odpowiedź."))
        assertTrue(QuestionPatterns.isAnswerInstruction("Select the correct answer."))
        assertFalse(QuestionPatterns.isAnswerInstruction("Która odpowiedź jest prawidłowa?"))
        assertFalse(QuestionPatterns.isAnswerInstruction("Wyjaśnij, dlaczego niebo jest niebieskie."))
    }

    @Test
    fun `mapowanie cyfr mylonych przez OCR`() {
        assertEquals('B', QuestionPatterns.digitToLetter('8'))
        assertEquals('A', QuestionPatterns.digitToLetter('4'))
        assertNull(QuestionPatterns.digitToLetter('3'))
    }

    @Test
    fun `wypelniacz w osobnej linii`() {
        assertTrue(QuestionPatterns.isFillerOnly("..........."))
        assertTrue(QuestionPatterns.isFillerOnly("____"))
        assertFalse(QuestionPatterns.isFillerOnly("Podpis ........"))
        assertFalse(QuestionPatterns.isFillerOnly(".."))
    }

    @Test
    fun `id pytania jest skroconym sha1`() {
        val id = QuestionId.of("Który narząd produkuje insulinę?", listOf("A", "B", "C", "D"))
        assertEquals(16, id.length)
        assertTrue(id.all { it in '0'..'9' || it in 'a'..'f' })
        assertNotNull(id)
    }
}
