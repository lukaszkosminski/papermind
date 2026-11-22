package pl.lk.papermind.ui.tracking

import pl.lk.papermind.model.Box
import pl.lk.papermind.model.OcrFrame
import kotlin.math.hypot
import kotlin.math.min

/** Wynik dopasowania: [box] to suma boxów dopasowanych linii, [score] — podobieństwo 0..1. */
data class LocatorMatch(val box: Box, val score: Float)

/** Klatka OCR z jednokrotnie znormalizowanym tekstem linii (wiele zapytań na klatkę). */
class PreparedFrame(val frame: OcrFrame) {
    val normalized: List<String> = frame.lines.map { TextSimilarity.normalize(it.text) }
}

/**
 * Znajduje w bieżącej klatce OCR najbardziej podobny fragment tekstu (1..[maxSpan] kolejnych linii).
 * Gdy tekst występuje wielokrotnie, preferuje trafienie najbliżej [near].
 */
class TextLocator(
    private val threshold: Float = 0.6f,
    private val maxSpan: Int = 4,
    private val proximityWeight: Float = 0.1f,
    private val proximityScalePx: Float = 1500f,
) {
    /** [alternatives] — równoważne zapisy szukanego tekstu (np. z etykietą i bez). */
    fun locate(alternatives: List<String>, frame: PreparedFrame, near: Box?): LocatorMatch? {
        val needles = alternatives.map(TextSimilarity::normalize).filter { it.isNotEmpty() }
        if (needles.isEmpty()) return null
        val lines = frame.frame.lines
        val maxNeedle = needles.maxOf { it.length }

        var best: LocatorMatch? = null
        var bestAdjusted = -1f
        for (start in lines.indices) {
            val sb = StringBuilder()
            var box: Box? = null
            for (end in start until min(lines.size, start + maxSpan)) {
                val part = frame.normalized[end]
                if (part.isEmpty()) continue
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(part)
                box = box?.union(lines[end].box) ?: lines[end].box
                // dalsze doklejanie tylko oddala od szukanego tekstu
                if (sb.length > maxNeedle * 1.7f + 12) break
                val candidate = sb.toString()
                val score = needles.maxOf { TextSimilarity.diceNormalized(it, candidate) }
                if (score < threshold) continue
                val adjusted = score - proximityPenalty(box, near)
                if (adjusted > bestAdjusted) {
                    bestAdjusted = adjusted
                    best = LocatorMatch(box, score)
                }
            }
        }
        return best
    }

    private fun proximityPenalty(box: Box, near: Box?): Float {
        if (near == null) return 0f
        val dx = (box.left + box.right) / 2 - (near.left + near.right) / 2
        val dy = (box.top + box.bottom) / 2 - (near.top + near.bottom) / 2
        return proximityWeight * min(1f, hypot(dx, dy) / proximityScalePx)
    }
}
