package pl.lk.papermind.ocr

import java.text.Normalizer
import kotlin.math.abs

/**
 * Normalizacja tekstu OCR na potrzeby porównań klatek, id pytań i dopasowań słów kluczowych.
 * Czysta logika JVM (bez Androida) — testowana jednostkowo.
 *
 * Wydajność: [similarity]/[similarityAtLeast] liczą Jaccarda na tokenach i odrzucają oczywiście różne teksty
 * bez uruchamiania Levenshteina; gdy Levenshtein jest potrzebny, pracuje na pasmie wokół diagonali
 * z wczesnym przerwaniem (Ukkonen), a wspólny prefiks/sufiks jest odcinany przed obliczeniami.
 */
internal object TextNormalizer {
    private val DIACRITICS = Regex("\\p{Mn}+")
    private val WHITESPACE = Regex("\\s+")

    /** Poniżej tego Jaccarda teksty są na tyle różne, że pełne podobieństwo i tak nie przekroczy progów decyzyjnych. */
    private const val FAST_REJECT_JACCARD = 0.50

    /** [similarity] jest dokładne dla wartości ≥ tej liczby (progi stabilizatora: 0.55 / 0.90 / 0.985). */
    const val MIN_EXACT_SIMILARITY = 0.55

    /** Początkowa szerokość pasma Levenshteina (poszerzana, gdy odległość okazuje się większa). */
    private const val START_BAND = 32

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
    fun squash(s: String): String = analyze(s).squashed

    /** Tokeny (litery/cyfry) po foldowaniu — do podobieństwa stron. */
    fun tokens(s: String): Set<String> = analyze(s).tokens

    /**
     * Kanoniczna postać tekstu dla stabilnego id: bez diakrytyków, tylko litery/cyfry,
     * z ujednoliconymi znakami mylonymi przez OCR (0/o, 1/l/i, 5/s, 8/b, rn/m).
     */
    fun ocrCanonical(s: String): String {
        val folded = fold(s)
        val sb = StringBuilder(folded.length)
        for (c in folded) {
            if (!c.isLetterOrDigit()) continue
            sb.append(
                when (c) {
                    '0' -> 'o'
                    '1' -> 'l'
                    'i' -> 'l'
                    '5' -> 's'
                    '8' -> 'b'
                    else -> c
                },
            )
        }
        return if (sb.indexOf("rn") < 0) sb.toString() else sb.toString().replace("rn", "m")
    }

    fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val (small, large) = if (a.size <= b.size) a to b else b to a
        val inter = small.count { it in large }
        return inter.toDouble() / (a.size + b.size - inter)
    }

    /** Znormalizowana odległość Levenshteina → podobieństwo 0..1 (dokładna wartość). */
    fun levenshteinSimilarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val max = maxOf(a.length, b.length)
        if (max == 0) return 1.0
        val d = editDistanceAdaptive(a, b, max)
        return 1.0 - d.toDouble() / max
    }

    /**
     * Podobieństwo dwóch tekstów OCR (0..1): średnia Jaccarda tokenów i znormalizowanego Levenshteina
     * na tekście bez spacji/interpunkcji. Odporne na inne łamanie linii i drobne błędy OCR.
     *
     * Wartość jest dokładna dla wyników ≥ [MIN_EXACT_SIMILARITY]; poniżej zwracana jest tania wartość
     * orientacyjna (< 0.55), bo żaden próg decyzyjny stabilizatora nie leży niżej. Do podejmowania decyzji
     * progowych używać [similarityAtLeast] — liczy dokładnie dla dowolnego progu i taniej.
     */
    fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val fa = analyze(a)
        val fb = analyze(b)
        if (fa.squashed.isEmpty() || fb.squashed.isEmpty()) return 0.0
        val j = jaccard(fa.tokens, fb.tokens)
        if (j < FAST_REJECT_JACCARD) return j * 0.5

        val maxLen = maxOf(fa.squashed.length, fb.squashed.length)
        val neededLev = (2.0 * MIN_EXACT_SIMILARITY - j).coerceAtLeast(0.0)
        val maxEdits = ((1.0 - neededLev) * maxLen + 1e-6).toInt()
        val distance = editDistanceAdaptive(fa.squashed, fb.squashed, maxEdits)
        if (distance < 0) return j * 0.5 // wynik < MIN_EXACT_SIMILARITY
        val lev = 1.0 - distance.toDouble() / maxLen
        return 0.5 * (j + lev)
    }

    /**
     * Dokładna odpowiedź na pytanie „czy podobieństwo ≥ [threshold]?” (dla progów ≥ [MIN_EXACT_SIMILARITY]
     * zgodna z [similarity]). Liczona taniej: pasmo Levenshteina dobierane jest do progu (dla 0.985 to
     * ~1,5% długości tekstu), z szybkim odrzuceniem po Jaccardzie, gdy wynik jest przesądzony matematycznie.
     */
    fun similarityAtLeast(a: String, b: String, threshold: Double): Boolean {
        if (a == b) return true
        if (threshold <= 0.0) return true
        if (threshold > 1.0) return false
        val fa = analyze(a)
        val fb = analyze(b)
        if (fa.squashed.isEmpty() || fb.squashed.isEmpty()) return false
        // avg = (j + lev) / 2 ≥ t  ⟺  lev ≥ 2t − j
        val j = jaccard(fa.tokens, fb.tokens)
        val neededLev = 2.0 * threshold - j
        if (neededLev > 1.0) return false
        if (neededLev <= 0.0) return true

        val maxLen = maxOf(fa.squashed.length, fb.squashed.length)
        val maxEdits = ((1.0 - neededLev) * maxLen + 1e-6).toInt()
        return editDistanceAdaptive(fa.squashed, fb.squashed, maxEdits) >= 0
    }

    // ------------------------------------------------------------------ wnętrze

    private class Analysis(val squashed: String, val tokens: Set<String>)

    /** Jedno foldowanie + jedno przejście: daje i sklejony tekst, i zbiór tokenów. */
    private fun analyze(s: String): Analysis {
        val folded = fold(s)
        val squashed = StringBuilder(folded.length)
        val tokens = HashSet<String>(folded.length / 4 + 4)
        val current = StringBuilder(16)
        for (c in folded) {
            if (c.isLetterOrDigit()) {
                squashed.append(c)
                current.append(c)
            } else if (current.isNotEmpty()) {
                tokens += current.toString()
                current.setLength(0)
            }
        }
        if (current.isNotEmpty()) tokens += current.toString()
        return Analysis(squashed.toString(), tokens)
    }

    /**
     * Jak [editDistanceWithin], ale zaczyna od wąskiego pasma i poszerza je (×4) tylko gdy odległość
     * faktycznie przekracza dotychczasowy limit. Dzięki temu typowy, prawie identyczny tekst OCR
     * (kilka pomyłek) liczy się w pasmie kilkudziesięciu znaków, a nie na całej macierzy.
     */
    private fun editDistanceAdaptive(a: String, b: String, maxEdits: Int): Int {
        if (maxEdits < 0) return -1
        if (maxEdits <= START_BAND) return editDistanceWithin(a, b, maxEdits)
        var band = START_BAND
        while (true) {
            val d = editDistanceWithin(a, b, band)
            if (d >= 0) return d
            if (band >= maxEdits) return -1
            band = minOf(maxEdits, band shl 2)
        }
    }

    /**
     * Odległość Levenshteina, jeśli ≤ [maxEdits]; inaczej -1.
     *
     * - wspólny prefiks/sufiks odcinany przed obliczeniami (szum OCR zwykle jest lokalny),
     * - pasmo Ukkonena (tylko komórki |i−j| ≤ maxEdits) i przerwanie, gdy cały wiersz przekracza limit.
     */
    private fun editDistanceWithin(a: String, b: String, maxEdits: Int): Int {
        if (maxEdits < 0) return -1
        if (a == b) return 0
        val aLen = a.length
        val bLen = b.length
        if (abs(aLen - bLen) > maxEdits) return -1

        var start = 0
        while (start < aLen && start < bLen && a[start] == b[start]) start++
        var aEnd = aLen
        var bEnd = bLen
        while (aEnd > start && bEnd > start && a[aEnd - 1] == b[bEnd - 1]) {
            aEnd--
            bEnd--
        }
        val n = aEnd - start
        val m = bEnd - start
        if (n == 0) return if (m <= maxEdits) m else -1
        if (m == 0) return if (n <= maxEdits) n else -1
        if (abs(n - m) > maxEdits) return -1

        val band = minOf(maxEdits, maxOf(n, m))
        val inf = band + 1
        var prev = IntArray(m + 1) { if (it <= band) it else inf }
        var cur = IntArray(m + 1) { inf }

        for (i in 1..n) {
            val jFrom = maxOf(1, i - band)
            val jTo = minOf(m, i + band)
            if (jFrom > jTo) return -1
            cur[0] = if (i <= band) i else inf
            if (jFrom > 1) cur[jFrom - 1] = inf
            var rowMin = cur[0]
            for (j in jFrom..jTo) {
                val cost = if (a[start + i - 1] == b[start + j - 1]) 0 else 1
                var v = prev[j - 1] + cost
                val delete = prev[j] + 1
                if (delete < v) v = delete
                val insert = cur[j - 1] + 1
                if (insert < v) v = insert
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            if (jTo < m) cur[jTo + 1] = inf
            if (rowMin > band) return -1
            val tmp = prev
            prev = cur
            cur = tmp
        }
        val distance = prev[m]
        return if (distance <= band) distance else -1
    }
}
