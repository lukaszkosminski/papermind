package pl.lk.papermind.ocr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class SharpnessTest {
    private fun image(w: Int, h: Int, f: (Int, Int) -> Int): ByteBuffer =
        ByteBuffer.wrap(ByteArray(w * h) { f(it % w, it / w).toByte() })

    @Test fun sharpEdgesScoreHigherThanSmoothGradient() {
        val sharp = image(200, 200) { x, _ -> if ((x / 2) % 2 == 0) 0 else 255 }
        val blurred = image(200, 200) { x, _ -> (x * 255 / 199) }
        assertTrue(Sharpness.score(sharp, 200, 200, 200) > 10 * Sharpness.score(blurred, 200, 200, 200))
    }

    @Test fun flatImageScoresZero() {
        assertTrue(Sharpness.score(image(100, 100) { _, _ -> 128 }, 100, 100, 100) == 0.0)
    }

    @Test fun gateRejectsBlurredFrameAfterSharpOne() {
        val g = Sharpness.Gate(windowMs = 1000, ratio = 0.8)
        assertTrue(g.accept(100.0, 0))
        assertFalse(g.accept(50.0, 100))
        assertTrue(g.accept(95.0, 200))
    }

    @Test fun gateForgetsOldBest() {
        val g = Sharpness.Gate(windowMs = 1000, ratio = 0.8)
        g.accept(100.0, 0)
        assertTrue(g.accept(50.0, 1500))
    }
}
