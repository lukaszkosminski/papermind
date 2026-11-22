package pl.lk.papermind.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lk.papermind.model.Box
import pl.lk.papermind.model.OcrFrame
import pl.lk.papermind.model.OcrLine
import pl.lk.papermind.model.QuestionType

/**
 * Realistyczne próbki (PL/EN): testy medyczne, formularze, matematyka, błędy OCR
 * ("B," zamiast "B.", "8)" zamiast "B)"), dwie kolumny opcji, brak numeracji.
 */
class RegexQuestionParserTest {

    private val parser = RegexQuestionParser()

    // ------------------------------------------------------------------ pomocniki

    private fun line(
        text: String,
        top: Float,
        left: Float = 20f,
        height: Float = 24f,
        width: Float = (text.length * 9f).coerceAtLeast(40f),
        confidence: Float? = null,
    ) = OcrLine(text, Box(left, top, left + width, top + height), confidence)

    /** Strona z liniami układanymi pionowo co [step]. */
    private fun page(vararg lines: String, step: Float = 30f, height: Float = 24f): OcrFrame {
        val ocr = lines.mapIndexed { i, t -> line(t, top = 100f + i * step, height = height) }
        return OcrFrame(timestampMs = 1_000L, lines = ocr)
    }

    // ------------------------------------------------------------------ podstawy

    @Test
    fun `parsuje numerowane pytanie z czterema opcjami`() {
        val qs = parser.parse(
            page(
                "1. Które z poniższych stwierdzeń jest prawdziwe?",
                "A. Mitochondrium jest miejscem syntezy ATP",
                "B. Rybosomy odpowiadają za trawienie",
                "C. Aparat Golgiego syntetyzuje DNA",
                "D. Lizosomy produkują ATP",
            ),
        )

        assertEquals(1, qs.size)
        val q = qs.single()
        assertEquals("1", q.number)
        assertEquals(QuestionType.SINGLE_CHOICE, q.type)
        assertEquals("Które z poniższych stwierdzeń jest prawdziwe?", q.text)
        assertEquals(listOf("A", "B", "C", "D"), q.options.map { it.label })
        assertTrue(q.options.first().text.startsWith("Mitochondrium"))
        assertEquals(100f, q.box.top, 0.1f)
        assertEquals(244f, q.box.bottom, 0.1f)
    }

    @Test
    fun `opcje rozbite przez OCR na osobne bloki w jednej linii`() {
        val lines = listOf(
            line("2. Stolica Polski to:", top = 100f, width = 200f),
            line("A. Warszawa", top = 140f, left = 20f, width = 110f),
            line("B. Kraków", top = 140f, left = 300f, width = 100f),
            line("C. Gdańsk", top = 140f, left = 520f, width = 100f),
        )
        val q = parser.parse(OcrFrame(1L, lines)).single()

        assertEquals(QuestionType.SINGLE_CHOICE, q.type)
        assertEquals(listOf("A", "B", "C"), q.options.map { it.label })
        assertEquals("Warszawa", q.options[0].text)
        assertEquals("Kraków", q.options[1].text)
        assertEquals("Gdańsk", q.options[2].text)
        assertTrue(q.options[0].box.left < q.options[1].box.left)
        assertTrue(q.options[1].box.right <= q.options[2].box.left + 1f)
    }

    @Test
    fun `kilka opcji w jednej linii z tekstem pytania`() {
        val q = parser.parse(page("3. Który pierwiastek jest metalem? A. Tlen B. Żelazo C. Siarka")).single()

        assertEquals("3", q.number)
        assertEquals("Który pierwiastek jest metalem?", q.text)
        assertEquals(listOf("A", "B", "C"), q.options.map { it.label })
        assertEquals(listOf("Tlen", "Żelazo", "Siarka"), q.options.map { it.text })
    }

    // ------------------------------------------------------------------ błędy OCR

    @Test
    fun `toleruje przecinek zamiast kropki i osemke zamiast litery B`() {
        val q = parser.parse(
            page(
                "4. Który narząd produkuje insulinę?",
                "A. Wątroba",
                "8) Trzustka",
                "C, Nerki",
                "D. Śledziona",
            ),
        ).single()

        assertEquals(listOf("A", "B", "C", "D"), q.options.map { it.label })
        assertEquals("Trzustka", q.options.first { it.label == "B" }.text)
        assertEquals("Nerki", q.options.first { it.label == "C" }.text)
    }

    @Test
    fun `cyfrowa numeracja kolejnego pytania nie staje sie opcja`() {
        val qs = parser.parse(
            page(
                "1. Pytanie pierwsze?",
                "A. Ala",
                "B. Ola",
                "2. Pytanie drugie?",
                "A. Ewa",
                "B. Iza",
            ),
        )

        assertEquals(2, qs.size)
        assertEquals("2", qs[1].number)
        assertEquals(listOf("A", "B"), qs[1].options.map { it.label })
        assertEquals("Pytanie drugie?", qs[1].text)
    }

    // ------------------------------------------------------------------ typy

    @Test
    fun `wielokrotny wybor wykrywany po instrukcji`() {
        val qs = parser.parse(
            page(
                "Zaznacz wszystkie poprawne odpowiedzi.",
                "5. Które witaminy są rozpuszczalne w tłuszczach?",
                "A. Witamina A",
                "B. Witamina C",
                "C. Witamina D",
                "D. Witamina B12",
            ),
        )

        assertEquals(1, qs.size)
        assertEquals(QuestionType.MULTI_CHOICE, qs.single().type)
    }

    @Test
    fun `wielokrotny wybor po angielsku`() {
        val qs = parser.parse(
            page(
                "6. Which of the following are noble gases?",
                "Select all that apply.",
                "A. Helium",
                "B. Oxygen",
                "C. Neon",
                "D. Argon",
            ),
        )
        assertEquals(QuestionType.MULTI_CHOICE, qs.single().type)
    }

    @Test
    fun `pole formularza z dwiema etykietami w jednej linii`() {
        val qs = parser.parse(
            OcrFrame(
                1L,
                listOf(
                    line("Imię: ____________ Nazwisko: ____________", top = 100f, width = 560f),
                    line("Data urodzenia: ......", top = 140f, width = 240f),
                ),
            ),
        )

        assertEquals(3, qs.size)
        assertTrue(qs.all { it.type == QuestionType.FORM_FIELD })
        assertEquals("Imię:", qs[0].text)
        assertEquals("Nazwisko:", qs[1].text)
        assertEquals("Data urodzenia:", qs[2].text)
        assertNull(qs[0].number)
        // boxy pól rozdzielone proporcjonalnie wg pozycji znaków
        assertTrue(qs[0].box.right <= qs[1].box.left + 2f)
    }

    @Test
    fun `pole formularza z etykieta i wypelniaczem w osobnych liniach OCR`() {
        val qs = parser.parse(
            page(
                "Imię i nazwisko:",
                "....................................",
                "PESEL:",
            ),
        )

        assertEquals(2, qs.size)
        assertEquals(QuestionType.FORM_FIELD, qs[0].type)
        assertEquals("Imię i nazwisko:", qs[0].text)
        assertEquals(QuestionType.FORM_FIELD, qs[1].type)
        assertEquals("PESEL:", qs[1].text)
    }

    @Test
    fun `pole formularza wewnatrz numerowanego zadania`() {
        val q = parser.parse(
            page(
                "Zad. 3. Wpisz dane pacjenta:",
                "Imię: ______ Nazwisko: ______",
            ),
        ).single()

        assertEquals(QuestionType.FORM_FIELD, q.type)
        assertTrue(q.text.startsWith("Wpisz dane pacjenta:"))
        assertTrue(q.text.contains("Imię:"))
    }

    @Test
    fun `matematyka po czasowniku i po samych operatorach`() {
        val qs = parser.parse(
            page(
                "7. Oblicz pole koła o promieniu 3 cm.",
                "8. Rozwiąż równanie 2x + 7 = 15",
                "9. 12 × 8 = ?",
            ),
        )

        assertEquals(3, qs.size)
        assertTrue(qs.all { it.type == QuestionType.MATH })
    }

    @Test
    fun `pytanie otwarte zajmuje wiele linii`() {
        val q = parser.parse(
            page(
                "10. Wyjaśnij, na czym polega różnica między",
                "mitozą a mejozą.",
            ),
        ).single()

        assertEquals(QuestionType.OPEN, q.type)
        assertEquals("Wyjaśnij, na czym polega różnica między mitozą a mejozą.", q.text)
    }

    @Test
    fun `tekst bez numeracji tez jest pytaniem`() {
        val qs = parser.parse(
            page(
                "Które z poniższych stwierdzeń jest prawdziwe?",
                "A. Ziemia jest płaska",
                "B. Ziemia obiega Słońce",
            ),
        )

        assertEquals(1, qs.size)
        val q = qs.single()
        assertNull(q.number)
        assertEquals(QuestionType.SINGLE_CHOICE, q.type)
        assertEquals(2, q.options.size)
    }

    @Test
    fun `naglowek strony nie jest pytaniem`() {
        val qs = parser.parse(
            page(
                "TEST Z BIOLOGII — GRUPA A",
                "1. Który narząd wydziela żółć?",
                "A. Wątroba",
                "B. Trzustka",
            ),
        )

        assertEquals(1, qs.size)
        assertEquals("1", qs.single().number)
    }

    // ------------------------------------------------------------------ opcje specjalne

    @Test
    fun `opcje z checkboxami i kolkami`() {
        val q = parser.parse(
            page(
                "11. Które z wymienionych są gazami szlachetnymi?",
                "□ Hel",
                "○ Neon",
                "☐ Argon",
            ),
        ).single()

        assertEquals(listOf("A", "B", "C"), q.options.map { it.label })
        assertEquals(listOf("Hel", "Neon", "Argon"), q.options.map { it.text })
    }

    @Test
    fun `opcje male litery i nawiasy`() {
        val q = parser.parse(
            page(
                "12. Wybierz poprawną odpowiedź:",
                "a) pierwsza",
                "(b) druga",
                "c. trzecia",
            ),
        ).single()

        assertEquals(listOf("A", "B", "C"), q.options.map { it.label })
        assertEquals(listOf("pierwsza", "druga", "trzecia"), q.options.map { it.text })
    }

    @Test
    fun `dwie kolumny opcji sa czytane poprawnie`() {
        val lines = listOf(
            line("13. Wskaż stolice:", top = 100f, width = 200f),
            line("A. Warszawa", top = 140f, left = 20f, width = 110f),
            line("C. Gdańsk", top = 140f, left = 330f, width = 100f),
            line("B. Kraków", top = 170f, left = 20f, width = 100f),
            line("D. Poznań", top = 170f, left = 330f, width = 100f),
        )
        val q = parser.parse(OcrFrame(1L, lines)).single()

        assertEquals(listOf("A", "B", "C", "D"), q.options.map { it.label })
        assertEquals("Warszawa", q.options[0].text)
        assertEquals("Kraków", q.options[1].text)
        assertEquals("Gdańsk", q.options[2].text)
        assertEquals("Poznań", q.options[3].text)
    }

    @Test
    fun `opcja z wielokropkiem i przecinkiem w tekscie nie jest rozbijana`() {
        val q = parser.parse(
            page(
                "14. Co oznacza skrót EKG?",
                "A. Elektrokardiogram, czyli zapis pracy serca",
                "B. Elektroencefalogram",
            ),
        ).single()

        assertEquals(2, q.options.size)
        assertEquals("Elektrokardiogram, czyli zapis pracy serca", q.options[0].text)
    }

    // ------------------------------------------------------------------ id

    @Test
    fun `id jest stabilne mimo numeracji, spacji i interpunkcji`() {
        val a = parser.parse(
            page(
                "1. Który narząd produkuje insulinę?",
                "A. Wątroba",
                "B. Trzustka",
            ),
        ).single()
        val b = parser.parse(
            page(
                "17.  Który narząd produkuje insulinę ?",
                "A.  Wątroba",
                "B.  Trzustka",
            ),
        ).single()

        assertEquals(a.id, b.id)
        assertEquals("17", b.number)
    }

    @Test
    fun `id zmienia sie gdy zmieni sie tresc lub liczba opcji`() {
        val base = parser.parse(page("1. Pytanie o serce?", "A. tak", "B. nie")).single()
        val otherStem = parser.parse(page("1. Pytanie o płuca?", "A. tak", "B. nie")).single()
        val otherCount = parser.parse(page("1. Pytanie o serce?", "A. tak", "B. może", "C. nie wiem")).single()

        assertNotEquals(base.id, otherStem.id)
        assertNotEquals(base.id, otherCount.id)
    }

    @Test
    fun `id jest odporne na typowe pomylki OCR`() {
        val original = parser.parse(page("1. Insulina to hormon białkowy produkowany przez trzustkę?", "A. tak", "B. nie")).single()
        // 0/o, 1/l, 5/s, 8/b, i/l, brak diakrytyków — typowe pomyłki ML Kit
        val noisy = parser.parse(page("1. In5ulina to h0rmon b1alkowy pr0dukowany przez trzustke?", "A. tak", "B. nie")).single()

        assertEquals(original.id, noisy.id)
    }

    @Test
    fun `powtorzone identyczne pytania dostaja rozne id`() {
        val qs = parser.parse(
            page(
                "1. Co to jest?",
                "2. Co to jest?",
            ),
        )
        assertEquals(2, qs.size)
        assertNotEquals(qs[0].id, qs[1].id)
    }

    @Test
    fun `pusta klatka nie daje pytan`() {
        assertTrue(parser.parse(OcrFrame(0L, emptyList())).isEmpty())
    }

    @Test
    fun `pytanie z numerem w osobnej linii`() {
        val q = parser.parse(
            page(
                "15.",
                "Wymień objawy zawału serca.",
                "A. Ból w klatce",
                "B. Duszność",
            ),
        ).single()

        assertEquals("15", q.number)
        assertEquals(QuestionType.SINGLE_CHOICE, q.type)
        assertEquals("Wymień objawy zawału serca.", q.text)
        assertEquals(2, q.options.size)
    }

    @Test
    fun `pytanie o prawidlowa odpowiedz nie jest instrukcja`() {
        val qs = parser.parse(
            page(
                "16. Która odpowiedź jest prawidłowa?",
                "A. pierwsza",
                "B. druga",
            ),
        )
        assertEquals(1, qs.size)
        assertEquals(QuestionType.SINGLE_CHOICE, qs.single().type)
        assertEquals("Która odpowiedź jest prawidłowa?", qs.single().text)
    }

    @Test
    fun `opcje nie moga zawierac etykiet spoza sekwencji`() {
        val q = parser.parse(
            page(
                "17. Wymień witaminy rozpuszczalne w wodzie:",
                "A. Witamina C",
                "B. Witamina B6",
                "C. Witamina PP",
            ),
        ).single()

        // "Witamina B6" zawiera "B" — nie może zostać potraktowane jako nowa opcja
        assertEquals(3, q.options.size)
        assertEquals("Witamina B6", q.options[1].text)
    }

    @Test
    fun `linia opcji nie jest doklejana do pnia gdy pytanie ma opcje`() {
        val q = parser.parse(
            page(
                "18. Który z zapisów jest poprawny?",
                "A. 2 + 2 = 4",
                "B. 2 + 2 = 5",
            ),
        ).single()

        assertEquals(QuestionType.SINGLE_CHOICE, q.type)
        assertEquals(listOf("2 + 2 = 4", "2 + 2 = 5"), q.options.map { it.text })
        assertFalse(q.text.contains("2 + 2"))
    }

    @Test
    fun `checkbox z etykieta litery to jedna opcja`() {
        val q = parser.parse(
            page(
                "20. Które z wymienionych są gazami szlachetnymi?",
                "□ A. Hel",
                "☐ B. Neon",
            ),
        ).single()

        assertEquals(listOf("A", "B"), q.options.map { it.label })
        assertEquals(listOf("Hel", "Neon"), q.options.map { it.text })
    }

    @Test
    fun `dluga opcja zawinieta do kolejnej linii`() {
        val q = parser.parse(
            page(
                "19. Wskaż prawidłowe stwierdzenie:",
                "A. Bardzo długi tekst opcji, który",
                "przechodzi do następnej linii",
                "B. Krótka opcja",
            ),
        ).single()

        assertEquals(2, q.options.size)
        assertEquals("Bardzo długi tekst opcji, który przechodzi do następnej linii", q.options[0].text)
        assertEquals("Krótka opcja", q.options[1].text)
    }

    // ------------------------------------------------------------------ realistyczne strony

    @Test
    fun `realistyczny test medyczny z dwoma pytaniami`() {
        val qs = parser.parse(
            page(
                "EGZAMIN LEKARSKI — TEST PRÓBNY",
                "Czas: 60 minut",
                "1. U pacjenta z ostrym bólem w klatce piersiowej należy:",
                "A. wykonać EKG",
                "B. podać aspirynę",
                "C. wykonać RTG klatki piersiowej",
                "D. oznaczyć troponinę",
                "2. Zaznacz wszystkie prawidłowe odpowiedzi dotyczące cukrzycy typu 1:",
                "A. jest chorobą autoimmunologiczną",
                "B. występuje najczęściej u dzieci",
                "C. jest związana z insulinoopornością",
                "D. wymaga insulinoterapii",
            ),
        )

        assertEquals(2, qs.size)
        assertEquals("1", qs[0].number)
        assertEquals(QuestionType.SINGLE_CHOICE, qs[0].type)
        assertEquals(4, qs[0].options.size)
        assertEquals("2", qs[1].number)
        assertEquals(QuestionType.MULTI_CHOICE, qs[1].type)
        assertEquals(4, qs[1].options.size)
        assertEquals("oznaczyć troponinę", qs[0].options[3].text)
        assertEquals("wymaga insulinoterapii", qs[1].options[3].text)
    }

    @Test
    fun `realistyczny formularz z naglowkiem`() {
        val qs = parser.parse(
            OcrFrame(
                1L,
                listOf(
                    line("FORMULARZ ZGŁOSZENIA PACJENTA", top = 60f, width = 400f),
                    line("Imię: ______________________", top = 100f, width = 300f),
                    line("Nazwisko: __________________", top = 130f, width = 300f),
                    line("Data: ______________________", top = 160f, width = 300f),
                    line("PESEL: ▢▢▢▢▢▢▢▢▢▢▢", top = 190f, width = 300f),
                ),
            ),
        )

        assertEquals(4, qs.size)
        assertTrue(qs.all { it.type == QuestionType.FORM_FIELD })
        assertEquals(listOf("Imię:", "Nazwisko:", "Data:", "PESEL:"), qs.map { it.text })
        assertTrue(qs.all { it.number == null })
    }

    @Test
    fun `realistyczne zadania otwarte i matematyczne`() {
        val qs = parser.parse(
            page(
                "Zadanie 1. Oblicz wartość wyrażenia 3^2 + 4 × 2",
                "Zadanie 2. Wyjaśnij pojęcie homeostazy.",
                "Zadanie 3. Podaj dwa przykłady enzymów trawiennych.",
            ),
        )

        assertEquals(3, qs.size)
        assertEquals(listOf("1", "2", "3"), qs.map { it.number })
        assertEquals(QuestionType.MATH, qs[0].type)
        assertEquals(QuestionType.OPEN, qs[1].type)
        assertEquals(QuestionType.OPEN, qs[2].type)
    }
}
