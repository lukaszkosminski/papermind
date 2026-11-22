package pl.lk.papermind.ocr

import pl.lk.papermind.model.Box
import pl.lk.papermind.model.CrosswordGrid
import pl.lk.papermind.model.CrosswordSlot
import pl.lk.papermind.model.Direction
import pl.lk.papermind.model.GridCell
import pl.lk.papermind.model.OcrLine
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Detektor siatki krzyżówki — czysta logika JVM na tablicy luminancji (płaszczyzna Y z kamery).
 *
 * Pipeline:
 * 1. zmniejszenie obrazu (dłuższy bok ~[Config.targetLongSide]) i binaryzacja adaptacyjna (Otsu + lokalna średnia),
 * 2. estymacja lekkiego obrotu (±[Config.maxSkewDegrees]) przez wariancję rzutów poziomych,
 * 3. obrót maski do układu „linie poziome/pionowe" i detekcja linii siatki (najdłuższe ciągi ciemnych pikseli),
 * 4. walidacja regularności okresu komórek i kwadratowości pól,
 * 5. macierz komórek (`blocked` = w większości ciemna), numeracja z OCR (małe liczby w lewym górnym rogu komórki)
 *    z fallbackiem na standardową numerację krzyżówki, na końcu sloty.
 *
 * Odrzuca fałszywe siatki (tabele): minimum 5×5, komórki kwadratowe, regularny okres, obecność czarnych pól
 * **lub** wiarygodnej numeracji; odrzuca też siatki z całkowicie zaczernionym wierszem/kolumną (typowa tabela).
 *
 * Zwracane [Box] są w układzie wejściowym (piksele obrazu); pipeline mapuje je do pikseli widoku.
 */
internal class CrosswordGridDetector(private val config: Config = Config()) {

    data class Config(
        /** Minimalna/liczba komórek siatki w każdym wymiarze. */
        val minCells: Int = 5,
        val maxCells: Int = 40,
        /** Minimalny rozmiar komórki w przeskalowanym obrazie (px). */
        val minCellSizePx: Float = 6f,
        /** Docelowy dłuższy bok obrazu roboczego. */
        val targetLongSide: Int = 640,
        /** Udział ciemnych pikseli w komórce, od którego uznajemy ją za zaczernioną. */
        val blockedThreshold: Double = 0.5,
        /** Minimalny odsetek czarnych pól, by uznać siatkę bez numeracji OCR za krzyżówkę. */
        val minBlackFraction: Double = 0.05,
        val maxSkewDegrees: Float = 5f,
        /** Minimalna długość linii (ułamek szerokości/wysokości obrazu roboczego). */
        val minLineCoverage: Double = 0.12,
        /** Zgodność numerów OCR ze standardową numeracją, przy której ufamy OCR (jolka bez czarnych pól). */
        val minNumberAgreement: Double = 0.6,
    )

    fun detect(
        luma: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int = width,
        numbers: List<OcrLine> = emptyList(),
    ): CrosswordGrid? {
        if (width < 80 || height < 80) return null
        if (rowStride < width) return null
        if (luma.size < (height - 1) * rowStride + width) return null

        val step = max(1, ceil(max(width, height).toFloat() / config.targetLongSide).toInt())
        val small = downsample(luma, width, height, rowStride, step)
        val dark = binarize(small)
        val angle = estimateSkew(dark, small.w, small.h)
        val frame = RotFrame(small.w, small.h, angle, step)
        val rotated = frame.render(dark)

        val rowLines = findLines(rotated, frame.rw, frame.rh, horizontal = true) ?: return null
        val colLines = findLines(rotated, frame.rw, frame.rh, horizontal = false) ?: return null
        val rows = rowLines.positions.size - 1
        val cols = colLines.positions.size - 1
        if (rows !in config.minCells..config.maxCells) return null
        if (cols !in config.minCells..config.maxCells) return null
        val cellW = colLines.cell
        val cellH = rowLines.cell
        if (cellW < config.minCellSizePx || cellH < config.minCellSizePx) return null
        if (abs(cellW - cellH) > 0.40f * max(cellW, cellH)) return null

        val integral = integralImage(rotated, frame.rw, frame.rh)
        val blocked = Array(rows) { BooleanArray(cols) }
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                blocked[r][c] = isBlocked(
                    integral, frame.rw, frame.rh,
                    colLines.positions[c], rowLines.positions[r],
                    colLines.positions[c + 1], rowLines.positions[r + 1],
                )
            }
        }

        val standard = standardNumbering(blocked, rows, cols)
        val ocr = ocrNumbers(numbers, frame, colLines.positions, rowLines.positions, rows, cols, cellH, step)
        val agreement = ocrAgreement(standard, ocr)
        val blockedCount = blocked.sumOf { row -> row.count { it } }
        val blockedFraction = blockedCount.toDouble() / (rows * cols)
        val numberingEvidence = ocr.size >= 3 && agreement >= config.minNumberAgreement
        if (blockedFraction < config.minBlackFraction && !numberingEvidence) return null
        if (blockedFraction > 0.75) return null
        // Całkowicie zaczerniony wiersz/kolumna to wzorzec tabeli (np. nagłówek), nie krzyżówki.
        if ((0 until rows).any { r -> (0 until cols).all { c -> blocked[r][c] } }) return null
        if ((0 until cols).any { c -> (0 until rows).all { r -> blocked[r][c] } }) return null

        val finalNumbers = mergeNumbers(standard, ocr, numberingEvidence)

        val cellByPos = Array(rows) { arrayOfNulls<GridCell>(cols) }
        val cells = ArrayList<GridCell>(rows * cols)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val box = mapRect(
                    frame,
                    colLines.positions[c], rowLines.positions[r],
                    colLines.positions[c + 1], rowLines.positions[r + 1],
                )
                val cell = GridCell(r, c, box, blocked[r][c], finalNumbers[r][c].takeIf { it > 0 })
                cellByPos[r][c] = cell
                cells += cell
            }
        }

        val slots = buildSlots(blocked, finalNumbers, rows, cols, cellByPos)
        if (slots.count { it.direction == Direction.ACROSS } < 1) return null
        if (slots.count { it.direction == Direction.DOWN } < 1) return null
        if (slots.size < 4) return null

        val box = mapRect(
            frame,
            colLines.positions.first(), rowLines.positions.first(),
            colLines.positions.last(), rowLines.positions.last(),
        )
        return CrosswordGrid(rows = rows, cols = cols, box = box, cells = cells, slots = slots)
    }

    // ------------------------------------------------------------------ obraz roboczy

    private class Small(val luma: ByteArray, val w: Int, val h: Int, val step: Int)

    private fun downsample(luma: ByteArray, width: Int, height: Int, rowStride: Int, step: Int): Small {
        val w = max(1, width / step)
        val h = max(1, height / step)
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            var ox = y * w
            for (x in 0 until w) {
                var sum = 0
                var n = 0
                for (dy in 0 until step) {
                    val srcRow = (y * step + dy) * rowStride + x * step
                    for (dx in 0 until step) {
                        val index = srcRow + dx
                        if (index < luma.size) {
                            sum += luma[index].toInt() and 0xFF
                            n++
                        }
                    }
                }
                out[ox++] = (if (n == 0) 255 else sum / n).toByte()
            }
        }
        return Small(out, w, h, step)
    }

    private fun binarize(img: Small): ByteArray {
        val n = img.w * img.h
        val otsu = otsuThreshold(img.luma, n)
        val window = max(8, min(img.w, img.h) / 8)
        val integral = integralImage(img.luma, img.w, img.h)
        val out = ByteArray(n)
        for (y in 0 until img.h) {
            for (x in 0 until img.w) {
                val x0 = max(0, x - window)
                val x1 = min(img.w - 1, x + window)
                val y0 = max(0, y - window)
                val y1 = min(img.h - 1, y + window)
                val area = (x1 - x0 + 1) * (y1 - y0 + 1)
                val mean = rectSum(integral, img.w, x0, y0, x1, y1) / area
                val v = img.luma[y * img.w + x].toInt() and 0xFF
                out[y * img.w + x] = if (v < otsu || v < mean - 12) 1 else 0
            }
        }
        return out
    }

    private fun otsuThreshold(luma: ByteArray, n: Int): Int {
        val hist = IntArray(256)
        for (i in 0 until n) hist[luma[i].toInt() and 0xFF]++
        var sum = 0L
        for (i in 0..255) sum += i.toLong() * hist[i]
        var sumB = 0L
        var wB = 0L
        var best = 0.0
        var threshold = 128
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0L) continue
            val wF = n - wB
            if (wF == 0L) break
            sumB += t.toLong() * hist[t]
            val mB = sumB.toDouble() / wB
            val mF = (sum - sumB).toDouble() / wF
            val between = wB.toDouble() * wF.toDouble() * (mB - mF) * (mB - mF)
            if (between > best) {
                best = between
                threshold = t
            }
        }
        return threshold
    }

    private fun integralImage(pixels: ByteArray, w: Int, h: Int): IntArray {
        val stride = w + 1
        val integral = IntArray(stride * (h + 1))
        for (y in 0 until h) {
            var rowSum = 0
            val srcRow = y * w
            val dstRow = (y + 1) * stride
            val prevRow = y * stride
            for (x in 0 until w) {
                rowSum += pixels[srcRow + x].toInt() and 0xFF
                integral[dstRow + x + 1] = integral[prevRow + x + 1] + rowSum
            }
        }
        return integral
    }

    private fun rectSum(integral: IntArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int): Int {
        val stride = w + 1
        val a = y0 * stride + x0
        val b = y0 * stride + x1 + 1
        val c = (y1 + 1) * stride + x0
        val d = (y1 + 1) * stride + x1 + 1
        return integral[d] - integral[b] - integral[c] + integral[a]
    }

    // ------------------------------------------------------------------ obrót

    /**
     * Estymacja lekkiego obrotu: rzut ciemnych pikseli na oś y' i wybór kąta, przy którym rzut jest
     * najbardziej „grzebieniowy" (linie siatki zbierają się w wąskich, regularnych pikach).
     *
     * Metryka to suma trzech najwyższych binów rzutu (szerokość binu ~3 px) — odporna na grubość linii
     * i na czarne pola, w przeciwieństwie do wariancji całego rzutu. Wynik jest przycinany do
     * ±[Config.maxSkewDegrees]; jeśli poprawa względem 0° jest znikoma, zostaje 0°.
     */
    private fun estimateSkew(dark: ByteArray, w: Int, h: Int): Float {
        val cx = w / 2f
        val cy = h / 2f
        // zbierz ciemne piksele (co drugi w obu osiach — wystarczy do estymacji kąta)
        val xs = ArrayList<Int>(w * h / 8)
        val ys = ArrayList<Int>(w * h / 8)
        for (y in 0 until h step 2) {
            for (x in 0 until w step 2) {
                if (dark[y * w + x] != 0.toByte()) {
                    xs += x
                    ys += y
                }
            }
        }
        if (xs.size < 40) return 0f

        val bound = h / 2f + 0.12f * w + 3f
        val binSize = max(2f, h / 160f)
        val bins = ceil(2 * bound / binSize).toInt().coerceAtLeast(16)
        val hist = IntArray(bins)

        fun score(angleDeg: Float): Double {
            val rad = Math.toRadians(angleDeg.toDouble())
            val sinA = sin(rad).toFloat()
            val cosA = cos(rad).toFloat()
            java.util.Arrays.fill(hist, 0)
            for (i in xs.indices) {
                val yRot = -sinA * (xs[i] - cx) + cosA * (ys[i] - cy)
                val bin = ((yRot + bound) / binSize).toInt().coerceIn(0, bins - 1)
                hist[bin]++
            }
            var first = 0
            var second = 0
            var third = 0
            for (v in hist) {
                if (v > first) {
                    third = second
                    second = first
                    first = v
                } else if (v > second) {
                    third = second
                    second = v
                } else if (v > third) {
                    third = v
                }
            }
            return (first + second + third).toDouble()
        }

        var bestAngle = 0f
        var bestScore = -1.0
        var angle = -config.maxSkewDegrees
        while (angle <= config.maxSkewDegrees + 1e-4f) {
            val s = score(angle)
            if (s > bestScore) {
                bestScore = s
                bestAngle = angle
            }
            angle += 0.5f
        }
        val coarse = bestAngle
        angle = coarse - 0.5f
        while (angle <= coarse + 0.5f + 1e-4f) {
            val clamped = angle.coerceIn(-config.maxSkewDegrees, config.maxSkewDegrees)
            val s = score(clamped)
            if (s > bestScore) {
                bestScore = s
                bestAngle = clamped
            }
            angle += 0.1f
        }
        val atZero = score(0f)
        if (bestScore < atZero * 1.06) bestAngle = 0f
        return if (abs(bestAngle) < 0.15f) 0f else bestAngle
    }

    /**
     * Obrót obrazu roboczego do układu, w którym linie siatki są poziome/pionowe.
     * [toRotX]/[toRotY] mapują piksel obrazu roboczego na współrzędne obrócone,
     * [toImageX]/[toImageY] — odwrotnie (do mapowania boxów z powrotem).
     */
    private class RotFrame(val w: Int, val h: Int, angleDegrees: Float, val step: Int) {
        private val rad = Math.toRadians(angleDegrees.toDouble())
        private val cosA = cos(rad).toFloat()
        private val sinA = sin(rad).toFloat()
        private val cx = w / 2f
        private val cy = h / 2f
        val rw = ceil(abs(w * cosA) + abs(h * sinA)).toInt().coerceAtLeast(1)
        val rh = ceil(abs(w * sinA) + abs(h * cosA)).toInt().coerceAtLeast(1)
        private val rcx = rw / 2f
        private val rcy = rh / 2f

        fun toRotX(x: Float, y: Float): Float = cosA * (x - cx) + sinA * (y - cy) + rcx
        fun toRotY(x: Float, y: Float): Float = -sinA * (x - cx) + cosA * (y - cy) + rcy
        fun toImageX(xr: Float, yr: Float): Float = cosA * (xr - rcx) - sinA * (yr - rcy) + cx
        fun toImageY(xr: Float, yr: Float): Float = sinA * (xr - rcx) + cosA * (yr - rcy) + cy

        fun render(mask: ByteArray): ByteArray {
            val out = ByteArray(rw * rh)
            for (yr in 0 until rh) {
                val y = yr + 0.5f
                for (xr in 0 until rw) {
                    val x = xr + 0.5f
                    val ix = toImageX(x, y).toInt()
                    val iy = toImageY(x, y).toInt()
                    if (ix in 0 until w && iy in 0 until h) {
                        out[yr * rw + xr] = mask[iy * w + ix]
                    }
                }
            }
            return out
        }
    }

    private fun mapRect(frame: RotFrame, x0: Float, y0: Float, x1: Float, y1: Float): Box {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        val xs = floatArrayOf(x0, x1, x1, x0)
        val ys = floatArrayOf(y0, y0, y1, y1)
        for (i in 0..3) {
            val ix = frame.toImageX(xs[i], ys[i]) * frame.step
            val iy = frame.toImageY(xs[i], ys[i]) * frame.step
            if (ix < minX) minX = ix
            if (iy < minY) minY = iy
            if (ix > maxX) maxX = ix
            if (iy > maxY) maxY = iy
        }
        return Box(minX, minY, maxX, maxY)
    }

    // ------------------------------------------------------------------ linie

    private class LineSet(val positions: FloatArray, val cell: Float, val thickness: Float)

    /**
     * Szuka linii siatki jako najdłuższych (z tolerancją 2 px przerwy) ciągów ciemnych pikseli w wierszach/kolumnach.
     * Zwraca regularny zestaw pozycji linii (siatka komórek) albo null.
     */
    private fun findLines(mask: ByteArray, w: Int, h: Int, horizontal: Boolean): LineSet? {
        val count = if (horizontal) h else w
        val len = if (horizontal) w else h
        val runs = IntArray(count)
        var maxRun = 0
        for (k in 0 until count) {
            var best = 0
            var start = -1
            var gap = 0
            for (j in 0 until len) {
                val dark = if (horizontal) mask[k * w + j].toInt() != 0 else mask[j * w + k].toInt() != 0
                if (dark) {
                    if (start < 0) start = j
                    gap = 0
                    val run = j - start + 1
                    if (run > best) best = run
                } else if (start >= 0) {
                    gap++
                    if (gap > 2) {
                        start = -1
                        gap = 0
                    }
                }
            }
            runs[k] = best
            if (best > maxRun) maxRun = best
        }
        if (maxRun < (len * 0.2f).toInt()) return null

        val cut = maxOf((maxRun * 0.35f).toInt(), (len * config.minLineCoverage).toInt(), 6)
        val centers = ArrayList<Float>()
        val thickness = ArrayList<Int>()
        var k = 0
        while (k < count) {
            if (runs[k] < cut) {
                k++
                continue
            }
            var end = k
            while (end + 1 < count && runs[end + 1] >= cut) end++
            var weighted = 0L
            var weight = 0L
            for (t in k..end) {
                weighted += t.toLong() * runs[t]
                weight += runs[t]
            }
            if (weight > 0) {
                centers += (weighted.toFloat() / weight)
                thickness += (end - k + 1)
            }
            k = end + 1
        }
        if (centers.size < config.minCells + 1) return null

        val raw = centers.toFloatArray()
        val cell = medianDiff(raw) ?: return null
        if (cell < config.minCellSizePx) return null

        // Dopasowanie do sieci okresowej — odrzuca przypadkowe linie (np. podkreślenia w tekście).
        val origin = raw[raw.size / 2]
        val kept = ArrayList<Float>(raw.size)
        var lastLattice = Int.MIN_VALUE
        for (p in raw) {
            val lattice = ((p - origin) / cell).roundToInt()
            if (lattice == lastLattice) continue
            val expected = origin + lattice * cell
            if (abs(p - expected) > 0.42f * cell) continue
            kept += p
            lastLattice = lattice
        }
        if (kept.size < config.minCells + 1) return null
        for (i in 1 until kept.size) {
            val d = kept[i] - kept[i - 1]
            if (d < 0.55f * cell || d > 1.75f * cell) return null
        }
        val refined = medianDiff(kept.toFloatArray()) ?: return null
        val thick = thickness.sorted().let { it[it.size / 2] }.toFloat().coerceAtLeast(1f)
        return LineSet(kept.toFloatArray(), refined, thick)
    }

    private fun medianDiff(values: FloatArray): Float? {
        if (values.size < 2) return null
        val diffs = FloatArray(values.size - 1) { values[it + 1] - values[it] }
        diffs.sort()
        return diffs[diffs.size / 2]
    }

    private fun isBlocked(
        integral: IntArray, w: Int, h: Int,
        x0f: Float, y0f: Float, x1f: Float, y1f: Float,
    ): Boolean {
        val cellW = x1f - x0f
        val cellH = y1f - y0f
        val insetX = cellW * 0.16f
        val insetY = cellH * 0.16f
        val x0 = (x0f + insetX).toInt().coerceIn(0, w - 1)
        val x1 = (x1f - insetX).toInt().coerceIn(0, w - 1)
        val y0 = (y0f + insetY).toInt().coerceIn(0, h - 1)
        val y1 = (y1f - insetY).toInt().coerceIn(0, h - 1)
        if (x1 <= x0 || y1 <= y0) return false
        val dark = rectSum(integral, w, x0, y0, x1, y1)
        val area = (x1 - x0 + 1) * (y1 - y0 + 1)
        return dark.toDouble() / area >= config.blockedThreshold
    }

    // ------------------------------------------------------------------ numeracja i sloty

    private fun startsAcross(blocked: Array<BooleanArray>, r: Int, c: Int, cols: Int): Boolean {
        if (blocked[r][c]) return false
        if (c > 0 && !blocked[r][c - 1]) return false
        return c + 1 < cols && !blocked[r][c + 1]
    }

    private fun startsDown(blocked: Array<BooleanArray>, r: Int, c: Int, rows: Int): Boolean {
        if (blocked[r][c]) return false
        if (r > 0 && !blocked[r - 1][c]) return false
        return r + 1 < rows && !blocked[r + 1][c]
    }

    private fun standardNumbering(blocked: Array<BooleanArray>, rows: Int, cols: Int): Array<IntArray> {
        val numbers = Array(rows) { IntArray(cols) }
        var next = 1
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (blocked[r][c]) continue
                if (startsAcross(blocked, r, c, cols) || startsDown(blocked, r, c, rows)) {
                    numbers[r][c] = next++
                }
            }
        }
        return numbers
    }

    /** Numery przeczytane przez OCR, przypisane do komórek (tylko lewy górny róg, mała wysokość). */
    private fun ocrNumbers(
        numbers: List<OcrLine>,
        frame: RotFrame,
        colPositions: FloatArray,
        rowPositions: FloatArray,
        rows: Int,
        cols: Int,
        cellH: Float,
        step: Int,
    ): Map<Pair<Int, Int>, Int> {
        if (numbers.isEmpty()) return emptyMap()
        val out = HashMap<Pair<Int, Int>, Int>()
        val cellW = colPositions[1] - colPositions[0]
        for (line in numbers) {
            val digits = line.text.trim().takeWhile { it.isDigit() }
            if (digits.isEmpty() || digits.length > 2) continue
            val value = digits.toIntOrNull() ?: continue
            if (value !in 1..99) continue
            val tail = line.text.trim().substring(digits.length)
            if (tail.any { it.isLetterOrDigit() }) continue

            val cx = (line.box.left + line.box.right) / 2f / step
            val cy = (line.box.top + line.box.bottom) / 2f / step
            val xr = frame.toRotX(cx, cy)
            val yr = frame.toRotY(cx, cy)
            val col = cellIndex(xr, colPositions, cols)
            val row = cellIndex(yr, rowPositions, rows)
            if (col < 0 || row < 0) continue
            val fx = (xr - colPositions[col]) / cellW
            val fy = (yr - rowPositions[row]) / cellH
            if (fx > 0.6f || fy > 0.6f) continue
            if (line.box.height / step > 0.6f * cellH) continue
            out.putIfAbsent(row to col, value)
        }
        return out
    }

    private fun cellIndex(value: Float, positions: FloatArray, cells: Int): Int {
        for (i in 0 until cells) {
            if (value >= positions[i] && value <= positions[i + 1]) return i
        }
        return -1
    }

    private fun ocrAgreement(standard: Array<IntArray>, ocr: Map<Pair<Int, Int>, Int>): Double {
        if (ocr.isEmpty()) return 0.0
        var matching = 0
        var counted = 0
        for ((pos, value) in ocr) {
            val std = standard[pos.first][pos.second]
            if (std <= 0) continue
            counted++
            if (std == value) matching++
        }
        return if (counted == 0) 0.0 else matching.toDouble() / counted
    }

    private fun mergeNumbers(
        standard: Array<IntArray>,
        ocr: Map<Pair<Int, Int>, Int>,
        trustOcr: Boolean,
    ): Array<IntArray> {
        val merged = Array(standard.size) { standard[it].clone() }
        if (!trustOcr) return merged
        val used = HashSet<Int>()
        for (r in merged.indices) {
            for (c in merged[r].indices) {
                if (merged[r][c] <= 0) continue
                val value = ocr[r to c] ?: continue
                if (used.add(value)) merged[r][c] = value
            }
        }
        return merged
    }

    private fun buildSlots(
        blocked: Array<BooleanArray>,
        numbers: Array<IntArray>,
        rows: Int,
        cols: Int,
        cells: Array<Array<GridCell?>>,
    ): List<CrosswordSlot> {
        val slots = ArrayList<CrosswordSlot>()
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val number = numbers[r][c]
                if (number <= 0) continue
                if (startsAcross(blocked, r, c, cols)) {
                    val run = ArrayList<GridCell>()
                    var cc = c
                    while (cc < cols && !blocked[r][cc]) {
                        cells[r][cc]?.let { run += it }
                        cc++
                    }
                    if (run.size >= 2) slots += CrosswordSlot(number, Direction.ACROSS, run)
                }
                if (startsDown(blocked, r, c, rows)) {
                    val run = ArrayList<GridCell>()
                    var rr = r
                    while (rr < rows && !blocked[rr][c]) {
                        cells[rr][c]?.let { run += it }
                        rr++
                    }
                    if (run.size >= 2) slots += CrosswordSlot(number, Direction.DOWN, run)
                }
            }
        }
        return slots
    }
}

/** Zmienia boxy siatki (np. z pikseli obrazu na piksele widoku), zachowując powiązania slot → komórka. */
internal fun CrosswordGrid.remapBoxes(map: (Box) -> Box): CrosswordGrid {
    val mappedCells = cells.map { it.copy(box = map(it.box)) }
    val byPos = mappedCells.associateBy { it.row to it.col }
    val mappedSlots = slots.map { slot ->
        slot.copy(cells = slot.cells.mapNotNull { byPos[it.row to it.col] })
    }
    return copy(box = map(box), cells = mappedCells, slots = mappedSlots)
}
