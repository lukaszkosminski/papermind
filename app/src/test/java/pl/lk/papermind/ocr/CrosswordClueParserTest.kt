package pl.lk.papermind.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lk.papermind.model.Box
import pl.lk.papermind.model.Direction
import pl.lk.papermind.model.OcrFrame
import pl.lk.papermind.model.OcrLine
import pl.lk.papermind.model.QuestionType

class CrosswordClueParserTest {

    private val parser = RegexQuestionParser()

    private fun page(vararg lines: String): OcrFrame = OcrFrame(
        timestampMs = 0L,
        lines = lines.mapIndexed { i, text ->
            OcrLine(text, Box(20f, 100f + i * 30f, 20f + text.length * 9f, 124f + i * 30f))
        },
    )

    private fun clues(frame: OcrFrame) = parser.parse(frame).filter { it.type == QuestionType.CROSSWORD_CLUE }

    @Test
    fun `polskie naglowki i formaty hasel`() {
        val qs = clues(
            page(
                "POZIOMO",
                "1. Kot (3)",
                "2) Pies (4)",
                "3. Dom 5 liter",
                "PIONOWO:",
                "1. Ala [3]",
                "2. Ola (3) 3. Ewa (3)",
            ),
        )

        assertEquals(6, qs.size)
        val across = qs.filter { it.clue?.direction == Direction.ACROSS }
        val down = qs.filter { it.clue?.direction == Direction.DOWN }
        assertEquals(3, across.size)
        assertEquals(3, down.size)
        assertEquals(listOf(1, 2, 3), across.map { it.clue!!.number })
        assertEquals(listOf(3, 4, 5), across.map { it.clue!!.length })
        assertEquals(listOf("Kot", "Pies", "Dom"), across.map { it.text })
        assertEquals(listOf(1, 2, 3), down.map { it.clue!!.number })
        assertEquals(listOf(3, 3, 3), down.map { it.clue!!.length })
        assertEquals(listOf("Ala", "Ola", "Ewa"), down.map { it.text })
        assertEquals("1", across[0].number)
        assertEquals("1", down[0].number)
    }

    @Test
    fun `angielskie naglowki across i down`() {
        val qs = clues(page("Across", "1. Small (3)", "Down", "1. Big (3)"))

        assertEquals(2, qs.size)
        assertEquals(Direction.ACROSS, qs[0].clue?.direction)
        assertEquals(Direction.DOWN, qs[1].clue?.direction)
        assertEquals(QuestionType.CROSSWORD_CLUE, qs[0].type)
    }

    @Test
    fun `naglowek bez haseł nie tworzy pytania`() {
        val qs = parser.parse(page("1. Który narząd produkuje insulinę?", "A. wątroba", "B. trzustka", "POZIOMO"))

        assertEquals(1, qs.size)
        assertEquals(QuestionType.SINGLE_CHOICE, qs[0].type)
        assertTrue(qs.none { it.type == QuestionType.CROSSWORD_CLUE })
    }

    @Test
    fun `bez naglowka haslo jest zwyklym pytaniem`() {
        val qs = parser.parse(page("1. Kot (3)"))

        assertEquals(1, qs.size)
        assertEquals(QuestionType.OPEN, qs[0].type)
        assertNull(qs[0].clue)
    }

    @Test
    fun `haslo bez podanej dlugosci`() {
        val qs = clues(page("Poziomo:", "4. Rzeka"))

        assertEquals(1, qs.size)
        assertNull(qs[0].clue!!.length)
        assertEquals("Rzeka", qs[0].text)
    }

    @Test
    fun `zawiniete haslo jest doklejane do poprzedniego`() {
        val qs = clues(
            page(
                "Pionowo",
                "5. Najdłuższa rzeka w Polsce",
                "i w Europie (6)",
                "6. Inne hasło (4)",
            ),
        )

        assertEquals(2, qs.size)
        assertEquals("Najdłuższa rzeka w Polsce i w Europie", qs[0].text)
        assertEquals(6, qs[0].clue!!.length)
    }

    @Test
    fun `krzyzowka obok zwyklych pytan`() {
        val qs = parser.parse(
            page(
                "1. Który narząd produkuje insulinę?",
                "A. wątroba",
                "B. trzustka",
                "POZIOMO",
                "1. Kot (3)",
                "2. Pies (4)",
            ),
        )

        assertEquals(3, qs.size)
        assertEquals(QuestionType.SINGLE_CHOICE, qs[0].type)
        assertEquals(2, qs.count { it.type == QuestionType.CROSSWORD_CLUE })
    }

    @Test
    fun `id hasla jest stabilne mimo szumu OCR i rozni sie miedzy kierunkami`() {
        val a = clues(page("Poziomo", "1. Kot (3)")).single()
        // 0/o i dodatkowe spacje — typowy szum OCR
        val b = clues(page("POZIOMO:", "1.  K0t  (3)")).single()
        val down = clues(page("Pionowo", "1. Kot (3)")).single()

        assertEquals(a.id, b.id)
        assertNotEquals(a.id, down.id)
        assertEquals(a.clue!!.number, b.clue!!.number)
    }

    @Test
    fun `kilka haseł w jednej linii ma osobne boxy`() {
        val qs = parser.parse(
            OcrFrame(
                0L,
                listOf(
                    OcrLine("Poziomo: 1. Kot (3) 2. Pies (4)", Box(20f, 100f, 400f, 124f)),
                ),
            ),
        ).filter { it.type == QuestionType.CROSSWORD_CLUE }

        assertEquals(2, qs.size)
        // sąsiadujące hasła dzielą granicę boxa (proporcjonalne cięcie linii)
        assertTrue(qs[0].box.right <= qs[1].box.left + 0.01f)
        assertTrue(qs[0].box.left >= 20f)
        assertTrue(qs[1].box.right <= 400f)
    }

    @Test
    fun `krzyzowka panoramiczna nie wywraca parsera`() {
        val qs = parser.parse(
            page(
                "KRZYŻÓWKA",
                "K O T",
                "O L A",
                "T A K",
                "1. Hasło do wpisania (3)",
            ),
        )

        // brak nagłówka Poziomo/Pionowo → brak haseł, brak wyjątku
        assertTrue(qs.none { it.type == QuestionType.CROSSWORD_CLUE })
    }

    @Test
    fun `naglowek z liczba haseł i wersalikami`() {
        val qs = clues(page("POZIOMO (2)", "1. Kot (3)", "2. Pies (4)"))

        assertEquals(2, qs.size)
        assertEquals(Direction.ACROSS, qs[0].clue?.direction)
    }

    @Test
    fun `linia Poziomo i pionowo w zdaniu nie jest naglowkiem`() {
        val qs = parser.parse(page("Poziomo i pionowo wpisz hasła do krzyżówki", "1. Kot (3)"))

        assertTrue(qs.none { it.type == QuestionType.CROSSWORD_CLUE })
    }
}
