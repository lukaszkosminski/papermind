package pl.lk.papermind.ui.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lk.papermind.model.Box
import pl.lk.papermind.model.OcrFrame
import pl.lk.papermind.model.OcrLine

class TextLocatorTest {

    @Test
    fun locate_singleLineExactMatch() {
        val frame = OcrFrame(
            timestampMs = 1000L,
            lines = listOf(
                OcrLine("Zadanie 1", Box(50f, 100f, 200f, 140f)),
                OcrLine("Ktore organelle produkuja ATP?", Box(50f, 150f, 400f, 190f)),
                OcrLine("A. Rybosomy", Box(80f, 200f, 300f, 240f)),
            ),
        )
        val prepared = PreparedFrame(frame)
        val locator = TextLocator()

        val match = locator.locate(listOf("Które organelle produkują ATP?"), prepared, null)
        assertNotNull(match)
        assertEquals(Box(50f, 150f, 400f, 190f), match!!.box)
        assertTrue(match.score > 0.9f)
    }

    @Test
    fun locate_multiLineSpanMatch() {
        val frame = OcrFrame(
            timestampMs = 1000L,
            lines = listOf(
                OcrLine("Wyjasnij krotko,", Box(50f, 100f, 300f, 140f)),
                OcrLine("czym jest fotosynteza.", Box(50f, 150f, 350f, 190f)),
                OcrLine("Podaj dwa etapy.", Box(50f, 200f, 320f, 240f)),
            ),
        )
        val prepared = PreparedFrame(frame)
        val locator = TextLocator()

        val match = locator.locate(listOf("Wyjaśnij krótko, czym jest fotosynteza."), prepared, null)
        assertNotNull(match)
        assertEquals(Box(50f, 100f, 350f, 190f), match!!.box)
        assertTrue(match.score > 0.9f)
    }

    @Test
    fun locate_prefersCandidateNearFallbackBoxWhenMultipleMatches() {
        val frame = OcrFrame(
            timestampMs = 1000L,
            lines = listOf(
                OcrLine("Mitochondria", Box(50f, 100f, 200f, 140f)),
                OcrLine("Inny tekst", Box(50f, 200f, 200f, 240f)),
                OcrLine("Mitochondria", Box(50f, 800f, 200f, 840f)),
            ),
        )
        val prepared = PreparedFrame(frame)
        val locator = TextLocator()

        // Near the bottom one
        val match = locator.locate(listOf("Mitochondria"), prepared, Box(50f, 790f, 200f, 830f))
        assertNotNull(match)
        assertEquals(Box(50f, 800f, 200f, 840f), match!!.box)
    }

    @Test
    fun locate_returnsNullWhenBelowThreshold() {
        val frame = OcrFrame(
            timestampMs = 1000L,
            lines = listOf(
                OcrLine("Zupelnie inny tekst", Box(50f, 100f, 300f, 140f)),
            ),
        )
        val prepared = PreparedFrame(frame)
        val locator = TextLocator(threshold = 0.6f)

        val match = locator.locate(listOf("Mitochondria produkujące energię"), prepared, null)
        assertNull(match)
    }

    @Test
    fun locate_emptyAlternativesReturnsNull() {
        val frame = OcrFrame(1000L, listOf(OcrLine("Tekst", Box(10f, 10f, 50f, 50f))))
        val locator = TextLocator()
        assertNull(locator.locate(emptyList(), PreparedFrame(frame), null))
        assertNull(locator.locate(listOf("", "  "), PreparedFrame(frame), null))
    }
}
