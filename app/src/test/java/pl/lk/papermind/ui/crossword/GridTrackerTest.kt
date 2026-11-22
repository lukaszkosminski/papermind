package pl.lk.papermind.ui.crossword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import pl.lk.papermind.model.Box
import pl.lk.papermind.model.CrosswordGrid
import pl.lk.papermind.model.CrosswordSlot
import pl.lk.papermind.model.Direction
import pl.lk.papermind.model.GridCell
import pl.lk.papermind.model.OcrFrame
import pl.lk.papermind.model.OcrLine

class GridTrackerTest {

    private val cell00 = GridCell(0, 0, Box(100f, 100f, 200f, 200f), blocked = false, number = 1)
    private val cell01 = GridCell(0, 1, Box(200f, 100f, 300f, 200f), blocked = false, number = 2)

    private val grid = CrosswordGrid(
        rows = 1,
        cols = 2,
        box = Box(100f, 100f, 300f, 200f),
        cells = listOf(cell00, cell01),
        slots = listOf(CrosswordSlot(1, Direction.ACROSS, listOf(cell00, cell01))),
    )

    @Test
    fun update_nullGrid_returnsNull() {
        val tracker = GridTracker()
        val frame = OcrFrame(100L, emptyList())
        assertNull(tracker.update(null, frame))
    }

    @Test
    fun update_noMatchingLines_returnsOriginalGrid() {
        val tracker = GridTracker()
        val frame = OcrFrame(100L, emptyList())
        val tracked = tracker.update(grid, frame)
        assertNotNull(tracked)
        assertEquals(grid.box, tracked!!.box)
        assertEquals(2, tracked.cells.size)
    }

    @Test
    fun update_withShiftedNumberLines_shiftsGrid() {
        // Shift dx = 15, dy = 10
        val tracker = GridTracker(smoothing = 1.0f) // immediate jump for clean test assertion
        val frame = OcrFrame(
            timestampMs = 100L,
            lines = listOf(
                OcrLine("1", Box(115f, 110f, 135f, 130f), 0.99f),
                OcrLine("2", Box(215f, 110f, 235f, 130f), 0.99f),
            ),
        )

        val tracked = tracker.update(grid, frame)
        assertNotNull(tracked)
        assertEquals(115f, tracked!!.box.left, 0.01f)
        assertEquals(110f, tracked.box.top, 0.01f)
        assertEquals(315f, tracked.box.right, 0.01f)
        assertEquals(210f, tracked.box.bottom, 0.01f)

        // Verify cells shifted by the same amount
        val c00 = tracked.cells[0]
        assertEquals(115f, c00.box.left, 0.01f)
        assertEquals(110f, c00.box.top, 0.01f)
    }

    @Test
    fun reset_clearsTrackingState() {
        val tracker = GridTracker()
        tracker.update(grid, OcrFrame(100L, emptyList()))
        tracker.reset()
        val tracked = tracker.update(grid, OcrFrame(200L, emptyList()))
        assertNotNull(tracked)
        assertEquals(grid.box, tracked!!.box)
    }
}
