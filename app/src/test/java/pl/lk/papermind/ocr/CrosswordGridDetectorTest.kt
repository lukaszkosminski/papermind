package pl.lk.papermind.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lk.papermind.model.Box
import pl.lk.papermind.model.CrosswordGrid
import pl.lk.papermind.model.Direction
import pl.lk.papermind.model.OcrLine
import java.util.Random
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/** Testy detektora siatki na syntetycznych obrazach (białe/czarne pola, linie, szum, lekki obrót). */
class CrosswordGridDetectorTest {

    private val detector = CrosswordGridDetector()

    // ------------------------------------------------------------------ generator obrazu

    private class Synthetic(
        val luma: ByteArray,
        val width: Int,
        val height: Int,
        val rowStride: Int,
        val gridLeft: Int,
        val gridTop: Int,
        val cellW: Int,
        val cellH: Int,
    )

    private fun renderGrid(
        rows: Int,
        cols: Int,
        cellW: Int = 40,
        cellH: Int = 40,
        lineWidth: Int = 3,
        margin: Int = 30,
        blocked: Set<Pair<Int, Int>> = emptySet(),
        noise: Int = 0,
        rowPadding: Int = 0,
        seed: Long = 7L,
    ): Synthetic {
        val width = cols * cellW + 2 * margin
        val height = rows * cellH + 2 * margin
        val rowStride = width + rowPadding
        val data = ByteArray(rowStride * height) { 245.toByte() }

        fun put(x: Int, y: Int, value: Int) {
            if (x in 0 until width && y in 0 until height) data[y * rowStride + x] = value.toByte()
        }

        for ((r, c) in blocked) {
            for (y in margin + r * cellH + lineWidth / 2 + 1 until margin + (r + 1) * cellH - lineWidth / 2 - 1) {
                for (x in margin + c * cellW + lineWidth / 2 + 1 until margin + (c + 1) * cellW - lineWidth / 2 - 1) {
                    put(x, y, 25)
                }
            }
        }
        for (r in 0..rows) {
            val y = margin + r * cellH
            for (t in -lineWidth / 2..lineWidth / 2) {
                for (x in margin - lineWidth / 2..margin + cols * cellW + lineWidth / 2) put(x, y + t, 30)
            }
        }
        for (c in 0..cols) {
            val x = margin + c * cellW
            for (t in -lineWidth / 2..lineWidth / 2) {
                for (y in margin - lineWidth / 2..margin + rows * cellH + lineWidth / 2) put(x + t, y, 30)
            }
        }
        if (noise > 0) {
            val random = Random(seed)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val v = (data[y * rowStride + x].toInt() and 0xFF) + random.nextInt(2 * noise + 1) - noise
                    data[y * rowStride + x] = v.coerceIn(0, 255).toByte()
                }
            }
            // „brud" w paddingu wiersza — detektor musi go zignorować
            for (y in 0 until height) {
                for (x in width until rowStride) data[y * rowStride + x] = (random.nextInt(256)).toByte()
            }
        }
        return Synthetic(data, width, height, rowStride, margin, margin, cellW, cellH)
    }

    private fun rotate(src: Synthetic, degrees: Float): Synthetic {
        val rad = Math.toRadians(degrees.toDouble())
        val cosA = cos(rad).toFloat()
        val sinA = sin(rad).toFloat()
        val width = ceil(abs(src.width * cosA) + abs(src.height * sinA)).toInt()
        val height = ceil(abs(src.width * sinA) + abs(src.height * cosA)).toInt()
        val out = ByteArray(width * height) { 245.toByte() }
        for (y in 0 until height) {
            val dy = y - height / 2f
            for (x in 0 until width) {
                val dx = x - width / 2f
                val sx = (cosA * dx + sinA * dy + src.width / 2f).toInt()
                val sy = (-sinA * dx + cosA * dy + src.height / 2f).toInt()
                if (sx in 0 until src.width && sy in 0 until src.height) {
                    out[y * width + x] = src.luma[sy * src.rowStride + sx]
                }
            }
        }
        return Synthetic(out, width, height, width, 0, 0, src.cellW, src.cellH)
    }

    private fun detect(s: Synthetic, numbers: List<OcrLine> = emptyList()): CrosswordGrid? =
        detector.detect(s.luma, s.width, s.height, s.rowStride, numbers)

    /** Niezależna implementacja standardowej numeracji — punkt odniesienia dla detektora. */
    private fun expectedNumbering(rows: Int, cols: Int, blocked: Set<Pair<Int, Int>>): Array<IntArray> {
        fun b(r: Int, c: Int) = (r to c) in blocked || r !in 0 until rows || c !in 0 until cols
        val numbers = Array(rows) { IntArray(cols) }
        var next = 1
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (b(r, c)) continue
                val across = b(r, c - 1) && !b(r, c + 1)
                val down = b(r - 1, c) && !b(r + 1, c)
                if (across || down) numbers[r][c] = next++
            }
        }
        return numbers
    }

    // ------------------------------------------------------------------ testy

    private val blocked7 = setOf(
        0 to 3, 1 to 1, 2 to 5, 3 to 0, 3 to 6, 4 to 2, 5 to 4, 6 to 3,
    )

    @Test
    fun `wykrywa siatke 7x7 z czarnymi polami`() {
        val image = renderGrid(rows = 7, cols = 7, blocked = blocked7)
        val grid = detect(image)

        assertNotNull(grid)
        grid!!
        assertEquals(7, grid.rows)
        assertEquals(7, grid.cols)
        assertEquals(49, grid.cells.size)
        for (cell in grid.cells) {
            assertEquals((cell.row to cell.col) in blocked7, cell.blocked)
        }
        // obrys siatki pokrywa narysowany obszar (z tolerancją na grubość linii)
        assertTrue(grid.box.left in 0f..40f)
        assertTrue(grid.box.top in 0f..40f)
        assertTrue(grid.box.right > image.width - 60f)
        assertTrue(grid.box.bottom > image.height - 60f)
    }

    @Test
    fun `numeracja i sloty zgodne ze standardem`() {
        val rows = 7
        val cols = 7
        val image = renderGrid(rows = rows, cols = cols, blocked = blocked7)
        val grid = detect(image)

        assertNotNull(grid)
        val expected = expectedNumbering(rows, cols, blocked7)
        for (cell in grid!!.cells) {
            val want = expected[cell.row][cell.col].takeIf { it > 0 }
            assertEquals("komórka ${cell.row},${cell.col}", want, cell.number)
        }
        assertTrue(grid.slots.any { it.direction == Direction.ACROSS })
        assertTrue(grid.slots.any { it.direction == Direction.DOWN })
        for (slot in grid.slots) {
            assertTrue(slot.length >= 2)
            assertEquals(slot.number, slot.cells.first().number)
            when (slot.direction) {
                Direction.ACROSS -> {
                    assertTrue(slot.cells.all { it.row == slot.cells.first().row })
                    for (i in 1 until slot.cells.size) {
                        assertEquals(slot.cells[i - 1].col + 1, slot.cells[i].col)
                    }
                }
                Direction.DOWN -> {
                    assertTrue(slot.cells.all { it.col == slot.cells.first().col })
                    for (i in 1 until slot.cells.size) {
                        assertEquals(slot.cells[i - 1].row + 1, slot.cells[i].row)
                    }
                }
            }
        }
        assertEquals(grid.slots.size, grid.slots.map { it.number to it.direction }.distinct().size)
    }

    @Test
    fun `numeracja z OCR jest uzywana gdy jest wiarygodna`() {
        val rows = 7
        val cols = 7
        val image = renderGrid(rows = rows, cols = cols, cellW = 40, cellH = 40, blocked = blocked7)
        val expected = expectedNumbering(rows, cols, blocked7)

        fun numbers(override: Map<Pair<Int, Int>, Int> = emptyMap()): List<OcrLine> = buildList {
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    val value = override[r to c] ?: expected[r][c].takeIf { it > 0 } ?: continue
                    val left = image.gridLeft + c * image.cellW + image.cellW * 0.12f
                    val top = image.gridTop + r * image.cellH + image.cellH * 0.10f
                    add(OcrLine(value.toString(), Box(left, top, left + image.cellW * 0.22f, top + image.cellH * 0.35f)))
                }
            }
        }

        val withOcr = detect(image, numbers())
        assertNotNull(withOcr)
        for (cell in withOcr!!.cells) {
            assertEquals(expected[cell.row][cell.col].takeIf { it > 0 }, cell.number)
        }

        // jeden błędny numer przy wysokiej zgodności — OCR ma priorytet, ale numery pozostają unikalne
        val overridden = detect(image, numbers(mapOf((0 to 0) to 90)))
        assertNotNull(overridden)
        assertEquals(90, overridden!!.cells.first { it.row == 0 && it.col == 0 }.number)
        assertEquals(overridden.slots.size, overridden.slots.map { it.number to it.direction }.distinct().size)
    }

    @Test
    fun `wykrywa minimalna siatke 5x5`() {
        val blocked = setOf(0 to 0, 2 to 2, 4 to 4)
        val image = renderGrid(rows = 5, cols = 5, cellW = 48, cellH = 48, margin = 40, blocked = blocked)
        val grid = detect(image)

        assertNotNull(grid)
        assertEquals(5, grid!!.rows)
        assertEquals(5, grid.cols)
        assertEquals(25, grid.cells.size)
        assertEquals(3, grid.cells.count { it.blocked })
        assertTrue(grid.slots.size >= 4)
        assertTrue(grid.slots.any { it.direction == Direction.ACROSS })
        assertTrue(grid.slots.any { it.direction == Direction.DOWN })
    }

    @Test
    fun `odrzuca zbyt mala siatke`() {
        assertNull(detect(renderGrid(rows = 4, cols = 4, blocked = setOf(1 to 1, 2 to 2))))
    }

    @Test
    fun `odrzuca tabele bez czarnych pol i numeracji`() {
        assertNull(detect(renderGrid(rows = 6, cols = 6)))
    }

    @Test
    fun `odrzuca tabele z zaczernionym wierszem`() {
        val fullRow = (0 until 6).map { 0 to it }.toSet()
        assertNull(detect(renderGrid(rows = 6, cols = 6, blocked = fullRow)))
    }

    @Test
    fun `odrzuca komorki niekwadratowe`() {
        val image = renderGrid(rows = 6, cols = 6, cellW = 52, cellH = 22, blocked = setOf(1 to 1, 4 to 4))
        assertNull(detect(image))
    }

    @Test
    fun `przyjmuje jolke bez czarnych pol gdy numeracja OCR jest wiarygodna`() {
        val rows = 8
        val cols = 8
        val image = renderGrid(rows = rows, cols = cols, cellW = 34, cellH = 34)
        val expected = expectedNumbering(rows, cols, emptySet())
        val numbers = buildList {
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    val value = expected[r][c].takeIf { it > 0 } ?: continue
                    val left = image.gridLeft + c * image.cellW + image.cellW * 0.12f
                    val top = image.gridTop + r * image.cellH + image.cellH * 0.10f
                    add(OcrLine(value.toString(), Box(left, top, left + image.cellW * 0.25f, top + image.cellH * 0.40f)))
                }
            }
        }
        val grid = detect(image, numbers)

        assertNotNull(grid)
        assertEquals(8, grid!!.rows)
        assertEquals(8, grid.cols)
        assertTrue(grid.cells.none { it.blocked })
        for (cell in grid.cells) {
            assertEquals(expected[cell.row][cell.col].takeIf { it > 0 }, cell.number)
        }
    }

    @Test
    fun `radzi sobie z lekkim obrotem`() {
        val image = rotate(renderGrid(rows = 7, cols = 7, cellW = 44, cellH = 44, blocked = blocked7), 3.5f)
        val grid = detect(image)

        assertNotNull("siatka po obrocie 3.5° powinna zostać wykryta", grid)
        assertEquals(7, grid!!.rows)
        assertEquals(7, grid.cols)
        for (cell in grid.cells) {
            assertEquals((cell.row to cell.col) in blocked7, cell.blocked)
        }
    }

    @Test
    fun `radzi sobie z szumem i paddingiem wiersza`() {
        val image = renderGrid(rows = 6, cols = 6, noise = 14, rowPadding = 37, blocked = setOf(0 to 2, 2 to 0, 3 to 5, 5 to 3))
        val grid = detect(image)

        assertNotNull(grid)
        assertEquals(6, grid!!.rows)
        assertEquals(6, grid.cols)
        assertEquals(4, grid.cells.count { it.blocked })
    }

    @Test
    fun `odrzuca obraz z samym tekstem`() {
        val width = 640
        val height = 480
        val luma = ByteArray(width * height) { 245.toByte() }
        val random = Random(3)
        var y = 60
        while (y < height - 40) {
            var x = 40
            while (x < width - 60) {
                val wordLength = 25 + random.nextInt(60)
                for (dx in 0 until wordLength) {
                    if (x + dx >= width - 20) break
                    for (dy in 0 until 9) luma[(y + dy) * width + x + dx] = 35
                }
                x += wordLength + 12
            }
            y += 34
        }
        assertNull(detector.detect(luma, width, height, width, emptyList()))
    }

    @Test
    fun `odrzuca pusty obraz`() {
        val width = 400
        val height = 300
        assertNull(detector.detect(ByteArray(width * height) { 245.toByte() }, width, height, width, emptyList()))
        assertNull(detector.detect(ByteArray(10), 2, 2, 2, emptyList()))
    }

    @Test
    fun `remapBoxes zachowuje powiazania slotow z komorkami`() {
        val grid = detect(renderGrid(rows = 7, cols = 7, blocked = blocked7))
        assertNotNull(grid)
        val mapped = grid!!.remapBoxes { box -> Box(box.left + 10f, box.top + 20f, box.right + 10f, box.bottom + 20f) }

        assertEquals(grid.rows, mapped.rows)
        assertEquals(grid.cells.size, mapped.cells.size)
        for (i in grid.cells.indices) {
            val before = grid.cells[i]
            val after = mapped.cells[i]
            assertEquals(before.row, after.row)
            assertEquals(before.col, after.col)
            assertEquals(before.blocked, after.blocked)
            assertEquals(before.number, after.number)
            assertEquals(before.box.left + 10f, after.box.left, 0.01f)
            assertEquals(before.box.top + 20f, after.box.top, 0.01f)
        }
        for (i in grid.slots.indices) {
            assertEquals(grid.slots[i].number, mapped.slots[i].number)
            assertEquals(grid.slots[i].direction, mapped.slots[i].direction)
            assertEquals(grid.slots[i].cells.map { it.row to it.col }, mapped.slots[i].cells.map { it.row to it.col })
        }
    }

    @Test
    fun `wykrywa siatke w wiekszym obrazie z marginesem`() {
        val image = renderGrid(rows = 9, cols = 9, cellW = 36, cellH = 36, margin = 120, blocked = setOf(
            0 to 4, 2 to 2, 4 to 6, 6 to 0, 8 to 4, 5 to 5,
        ))
        val grid = detect(image)

        assertNotNull(grid)
        assertEquals(9, grid!!.rows)
        assertEquals(9, grid.cols)
        // box siatki nie obejmuje całego obrazu — tylko obszar siatki
        assertTrue(grid.box.left > 80f)
        assertTrue(grid.box.top > 80f)
        assertTrue(grid.box.right < image.width - 80f)
        assertTrue(grid.box.bottom < image.height - 80f)
    }
}
