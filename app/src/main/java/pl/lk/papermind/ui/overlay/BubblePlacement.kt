package pl.lk.papermind.ui.overlay

import pl.lk.papermind.model.Box

data class BubblePos(val x: Float, val y: Float)

/** Czysta logika ustawiania dymka przy boxie pytania tak, by nie wychodził poza widoczny obszar. */
object BubblePlacement {
    /**
     * Preferuje miejsce pod [anchor], potem nad nim; na końcu przycina do obszaru.
     * [safeTop] — górna granica obszaru (np. pod paskiem statusu / wycięciem aparatu), domyślnie 0f.
     * [safeBottom] — dolna granica obszaru (np. nad zwiniętym panelem), domyślnie [viewH].
     */
    fun place(
        anchor: Box,
        w: Float,
        h: Float,
        viewW: Float,
        viewH: Float,
        margin: Float = 8f,
        gap: Float = 6f,
        safeTop: Float = 0f,
        safeBottom: Float = viewH,
    ): BubblePos {
        val topLimit = safeTop + margin
        val bottomLimit = minOf(safeBottom, viewH) - margin
        val below = anchor.bottom + gap
        val above = anchor.top - gap - h
        val y = when {
            below + h <= bottomLimit -> maxOf(below, topLimit)
            above >= topLimit -> above
            else -> below.coerceIn(topLimit, maxOf(topLimit, bottomLimit - h))
        }
        val maxX = viewW - margin - w
        val x = if (maxX < margin) margin else anchor.left.coerceIn(margin, maxX)
        return BubblePos(x, y)
    }
}
