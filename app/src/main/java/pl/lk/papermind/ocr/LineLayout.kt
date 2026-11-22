package pl.lk.papermind.ocr

import pl.lk.papermind.model.Box
import pl.lk.papermind.model.OcrLine

/** Fragment tekstu logicznej linii wraz z boxem (zakres znaków [start, end) w [LogicalLine.text]). */
internal class Piece(val start: Int, val end: Int, val box: Box)

/**
 * Linia logiczna = jeden wiersz na kartce (może łączyć kilka [OcrLine] leżących obok siebie,
 * np. opcje "A. x    B. y" rozbite przez OCR na osobne bloki).
 */
internal class LogicalLine(val text: String, val pieces: List<Piece>, val box: Box) {
    /** Offsety początków fragmentów — granice bloków OCR (pomagają rozpoznać kolumny opcji). */
    val pieceStarts: Set<Int> = pieces.mapTo(HashSet()) { it.start }

    val height: Float get() = box.height

    /** Box podciągu [from, to) — proporcjonalne cięcie boxa wg pozycji znaków w obrębie fragmentu. */
    fun boxOf(from: Int, to: Int): Box {
        var result: Box? = null
        for (p in pieces) {
            val a = maxOf(from, p.start)
            val b = minOf(to, p.end)
            if (a >= b) continue
            val len = (p.end - p.start).toFloat()
            val l = p.box.left + p.box.width * (a - p.start) / len
            val r = p.box.left + p.box.width * (b - p.start) / len
            val part = Box(l, p.box.top, r, p.box.bottom)
            result = result?.union(part) ?: part
        }
        return result ?: box
    }

    /** Dokleja kolejną linię logiczną (np. sam wypełniacz pod etykietą formularza). */
    fun mergeWith(next: LogicalLine): LogicalLine {
        val offset = text.length + 1
        val joinedPieces = pieces + next.pieces.map { Piece(it.start + offset, it.end + offset, it.box) }
        return LogicalLine(
            text = text + " " + next.text,
            pieces = joinedPieces,
            box = box.union(next.box),
        )
    }
}

/** Układ strony: sortowanie w kolejności czytania, łączenie wierszy, wykrywanie kolumn. */
internal object LineLayout {
    private val WS = Regex("\\s+")

    fun medianHeight(lines: List<OcrLine>): Float {
        val hs = lines.map { it.box.height }.filter { it > 0f }.sorted()
        return if (hs.isEmpty()) 1f else hs[hs.size / 2]
    }

    fun build(raw: List<OcrLine>): List<LogicalLine> {
        val lines = raw.map { it.copy(text = WS.replace(it.text, " ").trim()) }
            .filter { it.text.isNotEmpty() && it.box.width > 0f && it.box.height > 0f }
        if (lines.isEmpty()) return emptyList()
        val h = medianHeight(lines)
        return splitColumns(lines, h).flatMap { buildRegion(it) }
    }

    /**
     * Kolumny wykrywamy tylko gdy istnieje pionowa rynna (pusty pas) a w każdej kolumnie jest linia zaczynająca się
     * numeracją pytania — dzięki temu rząd opcji "A. x      B. y" nie jest brany za dwie kolumny.
     */
    private fun splitColumns(lines: List<OcrLine>, h: Float): List<List<OcrLine>> {
        val sorted = lines.sortedBy { it.box.left }
        val gutters = ArrayList<Float>()
        var coverEnd = sorted.first().box.right
        for (l in sorted.drop(1)) {
            if (l.box.left - coverEnd > 2f * h) gutters += (coverEnd + l.box.left) / 2f
            coverEnd = maxOf(coverEnd, l.box.right)
        }
        if (gutters.isEmpty()) return listOf(lines)
        val regions = (0..gutters.size).map { i ->
            lines.filter { l ->
                val cx = (l.box.left + l.box.right) / 2f
                (i == 0 || cx >= gutters[i - 1]) && (i == gutters.size || cx < gutters[i])
            }
        }
        if (regions.any { it.isEmpty() }) return listOf(lines)
        val ok = regions.all { r -> r.any { QuestionPatterns.startsWithQuestionNumber(it.text) } }
        return if (ok) regions else listOf(lines)
    }

    private fun buildRegion(lines: List<OcrLine>): List<LogicalLine> {
        // Grupowanie w wiersze: linia należy do wiersza, gdy pokrywa się pionowo w ≥50% mniejszej wysokości.
        val rows = ArrayList<MutableList<OcrLine>>()
        for (l in lines.sortedBy { it.box.top }) {
            val row = rows.lastOrNull()
            if (row != null && sameRow(row, l)) row += l else rows += mutableListOf(l)
        }
        return rows.map { row ->
            val parts = row.sortedBy { it.box.left }
            val sb = StringBuilder()
            val pieces = ArrayList<Piece>()
            var box: Box? = null
            for (p in parts) {
                if (sb.isNotEmpty()) sb.append(' ')
                pieces += Piece(sb.length, sb.length + p.text.length, p.box)
                sb.append(p.text)
                box = box?.union(p.box) ?: p.box
            }
            LogicalLine(sb.toString(), pieces, box!!)
        }.sortedBy { it.box.top }
    }

    private fun sameRow(row: List<OcrLine>, l: OcrLine): Boolean {
        val top = row.minOf { it.box.top }
        val bottom = row.maxOf { it.box.bottom }
        val overlap = minOf(bottom, l.box.bottom) - maxOf(top, l.box.top)
        val minH = minOf(bottom - top, l.box.height)
        return overlap >= 0.5f * minH
    }
}
