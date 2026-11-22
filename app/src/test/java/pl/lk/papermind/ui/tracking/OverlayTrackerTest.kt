package pl.lk.papermind.ui.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lk.papermind.model.AnswerOption
import pl.lk.papermind.model.Box
import pl.lk.papermind.model.DetectedQuestion
import pl.lk.papermind.model.OcrFrame
import pl.lk.papermind.model.OcrLine
import pl.lk.papermind.model.QuestionType

class OverlayTrackerTest {

    private val question = DetectedQuestion(
        id = "q1",
        number = "1",
        type = QuestionType.SINGLE_CHOICE,
        text = "Ktore organelle produkuja ATP?",
        options = listOf(
            AnswerOption("A", "Rybosomy", Box(80f, 200f, 300f, 240f)),
            AnswerOption("B", "Mitochondria", Box(80f, 260f, 300f, 300f)),
        ),
        box = Box(50f, 150f, 400f, 190f),
    )

    @Test
    fun update_findsAndSmoothsQuestionAndOptions() {
        val tracker = OverlayTracker(holdMs = 400, fadeMs = 400, smoothing = 0.5f)

        // Frame 1: positions match
        val frame1 = OcrFrame(
            timestampMs = 1000L,
            lines = listOf(
                OcrLine("1. Ktore organelle produkuja ATP?", Box(50f, 150f, 400f, 190f)),
                OcrLine("A. Rybosomy", Box(80f, 200f, 300f, 240f)),
                OcrLine("B. Mitochondria", Box(80f, 260f, 300f, 300f)),
            ),
        )

        val res1 = tracker.update(listOf(question), frame1)
        assertEquals(1, res1.size)
        val tq1 = res1[0]
        val q1 = checkNotNull(tq1.question)
        assertEquals(1f, q1.alpha, 0.001f)
        assertEquals(Box(50f, 150f, 400f, 190f), q1.box)
        assertEquals(2, tq1.options.size)
        val optB = checkNotNull(tq1.options["B"])
        assertEquals(1f, optB.alpha, 0.001f)

        // Frame 2: slightly shifted by +10px (camera wobble) -> smoothed
        val frame2 = OcrFrame(
            timestampMs = 1100L,
            lines = listOf(
                OcrLine("1. Ktore organelle produkuja ATP?", Box(60f, 160f, 410f, 200f)),
                OcrLine("A. Rybosomy", Box(90f, 210f, 310f, 250f)),
                OcrLine("B. Mitochondria", Box(90f, 270f, 310f, 310f)),
            ),
        )
        val res2 = tracker.update(listOf(question), frame2)
        val tq2 = res2[0]
        val q2 = checkNotNull(tq2.question)
        // smoothing = 0.5 -> 50 + (60 - 50)*0.5 = 55, 150 + (160 - 150)*0.5 = 155
        assertEquals(55f, q2.box.left, 0.1f)
        assertEquals(155f, q2.box.top, 0.1f)
    }

    @Test
    fun update_fadesOutWhenTextDisappearsFromFrame() {
        val tracker = OverlayTracker(holdMs = 400, fadeMs = 400)

        // Frame 1: seen at t=1000
        val frame1 = OcrFrame(
            timestampMs = 1000L,
            lines = listOf(OcrLine("1. Ktore organelle produkuja ATP?", Box(50f, 150f, 400f, 190f))),
        )
        tracker.update(listOf(question), frame1)

        // Frame 2: at t=1200 (within holdMs of 400ms) -> still alpha = 1f
        val frameEmpty1 = OcrFrame(timestampMs = 1200L, lines = emptyList())
        val res2 = tracker.update(listOf(question), frameEmpty1)
        assertEquals(1f, res2[0].question!!.alpha, 0.001f)

        // Frame 3: at t=1600 (holdMs passed by 200ms into 400ms fadeMs) -> alpha = 1 - 200/400 = 0.5f
        val frameEmpty2 = OcrFrame(timestampMs = 1600L, lines = emptyList())
        val res3 = tracker.update(listOf(question), frameEmpty2)
        assertEquals(0.5f, res3[0].question!!.alpha, 0.01f)

        // Frame 4: at t=1850 (holdMs + fadeMs passed) -> null (wygaszone)
        val frameEmpty3 = OcrFrame(timestampMs = 1850L, lines = emptyList())
        val res4 = tracker.update(listOf(question), frameEmpty3)
        assertNull(res4[0].question)
    }

    @Test
    fun reset_clearsInternalTracks() {
        val tracker = OverlayTracker()
        val frame1 = OcrFrame(1000L, listOf(OcrLine("1. Ktore organelle produkuja ATP?", Box(50f, 150f, 400f, 190f))))
        tracker.update(listOf(question), frame1)

        tracker.reset()

        // Frame without matching text should now return null immediately
        val frameEmpty = OcrFrame(1100L, emptyList())
        val res = tracker.update(listOf(question), frameEmpty)
        assertNull(res[0].question)
    }
}
