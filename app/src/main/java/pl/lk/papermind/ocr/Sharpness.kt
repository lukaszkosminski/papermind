package pl.lk.papermind.ocr

import java.nio.ByteBuffer

/** Miara ostrości klatki i bramka wybierająca najostrzejsze klatki z ciągłego strumienia kamery. */
object Sharpness {
    /**
     * Średni moduł gradientu płaszczyzny Y (poziomo + pionowo) w środkowym kadrze, próbkowany rzadko
     * (kilka ms na klatkę 1080p). Rozmycie ruchu/ostrości obniża wartość. Brak zmiany jasności → 0.
     */
    fun score(luma: ByteBuffer, width: Int, height: Int, rowStride: Int): Double {
        if (width < 8 || height < 8 || rowStride < width) return 0.0
        val buf = luma.duplicate()
        val limit = buf.limit()
        val x0 = width / 5
        val x1 = width - width / 5 - 1
        val y0 = height / 5
        val y1 = height - height / 5 - 1
        var sum = 0L
        var n = 0
        var y = y0
        while (y < y1) {
            val row = y * rowStride
            if (row + rowStride + x1 >= limit) break
            var x = x0
            while (x < x1) {
                val i = row + x
                val p = buf.get(i).toInt() and 0xFF
                sum += kotlin.math.abs(p - (buf.get(i + 1).toInt() and 0xFF)) +
                    kotlin.math.abs(p - (buf.get(i + rowStride).toInt() and 0xFF))
                n++
                x += 3
            }
            y += 3
        }
        return if (n == 0) 0.0 else sum.toDouble() / n
    }

    /**
     * Przepuszcza klatkę do OCR tylko, gdy jej ostrość ≥ [ratio] × najlepszej z ostatnich [windowMs] ms.
     * Dzięki temu klatki rozmyte ruchem ręki są pomijane, a OCR dostaje ostre ujęcia.
     */
    class Gate(
        private val windowMs: Long = 1_000L,
        private val ratio: Double = 0.8,
    ) {
        private val recent = java.util.ArrayDeque<Pair<Long, Double>>()

        @Synchronized
        fun accept(score: Double, nowMs: Long): Boolean {
            recent.addLast(nowMs to score)
            while (recent.isNotEmpty() && nowMs - recent.first.first > windowMs) recent.removeFirst()
            val best = recent.maxOf { it.second }
            return score >= best * ratio
        }

        @Synchronized
        fun reset() = recent.clear()
    }
}
