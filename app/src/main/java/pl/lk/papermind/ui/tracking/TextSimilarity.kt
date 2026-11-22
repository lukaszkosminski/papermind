package pl.lk.papermind.ui.tracking

import java.text.Normalizer

/** Czysta logika porównywania tekstu z OCR (odporna na wielkość liter, interpunkcję i polskie znaki). */
object TextSimilarity {

    /** Małe litery, bez znaków diakrytycznych i interpunkcji, pojedyncze spacje. */
    fun normalize(s: String): String {
        val decomposed = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        val sb = StringBuilder(decomposed.length)
        var lastSpace = true
        for (raw in decomposed) {
            val c = if (raw == 'ł') 'l' else raw
            if (Character.getType(c) == Character.NON_SPACING_MARK.toInt()) continue
            if (c.isLetterOrDigit()) {
                sb.append(c)
                lastSpace = false
            } else if (!lastSpace) {
                sb.append(' ')
                lastSpace = true
            }
        }
        return sb.toString().trimEnd()
    }

    /** Współczynnik Dice'a na bigramach znormalizowanych tekstów, 0..1. */
    fun similarity(a: String, b: String): Float = diceNormalized(normalize(a), normalize(b))

    /** Jak [similarity], ale dla tekstów już znormalizowanych przez [normalize]. */
    fun diceNormalized(a: String, b: String): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        if (a == b) return 1f
        if (a.length < 2 || b.length < 2) return 0f
        val counts = HashMap<Int, Int>(a.length * 2)
        for (i in 0 until a.length - 1) counts.merge(key(a, i), 1, Int::plus)
        var common = 0
        for (i in 0 until b.length - 1) {
            val k = key(b, i)
            val n = counts[k] ?: continue
            if (n > 0) {
                counts[k] = n - 1
                common++
            }
        }
        return 2f * common / ((a.length - 1) + (b.length - 1))
    }

    private fun key(s: String, i: Int) = (s[i].code shl 16) or s[i + 1].code
}
