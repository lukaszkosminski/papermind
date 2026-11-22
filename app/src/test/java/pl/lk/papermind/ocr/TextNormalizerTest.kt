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
    fun `kanonizacja OCR ujednolica mylone znaki`() {
        assertEquals(
            TextNormalizer.ocrCanonical("Insulina to hormon białkowy"),
            TextNormalizer.ocrCanonical("In5ulina to h0rmon b1alkowy"),
        )
        assertEquals(
            TextNormalizer.ocrCanonical("gruczoł mlekowy"),
            TextNormalizer.ocrCanonical("gruczoł rnlekowy"),
        )
        assertEquals(
            TextNormalizer.ocrCanonical("białko"),
            TextNormalizer.ocrCanonical("8ialko"),
        )
        // interpunkcja i spacje nie mają znaczenia
        assertEquals(
            TextNormalizer.ocrCanonical("Który narząd?"),
            TextNormalizer.ocrCanonical("ktory   narzad !"),
        )
    }

    @Test
    fun `similarityAtLeast jest zgodne z similarity`() {
        val a = "Który narząd produkuje insulinę? A. wątroba B. trzustka C. nerki"
        val pairs = listOf(
            a to a,
            a to "Który narząd produkuje insulinę? A. wątroba B. trzustka C. nerki ",
            a to "Który narząd wydziela insulinę? A. wątroba B. trzustka C. nerki",
            a to "Który narząd produkuje żółć? A. wątroba B. pęcherzyk C. nerki",
            a to "Oblicz pole koła o promieniu 3 cm. Wynik podaj w cm2",
            "krótki" to "krótki tekst",
            "abc" to "xyz",
        )
        val thresholds = listOf(0.0, 0.55, 0.75, 0.90, 0.985, 1.0)
        for ((x, y) in pairs) {
            val s = TextNormalizer.similarity(x, y)
            for (t in thresholds) {
                assertEquals(
                    "para=\"$x\" / \"$y\", similarity=$s, prog=$t",
                    s >= t,
                    TextNormalizer.similarityAtLeast(x, y, t),
                )
            }
        }
    }

    @Test
    fun `similarity dziala na dlugiej stronie szybko`() {
        // realistyczna strona ~1500 znaków: kilkanaście różnych pytań/opcji (dużo unikalnych słów)
        val base = listOf(
            "U pacjenta z ostrym bólem w klatce piersiowej należy wykonać EKG, oznaczyć troponinę i podać aspirynę.",
            "Które z poniższych stwierdzeń dotyczących cukrzycy typu pierwszego jest prawdziwe? A. autoimmunologia",
            "Oblicz pole powierzchni kuli o promieniu czterech centymetrów, wynik podaj z dokładnością do dwóch miejsc.",
            "Wyjaśnij, na czym polega różnica między mitozą a mejozą oraz wskaż, w jakich tkankach zachodzi każdy proces.",
            "Wymień witaminy rozpuszczalne w tłuszczach i podaj po jednym przykładzie produktu spożywczego ich źródła.",
            "Zaznacz wszystkie poprawne odpowiedzi dotyczące budowy i funkcji układu odpornościowego człowieka dorosłego.",
            "Wymień narządy układu pokarmowego w kolejności, w jakiej pokarm przez nie przechodzi, od jamy ustnej.",
            "Podaj dwa przykłady enzymów trawiennych, miejsce ich wydzielania oraz substrat, na który działają.",
            "Opisz mechanizm wymiany gazowej w pęcherzykach płucnych, uwzględniając rolę hemoglobiny i ciśnień.",
            "Który hormon jest wydzielany przez przysadkę mózgową i wpływa na pracę tarczycy? A. TSH B. insulina C. kortyzol",
            "Wskaż prawidłowe stwierdzenie o dziedziczeniu cech sprzężonych z płcią oraz podaj przykład choroby recesywnej.",
            "Zdefiniuj pojęcie homeostazy i wyjaśnij jej znaczenie dla prawidłowego funkcjonowania organizmu człowieka.",
            "Wymień trzy objawy odwodnienia organizmu oraz dwa sposoby zapobiegania im podczas upałów i wysiłku.",
            "Podaj nazwy dwóch kości kończyny dolnej, które łączą się stawem zawiasowym, i opisz zakres ich ruchu.",
        ).joinToString(" ")

        val variant = StringBuilder(base)
        for (i in intArrayOf(140, 1290)) {
            if (i < variant.length) variant.setCharAt(i, if (variant[i] == 'o') '0' else 'o')
        }
        val noisy = variant.toString()

        // kontrola poprawności: dwie pomyłki to wciąż ta sama strona
        assertTrue("długość strony: ${base.length}", base.length in 1_400..1_800)
        assertTrue(TextNormalizer.similarityAtLeast(base, noisy, 0.90))
        assertTrue(TextNormalizer.similarity(base, noisy) > 0.90)

        repeat(300) { TextNormalizer.similarity(base, noisy) } // rozgrzewka JIT

        val samples = DoubleArray(50)
        for (n in samples.indices) {
            val startNs = System.nanoTime()
            TextNormalizer.similarity(base, noisy)
            samples[n] = (System.nanoTime() - startNs) / 1_000_000.0
        }
        val medianMs = samples.sorted()[samples.size / 2]
        assertTrue("mediana similarity() = $medianMs ms (oczekiwane < 2 ms)", medianMs < 2.0)
    }

    @Test
    fun `fold nie gubi cyfr i lacinskich znakow`() {
        val f = TextNormalizer.fold("Zad. 12: àéîõü 100%")
        assertTrue(f.contains("zad. 12"))
        assertTrue(f.contains("aeiou"))
        assertFalse(f.contains("à"))
    }
}
