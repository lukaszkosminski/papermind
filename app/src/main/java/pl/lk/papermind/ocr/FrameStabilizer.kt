package pl.lk.papermind.ocr

import pl.lk.papermind.model.OcrFrame
import pl.lk.papermind.model.ParsedPage

/**
 * Stabilizator klatek: emituje [ParsedPage] dopiero gdy treść OCR przestaje się zmieniać przez `stabilityMs`
 * (kamera trzymana nieruchomo) ORAZ różni się istotnie od ostatnio wyemitowanej strony (nie spamujemy AI tą samą kartką).
 *
 * Ruch / rozmycie: gdy kolejne klatki różnią się mocno albo pewność OCR jest bardzo niska, okno stabilności
 * jest zerowane i nic nie jest emitowane.
 *
 * Klasa jest czysta (bez Androida): czas wstrzykiwany przez [clock] ułatwia testy.
 */
class FrameStabilizer(
    private val parser: QuestionParser,
    private val stabilityMs: () -> Long = { DEFAULT_STABILITY_MS },
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val config: Config = Config(),
) {
    constructor(
        parser: QuestionParser,
        stabilityMs: Long,
        clock: () -> Long = { System.nanoTime() / 1_000_000 },
        config: Config = Config(),
    ) : this(parser, { stabilityMs }, clock, config)

    data class Config(
        /** Klatki o podobieństwie ≥ tej wartości uznajemy za "ten sam tekst". */
        val stableSimilarity: Double = 0.90,
        /** Poniżej tego podobieństwa uznajemy, że kamera się ruszyła / to inna scena. */
        val motionSimilarity: Double = 0.55,
        /** Minimalna średnia pewność OCR (gdy ML Kit ją podaje); niżej = rozmycie. */
        val minConfidence: Float = 0.15f,
        /** Minimalny odstęp między emisjami (chroni przed migotaniem OCR). */
        val emitCooldownMs: Long = 600L,
        /** Strona o takim podobieństwie do ostatnio wysłanej uznawana jest za duplikat (szum OCR). */
        val duplicateSimilarity: Double = 0.985,
    )

    private var prevText: String? = null
    private var anchorText: String? = null
    private var anchorSince: Long = 0L
    private var lastEmitAt: Long? = null
    private var lastEmittedIds: Set<String>? = null
    private var lastEmittedText: String? = null

    /** Przetwarza klatkę; zwraca stronę do wysłania do AI albo null (jeszcze nie stabilna / ta sama strona). */
    fun onFrame(frame: OcrFrame): ParsedPage? {
        val text = frame.fullText
        val now = clock()

        if (frame.lines.isEmpty() || isBlurry(frame)) {
            resetWindow()
            prevText = text
            return null
        }

        val prev = prevText
        prevText = text
        if (prev != null && TextNormalizer.similarity(prev, text) < config.motionSimilarity) {
            // duża zmiana kadru — wymagamy nowego, pełnego okna stabilności
            anchorText = null
        }

        val anchor = anchorText
        if (anchor == null || TextNormalizer.similarity(anchor, text) < config.stableSimilarity) {
            anchorText = text
            anchorSince = now
            return null
        }
        if (now - anchorSince < stabilityMs().coerceAtLeast(0L)) return null

        val questions = parser.parse(frame)
        if (questions.isEmpty()) {
            // brak pytań (np. sam nagłówek) — sprawdzamy ponownie dopiero po kolejnym oknie stabilności
            anchorSince = now
            return null
        }

        val ids = questions.map { it.id }.toSet()
        if (ids == lastEmittedIds) {
            anchorSince = now
            return null
        }
        // ta sama strona z drobnym szumem OCR (inny id, niemal identyczny tekst) — nie spamujemy AI
        val previousText = lastEmittedText
        if (previousText != null &&
            ids.size == lastEmittedIds?.size &&
            TextNormalizer.similarity(previousText, text) >= config.duplicateSimilarity
        ) {
            anchorSince = now
            return null
        }

        val lastEmit = lastEmitAt
        if (lastEmit != null && now - lastEmit < config.emitCooldownMs) {
            anchorSince = now
            return null
        }

        lastEmittedIds = ids
        lastEmittedText = text
        lastEmitAt = now
        // po emisji wymagamy kolejnego okna stabilności (nawet jeśli kartka się nie zmieni)
        anchorText = text
        anchorSince = now
        return ParsedPage(
            timestampMs = frame.timestampMs,
            questions = questions,
            rawText = text,
        )
    }

    /** Czyści cały stan (np. po wyjściu z ekranu kamery). */
    fun reset(clearEmittedPages: Boolean = true) {
        resetWindow()
        prevText = null
        if (clearEmittedPages) {
            lastEmittedIds = null
            lastEmittedText = null
            lastEmitAt = null
        }
    }

    private fun resetWindow() {
        anchorText = null
        anchorSince = 0L
    }

    /** Rozmycie: ML Kit zwykle podaje pewność linii; przy bardzo niskiej średniej nie ufamy tekstowi. */
    private fun isBlurry(frame: OcrFrame): Boolean {
        val known = frame.lines.mapNotNull { it.confidence }.filter { it > 0f }
        if (known.size < 3) return false
        return known.average() < config.minConfidence
    }

    companion object {
        const val DEFAULT_STABILITY_MS = 700L
    }
}
