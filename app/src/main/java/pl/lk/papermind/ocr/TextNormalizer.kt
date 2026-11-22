package pl.lk.papermind.ocr

import java.text.Normalizer

/**
 * Normalizacja tekstu OCR na potrzeby porównań klatek, id pytań i dopasowań słów kluczowych.
 * Czysta logika JVM (bez Androida) — testowana jednostkowo.
 */
internal object TextNormalizer {
    private val DIACRITICS = Regex("\\p{Mn}+")
    private val WHITESPACE = Regex("\\s+")

    /** lowercase + usunięcie znaków diakrytycznych (ł→l, ą→a …); zachowuje spacje i interpunkcję. */
    fun fold(s: String): String {
        val d = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        return DIACRITICS.replace(d, "")
            .replace('ł', 'l')
            .replace('Ł', 'l')
            .replace('đ', 'd')
            .replace('Đ', 'd')
    }

    fun normalizeSpace(s: String): String = WHITESPACE.replace(s, " ").trim()

    /** fold + tylko litery i cyfry (bez białych znaków i interpunkcji) — wejście do hasha id. */
    fun squash(s: String): String = buildString {
        for (c in fold(s)) if (c.isLetterOrDigit()) append(c)
    }

    /** Tokeny (litery/cyfry) po foldowaniu — do podobieństwa stron. */
    fun tokens(s: String): Set<String> {
        val out = HashSet<String>()
        val cur = StringBuilder()
        for (c in fold(s)) {
            if (c.isLetterOrDigit()) {
                cur.append(c)
            } else if (cur.isNotEmpty()) {
                out += cur.toString()
                cur.setLength(0)
            }
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val (small, large) = if (a.size <= b.size) a to b else b to a
        val inter = small.count { it in large }
        return inter.toDouble() / (a.size + b.size - inter)
    }

    /** Znormalizowana odległość Levenshteina → podobieństwo 0..1. */
    fun levenshteinSimilarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val max = maxOf(a.length, b.length)
        if (max == 0) return 1.0
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev
            prev = cur
            cur = t
        }
        return 1.0 - prev[b.length].toDouble() / max
    }

    /**
     * Podobieństwo dwóch tekstów OCR (0..1): średnia Jaccarda tokenów i znormalizowanego Levenshteina
     * na tekście bez spacji/interpunkcji. Odporne na inne łamanie linii i drobne błędy OCR.
     */
    fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val sa = squash(a)
        val sb = squash(b)
        if (sa.isEmpty() || sb.isEmpty()) return 0.0
        val j = jaccard(tokens(a), tokens(b))
        val l = levenshteinSimilarity(sa, sb)
        return 0.5 * j + 0.5 * l
    }
}
