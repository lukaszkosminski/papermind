package pl.lk.papermind.ui.crossword

import pl.lk.papermind.model.Box
import pl.lk.papermind.model.CrosswordGrid
import pl.lk.papermind.model.OcrFrame
import kotlin.math.abs

/**
 * Śledzi położenie siatki krzyżówki w kolejnych klatkach kamery:
 * przelicza komórki i sloty przez przesunięcie/skalę względem bieżącej klatki OCR,
 * dopasowując po numerkach komórek lub opierając się na [CrosswordGrid.box] z najnowszej strony.
 */
class GridTracker(
    private val smoothing: Float = 0.6f,
) {
    private var trackedBox: Box? = null

    fun reset() {
        trackedBox = null
    }

    fun update(grid: CrosswordGrid?, frame: OcrFrame): CrosswordGrid? {
        if (grid == null) {
            trackedBox = null
            return null
        }

        // Dopasowanie przesunięcia po numerkach komórek
        val shifts = mutableListOf<Pair<Float, Float>>()
        val numberedCells = grid.cells.filter { it.number != null }

        for (cell in numberedCells) {
            val numStr = cell.number.toString()
            val maxDist = maxOf(cell.box.width, cell.box.height) * 1.5f
            // Szukamy linii w klatce będącej numerkiem w okolicy tej komórki
            val match = frame.lines.firstOrNull { line ->
                val text = line.text.trim()
                (text == numStr || text.startsWith(numStr)) &&
                    abs(line.box.left - cell.box.left) < maxDist &&
                    abs(line.box.top - cell.box.top) < maxDist
            }
            if (match != null) {
                shifts += (match.box.left - cell.box.left) to (match.box.top - cell.box.top)
            }
        }

        val targetBox = if (shifts.isNotEmpty()) {
            val avgDx = shifts.map { it.first }.average().toFloat()
            val avgDy = shifts.map { it.second }.average().toFloat()
            Box(
                left = grid.box.left + avgDx,
                top = grid.box.top + avgDy,
                right = grid.box.right + avgDx,
                bottom = grid.box.bottom + avgDy,
            )
        } else {
            grid.box
        }

        val prev = trackedBox
        val currentBox = if (prev == null) {
            targetBox
        } else {
            smooth(prev, targetBox)
        }
        trackedBox = currentBox

        val scaleX = if (grid.box.width > 0f) currentBox.width / grid.box.width else 1f
        val scaleY = if (grid.box.height > 0f) currentBox.height / grid.box.height else 1f

        fun transform(b: Box) = Box(
            left = currentBox.left + (b.left - grid.box.left) * scaleX,
            top = currentBox.top + (b.top - grid.box.top) * scaleY,
            right = currentBox.left + (b.right - grid.box.left) * scaleX,
            bottom = currentBox.top + (b.bottom - grid.box.top) * scaleY,
        )

        val updatedCells = grid.cells.map { it.copy(box = transform(it.box)) }
        val cellMap = updatedCells.associateBy { it.row to it.col }
        val updatedSlots = grid.slots.map { slot ->
            slot.copy(cells = slot.cells.map { cellMap[it.row to it.col] ?: it })
        }

        return CrosswordGrid(
            rows = grid.rows,
            cols = grid.cols,
            box = currentBox,
            cells = updatedCells,
            slots = updatedSlots,
        )
    }

    private fun smooth(prev: Box, target: Box): Box {
        val jump = maxOf(abs(target.left - prev.left), abs(target.top - prev.top))
        if (jump > maxOf(prev.width, prev.height, 80f) * 1.5f) return target
        fun l(a: Float, b: Float) = a + (b - a) * smoothing
        return Box(l(prev.left, target.left), l(prev.top, target.top), l(prev.right, target.right), l(prev.bottom, target.bottom))
    }
}
