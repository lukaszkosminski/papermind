package pl.lk.papermind.ui.tracking

import pl.lk.papermind.model.Box
import pl.lk.papermind.model.DetectedQuestion
import pl.lk.papermind.model.OcrFrame

/** Box w aktualnych współrzędnych widoku + przezroczystość (0..1) do wygaszania zaginionych elementów. */
data class Tracked(val box: Box, val alpha: Float = 1f)

data class TrackedQuestion(
    val questionId: String,
    val question: Tracked?,
    /** etykieta opcji -> pozycja w bieżącej klatce (tylko znalezione). */
    val options: Map<String, Tracked>,
)

/**
 * Śledzi pytania i opcje z ostatniej stabilnej strony w kolejnych klatkach OCR (kamera mogła się ruszyć):
 * dopasowuje po tekście, wygładza ruch, a gdy tekst zniknie z kadru — wygasza po [holdMs]+[fadeMs].
 * Stan wewnętrzny — używać z jednego wątku/korutyny naraz.
 */
class OverlayTracker(
    private val locator: TextLocator = TextLocator(),
    private val holdMs: Long = 400,
    private val fadeMs: Long = 400,
    private val smoothing: Float = 0.6f,
) {
    private class Track(var box: Box, var lastSeenMs: Long)

    private val tracks = HashMap<String, Track>()

    fun reset() = tracks.clear()

    fun update(questions: List<DetectedQuestion>, frame: OcrFrame): List<TrackedQuestion> {
        val prepared = PreparedFrame(frame)
        val now = frame.timestampMs
        val liveKeys = HashSet<String>()

        val result = questions.map { q ->
            val qKey = "q:${q.id}"
            liveKeys += qKey
            val qTracked = resolve(
                key = qKey,
                alternatives = listOfNotNull(q.text, q.number?.let { "$it ${q.text}" }),
                fallbackNear = q.box,
                prepared = prepared,
                now = now,
            )
            val options = LinkedHashMap<String, Tracked>()
            for (o in q.options) {
                val oKey = "o:${q.id}:${o.label}"
                liveKeys += oKey
                resolve(oKey, listOf(o.text, "${o.label} ${o.text}"), o.box, prepared, now)
                    ?.let { options[o.label] = it }
            }
            TrackedQuestion(q.id, qTracked, options)
        }
        tracks.keys.retainAll(liveKeys)
        return result
    }

    private fun resolve(
        key: String,
        alternatives: List<String>,
        fallbackNear: Box,
        prepared: PreparedFrame,
        now: Long,
    ): Tracked? {
        val track = tracks[key]
        val match = locator.locate(alternatives, prepared, track?.box ?: fallbackNear)
        if (match != null) {
            if (track == null) {
                tracks[key] = Track(match.box, now)
            } else {
                track.box = smooth(track.box, match.box)
                track.lastSeenMs = now
            }
            return Tracked(tracks.getValue(key).box, 1f)
        }
        track ?: return null
        val age = now - track.lastSeenMs
        if (age < 0 || age <= holdMs) return Tracked(track.box, 1f)
        val alpha = 1f - (age - holdMs).toFloat() / fadeMs
        if (alpha <= 0f) {
            tracks.remove(key)
            return null
        }
        return Tracked(track.box, alpha)
    }

    private fun smooth(prev: Box, target: Box): Box {
        // przy dużym skoku (inna kopia tekstu / szybki ruch) nie wygładzaj — przeskocz
        val jump = maxOf(
            kotlin.math.abs(target.left - prev.left),
            kotlin.math.abs(target.top - prev.top),
        )
        if (jump > maxOf(prev.width, prev.height, 80f) * 1.5f) return target
        fun l(a: Float, b: Float) = a + (b - a) * smoothing
        return Box(l(prev.left, target.left), l(prev.top, target.top), l(prev.right, target.right), l(prev.bottom, target.bottom))
    }
}
