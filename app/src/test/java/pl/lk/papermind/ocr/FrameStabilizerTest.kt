package pl.lk.papermind.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lk.papermind.model.Box
import pl.lk.papermind.model.DetectedQuestion
import pl.lk.papermind.model.OcrFrame
import pl.lk.papermind.model.OcrLine
import pl.lk.papermind.model.QuestionType

class FrameStabilizerTest {

    private class FakeClock(var now: Long = 0L) {
        fun advance(ms: Long) {
            now += ms
        }
    }

    /** Parser-atrapa: id wynika wprost z znormalizowanego tekstu, dzięki czemu testujemy tylko stabilizator. */
    private val fakeParser = object : QuestionParser {
        override fun parse(frame: OcrFrame): List<DetectedQuestion> {
            val text = frame.fullText.trim()
            val id = text.filter { it.isLetterOrDigit() }.lowercase()
            return listOf(
                DetectedQuestion(
                    id = id,
                    number = null,
                    type = QuestionType.OPEN,
                    text = text,
                    options = emptyList(),
                    box = Box(0f, 0f, 100f, 20f),
                ),
            )
        }
    }

    private fun frame(text: String, ts: Long = 0L, confidence: Float? = null): OcrFrame =
        OcrFrame(
            timestampMs = ts,
            lines = text.split('\n').mapIndexed { i, t ->
                OcrLine(t, Box(0f, i * 30f, 600f, i * 30f + 20f), confidence)
            },
        )

    private fun stabilizer(clock: FakeClock, stabilityMs: Long = 700L, config: FrameStabilizer.Config = FrameStabilizer.Config()) =
        FrameStabilizer(fakeParser, stabilityMs, { clock.now }, config)

    @Test
    fun `emituje dopiero po czasie stabilnosci`() {
        val clock = FakeClock()
        val s = stabilizer(clock)
        val f = frame("1. Pytanie o serce?\nA. tak\nB. nie")

        assertNull(s.onFrame(f))
        clock.advance(300)
        assertNull(s.onFrame(f))
        clock.advance(399)
        assertNull(s.onFrame(f))
        clock.advance(1)
        val page = s.onFrame(f)
        assertNotNull(page)
        assertEquals(1, page!!.questions.size)
        assertEquals("1. Pytanie o serce?\nA. tak\nB. nie", page.rawText)
    }

    @Test
    fun `nie emituje drugi raz tej samej strony`() {
        val clock = FakeClock()
        val s = stabilizer(clock)
        val f = frame("1. Pytanie?\nA. tak\nB. nie")

        s.onFrame(f)
        clock.advance(700)
        assertNotNull(s.onFrame(f))

        clock.advance(2_000)
        assertNull(s.onFrame(f))
        clock.advance(2_000)
        assertNull(s.onFrame(f))
    }

    @Test
    fun `drobne roznice OCR tej samej strony nie powoduja emisji`() {
        val clock = FakeClock()
        val s = stabilizer(clock)
        val f1 = frame("1. Który narząd produkuje insulinę?\nA. wątroba")
        val f2 = frame("1.  Który narząd produkuje insulinę ?\nA. wątroba")

        s.onFrame(f1)
        clock.advance(700)
        assertNotNull(s.onFrame(f2))
        clock.advance(1_000)
        assertNull(s.onFrame(f1))
    }

    @Test
    fun `nowa strona jest emitowana po wlasnym oknie stabilnosci`() {
        val clock = FakeClock()
        val s = stabilizer(clock)
        val pageA = frame("1. Pytanie o serce?\nA. tak\nB. nie")
        val pageB = frame("22. Zupełnie inne zagadnienie z astronomii\nA. Mars\nB. Wenus\nC. Jowisz")

        s.onFrame(pageA)
        clock.advance(700)
        assertNotNull(s.onFrame(pageA))

        clock.advance(500)
        assertNull(s.onFrame(pageB))          // nowa treść — okno startuje od nowa
        clock.advance(699)
        assertNull(s.onFrame(pageB))
        clock.advance(1)
        assertNotNull(s.onFrame(pageB))
    }

    @Test
    fun `ruch kamery nie emituje stron`() {
        val clock = FakeClock()
        val s = stabilizer(clock)
        val a = frame("1. Pytanie o serce?\nA. tak")
        val b = frame("99. Całkiem inny tekst o gwiazdach i planetach\nB. nie")

        repeat(40) {
            assertNull(s.onFrame(a))
            clock.advance(100)
            assertNull(s.onFrame(b))
            clock.advance(100)
        }
    }

    @Test
    fun `rozmyta klatka nie jest emitowana`() {
        val clock = FakeClock()
        val s = stabilizer(clock)
        val blurred = frame("1. Pytanie?\nA. tak\nB. nie", confidence = 0.05f)

        s.onFrame(blurred)
        clock.advance(2_000)
        assertNull(s.onFrame(blurred))
        clock.advance(2_000)
        assertNull(s.onFrame(blurred))
    }

    @Test
    fun `pusta klatka nie jest emitowana`() {
        val clock = FakeClock()
        val s = stabilizer(clock)
        val empty = OcrFrame(0L, emptyList())

        assertNull(s.onFrame(empty))
        clock.advance(5_000)
        assertNull(s.onFrame(empty))
    }

    @Test
    fun `reset pozwala wyemitowac te sama strone ponownie`() {
        val clock = FakeClock()
        val s = stabilizer(clock)
        val f = frame("1. Pytanie?\nA. tak\nB. nie")

        s.onFrame(f)
        clock.advance(700)
        assertNotNull(s.onFrame(f))
        clock.advance(2_000)
        assertNull(s.onFrame(f))

        s.reset()
        assertNull(s.onFrame(f))
        clock.advance(700)
        assertNotNull(s.onFrame(f))
    }

    @Test
    fun `szum OCR nie powoduje ponownej emisji tej samej strony`() {
        val clock = FakeClock()
        val s = stabilizer(clock)
        val a = frame("1. Który narząd produkuje insulinę?\nA. wątroba\nB. trzustka")
        // jedna litera mniej = inny id, ale tekst praktycznie identyczny
        val b = frame("1. Który narząd produkuje insulinę?\nA. wątroba\nB. trzuska")

        s.onFrame(a)
        clock.advance(700)
        assertNotNull(s.onFrame(a))
        clock.advance(700)
        assertNull(s.onFrame(b))
    }

    @Test
    fun `cooldown chroni przed migotaniem OCR`() {
        val clock = FakeClock()
        val s = stabilizer(clock, config = FrameStabilizer.Config(emitCooldownMs = 5_000L))
        val a = frame("1. Pytanie A?\nA. tak")
        val b = frame("2. Pytanie B?\nA. nie")

        s.onFrame(a)
        clock.advance(700)
        assertNotNull(s.onFrame(a))

        // strona B stabilna, ale cooldown jeszcze trwa
        clock.advance(100)
        assertNull(s.onFrame(b))
        clock.advance(700)
        assertNull(s.onFrame(b))
        clock.advance(4_200)
        assertNotNull(s.onFrame(b))
    }

    @Test
    fun `dziala z prawdziwym parserem`() {
        val clock = FakeClock()
        val s = FrameStabilizer(RegexQuestionParser(), 500L, { clock.now })
        val f = frame(
            "1. Który narząd produkuje insulinę?\nA. Wątroba\nB. Trzustka\nC. Nerki",
        )

        assertNull(s.onFrame(f))
        clock.advance(500)
        val page = s.onFrame(f)
        assertNotNull(page)
        assertEquals(1, page!!.questions.size)
        assertEquals("1", page.questions[0].number)
        assertTrue(page.questions[0].options.size == 3)
    }
}
