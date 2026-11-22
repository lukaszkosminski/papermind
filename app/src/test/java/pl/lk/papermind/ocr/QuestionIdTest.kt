package pl.lk.papermind.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionIdTest {

    @Test
    fun `typowe pomylki OCR nie zmieniaja id`() {
        val original = QuestionId.of("Insulina to hormon białkowy produkowany przez trzustkę?", 2)
        // 0/o, 1/l, 5/s, i/l, brak diakrytyków — tak ML Kit potrafi odczytać ten sam tekst
        val noisy = QuestionId.of("In5ulina to h0rmon b1alkowy pr0dukowany przez trzustke?", 2)
        assertEquals(original, noisy)
    }

    @Test
    fun `rn jest rownowazne m`() {
        val a = QuestionId.of("Gruczoł mlekowy jest gruczołem piersiowym", 0)
        val b = QuestionId.of("Gruczoł rnlekowy jest gruczołem piersiowym", 0)
        assertEquals(a, b)
    }

    @Test
    fun `osemka jest rownowazna literze b`() {
        val a = QuestionId.of("Białko zbudowane jest z aminokwasów", 0)
        val b = QuestionId.of("8iałko zbudowane jest z aminokwasów", 0)
        assertEquals(a, b)
    }

    @Test
    fun `diakrytyki spacje i interpunkcja nie zmieniaja id`() {
        val a = QuestionId.of("Który narząd produkuje insulinę?", 4)
        val b = QuestionId.of("KTORY   narzad, produkuje insuline!", 4)
        assertEquals(a, b)
    }

    @Test
    fun `liczba opcji zmienia id`() {
        val open = QuestionId.of("Który narząd produkuje insulinę?", 0)
        val choice = QuestionId.of("Który narząd produkuje insulinę?", 4)
        assertNotEquals(open, choice)
    }

    @Test
    fun `rozna tresc daje rozne id`() {
        assertNotEquals(
            QuestionId.of("Który narząd produkuje insulinę?", 2),
            QuestionId.of("Który narząd produkuje żółć?", 2),
        )
    }

    @Test
    fun `sol rozroznia powtorzone pytania`() {
        val base = QuestionId.of("Co to jest?", 0)
        val second = QuestionId.of("Co to jest?", 0, salt = 1)
        val third = QuestionId.of("Co to jest?", 0, salt = 2)

        assertNotEquals(base, second)
        assertNotEquals(second, third)
        assertEquals(base, QuestionId.of("Co to jest?", 0, salt = 0))
    }

    @Test
    fun `id jest 16-znakowym hexem`() {
        val id = QuestionId.of("Który narząd produkuje insulinę?", 4)
        assertEquals(16, id.length)
        assertTrue(id.all { it in '0'..'9' || it in 'a'..'f' })
    }
}
